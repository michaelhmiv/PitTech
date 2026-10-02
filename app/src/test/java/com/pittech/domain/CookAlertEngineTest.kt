package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookAlertEngineTest {
    private fun sample(value: Double, at: Long, valid: Boolean = true) = AlertSample(value, at, at, valid, 180_000)
    @Test fun pitDwellNeedsContinuousAcceptedSamplesAndRecoveryMargin() {
        val rule = CookAlertRule(type = "pit_range", lowF = 200.0, highF = 300.0, dwellMillis = 120_000)
        var state = CookAlertState()
        state = CookAlertEngine.evaluate(rule, state, sample(310.0, 0), 0, false, "cooking", 0).state
        assertFalse(CookAlertEngine.evaluate(rule, state, sample(310.0, 0), 0, false, "cooking", 120_000).notify)
        val sustained = CookAlertEngine.evaluate(rule, state, sample(310.0, 120_000), 120_000, false, "cooking", 120_000)
        assertTrue(sustained.notify)
        assertTrue(CookAlertEngine.evaluate(rule, sustained.state, sample(298.0, 130_000), 130_000, false, "cooking", 130_000).state.active)
        assertFalse(CookAlertEngine.evaluate(rule, sustained.state, sample(294.0, 140_000), 140_000, false, "cooking", 140_000).state.active)
        val afterGap = CookAlertEngine.evaluate(rule, state, sample(310.0, 300_000), 300_000, false, "cooking", 300_000)
        assertFalse(afterGap.notify)
    }
    @Test fun missingAndReportedOfflineAreDifferentAndPauseStopsMonitoring() {
        val missing = CookAlertRule(type = "missing", missingMillis = 180_000)
        assertTrue(CookAlertEngine.evaluate(missing, CookAlertState(), null, 0, null, "cooking", 180_000).notify)
        val offline = CookAlertRule(type = "offline")
        assertFalse(CookAlertEngine.evaluate(offline, CookAlertState(), null, 0, null, "cooking", 180_000).notify)
        assertTrue(CookAlertEngine.evaluate(offline, CookAlertState(), null, 0, true, "cooking", 180_000).notify)
        assertFalse(CookAlertEngine.evaluate(missing, CookAlertState(), null, 0, true, "cooking", 180_000, running = false).notify)
    }
    @Test fun targetStageSuppressionAcknowledgmentAndRepeatLimitAreHonest() {
        val target = CookAlertRule(targetF = 165.0)
        assertFalse(CookAlertEngine.evaluate(target, CookAlertState(), sample(170.0, 0), 0, false, "resting", 0).notify)
        assertFalse(CookAlertEngine.evaluate(target, CookAlertState(), sample(170.0, 0, false), 0, false, "cooking", 0).notify)
        val acknowledged = CookAlertEngine.evaluate(target, CookAlertState(active = true, acknowledged = true), sample(170.0, 0), 0, false, "cooking", 0)
        assertTrue(acknowledged.state.active); assertFalse(acknowledged.notify)
        assertFalse(CookAlertEngine.evaluate(target, CookAlertState(notifiedCount = 3), sample(170.0, 0), 0, false, "cooking", 0).notify)
        val pit = CookAlertRule(type = "pit_range", lowF = 200.0, highF = 300.0)
        assertFalse(CookAlertEngine.evaluate(pit, CookAlertState(suppressedUntil = 600_000), sample(100.0, 0), 0, false, "cooking", 0).notify)
        assertEquals(target, CookAlertEngine.decode(CookAlertEngine.encode(target)).first)
    }
}
