package com.pittech.devices

import org.junit.Assert.*
import org.junit.Test

class GrillSamplingPolicyTest {
    @Test fun defaultAndStoredSchedulesAreExplicitAndRejectUnknownValues() {
        assertEquals(60_000L, GrillSamplingPolicy().intervalMillis)
        assertEquals(GrillSamplingMode.PERIODIC, GrillSamplingPolicy().mode)
        assertEquals(0L, GrillSamplingPolicy(GrillSamplingMode.ON_LOG).sampleIntervalMillis)
        assertEquals(GrillSamplingPolicy(GrillSamplingMode.ON_LOG, 300_000L), GrillSamplingPolicy.stored("on_log", 300_000L))
        assertThrows(Exception::class.java) { GrillSamplingPolicy.stored("unknown", 60_000L) }
        assertThrows(Exception::class.java) { GrillSamplingPolicy(intervalMillis = 1_000L) }
    }
    @Test fun retriesNeverShortenTheRequestedCadenceOrServerPause() {
        for (interval in GrillSamplingPolicy.INTERVALS) for (failures in 0..10) {
            assertTrue(PolarisMonitorPolicy.nextDelay(failures, intervalMillis = interval) >= interval)
        }
        assertEquals(900_000L, PolarisMonitorPolicy.nextDelay(10, 900_000L, 300_000L))
        assertEquals(60_000L, PolarisMonitorPolicy.nextDelay(0))
    }
    @Test fun receiptAgeScalesButNativeDeviceAgeIsCheckedAtReceipt() {
        val time = 1_000_000L
        val sample = PolarisSample(time, PolarisPayload(mapOf("tempUnit" to 0.0), emptyList(), 0, null, reportedAtMillis = time - 10_000L), 300_000L)
        val state = PolarisMonitorState(latest = sample, sampling = GrillSamplingPolicy(intervalMillis = 300_000L), onlineStatus = 0, statusFetchedAtMillis = time)
        assertFalse(state.readingsAreOld(time + 300_000L))
        assertEquals("Grill online", state.onlineLabel(time + 300_000L))
        assertTrue(state.readingsAreOld(time + 330_001L))
        assertTrue(state.copy(latest = sample.copy(payload = sample.payload.copy(reportedAtMillis = time - 45_001L))).readingsAreOld(time))
        assertTrue(state.copy(readingRequestFailed = true).readingsAreOld(time))
        assertEquals(15_000L, GrillSamplingPolicy.receiptWindow(0))
        assertEquals(45_000L, GrillSamplingPolicy.receiptWindow(15_000))
        assertEquals(90_000L, GrillSamplingPolicy.receiptWindow(60_000))
    }
    @Test fun serverRetryAfterSupportsLongPausesAndHttpDatesWithoutOverflow() {
        assertEquals(900_000L, GrillRetryAfter.parse("900", 0L))
        assertEquals(60_000L, GrillRetryAfter.parse("Thu, 1 Jan 1970 00:01:00 GMT", 0L))
        assertEquals(0L, GrillRetryAfter.parse("Thu, 1 Jan 1970 00:01:00 GMT", 120_000L))
        assertNull(GrillRetryAfter.parse("-1"))
        assertNull(GrillRetryAfter.parse("9223372036854775807"))
        assertNull(GrillRetryAfter.parse("bad"))
    }

}
