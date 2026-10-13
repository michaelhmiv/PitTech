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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun launch(scope: CoroutineScope, cookId: String, bindings: List<TelemetryBinding>): Job =
        scope.launch {
            supervisorScope {
                bindings.forEach { binding ->
                    TelemetryConnectorRegistry.requirePlanned(binding.connector.connectorId)
                    require(binding.source.connectorId == binding.connector.connectorId)
                    launch { runBinding(cookId, binding) }
                }
            }
        }

    private suspend fun runBinding(cookId: String, binding: TelemetryBinding) {
        val (source, connector) = binding
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                backfill(cookId, source, connector)
                recorder.ingest(cookId, source, connector.snapshot(source.deviceId))
                failures = 0
                connector.observe(source.deviceId).collect { batch ->
                    recorder.ingest(cookId, source, batch)
                }
                // A finite stream closing is a disconnect, not a reason to tight-loop.
                delay(5_000L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures++
                delay(TelemetryRecoveryPolicy.retryDelayMillis(failures))
            }
        }
    }

    suspend fun backfill(cookId: String, source: TelemetrySource, connector: ReadOnlyTelemetryConnector): Int {
        val previous = recorder.latestObservation(cookId, source) ?: return 0
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
