package com.pittech.devices

import org.junit.Assert.*
import org.junit.Test

class TelemetryCloudPolicyTest {
    @Test fun failedAttemptsStillConsumeTheAccountBudget() {
        val budget = TelemetryAccountBudget(30_000)
        budget.reserve(1_000)
        budget.failed(java.io.IOException(), 1_000)
        try { budget.reserve(2_000); fail("Failure bypassed quota") }
        catch (e: TelemetryRequestException) { assertEquals(31_000L, e.retryAtMillis) }
        budget.reserve(31_000)
    }
    @Test fun retryAfterBlocksRequestsForTheFullCooldown() {
        val budget = TelemetryAccountBudget(30_000)
        budget.reserve(1_000)
        budget.failed(TelemetryRequestException(TelemetryFailureKind.RATE_LIMIT, 301_000), 1_000)
        try { budget.reserve(31_000); fail("429 ignored") }
        catch (e: TelemetryRequestException) { assertEquals(301_000L, e.retryAtMillis) }
        budget.reserve(301_000)
    }
    @Test fun retryAfterSupportsSecondsDatesAndMalformedHeaders() {
        assertEquals(61_000L, TelemetryHttpPolicy.retryAt("60", 1_000, 300_000))
        assertEquals(60_000L, TelemetryHttpPolicy.retryAt("Thu, 1 Jan 1970 00:01:00 GMT", 1_000, 300_000))
        assertEquals(301_000L, TelemetryHttpPolicy.retryAt("not a date", 1_000, 300_000))
        assertEquals(2_000L, TelemetryHttpPolicy.retryAt("0", 1_000, 300_000))
    }
    @Test fun profileRoundTripKeepsMappingsButNeverContainsCredentials() {
        val source = TelemetrySource("meater", "device", "Probe", channels = listOf(TelemetryChannel("internal", "Brisket", TelemetryKind.FOOD)))
        val profile = TelemetryProfile("test", "meater", "My probes", sources = listOf(source), cookId = "cook", onLogOnly = true)
        val json = TelemetryProfileStore.encode(profile)
        assertEquals(profile, TelemetryProfileStore.decode(json))
        assertFalse(json.has("password")); assertFalse(json.has("token"))
    }
}
