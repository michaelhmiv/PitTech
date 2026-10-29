package com.pittech.data

import com.pittech.domain.DishDraft
import com.pittech.domain.RecipeObservation
import com.pittech.domain.RecipeSnapshot
import com.pittech.domain.RecipeSnapshotCodec
import com.pittech.domain.SharingDocument
import com.pittech.domain.SharingEntry
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Flowing Slice 3 (spec §4.5) content-model evidence: the publication
 * variant's canonical-URL slot and counts, the manual variant's hero
 * resolution (hand-logged temps replace a curve that was never drawn),
 * and the personal variant's unchanged shape.
 */
class ShareCardFlowingTest {
    private val anchor = Instant.parse("2026-10-12T10:05:00Z").toEpochMilli() // 6:05 AM in New York
    private val itemId = "123e4567-e89b-42d3-a456-426614174000"

    private fun reading(id: String, at: Long, value: Double, source: String, interval: Long) = JSONObject()
        .put("id", id).put("probeId", "p1").put("probeName", "Probe 1").put("measurementType", "food")
        .put("value", value).put("unit", "°F").put("measuredAtUtcMillis", at).put("timeZoneId", "America/New_York")
        .put("source", source).put("qualityStatus", "valid").put("samplingIntervalMillis", interval)

    private fun document(readings: List<JSONObject>): SharingDocument {
        val events = listOf(
            RecipeObservation("on", anchor, "America/New_York", "Food on", "", "food_on", 0, null),
            RecipeObservation("done", anchor + 3_600_000, "America/New_York", "Done", "", "dish_done", 3_600_000, null),
        )
        val snapshot = RecipeSnapshot("Boston Butt", DishDraft("Boston Butt", "Pork"), observations = events, smokingAnchorUtcMillis = anchor)
        val observations = JSONObject(RecipeSnapshotCodec.encode(snapshot)).getJSONArray("observations")
        val story = JSONObject().put("version", 1).put("attemptId", "story-1").put("anchorUtcMillis", anchor)
            .put("observations", observations).put("readings", JSONArray(readings)).put("results", JSONArray())
        val entry = SharingEntry(snapshot = snapshot, storyJson = story.toString()).copy(includeStory = true)
        return SharingDocument("Boston Butt", listOf(entry))
    }

    private fun manualReadings() = listOf(
        reading("m1", anchor, 40.0, "manual", 0),
        reading("m2", anchor + 1_800_000, 120.0, "manual", 0),
        reading("m3", anchor + 3_600_000, 165.0, "manual", 0),
    )

    private fun recordedReadings() = listOf(
        reading("r1", anchor, 40.0, "recorded", 15_000),
        reading("r2", anchor + 30_000, 44.0, "recorded", 15_000),
        reading("r3", anchor + 60_000, 49.0, "recorded", 15_000),
    )

    @Test fun manualCookGetsTheManualHeroAndHandLoggedChips() {
        val content = JSONObject(ShareCardContent.build(document(manualReadings()), null).content)
        assertEquals("manual", content.getString("hero"))
        val temps = content.getJSONArray("manualTemps")
        assertEquals(3, temps.length())
        assertEquals("6:05 AM", temps.getJSONObject(0).getString("label"))
        assertEquals("40 °F", temps.getJSONObject(0).getString("value"))
        assertEquals("6:35 AM", temps.getJSONObject(1).getString("label"))
        assertEquals("165 °F", temps.getJSONObject(2).getString("value"))
    }

    @Test fun recordedCookKeepsTheChartHero() {
        val content = JSONObject(ShareCardContent.build(document(recordedReadings()), null).content)
        assertEquals("chart", content.getString("hero"))
        assertEquals(0, content.getJSONArray("manualTemps").length())
    }

    @Test fun mixedCookKeepsTheChartHero() {
        val content = JSONObject(ShareCardContent.build(document(recordedReadings() + manualReadings()), null).content)
        assertEquals("chart", content.getString("hero"))
    }

    @Test fun publicationSlotCarriesCanonicalUrlAndCounts() {
        val content = JSONObject(ShareCardContent.build(document(recordedReadings()), null, publication = ShareCardPublication(itemId, 12, 3)).content)
        val publication = content.getJSONObject("publication")
        assertEquals("https://pittech.app/c/$itemId", publication.getString("url"))
        assertEquals(12, publication.getInt("likes"))
        assertEquals(3, publication.getInt("forks"))
        assertTrue(content.isNull("relay"))
    }

    @Test fun publicationCountsAreAbsentWhenUnknown() {
        val content = JSONObject(ShareCardContent.build(document(recordedReadings()), null, publication = ShareCardPublication(itemId)).content)
        val publication = content.getJSONObject("publication")
        assertEquals("https://pittech.app/c/$itemId", publication.getString("url"))
        assertFalse(publication.has("likes"))
        assertFalse(publication.has("forks"))
    }

    @Test fun personalCardShapeIsUnchanged() {
        val content = JSONObject(ShareCardContent.build(document(recordedReadings()), null).content)
        assertTrue(content.isNull("publication"))
        assertTrue(content.isNull("relay"))
        val relayed = JSONObject(ShareCardContent.build(document(recordedReadings()), null, ShareCardRelay("EMBER-OAK-7", "https://pittech.app/s/EMBER-OAK-7")).content)
        assertEquals("EMBER-OAK-7", relayed.getJSONObject("relay").getString("code"))
        assertTrue(relayed.isNull("publication"))
    }

    @Test fun aCardCarriesOneFooterOnly() {
        try {
            ShareCardContent.build(document(recordedReadings()), null, ShareCardRelay("EMBER-OAK-7", "https://pittech.app/s/EMBER-OAK-7"), ShareCardPublication(itemId))
            fail("relay + publication must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun publicationRejectsNonCanonicalItemIds() {
        try {
            ShareCardContent.build(document(recordedReadings()), null, publication = ShareCardPublication("weekly-special"))
            fail("a non-publication id must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
