package com.pittech.domain

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The provenance payload (spec §4.3) assembled from a synthetic archive in
 * the Boston-butt shape: a stalled cook with a wrap, photos at three
 * moments, a verdict, and keep/change lessons.
 */
class RecipeProvenanceTest {
    private val start = Instant.parse("2026-10-12T10:05:00Z").toEpochMilli() // Mon Oct 12, 2026, 6:05 AM EDT
    private fun at(minute: Long) = start + minute * 60_000L

    private fun archive(withStory: Boolean = true): JSONObject {
        val root = JSONObject()
        root.put("cooks", JSONArray().put(JSONObject()
            .put("id", "c1").put("title", "Boston Butt").put("status", "completed")
            .put("startedAtUtcMillis", start).put("endedAtUtcMillis", at(655))
            .put("startedTimeZoneId", "America/New_York")
            .put("smokerName", "Offset smoker").put("initialSetpointValue", 250).put("initialSetpointUnit", "°F")
            .put("woodOrPelletBlend", "Hickory").put("fuelType", "Charcoal")))
        root.put("dishes", JSONArray().put(JSONObject()
            .put("id", "d1").put("cookId", "c1").put("name", "Boston Butt").put("foodType", "Pork")
            .put("cut", "Boston butt").put("weightValue", 9.4).put("weightUnit", "lb")))
        root.put("lifecycles", JSONArray().put(JSONObject().put("cookId", "c1").put("timingMode", "explicit_phases")))
        if (!withStory) {
            root.put("events", JSONArray()); root.put("readings", JSONArray())
            root.put("results", JSONArray()); root.put("photos", JSONArray())
            return root
        }
        val events = JSONArray()
        fun event(id: String, action: String, minute: Long) = JSONObject()
            .put("id", id).put("cookId", "c1").put("dishIds", JSONArray().put("d1")).put("action", action).put("at", at(minute))
        events.put(event("e1", "food_on", 0)).put(event("e2", "wrap", 195)).put(event("e3", "remove", 580))
            .put(event("e4", "rest_start", 580)).put(event("e5", "dish_done", 655))
        root.put("events", events)
        val readings = JSONArray()
        fun probe(minute: Long, value: Double) = JSONObject()
            .put("id", "r$minute").put("cookId", "c1").put("dishId", "d1").put("probeId", "p1").put("probeName", "Probe 1")
            .put("measurementType", "food_probe").put("value", value).put("unit", "°F")
            .put("measuredAtUtcMillis", at(minute)).put("source", "manual").put("qualityStatus", "valid")
        var minute = 0L
        while (minute <= 580) {
            val value = when {
                minute <= 120 -> 40.0 + minute
                minute <= 210 -> 160.0
                minute <= 296 -> 160.0 + (minute - 210) * 0.5
                else -> 203.0
            }
            readings.put(probe(minute, value))
            minute += 10
        }
        root.put("readings", readings)
        val results = JSONArray()
        fun result(id: String, type: String, numeric: Double?, text: String?) = JSONObject()
            .put("id", id).put("cookId", "c1").put("dishId", "d1").put("resultType", type)
            .put("numericValue", numeric ?: JSONObject.NULL).put("textValue", text ?: JSONObject.NULL)
            .put("recordedAtUtcMillis", at(655))
        results.put(result("x1", "overall_rating", 4.0, null))
            .put(result("x2", "cook_again", 1.0, null))
            .put(result("x3", "keep_doing", null, "Hickory"))
            .put(result("x4", "change_next_time", null, "Wrap earlier"))
            .put(result("x5", "result_notes", null, "Bark set by hour 5."))
        root.put("results", results)
        val photos = JSONArray()
        fun photo(id: String, eventId: String?, minute: Long) = JSONObject()
            .put("id", id).put("cookId", "c1").put("dishId", "d1")
            .put("eventId", eventId ?: JSONObject.NULL).put("caption", JSONObject.NULL)
            .put("capturedAtUtcMillis", at(minute)).put("addedAtUtcMillis", at(minute))
        photos.put(photo("p1", "e1", 5)).put(photo("p2", "e2", 200)).put(photo("p3", "e3", 585)).put(photo("p4", null, 300))
        root.put("photos", photos)
        return root
    }

    @Test fun payloadCarriesTheWholeStory() {
        val payload = RecipeProvenance.fromArchive(archive(), "c1", "d1") ?: error("payload must exist")
        assertEquals("October 12, 2026", payload.cookedDateText)
        assertEquals("Offset smoker", payload.pitName)
        assertEquals(250.0, payload.setpointF!!, 0.0)
        assertEquals("Hickory", payload.wood)
        assertEquals("9.4 lb", payload.weightText)
        assertEquals(655.0, payload.totalMinutes!!, 0.0)
        assertTrue("a real stall is measured, not implied", payload.stallMinutes!! in 60.0..120.0)
        assertEquals(160.0, payload.wrappedAtF!!, 0.5)
        assertEquals(75.0, payload.restMinutes!!, 0.0)
        assertEquals(4.0, payload.rating!!, 0.0)
        assertEquals(true, payload.cookAgain)
        assertEquals("Bark set by hour 5.", payload.note)
        assertEquals(listOf("Hickory"), payload.keepChips)
        assertEquals(listOf("Wrap earlier"), payload.changeChips)
        assertEquals(
            listOf(ProvenancePhotoRole.ON_PIT, ProvenancePhotoRole.AT_WRAP, ProvenancePhotoRole.SLICED, ProvenancePhotoRole.OTHER),
            payload.photos.map { it.role },
        )
        assertTrue(payload.curve.isNotEmpty() && payload.curve.size <= RecipeProvenance.MAX_CURVE_POINTS)
        assertEquals(0.0, payload.curve.first().offsetMinutes, 0.0)
        assertEquals(203.0, payload.curve.last().fahrenheit, 0.0)
    }

    @Test fun noSourceCookMeansNoPayload() {
        assertNull(RecipeProvenance.fromArchive(archive(), null, "d1"))
        assertNull(RecipeProvenance.fromArchive(archive(), "missing-cook", "d1"))
        assertNull(RecipeProvenance.fromArchive(archive(), "c1", null))
        assertNull(RecipeProvenance.fromArchive(archive(), "c1", "missing-dish"))
    }

    @Test fun aCookWithoutAStoryYieldsHonestAbsencesNotFabrications() {
        val payload = RecipeProvenance.fromArchive(archive(withStory = false), "c1", "d1") ?: error("the cook exists; the payload should too")
        assertEquals("October 12, 2026", payload.cookedDateText)
        assertNull(payload.totalMinutes)
        assertNull(payload.stallMinutes)
        assertNull(payload.wrappedAtF)
        assertNull(payload.rating)
        assertNull(payload.note)
        assertTrue(payload.curve.isEmpty())
        assertTrue(payload.photos.isEmpty())
        assertTrue(payload.keepChips.isEmpty() && payload.changeChips.isEmpty())
    }
}
