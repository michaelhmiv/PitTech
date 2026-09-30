package com.pittech.devices

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class NearbyBluetoothDevice(
    val key: String,
    val advertisedName: String?,
    val address: String?,
    val rssi: Int,
    val advertisements: List<BluetoothAdvertisementVariant>,
    val omittedAdvertisementVariants: Int,
    val discoveryPaths: Set<BluetoothDiscoveryPath> = setOf(BluetoothDiscoveryPath.BLE_ADVERTISEMENT),
    val bluetoothDeviceType: String? = null,
    val adapterName: String? = null,
) {
    val observationCount: Int get() = advertisements.sumOf { it.observationCount }
    val displayName: String
        get() = advertisedName?.trim()?.takeIf { it.isNotEmpty() }
            ?: adapterName?.trim()?.takeIf { it.isNotEmpty() }
            ?: address?.takeLast(5)?.let { "Unnamed Bluetooth device · $it" }
            ?: "Unnamed Bluetooth device"
}

data class BluetoothAdvertisementVariant(
    val firstSeenOffsetMillis: Long,
    val lastSeenOffsetMillis: Long,
    val observationCount: Int,
    val weakestRssi: Int,
    val strongestRssi: Int,
    val advertisedName: String?,
    val txPower: Int?,
    val connectable: Boolean?,
    val advertiseFlags: Int?,
    val primaryPhy: Int?,
    val secondaryPhy: Int?,
    val advertisingSid: Int?,
    val periodicAdvertisingInterval: Int?,
    val dataStatus: Int?,
    val serviceUuids: List<String>,
    val serviceSolicitationUuids: List<String>,
    val manufacturerData: Map<String, String>,
    val serviceData: Map<String, String>,
    val rawRecordHex: String,
)

data class BluetoothScanSummary(
    val startedAtUtc: String,
    val finishedAtUtc: String,
    val durationMillis: Long,
    val scanMode: String,
    val totalResults: Int,
    val capturedDeviceCount: Int,
    val omittedDeviceCount: Int,
    val error: String?,
    val bleResultCount: Int = 0,
    val adapterDiscoveryResultCount: Int = 0,
)

enum class BluetoothDiscoveryPath(val displayLabel: String) {
    BLE_ADVERTISEMENT("BLE advertisement"),
    ANDROID_ADAPTER_DISCOVERY("Android adapter discovery"),
}

internal data class BluetoothDiscoveryMetadata(
    val advertisedName: String?,
    val adapterName: String?,
    val address: String?,
    val rssi: Int,
    val discoveryPaths: Set<BluetoothDiscoveryPath>,
    val bluetoothDeviceType: String?,
)

internal fun mergeBluetoothDiscoveryMetadata(
    existing: BluetoothDiscoveryMetadata?,
    advertisedName: String?,
    adapterName: String?,
    address: String?,
    rssi: Int?,
    discoveryPath: BluetoothDiscoveryPath,
    bluetoothDeviceType: String?,
): BluetoothDiscoveryMetadata {
    val usableName = advertisedName?.takeIf { it.isNotBlank() }
    if (existing == null) {
        return BluetoothDiscoveryMetadata(
            advertisedName = usableName,
            adapterName = adapterName?.takeIf { it.isNotBlank() },
            address = address,
            rssi = rssi ?: Int.MIN_VALUE,
            discoveryPaths = setOf(discoveryPath),
            bluetoothDeviceType = bluetoothDeviceType,
        )
    }

    return existing.copy(
        advertisedName = existing.advertisedName?.takeIf { it.isNotBlank() } ?: usableName,
        adapterName = existing.adapterName?.takeIf { it.isNotBlank() }
            ?: adapterName?.takeIf { it.isNotBlank() },
        address = existing.address ?: address,
        rssi = rssi ?: existing.rssi,
        discoveryPaths = existing.discoveryPaths + discoveryPath,
        bluetoothDeviceType = bluetoothDeviceType ?: existing.bluetoothDeviceType,
    )
}

/**
 * Foreground-only Bluetooth discovery. It first retains distinct BLE
 * advertisements, then runs Android adapter discovery and merges candidates
 * by address. The report UI sends only the device the user chooses.
 */
class ControllerBleDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var activeScanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null
    private var activeAdapter: BluetoothAdapter? = null
    private var activeReceiver: BroadcastReceiver? = null
    private var activeSessionId = 0
    private var timeout: Runnable? = null

    fun startScan(
        onDevices: (List<NearbyBluetoothDevice>) -> Unit,
        onFinished: (BluetoothScanSummary, String?) -> Unit,
    ) {
        stopScan()
        val scanSessionId = activeSessionId

        val startWallTime = System.currentTimeMillis()
        val startElapsedTime = SystemClock.elapsedRealtime()
        val startElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val found = linkedMapOf<String, MutableDevice>()
        var totalResults = 0
        var bleResultCount = 0
        var adapterDiscoveryResultCount = 0
        var omittedDevices = 0
        var bleError: String? = null
        var adapterDiscoveryError: String? = null
        val omittedDeviceKeys = mutableSetOf<String>()

        fun snapshot(): List<NearbyBluetoothDevice> = found.values
            .map { it.toSnapshot() }
            .sortedByDescending { it.rssi }

        var lastPublishedElapsedMillis = 0L
        var pendingPublish: Runnable? = null
        fun publishDevices(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            val elapsedSinceLastPublish = now - lastPublishedElapsedMillis
            if (force || elapsedSinceLastPublish >= PUBLISH_INTERVAL_MILLIS) {
                pendingPublish?.let(handler::removeCallbacks)
                pendingPublish = null
                lastPublishedElapsedMillis = now
                onDevices(snapshot())
            } else if (pendingPublish == null) {
                val task = Runnable {
                    pendingPublish = null
                    lastPublishedElapsedMillis = SystemClock.elapsedRealtime()
                    onDevices(snapshot())
                }
                pendingPublish = task
                handler.postDelayed(
                    task,
                    (PUBLISH_INTERVAL_MILLIS - elapsedSinceLastPublish).coerceAtLeast(1L),
                )
            }
        }

        fun combinedError(): String? = listOfNotNull(bleError, adapterDiscoveryError)
            .joinToString("; ")
            .ifBlank { null }

        fun summary(error: String?): BluetoothScanSummary = BluetoothScanSummary(
            startedAtUtc = formatUtc(startWallTime),
            finishedAtUtc = formatUtc(System.currentTimeMillis()),
            durationMillis = (SystemClock.elapsedRealtime() - startElapsedTime).coerceAtLeast(0L),
            scanMode = "BLE_LOW_LATENCY_THEN_ANDROID_ADAPTER_DISCOVERY",
            totalResults = totalResults,
            capturedDeviceCount = found.size,
            omittedDeviceCount = omittedDevices,
            error = error,
            bleResultCount = bleResultCount,
            adapterDiscoveryResultCount = adapterDiscoveryResultCount,
        )

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = try {
            manager?.adapter
        } catch (_: SecurityException) {
            null
        }
        if (adapter == null || adapter.state != BluetoothAdapter.STATE_ON) {
            val error = "Bluetooth is unavailable or turned off."
            onFinished(summary(error), error)
            return
        }
        activeAdapter = adapter

        var finished = false
        var adapterDiscoveryStarted = false

        fun finishScan() {
            if (finished || activeSessionId != scanSessionId) return
            finished = true
            val error = combinedError()
            stopScan()
            publishDevices(force = true)
            val message = if (found.isEmpty()) error ?: "No Bluetooth devices were detected." else null
            onFinished(summary(error), message)
        }

        fun mergeCandidate(
            key: String,
            advertisedName: String?,
            adapterName: String?,
            address: String?,
            rssi: Int?,
            discoveryPath: BluetoothDiscoveryPath,
            bluetoothDeviceType: String?,
        ): MutableDevice? {
            var device = found[key]
            if (device == null) {
                if (found.size >= MAX_TRACKED_DEVICES) {
                    if (omittedDeviceKeys.add(key)) omittedDevices = omittedDeviceKeys.size
                    return null
                }
                device = MutableDevice(
                    key = key,
                    metadata = mergeBluetoothDiscoveryMetadata(
                        existing = null,
                        advertisedName = advertisedName,
                        adapterName = adapterName,
                        address = address,
                        rssi = rssi,
                        discoveryPath = discoveryPath,
                        bluetoothDeviceType = bluetoothDeviceType,
                    ),
                )
                found[key] = device
            } else {
                device.mergeMetadata(
                    advertisedName = advertisedName,
                    adapterName = adapterName,
                    address = address,
                    rssi = rssi,
                    discoveryPath = discoveryPath,
                    bluetoothDeviceType = bluetoothDeviceType,
                )
            }
            return device
        }

        fun recordAdapterDiscoveryResult(intent: Intent) {
            val device = intent.bluetoothDeviceExtra() ?: return
            totalResults += 1
            adapterDiscoveryResultCount += 1

            val adapterName = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            val address = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() }
            val deviceType = runCatching { device.type }.getOrNull()?.let(::bluetoothDeviceTypeLabel)
            val key = address?.uppercase(Locale.ROOT)
                ?: "adapter:${adapterName.orEmpty()}:${System.identityHashCode(device)}"
            val reportedRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                .takeIf { it != Short.MIN_VALUE }
                ?.toInt()

            mergeCandidate(
                key = key,
                advertisedName = null,
                adapterName = adapterName,
                address = address,
                rssi = reportedRssi,
                discoveryPath = BluetoothDiscoveryPath.ANDROID_ADAPTER_DISCOVERY,
                bluetoothDeviceType = deviceType,
            )
            publishDevices()
        }

        fun beginAdapterDiscovery() {
            if (finished || activeSessionId != scanSessionId || adapterDiscoveryStarted) return
            adapterDiscoveryStarted = true
            timeout?.let(handler::removeCallbacks)
            timeout = null
            stopActiveBleScan()

            var receivedDiscoveryStarted = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val receivedIntent = intent ?: return
                    when (receivedIntent.action) {
                        BluetoothDevice.ACTION_FOUND -> recordAdapterDiscoveryResult(receivedIntent)
                        BluetoothAdapter.ACTION_DISCOVERY_STARTED -> receivedDiscoveryStarted = true
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            if (receivedDiscoveryStarted) finishScan()
                        }
                    }
                }
            }
            activeReceiver = receiver
            try {
                registerAdapterDiscoveryReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(BluetoothDevice.ACTION_FOUND)
                        addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                    },
                )
            } catch (_: SecurityException) {
                activeReceiver = null
                adapterDiscoveryError = "Bluetooth scan permission was not granted for Android device discovery."
                finishScan()
                return
            }

            val started = try {
                adapter.startDiscovery()
            } catch (_: SecurityException) {
                adapterDiscoveryError = "Bluetooth scan permission was not granted for Android device discovery."
                false
            } catch (_: IllegalStateException) {
                adapterDiscoveryError = "Android Bluetooth discovery could not start."
                false
            }
            if (!started) {
                if (adapterDiscoveryError == null) {
                    adapterDiscoveryError = "Android Bluetooth discovery did not start."
                }
                activeReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
                activeReceiver = null
                finishScan()
                return
            }

            if (!finished && activeSessionId == scanSessionId) {
                val timeoutTask = Runnable {
                    if (!receivedDiscoveryStarted) {
                        adapterDiscoveryError = "Android Bluetooth discovery did not report that it started."
                    } else {
                        adapterDiscoveryError = "Android Bluetooth discovery timed out."
                    }
                    finishScan()
                }
                timeout = timeoutTask
                handler.postDelayed(timeoutTask, ADAPTER_DISCOVERY_TIMEOUT_MILLIS)
            }
        }

        fun recordBleResult(result: ScanResult) {
            if (finished || activeSessionId != scanSessionId || adapterDiscoveryStarted) return
            totalResults += 1
            bleResultCount += 1
            val record = result.scanRecord
            val advertisedName = record?.deviceName?.takeIf { it.isNotBlank() }
            val rawRecordHex = record?.bytes?.toHex().orEmpty()
            val serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() }.sorted()
            val serviceSolicitationUuids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                record?.serviceSolicitationUuids.orEmpty().map { it.uuid.toString() }.sorted()
            } else {
                emptyList()
            }
            val manufacturerData = record?.manufacturerSpecificData?.let { sparse ->
                buildMap {
                    for (index in 0 until sparse.size()) {
                        put("0x%04X".format(Locale.US, sparse.keyAt(index)), sparse.valueAt(index).toHex())
                    }
                }.toSortedMap()
            }.orEmpty()
            val serviceData = record?.serviceData.orEmpty()
                .mapKeys { it.key.uuid.toString() }
                .mapValues { it.value.toHex() }
                .toSortedMap()
            val address = runCatching { result.device.address }.getOrNull()
            val key = address?.uppercase(Locale.ROOT)
                ?: listOf(advertisedName.orEmpty(), rawRecordHex, serviceUuids.joinToString(","))
                    .joinToString("|")
                    .takeIf { it != "||" }
                    ?.let { "unaddressed:$it" }
                ?: "unidentified"
            val bluetoothDeviceType = runCatching { result.device.type }
                .getOrNull()
                ?.let(::bluetoothDeviceTypeLabel)
            val device = mergeCandidate(
                key = key,
                advertisedName = advertisedName,
                adapterName = null,
                address = address,
                rssi = result.rssi,
                discoveryPath = BluetoothDiscoveryPath.BLE_ADVERTISEMENT,
                bluetoothDeviceType = bluetoothDeviceType,
            )
            if (device == null) {
                publishDevices()
                return
            }

            val offsetMillis = (
                result.timestampNanos / 1_000_000L -
                    startElapsedNanos / 1_000_000L
                ).coerceAtLeast(0L)
            val txPower = record?.txPowerLevel?.takeIf { it in -127..20 }
            val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { result.isConnectable }.getOrNull()
            } else {
                null
            }
            val variantKey = listOf(
                rawRecordHex,
                serviceUuids.joinToString(","),
                serviceSolicitationUuids.joinToString(","),
                manufacturerData.toString(),
                serviceData.toString(),
                txPower.toString(),
                connectable.toString(),
            ).joinToString("|")
            val variant = device.variants[variantKey]
            if (variant != null) {
                variant.addObservation(offsetMillis, result.rssi)
            } else if (device.variants.size < MAX_VARIANTS_PER_DEVICE) {
                device.variants[variantKey] = MutableVariant(
                    firstSeenOffsetMillis = offsetMillis,
                    lastSeenOffsetMillis = offsetMillis,
                    observationCount = 1,
                    weakestRssi = result.rssi,
                    strongestRssi = result.rssi,
                    advertisedName = advertisedName,
                    txPower = txPower,
                    connectable = connectable,
                    advertiseFlags = record?.advertiseFlags?.takeIf { it >= 0 },
                    primaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.primaryPhy else null,
                    secondaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.secondaryPhy else null,
                    advertisingSid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        result.advertisingSid.takeIf { it >= 0 }
                    } else null,
                    periodicAdvertisingInterval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        result.periodicAdvertisingInterval.takeIf { it >= 0 }
                    } else null,
                    dataStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.dataStatus else null,
                    serviceUuids = serviceUuids,
                    serviceSolicitationUuids = serviceSolicitationUuids,
                    manufacturerData = manufacturerData,
                    serviceData = serviceData,
                    rawRecordHex = rawRecordHex,
                )
            } else {
                device.omittedAdvertisementVariants += 1
            }
            publishDevices()
        }

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        }
        if (scanner == null) {
            bleError = "BLE scanner is unavailable; continuing with Android adapter discovery."
            beginAdapterDiscovery()
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = recordBleResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::recordBleResult)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!adapterDiscoveryStarted) {
                    bleError = "BLE scan failed (code $errorCode)."
                    beginAdapterDiscovery()
                }
            }
        }

        activeScanner = scanner
        activeCallback = callback
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
        } catch (_: SecurityException) {
            bleError = "Bluetooth scan permission was not granted for BLE discovery."
            beginAdapterDiscovery()
            return
        } catch (_: IllegalStateException) {
            bleError = "BLE scanner is unavailable or Bluetooth is turned off."
            beginAdapterDiscovery()
            return
        }

        val timeoutTask = Runnable { beginAdapterDiscovery() }
        timeout = timeoutTask
        handler.postDelayed(timeoutTask, BLE_SCAN_DURATION_MILLIS)
    }

    fun stopScan() {
        activeSessionId += 1
        timeout?.let(handler::removeCallbacks)
        timeout = null
        stopActiveBleScan()

        val receiver = activeReceiver
        activeReceiver = null
        if (receiver != null) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // The discovery receiver may already have been removed.
            }
        }

        val adapter = activeAdapter
        activeAdapter = null
        if (adapter != null) {
            try {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // Permission may have been revoked while discovery was running.
            } catch (_: IllegalStateException) {
                // Bluetooth may have been switched off during discovery.
            }
        }
    }

    private fun stopActiveBleScan() {
        val scanner = activeScanner
        val callback = activeCallback
        activeScanner = null
        activeCallback = null
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission may have been revoked while the screen was open.
            } catch (_: IllegalStateException) {
                // Bluetooth may have been switched off during discovery.
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerAdapterDiscoveryReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun bluetoothDeviceTypeLabel(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "UNKNOWN"
    }

    private data class MutableDevice(
        val key: String,
        var metadata: BluetoothDiscoveryMetadata,
        val variants: LinkedHashMap<String, MutableVariant> = linkedMapOf(),
        var omittedAdvertisementVariants: Int = 0,
    ) {
        fun mergeMetadata(
            advertisedName: String?,
            adapterName: String?,
            address: String?,
            rssi: Int?,
            discoveryPath: BluetoothDiscoveryPath,
            bluetoothDeviceType: String?,
        ) {
            metadata = mergeBluetoothDiscoveryMetadata(
                existing = metadata,
                advertisedName = advertisedName,
                adapterName = adapterName,
                address = address,
                rssi = rssi,
                discoveryPath = discoveryPath,
                bluetoothDeviceType = bluetoothDeviceType,
            )
        }

        fun toSnapshot() = NearbyBluetoothDevice(
            key = key,
            advertisedName = metadata.advertisedName,
            adapterName = metadata.adapterName,
            address = metadata.address,
            rssi = metadata.rssi,
            advertisements = variants.values.map { it.toSnapshot() },
            omittedAdvertisementVariants = omittedAdvertisementVariants,
            discoveryPaths = metadata.discoveryPaths,
            bluetoothDeviceType = metadata.bluetoothDeviceType,
        )
    }

    private data class MutableVariant(
        val firstSeenOffsetMillis: Long,
        var lastSeenOffsetMillis: Long,
        var observationCount: Int,
        var weakestRssi: Int,
        var strongestRssi: Int,
        val advertisedName: String?,
        val txPower: Int?,
        val connectable: Boolean?,
        val advertiseFlags: Int?,
        val primaryPhy: Int?,
        val secondaryPhy: Int?,
        val advertisingSid: Int?,
        val periodicAdvertisingInterval: Int?,
        val dataStatus: Int?,
        val serviceUuids: List<String>,
        val serviceSolicitationUuids: List<String>,
        val manufacturerData: Map<String, String>,
        val serviceData: Map<String, String>,
        val rawRecordHex: String,
    ) {
        fun addObservation(offsetMillis: Long, rssi: Int) {
            lastSeenOffsetMillis = offsetMillis
            observationCount += 1
            weakestRssi = minOf(weakestRssi, rssi)
            strongestRssi = maxOf(strongestRssi, rssi)
        }

        fun toSnapshot() = BluetoothAdvertisementVariant(
            firstSeenOffsetMillis = firstSeenOffsetMillis,
            lastSeenOffsetMillis = lastSeenOffsetMillis,
            observationCount = observationCount,
            weakestRssi = weakestRssi,
            strongestRssi = strongestRssi,
            advertisedName = advertisedName,
            txPower = txPower,
            connectable = connectable,
            advertiseFlags = advertiseFlags,
            primaryPhy = primaryPhy,
            secondaryPhy = secondaryPhy,
            advertisingSid = advertisingSid,
            periodicAdvertisingInterval = periodicAdvertisingInterval,
            dataStatus = dataStatus,
            serviceUuids = serviceUuids,
            serviceSolicitationUuids = serviceSolicitationUuids,
            manufacturerData = manufacturerData,
            serviceData = serviceData,
            rawRecordHex = rawRecordHex,
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

    private fun formatUtc(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timestampMillis))

    private companion object {
        const val BLE_SCAN_DURATION_MILLIS = 12_000L
        const val ADAPTER_DISCOVERY_TIMEOUT_MILLIS = 15_000L
        const val PUBLISH_INTERVAL_MILLIS = 250L
        const val MAX_TRACKED_DEVICES = 100
        const val MAX_VARIANTS_PER_DEVICE = 80
    }
}
