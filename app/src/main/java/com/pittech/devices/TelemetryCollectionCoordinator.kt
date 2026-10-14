package com.pittech.devices

import com.pittech.data.TelemetryRecordingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal data class TelemetryBinding(val source: TelemetrySource, val connector: ReadOnlyTelemetryConnector)

/**
 * Independent jobs per source. Errors in one provider cannot interrupt a cook or
 * another probe source. The host must run these jobs in its Android foreground
 * recording service when monitoring needs to survive app backgrounding.
 */
internal class TelemetryCollectionCoordinator(
    private val recorder: TelemetryRecordingRepository,
    private val onState: (TelemetrySource, String) -> Unit = { _, _ -> },
    private val onBatch: (TelemetrySource, List<TelemetryObservation>) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun launch(scope: CoroutineScope, cookId: String, bindings: List<TelemetryBinding>, intervalMillis: Long = 60_000L): Job =
        scope.launch {
            supervisorScope {
                bindings.forEach { binding ->
                    TelemetryConnectorRegistry.requirePlanned(binding.connector.connectorId)
                    require(binding.source.connectorId == binding.connector.connectorId)
                    launch { runBinding(cookId, binding, intervalMillis) }
                }
            }
        }

    private suspend fun runBinding(cookId: String, binding: TelemetryBinding, intervalMillis: Long) {
        val (source, connector) = binding
        var failures = 0
        while (currentCoroutineContext().isActive && recorder.isAttached(cookId, source)) {
            try {
                onState(source, "Connecting…")
                var historyUnavailable = false
                try { backfill(cookId, source, connector) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { historyUnavailable = true; onState(source, "History unavailable; reconnecting live readings") }
                val initial = connector.snapshot(source.deviceId)
                onBatch(source, initial)
                recorder.ingest(cookId, source, initial, maxOf(intervalMillis, connector.minimumIntervalMillis))
                var lastAccepted = Long.MIN_VALUE
                connector.observe(source.deviceId, maxOf(intervalMillis, connector.minimumIntervalMillis)).collect { batch ->
                    if (!recorder.isAttached(cookId, source)) throw CancellationException("Cook stopped or source detached")
                    onBatch(source, batch)
                    val available = batch.any { TelemetryNormalizer.normalize(it) != null }
                    if (available) failures = 0
                    onState(source, if (available) (if (historyUnavailable) "Connected · history unavailable" else "Connected") else "Unavailable or stale")
                    val now = clock()
                    if (available && (lastAccepted == Long.MIN_VALUE || now - lastAccepted >= maxOf(intervalMillis, connector.minimumIntervalMillis))) {
                        recorder.ingest(cookId, source, batch, maxOf(intervalMillis, connector.minimumIntervalMillis))
                        lastAccepted = now
                    }
                }
                // A finite stream closing is a disconnect, not a reason to tight-loop.
                delay(maxOf(5_000L, connector.minimumIntervalMillis))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
                if (error is TelemetryRequestException && error.kind == TelemetryFailureKind.AUTHORIZATION) {
                    onState(source, "Sign in again")
                    return
                }
                onState(source, "Reconnecting…")
                val retryAfter = (error as? TelemetryRequestException)?.retryAtMillis?.minus(clock())
                delay(maxOf(connector.minimumIntervalMillis, TelemetryRecoveryPolicy.retryDelayMillis(failures, retryAfter)))
            }
        }
    }

    suspend fun backfill(cookId: String, source: TelemetrySource, connector: ReadOnlyTelemetryConnector): Int {
        if (!connector.supportsHistory) return 0
        val now = clock()
        val pending = recorder.pendingRecovery(cookId, source)
        val previous = pending?.first ?: recorder.recoveryStart(cookId, source) ?: return 0
        val until = pending?.last ?: now
        if (until <= previous) return 0
        recorder.saveRecovery(cookId, source, previous, until)
        var processed = 0
        var requests = 0
        suspend fun window(from: Long, to: Long, depth: Int): Boolean {
            if (requests++ >= 80) return false
            val observations = connector.history(source.deviceId, from, to)
            if (observations.size >= 20_000 && to - from > 1_000L && depth < 16) {
                val middle = from + (to - from) / 2
                if (!window(from, middle, depth + 1)) return false
                return window(middle, to, depth + 1)
            }
            processed += recorder.ingest(cookId, source, observations.take(20_000).map { it.copy(fromHistory = true) })
            recorder.saveRecovery(cookId, source, to, until)
            return true
        }
        for (range in TelemetryRecoveryPolicy.windows(previous, until)) {
            if (!window(range.first, range.last, 0)) return processed
        }
        recorder.saveRecovery(cookId, source, until, until, complete = true)
        if (pending != null && until < now) {
            // The older gap is finished. Keep any newer offline period recoverable.
            val latest = recorder.recoveryStart(cookId, source)
            if (latest != null && latest < now) recorder.saveRecovery(cookId, source, maxOf(until, latest), now)
        }
        return processed
    }
}
