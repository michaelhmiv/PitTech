package com.pittech.data

import com.pittech.domain.CookNoteMetadata
import com.pittech.domain.ProvenancePhotoRole
import com.pittech.domain.RecipeProvenance
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the Flowing §4.3 seam: the archive the app actually
 * writes ([archiveSnapshotJson]) stores timeline events in the storage
 * vocabulary — `eventType` / `occurredAtUtcMillis`, with the action and
 * dish scopes encoded inside `noteMetadataJson` — while
 * [RecipeProvenance.fromArchive] consumes the insights vocabulary
 * (`action` / `at` / `dishIds`). Feeding the raw archive to the payload
 * builder throws on the first event, which the story surface swallowed
 * into a silently missing story block. [provenanceReadyArchive] is the
 * bridge; this fixture mirrors the writers' `toJson` shapes key for key.
 */
class ProvenanceArchiveSeamTest {
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
        fun result(id: String, type: String, text: String) = JSONObject()
            .put("id", id).put("cookId", "c1").put("dishId", "d1").put("resultType", type)
            .put("numericValue", JSONObject.NULL).put("unit", JSONObject.NULL).put("textValue", text)
            .put("notes", JSONObject.NULL).put("recordedAtUtcMillis", at(655))
        root.put("results", JSONArray()
            .put(result("x1", "keep_doing", "Paper wrap, no spritz"))
            .put(result("x2", "change_next_time", "Start an hour earlier")))
        fun photo(id: String, eventId: String, caption: String, minute: Long) = JSONObject()
            .put("id", id).put("cookId", "c1").put("dishId", "d1").put("eventId", eventId)
            .put("originalFileName", "$id.jpg").put("mimeType", "image/jpeg").put("caption", caption)
            .put("capturedAtUtcMillis", at(minute)).put("addedAtUtcMillis", at(minute))
            .put("temperatureContextJson", JSONObject.NULL).put("archivePath", "attachments/$id.jpg")
        root.put("photos", JSONArray()
            .put(photo("p1", "e1", "On the pit", 5))
            .put(photo("p2", "e2", "At the wrap", 200)))
        return root
    }

    @Test fun theRawLibraryArchiveYieldsNoPayload() {
        // Control: without the bridge, the storage-vocabulary events break
        // the insights parser — the exact failure the story surface hit.
        val payload = runCatching { RecipeProvenance.fromArchive(realArchive(), "c1", "d1") }.getOrNull()
        assertNull(payload)
    }

    @Test fun theBridgedArchiveCarriesTheWholeStory() {
        val payload = RecipeProvenance.fromArchive(provenanceReadyArchive(realArchive()), "c1", "d1")
        assertNotNull("provenance payload from the bridged archive", payload)
        payload!!
        assertEquals("October 12, 2026", payload.cookedDateText)
        assertEquals("Austin XL", payload.pitName)
        assertEquals(250.0, payload.setpointF!!, 0.001)
        assertEquals("Hickory", payload.wood)
        assertEquals("9.4 lb", payload.weightText)
        assertEquals(655.0, payload.totalMinutes!!, 0.001)
        assertEquals(75.0, payload.restMinutes!!, 0.001)
        assertEquals(160.0, payload.wrappedAtF!!, 0.001)
        assertTrue("curve rides along", payload.curve.size >= 2)
        assertEquals(listOf(ProvenancePhotoRole.ON_PIT, ProvenancePhotoRole.AT_WRAP), payload.photos.map { it.role })
        assertEquals(listOf("Paper wrap, no spritz"), payload.keepChips)
        assertEquals(listOf("Start an hour earlier"), payload.changeChips)
    }
}
