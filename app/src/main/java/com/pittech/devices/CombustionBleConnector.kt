package com.pittech.devices

import inc.combustion.framework.service.ProbeScanResult
import inc.combustion.framework.service.ProbeScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional passive scanner using Combustion's official MIT Android BLE SDK.
 * No engine/fan, DFU, write, probe configuration or proprietary protocol methods.
 * Caller must obtain BLUETOOTH_SCAN permission and enabled Bluetooth first.
 */
internal class CombustionBleConnector(
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val advertisements: Flow<ProbeScanResult> = ProbeScanner.scanResults,
    private val scan: suspend () -> Unit = { ProbeScanner.scan() },
    private val scanWindowMillis: Long = 8_000L,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "combustion"
    private var scanner: Job? = null
    @Volatile private var closed = false
    private var observers = 0
    @Synchronized private fun stopIfIdle() { if (observers == 0) { scanner?.cancel(); scanner = null } }
    private val seen = ConcurrentHashMap<String, List<TelemetryObservation>>()

    @Synchronized private fun scanIfNeeded() {
        if (closed) return
        if (scanner?.isActive == true) return
        scanner = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                scan()
                kotlinx.coroutines.delay(5_000L)
            }
        }
    }

    private suspend fun <T> withScan(block: suspend () -> T): T {
        synchronized(this) { observers++ }
        scanIfNeeded()
        return try { block() } finally { synchronized(this) { observers-- }; stopIfIdle() }
    }

    override suspend fun discover(): List<TelemetrySource> = withScan {
        val detected = LinkedHashSet<String>()
        withTimeoutOrNull(scanWindowMillis) {
            advertisements.collect { result ->
                result.serialNumber?.takeIf { it.isNotBlank() }?.let { detected.add(it); seen[it] = decode(result, clock()) }
            }
        }
        detected.map(::source)
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
        if (closed) return emptyList()
        seen[deviceId]?.takeIf { samples -> samples.any { clock() - it.receivedAtMillis in 0..15_000L } }?.let { return it }
        return withScan {
            withTimeoutOrNull(scanWindowMillis) {
                advertisements.first { it.serialNumber == deviceId }.let { decode(it, clock()) }
                    .also { seen[deviceId] = it }
            }.orEmpty()
        }
    }

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = observe(deviceId, minimumIntervalMillis)
    override fun observe(deviceId: String, intervalMillis: Long): Flow<List<TelemetryObservation>> = flow {
        require(deviceId.isNotBlank())
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !closed) {
            val started = clock()
            emit(snapshot(deviceId))
            // Scan only for a bounded window per requested sample, not for the whole cook.
            kotlinx.coroutines.delay((maxOf(minimumIntervalMillis, intervalMillis) - (clock() - started).coerceAtLeast(0L)).coerceAtLeast(1_000L))
        }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(untilMillis >= fromMillis)
        // Probe log transfer is SDK-supported but not yet implemented by passive scanning.
        return emptyList()
    }

    override suspend fun disconnect() {
        closed = true
        scanner?.cancel()
        scanner = null
        seen.clear()
    }

    internal companion object {
        fun source(serial: String): TelemetrySource = TelemetrySource(
            "combustion", serial, "Combustion probe " + serial.takeLast(4), "Combustion",
            // The raw sensor array is not the SDK's derived core/surface measurement.
            channels = (0..7).map { TelemetryChannel("sensor" + it, "Raw sensor " + (it + 1),
                when (it) { 0 -> TelemetryKind.FOOD; 7 -> TelemetryKind.AMBIENT; else -> TelemetryKind.OTHER }) } +
                TelemetryChannel("instant", "Instant-read", TelemetryKind.FOOD),
        )

        /** Official temperature advertisements have no source timestamp. Use BLE receipt time explicitly. */
        fun decode(result: ProbeScanResult, received: Long): List<TelemetryObservation> {
            val readings = result.temperatures?.values.orEmpty().take(8).mapIndexed { index, c ->
                TelemetryObservation("sensor" + index, c.takeIf { it.isFinite() }, "°C",
                    observedAtMillis = null, receivedAtMillis = received)
            }
            val instant = result.instantReadTemperature?.takeIf { it.isFinite() }?.let {
                TelemetryObservation("instant", it, "°C", observedAtMillis = null, receivedAtMillis = received)
            }
            return readings + listOfNotNull(instant)
        }
    }
}

internal class CombustionFactory : TelemetryConnectorFactory {
    override suspend fun connect(context: android.content.Context, profile: TelemetryProfile, user: String, secret: String): ReadOnlyTelemetryConnector = restore(context, profile)
    override suspend fun restore(context: android.content.Context, profile: TelemetryProfile): ReadOnlyTelemetryConnector {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 31) listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        require(permissions.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) { "Allow Bluetooth access to discover Combustion probes." }
        require(context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true) { "Turn on Bluetooth to discover Combustion probes." }
        return CombustionBleConnector()
    }
}
