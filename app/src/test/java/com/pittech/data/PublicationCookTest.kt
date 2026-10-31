package com.pittech.data

import com.pittech.domain.DishDraft
import com.pittech.domain.RecipeSnapshot
import com.pittech.domain.SharingDocument
import com.pittech.domain.SharingDraftCodec
import com.pittech.domain.SharingEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publishable Cooks (issue #250), Android writer: a meal draft that carries
 * its recorded session publishes as kind=cook — session object on the
 * payload, every dish's story placed on the session clock, cook inputs
 * lifted into the stories — and forking the result saves its dishes as
 * library recipes (Option A: the food, not the session).
 */
class PublicationCookTest {
    private val start = 1_700_000_000_000L

    private fun sessionJson(): String = JSONObject()
        .put("startedAtUtcMillis", start).put("endedAtUtcMillis", start + 40_800_000)
        .put("smokerName", "Offset smoker").put("fuelType", "Pellets").put("woodOrPelletBlend", "Oak")
        .put("setpointValue", 250.0).put("setpointUnit", "°F")
        .put("outdoorTemperatureValue", 61.0).put("outdoorTemperatureUnit", "°F")
        .put("weatherNotes", "Clear").put("windNotes", "").put("notes", "Game day")
        .put("serveAtUtcMillis", start + 39_600_000).toString()

    private fun observation(id: String, at: Long, action: String, title: String) = JSONObject()
        .put("sourceEventId", id).put("occurredAtUtcMillis", at).put("timeZoneId", "UTC")
        .put("title", title).put("details", "").put("action", action)
        .put("offsetMillis", JSONObject.NULL).put("recordedTemperatures", JSONArray())

    private fun reading(id: String, at: Long, type: String, value: Double) = JSONObject()
        .put("id", id).put("probeId", "probe-$id").put("probeName", if (type == "pit") "Pit probe" else "Food probe")
        .put("measurementType", type).put("value", value).put("unit", "°F")
        .put("measuredAtUtcMillis", at).put("timeZoneId", "UTC").put("timestampBasis", "recorded")
        .put("samplingIntervalMillis", 0).put("qualityStatus", "valid").put("source", "recorded")

    private fun result(type: String, text: String? = null, numeric: Double? = null) = JSONObject()
        .put("type", type).put("textValue", text ?: JSONObject.NULL)
        .put("numericValue", numeric ?: JSONObject.NULL).put("unit", JSONObject.NULL)

    private fun story(attemptId: String, anchor: Long, observations: List<JSONObject>, readings: List<JSONObject>, results: List<JSONObject>) = JSONObject()
        .put("version", 1).put("attemptId", attemptId).put("anchorUtcMillis", anchor)
        .put("connectionGaps", JSONArray()).put("observations", JSONArray(observations))
        .put("readings", JSONArray(readings)).put("results", JSONArray(results)).toString()

    private fun brisket(): SharingEntry {
        val anchor = start + 3_600_000
        return SharingEntry(
            sourceDishId = "dish-1",
            snapshot = RecipeSnapshot("Brisket", DishDraft("Brisket", "Beef")),
            storyJson = story("dish-1", anchor,
                listOf(observation("e1", anchor, "food_on", "Food on"), observation("e2", anchor + 7_200_000, "wrap", "Wrapped")),
                listOf(reading("r1", anchor + 600_000, "pit", 250.0)),
                listOf(result("wrap_material", "butcher_paper"), result("rub", "House rub"), result("spritz", "yes"), result("spritz_medium", "Apple juice"), result("overall_rating", numeric = 5.0), result("result_notes", "Great bark"))),
            includeStory = true,
        )
    }

    private fun potatoes(): SharingEntry {
        val anchor = start + 5_400_000
        return SharingEntry(
            sourceDishId = "dish-2",
            snapshot = RecipeSnapshot("Smoked potatoes", DishDraft("Smoked potatoes", "Potatoes")),
            storyJson = story("dish-2", anchor,
                listOf(observation("e3", anchor, "food_on", "Food on"), observation("e4", anchor + 18_000_000, "remove", "Food off")),
                listOf(reading("r2", anchor + 600_000, "food", 200.0)),
                listOf(result("overall_rating", numeric = 4.0))),
            includeStory = true,
        )
    }

    private fun document(entries: List<SharingEntry> = listOf(brisket(), potatoes()), session: String? = sessionJson()) =
        SharingDocument("Game day cook", entries, sourceCookId = "cook-1", sessionJson = session, categoryId = "beef")

    @Test fun cookPublicationCarriesTheSessionStoriesOnOneClockAndLiftedInputs() {
        val payload = PublicationProjection.build(document(), 1, emptyList(), "cook").payload
        assertEquals("cook", payload.getString("kind"))
        assertTrue(payload.isNull("measuredAttemptId"))
        val session = payload.getJSONObject("session")
        assertEquals("Offset smoker", session.getString("smokerName"))
        assertEquals("Pellets", session.getString("fuelType"))
        assertEquals(250.0, session.getJSONObject("setpoint").getDouble("value"), 0.0)
        assertEquals("°F", session.getJSONObject("setpoint").getString("unit"))
        assertEquals(39_600_000L, session.getLong("serveAtOffsetMillis"))
        assertEquals(40_800_000L, session.getLong("durationMillis"))
        val first = payload.getJSONArray("recipes").getJSONObject(0).getJSONObject("story")
        assertEquals(3_600_000L, first.getLong("anchorOffsetMillis"))
        assertEquals(0L, first.getLong("foodOnOffsetMillis"))
        val inputs = first.getJSONObject("inputs")
        assertEquals("butcher_paper", inputs.getString("wrapMaterial"))
        assertEquals("House rub", inputs.getString("rub"))
        assertTrue(inputs.getBoolean("spritz"))
        assertEquals("Apple juice", inputs.getString("spritzMedium"))
        val second = payload.getJSONArray("recipes").getJSONObject(1).getJSONObject("story")
        assertEquals(5_400_000L, second.getLong("anchorOffsetMillis"))
        assertTrue("a dish with no recorded inputs publishes inputs as null, never invented ones", second.isNull("inputs"))
    }

    @Test fun otherKindsGainNoSessionAndNoSessionClockFields() {
        val payload = PublicationProjection.build(document(), 1, emptyList(), "collection").payload
        assertFalse(payload.has("session"))
        val story = payload.getJSONArray("recipes").getJSONObject(0).getJSONObject("story")
        assertFalse(story.has("anchorOffsetMillis"))
        assertFalse(story.has("inputs"))
    }

    @Test fun cookPublicationRequiresTheSessionAndEveryStory() {
        assertThrows(IllegalArgumentException::class.java) { PublicationProjection.build(document(session = null), 1, emptyList(), "cook") }
        val storyless = document(listOf(brisket(), potatoes().copy(includeStory = false)))
        assertThrows(IllegalArgumentException::class.java) { PublicationProjection.build(storyless, 1, emptyList(), "cook") }
    }

    @Test fun cookPublicationRejectsAStoryFromOutsideTheSessionWindow() {
        val anchor = start - 60_000
        val foreign = SharingEntry(
            sourceDishId = "dish-9",
            snapshot = RecipeSnapshot("Yesterday's ribs", DishDraft("Ribs", "Pork")),
            storyJson = story("dish-9", anchor, listOf(observation("e9", anchor, "food_on", "Food on")), emptyList(), emptyList()),
            includeStory = true,
        )
        assertThrows(IllegalArgumentException::class.java) { PublicationProjection.build(document(listOf(brisket(), foreign)), 1, emptyList(), "cook") }
    }

    @Test fun sessionFactsRoundTripThroughTheDraftCodecAndLegacyDraftsStayReadable() {
        val encoded = SharingDraftCodec.encode(document())
        val decoded = SharingDraftCodec.decode(encoded)
        assertTrue(SharingDraftCodec.sameStory(sessionJson(), decoded.sessionJson!!))
        val legacy = JSONObject(encoded).apply { remove("session") }.toString()
        assertNull(SharingDraftCodec.decode(legacy).sessionJson)
    }

    @Test fun discoverParsesCookItems() {
        val item = PublicItem.parse(JSONObject()
            .put("id", "00000000-0000-4000-8000-000000000001").put("revisionId", "00000000-0000-4000-8000-000000000002")
            .put("title", "Game day cook").put("kind", "cook")
            .put("credit", JSONObject().put("displayName", "a PitTech cook")).put("likes", 3).put("forks", 1))
        assertEquals("cook", item.kind)
    }

    @Test fun forkingACookPublicationSavesItsDishesAsRecipes() = runBlocking {
        val payload = PublicationProjection.build(document(), 1, emptyList(), "cook").payload
        val detail = PublicDetail("00000000-0000-4000-8000-0000000000aa", "00000000-0000-4000-8000-0000000000ab", payload, "a PitTech cook", emptyList())
        val saved = PublicationFork.prepare(detail) { PublicationFork.untweaked() }
        assertEquals(2, saved.size)
        assertEquals(listOf("Brisket", "Smoked potatoes"), saved.map { it.snapshot.name })
        saved.forEach { entry ->
            assertEquals(detail.id, entry.snapshot.publicSource!!.itemId)
            assertTrue("each forked dish keeps its measured story pinned", entry.story != null)
        }
    }
}
