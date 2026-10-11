package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

class CookPhasesTest {
    private fun e(id: String, action: String, dish: String?, at: Long) = PlanEvent(id, action, dish, at)
    @Test fun overnightPrepMixedDishesAndRecorderPausesDoNotAddHeatingTime() {
        val events = listOf(e("on", "food_on", "a", 100), e("pause", "cook_paused", null, 150), e("wrap", "wrap", "a", 200), e("off", "remove", "a", 300), e("rest", "rest_start", "a", 350), e("hold", "hold_start", "a", 400))
        val phases = CookPhases.all(events, listOf("a", "b"), 0, 500)
        assertEquals("Mixed phases", CookPhases.summary(phases))
        assertEquals(200L, phases[0].cookingMillis)
        assertEquals(50L, phases[0].restMillis)
        assertEquals(100L, phases[0].holdMillis)
        assertEquals(0L, phases[1].cookingMillis)
        assertEquals("prep", phases[1].phase)
        assertEquals(200L, CookPhases.heatingMillis(phases))
        CookPhases.validate(events, listOf("a", "b"))
    }
    @Test fun reheatingAccumulatesAndOverlappingDishesCountSmokerHoursOnce() {
        val events = listOf(e("a1", "food_on", "a", 10), e("b1", "food_on", "b", 20), e("off", "remove", "a", 30), e("a2", "food_on", "a", 50), e("b2", "remove", "b", 60), e("end", "remove", "a", 70))
        CookPhases.validate(events, listOf("a", "b"))
        val phases = CookPhases.all(events, listOf("a", "b"), 0, 80)
        assertEquals(40L, phases[0].cookingMillis)
        assertEquals(60L, CookPhases.heatingMillis(phases))
    }
    @Test fun timeCorrectionsRecomputeClocksAndRejectImpossibleOrder() {
        val events = listOf(e("on", "food_on", null, 10), e("off", "remove", null, 40))
        assertEquals(30L, CookPhases.all(events, listOf("a"), 0, 80).single().cookingMillis)
        assertEquals(20L, CookPhases.all(events.map { if (it.id == "on") it.copy(at = 20) else it }, listOf("a"), 0, 80).single().cookingMillis)
        assertThrows(IllegalArgumentException::class.java) { CookPhases.validate(events.map { if (it.id == "on") it.copy(at = 50) else it }, listOf("a")) }
    }
}
