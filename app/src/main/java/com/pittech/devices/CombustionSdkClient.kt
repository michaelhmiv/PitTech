package com.pittech.devices

import android.app.Application
import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.DeviceDiscoveryEvent
import inc.combustion.framework.service.DeviceManager
import inc.combustion.framework.service.Probe
import inc.combustion.framework.service.ProbeScanner
import inc.combustion.framework.service.LoggedProbeDataPoint
import inc.combustion.framework.service.ProbeUploadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The SDK is a process singleton. Serialize bounded service leases across connections, and
 * stop the actual service (stopScanningForDevices alone does not stop its radio scanner).
 * No configuration, power mode, engine, food-safety, alarm, prediction or firmware call occurs.
 */
internal class OfficialCombustionReadOnlyClient(private val application: Application) : CombustionReadOnlyClient {
    @Volatile private var closed = false
    private val operations = ConcurrentHashMap.newKeySet<Job>()

    private suspend fun <T> window(serial: String?, block: suspend (DeviceManager) -> T): T {
        check(!closed) { "This Bluetooth connection was removed." }
        val owner = currentCoroutineContext()[Job] ?: error("Bluetooth operation needs a cancellable owner")
        operations.add(owner)
        try {
            return withTimeout(30_000L) {
                serviceMutex.withLock {
                    check(!closed) { "This Bluetooth connection was removed." }
                    val ready = CompletableDeferred<DeviceManager>()
                    var started = false
                    var bound = false
                    try {
                        withContext(Dispatchers.Main) {
                            bindingWaiter = ready
                            DeviceManager.initialize(application, DeviceManager.Settings(
                                autoReconnect = false, autoLogTransfer = false, meatNetEnabled = true),
                                onBound = { bindingWaiter?.complete(it) })
                            DeviceManager.startCombustionService(notification = null)
                            started = true
                            DeviceManager.bindCombustionService()
                            bound = true
                        }
                        val manager = withTimeout(4_000L) { ready.await() }
                        manager.setDeviceAllowList(serial?.let { setOf(it) })
                        block(manager)
                    } finally {
                        // Cancellation/removal must also release the radio and SDK connections.
                        withContext(NonCancellable) {
                            if (started) coroutineScope {
                                val cleared = CompletableDeferred<Unit>()
                                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                                    DeviceManager.instance.discoveredDevicesFlow.first { it is DeviceDiscoveryEvent.DevicesCleared }
                                    cleared.complete(Unit)
                                }
                                try {
                                    withContext(Dispatchers.Main) {
                                        bindingWaiter = null
                                        if (bound) runCatching { DeviceManager.unbindCombustionService() }
                                        DeviceManager.stopCombustionService()
                                    }
                                    withTimeoutOrNull(3_000L) { cleared.await() }
                                    // Queue a Main turn after onDestroy; the SDK's stop flags are
                                    // reset there, after DevicesCleared is emitted.
                                    withContext(Dispatchers.Main) { }
                                } finally { observer.cancel() }
                            }
                        }
                    }
                }
            }
        } finally { operations.remove(owner) }
    }

    override suspend fun discover(): List<CombustionFrame> = window(null) { manager ->
        val detected = linkedSetOf<String>()
        withTimeoutOrNull(8_000L) {
            manager.discoveredDevicesFlow.collect { event ->
                if (event is DeviceDiscoveryEvent.ProbeDiscovered && event.serialNumber != "0")
                    detected.add(event.serialNumber)
            }
        }
        // DeviceScanner replays cached advertisements. Discovery establishes identity,
        // not a fresh reading; the first snapshot must obtain current telemetry.
        detected.mapNotNull { serial -> manager.probe(serial)?.let {
            CombustionFrame(serial, System.currentTimeMillis(), quality = TelemetryQuality.STALE,
                viaRepeater = (it.hopCount ?: 0u) > 0u)
        } }
    }

    private suspend fun awaitStatus(manager: DeviceManager, serial: String): CombustionFrame? = coroutineScope {
        val samplingScope = this
        var latest: CombustionFrame? = null
        val instant = java.util.concurrent.atomic.AtomicReference<CombustionFrame?>(null)
        var scanner: Job? = null
        val began = System.currentTimeMillis()
        // ProbeScanner has no replay buffer: its instant-read frames are newly received.
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            ProbeScanner.scanResults.collect { result ->
                val value = result.instantReadTemperature
                if (result.serialNumber == serial && value?.isFinite() == true)
                    instant.set(CombustionFrame(serial, System.currentTimeMillis(), instant = value))
            }
        }
        var requestedConnection = false
        try {
            withTimeoutOrNull(8_000L) {
                while (currentCoroutineContext().isActive) {
                    val probe = manager.probe(serial)
                    if (probe != null) {
                        if (!requestedConnection) { manager.connect(serial); requestedConnection = true }
                        // These log points are generated from connected Normal Mode status,
                        // not replayed advertising data. SDK timestamp fallbacks are ignored.
                        val point = manager.exportLogsForProbe(serial)?.lastOrNull {
                            it.sessionId == probe.sessionInfo?.sessionID && it.sequenceNumber == probe.maxSequence
                        }
                        latest = point?.let { confirmedFrame(probe, it, System.currentTimeMillis()) }
                        if (latest != null) return@withTimeoutOrNull
                    }
                    instant.get()?.let { latest = it; return@withTimeoutOrNull }
                    if (scanner == null && System.currentTimeMillis() - began >= 2_000L) scanner = samplingScope.launch {
                        try { ProbeScanner.scan() }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Revoked permission leaves this source unavailable. */ }
                    }
                    delay(50L)
                }
            }
            latest
        } finally {
            withContext(NonCancellable) { scanner?.cancelAndJoin(); listener.cancelAndJoin() }
        }
    }

    override suspend fun snapshot(serial: String): CombustionFrame? = window(serial) { awaitStatus(it, serial) }

    override suspend fun logs(serial: String): CombustionLogBatch = window(serial) { manager ->
        val status = awaitStatus(manager, serial)
        val probe = manager.probe(serial)
        val min = probe?.minSequence?.toLong()
        val max = probe?.maxSequence?.toLong()
        if (status?.connectedStatus != true || status.quality != TelemetryQuality.VALID ||
            status.session == null || status.sequence == null || min == null || max == null ||
            min > max)
            throw java.io.IOException("Reconnect the probe before recovering its history.")
        if (max - min + 1 > CombustionBleConnector.MAX_LOG_RECORDS)
            throw java.io.IOException("This probe session exceeds the official API's bounded transfer limit.")
        // The official public API requests a session, not an arbitrary range. Refuse an
        // oversized session before transmitting; time and retained result counts are bounded.
        manager.startRecordTransfer(serial)
        val completed = withTimeoutOrNull(12_000L) {
            manager.probeFlow(serial)?.first { it.uploadState is ProbeUploadState.ProbeUploadComplete }
        }
        // A partial transfer cannot advance the shared recovery cursor past missing records.
        if (completed == null) throw java.io.IOException("Probe history transfer was interrupted; retry after reconnecting.")
        val records = manager.exportLogsForProbe(serial).orEmpty().take(CombustionBleConnector.MAX_LOG_RECORDS)
            .filter { it.sessionId.toLong() == status.session && it.temperatures.values.size == 8 }
            .map { record -> CombustionLogRecord(record.sessionId.toLong(), record.sequenceNumber.toLong(),
                record.temperatures.values, record.virtualCoreTemperature, record.virtualSurfaceTemperature,
                record.virtualAmbientTemperature) }
        CombustionLogBatch(status, records)
    }

    override suspend fun close() {
        closed = true
        val current = currentCoroutineContext()[Job]
        operations.filter { it !== current }.forEach { it.cancel() }
    }

    internal companion object {
        private val serviceMutex = Mutex()
        // Accessed only on Main, including the SDK's ServiceConnection callback.
        private var bindingWaiter: CompletableDeferred<DeviceManager>? = null

        fun frame(probe: Probe, receivedAtMillis: Long): CombustionFrame {
            val unavailable = probe.baseDevice.connectionState in setOf(DeviceConnectionState.OUT_OF_RANGE,
                DeviceConnectionState.DISCONNECTED, DeviceConnectionState.NO_ROUTE)
            val connected = probe.baseDevice.connectionState == DeviceConnectionState.CONNECTED &&
                probe.hasReceivedStatus && !probe.statusNotificationsStale
            return CombustionFrame(probe.baseDevice.serialNumber, receivedAtMillis,
                probe.temperaturesCelsius?.values.orEmpty(), probe.instantReadCelsius,
                probe.coreTemperatureCelsius, probe.surfaceTemperatureCelsius, probe.ambientTemperatureCelsius,
                quality = if (!connected || unavailable || probe.statusNotificationsStale && probe.hasReceivedStatus)
                    TelemetryQuality.STALE else TelemetryQuality.VALID,
                connectedStatus = connected, session = if (connected) probe.sessionInfo?.sessionID?.toLong() else null,
                sequence = if (connected) probe.maxSequence?.toLong() else null,
                samplePeriodMillis = if (connected) probe.sessionInfo?.samplePeriod?.toLong() else null,
                viaRepeater = (probe.hopCount ?: 0u) > 0u)
        }

        fun confirmedFrame(probe: Probe, point: LoggedProbeDataPoint, receipt: Long): CombustionFrame? {
            val metadata = frame(probe, receipt)
            if (!metadata.connectedStatus || metadata.quality != TelemetryQuality.VALID || point.temperatures.values.size != 8 ||
                point.sessionId.toLong() != metadata.session || point.sequenceNumber.toLong() != metadata.sequence) return null
            return metadata.copy(temperatures = point.temperatures.values, instant = null,
                core = point.virtualCoreTemperature, surface = point.virtualSurfaceTemperature,
                ambient = point.virtualAmbientTemperature)
        }
    }
}
