package com.pittech.domain

import org.junit.Test
import org.junit.Assert.*

class RecipeReferenceTimelineTest {
    private fun observation(id: String, at: Long, action: String?) = RecipeObservation(id, at, "UTC", id, "Recorded", action, at - 100_000, null)
    @Test fun originalIntervalsKeepReturnsToHeatAndUnrecordedEnds() {
        val snapshot = RecipeSnapshot("Mac", DishDraft("Mac", "Pasta"), observations = listOf(observation("prep", 40_000, null), observation("on", 100_000, "food_on"), observation("wrap", 150_000, "wrap"), observation("off", 200_000, "remove"), observation("return", 300_000, "food_on")), smokingAnchorUtcMillis = 100_000)
        assertEquals(listOf(HistoricalHeatingInterval(100_000, 200_000), HistoricalHeatingInterval(300_000, null)), RecipeReferenceTimeline.heating(snapshot))
        assertEquals("−0h 1m", RecipeReferenceTimeline.relativeLabel(snapshot.observations.first().offsetMillis))
        val edited = snapshot.copy(instructions = listOf(RecipeInstruction("new", "Do something different", offsetMillis = 999)))
        assertEquals(RecipeReferenceTimeline.events(snapshot), RecipeReferenceTimeline.events(edited))
        assertEquals(snapshot, RecipeSnapshotCodec.decode(RecipeSnapshotCodec.encode(snapshot)))
    }
    @Test fun missingAnchorDoesNotInventRelativeTimeOrHeatingDuration() {
        val snapshot = RecipeSnapshot("Mac", DishDraft("Mac", "Pasta"), observations = listOf(observation("prep", 40_000, null).copy(offsetMillis = null)))
        assertTrue(RecipeReferenceTimeline.heating(snapshot).isEmpty())
        assertEquals("No recorded Food on anchor", RecipeReferenceTimeline.relativeLabel(null))
    }
}
