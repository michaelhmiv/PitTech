package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * The plan day-board's derivation (Flowing spec Spread 03): the answer
 * first (3.1), the stagger against the serve line (3.2), and
 * warn-and-stretch (3.3) — including the board's save contract: a plan
 * saved with a resolution applied re-derives to the same lanes with no
 * conflict left. Uses the book's canonical Saturday shape (brisket +
 * ribs + beans on one pit, ribs planned at 275° in a 250° pit).
 */
class PlanBoardTest {
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
        val book = CookPlaybook(name = "Saturday", draft = draft, steps = emptyList())
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

    @Test fun theHeadlineIsTheDishThatStartsTheDay() {
        val timeline = PlanTimeline.derive(canonicalPlan())
        val headline = PlanBoard.headlineLane(timeline)
        assertEquals("Brisket", headline?.dishName)
        // Food on = serve − rest(60) − buffer(60) − longest cook(780) = 03:00 local.
        assertEquals(canonicalPlan().serveAt - (60 + 60 + 780) * 60_000L, headline?.foodOnAtUtcMillis)
    }

    @Test fun laneSpansSitInsideTheBoardWindowAgainstTheServeLine() {
        val plan = canonicalPlan()
        val timeline = PlanTimeline.derive(plan)
        val window = PlanBoard.boardWindow(timeline)!!
        assertEquals(plan.serveAt, window.second)
        val brisket = timeline.lanes.first { it.dishIndex == 0 }
        val beans = timeline.lanes.first { it.dishIndex == 2 }
        assertEquals(0.0, PlanBoard.laneSpan(brisket, window).first, 0.0001)
        val beansSpan = PlanBoard.laneSpan(beans, window)
        assertTrue("beans start well into the day", beansSpan.first > 0.5)
        assertTrue("beans are ready before the serve line", beansSpan.second < 1.0)
        timeline.lanes.forEach { lane ->
            val (start, end) = PlanBoard.laneSpan(lane, window)
            assertTrue(start in 0.0..1.0 && end in start..1.0)
        }
    }

    @Test fun anUnresolvedConflictDefaultsToTheEarliestDishsPitAndStretches() {
        val plan = canonicalPlan()
        val timeline = PlanBoard.resolvedTimeline(plan, chosenPitF = null)
        assertEquals(250.0, timeline.chosenPitF!!, 0.001)
        val ribs = timeline.lanes.first { it.dishIndex == 1 }
        assertEquals("the book's canonical stretch", 35L, ribs.stretch?.addedMinutes)
        assertTrue(ribs.stretch!!.basis.contains("+35 min"))
        val brisket = timeline.lanes.first { it.dishIndex == 0 }
        assertNull("the pit-setting dish does not stretch", brisket.stretch)
    }

    @Test fun theCookCanPickTheOtherPitInstead() {
        val plan = canonicalPlan()
        assertEquals(listOf(250.0, 275.0), PlanBoard.conflictPitChoices(PlanTimeline.derive(plan)))
        val timeline = PlanBoard.resolvedTimeline(plan, chosenPitF = 275.0)
        // At 275° nothing planned hotter exists above the threshold, so no
        // lane stretches — the resolution is the hotter pit itself.
        assertTrue(timeline.lanes.all { it.stretch == null })
        assertEquals(275.0, timeline.chosenPitF!!, 0.001)
    }

    @Test fun aSavedResolutionRederivesToTheSameLanesWithNoConflictLeft() {
        val plan = canonicalPlan()
        val timeline = PlanBoard.resolvedTimeline(plan, chosenPitF = null)
        val saved = PlanBoard.stretchedPlan(plan, timeline)
        val ribs = saved.schedules.first { it.dishIndex == 1 }
        assertEquals(270L, ribs.cookMinMinutes)
        assertEquals(315L, ribs.cookMaxMinutes)
        assertEquals(250.0, ribs.pitF!!, 0.001)
        assertTrue(ribs.evidenceNote.contains("Stretched +35 min at 250° (planned 275°)"))
        val rederived = PlanTimeline.derive(saved)
        assertTrue("no conflict survives the save", rederived.conflicts.isEmpty())
        val savedRibs = rederived.lanes.first { it.dishIndex == 1 }
        val boardRibs = timeline.lanes.first { it.dishIndex == 1 }
        assertEquals(boardRibs.foodOnAtUtcMillis, savedRibs.foodOnAtUtcMillis)
        assertEquals(boardRibs.readyLatestUtcMillis, savedRibs.readyLatestUtcMillis)
    }

    @Test fun anUnconflictedPlanSavesUnchanged() {
        val plan = canonicalPlan().let { p ->
            p.copy(schedules = p.schedules.map { if (it.dishIndex == 1) it.copy(pitF = 250.0) else it })
        }
        val timeline = PlanBoard.resolvedTimeline(plan, chosenPitF = null)
        assertNull(timeline.chosenPitF)
        assertSame(plan, PlanBoard.stretchedPlan(plan, timeline))
    }
}
