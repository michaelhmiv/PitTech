package com.pittech.devices

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
) {
    val observationCount: Int get() = advertisements.sumOf { it.observationCount }
    val displayName: String
        get() = advertisedName?.trim()?.takeIf { it.isNotEmpty() }
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
)

/**
 * Foreground-only BLE discovery. It retains the distinct advertisements seen
 * during a short scan so a user can report an unverified device for support.
 * The report UI sends only the device the user chooses.
 */
class ControllerBleDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var activeScanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null
    private var timeout: Runnable? = null

    fun startScan(
        onDevices: (List<NearbyBluetoothDevice>) -> Unit,
        onFinished: (BluetoothScanSummary, String?) -> Unit,
    ) {
        stopScan()

        val startWallTime = System.currentTimeMillis()
        val startElapsedTime = SystemClock.elapsedRealtime()
        val startElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val found = linkedMapOf<String, MutableDevice>()
        var totalResults = 0
        var omittedDevices = 0
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

        fun summary(error: String?): BluetoothScanSummary = BluetoothScanSummary(
            startedAtUtc = formatUtc(startWallTime),
            finishedAtUtc = formatUtc(System.currentTimeMillis()),
            durationMillis = (SystemClock.elapsedRealtime() - startElapsedTime).coerceAtLeast(0L),
            scanMode = "LOW_LATENCY",
            totalResults = totalResults,
            capturedDeviceCount = found.size,
            omittedDeviceCount = omittedDevices,
            error = error,
        )

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = try {
            manager?.adapter?.bluetoothLeScanner
        } catch (_: SecurityException) {
            onFinished(summary("Bluetooth permission was not granted."), "Bluetooth permission was not granted.")
            return
        }
        if (scanner == null) {
            onFinished(summary("Bluetooth is unavailable or turned off."), "Bluetooth is unavailable or turned off.")
            return
        }

        lateinit var finish: (String?) -> Unit
        var finished = false

        fun recordResult(result: ScanResult) {
            totalResults += 1
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
            var device = found[key]
            if (device == null) {
                if (found.size >= MAX_TRACKED_DEVICES) {
                    if (omittedDeviceKeys.add(key)) omittedDevices = omittedDeviceKeys.size
                    publishDevices()
                    return
                }
                device = MutableDevice(key, advertisedName, address)
                found[key] = device
            } else if (device.advertisedName.isNullOrBlank() && advertisedName != null) {
                device.advertisedName = advertisedName
            }

            val offsetMillis = (result.timestampNanos / 1_000_000L - startElapsedNanos / 1_000_000L).coerceAtLeast(0L)
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
            device.rssi = result.rssi
            publishDevices()
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = recordResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::recordResult)
            }

            override fun onScanFailed(errorCode: Int) {
                finish("Bluetooth scan failed (code $errorCode).")
            }
        }
        finish = { message ->
            if (!finished && activeCallback === callback) {
                finished = true
                stopScan()
                publishDevices(force = true)
                onFinished(summary(message), message)
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
            activeScanner = null
            activeCallback = null
            onFinished(summary("Bluetooth scan permission was not granted."), "Bluetooth scan permission was not granted.")
            return
        } catch (_: IllegalStateException) {
            activeScanner = null
            activeCallback = null
            onFinished(summary("Bluetooth is unavailable or turned off."), "Bluetooth is unavailable or turned off.")
            return
        }

        val timeoutTask = Runnable {
            finish(if (found.isEmpty()) "No Bluetooth devices were detected." else null)
        }
        timeout = timeoutTask
        handler.postDelayed(timeoutTask, SCAN_DURATION_MILLIS)
    }

    fun stopScan() {
        timeout?.let(handler::removeCallbacks)
        timeout = null

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

    private data class MutableDevice(
        val key: String,
        var advertisedName: String?,
        val address: String?,
        var rssi: Int = Int.MIN_VALUE,
        val variants: LinkedHashMap<String, MutableVariant> = linkedMapOf(),
        var omittedAdvertisementVariants: Int = 0,
    ) {
        fun toSnapshot() = NearbyBluetoothDevice(
            key = key,
            advertisedName = advertisedName,
            address = address,
            rssi = rssi,
            advertisements = variants.values.map { it.toSnapshot() },
            omittedAdvertisementVariants = omittedAdvertisementVariants,
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
        const val SCAN_DURATION_MILLIS = 12_000L
        const val PUBLISH_INTERVAL_MILLIS = 250L
        const val MAX_TRACKED_DEVICES = 100
        const val MAX_VARIANTS_PER_DEVICE = 80
    }
}
