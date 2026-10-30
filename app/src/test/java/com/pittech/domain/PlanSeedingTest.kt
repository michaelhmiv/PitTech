package com.pittech.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * Plan seeding (Flowing spec 3.4): a lane arrives from a recipe with the
 * §4.3 provenance durations attached — or, when the source cook recorded
 * no durations, it does not arrive at all. Nothing is invented to fill
 * the gap (§4.3: absence is data, never filled in).
 */
class PlanSeedingTest {
    private fun provenance(total: Double? = 655.0, rest: Double? = 75.0) = RecipeProvenance(
        sourceCookId = "c1", sourceDishId = "d1", cookTitle = "Story night",
        cookedDateText = "October 12, 2026", cookedAtUtcMillis = 0L, timeZoneId = "America/New_York",
        pitName = "Austin XL", setpointF = 250.0, wood = "Hickory", fuel = null,
        dishName = "Boston Butt", cut = "Boston butt", weightText = "9.4 lb",
        totalMinutes = total, stallMinutes = 105.0, wrappedAtF = 160.0, restMinutes = rest,
        curve = emptyList(), photos = emptyList(), note = null, rating = null, cookAgain = null,
        keepChips = emptyList(), changeChips = emptyList(),
    )

    private fun snapshot(pit: Double? = null) = RecipeSnapshot(
        name = "Boston Butt",
        dish = DishDraft("Boston Butt", "Pork", cut = "Boston butt", weightText = "9.4", weightUnit = "lb"),
        preferredPitTemperatureF = pit,
    )

    @Test fun aSeededLaneCarriesTheSourceCooksRecordedDurations() {
        val seeded = PlanSeeding.fromRecipe(snapshot(), provenance())!!
        assertEquals("Boston Butt", seeded.dish.name)
        assertEquals(655L, seeded.schedule.cookMinMinutes)
        assertEquals(655L, seeded.schedule.cookMaxMinutes)
        assertEquals(75L, seeded.schedule.restMinutes)
        assertEquals(250.0, seeded.schedule.pitF!!, 0.001)
        assertEquals(1, seeded.schedule.evidenceCount)
        assertTrue(seeded.schedule.evidenceNote.contains("October 12, 2026"))
        assertTrue(seeded.schedule.evidenceNote.contains("10 h 55 min on the pit"))
        assertTrue(seeded.schedule.evidenceNote.contains("1 h 15 min rest"))
    }

    @Test fun theRecipeSnapshotPitWinsOverTheSourceCookSetpoint() {
        val seeded = PlanSeeding.fromRecipe(snapshot(pit = 275.0), provenance())!!
        assertEquals(275.0, seeded.schedule.pitF!!, 0.001)
    }

    @Test fun noSourceCookSeedsNothing() {
        assertNull(PlanSeeding.fromRecipe(snapshot(), null))
    }

    @Test fun aSourceCookWithNoRecordedTotalSeedsNothing() {
        assertNull(PlanSeeding.fromRecipe(snapshot(), provenance(total = null)))
    }

    @Test fun anUnrecordedRestSeedsZeroAndTheNoteSaysOnlyWhatWasRecorded() {
        val seeded = PlanSeeding.fromRecipe(snapshot(), provenance(rest = null))!!
        assertEquals(0L, seeded.schedule.restMinutes)
        assertFalse(seeded.schedule.evidenceNote.contains("rest"))
    }
}
