package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ServeTimePlannerTest {
    private val book = CookPlaybook("Meal", NewCookDraft("Meal", dishes = listOf(DishDraft("Brisket", "Beef"), DishDraft("Side", "Vegetables"))), emptyList())
    @Test fun backwardsPlanSeparatesRestHoldAndBufferAndFindsPitConflicts() {
        val p = ServePlan(book, 100_000_000, "UTC", 60, listOf(DishSchedule(0, 480, 600, restMinutes = 120, holdMinutes = 30, pitF = 225.0), DishSchedule(1, 60, 90, restMinutes = 10, pitF = 350.0)))
        val w = ServeTimePlanner.windows(p).first()
        assertEquals(p.serveAt - 810 * 60_000L, w.foodOnAt)
        assertEquals(p.serveAt - 90 * 60_000L, w.readyLatest)
        assertEquals(listOf(0 to 1), ServeTimePlanner.conflicts(p))
        assertTrue(ServeTimePlanner.conflicts(p.copy(schedules = p.schedules.map { if (it.dishIndex == 1) it.copy(method = "Oven") else it })).isEmpty())
        assertEquals(p, ServeTimePlanner.decode(ServeTimePlanner.encode(p)))
    }
    @Test fun daylightSavingsGapRejectedAndOverlapChoiceIsExplicit() {
        assertThrows(IllegalArgumentException::class.java) { ServeTimePlanner.localGoal("2026-03-08", "02:30", "America/New_York") }
        val early = ServeTimePlanner.localGoal("2026-11-01", "01:30", "America/New_York")
        val late = ServeTimePlanner.localGoal("2026-11-01", "01:30", "America/New_York", true)
        assertEquals(3_600_000L, late - early)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), early)
    }
    @Test fun planningWindowMovesWithFoodOnAndRemoval() {
        val p = ServePlan(book, 100_000_000, "UTC", schedules = listOf(DishSchedule(0, 60, 90, restMinutes = 30), DishSchedule(1)))
        val s = p.schedules.first()
        assertEquals(5_401_000L to 7_201_000L, ServeTimePlanner.updatedReadyWindow(p, s, listOf(PlanEvent("on", "food_on", "dish", 1_000)), "dish"))
        assertEquals(1_802_000L to 1_802_000L, ServeTimePlanner.updatedReadyWindow(p, s, listOf(PlanEvent("off", "remove", "dish", 2_000)), "dish"))
    }
}
