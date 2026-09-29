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
import java.text.SimpleDateFormat
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
                    recordFailureAndStop(session, ProbeStepState.TIMEOUT, "Overall protocol-probe timeout reached.")
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
            if (gatt == null) {
                finish(session, "Android did not create a GATT client for the protocol probe.")
            } else {
                val timeout = Runnable {
                    if (isActive(session) && session.connected != true) {
                        recordFailureAndStop(session, ProbeStepState.TIMEOUT, "RPC GATT connection callback timed out.")
                    }
                }
                session.connectionTimeout = timeout
                handler.postDelayed(timeout, CONNECTION_TIMEOUT_MILLIS)
            }
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
        session.operations.clear()
        activeSession = null
        closeGatt(session.gatt)
    }

    private fun callbackFor(session: Session) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isActive(session)) return
            session.connected = newState == BluetoothProfile.STATE_CONNECTED
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTING) {
                session.connectionTimeout?.let(handler::removeCallbacks)
                session.connectionTimeout = null
            }
            record(session, "RPC GATT connection callback status=$status state=$newState.")
            when {
                status != BluetoothGatt.GATT_SUCCESS -> finish(session, "RPC GATT connection failed with status $status.")
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    if (!session.operations.begin(GattOperationKind.DISCOVER_SERVICES)) {
                        finish(session, "A GATT operation was already active before RPC service discovery.")
                        return
                    }
                    val accepted = runCatching { gatt.discoverServices() }.getOrDefault(false)
                    record(session, "RPC discoverServices accepted=$accepted.")
                    if (!accepted) {
                        session.operations.complete(GattOperationKind.DISCOVER_SERVICES)
                        finish(session, "Android did not start RPC service discovery.")
                    } else {
                        val timeout = Runnable {
                            if (isActive(session) && session.operations.timedOut(GattOperationKind.DISCOVER_SERVICES)) {
                                recordFailureAndStop(session, ProbeStepState.TIMEOUT, "RPC GATT service-discovery callback timed out.")
                            }
                        }
                        session.serviceDiscoveryTimeout = timeout
                        handler.postDelayed(timeout, SERVICE_DISCOVERY_TIMEOUT_MILLIS)
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> finish(
                    session,
                    "Controller disconnected during protocol probing; partial evidence retained.",
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isActive(session)) return
            session.serviceDiscoveryTimeout?.let(handler::removeCallbacks)
            session.serviceDiscoveryTimeout = null
            if (!session.operations.complete(GattOperationKind.DISCOVER_SERVICES)) {
                record(session, "Ignored unmatched RPC service-discovery callback.")
                return
            }
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
        val supportsNotifications = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotifications) {
            if (purpose == NotificationPurpose.RPC_RX) {
                finish(session, "Mongoose RPC RX-control characteristic does not support notifications or indications.")
            } else {
                record(session, "Mongoose debug characteristic has no notification or indication property; continuing without debug capture.")
                beginRpcSequence(session, gatt)
            }
            return
        }
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
            if (purpose == NotificationPurpose.RPC_RX) {
                finish(session, "CCCD was not exposed for Mongoose RPC response notifications.")
            } else {
                record(session, "CCCD was not exposed for debug notifications; continuing without debug capture.")
                beginRpcSequence(session, gatt)
            }
            return
        }
        val payload = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (!session.operations.begin(GattOperationKind.WRITE_DESCRIPTOR, cccd.uuid.toString())) {
            recordFailureAndStop(
                session,
                ProbeStepState.TRANSPORT_ERROR,
                "Another GATT operation was still in flight before notification setup.",
            )
            return
        }
        if (!writeDescriptorCompat(gatt, cccd, payload)) {
            session.operations.complete(GattOperationKind.WRITE_DESCRIPTOR, cccd.uuid.toString())
            if (purpose == NotificationPurpose.RPC_RX) {
                finish(session, "Android rejected the RPC notification descriptor write.")
            } else {
                record(session, "Debug CCCD write was rejected; continuing without debug stream.")
                beginRpcSequence(session, gatt)
            }
        } else {
            val timeout = Runnable {
                if (isActive(session) && session.notificationPurpose == purpose &&
                    session.operations.timedOut(GattOperationKind.WRITE_DESCRIPTOR, cccd.uuid.toString())
                ) {
                    session.descriptorTimeout = null
                    runCatching { gatt.setCharacteristicNotification(characteristic, false) }
                    record(session, "${purpose.name} notification descriptor callback timed out.")
                    if (purpose == NotificationPurpose.RPC_RX) {
                        finish(session, "RPC response notifications could not be enabled before timeout.")
                    } else {
                        beginRpcSequence(session, gatt)
                    }
                }
            }
            session.descriptorTimeout = timeout
            handler.postDelayed(timeout, DESCRIPTOR_WRITE_TIMEOUT_MILLIS)
        }
    }

    private fun handleDescriptorWrite(
        session: Session,
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (!isActive(session) || descriptor.uuid != CCCD_UUID) return
        if (!session.operations.complete(GattOperationKind.WRITE_DESCRIPTOR, descriptor.uuid.toString())) {
            record(session, "Ignored unmatched notification descriptor callback.")
            return
        }
        session.descriptorTimeout?.let(handler::removeCallbacks)
        session.descriptorTimeout = null
        val purpose = session.notificationPurpose
        record(session, "Notification descriptor write for ${purpose?.name ?: "unknown"} status=$status.")
        if (status != BluetoothGatt.GATT_SUCCESS && purpose == NotificationPurpose.RPC_RX) {
            finish(session, "Could not enable RPC response notifications; GATT status $status.")
            return
        }
        if (purpose == NotificationPurpose.DEBUG && status == BluetoothGatt.GATT_SUCCESS) {
            session.debugCaptureStartedAtElapsedMillis = SystemClock.elapsedRealtime()
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
        session.planner.start()
        sendNext(session, gatt)
    }

    private fun sendNext(session: Session, gatt: BluetoothGatt) {
        if (!isActive(session)) return
        session.commandTimeout?.let(handler::removeCallbacks)
        session.commandTimeout = null
        session.activeRequest = session.planner.next()
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
        val payload = MongooseRpcFraming.encodeRequest(session.requestId, request.method, request.params)
        session.pendingPayload = payload
        session.payloadOffset = 0
        session.writePhase = WritePhase.TX_LENGTH
        session.responseAssembler = null

        record(session, "Sending allowlisted observational RPC ${request.label} (${request.method}).")
        if (!writeCharacteristic(
                session,
                gatt,
                session.txCtlChar!!,
                MongooseRpcFraming.encodeLength(payload.size),
            )
        ) {
            recordFailureAndStop(session, ProbeStepState.TRANSPORT_ERROR, "TX control write was rejected by Android.")
            return
        }

        val timeout = Runnable {
            if (isActive(session) && session.activeRequest === request) {
                recordFailureAndStop(
                    session,
                    ProbeStepState.TIMEOUT,
                    "No RPC response before timeout; stopped further requests to preserve one in-flight response frame.",
                )
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
        if (!session.operations.complete(
                GattOperationKind.WRITE_CHARACTERISTIC,
                characteristic.uuid.toString(),
            )
        ) {
            record(session, "Ignored unmatched characteristic-write callback for ${characteristic.uuid}.")
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recordFailureAndStop(session, ProbeStepState.TRANSPORT_ERROR, "Characteristic write failed with GATT status $status.")
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
            if (session.responseAssembler != null && !readRpcResponseChunk(session, gatt)) {
                recordFailureAndStop(session, ProbeStepState.TRANSPORT_ERROR, "Android rejected the deferred RPC response read.")
            }
            return
        }
        val end = minOf(payload.size, session.payloadOffset + BLE_CHUNK_BYTES)
        val chunk = payload.copyOfRange(session.payloadOffset, end)
        session.payloadOffset = end
        if (!writeCharacteristic(session, gatt, session.dataChar!!, chunk)) {
            recordFailureAndStop(session, ProbeStepState.TRANSPORT_ERROR, "RPC data write was rejected by Android.")
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
                val captureStart = session.debugCaptureStartedAtElapsedMillis
                if (captureStart == null || SystemClock.elapsedRealtime() - captureStart > DEBUG_OBSERVATION_WINDOW_MILLIS) {
                    session.omittedDebugMessages += 1
                } else if (session.debugNotificationCount >= MAX_DEBUG_NOTIFICATION_COUNT) {
                    session.omittedDebugMessages += 1
                } else {
                    val message = String(
                        value,
                        0,
                        minOf(value.size, MAX_DEBUG_EVENT_BYTES),
                        Charsets.UTF_8,
                    ).take(MAX_DEBUG_MESSAGE_CHARS)
                    session.debugNotificationCount += 1
                    if (message != session.lastDebugMessage) session.debugPayloadChangeCount += 1
                    session.lastDebugMessage = message
                    if (value.size <= MAX_DEBUG_EVENT_BYTES && session.debugMessages.size < MAX_DEBUG_MESSAGES &&
                        session.retainedDebugBytes + value.size <= MAX_DEBUG_TOTAL_BYTES
                    ) {
                        session.retainedDebugBytes += value.size
                        session.debugMessages += message
                    } else {
                        session.omittedDebugMessages += 1
                    }
                }
            }
            uuid.equals(ControllerProtocolDetector.MONGOOSE_RPC_RX_CTL, ignoreCase = true) -> {
                val length = try {
                    MongooseRpcFraming.decodeResponseLength(value)
                } catch (error: IllegalArgumentException) {
                    recordFailureAndStop(
                        session,
                        ProbeStepState.PROTOCOL_ERROR,
                        error.message ?: "Malformed RPC response-length notification.",
                    )
                    return
                }
                if (length == 0) {
                    recordFailureAndStop(session, ProbeStepState.PROTOCOL_ERROR, "RPC response announced an empty frame.")
                    return
                }
                if (session.responseAssembler != null) {
                    recordFailureAndStop(session, ProbeStepState.PROTOCOL_ERROR, "A new RPC response arrived before the previous frame was fully read.")
                    return
                }
                session.responseAssembler = MongooseRpcResponseAssembler(length)
                if (session.operations.hasInFlightOperation()) {
                    if (session.writePhase != WritePhase.DATA && session.writePhase != WritePhase.WAITING_RESPONSE) {
                        recordFailureAndStop(
                            session,
                            ProbeStepState.PROTOCOL_ERROR,
                            "RPC response arrived while no complete request was being sent.",
                        )
                    } else {
                        record(session, "RPC response length arrived during a serialized write; response read queued after the write callback.")
                    }
                } else if (!readRpcResponseChunk(session, gatt)) {
                    recordFailureAndStop(session, ProbeStepState.TRANSPORT_ERROR, "Android rejected the RPC response read.")
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
        if (!session.operations.complete(
                GattOperationKind.READ_CHARACTERISTIC,
                characteristic.uuid.toString(),
            )
        ) {
            record(session, "Ignored unmatched RPC data-read callback.")
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recordFailureAndStop(session, ProbeStepState.TRANSPORT_ERROR, "RPC response read failed with GATT status $status.")
            return
        }
        val assembler = session.responseAssembler ?: return
        val complete = try {
            assembler.append(value)
        } catch (error: IllegalArgumentException) {
            recordFailureAndStop(
                session,
                ProbeStepState.PROTOCOL_ERROR,
                error.message ?: "Malformed RPC response frame.",
            )
            return
        }
        if (!complete) {
            if (!readRpcResponseChunk(session, gatt)) {
                recordFailureAndStop(session, ProbeStepState.TRANSPORT_ERROR, "Android rejected a subsequent RPC response read.")
            }
            return
        }
        val raw = assembler.finish().toString(Charsets.UTF_8)
        session.responseAssembler = null
        processRpcResponse(session, gatt, raw)
    }

    private fun readRpcResponseChunk(session: Session, gatt: BluetoothGatt): Boolean {
        val characteristic = session.dataChar ?: return false
        val uuid = characteristic.uuid.toString()
        if (!session.operations.begin(GattOperationKind.READ_CHARACTERISTIC, uuid)) return false
        val accepted = try {
            gatt.readCharacteristic(characteristic)
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) session.operations.complete(GattOperationKind.READ_CHARACTERISTIC, uuid)
        return accepted
    }

    private fun processRpcResponse(session: Session, gatt: BluetoothGatt, raw: String) {
        val request = session.activeRequest ?: return

        val response = MongooseRpcResponseParser.parse(raw, session.requestId)
        if (response.kind == MongooseRpcResponseKind.STALE_RESPONSE) {
            record(session, "Ignored stale RPC response id=${response.id} while waiting for id=${session.requestId}.")
            return
        }
        session.commandTimeout?.let(handler::removeCallbacks)
        session.commandTimeout = null

        when (response.kind) {
            MongooseRpcResponseKind.MALFORMED -> {
                session.observations += RpcProbeObservation(
                    request.label,
                    request.method,
                    ProbeStepState.PROTOCOL_ERROR,
                    error = response.failureReason ?: "Malformed RPC response.",
                )
                record(session, "Could not parse ${request.method} response.")
            }
            MongooseRpcResponseKind.RPC_ERROR -> {
                val code = response.errorCode
                val message = response.errorMessage ?: "RPC error"
                val state = if (code == 401) ProbeStepState.AUTH_REQUIRED else ProbeStepState.PROTOCOL_ERROR
                session.observations += RpcProbeObservation(
                    request.label,
                    request.method,
                    state,
                    error = "code=${code ?: "unknown"}; $message",
                )
                record(session, "${request.method} returned error code=${code ?: "unknown"}.")
            }
            MongooseRpcResponseKind.SUCCESS -> {
                val resultText = response.resultJson
                session.observations += RpcProbeObservation(
                    request.label,
                    request.method,
                    ProbeStepState.SUCCESS,
                    response = resultText.take(MAX_OBSERVATION_CHARS),
                )
                record(session, "${request.method} succeeded; responseChars=${resultText.length}.")
            }
            MongooseRpcResponseKind.STALE_RESPONSE -> error("Handled above")
        }

        session.planner.accept(request, response)
        if (request.method == "RPC.List" || request.method == "RPC.ListEx") {
            record(
                session,
                "RPC inventory now contains ${session.planner.rpcMethods.size} method(s); " +
                    "planned safe reads and bounded introspection requests.",
            )
        }
        session.activeRequest = null
        sendNext(session, gatt)
    }

    private fun recordFailureAndStop(
        session: Session,
        state: ProbeStepState,
        error: String,
    ) {
        val request = session.activeRequest
        session.commandTimeout?.let(handler::removeCallbacks)
        session.commandTimeout = null
        if (request != null) {
            session.observations += RpcProbeObservation(request.label, request.method, state, error = error)
            record(session, "${request.method} stopped: $error")
        } else {
            record(session, "RPC transport stopped: $error")
        }
        session.activeRequest = null
        finish(session, "RPC transport stopped after a framing or timeout failure; collected evidence retained.")
    }

    private fun finish(session: Session, outcome: String) {
        if (!isActive(session)) return
        record(session, "Protocol probe finished: $outcome")
        session.finished = true
        removeTimeouts(session)
        val pingSucceeded = session.observations.any {
            it.method == "RPC.Ping" && it.state == ProbeStepState.SUCCESS
        }
        val httpEnable = session.observations.firstOrNull {
            it.label == "Configuration capability: http.enable" && it.state == ProbeStepState.SUCCESS
        }?.response?.trim()?.trim('"')
        val httpCapability = when (httpEnable?.lowercase()) {
            "true" -> ControllerTransportCapability(
                ControllerTransportType.LOCAL_HTTP,
                CapabilityState.POSSIBLE,
                "Controller configuration reports HTTP enabled; local network reachability was not tested.",
            )
            "false" -> ControllerTransportCapability(
                ControllerTransportType.LOCAL_HTTP,
                CapabilityState.UNAVAILABLE,
                "Controller configuration reports HTTP disabled.",
            )
            else -> ControllerTransportCapability(
                ControllerTransportType.LOCAL_HTTP,
                CapabilityState.UNKNOWN,
                "No safe positive HTTP configuration evidence was collected.",
            )
        }
        val capabilities = listOf(
            ControllerTransportCapability(
                ControllerTransportType.BLE,
                when {
                    pingSucceeded -> CapabilityState.SUPPORTED
                    ControllerProtocolFamily.MONGOOSE_RPC in session.protocols -> CapabilityState.POSSIBLE
                    else -> CapabilityState.UNKNOWN
                },
                when {
                    pingSucceeded -> "Mongoose RPC exchange succeeded."
                    ControllerProtocolFamily.MONGOOSE_RPC in session.protocols -> "Mongoose RPC GATT service detected; exchange was not confirmed."
                    else -> "Bluetooth GATT was available; no known RPC transport was detected."
                },
            ),
            httpCapability,
            ControllerTransportCapability(
                ControllerTransportType.VENDOR_RELAY,
                CapabilityState.UNKNOWN,
                "Not tested during automatic interrogation.",
            ),
        )

        val report = ControllerProbeReport(
            address = session.address,
            startedAtUtc = formatUtc(session.startedAtMillis),
            finishedAtUtc = formatUtc(System.currentTimeMillis()),
            outcome = outcome,
            protocols = session.protocols,
            rpcMethods = session.planner.rpcMethods,
            rpcDescriptions = session.planner.rpcDescriptions,
            observations = session.observations.toList(),
            debugMessages = session.debugMessages.toList(),
            debugNotificationCount = session.debugNotificationCount,
            debugPayloadChangeCount = session.debugPayloadChangeCount,
            debugRetainedBytes = session.retainedDebugBytes,
            omittedDebugMessageCount = session.omittedDebugMessages,
            transportCapabilities = capabilities,
            events = session.events.toList(),
            omittedEventCount = session.omittedEvents,
        )
        val gatt = session.gatt
        session.gatt = null
        session.operations.clear()
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
        session.connectionTimeout?.let(handler::removeCallbacks)
        session.connectionTimeout = null
        session.serviceDiscoveryTimeout?.let(handler::removeCallbacks)
        session.serviceDiscoveryTimeout = null
        session.descriptorTimeout?.let(handler::removeCallbacks)
        session.descriptorTimeout = null
        session.overallTimeout?.let(handler::removeCallbacks)
        session.overallTimeout = null
    }

    private fun closeGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun writeCharacteristic(
        session: Session,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        val target = characteristic.uuid.toString()
        if (!session.operations.begin(GattOperationKind.WRITE_CHARACTERISTIC, target)) return false
        val accepted = writeCharacteristicCompat(gatt, characteristic, value)
        if (!accepted) session.operations.complete(GattOperationKind.WRITE_CHARACTERISTIC, target)
        return accepted
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
        var connected: Boolean? = null
        var connectionTimeout: Runnable? = null
        var serviceDiscoveryTimeout: Runnable? = null
        var descriptorTimeout: Runnable? = null
        val operations = SerializedGattOperationCoordinator()
        var activeRequest: SafeRpcRequest? = null
        var requestId: Int = 0
        var pendingPayload: ByteArray = byteArrayOf()
        var payloadOffset: Int = 0
        var writePhase: WritePhase = WritePhase.IDLE
        var responseAssembler: MongooseRpcResponseAssembler? = null
        var startedCommands = false
        var finished = false
        var omittedEvents = 0
        var omittedDebugMessages = 0
        var debugCaptureStartedAtElapsedMillis: Long? = null
        var debugNotificationCount: Int = 0
        var debugPayloadChangeCount: Int = 0
        var retainedDebugBytes: Int = 0
        var lastDebugMessage: String? = null
        val planner = MongooseRpcInterrogationPlanner()
        val observations = mutableListOf<RpcProbeObservation>()
        val debugMessages = mutableListOf<String>()
        val events = mutableListOf<String>()
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val BLE_CHUNK_BYTES = MongooseRpcFraming.DEFAULT_CHUNK_BYTES
        const val COMMAND_TIMEOUT_MILLIS = 5_000L
        const val OVERALL_TIMEOUT_MILLIS = 120_000L
        const val CONNECTION_TIMEOUT_MILLIS = 20_000L
        const val SERVICE_DISCOVERY_TIMEOUT_MILLIS = 15_000L
        const val DESCRIPTOR_WRITE_TIMEOUT_MILLIS = 3_000L
        const val MAX_OBSERVATION_CHARS = 8_000
        const val MAX_DEBUG_MESSAGES = 40
        const val MAX_DEBUG_NOTIFICATION_COUNT = 1_000
        const val MAX_DEBUG_EVENT_BYTES = 2 * 1024
        const val MAX_DEBUG_MESSAGE_CHARS = 600
        const val MAX_DEBUG_TOTAL_BYTES = 16 * 1024
        const val DEBUG_OBSERVATION_WINDOW_MILLIS = 15_000L
        const val MAX_EVENTS = 220
        const val MAX_EVENT_CHARS = 350
    }
}
