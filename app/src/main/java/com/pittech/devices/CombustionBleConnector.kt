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
) : ReadOnlyTelemetryConnector {
    override val connectorId = "combustion"
    private var scanner: Job? = null
    private var observers = 0
    @Synchronized private fun stopIfIdle() { if (observers == 0) { scanner?.cancel(); scanner = null } }
    private val seen = ConcurrentHashMap<String, List<TelemetryObservation>>()

    @Synchronized private fun scanIfNeeded() {
        if (scanner?.isActive == true) return
        scanner = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                ProbeScanner.scan()
                kotlinx.coroutines.delay(5_000L)
            }
        }
    }

    override suspend fun discover(): List<TelemetrySource> {
        val detected = LinkedHashSet<String>()
        scanIfNeeded()
        withTimeoutOrNull(8_000L) {
            ProbeScanner.scanResults.collect { result ->
                result.serialNumber?.takeIf { it.isNotBlank() }?.let { detected.add(it); seen[it] = decode(result, clock()) }
            }
        }
        stopIfIdle()
        return detected.map(::source)
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
        seen[deviceId]?.takeIf { samples -> samples.any { clock() - it.receivedAtMillis in 0..15_000L } }?.let { return it }
        scanIfNeeded()
        return try {
            withTimeoutOrNull(8_000L) {
                ProbeScanner.scanResults.first { it.serialNumber == deviceId }.let { decode(it, clock()) }
                    .also { seen[deviceId] = it }
            }.orEmpty()
        } finally { stopIfIdle() }
    }

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = flow {
        require(deviceId.isNotBlank())
        synchronized(this@CombustionBleConnector) { observers++ }
        scanIfNeeded()
        try { ProbeScanner.scanResults.filter { it.serialNumber == deviceId }.collect { result ->
            val received = clock()
            val sample = decode(result, received)
            seen[deviceId] = sample
            emit(sample)
        } } finally { synchronized(this@CombustionBleConnector) { observers-- }; stopIfIdle() }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(untilMillis >= fromMillis)
        // Probe log transfer is SDK-supported but not yet implemented by passive scanning.
        return emptyList()
    }

    override suspend fun disconnect() {
        scanner?.cancel()
        scanner = null
        seen.clear()
    }

    internal companion object {
        fun source(serial: String): TelemetrySource = TelemetrySource(
            "combustion", serial, "Combustion probe " + serial.takeLast(4), "Combustion",
            channels = (0..7).map { TelemetryChannel("sensor" + it, "Sensor " + (it + 1), TelemetryKind.FOOD) } +
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
