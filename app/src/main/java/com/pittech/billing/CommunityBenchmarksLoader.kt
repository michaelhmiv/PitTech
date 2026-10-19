package com.pittech.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the benchmarks entry shows once the Pro gate resolves to PRO.
 * Signed-out and non-Pro states never reach the loader: the gate shows the
 * paywall instead. A stale entitled cache that the server rejects (401/403)
 * converges through one entitlement refresh, then shows the paywall. */
internal sealed interface CommunityBenchmarksState {
    data object Loading : CommunityBenchmarksState
    data class Loaded(val snapshot: CommunityBenchmarksSnapshot) : CommunityBenchmarksState
    data object Empty : CommunityBenchmarksState
    data object SignedOut : CommunityBenchmarksState
    data object Forbidden : CommunityBenchmarksState
    data object Unavailable : CommunityBenchmarksState
}

/** Loads the latest recorded run plus its figures. Figures live only while the
 * benchmarks view is shown: the view resets on dispose, so a removed or
 * expired entitlement never keeps serving figures. */
internal class CommunityBenchmarksLoader(
    private val api: CommunityBenchmarksAPI = CommunityBenchmarksClient(),
    private val session: suspend () -> String?,
    private val onAuthMismatch: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow<CommunityBenchmarksState>(CommunityBenchmarksState.Loading)
    val state = _state.asStateFlow()
    private var mismatched = false

    suspend fun load() {
        _state.value = CommunityBenchmarksState.Loading
        val token = runCatching { session() }.getOrNull()
        if (token == null) {
            mismatch()
            _state.value = CommunityBenchmarksState.SignedOut
            return
        }
        try {
            val payload = CommunityBenchmarks.parseAggregates(api.aggregates(null, null, null, token))
            val run = payload.run
            if (run == null) {
                _state.value = CommunityBenchmarksState.Empty
                return
            }
            val (cards, omitted) = CommunityBenchmarks.cards(payload.figures)
            _state.value = CommunityBenchmarksState.Loaded(CommunityBenchmarksSnapshot(run.runId, cards, omitted))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: CommunityBenchmarksFailure) {
            when (e.status) {
                401 -> {
                    mismatch()
                    _state.value = CommunityBenchmarksState.SignedOut
                }
                403 -> {
                    mismatch()
                    _state.value = CommunityBenchmarksState.Forbidden
                }
                else -> _state.value = CommunityBenchmarksState.Unavailable
            }
        } catch (_: Exception) {
            // Strict decode failures fail closed: no partial figures render.
            _state.value = CommunityBenchmarksState.Unavailable
        }
    }

    fun reset() {
        _state.value = CommunityBenchmarksState.Loading
        mismatched = false
    }

    private suspend fun mismatch() {
        if (mismatched) return
        mismatched = true
        onAuthMismatch()
    }
}
