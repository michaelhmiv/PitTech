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
import java.util.UUID

internal enum class GrillirGSetupStage {
    IDLE,
    CONNECTING,
    NEGOTIATING_MTU,
    DISCOVERING_SERVICES,
    CHECKING_CONNECTION,
    ENABLING_NOTIFICATIONS,
    REQUESTING_NETWORKS,
    NETWORKS_READY,
    SENDING_CREDENTIALS,
    WAITING_FOR_WIFI,
    COMPLETE,
    FAILED,
}

internal data class GrillirGSetupSnapshot(
    val stage: GrillirGSetupStage = GrillirGSetupStage.IDLE,
    val message: String = "Ready to connect to the selected iFireTech controller.",
    val networks: List<GrillirGProtocol.WifiNetwork> = emptyList(),
    val wifiStatusCode: Int? = null,
    val diagnostics: GrillirGSetupDiagnostics = GrillirGSetupDiagnostics(),
)

/** Privacy-safe exchange facts. Never store network identity, credentials, or packet contents here. */
internal data class GrillirGSetupDiagnostics(
    val attempted: Boolean = false,
    val currentStage: String = "IDLE",
    val elapsedMillis: Long = 0,
    val connected: Boolean? = null,
    val connectionGattStatus: Int? = null,
    val negotiatedMtu: Int? = null,
    val mtuGattStatus: Int? = null,
    val setupServiceFound: Boolean? = null,
    val writeCharacteristicAvailable: Boolean? = null,
    val notifyCharacteristicAvailable: Boolean? = null,
    val cccdAvailable: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
    val writeChunksAcknowledged: Int = 0,
    val writesStarted: Map<String, Int> = emptyMap(),
    val writesAcknowledged: Map<String, Int> = emptyMap(),
    val credentialWriteAcknowledged: Boolean? = null,
    val credentialWriteChunksAcknowledged: Int = 0,
    val credentialWriteChunksTotal: Int = 0,
    val notificationsReceived: Int = 0,
    val notificationsAfterCredentialRequest: Int = 0,
    val notificationBytesReceived: Int = 0,
    val validFramesReceived: Int = 0,
    val invalidFramesReceived: Int = 0,
    val parsedCommandCounts: Map<Int, Int> = emptyMap(),
    val wifiNetworkRecordsReceived: Int = 0,
    val unparsedWifiNetworkRecords: Int = 0,
    val wifiScanComplete: Boolean = false,
    val wifiStatusFramesReceived: Int = 0,
    val wifiStatusFramesMalformed: Int = 0,
    val wifiStatusCode: Int? = null,
    val failureAtStage: String? = null,
    val events: List<String> = emptyList(),
) {
    fun reportLines(): List<String> {
        if (!attempted) return emptyList()
        fun yesNoUnknown(value: Boolean?): String = when (value) {
            true -> "yes"
            false -> "no"
            null -> "not reached"
        }
        return buildList {
            add("Final setup stage: $currentStage")
            add("Elapsed time: ${elapsedMillis} ms")
            add("BLE connection established: ${yesNoUnknown(connected)}${connectionGattStatus?.let { " (GATT status $it)" }.orEmpty()}")
            add("Negotiated MTU: ${negotiatedMtu?.let { "$it (status ${mtuGattStatus ?: "unknown"})" } ?: "not reached"}")
            add("Setup service found: ${yesNoUnknown(setupServiceFound)}; write=${yesNoUnknown(writeCharacteristicAvailable)}, notify=${yesNoUnknown(notifyCharacteristicAvailable)}, CCCD=${yesNoUnknown(cccdAvailable)}")
            add("Notifications enabled: ${yesNoUnknown(notificationsEnabled)}")
            add("GATT write chunks acknowledged: $writeChunksAcknowledged")
            add("Completed GATT writes: ${writesAcknowledged.toSortedMap().entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "none" }}")
            add("Credential write GATT-acknowledged: ${yesNoUnknown(credentialWriteAcknowledged)}; chunks=${credentialWriteChunksAcknowledged}/${credentialWriteChunksTotal}")
            add("A GATT write acknowledgement confirms delivery to the BLE stack, not that the controller joined Wi-Fi.")
            add("Notifications received: $notificationsReceived total, $notificationsAfterCredentialRequest after credential request; $notificationBytesReceived bytes")
            add("Frames parsed: $validFramesReceived; unparsed notifications: $invalidFramesReceived")
            add("Parsed protocol commands: ${parsedCommandCounts.toSortedMap().entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "none" }}")
            add("Unique Wi-Fi networks reported: $wifiNetworkRecordsReceived; record parse failures: $unparsedWifiNetworkRecords; scan-complete marker observed: ${yesNoUnknown(wifiScanComplete)}")
            add("Wi-Fi status frames: $wifiStatusFramesReceived; malformed status frames: $wifiStatusFramesMalformed; status code: ${wifiStatusCode ?: "none"}")
            failureAtStage?.let { add("Failure/timeout stage: $it") }
            add("Exchange events (relative time): ${events.size}")
            events.forEach { add("- $it") }
            add("Network names, BSSIDs, passwords, Bluetooth addresses, and raw packet contents are excluded.")
        }
    }
}

internal interface GrillirGSetupEngine {
    val requiresBluetoothPermissions: Boolean get() = true
    fun start(address: String, onUpdate: (GrillirGSetupSnapshot) -> Unit)
    fun provision(network: GrillirGProtocol.WifiNetwork, password: String)
    fun close()
}

/** Foreground GATT setup session matching the vendor app's Wi-Fi provisioning exchange. */
internal class AndroidGrillirGSetupEngine(context: Context) : GrillirGSetupEngine {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var callback: ((GrillirGSetupSnapshot) -> Unit)? = null
    private var snapshot = GrillirGSetupSnapshot()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var cccd: BluetoothGattDescriptor? = null
    private var pendingWrite: PendingWrite? = null
    private var timeout: Runnable? = null
    private var sessionId = 0
    private var diagnostics = GrillirGSetupDiagnostics()
    private var setupStartedAtElapsedRealtime = 0L

    override fun start(address: String, onUpdate: (GrillirGSetupSnapshot) -> Unit) {
        close()
        val currentSession = sessionId
        callback = onUpdate
        setupStartedAtElapsedRealtime = SystemClock.elapsedRealtime()
        diagnostics = GrillirGSetupDiagnostics(
            attempted = true,
            currentStage = GrillirGSetupStage.CONNECTING.name,
        )
        recordDiagnosticEvent("session_started")
        snapshot = GrillirGSetupSnapshot(
            stage = GrillirGSetupStage.CONNECTING,
            message = "Connecting directly to the selected controller over Bluetooth LE…",
            diagnostics = diagnostics,
        )
        publish()

        if (address.isBlank()) {
            fail("Android did not provide a Bluetooth address for this controller.")
            return
        }
        val adapter = try {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: SecurityException) {
            null
        }
        if (adapter == null) {
            fail("Bluetooth is unavailable or turned off.")
            return
        }
        val bluetoothEnabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            fail("Bluetooth connection permission was denied.")
            return
        }
        if (!bluetoothEnabled) {
            fail("Bluetooth is unavailable or turned off.")
            return
        }

        try {
            val device = adapter.getRemoteDevice(address)
            armTimeout(CONNECTION_TIMEOUT_MILLIS, "The controller did not complete the Bluetooth connection.")
            @Suppress("DEPRECATION")
            gatt = device.connectGatt(appContext, false, createGattCallback(currentSession), BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("Android could not start a Bluetooth LE connection.")
        } catch (error: SecurityException) {
            fail("Bluetooth connection permission was denied.")
        } catch (error: IllegalArgumentException) {
            fail("Android rejected the selected Bluetooth address.")
        } catch (error: RuntimeException) {
            fail("Could not start the Bluetooth connection (${error.javaClass.simpleName}).")
        }
    }

    override fun provision(network: GrillirGProtocol.WifiNetwork, password: String) {
        if (snapshot.stage != GrillirGSetupStage.NETWORKS_READY) return
        val frame = try {
            GrillirGProtocol.createWifiCredentialFrame(network, password)
        } catch (error: IllegalArgumentException) {
            update(GrillirGSetupStage.NETWORKS_READY, error.message ?: "The selected network details are invalid.")
            return
        } catch (_: Exception) {
            update(GrillirGSetupStage.NETWORKS_READY, "Could not encrypt the Wi-Fi password for this controller.")
            return
        }

        update(
            GrillirGSetupStage.SENDING_CREDENTIALS,
            "Sending the selected network details to the controller over Bluetooth…",
        )
        armTimeout(WRITE_TIMEOUT_MILLIS, "The controller did not acknowledge the Wi-Fi setup request.")
        writeFrame(frame, "Wi-Fi credentials") {
            update(
                GrillirGSetupStage.WAITING_FOR_WIFI,
                "The controller received the setup request. Waiting for its Wi-Fi connection result…",
            )
            armTimeout(WIFI_CONNECT_TIMEOUT_MILLIS, wifiConnectionTimeoutMessage())
        }
    }

    override fun close() {
        sessionId += 1
        timeout?.let(handler::removeCallbacks)
        timeout = null
        pendingWrite?.chunks?.forEach { it.fill(0) }
        pendingWrite = null
        callback = null
        closeGatt()
        snapshot = GrillirGSetupSnapshot()
        diagnostics = GrillirGSetupDiagnostics()
        setupStartedAtElapsedRealtime = 0L
    }

    private fun createGattCallback(currentSession: Int) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (currentSession != sessionId) return@post
                val connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
                recordDiagnosticEvent("gatt_connection connected=$connected status=$status") {
                    it.copy(
                        connected = if (connected) true else it.connected,
                        connectionGattStatus = status,
                    )
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Bluetooth connection failed with GATT status $status.")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    cancelTimeout()
                    update(
                        GrillirGSetupStage.NEGOTIATING_MTU,
                        "Connected. Negotiating a BLE packet size for the controller's Wi-Fi scan results…",
                    )
                    armTimeout(
                        MTU_NEGOTIATION_TIMEOUT_MILLIS,
                        "Android did not finish negotiating the BLE packet size required for Wi-Fi scan results.",
                    )
                    val accepted = try {
                        gatt.requestMtu(PREFERRED_GATT_MTU)
                    } catch (_: SecurityException) {
                        false
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (!accepted) fail("Android could not start BLE packet-size negotiation for the Wi-Fi scan results.")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && snapshot.stage !in TERMINAL_STAGES) {
                    fail("The controller disconnected during Wi-Fi setup.")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (currentSession != sessionId || snapshot.stage != GrillirGSetupStage.NEGOTIATING_MTU) return@post
                recordDiagnosticEvent("mtu_result value=$mtu status=$status") {
                    it.copy(negotiatedMtu = mtu, mtuGattStatus = status)
                }
                cancelTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS || mtu < MINIMUM_GATT_MTU) {
                    fail("The BLE connection negotiated MTU $mtu; at least $MINIMUM_GATT_MTU is required for the controller's Wi-Fi scan records.")
                    return@post
                }
                beginServiceDiscovery(gatt, currentSession, mtu)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (currentSession != sessionId || snapshot.stage != GrillirGSetupStage.DISCOVERING_SERVICES) return@post
                cancelTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("GATT service discovery failed with status $status.")
                    return@post
                }
                val service = gatt.getService(UUID.fromString(GrillirGProtocol.SERVICE_UUID))
                val characteristic = service?.getCharacteristic(UUID.fromString(GrillirGProtocol.WRITE_NOTIFY_CHARACTERISTIC_UUID))
                val canWrite = characteristic?.let {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                } == true
                val canNotify = characteristic?.let {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                } == true
                val descriptor = characteristic?.getDescriptor(CCCD_UUID)
                recordDiagnosticEvent(
                    "setup_gatt service=${service != null} write=$canWrite notify=$canNotify cccd=${descriptor != null}",
                ) {
                    it.copy(
                        setupServiceFound = service != null,
                        writeCharacteristicAvailable = canWrite,
                        notifyCharacteristicAvailable = canNotify,
                        cccdAvailable = descriptor != null,
                    )
                }
                if (characteristic == null) {
                    fail("This device does not expose the expected GrillirG FF01 setup characteristic.")
                    return@post
                }
                if (!canWrite || !canNotify || descriptor == null) {
                    fail("The GrillirG FF01 characteristic is missing write, notification, or CCCD support.")
                    return@post
                }
                writeCharacteristic = characteristic
                cccd = descriptor
                update(
                    GrillirGSetupStage.CHECKING_CONNECTION,
                    "The expected setup service is present. Sending the vendor app's connection check…",
                )
                writeFrame(GrillirGProtocol.verificationFrame(), "connection check") {
                    enableNotifications(gatt, characteristic, descriptor, currentSession)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (currentSession != sessionId || descriptor.uuid != CCCD_UUID) return@post
                recordDiagnosticEvent("notification_subscription status=$status") {
                    it.copy(notificationsEnabled = status == BluetoothGatt.GATT_SUCCESS)
                }
                cancelTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Android could not enable the controller's Wi-Fi setup notifications (status $status).")
                    return@post
                }
                update(
                    GrillirGSetupStage.REQUESTING_NETWORKS,
                    "Notifications are ready. Asking the controller to scan for nearby Wi-Fi networks…",
                )
                writeFrame(GrillirGProtocol.wifiScanRequestFrame(), "Wi-Fi scan request") {
                    // A scan-complete notification can arrive before the write callback.
                    if (snapshot.stage == GrillirGSetupStage.REQUESTING_NETWORKS) {
                        armTimeout(WIFI_SCAN_TIMEOUT_MILLIS, scanTimeoutMessage())
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post {
                if (currentSession != sessionId || characteristic.uuid != UUID.fromString(GrillirGProtocol.WRITE_NOTIFY_CHARACTERISTIC_UUID)) return@post
                val write = pendingWrite ?: return@post
                cancelTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recordDiagnosticEvent("write_chunk_rejected operation=${write.label} status=$status") {
                        it.copy(
                            credentialWriteAcknowledged = if (write.label == CREDENTIAL_WRITE_LABEL) false else it.credentialWriteAcknowledged,
                        )
                    }
                    fail("The controller rejected the ${write.label} write (GATT status $status).")
                } else {
                    val chunkNumber = write.nextChunkIndex + 1
                    recordDiagnosticEvent("write_chunk_ack operation=${write.label} chunk=$chunkNumber/${write.chunks.size}") {
                        it.copy(
                            writeChunksAcknowledged = it.writeChunksAcknowledged + 1,
                            credentialWriteChunksAcknowledged = if (write.label == CREDENTIAL_WRITE_LABEL) {
                                it.credentialWriteChunksAcknowledged + 1
                            } else {
                                it.credentialWriteChunksAcknowledged
                            },
                        )
                    }
                    write.nextChunkIndex += 1
                    if (write.nextChunkIndex < write.chunks.size) {
                        submitNextWriteChunk(write)
                    } else {
                        pendingWrite = null
                        write.chunks.forEach { it.fill(0) }
                        recordDiagnosticEvent("write_ack_complete operation=${write.label}") {
                            val completed = it.writesAcknowledged.toMutableMap()
                            completed[write.label] = (completed[write.label] ?: 0) + 1
                            it.copy(
                                writesAcknowledged = completed,
                                credentialWriteAcknowledged = if (write.label == CREDENTIAL_WRITE_LABEL) true else it.credentialWriteAcknowledged,
                            )
                        }
                        write.onSuccess()
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (currentSession != sessionId ||
                characteristic.uuid != UUID.fromString(GrillirGProtocol.WRITE_NOTIFY_CHARACTERISTIC_UUID)
            ) return
            val copy = value.copyOf()
            handler.post { if (currentSession == sessionId) handleNotification(copy) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (currentSession != sessionId ||
                characteristic.uuid != UUID.fromString(GrillirGProtocol.WRITE_NOTIFY_CHARACTERISTIC_UUID)
            ) return
            val copy = characteristic.value?.copyOf() ?: return
            handler.post { if (currentSession == sessionId) handleNotification(copy) }
        }
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        descriptor: BluetoothGattDescriptor,
        currentSession: Int,
    ) {
        update(
            GrillirGSetupStage.ENABLING_NOTIFICATIONS,
            "Enabling the controller's Wi-Fi setup response channel…",
        )
        val localEnabled = try {
            gatt.setCharacteristicNotification(characteristic, true)
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!localEnabled) {
            fail("Android could not subscribe to the controller's Wi-Fi setup characteristic.")
            return
        }
        armTimeout(DESCRIPTOR_WRITE_TIMEOUT_MILLIS, "The controller did not acknowledge notification setup.")
        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted && currentSession == sessionId) fail("Android did not start notification setup.")
    }

    private fun beginServiceDiscovery(gatt: BluetoothGatt, currentSession: Int, mtu: Int) {
        if (currentSession != sessionId) return
        update(
            GrillirGSetupStage.DISCOVERING_SERVICES,
            "BLE MTU $mtu negotiated. Checking for the GrillirG Wi-Fi setup service…",
        )
        armTimeout(SERVICE_DISCOVERY_TIMEOUT_MILLIS, "The controller did not finish GATT service discovery.")
        val accepted = try {
            gatt.discoverServices()
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) fail("Android did not start GATT service discovery.")
    }

    private fun writeFrame(frame: ByteArray, label: String, onSuccess: () -> Unit) {
        val activeGatt = gatt
        val characteristic = writeCharacteristic
        if (activeGatt == null || characteristic == null) {
            frame.fill(0)
            fail("The Bluetooth setup session is no longer connected.")
            return
        }
        if (pendingWrite != null) {
            frame.fill(0)
            fail("A Bluetooth write was already in progress.")
            return
        }
        val chunks = try {
            GrillirGProtocol.splitForGattWrites(frame)
        } catch (_: IllegalArgumentException) {
            frame.fill(0)
            fail("The ${label} frame could not be split into Bluetooth writes.")
            return
        }
        recordDiagnosticEvent("write_started operation=$label frameBytes=${frame.size} chunks=${chunks.size}") {
            val started = it.writesStarted.toMutableMap()
            started[label] = (started[label] ?: 0) + 1
            it.copy(
                writesStarted = started,
                credentialWriteAcknowledged = if (label == CREDENTIAL_WRITE_LABEL) false else it.credentialWriteAcknowledged,
                credentialWriteChunksAcknowledged = if (label == CREDENTIAL_WRITE_LABEL) 0 else it.credentialWriteChunksAcknowledged,
                credentialWriteChunksTotal = if (label == CREDENTIAL_WRITE_LABEL) chunks.size else it.credentialWriteChunksTotal,
            )
        }
        frame.fill(0)
        pendingWrite = PendingWrite(chunks = chunks, label = label, onSuccess = onSuccess)
        submitNextWriteChunk(pendingWrite!!)
    }

    private fun submitNextWriteChunk(write: PendingWrite) {
        val activeGatt = gatt
        val characteristic = writeCharacteristic
        if (activeGatt == null || characteristic == null || pendingWrite !== write) {
            fail("The Bluetooth setup session is no longer connected.")
            return
        }
        val chunk = write.chunks[write.nextChunkIndex]
        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            fail("Android did not start the ${write.label} write.")
        } else {
            armTimeout(WRITE_TIMEOUT_MILLIS, "The controller did not acknowledge the ${write.label} write.")
        }
    }

    private fun handleNotification(bytes: ByteArray) {
        recordDiagnosticEvent("notification_received bytes=${bytes.size}") {
            it.copy(
                notificationsReceived = it.notificationsReceived + 1,
                notificationsAfterCredentialRequest = it.notificationsAfterCredentialRequest +
                    if (it.credentialWriteAcknowledged != null || CREDENTIAL_WRITE_LABEL in it.writesStarted) 1 else 0,
                notificationBytesReceived = it.notificationBytesReceived + bytes.size,
            )
        }
        val frame = GrillirGProtocol.parseFrame(bytes)
        if (frame == null) {
            recordDiagnosticEvent("notification_frame_unparsed") {
                it.copy(invalidFramesReceived = it.invalidFramesReceived + 1)
            }
            publish()
            return
        }
        recordDiagnosticEvent("frame_parsed command=${frame.command} payloadBytes=${frame.payload.size}") {
            val commandCounts = it.parsedCommandCounts.toMutableMap()
            commandCounts[frame.command] = (commandCounts[frame.command] ?: 0) + 1
            it.copy(
                validFramesReceived = it.validFramesReceived + 1,
                parsedCommandCounts = commandCounts,
                wifiStatusFramesReceived = it.wifiStatusFramesReceived +
                    if (frame.command == 4 && snapshot.stage in WIFI_RESULT_STAGES) 1 else 0,
            )
        }
        when {
            frame.command == 2 && snapshot.stage in NETWORK_SCAN_STAGES -> {
                if (GrillirGProtocol.isWifiScanComplete(frame)) {
                    cancelTimeout()
                    recordDiagnosticEvent("wifi_scan_complete_marker") {
                        it.copy(wifiScanComplete = true)
                    }
                    val message = if (snapshot.networks.isEmpty()) {
                        "The controller finished scanning but returned no Wi-Fi networks."
                    } else {
                        "Found ${snapshot.networks.size} Wi-Fi network(s). Select the grill's 2.4 GHz network below."
                    }
                    update(GrillirGSetupStage.NETWORKS_READY, message)
                } else {
                    val network = GrillirGProtocol.parseWifiNetwork(frame.payload)
                    if (network == null) {
                        recordDiagnosticEvent("wifi_network_record_unparsed") {
                            it.copy(unparsedWifiNetworkRecords = it.unparsedWifiNetworkRecords + 1)
                        }
                        publish()
                        return
                    }
                    val byBssid = snapshot.networks.associateBy { it.key }.toMutableMap()
                    val isNewNetwork = network.key !in byBssid
                    if (isNewNetwork) byBssid[network.key] = network
                    if (isNewNetwork) {
                        recordDiagnosticEvent("wifi_network_record_received count=${byBssid.size}") {
                            it.copy(wifiNetworkRecordsReceived = it.wifiNetworkRecordsReceived + 1)
                        }
                    }
                    snapshot = snapshot.copy(
                        networks = byBssid.values.toList(),
                        message = "Scanning… found ${byBssid.size} Wi-Fi network(s).",
                        diagnostics = diagnostics,
                    )
                    publish()
                }
            }
            frame.command == 4 && snapshot.stage in WIFI_RESULT_STAGES -> {
                val status = GrillirGProtocol.wifiConnectStatus(frame)
                if (status == null) {
                    val candidates = GrillirGProtocol.wifiStatusCodeCandidates(frame)
                        .joinToString { (index, code) -> "byte$index=$code" }
                        .ifBlank { "none" }
                    recordDiagnosticEvent(
                        "wifi_status_frame_malformed payloadBytes=${frame.payload.size} knownStatusCandidates=$candidates",
                    ) {
                        it.copy(wifiStatusFramesMalformed = it.wifiStatusFramesMalformed + 1)
                    }
                    snapshot = snapshot.copy(
                        message = "The controller replied, but PitTech could not decode its ${frame.payload.size}-byte Wi-Fi status frame. Waiting for a supported result.",
                        diagnostics = diagnostics,
                    )
                    publish()
                    return
                }
                cancelTimeout()
                pendingWrite?.chunks?.forEach { it.fill(0) }
                pendingWrite = null
                val success = status == 1
                recordDiagnosticEvent("wifi_status_received code=$status") {
                    it.copy(
                        wifiStatusCode = status,
                        currentStage = if (success) GrillirGSetupStage.COMPLETE.name else GrillirGSetupStage.FAILED.name,
                    )
                }
                snapshot = snapshot.copy(
                    stage = if (success) GrillirGSetupStage.COMPLETE else GrillirGSetupStage.FAILED,
                    message = GrillirGProtocol.wifiStatusMessage(status),
                    wifiStatusCode = status,
                    diagnostics = diagnostics,
                )
                publish()
                closeGatt()
            }
        }
    }

    private fun scanTimeoutMessage(): String = if (snapshot.networks.isEmpty()) {
        "The controller did not return a Wi-Fi scan result."
    } else {
        "Wi-Fi scanning timed out; the networks received so far are available to select."
    }

    private fun wifiConnectionTimeoutMessage(): String = if (diagnostics.wifiStatusFramesMalformed > 0) {
        "The controller sent ${diagnostics.wifiStatusFramesMalformed} command-4 frame(s), but PitTech could not decode them as a supported Wi-Fi result."
    } else {
        "No Wi-Fi connection result arrived from the controller."
    }

    private fun armTimeout(durationMillis: Long, message: String) {
        cancelTimeout()
        val task = Runnable {
            timeout = null
            val timeoutMessage = if (snapshot.stage == GrillirGSetupStage.WAITING_FOR_WIFI) {
                wifiConnectionTimeoutMessage()
            } else {
                message
            }
            if (timeoutMessage.startsWith("Wi-Fi scanning timed out") && snapshot.networks.isNotEmpty()) {
                recordDiagnosticEvent("timeout stage=${snapshot.stage.name}") {
                    it.copy(failureAtStage = snapshot.stage.name)
                }
                update(GrillirGSetupStage.NETWORKS_READY, timeoutMessage)
            } else {
                fail(timeoutMessage)
            }
        }
        timeout = task
        handler.postDelayed(task, durationMillis)
    }

    private fun cancelTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    private fun update(stage: GrillirGSetupStage, message: String) {
        recordDiagnosticEvent("stage=${stage.name}") { it.copy(currentStage = stage.name) }
        snapshot = snapshot.copy(stage = stage, message = message, diagnostics = diagnostics)
        publish()
    }

    private fun recordDiagnosticEvent(
        event: String,
        transform: (GrillirGSetupDiagnostics) -> GrillirGSetupDiagnostics = { it },
    ) {
        val elapsed = if (setupStartedAtElapsedRealtime == 0L) 0L else {
            (SystemClock.elapsedRealtime() - setupStartedAtElapsedRealtime).coerceAtLeast(0)
        }
        val previous = diagnostics
        diagnostics = transform(previous).copy(
            elapsedMillis = elapsed,
            events = (previous.events + "+${elapsed}ms $event").takeLast(MAX_DIAGNOSTIC_EVENTS),
        )
        snapshot = snapshot.copy(diagnostics = diagnostics)
    }

    private fun publish() {
        val value = snapshot.copy(networks = snapshot.networks.toList(), diagnostics = diagnostics)
        callback?.invoke(value)
    }

    private fun fail(message: String) {
        val failedAtStage = snapshot.stage.name
        recordDiagnosticEvent("failed stage=$failedAtStage") {
            it.copy(failureAtStage = failedAtStage, currentStage = GrillirGSetupStage.FAILED.name)
        }
        cancelTimeout()
        pendingWrite?.chunks?.forEach { it.fill(0) }
        pendingWrite = null
        snapshot = snapshot.copy(stage = GrillirGSetupStage.FAILED, message = message, diagnostics = diagnostics)
        publish()
        closeGatt()
    }

    private fun closeGatt() {
        val active = gatt
        gatt = null
        writeCharacteristic = null
        cccd = null
        if (active != null) {
            runCatching { active.disconnect() }
            runCatching { active.close() }
        }
    }

    private data class PendingWrite(
        val chunks: List<ByteArray>,
        val label: String,
        val onSuccess: () -> Unit,
        var nextChunkIndex: Int = 0,
    )

    private companion object {
        const val CREDENTIAL_WRITE_LABEL = "Wi-Fi credentials"
        const val MAX_DIAGNOSTIC_EVENTS = 40
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CONNECTION_TIMEOUT_MILLIS = 18_000L
        const val MTU_NEGOTIATION_TIMEOUT_MILLIS = 10_000L
        const val SERVICE_DISCOVERY_TIMEOUT_MILLIS = 12_000L
        const val DESCRIPTOR_WRITE_TIMEOUT_MILLIS = 8_000L
        const val WRITE_TIMEOUT_MILLIS = 8_000L
        const val WIFI_SCAN_TIMEOUT_MILLIS = 35_000L
        const val WIFI_CONNECT_TIMEOUT_MILLIS = 90_000L
        const val PREFERRED_GATT_MTU = 247
        const val MINIMUM_GATT_MTU = 54
        val NETWORK_SCAN_STAGES = setOf(
            GrillirGSetupStage.REQUESTING_NETWORKS,
            GrillirGSetupStage.NETWORKS_READY,
        )
        val WIFI_RESULT_STAGES = setOf(
            GrillirGSetupStage.SENDING_CREDENTIALS,
            GrillirGSetupStage.WAITING_FOR_WIFI,
        )
        val TERMINAL_STAGES = setOf(GrillirGSetupStage.COMPLETE, GrillirGSetupStage.FAILED)
    }
}
