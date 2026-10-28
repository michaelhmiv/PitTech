package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * Plan-timeline derivation (spec §4.4): lanes, derived alerts, and the
 * book's canonical warn-and-stretch case — brisket + ribs + beans on one
 * pit, ribs planned at 275° in a 250° pit stretching by +35 minutes.
 */
class PlanTimelineTest {
    private fun canonicalPlan(): ServePlan {
        val draft = NewCookDraft(
            title = "Saturday",
            smokerName = "Austin XL",
            dishes = listOf(
                DishDraft("Brisket", "Beef", "Packer brisket"),
                DishDraft("Ribs", "Pork", "Spare ribs"),
                DishDraft("Beans", "Side", "Baked beans"),
            ),
        )
        val book = CookPlaybook(
            name = "Saturday",
            draft = draft,
            steps = listOf(
                PlaybookStep(dishIndex = 0, action = "wrap", title = "Wrap the brisket", trigger = "elapsed", minutes = 360),
            ),
        )
        val serveAt = ServeTimePlanner.localGoal("2026-10-31", "18:00", "America/New_York")
        return ServePlan(
            book = book,
            serveAt = serveAt,
            zoneId = "America/New_York",
            schedules = listOf(
                DishSchedule(dishIndex = 0, cookMinMinutes = 600, cookMaxMinutes = 780, restMinutes = 60, pitF = 250.0),
                DishSchedule(dishIndex = 1, cookMinMinutes = 240, cookMaxMinutes = 280, restMinutes = 15, pitF = 275.0),
                DishSchedule(dishIndex = 2, cookMinMinutes = 90, cookMaxMinutes = 120, restMinutes = 0, pitF = 250.0),
            ),
        )
    }

    @Test fun lanesCarryFinishTargetsAgainstTheServeLine() {
        val plan = canonicalPlan()
        val timeline = PlanTimeline.derive(plan)
        assertEquals(3, timeline.lanes.size)
        val brisket = timeline.lanes.first { it.dishIndex == 0 }
        // Latest remove = serve − rest(60) − buffer(60) = 16:00; food on = 16:00 − 780m = 03:00.
        val expectedFoodOn = plan.serveAt - (60 + 60 + 780) * 60_000L
        assertEquals(expectedFoodOn, brisket.foodOnAtUtcMillis)
        assertEquals(plan.serveAt - 60 * 60_000L, brisket.readyLatestUtcMillis - 0) // ready latest = remove latest + rest
        assertTrue(timeline.conflicts.any { it.dishIndexA == 0 && it.dishIndexB == 1 })
    }

    @Test fun alertsAreDerivedFromTheLanesNeverReEntered() {
        val plan = canonicalPlan()
        val timeline = PlanTimeline.derive(plan)
        val brisket = timeline.lanes.first { it.dishIndex == 0 }
        val foodOns = timeline.alerts.filter { it.kind == DerivedAlert.Kind.FOOD_ON }
        assertEquals("one food-on alert per dish", 3, foodOns.size)
        val wrap = timeline.alerts.single { it.kind == DerivedAlert.Kind.WRAP }
        assertEquals(0, wrap.dishIndex)
        assertEquals("wrap fires from the step's elapsed trigger", brisket.foodOnAtUtcMillis + 360 * 60_000L, wrap.atUtcMillis)
        val offs = timeline.alerts.filter { it.kind == DerivedAlert.Kind.OFF }
        assertEquals(3, offs.size)
        assertEquals(brisket.removeEarliestUtcMillis, offs.first { it.dishIndex == 0 }.atUtcMillis)
    }

    @Test fun temperatureTriggeredWrapIsAnAlertWithoutAFixedTime() {
        val plan = canonicalPlan()
        val withTempStep = plan.copy(book = plan.book.copy(steps = listOf(
            PlaybookStep(dishIndex = 0, action = "wrap", title = "Wrap at temp", trigger = "temperature", temperatureF = 165.0),
        )))
        val wrap = PlanTimeline.derive(withTempStep).alerts.single { it.kind == DerivedAlert.Kind.WRAP }
        assertNull(wrap.atUtcMillis)
        assertEquals(165.0, wrap.temperatureF!!, 0.0)
    }

    @Test fun warnAndStretchHitsTheBooksCanonicalNumbers() {
        val plan = canonicalPlan()
        val resolved = PlanTimeline.applyPitChoice(plan, chosenPitF = 250.0)
        val ribs = resolved.lanes.first { it.dishIndex == 1 }
        val stretch = ribs.stretch ?: error("ribs must stretch in a 250° pit")
        // 280 max × (1 + 0.005 × 25°) = 315 → +35 minutes, the book's number.
        assertEquals(35L, stretch.addedMinutes)
        assertEquals(315L, stretch.adjustedCookMaxMinutes)
        assertTrue(stretch.basis.contains("+35 min"))
        assertTrue(stretch.basis.contains("250°"))
        assertTrue(stretch.basis.contains("275°"))
        // The stretched dish goes on earlier so it still lands on the serve line.
        val uncooked = PlanTimeline.derive(plan).lanes.first { it.dishIndex == 1 }
        assertEquals(uncooked.foodOnAtUtcMillis - 35 * 60_000L, ribs.foodOnAtUtcMillis)
        assertEquals(uncooked.removeLatestUtcMillis, ribs.removeLatestUtcMillis)
        // Dishes planned at the chosen temperature are untouched.
        assertNull(resolved.lanes.first { it.dishIndex == 0 }.stretch)
        assertNull(resolved.lanes.first { it.dishIndex == 2 }.stretch)
        assertEquals(250.0, resolved.chosenPitF!!, 0.0)
    }

    @Test fun aHotterChosenPitDoesNotStretch() {
        val plan = canonicalPlan()
        val resolved = PlanTimeline.applyPitChoice(plan, chosenPitF = 275.0)
        assertTrue(resolved.lanes.all { it.stretch == null })
    }
}
