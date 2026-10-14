package com.pittech.devices

import org.junit.Assert.*
import org.junit.Test

class TelemetryRecoveryPolicyTest {
    @Test fun recoversOnlyRecordedStateChangeWindowsNeverInventingOldSamples() {
        assertTrue(TelemetryRecoveryPolicy.windows(null, 10000).isEmpty())
        assertTrue(TelemetryRecoveryPolicy.windows(20000, 10000).isEmpty())
        val hours = 3_600_000L
        val ranges = TelemetryRecoveryPolicy.windows(hours, 21 * hours)
        assertEquals(4, ranges.size)
        assertEquals(hours, ranges.first().first)
        assertEquals(21 * hours, ranges.last().last)
        for (i in 1 until ranges.size) assertEquals(ranges[i-1].last, ranges[i].first)
        assertTrue(ranges.all { it.last - it.first <= TelemetryRecoveryPolicy.WINDOW_MS })
    }

    @Test fun capsBackfillAtTenDaysWithoutUnboundedLoops() {
        val days = 24 * 3_600_000L
        val result = TelemetryRecoveryPolicy.windows(1, 365 * days)
        assertEquals(40, result.size)
        assertEquals(355 * days, result.first().first)
        assertEquals(365 * days, result.last().last)
    }

    @Test fun rateErrorPauseNeverDecreasesOrGoesUnbounded() {
        assertEquals(1_000L, TelemetryRecoveryPolicy.retryDelayMillis(1))
        assertEquals(2_000L, TelemetryRecoveryPolicy.retryDelayMillis(2))
        assertEquals(300_000L, TelemetryRecoveryPolicy.retryDelayMillis(20))
        assertEquals(600_000L, TelemetryRecoveryPolicy.retryDelayMillis(1, 600_000L))
        assertEquals(3_600_000L, TelemetryRecoveryPolicy.retryDelayMillis(1, Long.MAX_VALUE))
    }
}
