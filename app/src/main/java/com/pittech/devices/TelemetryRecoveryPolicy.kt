package com.pittech.devices

import kotlin.math.min

/** Bounded recovery: never issue unlimited backfill queries on reconnect. */
internal object TelemetryRecoveryPolicy {
    const val WINDOW_MS = 6L * 3_600_000L
    const val MAX_LOOKBACK_MS = 10L * 24 * 3_600_000L

    fun retryDelayMillis(failures: Int, retryAfterMillis: Long? = null): Long {
        val exponent = (failures - 1).coerceIn(0, 8)
        val delay = (1_000L shl exponent).coerceAtMost(300_000L)
        return maxOf(delay, retryAfterMillis?.coerceAtLeast(0L) ?: 0L).coerceAtMost(3_600_000L)
    }

    fun windows(lastRecorded: Long?, now: Long): List<LongRange> {
        val last = lastRecorded ?: return emptyList()
        if (now <= last || last <= 0) return emptyList()
        val beginning = maxOf(last, now - MAX_LOOKBACK_MS)
        val result = mutableListOf<LongRange>()
        var start = beginning
        while (start < now && result.size < 40) {
            val end = min(now, start + WINDOW_MS)
            result.add(start..end)
            start = end
        }
        return result
    }
}
