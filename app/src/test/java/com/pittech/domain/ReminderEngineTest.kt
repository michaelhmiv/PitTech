package com.pittech.domain

import org.junit.Test
import org.junit.Assert.*

class ReminderEngineTest {
    @Test fun durationAndHistoricalDueTimesSurviveRelaunchAndAnchorCorrections() {
        val duration = ReminderTrigger.Duration(1000, 600000)
        assertEquals(duration, ReminderTriggerCodec.decode(ReminderTriggerCodec.encode(duration)))
        assertEquals(601000L, ReminderEngine.evaluate(duration, ReminderState(), emptyList(), "mac", 2000).dueAt)
        val history = ReminderTrigger.Historical("food_on", 12000000)
        assertNull(ReminderEngine.evaluate(history, ReminderState(), emptyList(), "mac", 3000).dueAt)
        val events = listOf(PlanEvent("other", "food_on", "pork", 100), PlanEvent("actual", "food_on", "mac", 5000))
        assertEquals(12005000L, ReminderEngine.evaluate(history, ReminderState(), events, "mac", 6000).dueAt)
        assertEquals(12008000L, ReminderEngine.evaluate(history, ReminderState(), events.map { if (it.id == "actual") it.copy(at = 8000) else it }, "mac", 9000).dueAt)
        assertFalse(ReminderEngine.evaluate(history, ReminderState(completed = true), events, "mac", 99999999).ready)
    }
    private val rule = ReminderTrigger.Probe("food-channel", "probe-id", "food", "above", 165.0, sustainMillis = 120000, repeatMillis = 900000)
    private fun sample(at: Long, value: Double = 170.0) = ReminderProbeSample("food-channel", "probe-id", "food", AlertSample(value, at, at, true, 180000), true, true)
    @Test fun freshCorrectlyAssignedDistinctSamplesAreRequiredForSustainedThresholds() {
        val first = ReminderEngine.evaluate(rule, ReminderState(), emptyList(), "mac", 100000, sample(100000))
        assertFalse(first.ready)
        assertFalse(ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 220000, sample(100000)).ready)
        val reached = ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 220000, sample(220000))
        assertTrue(reached.ready)
        assertFalse(ReminderEngine.evaluate(rule, reached.state.copy(lastDeliveredAt = 220000), emptyList(), "mac", 220000, sample(220000)).ready)
        assertFalse(ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 220000, sample(220000).copy(assignmentValid = false)).ready)
        assertFalse(ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 220000, sample(220000).copy(measurementType = "pit")).ready)
        assertFalse(ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 500000, sample(220000)).ready)
        assertFalse(ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 220000, sample(220000).let { it.copy(sample = it.sample.copy(ageKnown = false)) }).ready)
        assertFalse(ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 220000, sample(220000).copy(continuous = false)).ready)
        assertNull(ReminderEngine.evaluate(rule, first.state, emptyList(), "mac", 900000, sample(900000)).state.badSince?.takeIf { it == 100000L })
    }
    @Test fun belowThresholdSnoozeAndHysteresisDoNotCreateExtraDeliveries() {
        val trigger = rule.copy(direction = "below", thresholdF = 200.0, sustainMillis = 0)
        val due = ReminderEngine.evaluate(trigger, ReminderState(), emptyList(), null, 100000, sample(100000, 195.0))
        assertTrue(due.ready)
        val suppressed = due.state.copy(lastDeliveredAt = 100000, snoozedUntil = 700000)
        assertFalse(ReminderEngine.evaluate(trigger, suppressed, emptyList(), null, 200000, sample(200000, 195.0)).ready)
        assertFalse(ReminderEngine.evaluate(trigger, suppressed, emptyList(), null, 200000, sample(200000, 202.0)).state.active.not())
        assertFalse(ReminderEngine.evaluate(trigger, suppressed, emptyList(), null, 200000, sample(200000, 204.0)).state.active)
        assertFalse(ReminderEngine.evaluate(trigger, ReminderState(), emptyList(), null, 200000, sample(200000, 195.0), enabled = false).ready)
    }
    @Test fun anExplicitActionOccurrenceAndStrictVersionAreRetained() {
        val trigger = ReminderTrigger.Action("wrap", 600000, "second")
        val events = listOf(PlanEvent("first", "wrap", "mac", 1000), PlanEvent("second", "wrap", "mac", 5000))
        assertEquals(605000L, ReminderEngine.evaluate(trigger, ReminderState(), events, "mac", 5000).dueAt)
        assertTrue(runCatching { ReminderTriggerCodec.decode(ReminderTriggerCodec.encode(trigger).replace("\"version\":1", "\"version\":2")) }.isFailure)
    }
}
