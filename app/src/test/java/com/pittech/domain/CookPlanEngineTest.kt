package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookPlanEngineTest {
    private val dish = "flat"
    private val draft = NewCookDraft("Cook", dishes = listOf(DishDraft("Flat", "Beef")))
    private fun plan(vararg steps: PlaybookStep) = CookPlan(CookPlaybook("Cook", draft, steps.toList()), listOf(dish), "book")
    @Test fun lateActionMovesFollowingCheckAndClockCorrectionRecalculates() {
        val check = PlaybookStep(id = "check", dishIndex = 0, trigger = "after_action", anchor = "wrap", minutes = 30)
        val p = plan(check)
        val events = listOf(PlanEvent("on", "food_on", dish, 0), PlanEvent("wrap", "wrap", dish, 7_200_000))
        assertEquals(9_000_000L, CookPlanEngine.evaluate(p, events, emptyList(), 8_000_000).single().dueAt)
        assertFalse(CookPlanEngine.evaluate(p, events, emptyList(), 8_000_000).single().ready)
        assertEquals(10_800_000L, CookPlanEngine.evaluate(p, events.map { if (it.id == "wrap") it.copy(at = 9_000_000) else it }, emptyList(), 8_000_000).single().dueAt)
    }
    @Test fun recurrenceUsesActualConfirmationAndIsIdempotent() {
        val step = PlaybookStep(id = "spritz", dishIndex = 0, action = "spritz", trigger = "elapsed", minutes = 30, repeatMinutes = 30)
        val p = plan(step)
        val event = PlanEvent("spritz1", "spritz", dish, 3_600_000)
        val updated = CookPlanEngine.satisfy(p, step, event)
        assertEquals(updated, CookPlanEngine.satisfy(updated, step, event))
        val evaluated = CookPlanEngine.evaluate(updated, listOf(PlanEvent("on", "food_on", dish, 0), event), emptyList(), 4_000_000).single()
        assertEquals(5_400_000L, evaluated.dueAt)
        assertEquals(1, evaluated.progress.occurrence)
    }
    @Test fun twoDishStagesAndFreshTemperatureAreIndependent() {
        val s = PlaybookStep(id = "wrap", dishIndex = 0, action = "wrap", trigger = "temperature", temperatureF = 165.0)
        val p = plan(s)
        val events = listOf(PlanEvent("on", "food_on", dish, 1_000))
        assertFalse(CookPlanEngine.evaluate(p, events, listOf(PlanReading("other", 170.0, 2_000)), 3_000).single().ready)
        assertFalse(CookPlanEngine.evaluate(p, events, listOf(PlanReading(dish, 170.0, 2_000, type = "pit_ambient")), 3_000).single().ready)
        val named = p.copy(book = p.book.copy(steps = listOf(s.copy(probeName = "Flat probe"))))
        assertFalse(CookPlanEngine.evaluate(named, events, listOf(PlanReading(dish, 170.0, 2_000, probeName = "Other probe")), 3_000).single().ready)
        assertTrue(CookPlanEngine.evaluate(named, events, listOf(PlanReading(dish, 170.0, 2_000, probeName = "Flat probe")), 3_000).single().ready)
        assertFalse(CookPlanEngine.evaluate(p, events, listOf(PlanReading(dish, 170.0, 2_000)), 400_000).single().ready)
        assertTrue(CookPlanEngine.evaluate(p, events, listOf(PlanReading(dish, CookPlanEngine.fahrenheit(75.0, "°C"), 2_000)), 3_000).single().ready)
        assertEquals("cooking", CookPlanEngine.stage(events + PlanEvent("other", "rest_start", "other", 5_000), dish))
    }
    @Test fun skipAnchorsAreExplicitAndPausePreservesPhysicalTiming() {
        val wrap = PlaybookStep(id = "wrap", dishIndex = 0, action = "wrap")
        val after = PlaybookStep(id = "after", dishIndex = 0, trigger = "after_action", anchor = "wrap", minutes = 10)
        val p = plan(wrap, after).copy(progress = mapOf("wrap" to StepProgress(status = "skipped")))
        val events = listOf(PlanEvent("on", "food_on", dish, 0))
        assertNull(CookPlanEngine.evaluate(p, events, emptyList(), 900_000).last().dueAt)
        val anchored = p.copy(progress = mapOf("wrap" to StepProgress(status = "skipped", skippedAnchorAt = 100_000)))
        assertEquals(700_000L, CookPlanEngine.evaluate(anchored, events, emptyList(), 900_000).last().dueAt)
        assertFalse(CookPlanEngine.evaluate(anchored.copy(paused = true), events, emptyList(), 900_000).last().ready)
        assertEquals(anchored, CookPlanEngine.decode(CookPlanEngine.encode(anchored)))
    }
}
