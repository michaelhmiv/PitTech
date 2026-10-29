package com.pittech.data

import com.pittech.domain.CookNoteMetadata
import com.pittech.domain.DishDraft
import com.pittech.domain.PlanSeeding
import com.pittech.domain.RecipeProvenance
import com.pittech.domain.RecipeSnapshot
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writer-faithful seam test for plan seeding (Flowing spec 3.4): the
 * durations a seeded lane carries must be the ones the source cook
 * actually recorded, read through the same archive → provenance bridge
 * the recipe story uses (Slice 3's seam). The fixture mirrors the
 * writers' `toJson` shapes key for key — a Boston-butt cook: food on at
 * minute 0, off at 578, rest at 580, done at 655 (total 655, rest 75).
 */
class PlanSeedingArchiveSeamTest {
    private val start = Instant.parse("2026-10-12T10:05:00Z").toEpochMilli()
    private fun at(minute: Long) = start + minute * 60_000L

    private fun realArchive(): JSONObject {
        val root = JSONObject()
        root.put("cooks", JSONArray().put(JSONObject()
            .put("id", "c1").put("title", "Story night").put("status", "completed")
            .put("startedAtUtcMillis", start).put("startedTimeZoneId", "America/New_York")
            .put("endedAtUtcMillis", at(660)).put("smokerName", "Austin XL")
            .put("initialSetpointValue", 250).put("initialSetpointUnit", "°F")
            .put("notes", JSONObject.NULL).put("createdAtUtcMillis", start).put("updatedAtUtcMillis", at(660))
            .put("fuelType", JSONObject.NULL).put("woodOrPelletBlend", "Hickory")
            .put("outdoorTemperatureValue", JSONObject.NULL).put("outdoorTemperatureUnit", JSONObject.NULL)
            .put("weatherNotes", JSONObject.NULL).put("windNotes", JSONObject.NULL)))
        root.put("dishes", JSONArray().put(JSONObject()
            .put("id", "d1").put("cookId", "c1").put("name", "Boston Butt").put("foodType", "Pork")
            .put("cut", "Boston butt").put("weightValue", 9.4).put("weightUnit", "lb")
            .put("pieceCount", JSONObject.NULL).put("startingCondition", JSONObject.NULL)
            .put("boneIn", JSONObject.NULL).put("placement", JSONObject.NULL).put("prepNotes", JSONObject.NULL)
            .put("createdAtUtcMillis", start).put("updatedAtUtcMillis", at(660))
            .put("gradeOrSource", JSONObject.NULL).put("thicknessNotes", JSONObject.NULL)))
        root.put("lifecycles", JSONArray().put(JSONObject().put("cookId", "c1").put("timingMode", "explicit_phases")))
        fun event(id: String, action: String, title: String, minute: Long) = JSONObject()
            .put("id", id).put("cookId", "c1").put("dishId", "d1").put("eventType", "note")
            .put("title", title).put("details", JSONObject.NULL)
            .put("occurredAtUtcMillis", at(minute)).put("recordedAtUtcMillis", at(minute))
            .put("timeZoneId", "America/New_York").put("source", "manual").put("sourceDeviceId", JSONObject.NULL)
            .put("createdAtUtcMillis", at(minute)).put("updatedAtUtcMillis", at(minute))
            .put("temperatureContextJson", JSONObject.NULL)
            .put("noteMetadataJson", CookNoteMetadata("action", action, dishIds = listOf("d1")).encode())
        root.put("events", JSONArray()
            .put(event("e1", "food_on", "Food on", 0))
            .put(event("e2", "wrap", "Wrap", 195))
            .put(event("e3", "remove", "Off the pit", 578))
            .put(event("e4", "rest_start", "Rest", 580))
            .put(event("e5", "dish_done", "Done", 655)))
        val readings = JSONArray()
        var minute = 0L
        while (minute <= 580) {
            val value = when {
                minute <= 120 -> 40.0 + minute
                minute <= 210 -> 160.0
                minute <= 296 -> 160.0 + (minute - 210) * 0.5
                else -> 203.0
            }
            readings.put(JSONObject()
                .put("id", "r$minute").put("cookId", "c1").put("dishId", "d1").put("probeId", JSONObject.NULL)
                .put("probeName", "Butt").put("measurementType", "food_probe").put("value", value).put("unit", "°F")
                .put("measuredAtUtcMillis", at(minute)).put("timeZoneId", "America/New_York").put("source", "manual")
                .put("sourceDeviceId", JSONObject.NULL).put("qualityStatus", "valid").put("recordedAtUtcMillis", at(minute)))
            minute += 10
        }
        root.put("readings", readings)
        root.put("results", JSONArray())
        root.put("photos", JSONArray())
        return root
    }

    @Test fun aSeededLaneCarriesTheRecordedCooksOwnNumbers() {
        val provenance = RecipeProvenance.fromArchive(provenanceReadyArchive(realArchive()), "c1", "d1")
        assertNotNull("provenance payload from the bridged archive", provenance)
        val snapshot = RecipeSnapshot(
            name = "Boston Butt",
            dish = DishDraft("Boston Butt", "Pork", cut = "Boston butt", weightText = "9.4", weightUnit = "lb"),
        )
        val seeded = PlanSeeding.fromRecipe(snapshot, provenance)
        assertNotNull("the recorded cook seeds a lane", seeded)
        seeded!!
        assertEquals(655L, seeded.schedule.cookMinMinutes)
        assertEquals(655L, seeded.schedule.cookMaxMinutes)
        assertEquals(75L, seeded.schedule.restMinutes)
        assertEquals(250.0, seeded.schedule.pitF!!, 0.001)
        assertTrue(seeded.schedule.evidenceNote.contains("October 12, 2026"))
    }
}
