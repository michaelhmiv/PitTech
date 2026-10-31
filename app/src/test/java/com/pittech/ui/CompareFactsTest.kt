package com.pittech.ui

import com.pittech.data.CookEntity
import com.pittech.data.CookResultEntity
import com.pittech.data.CookStatus
import com.pittech.data.CookWithDishes
import com.pittech.data.DishEntity
import com.pittech.data.InsightsSnapshot
import com.pittech.data.SensorReadingEntity
import com.pittech.data.TargetEntity
import com.pittech.data.TimelineEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flowing Slice 6 (Spread 05) gate evidence at the model level: the
 * input/outcome diff renders recorded vs not-recorded history honestly
 * (5.2 / 5.5), the canonical stall pair reproduces exactly (5.2), the
 * projection exists only as a tagged §4.1 range for an in-progress cook
 * (5.3), and the teaching sentence appears only with recorded support
 * (5.4). The PROJECTED tag's rendering is asserted in InsightsFreeToolsTest.
 */
class CompareFactsTest {
    private val t0 = 1_700_000_000_000L

    private fun cook(
        id: String,
        status: String = CookStatus.COMPLETED,
        startedAt: Long = t0,
        endedAt: Long? = t0 + 600 * 60_000L,
        setpoint: Double? = null,
        dishes: List<DishEntity>,
        events: List<TimelineEventEntity> = emptyList(),
    ) = CookWithDishes(
        cook = CookEntity(
            id, "Cook $id", status, startedAt, "UTC",
            endedAtUtcMillis = endedAt,
            initialSetpointValue = setpoint, initialSetpointUnit = setpoint?.let { "°F" },
            createdAtUtcMillis = startedAt, updatedAtUtcMillis = endedAt ?: startedAt,
        ),
        dishes = dishes,
        events = events,
    )

    private fun dish(id: String, cookId: String, cut: String? = "Whole packer") =
        DishEntity(id, cookId, "Dish $id", "Beef", cut, createdAtUtcMillis = 0, updatedAtUtcMillis = 0)

    private fun reading(id: String, cookId: String, dishId: String?, value: Double, atMillis: Long) =
        SensorReadingEntity(id, cookId, dishId, "p-$cookId", "Probe", "food", value, "°F", atMillis, "UTC", "controller_cloud", recordedAtUtcMillis = atMillis)

    /**
     * A cook-shaped probe series whose §4.1 stall is exactly [stallMinutes]:
     * climb 100→160°F over an hour, hold the 160°F plateau, climb out. The
     * detector's first flat reading lands 30 min into the plateau (its
     * trailing window is fully flat) and its declared start is backdated
     * 15 min, so the plateau's last reading sits at stall + 75 minutes.
     */
    private fun stallReadings(cookId: String, dishId: String, startMillis: Long, stallMinutes: Int, prefix: String): List<SensorReadingEntity> {
        val plateauEnd = stallMinutes + 75
        val out = mutableListOf<SensorReadingEntity>()
        var i = 0
        fun add(minute: Int, temp: Double) {
            out += reading("$prefix-${i++}", cookId, dishId, temp, startMillis + minute * 60_000L)
        }
        for (m in 0..60 step 5) add(m, 100.0 + m)
        for (m in 65..plateauEnd step 5) add(m, 160.0)
        for (m in (plateauEnd + 5)..(plateauEnd + 65) step 5) add(m, 160.0 + 8.0 * ((m - plateauEnd) / 5))
        return out
    }

    private fun snapshot(cooks: List<CookWithDishes>, readings: List<SensorReadingEntity>, results: List<CookResultEntity>, targets: List<TargetEntity> = emptyList()) =
        InsightsSnapshot(cooks = cooks, readings = readings, results = results, targets = targets)

    @Test fun canonicalStallPairDiffsBesideRecordedInputs() {
        // 5.2's canonical example: stall 2h 40m → 1h 35m, beside the inputs.
        val paper = cook("paper", setpoint = 250.0, dishes = listOf(dish("dp", "paper")))
        val foil = cook("foil", setpoint = 225.0, dishes = listOf(dish("df", "foil")))
        val readings = stallReadings("paper", "dp", t0, 160, "rp") + stallReadings("foil", "df", t0, 95, "rf")
        val results = listOf(
            CookResultEntity("w1", "paper", "dp", "wrap_material", textValue = "butcher_paper", recordedAtUtcMillis = t0),
            CookResultEntity("r1", "paper", "dp", "rub", textValue = "House rub", recordedAtUtcMillis = t0),
            CookResultEntity("s1", "paper", "dp", "spritz", textValue = "yes", recordedAtUtcMillis = t0),
            CookResultEntity("sm1", "paper", "dp", "spritz_medium", textValue = "Apple juice", recordedAtUtcMillis = t0),
            CookResultEntity("w2", "foil", "df", "wrap_material", textValue = "foil", recordedAtUtcMillis = t0),
            CookResultEntity("r2", "foil", "df", "rub", textValue = "Salt and pepper", recordedAtUtcMillis = t0),
        )
        val data = snapshot(listOf(paper, foil), readings, results)
        val paperFacts = compareCookFacts(paper, data, t0 + 700 * 60_000L)
        val foilFacts = compareCookFacts(foil, data, t0 + 700 * 60_000L)
        assertEquals(160.0, paperFacts.stallMinutes!!, 0.001)
        assertEquals(95.0, foilFacts.stallMinutes!!, 0.001)
        assertEquals("Butcher paper", paperFacts.wrapText)
        assertEquals("Foil", foilFacts.wrapText)
        assertEquals("House rub", paperFacts.rubText)
        assertEquals("Yes · Apple juice", paperFacts.spritzText)
        assertEquals("250°", paperFacts.setpointText)
        assertEquals("225°", foilFacts.setpointText)
        assertTrue(paperFacts.inputsRecorded)
        assertNull(paperFacts.projected)
    }

    @Test fun preCaptureCookReadsNotRecordedWhileOutcomesStillComeFromReadings() {
        // 5.5: no §4.2 rows at all — inputs say so, outcomes still work.
        val old = cook("old", dishes = listOf(dish("do", "old")))
        val readings = stallReadings("old", "do", t0, 95, "ro")
        val data = snapshot(listOf(old), readings, emptyList())
        val facts = compareCookFacts(old, data, t0 + 700 * 60_000L)
        assertFalse(facts.inputsRecorded)
        assertEquals("Not recorded", facts.wrapText)
        assertEquals("Not recorded", facts.rubText)
        assertEquals("Not recorded", facts.spritzText)
        assertEquals("Not recorded", facts.setpointText)
        assertEquals(95.0, facts.stallMinutes!!, 0.001)
        assertEquals(t0, facts.foodOnUtcMillis)
    }

    @Test fun alignmentAnchorsAtTheRecordedFoodOnMoment() {
        // 5.1: the anchor is food-on (stored as the meat_on timeline
        // action), not the session start.
        val foodOn = TimelineEventEntity(
            "e1", "anchored", "da", "meat_on", "Food on", null,
            occurredAtUtcMillis = t0 + 45 * 60_000L, recordedAtUtcMillis = t0 + 45 * 60_000L,
            timeZoneId = "UTC", source = "manual", createdAtUtcMillis = t0, updatedAtUtcMillis = t0,
        )
        val anchored = cook("anchored", dishes = listOf(dish("da", "anchored")), events = listOf(foodOn))
        val data = snapshot(listOf(anchored), emptyList(), emptyList())
        assertEquals(t0 + 45 * 60_000L, compareCookFacts(anchored, data, t0).foodOnUtcMillis)
    }

    @Test fun inProgressCookGetsAProjectedRangeFromThePaceEngine() {
        // 5.3: the in-progress cook's total is a §4.1 range, carried as a
        // projection — and a completed cook never carries one.
        val live = cook("live", status = CookStatus.ACTIVE, endedAt = null, dishes = listOf(dish("dl", "live", cut = "Boston butt")))
        val done = cook("done", dishes = listOf(dish("dd", "done", cut = "Boston butt")))
        val readings = (0..18).map { i -> reading("rl-$i", "live", "dl", 100.0 + i, t0 + i * 5 * 60_000L) }
        val targets = listOf(TargetEntity("tg", "live", "dl", "personal_finish", 203.0, "°F", "dish", createdAtUtcMillis = t0))
        val data = snapshot(listOf(live, done), readings, emptyList(), targets)
        val now = t0 + 90 * 60_000L
        val liveFacts = compareCookFacts(live, data, now)
        assertNotNull(liveFacts.projected)
        assertTrue(liveFacts.projected!!.latestMinutes > liveFacts.projected.earliestMinutes)
        assertTrue(liveFacts.projected.earliestMinutes > liveFacts.elapsedMinutes)
        assertNull(liveFacts.projectionWithheldReason)
        val doneFacts = compareCookFacts(done, data, now)
        assertNull(doneFacts.projected)
        assertNull(doneFacts.projectionWithheldReason)
    }

    @Test fun sparseInProgressCookStatesWhyNoProjection() {
        // §4.1 honesty: below the evidence minimum the engine declines,
        // and compare carries its reason instead of a fabricated range.
        val live = cook("live", status = CookStatus.ACTIVE, endedAt = null, dishes = listOf(dish("dl", "live", cut = "Boston butt")))
        val readings = listOf(reading("rl-0", "live", "dl", 100.0, t0), reading("rl-1", "live", "dl", 105.0, t0 + 5 * 60_000L))
        val targets = listOf(TargetEntity("tg", "live", "dl", "personal_finish", 203.0, "°F", "dish", createdAtUtcMillis = t0))
        val data = snapshot(listOf(live), readings, emptyList(), targets)
        val facts = compareCookFacts(live, data, t0 + 10 * 60_000L)
        assertNull(facts.projected)
        assertEquals("not enough readings yet", facts.projectionWithheldReason)
    }

    private data class BuiltCook(val item: CookWithDishes, val readings: List<SensorReadingEntity>, val results: List<CookResultEntity>)

    private fun wrapCook(id: String, material: String?, stallMinutes: Int, dayOffset: Long): BuiltCook {
        val start = t0 + dayOffset
        val item = cook(id, startedAt = start, endedAt = start + 600 * 60_000L, dishes = listOf(dish("d-$id", id)))
        val results = material?.let {
            listOf(CookResultEntity("w-$id", id, "d-$id", "wrap_material", textValue = it, recordedAtUtcMillis = start))
        } ?: emptyList()
        return BuiltCook(item, stallReadings(id, "d-$id", start, stallMinutes, "r-$id"), results)
    }

    private fun sentenceOf(built: List<BuiltCook>): String? =
        compareTeachingSentence(snapshot(built.map { it.item }, built.flatMap { it.readings }, built.flatMap { it.results }))

    @Test fun teachingSentenceMatchesTheCanonicalExampleWhenHistorySupportsIt() {
        // 5.4: three recorded paper wraps averaging a 60-minute stall,
        // two recorded foil wraps at 125 — the spec's own sentence.
        val built = listOf(
            wrapCook("p1", "butcher_paper", 60, 0),
            wrapCook("p2", "butcher_paper", 60, 2 * 86_400_000L),
            wrapCook("p3", "butcher_paper", 60, 4 * 86_400_000L),
            wrapCook("f1", "foil", 125, 86_400_000L),
            wrapCook("f2", "foil", 125, 3 * 86_400_000L),
        )
        assertEquals("Paper is winning: your last three paper wraps stalled ~1h shorter than foil.", sentenceOf(built))
    }

    @Test fun teachingSentenceStaysSilentWithoutRecordedSupport() {
        // §9 restraint: fewer than three comparable cooks on the winning
        // side, a margin the data doesn't carry, or no recorded wraps at
        // all — in every case the sentence is absent, never filler.
        val twoPaper = listOf(
            wrapCook("p1", "butcher_paper", 60, 0),
            wrapCook("p2", "butcher_paper", 60, 2 * 86_400_000L),
            wrapCook("f1", "foil", 125, 86_400_000L),
            wrapCook("f2", "foil", 125, 3 * 86_400_000L),
        )
        assertNull(sentenceOf(twoPaper))
        val thinMargin = listOf(
            wrapCook("p1", "butcher_paper", 60, 0),
            wrapCook("p2", "butcher_paper", 60, 2 * 86_400_000L),
            wrapCook("p3", "butcher_paper", 60, 4 * 86_400_000L),
            wrapCook("f1", "foil", 70, 86_400_000L),
            wrapCook("f2", "foil", 70, 3 * 86_400_000L),
        )
        assertNull(sentenceOf(thinMargin))
        val unrecorded = listOf("a", "b", "c", "d").mapIndexed { index, id -> wrapCook(id, null, 60, index * 86_400_000L) }
        assertNull(sentenceOf(unrecorded))
    }
}
