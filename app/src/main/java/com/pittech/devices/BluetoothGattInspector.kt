package com.pittech.devices

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.bluetooth.BluetoothStatusCodes
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Explicit, foreground-only BLE GATT inspection for controller troubleshooting.
 * It discovers the full service tree and attempts every characteristic marked
 * readable, subject only to a high sanity cap and the overall session timeout.
 * This inventory phase never writes a characteristic or enables notifications.
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
                    if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTING) {
                        session.connectionTimeout?.let(handler::removeCallbacks)
                        session.connectionTimeout = null
                    }
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
                            if (!session.operations.begin(GattOperationKind.DISCOVER_SERVICES)) {
                                finish(session, "A GATT operation was already active before service discovery.")
                                return
                            }
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
                            if (!accepted) {
                                session.operations.complete(GattOperationKind.DISCOVER_SERVICES)
                                finish(session, "Android did not start GATT service discovery.")
                            } else {
                                val timeout = Runnable {
                                    if (isActive(session) && session.operations.timedOut(GattOperationKind.DISCOVER_SERVICES)) {
                                        record(session, "GATT service discovery callback timed out.")
                                        finish(session, "Service discovery timed out; collected evidence retained.")
                                    }
                                }
                                session.serviceDiscoveryTimeout = timeout
                                handler.postDelayed(timeout, SERVICE_DISCOVERY_TIMEOUT_MILLIS)
                            }
                        }
                        newState == BluetoothProfile.STATE_DISCONNECTED -> {
                            finish(session, "Controller disconnected before inspection completed.")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (!isActive(session)) return
                    session.serviceDiscoveryTimeout?.let(handler::removeCallbacks)
                    session.serviceDiscoveryTimeout = null
                    if (!session.operations.complete(GattOperationKind.DISCOVER_SERVICES)) {
                        record(session, "Ignored unmatched service-discovery callback.")
                        return
                    }
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

                    session.services.addAll(discoveredServices.map { service ->
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
                    })
                    session.totalCharacteristicCount = session.services.sumOf { it.characteristics.size }
                    val allReadableTargets = discoveredServices.flatMap { service ->
                        service.characteristics.orEmpty()
                            .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                            .map { ReadTarget(service.uuid.toString(), it) }
                    }
                    session.readableCharacteristicCount = allReadableTargets.size
                    val safeReadableTargets = allReadableTargets.filter { target ->
                        ControllerProtocolDetector.isSafeGattRead(
                            target.serviceUuid,
                            target.characteristic.uuid.toString(),
                        )
                    }
                    val safetySkippedTargets = allReadableTargets.filterNot(safeReadableTargets::contains)
                    session.skippedReadableCharacteristicCount = safetySkippedTargets.size
                    safetySkippedTargets.forEach { target ->
                        session.reads += failedRead(
                            target,
                            "Skipped for safety: Mongoose configuration value may expose the currently selected setting, including credentials.",
                        )
                    }
                    session.omittedReadableCharacteristicCount =
                        (safeReadableTargets.size - MAX_READS_PER_INSPECTION).coerceAtLeast(0)
                    safeReadableTargets.take(MAX_READS_PER_INSPECTION).forEach(session.readQueue::addLast)
                    val allNotificationTargets = discoveredServices.flatMap { service ->
                        val serviceUuid = service.uuid.toString()
                        if (serviceUuid.equals(ControllerProtocolDetector.MONGOOSE_RPC_SERVICE, ignoreCase = true) ||
                            serviceUuid.equals(ControllerProtocolDetector.MONGOOSE_DEBUG_SERVICE, ignoreCase = true) ||
                            serviceUuid.equals(ControllerProtocolDetector.MONGOOSE_CONFIG_SERVICE, ignoreCase = true)
                        ) {
                            emptyList()
                        } else {
                            service.characteristics.orEmpty().mapNotNull { characteristic ->
                                val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                                val canIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                                if (!canNotify && !canIndicate) return@mapNotNull null
                                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                                NotificationTarget(
                                    serviceUuid = serviceUuid,
                                    characteristic = characteristic,
                                    descriptor = descriptor,
                                )
                            }
                        }
                    }
                    session.omittedNotificationCharacteristicCount =
                        (allNotificationTargets.size - MAX_NOTIFICATION_CHARACTERISTICS).coerceAtLeast(0)
                    val boundedNotificationTargets = allNotificationTargets.take(MAX_NOTIFICATION_CHARACTERISTICS)
                    session.notificationTargets.addAll(boundedNotificationTargets)
                    boundedNotificationTargets.forEach(session.notificationQueue::addLast)
                    record(
                        session,
                        "Discovered " + session.services.size + " service(s), " +
                            session.totalCharacteristicCount + " characteristic(s), " +
                            allReadableTargets.size + " readable characteristic(s), safety-skipped=" +
                            session.skippedReadableCharacteristicCount + "; read cap=" +
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

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    handleNotificationDescriptorWrite(session, gatt, descriptor, status)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    observeNotification(session, characteristic, value)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    observeNotification(session, characteristic, characteristic.value ?: byteArrayOf())
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
                val connectionTimeout = Runnable {
                    if (isActive(session) && session.connected != true) {
                        record(session, "Bluetooth connection callback timed out.")
                        finish(session, "Bluetooth connection timed out.")
                    }
                }
                session.connectionTimeout = connectionTimeout
                handler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MILLIS)
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
        session.operations.clear()
        activeSession = null
        closeGatt(session.gatt)
    }

    private fun readNextCharacteristic(session: Session, gatt: BluetoothGatt) {
        if (!isActive(session)) return
        while (session.readQueue.isNotEmpty()) {
            val target = session.readQueue.removeFirst()
            session.activeRead = target
            if (!session.operations.begin(GattOperationKind.READ_CHARACTERISTIC, target.operationKey)) {
                session.reads += failedRead(target, "Skipped because another GATT operation is still active.")
                session.activeRead = null
                record(session, "Read queue stopped because another GATT operation remained in flight.")
                finish(session, "GATT inspection stopped after an operation-queue conflict; collected evidence retained.")
                return
            }
            var accepted = false
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
                        session.operations.timedOut(
                            GattOperationKind.READ_CHARACTERISTIC,
                            target.operationKey,
                        )
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
                        readNextCharacteristic(session, gatt)
                    }
                }
                session.readTimeout = timeout
                handler.postDelayed(timeout, CHARACTERISTIC_READ_TIMEOUT_MILLIS)
                return
            }

            session.operations.complete(GattOperationKind.READ_CHARACTERISTIC, target.operationKey)

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
        startPassiveNotificationInspection(session, gatt)
    }

    private fun startPassiveNotificationInspection(session: Session, gatt: BluetoothGatt) {
        if (session.notificationQueue.isEmpty()) {
            finish(session, "GATT service inspection completed.")
            return
        }
        record(
            session,
            "Beginning bounded passive observation for ${session.notificationQueue.size} notification-capable characteristic(s).",
        )
        enableNextPassiveNotification(session, gatt)
    }

    private fun enableNextPassiveNotification(session: Session, gatt: BluetoothGatt) {
        if (!isActive(session)) return
        val target = session.notificationQueue.pollFirst()
        if (target == null) {
            if (session.enabledNotificationTargets.isEmpty()) {
                finish(session, "GATT service inspection completed; no passive notification channel could be enabled.")
                return
            }
            session.passiveObservationStartedAtElapsed = SystemClock.elapsedRealtime()
            session.enabledNotificationTargets.forEach { it.captureStartedAtElapsed = session.passiveObservationStartedAtElapsed }
            session.notificationCaptureActive = true
            record(
                session,
                "Passively observing ${session.enabledNotificationTargets.size} non-protocol notification channel(s) for " +
                    "${PASSIVE_NOTIFICATION_WINDOW_MILLIS}ms; controller state will not be changed.",
            )
            val timeout = Runnable {
                if (isActive(session) && session.notificationCaptureActive) {
                    beginDisablingPassiveNotifications(session, gatt)
                }
            }
            session.notificationWindowTimeout = timeout
            handler.postDelayed(timeout, PASSIVE_NOTIFICATION_WINDOW_MILLIS)
            return
        }
        session.pendingNotificationTarget = target
        session.pendingNotificationMode = NotificationWriteMode.ENABLE
        val descriptor = target.descriptor
        if (descriptor == null) {
            target.failure = "CCCD descriptor is not exposed."
            session.pendingNotificationTarget = null
            session.pendingNotificationMode = null
            record(session, "Skipped passive observation for ${target.characteristic.uuid}: CCCD unavailable.")
            enableNextPassiveNotification(session, gatt)
            return
        }

        val localEnabled = runCatching {
            gatt.setCharacteristicNotification(target.characteristic, true)
        }.getOrDefault(false)
        if (!localEnabled) {
            target.failure = "Android rejected local notification registration."
            session.pendingNotificationTarget = null
            session.pendingNotificationMode = null
            record(session, "Android rejected local notification registration for ${target.characteristic.uuid}.")
            enableNextPassiveNotification(session, gatt)
            return
        }

        val cccdValue = if (target.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            target.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        ) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (!writeNotificationDescriptor(session, gatt, target, cccdValue)) {
            if (session.operations.hasInFlightOperation()) {
                target.failure = "A GATT operation was still active before notification setup."
                finish(session, "Passive notification setup stopped after an operation-queue conflict; prior evidence retained.")
                return
            }
            runCatching { gatt.setCharacteristicNotification(target.characteristic, false) }
            target.failure = "Android rejected the CCCD enable write."
            session.pendingNotificationTarget = null
            session.pendingNotificationMode = null
            record(session, "Android rejected CCCD enable for ${target.characteristic.uuid}; continuing.")
            enableNextPassiveNotification(session, gatt)
        }
    }

    private fun beginDisablingPassiveNotifications(session: Session, gatt: BluetoothGatt) {
        if (!isActive(session) || session.pendingNotificationMode == NotificationWriteMode.DISABLE) return
        session.notificationWindowTimeout?.let(handler::removeCallbacks)
        session.notificationWindowTimeout = null
        session.notificationCaptureActive = false
        session.notificationDisableIndex = 0
        disableNextPassiveNotification(session, gatt)
    }

    private fun disableNextPassiveNotification(session: Session, gatt: BluetoothGatt) {
        if (!isActive(session)) return
        val target = session.enabledNotificationTargets.getOrNull(session.notificationDisableIndex)
        if (target == null) {
            finish(session, "GATT services, readable values, and bounded passive notifications were inspected.")
            return
        }
        session.pendingNotificationTarget = target
        session.pendingNotificationMode = NotificationWriteMode.DISABLE
        val descriptor = target.descriptor
        runCatching { gatt.setCharacteristicNotification(target.characteristic, false) }
        if (descriptor == null || !writeNotificationDescriptor(
                session,
                gatt,
                target,
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
            )
        ) {
            if (session.operations.hasInFlightOperation()) {
                target.failure = target.failure ?: "A GATT operation was still active before notification cleanup."
                finish(session, "Passive notification cleanup stopped after an operation-queue conflict; prior evidence retained.")
                return
            }
            target.failure = target.failure ?: "Android rejected the CCCD disable write."
            session.pendingNotificationTarget = null
            session.pendingNotificationMode = null
            session.notificationDisableIndex++
            record(session, "Could not confirm notification cleanup for ${target.characteristic.uuid}; continuing.")
            disableNextPassiveNotification(session, gatt)
        }
    }

    private fun writeNotificationDescriptor(
        session: Session,
        gatt: BluetoothGatt,
        target: NotificationTarget,
        value: ByteArray,
    ): Boolean {
        val descriptor = target.descriptor ?: return false
        val operationTarget = target.operationKey
        if (!session.operations.begin(GattOperationKind.WRITE_DESCRIPTOR, operationTarget)) return false
        val accepted = writeDescriptorCompat(gatt, descriptor, value)
        if (!accepted) {
            session.operations.complete(GattOperationKind.WRITE_DESCRIPTOR, operationTarget)
            return false
        }
        val mode = session.pendingNotificationMode
        val timeout = Runnable {
            if (isActive(session) && session.pendingNotificationTarget === target &&
                session.pendingNotificationMode == mode
            ) {
                session.operations.timedOut(GattOperationKind.WRITE_DESCRIPTOR, operationTarget)
                session.notificationDescriptorTimeout = null
                session.pendingNotificationTarget = null
                session.pendingNotificationMode = null
                target.failure = "CCCD write callback timed out."
                runCatching { gatt.setCharacteristicNotification(target.characteristic, false) }
                record(session, "CCCD write timed out for ${target.characteristic.uuid}; continuing.")
                if (mode == NotificationWriteMode.DISABLE) {
                    session.notificationDisableIndex++
                    disableNextPassiveNotification(session, gatt)
                } else {
                    enableNextPassiveNotification(session, gatt)
                }
            }
        }
        session.notificationDescriptorTimeout = timeout
        handler.postDelayed(timeout, NOTIFICATION_DESCRIPTOR_TIMEOUT_MILLIS)
        return true
    }

    private fun handleNotificationDescriptorWrite(
        session: Session,
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (!isActive(session) || descriptor.uuid != CCCD_UUID) return
        val target = session.pendingNotificationTarget ?: return
        val actualKey = runCatching {
            "${descriptor.characteristic.service.uuid}/${descriptor.characteristic.uuid}"
                .lowercase(Locale.ROOT)
        }.getOrDefault("")
        if (actualKey != target.operationKey ||
            !session.operations.complete(GattOperationKind.WRITE_DESCRIPTOR, target.operationKey)
        ) {
            record(session, "Ignored unmatched passive-notification descriptor callback.")
            return
        }
        session.notificationDescriptorTimeout?.let(handler::removeCallbacks)
        session.notificationDescriptorTimeout = null
        val mode = session.pendingNotificationMode
        session.pendingNotificationTarget = null
        session.pendingNotificationMode = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            target.failure = "CCCD ${mode?.name?.lowercase(Locale.ROOT)} write failed with GATT status $status."
            runCatching { gatt.setCharacteristicNotification(target.characteristic, false) }
            record(session, "CCCD ${mode?.name?.lowercase(Locale.ROOT)} write failed for ${target.characteristic.uuid}; continuing.")
        }
        if (mode == NotificationWriteMode.DISABLE) {
            session.notificationDisableIndex++
            disableNextPassiveNotification(session, gatt)
        } else {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                session.enabledNotificationTargets += target
            }
            enableNextPassiveNotification(session, gatt)
        }
    }

    private fun observeNotification(
        session: Session,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (!isActive(session) || !session.notificationCaptureActive) return
        val target = session.enabledNotificationTargets.firstOrNull {
            it.characteristic.uuid == characteristic.uuid &&
                it.serviceUuid.equals(characteristic.service.uuid.toString(), ignoreCase = true)
        } ?: return
        session.receivedNotificationEvents++
        if (session.receivedNotificationEvents > MAX_NOTIFICATION_EVENTS ||
            value.size > MAX_NOTIFICATION_BYTES_PER_EVENT ||
            session.observedNotificationBytes + value.size > MAX_NOTIFICATION_BYTES_TOTAL
        ) {
            session.omittedNotificationEvents++
            if (session.receivedNotificationEvents >= MAX_NOTIFICATION_EVENTS) {
                record(session, "Passive-notification event cap reached; ending observation early.")
                beginDisablingPassiveNotifications(session, session.gatt ?: return)
            }
            return
        }
        target.accumulator.record(value)
        session.observedNotificationBytes += value.size
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
        if (!session.operations.complete(GattOperationKind.READ_CHARACTERISTIC, target.operationKey)) {
            record(session, "Ignored a late characteristic-read callback for " + characteristic.uuid + ".")
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
                skippedReadableCharacteristicCount = session.skippedReadableCharacteristicCount,
                notifications = session.notificationTargets.map { target ->
                    val duration = target.captureStartedAtElapsed
                        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
                        ?: 0L
                    target.accumulator.snapshot(duration, target.failure)
                },
                omittedNotificationCharacteristicCount = session.omittedNotificationCharacteristicCount,
                omittedNotificationEventCount = session.omittedNotificationEvents,
                reads = session.reads.toList(),
                events = session.events.toList(),
                omittedEventCount = session.omittedEvents,
            ),
        )
    }

    private fun removeTimeouts(session: Session) {
        session.timeout?.let(handler::removeCallbacks)
        session.timeout = null
        session.connectionTimeout?.let(handler::removeCallbacks)
        session.connectionTimeout = null
        session.serviceDiscoveryTimeout?.let(handler::removeCallbacks)
        session.serviceDiscoveryTimeout = null
        session.readTimeout?.let(handler::removeCallbacks)
        session.readTimeout = null
        session.notificationWindowTimeout?.let(handler::removeCallbacks)
        session.notificationWindowTimeout = null
        session.notificationDescriptorTimeout?.let(handler::removeCallbacks)
        session.notificationDescriptorTimeout = null
        session.operations.clear()
    }

    private fun closeGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
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
    ) {
        val operationKey: String
            get() = "${serviceUuid.lowercase(Locale.ROOT)}/${characteristic.uuid.toString().lowercase(Locale.ROOT)}"
    }

    private data class NotificationTarget(
        val serviceUuid: String,
        val characteristic: BluetoothGattCharacteristic,
        val descriptor: BluetoothGattDescriptor?,
        val accumulator: PassiveNotificationAccumulator = PassiveNotificationAccumulator(
            serviceUuid,
            characteristic.uuid.toString(),
        ),
        var failure: String? = null,
        var captureStartedAtElapsed: Long? = null,
    ) {
        val operationKey: String
            get() = "${serviceUuid.lowercase(Locale.ROOT)}/${characteristic.uuid.toString().lowercase(Locale.ROOT)}"
    }

    private enum class NotificationWriteMode { ENABLE, DISABLE }

    private class Session(
        val address: String,
        val startedAtMillis: Long,
        val startedAtElapsedMillis: Long,
        val onProgress: (String) -> Unit,
        val onFinished: (BluetoothGattInspectionReport) -> Unit,
    ) {
        var gatt: BluetoothGatt? = null
        var timeout: Runnable? = null
        var connectionTimeout: Runnable? = null
        var serviceDiscoveryTimeout: Runnable? = null
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
        var skippedReadableCharacteristicCount: Int = 0
        var omittedNotificationCharacteristicCount: Int = 0
        var omittedNotificationEvents: Int = 0
        var receivedNotificationEvents: Int = 0
        var observedNotificationBytes: Int = 0
        var passiveObservationStartedAtElapsed: Long? = null
        var notificationCaptureActive = false
        var notificationDisableIndex = 0
        var pendingNotificationTarget: NotificationTarget? = null
        var pendingNotificationMode: NotificationWriteMode? = null
        var notificationWindowTimeout: Runnable? = null
        var notificationDescriptorTimeout: Runnable? = null
        var omittedEvents: Int = 0
        val operations = SerializedGattOperationCoordinator()
        val services = mutableListOf<BluetoothGattServiceInfo>()
        val reads = mutableListOf<BluetoothGattReadResult>()
        val events = mutableListOf<String>()
        val readQueue = ArrayDeque<ReadTarget>()
        val notificationQueue = ArrayDeque<NotificationTarget>()
        val notificationTargets = mutableListOf<NotificationTarget>()
        val enabledNotificationTargets = mutableListOf<NotificationTarget>()
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MAX_READS_PER_INSPECTION = 256
        const val MAX_NOTIFICATION_CHARACTERISTICS = 8
        const val MAX_NOTIFICATION_EVENTS = 64
        const val MAX_NOTIFICATION_BYTES_PER_EVENT = 512
        const val MAX_NOTIFICATION_BYTES_TOTAL = 16 * 1024
        const val PASSIVE_NOTIFICATION_WINDOW_MILLIS = 10_000L
        const val NOTIFICATION_DESCRIPTOR_TIMEOUT_MILLIS = 3_000L
        const val MAX_VALUE_BYTES_IN_REPORT = 128
        const val MAX_TEXT_PREVIEW_CHARS = 256
        const val MAX_EVENTS = 160
        const val MAX_EVENT_MESSAGE_CHARS = 350
        const val SESSION_TIMEOUT_MILLIS = 120_000L
        const val CONNECTION_TIMEOUT_MILLIS = 20_000L
        const val SERVICE_DISCOVERY_TIMEOUT_MILLIS = 15_000L
        const val CHARACTERISTIC_READ_TIMEOUT_MILLIS = 5_000L
    }
}
