package com.pittech.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flowing Slice 5 (element 4.4): a forked recipe seeds its plan lane from
 * the frozen story pinned in its snapshot — the source cook's recorded
 * milestones, read with the provenance definitions (total = food-on to
 * dish done, rest included; rest = rest start to dish done). The
 * fixture mirrors a pack-built snapshot: anchor at the coordinate
 * origin, offsets relative to food-on.
 */
class PlanSeedingStoryTest {
    private val origin = 946_684_800_000L // Date.UTC(2000,0,1), the pack origin
    private fun at(minute: Long) = origin + minute * 60_000L
    private fun observation(action: String, minute: Long) = RecipeObservation(
        sourceEventId = "e-$action-$minute", occurredAtUtcMillis = at(minute), timeZoneId = "UTC",
        title = action, details = "", action = action, offsetMillis = minute * 60_000L,
        temperatureContextJson = null,
    )
    private fun snapshot(observations: List<RecipeObservation>, anchor: Long? = origin, credit: String? = "SmokeAndMaple") = RecipeSnapshot(
        name = "Smoked Chicken Thighs",
        dish = DishDraft("Smoked Chicken Thighs", "Chicken"),
        observations = observations,
        smokingAnchorUtcMillis = anchor,
        publicSource = credit?.let { PublicRecipeSource("12345678-1234-4234-8234-123456789abc", "12345678-1234-4234-8234-123456789abd", it) },
    )

    @Test fun aForkedStorySeedsTheSourceCooksOwnNumbers() {
        val seeded = PlanSeeding.fromSnapshotStory(snapshot(listOf(
            observation("food_on", 0), observation("wrap", 50), observation("remove", 85),
            observation("rest_start", 85), observation("dish_done", 105),
        )))
        assertNotNull("the recorded story seeds a lane", seeded)
        seeded!!
        assertEquals(105L, seeded.schedule.cookMinMinutes)
        assertEquals(105L, seeded.schedule.cookMaxMinutes)
        assertEquals(20L, seeded.schedule.restMinutes)
        assertTrue(seeded.schedule.evidenceNote.contains("SmokeAndMaple's recorded cook"))
        assertTrue(seeded.schedule.evidenceNote.contains("1 h 45 min on the pit"))
        assertTrue(seeded.schedule.evidenceNote.contains("20 min rest"))
    }

    @Test fun aStoryWithoutDishDoneSeedsToRemovalWithNoRestClaim() {
        val seeded = PlanSeeding.fromSnapshotStory(snapshot(listOf(
            observation("food_on", 0), observation("remove", 240),
        )))
        assertNotNull(seeded)
        seeded!!
        assertEquals(240L, seeded.schedule.cookMaxMinutes)
        assertEquals(0L, seeded.schedule.restMinutes)
        assertTrue(!seeded.schedule.evidenceNote.contains("rest"))
    }

    @Test fun aSnapshotWithoutAnAnchorSeedsNothing() {
        assertNull(PlanSeeding.fromSnapshotStory(snapshot(listOf(observation("remove", 240)), anchor = null)))
    }

    @Test fun aStoryWithNoOffThePitMilestoneSeedsNothing() {
        assertNull(PlanSeeding.fromSnapshotStory(snapshot(listOf(observation("food_on", 0), observation("wrap", 60)))))
    }

    @Test fun aLocalStoryUsesTheNeutralWording() {
        val seeded = PlanSeeding.fromSnapshotStory(snapshot(listOf(
            observation("food_on", 0), observation("remove", 60),
        ), credit = null))
        assertNotNull(seeded)
        assertTrue(seeded!!.schedule.evidenceNote.contains("this recipe's recorded cook"))
    }
}
