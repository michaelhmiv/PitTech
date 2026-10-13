package com.pittech.devices

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only temperature discovery/status/log reads are exposed by the SDK facade. */
internal interface CombustionReadOnlyClient {
    suspend fun discover(): List<CombustionFrame>
    suspend fun snapshot(serial: String): CombustionFrame?
    suspend fun logs(serial: String): CombustionLogBatch
    suspend fun close()
}

internal data class CombustionFrame(
    val serial: String,
    val receivedAtMillis: Long,
    val temperatures: List<Double> = emptyList(),
    val instant: Double? = null,
    val core: Double? = null,
    val surface: Double? = null,
    val ambient: Double? = null,
    val quality: TelemetryQuality = TelemetryQuality.VALID,
    val connectedStatus: Boolean = false,
    val session: Long? = null,
    val sequence: Long? = null,
    val samplePeriodMillis: Long? = null,
    val viaRepeater: Boolean = false,
)

internal data class CombustionLogRecord(
    val session: Long,
    val sequence: Long,
    val temperatures: List<Double>,
    val core: Double?,
    val surface: Double?,
    val ambient: Double?,
)
internal data class CombustionLogBatch(val status: CombustionFrame?, val records: List<CombustionLogRecord>)

/**
 * Official SDK status includes derived measurements and MeatNet routes. Radio/service leases
 * are bounded and released between samples. Device configuration, prediction, fan and DFU
 * APIs are deliberately absent from CombustionReadOnlyClient.
 */
internal class CombustionBleConnector(
    private val client: CombustionReadOnlyClient,
    private val anchors: CombustionAnchorStore = MemoryCombustionAnchorStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "combustion"
    override val minimumIntervalMillis = 30_000L
    override val supportsHistory = true
    @Volatile private var closed = false
    private val mutex = Mutex()
    private val seen = mutableMapOf<String, CombustionFrame>()
    private val logs = mutableMapOf<String, Pair<Long, CombustionLogBatch>>()

    override suspend fun discover(): List<TelemetrySource> = mutex.withLock {
        if (closed) return@withLock emptyList()
        client.discover().filter { it.serial.isNotBlank() && it.serial != "0" }.distinctBy { it.serial }.map {
            seen[it.serial] = it
            source(it.serial, it.viaRepeater)
        }
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> = mutex.withLock {
        if (closed) return@withLock emptyList()
        val cached = seen[deviceId]?.takeIf { clock() - it.receivedAtMillis in 0..15_000L }
        val status = cached ?: client.snapshot(deviceId)?.also { seen[deviceId] = it }
        status?.let { decode(it, anchor(it)) }.orEmpty()
    }

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = observe(deviceId, minimumIntervalMillis)
    override fun observe(deviceId: String, intervalMillis: Long): Flow<List<TelemetryObservation>> = flow {
        require(deviceId.isNotBlank())
        while (currentCoroutineContext().isActive && !closed) {
            val started = clock()
            emit(snapshot(deviceId))
            delay((maxOf(minimumIntervalMillis, intervalMillis) - (clock() - started).coerceAtLeast(0L)).coerceAtLeast(1_000L))
        }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> = mutex.withLock {
        require(untilMillis >= fromMillis)
        if (closed) return@withLock emptyList()
        val now = clock()
        val batch = logs[deviceId]?.takeIf { now - it.first in 0..30_000L }?.second
            ?: client.logs(deviceId).also { logs[deviceId] = now to it }
        val status = batch.status ?: return@withLock emptyList()
        val anchor = anchor(status) ?: return@withLock emptyList()
        batch.records.asSequence().take(MAX_LOG_RECORDS).filter { it.session == anchor.session }
            .mapNotNull { record ->
                val measured = anchor.timeFor(record.sequence)?.takeIf { it in fromMillis..untilMillis && it <= now }
                    ?: return@mapNotNull null
                // SDK Date fallbacks do not establish historical time. Raw advertisements
                // never become historical samples. The validated anchor survives process death.
                decode(CombustionFrame(deviceId, now, record.temperatures, core = record.core,
                    surface = record.surface, ambient = record.ambient, connectedStatus = true,
                    session = record.session, sequence = record.sequence, samplePeriodMillis = anchor.periodMillis),
                    anchor, fromHistory = true, measuredOverride = measured)
            }.flatten().toList()
    }

    private fun anchor(status: CombustionFrame): CombustionTimeAnchor? {
        if (!status.connectedStatus || status.quality != TelemetryQuality.VALID || status.temperatures.size != 8) return null
        val session = status.session ?: return null
        val sequence = status.sequence ?: return null
        val period = status.samplePeriodMillis ?: return null
        if (session !in 0..0xffff_ffffL || sequence !in 0..0xffff_ffffL || period !in 1_000L..60_000L) return null
        val key = TelemetryNormalizer.localKey("combustion", status.serial, session.toString())
        val old = anchors.get(key)
        if (old != null) {
            val expected = old.timeFor(sequence) ?: return null
            // Changed periods, clock discontinuities and reused sessions are ambiguous.
            if (old.periodMillis != period || kotlin.math.abs(expected - status.receivedAtMillis) > maxOf(30_000L, period * 2)) return null
            return old
        }
        val new = CombustionTimeAnchor(session, sequence, status.receivedAtMillis, period)
        anchors.save(key, new)
        return new
    }

    override suspend fun disconnect() {
        closed = true
        client.close()
        mutex.withLock { seen.clear(); logs.clear() }
    }

    internal companion object {
        const val MAX_LOG_RECORDS = 20_000
        fun source(serial: String, viaRepeater: Boolean = false) = TelemetrySource(
            "combustion", serial, "Combustion probe " + serial.takeLast(4), "Combustion",
            model = if (viaRepeater) "Predictive Thermometer · MeatNet" else "Predictive Thermometer",
            channels = (0..7).map { TelemetryChannel("sensor$it", "Raw sensor ${it + 1}",
                when (it) { 0 -> TelemetryKind.FOOD; 7 -> TelemetryKind.AMBIENT; else -> TelemetryKind.OTHER }) } + listOf(
                TelemetryChannel("instant", "Instant-read", TelemetryKind.FOOD),
                TelemetryChannel("core", "Derived core", TelemetryKind.FOOD),
                TelemetryChannel("surface", "Derived surface", TelemetryKind.OTHER),
                TelemetryChannel("ambient", "Derived ambient", TelemetryKind.AMBIENT)),
        )

        fun decode(frame: CombustionFrame, anchor: CombustionTimeAnchor? = null,
                   fromHistory: Boolean = false, measuredOverride: Long? = null): List<TelemetryObservation> {
            val measured = measuredOverride ?: frame.sequence?.let { anchor?.timeFor(it) }
            fun sample(id: String, value: Double?) = TelemetryObservation(id, value?.takeIf { it.isFinite() }, "°C",
                measured, frame.receivedAtMillis, frame.quality,
                eventId = if (measured != null && frame.session != null && frame.sequence != null)
                    "session:${frame.session}:sequence:${frame.sequence}" else null,
                fromHistory = fromHistory,
                timestampBasis = if (measured == null) TelemetryTimestampBasis.RECEIPT else TelemetryTimestampBasis.PROBE_SEQUENCE_ANCHOR)
            return frame.temperatures.take(8).mapIndexed { index, c -> sample("sensor$index", c) } +
                listOfNotNull(frame.instant?.let { sample("instant", it) }, frame.core?.let { sample("core", it) },
                    frame.surface?.let { sample("surface", it) }, frame.ambient?.let { sample("ambient", it) })
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
        return CombustionBleConnector(OfficialCombustionReadOnlyClient(context.applicationContext as android.app.Application),
            FileCombustionAnchorStore(context))
    }
}
