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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
    private val seen = ConcurrentHashMap<String, List<TelemetryObservation>>()

    private fun scanIfNeeded() {
        if (scanner?.isActive == true) return
        scanner = scope.launch { ProbeScanner.scan() }
    }

    override suspend fun discover(): List<TelemetrySource> {
        val detected = LinkedHashSet<String>()
        scanIfNeeded()
        withTimeoutOrNull(8_000L) {
            ProbeScanner.scanResults.collect { result ->
                result.serialNumber?.takeIf { it.isNotBlank() }?.let { detected.add(it) }
            }
        }
        return detected.map(::source)
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> =
        seen[deviceId].orEmpty()

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = flow {
        require(deviceId.isNotBlank())
        scanIfNeeded()
        ProbeScanner.scanResults.filter { it.serialNumber == deviceId }.collect { result ->
            val received = clock()
            val sample = decode(result, received)
            seen[deviceId] = sample
            emit(sample)
        }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(untilMillis >= fromMillis)
        // Probe log transfer is SDK-supported but not yet implemented by passive scanning.
        return emptyList()
    }

    override suspend fun disconnect() {
        scanner?.cancel()
        scanner = null
        scope.cancel()
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
