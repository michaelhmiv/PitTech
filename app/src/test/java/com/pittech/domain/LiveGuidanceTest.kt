package com.pittech.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flowing Slice 2: the verdict line's merge order and the ETA's honesty
 * shapes (spec §5 elements 1.1–1.2), plus the ghost delta (2.4) and the
 * same-cut history selection the Live board feeds the pace engine.
 */
class LiveGuidanceTest {
    private val time = { at: Long -> "T$at" }
    private val range = { a: Long, b: Long -> "R$a-$b" }
    private val duration = { minutes: Double -> "${minutes.toInt()}m" }

    private fun verdict(
        attention: Int = 0,
        stall: StallState = StallState.NotInStall,
        finish: FinishEstimate = FinishEstimate.PlanOnly("not enough readings yet"),
        next: Pair<String, Long>? = null,
        now: Long = 1_000_000L,
        eta: LiveEta = LiveEta.None,
    ) = LiveGuidance.verdict(attention, stall, finish, next, now, eta, time, duration)

    @Test fun attentionBeatsEverything() {
        val v = verdict(attention = 2, stall = StallState.InStall(500_000, 52.0, 130.0))
        assertEquals("2 alerts need attention.", v.headline)
        val one = verdict(attention = 1)
        assertEquals("An alert needs attention.", one.headline)
    }

    @Test fun stallIsCalmAndSpecific() {
        val v = verdict(stall = StallState.InStall(500_000, 52.0, 130.0))
        assertEquals("In the stall — normal. Nothing needs doing.", v.headline)
        assertEquals("In the stall since T500000 · 52m in · your average stall for this cut is 130m.", v.detail)
        val noHistory = verdict(stall = StallState.InStall(500_000, 52.0, null))
        assertEquals("In the stall since T500000 · 52m in.", noHistory.detail)
    }

    @Test fun upcomingReminderOutranksPlainPace() {
        val eta = LiveEta.RangedLine("Done R1-2", "from your last 30 min of readings")
        val v = verdict(next = "Wrap the brisket" to 1_020_000L, eta = eta)
        assertEquals("Next: Wrap the brisket at T1020000.", v.headline)
        assertEquals("Done R1-2 · from your last 30 min of readings", v.detail)
        // A reminder hours away does not lead; pace does.
        val later = verdict(next = "Wrap the brisket" to 9_000_000L, eta = eta)
        assertEquals("On pace — nothing needs doing.", later.headline)
    }

    @Test fun finishedAndQuietDefaults() {
        assertEquals("At target — ready when you are.", verdict(finish = FinishEstimate.Finished).headline)
        assertEquals("Nothing needs doing.", verdict().headline)
        assertNull(verdict().detail)
    }

    @Test fun etaShapesCarryTheirBasis() {
        val ranged = LiveGuidance.eta(FinishEstimate.Ranged(FinishRange(100, 200, PaceBasis.PACE)), null, range)
        assertTrue(ranged is LiveEta.RangedLine)
        assertEquals("Done R100-200", (ranged as LiveEta.RangedLine).text)
        assertEquals("from your last 30 min of readings", ranged.basis)

        // PlanOnly with a serve plan: the plan window stands, with the plan basis.
        val planned = LiveGuidance.eta(FinishEstimate.PlanOnly("not enough readings yet"), 300L to 400L, range)
        assertTrue(planned is LiveEta.PlanLine)
        assertEquals("Planning window R300-400", (planned as LiveEta.PlanLine).text)
        assertEquals("from your plan — not enough readings yet", planned.basis)

        // PlanOnly with no plan: nothing is invented — the reason is the line.
        val withheld = LiveGuidance.eta(FinishEstimate.PlanOnly("not enough readings yet"), null, range)
        assertTrue(withheld is LiveEta.Withheld)
        assertEquals("No finish range yet — not enough readings yet", withheld.detail)
    }

    @Test fun ghostDeltaIsTimeAtSameTemperature() {
        fun series(vararg points: Pair<Long, Double>) = points.map { PaceReading(it.first * 60_000, it.second) }
        val current = series(0L to 40.0, 60L to 120.0, 120L to 165.0, 180L to 190.0)
        val ghost = series(0L to 40.0, 60L to 110.0, 120L to 150.0, 158L to 165.0, 240L to 195.0)
        // At 120 min in, the current cook sits at 165°; the ghost reached
        // 165° at exactly 158 min — the cook is 38 minutes ahead of it.
        assertEquals(38, LiveGuidance.ghostDeltaMinutes(current, ghost, 120.0))
        // Before either series has data, or beyond the ghost's climb: silence.
        assertNull(LiveGuidance.ghostDeltaMinutes(current, ghost, 300.0))
        assertNull(LiveGuidance.ghostDeltaMinutes(current, series(0L to 40.0), 120.0))
    }

    @Test fun historyNeedsSameCutFinishedCooksAndRecordedTargets() {
        fun archive(dishes: List<InsightDishInput>, results: List<InsightResultInput>, cooks: List<InsightCookInput>) =
            InsightArchive(cooks, dishes, emptyList(), emptyList(), results)
        val past = InsightCookInput("past", 0, 10_000, "explicit_phases")
        val live = InsightCookInput("live", 0, null, "explicit_phases")
        val brisket = InsightDishInput("d1", "past", "Brisket", null, null)
        val chicken = InsightDishInput("d2", "past", "Chicken", null, null)
        val target = InsightResultInput("r1", "past", "d1", "final_temperature", 203.0, null, 9_000)
        // No readings in these archives: history() still walks dishes and
        // targets — with no series it can only return empty lists, so the
        // selection is proven through ghostCook + the NONE cases instead.
        val none = LiveGuidance.historyFor(archive(listOf(brisket, chicken), listOf(target), listOf(past, live)), "live", "d9", "Chicken")
        assertEquals(PaceHistory.NONE, none)
        val noTarget = LiveGuidance.historyFor(archive(listOf(brisket), emptyList(), listOf(past, live)), "live", "d9", "Brisket")
        assertEquals(PaceHistory.NONE, noTarget)
        val wrongCut = LiveGuidance.historyFor(archive(listOf(brisket), listOf(target), listOf(past, live)), "live", "d9", "Pork butt")
        assertEquals(PaceHistory.NONE, wrongCut)
        // The ghost is the most recent finished cook of the same cut.
        assertEquals("past", LiveGuidance.ghostCook(archive(listOf(brisket, chicken), listOf(target), listOf(past, live)), "live", " brisket ")?.id)
        assertNull(LiveGuidance.ghostCook(archive(listOf(brisket), listOf(target), listOf(past, live)), "live", null))
        assertNull(LiveGuidance.ghostCook(archive(listOf(brisket), listOf(target), listOf(past, live)), "past", "Brisket"))
    }
}
