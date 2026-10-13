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
                backfill(cookId, source, connector)
                recorder.ingest(cookId, source, connector.snapshot(source.deviceId))
                failures = 0
                var lastAccepted = Long.MIN_VALUE
                connector.observe(source.deviceId, maxOf(intervalMillis, connector.minimumIntervalMillis)).collect { batch ->
                    if (!recorder.isAttached(cookId, source)) throw CancellationException("Cook stopped or source detached")
                    val available = batch.any { TelemetryNormalizer.normalize(it) != null }
                    onState(source, if (available) "Connected" else "Unavailable or stale")
                    val now = clock()
                    if (available && (lastAccepted == Long.MIN_VALUE || now - lastAccepted >= maxOf(intervalMillis, connector.minimumIntervalMillis))) {
                        recorder.ingest(cookId, source, batch)
                        lastAccepted = now
                    }
                }
                // A finite stream closing is a disconnect, not a reason to tight-loop.
                delay(5_000L)
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
                delay(TelemetryRecoveryPolicy.retryDelayMillis(failures, retryAfter))
            }
        }
    }

    suspend fun backfill(cookId: String, source: TelemetrySource, connector: ReadOnlyTelemetryConnector): Int {
        if (!connector.supportsHistory) return 0
        val previous = recorder.recoveryStart(cookId, source) ?: return 0
        var processed = 0
        for (window in TelemetryRecoveryPolicy.windows(previous, clock())) {
            val observations = connector.history(source.deviceId, window.first, window.last)
                .take(20_000)
                .map { it.copy(fromHistory = true) }
            processed += recorder.ingest(cookId, source, observations)
        }
        return processed
    }
}
