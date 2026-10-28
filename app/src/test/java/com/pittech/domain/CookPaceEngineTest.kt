package com.pittech.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The Slice 1 backtest: CookPaceEngine run against the shared pace/stall
 * corpus (contracts/fixtures/pace-stall-corpus-v1.json), judged against the
 * oracles in that fixture's README and the Flowing spec §9 trust bar.
 * Protocol: evaluate every 15 minutes from 30 minutes after food-on until
 * the finish, truncating the series at each evaluation time, publishing
 * through one PaceRangeSmoother per cook — the way a live surface would.
 */
class CookPaceEngineTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }

    private data class Case(
        val name: String,
        val archive: InsightArchive,
        val targetF: Double,
        val foodOn: Long,
        val finish: Long,
        val stallStart: Long?,
        val stallEnd: Long?,
        val wrapAt: Long?,
    )

    private fun cases(): List<Case> {
        val root = JSONObject(resource("pace-stall-corpus-v1"))
        val array = root.getJSONArray("cases")
        return (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val archive = InsightsCalculations.archiveFromJson(c.getJSONObject("archive"))
            val expected = c.getJSONObject("expected")
            Case(
                name = c.getString("name"),
                archive = archive,
                targetF = expected.getDouble("targetTempF"),
                foodOn = archive.events.minOf { it.at },
                finish = expected.getLong("finishUtcMillis"),
                stallStart = if (expected.isNull("stallStartUtcMillis")) null else expected.getLong("stallStartUtcMillis"),
                stallEnd = if (expected.isNull("stallEndUtcMillis")) null else expected.getLong("stallEndUtcMillis"),
                wrapAt = archive.events.firstOrNull { it.action == "wrap" }?.at,
            )
        }
    }

    private fun series(case: Case, upto: Long = Long.MAX_VALUE): List<PaceReading> {
        val dish = case.archive.dishes.single()
        return case.archive.readings
            .filter { it.cookId == dish.cookId && (it.dishId == dish.id || it.dishId == null) &&
                InsightsCalculations.role(it.measurementType) == "food" && it.qualityStatus == "valid" && it.value != null }
            .map { PaceReading(it.measuredAtUtcMillis, InsightsCalculations.fahrenheit(it.value!!, it.unit)) }
            .filter { it.atUtcMillis <= upto }
            .sortedBy { it.atUtcMillis }
    }

    /** Briskets A/B/D lend each other history; chicken and the hand-logged butt have none. */
    private fun historyFor(case: Case, all: List<Case>): PaceHistory {
        val briskets = all.filter { "brisket" in it.name }
        if (case !in briskets) return PaceHistory.NONE
        val others = briskets - case
        val targets = others.flatMap { other -> other.archive.dishes.map { dish -> "${dish.cookId}/${dish.id}" to other.targetF } }.toMap()
        return CookPaceEngine.history(others.map { it.archive }, targets)
    }

    private data class Statement(val at: Long, val range: FinishRange, val stall: StallState)

    private fun runProtocol(case: Case, all: List<Case>): List<Statement> {
        val history = historyFor(case, all)
        val smoother = PaceRangeSmoother()
        val statements = mutableListOf<Statement>()
        var t = case.foodOn + 30 * 60_000L
        while (t < case.finish) {
            val assessment = CookPaceEngine.assess(series(case, t), case.targetF, t, case.foodOn, history)
            val ranged = (assessment.finish as? FinishEstimate.Ranged)?.range
            val published = smoother.publish(ranged, t)
            if (published != null) statements += Statement(t, published, assessment.stall)
            t += 15 * 60_000L
        }
        return statements
    }

    private fun minutes(ms: Long) = ms / 60_000.0

    @Test fun stallEdgesLandWithinTwentyMinutesOnEveryCorpusBrisket() {
        for (case in cases().filter { it.stallStart != null }) {
            val spans = CookPaceEngine.stallSpans(series(case))
            assertEquals("${case.name}: exactly one stall (a pit dip must not split it)", 1, spans.size)
            val span = spans.single()
            assertTrue("${case.name}: start error ${minutes(span.startUtcMillis - case.stallStart!!)} min",
                kotlin.math.abs(span.startUtcMillis - case.stallStart) <= 20 * 60_000L)
            assertTrue("${case.name}: end error ${minutes(span.endUtcMillis - case.stallEnd!!)} min",
                kotlin.math.abs(span.endUtcMillis - case.stallEnd) <= 20 * 60_000L)
        }
    }

    @Test fun canonicalBrisketRainDipIsNeverAStallSignal() {
        val case = cases().first { "canonical" in it.name }
        val spans = CookPaceEngine.stallSpans(series(case))
        val firstInBand = series(case).first { it.fahrenheit >= PaceConstants.STALL_BAND_LOW_F }.atUtcMillis
        assertTrue("no stall before the probe enters the band", spans.all { it.startUtcMillis >= firstInBand - 20 * 60_000L })
    }

    @Test fun foilWrapDoesNotEndTheStall() {
        val case = cases().first { "foil" in it.name }
        val span = CookPaceEngine.stallSpans(series(case)).single()
        assertTrue("wrap falls inside the stall", case.wrapAt!! in span.startUtcMillis..span.endUtcMillis)
        assertTrue("the stall ends on the probe's exit, not the wrap", span.endUtcMillis - case.wrapAt >= 60 * 60_000L)
        assertEquals("stall duration is the oracle's 2h40m (±20)", 160.0, span.durationMinutes, 20.0)
    }

    @Test fun fastChickenReportsNoStallAndAnEarlyContainingRange() {
        val all = cases()
        val case = all.first { "chicken" in it.name }
        assertTrue("presence in the band is not a stall", CookPaceEngine.stallSpans(series(case)).isEmpty())
        val statements = runProtocol(case, all)
        assertTrue("a range is available early", statements.isNotEmpty() && statements.first().at <= case.foodOn + 45 * 60_000L)
        assertTrue("the first range contains the finish", statements.first().range.let { case.finish in it.earliestUtcMillis..it.latestUtcMillis })
    }

    @Test fun firstStatedRangeContainsTheFinishOnEveryRangeProducingShape() {
        val all = cases()
        for (case in all.filter { "boston butt" !in it.name }) {
            val statements = runProtocol(case, all)
            assertTrue("${case.name}: the engine states a range", statements.isNotEmpty())
            val first = statements.first().range
            assertTrue("${case.name}: first stated range [${first.earliestUtcMillis}, ${first.latestUtcMillis}] contains finish ${case.finish}",
                case.finish in first.earliestUtcMillis..first.latestUtcMillis)
        }
    }

    @Test fun postStallRangesContainTheFinishOnTheCanonicalBrisket() {
        val all = cases()
        val case = all.first { "canonical" in it.name }
        val postStall = runProtocol(case, all).filter { it.at > case.stallEnd!! }
        assertTrue("ranges are stated after the stall", postStall.isNotEmpty())
        for (s in postStall) assertTrue("post-stall range at ${s.at} contains the finish",
            case.finish in s.range.earliestUtcMillis..s.range.latestUtcMillis)
    }

    @Test fun overnightCreepStallWidensInsteadOfSlidingEarlier() {
        val all = cases()
        val case = all.first { "overnight" in it.name }
        val statements = runProtocol(case, all)
        val midStall = statements.filter { it.at in case.stallStart!!..case.stallEnd!! && it.stall is StallState.InStall }
        assertTrue("the creep is detected as a stall mid-cook", midStall.isNotEmpty())
        for (s in midStall) {
            assertTrue("mid-stall range is wide, not a creep extrapolation", s.range.widthMinutes >= 90.0)
            assertTrue("mid-stall range contains the finish", case.finish in s.range.earliestUtcMillis..s.range.latestUtcMillis)
        }
    }

    @Test fun handLoggedButtGetsNoRangeAndAnUnknownStallState() {
        val all = cases()
        val case = all.first { "boston butt" in it.name }
        var t = case.foodOn + 30 * 60_000L
        while (t < case.finish) {
            val assessment = CookPaceEngine.assess(series(case, t), case.targetF, t, case.foodOn, historyFor(case, all))
            assertTrue("no pace range from one probe reading (t=$t)", assessment.finish is FinishEstimate.PlanOnly)
            assertTrue("stall state is unknown, never 'no stall' (t=$t)", assessment.stall is StallState.Unknown)
            t += 15 * 60_000L
        }
    }

    // ---- unit behavior -------------------------------------------------

    private fun synthetic(points: List<Pair<Long, Double>>) = points.map { PaceReading(it.first * 60_000L, it.second) }

    @Test fun flatInBandSeriesYieldsOneBackdatedSpan() {
        // Climb to 160 by minute 60, dead flat until 150, then climb out.
        val pts = mutableListOf<Pair<Long, Double>>()
        for (m in 0..60 step 5) pts += m.toLong() to (100.0 + m)
        for (m in 65..150 step 5) pts += m.toLong() to 160.0
        for (m in 155..200 step 5) pts += m.toLong() to (160.0 + (m - 150) * 0.8)
        val spans = CookPaceEngine.stallSpans(synthetic(pts))
        assertEquals(1, spans.size)
        // Flat run evaluates flat from ~minute 90 (30-min window fully flat);
        // the declared start is backdated half a window to ~75.
        assertTrue(spans.single().startUtcMillis in (60 * 60_000L)..(90 * 60_000L))
    }

    @Test fun climbingThroughTheBandIsNotAStall() {
        val pts = (0..40 step 5).map { m -> m.toLong() to (140.0 + m * 1.5) }
        assertTrue(CookPaceEngine.stallSpans(synthetic(pts)).isEmpty())
    }

    @Test fun slowCreepInBandIsAStall() {
        val pts = (0..36 step 5).map { m -> m.toLong() to (156.0 + m * 0.115) } // ~0.023°F/min for 3h
        assertEquals(1, CookPaceEngine.stallSpans(synthetic(pts)).size)
    }

    @Test fun aLongGapBreaksAFlatRun() {
        val pts = mutableListOf<Pair<Long, Double>>()
        for (m in 0..40 step 5) pts += m.toLong() to 160.0
        for (m in 90..130 step 5) pts += m.toLong() to 160.0 // 50-minute silence between
        assertTrue("two 40-minute flats across a 50-minute gap never merge into a stall",
            CookPaceEngine.stallSpans(synthetic(pts)).isEmpty())
    }

    @Test fun sparseReadingsYieldPlanBasisAndUnknownStall() {
        val assessment = CookPaceEngine.assess(
            listOf(PaceReading(0L, 140.0)), targetF = 203.0, nowUtcMillis = 60 * 60_000L, foodOnUtcMillis = 0L)
        assertTrue(assessment.finish is FinishEstimate.PlanOnly)
        assertTrue(assessment.stall is StallState.Unknown)
    }

    @Test fun reachingTheTargetIsFinishedNotARange() {
        val pts = (0..12 step 5).map { m -> PaceReading(m * 5 * 60_000L, 150.0 + m * 5.0) }
        val assessment = CookPaceEngine.assess(pts, targetF = 203.0, nowUtcMillis = 60 * 60_000L, foodOnUtcMillis = 0L)
        assertTrue(assessment.finish is FinishEstimate.Finished)
    }

    @Test fun smootherHonorsCadenceAndBoundsEdgeSteps() {
        val smoother = PaceRangeSmoother()
        val first = FinishRange(1_000_000L, 2_000_000L, PaceBasis.PACE)
        assertEquals(first, smoother.publish(first, 0L))
        val moved = FinishRange(1_000_000L + 60 * 60_000L, 2_000_000L + 60 * 60_000L, PaceBasis.PACE)
        assertEquals("inside the cadence the published range stands", first, smoother.publish(moved, 5 * 60_000L))
        val stepped = smoother.publish(moved, 11 * 60_000L)!!
        assertEquals("an edge moves at most the step per publication",
            first.earliestUtcMillis + PaceConstants.SMOOTHER_MAX_EDGE_STEP_MINUTES * 60_000L, stepped.earliestUtcMillis)
        assertEquals("a null candidate states nothing", null, smoother.publish(null, 30 * 60_000L))
    }

    @Test fun basisLinesReadAsTheSpecWroteThem() {
        assertEquals("from your last 30 min of readings", PaceBasis.PACE.displayText())
        assertEquals("from your plan — not enough readings yet", PaceBasis.PLAN.displayText())
    }
}
