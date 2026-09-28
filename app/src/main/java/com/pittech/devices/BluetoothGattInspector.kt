package com.pittech.devices

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Explicit, foreground-only BLE GATT inspection for controller troubleshooting.
 * It discovers the service tree and reads a bounded number of characteristics
 * marked readable. It never writes a characteristic or enables notifications.
 */
internal class BluetoothGattInspector(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var activeSession: Session? = null

    fun inspect(
        address: String,
        onProgress: (String) -> Unit,
        onFinished: (BluetoothGattInspectionReport) -> Unit,
    ) {
        closeSilently()
        val session = Session(
            address = address,
            startedAtMillis = System.currentTimeMillis(),
            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
            onProgress = onProgress,
            onFinished = onFinished,
        )
        activeSession = session
        record(session, "Inspection started; direct LE GATT connection requested with autoConnect=false.")
        if (address.isBlank()) {
            record(session, "No Bluetooth address was available from the scan result.")
            finish(session, "Cannot connect: Bluetooth address unavailable.")
            return
        }

        try {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            if (adapter == null) {
                record(session, "Bluetooth adapter unavailable.")
                finish(session, "Bluetooth adapter unavailable.")
                return
            }
            if (!adapter.isEnabled) {
                record(session, "Bluetooth adapter is disabled.")
                finish(session, "Bluetooth is turned off.")
                return
            }
            val device = adapter.getRemoteDevice(address)
            record(
                session,
                "Android device type=" + deviceTypeName(device.type) +
                    "; bond state=" + bondStateName(device.bondState) +
                    "; Bluetooth adapter enabled=true.",
            )

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (!isActive(session)) return
                    session.connectionStatusCode = status
                    session.connected = newState == BluetoothProfile.STATE_CONNECTED
                    record(
                        session,
                        "onConnectionStateChange status=" + statusName(status) +
                            "; state=" + connectionStateName(newState) + ".",
                    )
                    when {
                        status != BluetoothGatt.GATT_SUCCESS -> {
                            finish(session, "Connection callback failed: " + statusName(status) + ".")
                        }
                        newState == BluetoothProfile.STATE_CONNECTED -> {
                            val accepted = try {
                                gatt.discoverServices()
                            } catch (error: SecurityException) {
                                record(session, exceptionSummary("discoverServices permission failure", error))
                                false
                            } catch (error: RuntimeException) {
                                record(session, exceptionSummary("discoverServices exception", error))
                                false
                            }
                            record(session, "discoverServices() accepted=" + accepted + ".")
                            if (!accepted) finish(session, "Android did not start GATT service discovery.")
                        }
                        newState == BluetoothProfile.STATE_DISCONNECTED -> {
                            finish(session, "Controller disconnected before inspection completed.")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (!isActive(session)) return
                    session.serviceDiscoveryStatusCode = status
                    record(session, "onServicesDiscovered status=" + statusName(status) + ".")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(session, "Service discovery failed: " + statusName(status) + ".")
                        return
                    }

                    val discoveredServices = try {
                        gatt.services.orEmpty()
                    } catch (error: SecurityException) {
                        record(session, exceptionSummary("Reading discovered services failed", error))
                        finish(session, "Bluetooth permission denied while reading discovered services.")
                        return
                    } catch (error: RuntimeException) {
                        record(session, exceptionSummary("Reading discovered services failed", error))
                        finish(session, "Could not read discovered services.")
                        return
                    }

                    session.services = discoveredServices.map { service ->
                        BluetoothGattServiceInfo(
                            uuid = service.uuid.toString(),
                            kind = if (service.type == android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY) {
                                "primary"
                            } else {
                                "secondary/type-" + service.type
                            },
                            characteristics = service.characteristics.orEmpty().map { characteristic ->
                                BluetoothGattCharacteristicInfo(
                                    uuid = characteristic.uuid.toString(),
                                    properties = characteristicProperties(characteristic.properties),
                                    permissions = attributePermissions(characteristic.permissions),
                                    descriptors = characteristic.descriptors.orEmpty().map { descriptor ->
                                        BluetoothGattDescriptorInfo(
                                            uuid = descriptor.uuid.toString(),
                                            permissions = attributePermissions(descriptor.permissions),
                                        )
                                    },
                                )
                            },
                        )
                    }
                    session.totalCharacteristicCount = session.services.sumOf { it.characteristics.size }
                    val readableTargets = discoveredServices.flatMap { service ->
                        service.characteristics.orEmpty()
                            .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                            .map { ReadTarget(service.uuid.toString(), it) }
                    }
                    session.readableCharacteristicCount = readableTargets.size
                    session.omittedReadableCharacteristicCount =
                        (readableTargets.size - MAX_READS_PER_INSPECTION).coerceAtLeast(0)
                    readableTargets.take(MAX_READS_PER_INSPECTION).forEach(session.readQueue::addLast)
                    record(
                        session,
                        "Discovered " + session.services.size + " service(s), " +
                            session.totalCharacteristicCount + " characteristic(s), and " +
                            readableTargets.size + " readable characteristic(s); read cap=" +
                            MAX_READS_PER_INSPECTION + ".",
                    )
                    readNextCharacteristic(session, gatt)
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    completeRead(session, gatt, characteristic, value, status)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    completeRead(
                        session,
                        gatt,
                        characteristic,
                        characteristic.value ?: byteArrayOf(),
                        status,
                    )
                }
            }

            session.timeout = Runnable {
                if (isActive(session)) {
                    record(session, "Overall GATT inspection timeout reached.")
                    finish(session, "GATT inspection timed out.")
                }
            }
            handler.postDelayed(session.timeout!!, SESSION_TIMEOUT_MILLIS)

            @Suppress("DEPRECATION")
            val gatt = device.connectGatt(
                appContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
            if (gatt == null) {
                record(session, "connectGatt() returned null.")
                finish(session, "Android did not create a GATT connection.")
            } else {
                session.gatt = gatt
                record(session, "connectGatt() returned a GATT client; waiting for connection callback.")
            }
        } catch (error: SecurityException) {
            record(session, exceptionSummary("Bluetooth permission failure", error))
            finish(session, "Bluetooth connection permission was not granted.")
        } catch (error: IllegalArgumentException) {
            record(session, exceptionSummary("Bluetooth address rejected", error))
            finish(session, "Android rejected the scanned Bluetooth address.")
        } catch (error: RuntimeException) {
            record(session, exceptionSummary("GATT connection exception", error))
            finish(session, "Could not start the Bluetooth connection.")
        }
    }

    fun cancel() {
        val session = activeSession ?: return
        record(session, "User canceled the GATT inspection.")
        finish(session, "Canceled by user.")
    }

    /** Releases an in-progress connection when the test screen is closed. */
    fun closeSilently() {
        val session = activeSession ?: return
        session.finished = true
        removeTimeouts(session)
        activeSession = null
        closeGatt(session.gatt)
    }

    private fun readNextCharacteristic(session: Session, gatt: BluetoothGatt) {
        if (!isActive(session)) return
        while (session.readQueue.isNotEmpty()) {
            val target = session.readQueue.removeFirst()
            session.activeRead = target
            val accepted: Boolean
            var failureNote: String? = null
            try {
                accepted = gatt.readCharacteristic(target.characteristic)
            } catch (error: SecurityException) {
                accepted = false
                failureNote = exceptionSummary("Characteristic read permission failure", error)
            } catch (error: RuntimeException) {
                accepted = false
                failureNote = exceptionSummary("Characteristic read exception", error)
            }
            if (accepted) {
                record(
                    session,
                    "readCharacteristic() accepted for service=" + target.serviceUuid +
                        " characteristic=" + target.characteristic.uuid + ".",
                )
                val timeout = Runnable {
                    if (isActive(session) && session.activeRead === target) {
                        session.reads += failedRead(
                            target,
                            "No characteristic-read callback before timeout.",
                            requestAccepted = true,
                        )
                        session.activeRead = null
                        record(
                            session,
                            "Characteristic read callback timed out for " +
                                target.characteristic.uuid + ".",
                        )
                        finish(session, "Characteristic read timed out; remaining reads were stopped.")
                    }
                }
                session.readTimeout = timeout
                handler.postDelayed(timeout, CHARACTERISTIC_READ_TIMEOUT_MILLIS)
                return
            }

            session.reads += failedRead(
                target,
                failureNote ?: "readCharacteristic() returned false.",
            )
            session.activeRead = null
            record(
                session,
                "readCharacteristic() rejected for service=" + target.serviceUuid +
                    " characteristic=" + target.characteristic.uuid +
                    (failureNote?.let { "; " + it } ?: "; returned false.") ,
            )
        }
        record(session, "All queued readable characteristic requests completed.")
        finish(session, "GATT service inspection completed.")
    }

    private fun completeRead(
        session: Session,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (!isActive(session)) return
        val target = session.activeRead
        if (target == null || target.characteristic.uuid != characteristic.uuid ||
            target.serviceUuid != characteristic.service.uuid.toString()
        ) {
            record(session, "Received an unmatched characteristic-read callback for " + characteristic.uuid + ".")
            return
        }
        session.readTimeout?.let(handler::removeCallbacks)
        session.readTimeout = null
        session.activeRead = null

        val shownLength = value.size.coerceAtMost(MAX_VALUE_BYTES_IN_REPORT)
        val shownValue = value.copyOfRange(0, shownLength)
        session.reads += BluetoothGattReadResult(
            serviceUuid = target.serviceUuid,
            characteristicUuid = characteristic.uuid.toString(),
            initiated = true,
            statusCode = status,
            valueLengthBytes = value.size,
            valueHex = shownValue.toHex(),
            valueText = printableUtf8(shownValue),
            truncatedValueBytes = (value.size - shownLength).coerceAtLeast(0),
            note = if (status == BluetoothGatt.GATT_SUCCESS) null else statusName(status),
        )
        record(
            session,
            "onCharacteristicRead service=" + target.serviceUuid +
                " characteristic=" + characteristic.uuid +
                " status=" + statusName(status) + " bytes=" + value.size + ".",
        )
        readNextCharacteristic(session, gatt)
    }

    private fun failedRead(
        target: ReadTarget,
        note: String,
        requestAccepted: Boolean = false,
    ) = BluetoothGattReadResult(
        serviceUuid = target.serviceUuid,
        characteristicUuid = target.characteristic.uuid.toString(),
        initiated = requestAccepted,
        statusCode = null,
        valueLengthBytes = 0,
        valueHex = "",
        valueText = null,
        truncatedValueBytes = 0,
        note = note,
    )

    private fun isActive(session: Session): Boolean =
        activeSession === session && !session.finished

    private fun record(session: Session, message: String) {
        val elapsedMillis = (SystemClock.elapsedRealtime() - session.startedAtElapsedMillis).coerceAtLeast(0L)
        val entry = formatUtc(System.currentTimeMillis()) + " +" + elapsedMillis + "ms " + message.take(MAX_EVENT_MESSAGE_CHARS)
        if (session.events.size >= MAX_EVENTS) {
            session.events.removeAt(0)
            session.omittedEvents += 1
        }
        session.events += entry
        session.onProgress(message.take(220))
    }

    private fun finish(session: Session, outcome: String) {
        if (!isActive(session)) return
        record(session, "Inspection finished: " + outcome)
        session.finished = true
        session.outcome = outcome
        removeTimeouts(session)
        val gatt = session.gatt
        session.gatt = null
        closeGatt(gatt)
        if (activeSession === session) activeSession = null
        session.onFinished(
            BluetoothGattInspectionReport(
                address = session.address,
                startedAtUtc = formatUtc(session.startedAtMillis),
                finishedAtUtc = formatUtc(System.currentTimeMillis()),
                outcome = session.outcome,
                connected = session.connected,
                connectionStatusCode = session.connectionStatusCode,
                serviceDiscoveryStatusCode = session.serviceDiscoveryStatusCode,
                services = session.services,
                totalCharacteristicCount = session.totalCharacteristicCount,
                readableCharacteristicCount = session.readableCharacteristicCount,
                omittedReadableCharacteristicCount = session.omittedReadableCharacteristicCount,
                reads = session.reads.toList(),
                events = session.events.toList(),
                omittedEventCount = session.omittedEvents,
            ),
        )
    }

    private fun removeTimeouts(session: Session) {
        session.timeout?.let(handler::removeCallbacks)
        session.timeout = null
        session.readTimeout?.let(handler::removeCallbacks)
        session.readTimeout = null
    }

    private fun closeGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun characteristicProperties(mask: Int): List<String> = buildList {
        if (mask and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
        if (mask and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (mask and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
        if (mask and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (mask and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (mask and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (mask and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED_WRITE")
        if (mask and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("EXTENDED_PROPERTIES")
    }

    private fun attributePermissions(mask: Int): List<String> = buildList {
        if (mask and BluetoothGattCharacteristic.PERMISSION_READ != 0) add("READ")
        if (mask and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0) add("READ_ENCRYPTED")
        if (mask and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0) add("READ_ENCRYPTED_MITM")
        if (mask and BluetoothGattCharacteristic.PERMISSION_WRITE != 0) add("WRITE")
        if (mask and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0) add("WRITE_ENCRYPTED")
        if (mask and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0) add("WRITE_ENCRYPTED_MITM")
        if (mask and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED != 0) add("WRITE_SIGNED")
        if (mask and BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM != 0) add("WRITE_SIGNED_MITM")
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual-mode"
        else -> "unknown-" + type
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "not bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> "unknown-" + state
    }

    private fun connectionStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "STATE_" + state
    }

    private fun statusName(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS (0)"
        133 -> "GATT_ERROR_133 (133)"
        else -> "GATT_STATUS_" + status
    }

    private fun exceptionSummary(action: String, error: Throwable): String =
        action + ": " + error.javaClass.simpleName + ": " +
            error.message.orEmpty().replace('\n', ' ').take(180)

    private fun printableUtf8(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val decoded = String(bytes, Charsets.UTF_8)
        if (decoded.contains('\uFFFD')) return null
        if (decoded.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        return decoded.take(MAX_TEXT_PREVIEW_CHARS).takeIf { it.isNotBlank() }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

    private fun formatUtc(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timestampMillis))

    private data class ReadTarget(
        val serviceUuid: String,
        val characteristic: BluetoothGattCharacteristic,
    )

    private class Session(
        val address: String,
        val startedAtMillis: Long,
        val startedAtElapsedMillis: Long,
        val onProgress: (String) -> Unit,
        val onFinished: (BluetoothGattInspectionReport) -> Unit,
    ) {
        var gatt: BluetoothGatt? = null
        var timeout: Runnable? = null
        var readTimeout: Runnable? = null
        var activeRead: ReadTarget? = null
        var connected: Boolean? = null
        var connectionStatusCode: Int? = null
        var serviceDiscoveryStatusCode: Int? = null
        var outcome: String = "In progress"
        var finished: Boolean = false
        var totalCharacteristicCount: Int = 0
        var readableCharacteristicCount: Int = 0
        var omittedReadableCharacteristicCount: Int = 0
        var omittedEvents: Int = 0
        val services = mutableListOf<BluetoothGattServiceInfo>()
        val reads = mutableListOf<BluetoothGattReadResult>()
        val events = mutableListOf<String>()
        val readQueue = ArrayDeque<ReadTarget>()
    }

    private companion object {
        const val MAX_READS_PER_INSPECTION = 20
        const val MAX_VALUE_BYTES_IN_REPORT = 128
        const val MAX_TEXT_PREVIEW_CHARS = 256
        const val MAX_EVENTS = 160
        const val MAX_EVENT_MESSAGE_CHARS = 350
        const val SESSION_TIMEOUT_MILLIS = 90_000L
        const val CHARACTERISTIC_READ_TIMEOUT_MILLIS = 5_000L
    }
}
