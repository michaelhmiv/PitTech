package com.pittech.devices

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Automatic, observational Mongoose OS probe.
 *
 * BLE characteristic/descriptor writes here are transport framing only:
 * enabling notifications and carrying explicitly allowlisted read-only RPCs.
 * No controller-setting, MCU-control, Wi-Fi credential, reboot, OTA, file-write,
 * or other mutating RPC is ever issued by this class.
 */
internal class MongooseBleRpcProbe(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var activeSession: Session? = null

    fun probe(
        address: String,
        inspection: BluetoothGattInspectionReport,
        onProgress: (String) -> Unit,
        onFinished: (ControllerProbeReport) -> Unit,
    ) {
        closeSilently()
        val protocols = ControllerProtocolDetector.detect(inspection)
        val session = Session(
            address = address,
            startedAtMillis = System.currentTimeMillis(),
            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
            protocols = protocols,
            onProgress = onProgress,
            onFinished = onFinished,
        )
        activeSession = session

        if (ControllerProtocolFamily.MONGOOSE_RPC !in protocols) {
            record(session, "No Mongoose OS RPC GATT service was detected; protocol-specific RPC probing skipped.")
            finish(session, "GATT fingerprint collected; no supported RPC transport detected.")
            return
        }

        record(session, "Mongoose OS RPC GATT service detected; starting automatic observational RPC probe.")
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            finish(session, "Bluetooth adapter unavailable.")
            return
        }

        try {
            val device = adapter.getRemoteDevice(address)
            val callback = callbackFor(session)
            session.callback = callback
            session.overallTimeout = Runnable {
                if (isActive(session)) {
                    record(session, "Overall protocol-probe timeout reached.")
                    finish(session, "Protocol probe timed out; partial evidence retained.")
                }
            }
            handler.postDelayed(session.overallTimeout!!, OVERALL_TIMEOUT_MILLIS)

            @Suppress("DEPRECATION")
            val gatt = device.connectGatt(
                appContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
            session.gatt = gatt
            if (gatt == null) finish(session, "Android did not create a GATT client for the protocol probe.")
        } catch (error: SecurityException) {
            record(session, "Bluetooth permission failure: " + error.javaClass.simpleName)
            finish(session, "Bluetooth connection permission denied.")
        } catch (error: RuntimeException) {
            record(session, "Protocol-probe connection exception: " + safeMessage(error))
            finish(session, "Could not start the protocol probe.")
        }
    }

    fun closeSilently() {
        val session = activeSession ?: return
        session.finished = true
        removeTimeouts(session)
        activeSession = null
        closeGatt(session.gatt)
    }

    private fun callbackFor(session: Session) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isActive(session)) return
            record(session, "RPC GATT connection callback status=$status state=$newState.")
            when {
                status != BluetoothGatt.GATT_SUCCESS -> finish(session, "RPC GATT connection failed with status $status.")
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    val accepted = runCatching { gatt.discoverServices() }.getOrDefault(false)
                    record(session, "RPC discoverServices accepted=$accepted.")
                    if (!accepted) finish(session, "Android did not start RPC service discovery.")
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> finish(
                    session,
                    "Controller disconnected during protocol probing; partial evidence retained.",
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isActive(session)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(session, "RPC service discovery failed with status $status.")
                return
            }

            val rpcService = gatt.getService(UUID.fromString(ControllerProtocolDetector.MONGOOSE_RPC_SERVICE))
            session.dataChar = rpcService?.getCharacteristic(UUID.fromString(ControllerProtocolDetector.MONGOOSE_RPC_DATA))
            session.txCtlChar = rpcService?.getCharacteristic(UUID.fromString(ControllerProtocolDetector.MONGOOSE_RPC_TX_CTL))
            session.rxCtlChar = rpcService?.getCharacteristic(UUID.fromString(ControllerProtocolDetector.MONGOOSE_RPC_RX_CTL))

            val debugService = gatt.getService(UUID.fromString(ControllerProtocolDetector.MONGOOSE_DEBUG_SERVICE))
            session.debugChar = debugService?.getCharacteristic(UUID.fromString(ControllerProtocolDetector.MONGOOSE_DEBUG_LOG))

            if (session.dataChar == null || session.txCtlChar == null || session.rxCtlChar == null) {
                finish(session, "Mongoose RPC service was present but required characteristics were incomplete.")
                return
            }

            record(session, "Required Mongoose RPC characteristics resolved.")
            enableNotifications(session, gatt, session.rxCtlChar!!, NotificationPurpose.RPC_RX)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handleDescriptorWrite(session, gatt, descriptor, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicWrite(session, gatt, characteristic, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(session, gatt, characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristicChanged(session, gatt, characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(session, gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicRead(session, gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }
    }

    private fun enableNotifications(
        session: Session,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        purpose: NotificationPurpose,
    ) {
        session.notificationPurpose = purpose
        val localEnabled = runCatching { gatt.setCharacteristicNotification(characteristic, true) }.getOrDefault(false)
        if (!localEnabled) {
            if (purpose == NotificationPurpose.RPC_RX) {
                finish(session, "Android rejected RPC notification registration.")
            } else {
                record(session, "Debug notification registration was not accepted; continuing without debug stream.")
                beginRpcSequence(session, gatt)
            }
            return
        }

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            record(session, "CCCD was not exposed for ${purpose.name}; continuing with local notification registration only.")
            if (purpose == NotificationPurpose.RPC_RX) enableDebugOrBegin(session, gatt) else beginRpcSequence(session, gatt)
            return
        }
        val payload = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (!writeDescriptorCompat(gatt, cccd, payload)) {
            if (purpose == NotificationPurpose.RPC_RX) {
                finish(session, "Android rejected the RPC notification descriptor write.")
            } else {
                record(session, "Debug CCCD write was rejected; continuing without debug stream.")
                beginRpcSequence(session, gatt)
            }
        }
    }

    private fun handleDescriptorWrite(
        session: Session,
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (!isActive(session) || descriptor.uuid != CCCD_UUID) return
        val purpose = session.notificationPurpose
        record(session, "Notification descriptor write for ${purpose?.name ?: "unknown"} status=$status.")
        if (status != BluetoothGatt.GATT_SUCCESS && purpose == NotificationPurpose.RPC_RX) {
            finish(session, "Could not enable RPC response notifications; GATT status $status.")
            return
        }
        if (purpose == NotificationPurpose.RPC_RX) enableDebugOrBegin(session, gatt) else beginRpcSequence(session, gatt)
    }

    private fun enableDebugOrBegin(session: Session, gatt: BluetoothGatt) {
        val debug = session.debugChar
        if (debug != null) {
            record(session, "Mongoose debug characteristic detected; enabling bounded passive observation.")
            enableNotifications(session, gatt, debug, NotificationPurpose.DEBUG)
        } else {
            beginRpcSequence(session, gatt)
        }
    }

    private fun beginRpcSequence(session: Session, gatt: BluetoothGatt) {
        if (session.startedCommands) return
        session.startedCommands = true
        session.queue.add(SafeRpcRequest("RPC transport ping", "RPC.Ping"))
        session.queue.add(SafeRpcRequest("RPC method inventory", "RPC.List"))
        sendNext(session, gatt)
    }

    private fun sendNext(session: Session, gatt: BluetoothGatt) {
        if (!isActive(session)) return
        session.commandTimeout?.let(handler::removeCallbacks)
        session.commandTimeout = null
        session.activeRequest = session.queue.pollFirst()
        val request = session.activeRequest
        if (request == null) {
            finish(session, "Automatic observational controller interrogation completed.")
            return
        }

        if (!ControllerProbePolicy.canAutoExecute(request.method, request.params)) {
            session.observations += RpcProbeObservation(
                label = request.label,
                method = request.method,
                state = ProbeStepState.SKIPPED_SAFETY,
                error = "Method or parameters are not on the explicit automatic-probe allowlist.",
            )
            record(session, "Safety policy skipped ${request.method}.")
            sendNext(session, gatt)
            return
        }

        session.requestId = (session.requestId + 1) and 2047
        val payload = JSONObject()
            .put("id", session.requestId)
            .put("method", request.method)
            .put("params", request.params)
            .toString()
            .toByteArray(Charsets.UTF_8)
        session.pendingPayload = payload
        session.payloadOffset = 0
        session.writePhase = WritePhase.TX_LENGTH
        session.expectedResponseLength = null
        session.responseBytes.clear()

        record(session, "Sending allowlisted observational RPC ${request.label} (${request.method}).")
        if (!writeCharacteristicCompat(gatt, session.txCtlChar!!, encodeLength(payload.size))) {
            recordFailureAndContinue(session, gatt, ProbeStepState.TRANSPORT_ERROR, "TX control write was rejected by Android.")
            return
        }

        val timeout = Runnable {
            if (isActive(session) && session.activeRequest === request) {
                recordFailureAndContinue(session, gatt, ProbeStepState.TIMEOUT, "No RPC response before timeout.")
            }
        }
        session.commandTimeout = timeout
        handler.postDelayed(timeout, COMMAND_TIMEOUT_MILLIS)
    }

    private fun handleCharacteristicWrite(
        session: Session,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (!isActive(session)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recordFailureAndContinue(session, gatt, ProbeStepState.TRANSPORT_ERROR, "Characteristic write failed with GATT status $status.")
            return
        }

        when {
            characteristic.uuid.toString().equals(ControllerProtocolDetector.MONGOOSE_RPC_TX_CTL, ignoreCase = true) &&
                session.writePhase == WritePhase.TX_LENGTH -> {
                session.writePhase = WritePhase.DATA
                writeNextPayloadChunk(session, gatt)
            }
            characteristic.uuid.toString().equals(ControllerProtocolDetector.MONGOOSE_RPC_DATA, ignoreCase = true) &&
                session.writePhase == WritePhase.DATA -> writeNextPayloadChunk(session, gatt)
        }
    }

    private fun writeNextPayloadChunk(session: Session, gatt: BluetoothGatt) {
        val payload = session.pendingPayload
        if (session.payloadOffset >= payload.size) {
            session.writePhase = WritePhase.WAITING_RESPONSE
            return
        }
        val end = minOf(payload.size, session.payloadOffset + BLE_CHUNK_BYTES)
        val chunk = payload.copyOfRange(session.payloadOffset, end)
        session.payloadOffset = end
        if (!writeCharacteristicCompat(gatt, session.dataChar!!, chunk)) {
            recordFailureAndContinue(session, gatt, ProbeStepState.TRANSPORT_ERROR, "RPC data write was rejected by Android.")
        }
    }

    private fun handleCharacteristicChanged(
        session: Session,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (!isActive(session)) return
        val uuid = characteristic.uuid.toString()
        when {
            uuid.equals(ControllerProtocolDetector.MONGOOSE_DEBUG_LOG, ignoreCase = true) -> {
                if (session.debugMessages.size < MAX_DEBUG_MESSAGES) {
                    session.debugMessages += String(value, Charsets.UTF_8).take(MAX_DEBUG_MESSAGE_CHARS)
                } else {
                    session.omittedDebugMessages += 1
                }
            }
            uuid.equals(ControllerProtocolDetector.MONGOOSE_RPC_RX_CTL, ignoreCase = true) -> {
                if (value.size < 4) {
                    recordFailureAndContinue(session, gatt, ProbeStepState.PROTOCOL_ERROR, "RPC response-length notification was shorter than four bytes.")
                    return
                }
                val length = decodeLength(value)
                if (length !in 0..MAX_RPC_RESPONSE_BYTES) {
                    recordFailureAndContinue(session, gatt, ProbeStepState.PROTOCOL_ERROR, "RPC response announced implausible length $length.")
                    return
                }
                session.expectedResponseLength = length
                session.responseBytes.clear()
                if (length == 0) {
                    processRpcResponse(session, gatt, "{}")
                } else if (!runCatching { gatt.readCharacteristic(session.dataChar!!) }.getOrDefault(false)) {
                    recordFailureAndContinue(session, gatt, ProbeStepState.TRANSPORT_ERROR, "Android rejected the RPC response read.")
                }
            }
        }
    }

    private fun handleCharacteristicRead(
        session: Session,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (!isActive(session) ||
            !characteristic.uuid.toString().equals(ControllerProtocolDetector.MONGOOSE_RPC_DATA, ignoreCase = true)
        ) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recordFailureAndContinue(session, gatt, ProbeStepState.TRANSPORT_ERROR, "RPC response read failed with GATT status $status.")
            return
        }
        if (value.isEmpty()) {
            recordFailureAndContinue(session, gatt, ProbeStepState.PROTOCOL_ERROR, "RPC response ended before the announced length.")
            return
        }
        val expected = session.expectedResponseLength ?: return
        session.responseBytes.addAll(value.toList())
        if (session.responseBytes.size < expected) {
            if (!runCatching { gatt.readCharacteristic(session.dataChar!!) }.getOrDefault(false)) {
                recordFailureAndContinue(session, gatt, ProbeStepState.TRANSPORT_ERROR, "Android rejected a subsequent RPC response read.")
            }
            return
        }
        val raw = session.responseBytes.take(expected).toByteArray().toString(Charsets.UTF_8)
        processRpcResponse(session, gatt, raw)
    }

    private fun processRpcResponse(session: Session, gatt: BluetoothGatt, raw: String) {
        val request = session.activeRequest ?: return
        session.commandTimeout?.let(handler::removeCallbacks)
        session.commandTimeout = null

        try {
            val response = JSONObject(raw)
            val error = response.optJSONObject("error")
            if (error != null) {
                val code = error.optInt("code", Int.MIN_VALUE)
                val message = error.optString("message", "RPC error")
                val state = if (code == 401) ProbeStepState.AUTH_REQUIRED else ProbeStepState.PROTOCOL_ERROR
                session.observations += RpcProbeObservation(
                    request.label,
                    request.method,
                    state,
                    error = "code=$code; $message",
                )
                record(session, "${request.method} returned error code=$code.")
                if (request.method == "RPC.List" && code == 404) {
                    session.queue.addFirst(SafeRpcRequest("RPC method inventory (ListEx)", "RPC.ListEx"))
                }
            } else {
                val result = response.opt("result")
                val resultText = when (result) {
                    null, JSONObject.NULL -> "null"
                    else -> result.toString()
                }
                session.observations += RpcProbeObservation(
                    request.label,
                    request.method,
                    ProbeStepState.SUCCESS,
                    response = resultText.take(MAX_OBSERVATION_CHARS),
                )
                record(session, "${request.method} succeeded; responseChars=${resultText.length}.")
                when (request.method) {
                    "RPC.List", "RPC.ListEx" -> {
                        val methods = extractMethods(result)
                        if (methods.isNotEmpty() && session.rpcMethods.isEmpty()) {
                            session.rpcMethods += methods
                            enqueueInventoryDrivenReads(session)
                        }
                    }
                    "RPC.Describe" -> {
                        val name = request.params.optString("name")
                        if (name.isNotBlank()) session.rpcDescriptions[name] = resultText.take(MAX_DESCRIPTION_CHARS)
                    }
                }
            }
        } catch (error: Exception) {
            session.observations += RpcProbeObservation(
                request.label,
                request.method,
                ProbeStepState.PROTOCOL_ERROR,
                error = "Unparseable RPC response (${raw.length} chars): ${error.javaClass.simpleName}",
            )
            record(session, "Could not parse ${request.method} response.")
        }

        session.activeRequest = null
        sendNext(session, gatt)
    }

    private fun enqueueInventoryDrivenReads(session: Session) {
        val methods = session.rpcMethods.toSet()
        ControllerProbePolicy.plannedReads(methods).forEach(session.queue::addLast)
        if ("RPC.Describe" in methods) {
            ControllerProbePolicy.describeCandidates(methods).forEach { method ->
                session.queue.addLast(
                    SafeRpcRequest(
                        label = "Describe $method",
                        method = "RPC.Describe",
                        params = JSONObject().put("name", method),
                    ),
                )
            }
        }
        record(
            session,
            "RPC inventory captured ${methods.size} method(s); queued explicit safe reads and up to " +
                ControllerProbePolicy.MAX_DESCRIPTIONS + " introspection requests.",
        )
    }

    private fun extractMethods(result: Any?): List<String> {
        val methods = mutableSetOf<String>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    value.optString("name").takeIf { it.isNotBlank() }?.let(methods::add)
                    value.optJSONArray("methods")?.let(::visit)
                    value.optJSONArray("result")?.let(::visit)
                }
                is String -> if (value.contains('.')) methods += value
            }
        }
        visit(result)
        return methods.sorted()
    }

    private fun recordFailureAndContinue(
        session: Session,
        gatt: BluetoothGatt,
        state: ProbeStepState,
        error: String,
    ) {
        val request = session.activeRequest
        session.commandTimeout?.let(handler::removeCallbacks)
        session.commandTimeout = null
        if (request != null) {
            session.observations += RpcProbeObservation(request.label, request.method, state, error = error)
            record(session, "${request.method} failed: $error")
        }
        session.activeRequest = null
        sendNext(session, gatt)
    }

    private fun finish(session: Session, outcome: String) {
        if (!isActive(session)) return
        record(session, "Protocol probe finished: $outcome")
        session.finished = true
        removeTimeouts(session)
        val capabilities = linkedMapOf<String, String>()
        capabilities["Direct BLE"] = if (ControllerProtocolFamily.MONGOOSE_RPC in session.protocols) {
            if (session.observations.any { it.method == "RPC.Ping" && it.state == ProbeStepState.SUCCESS }) {
                "Mongoose RPC exchange succeeded"
            } else {
                "Mongoose RPC service detected; exchange not confirmed"
            }
        } else {
            "GATT available; known RPC transport not detected"
        }
        capabilities["Mongoose configuration GATT"] =
            if (ControllerProtocolFamily.MONGOOSE_CONFIG_GATT in session.protocols) "detected" else "not detected"
        val httpEvidence = session.observations
            .filter { it.label.contains("http", ignoreCase = true) && it.state == ProbeStepState.SUCCESS }
            .joinToString(" | ") { "${it.label}: ${it.response}" }
        capabilities["Local HTTP RPC"] = httpEvidence.ifBlank { "unknown; no safe positive configuration evidence collected" }
        capabilities["Vendor cloud relay"] = "not tested by automatic interrogation"

        val report = ControllerProbeReport(
            address = session.address,
            startedAtUtc = formatUtc(session.startedAtMillis),
            finishedAtUtc = formatUtc(System.currentTimeMillis()),
            outcome = outcome,
            protocols = session.protocols,
            rpcMethods = session.rpcMethods.distinct().sorted(),
            rpcDescriptions = session.rpcDescriptions.toSortedMap(),
            observations = session.observations.toList(),
            debugMessages = session.debugMessages.toList(),
            transportCapabilities = capabilities,
            events = session.events.toList(),
            omittedEventCount = session.omittedEvents + session.omittedDebugMessages,
        )
        val gatt = session.gatt
        session.gatt = null
        if (activeSession === session) activeSession = null
        closeGatt(gatt)
        session.onFinished(report)
    }

    private fun record(session: Session, message: String) {
        val elapsed = (SystemClock.elapsedRealtime() - session.startedAtElapsedMillis).coerceAtLeast(0)
        val entry = formatUtc(System.currentTimeMillis()) + " +" + elapsed + "ms " + message.take(MAX_EVENT_CHARS)
        if (session.events.size >= MAX_EVENTS) {
            session.events.removeAt(0)
            session.omittedEvents += 1
        }
        session.events += entry
        session.onProgress(message.take(220))
    }

    private fun isActive(session: Session) = activeSession === session && !session.finished

    private fun removeTimeouts(session: Session) {
        session.commandTimeout?.let(handler::removeCallbacks)
        session.commandTimeout = null
        session.overallTimeout?.let(handler::removeCallbacks)
        session.overallTimeout = null
    }

    private fun closeGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun encodeLength(length: Int) = byteArrayOf(
        ((length ushr 24) and 0xff).toByte(),
        ((length ushr 16) and 0xff).toByte(),
        ((length ushr 8) and 0xff).toByte(),
        (length and 0xff).toByte(),
    )

    private fun decodeLength(value: ByteArray): Int =
        ((value[0].toInt() and 0xff) shl 24) or
            ((value[1].toInt() and 0xff) shl 16) or
            ((value[2].toInt() and 0xff) shl 8) or
            (value[3].toInt() and 0xff)

    private fun safeMessage(error: Throwable): String =
        (error.javaClass.simpleName + ": " + error.message.orEmpty())
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(200)

    private fun formatUtc(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timestampMillis))

    private enum class NotificationPurpose { RPC_RX, DEBUG }
    private enum class WritePhase { IDLE, TX_LENGTH, DATA, WAITING_RESPONSE }

    private class Session(
        val address: String,
        val startedAtMillis: Long,
        val startedAtElapsedMillis: Long,
        val protocols: Set<ControllerProtocolFamily>,
        val onProgress: (String) -> Unit,
        val onFinished: (ControllerProbeReport) -> Unit,
    ) {
        var gatt: BluetoothGatt? = null
        var callback: BluetoothGattCallback? = null
        var dataChar: BluetoothGattCharacteristic? = null
        var txCtlChar: BluetoothGattCharacteristic? = null
        var rxCtlChar: BluetoothGattCharacteristic? = null
        var debugChar: BluetoothGattCharacteristic? = null
        var notificationPurpose: NotificationPurpose? = null
        var overallTimeout: Runnable? = null
        var commandTimeout: Runnable? = null
        var activeRequest: SafeRpcRequest? = null
        var requestId: Int = 0
        var pendingPayload: ByteArray = byteArrayOf()
        var payloadOffset: Int = 0
        var writePhase: WritePhase = WritePhase.IDLE
        var expectedResponseLength: Int? = null
        var startedCommands = false
        var finished = false
        var omittedEvents = 0
        var omittedDebugMessages = 0
        val responseBytes = mutableListOf<Byte>()
        val queue = ArrayDeque<SafeRpcRequest>()
        val rpcMethods = mutableListOf<String>()
        val rpcDescriptions = linkedMapOf<String, String>()
        val observations = mutableListOf<RpcProbeObservation>()
        val debugMessages = mutableListOf<String>()
        val events = mutableListOf<String>()
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val BLE_CHUNK_BYTES = 20
        const val COMMAND_TIMEOUT_MILLIS = 5_000L
        const val OVERALL_TIMEOUT_MILLIS = 120_000L
        const val MAX_RPC_RESPONSE_BYTES = 64 * 1024
        const val MAX_OBSERVATION_CHARS = 8_000
        const val MAX_DESCRIPTION_CHARS = 1_500
        const val MAX_DEBUG_MESSAGES = 40
        const val MAX_DEBUG_MESSAGE_CHARS = 600
        const val MAX_EVENTS = 220
        const val MAX_EVENT_CHARS = 350
    }
}
