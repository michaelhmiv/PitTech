package com.pittech.ui

import com.pittech.data.*
import com.pittech.domain.CookNoteMetadata
import com.pittech.domain.PlaybookCodec
import org.junit.Assert.*
import org.junit.Test

/**
 * Owner directive (2026-11-08, issue #293): "test every single event and make
 * sure that those all show up on the timeline."
 *
 * The matrix below is built from an exhaustive harvest of every stored
 * TimelineEventEntity shape in the codebase: every construction site
 * (CookRepository, CookNoteRepository, CookCompanionRepository,
 * CookRecordingRepository, TelemetryRecordingRepository), the PlaybookCodec
 * action <-> eventType mappings, the CookNoteMetadata action vocabulary, the
 * iOS writers that sync into the same library ("Meal paused"/"Meal resumed"),
 * legacy/raw shapes that survive in old libraries, imports and sync
 * (raw action eventTypes, "photo", "temperature", "reminder_completed"), and
 * the MCP API writer (api-service create_note stamps source "mcp").
 *
 * Each case asserts, per filter, both layers of the presentation contract:
 * CookFeedPresentation.matches (filter membership) and buildTimelineLines
 * (the rows the timeline actually renders), plus the rendered title.
 *
 * Two stored families are intentionally NOT event rows, by construction in
 * buildTimelineLines: telemetry_* events (they surface in the Devices /
 * telemetry status surfaces, which query them directly) and the legacy
 * "temperature" event type (temperature content surfaces as reading rows).
 * Their cases assert the exclusion explicitly so it is a named decision,
 * not a silent omission.
 */
class EventTimelineVisibilityTest {
    private fun event(id: String, type: String, source: String, title: String, details: String? = null,
        dishId: String? = "butt", metadata: String? = null) =
        TimelineEventEntity(id, "cook", dishId, type, title, details, 1_000, 1_000, "UTC", source,
            createdAtUtcMillis = 1_000, updatedAtUtcMillis = 1_000, noteMetadataJson = metadata)

    private val cook = CookEntity("cook", "Sweep cook", CookStatus.ACTIVE, 0, "UTC", createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
    private val dishes = listOf(
        DishEntity("butt", "cook", "Pork butt", "Pork", null, createdAtUtcMillis = 0, updatedAtUtcMillis = 0),
        DishEntity("steaks", "cook", "Steaks", "Beef", null, createdAtUtcMillis = 0, updatedAtUtcMillis = 0))

    private fun data(events: List<TimelineEventEntity>, photos: List<PhotoEntity> = emptyList(),
        readings: List<SensorReadingEntity> = emptyList(), links: List<LogReadingLink> = emptyList()) =
        CookDetailData(cook, dishes, emptyList(), events, readings, emptyList(), emptyList(), photos, emptyList(),
            logReadingLinks = links)

    private fun eventRowIds(data: CookDetailData, filter: TimelineFilter, foodId: String? = null) =
        buildTimelineLines(data, filter, newestFirst = true, foodId = foodId)
            .filterIsInstance<TimelineLine.Event>().map { it.event.id }

    private data class Case(
        val name: String, val event: TimelineEventEntity,
        val story: Boolean, val notes: Boolean, val actions: Boolean, val grill: Boolean,
        val title: String,
        /** buildTimelineLines never turns this stored shape into an Event row. */
        val builderExcluded: Boolean = false,
    )

    private val preparationLabels = mapOf(
        "preheat_start" to "Preheat", "season" to "Season", "trim" to "Trim",
        "marinate" to "Marinate", "add_fuel" to "Add fuel")

    private fun cases(): List<Case> {
        val cases = mutableListOf<Case>()
        fun add(case: Case) { cases += case }

        // Plain notes, as the composer stores them.
        add(Case("general note with details",
            event("n1", "note", "manual", "Cook note", "Bark is set", metadata = CookNoteMetadata("general").encode()),
            story = true, notes = true, actions = false, grill = false, title = "Bark is set"))
        add(Case("general note with its own title",
            event("n2", "note", "manual", "Quick thought", null, metadata = CookNoteMetadata("general").encode()),
            story = true, notes = true, actions = false, grill = false, title = "Quick thought"))
        add(Case("legacy note without metadata",
            event("n3", "note", "manual", "Cook note", "Wrapped in paper"),
            story = true, notes = true, actions = false, grill = false, title = "Wrapped in paper"))
        add(Case("temperature-category note",
            event("n4", "note", "manual", "Temperature note", null, metadata = CookNoteMetadata("temperature").encode()),
            story = true, notes = true, actions = false, grill = false, title = "Temperature note"))
        add(Case("photo-category note",
            event("n5", "note", "manual", "Photo added", null, metadata = CookNoteMetadata("photo").encode()),
            story = true, notes = true, actions = false, grill = false, title = "Photo added"))
        add(Case("raw photo event (legacy shape)",
            event("n6", "photo", "manual", "Photo added"),
            story = true, notes = true, actions = false, grill = false, title = "Photo added"))

        // Action notes, shape A: eventType "note" + CookNoteMetadata action —
        // the shape every current Android writer produces.
        for ((action, label) in PlaybookCodec.actions) {
            add(Case("action note (metadata): $action",
                event("a-$action", "note", "manual", label, metadata = CookNoteMetadata("action", action).encode()),
                story = true, notes = true, actions = true, grill = false, title = label))
        }
        // Preparation actions are stored with the generic "Cook note" title:
        // PlaybookCodec has no label for them, so the presentation layer must
        // supply the label from the metadata or the entry is indistinguishable
        // from a plain note.
        for ((action, label) in preparationLabels) {
            add(Case("preparation note (metadata): $action",
                event("p-$action", "note", "manual", "Cook note", metadata = CookNoteMetadata("action", action).encode()),
                story = true, notes = true, actions = true, grill = false, title = label))
        }
        add(Case("action note with the cook's own title",
            event("a-custom", "note", "manual", "My wrap note", "Used paper", metadata = CookNoteMetadata("action", "wrap").encode()),
            story = true, notes = true, actions = true, grill = false, title = "My wrap note"))
        add(Case("reminder check-in note",
            event("a-checkin", "note", "manual", "Check-in: Spritz the ribs", "Reminder check-in completed.", dishId = null,
                metadata = CookNoteMetadata("action", "check_in").encode()),
            story = true, notes = true, actions = true, grill = false, title = "Check-in: Spritz the ribs"))

        // Action events, shape B: the action carried by the raw eventType —
        // legacy libraries, imports, and other writers (the note loader still
        // decodes actions from this shape).
        for ((action, label) in PlaybookCodec.actions) {
            add(Case("action event (raw type): $action",
                event("r-$action", PlaybookCodec.eventType(action), "manual", label),
                story = true, notes = true, actions = true, grill = false, title = label))
        }
        add(Case("action event (raw canonical type): food_on",
            event("r-food_on", "food_on", "manual", "Food on"),
            story = true, notes = true, actions = true, grill = false, title = "Food on"))
        add(Case("action event (raw canonical type): rest_start",
            event("r-rest_start", "rest_start", "manual", "Start rest"),
            story = true, notes = true, actions = true, grill = false, title = "Start rest"))
        add(Case("action event (raw type, blank title): rest",
            event("r-rest-blank", "rest", "manual", ""),
            story = true, notes = true, actions = true, grill = false, title = "Start rest"))
        for ((action, label) in preparationLabels) {
            add(Case("preparation event (raw type): $action",
                event("rp-$action", action, "manual", label),
                story = true, notes = true, actions = true, grill = false, title = label))
        }

        // Cook lifecycle events (all source "manual", including the iOS titles).
        add(Case("cook started",
            event("l-started", "cook_started", "manual", "Preparation started", dishId = null),
            story = true, notes = false, actions = true, grill = false, title = "Preparation started"))
        add(Case("cook paused",
            event("l-paused", "cook_paused", "manual", "Cook paused", dishId = null),
            story = true, notes = false, actions = true, grill = false, title = "Cook paused"))
        add(Case("cook paused (iOS title)",
            event("l-paused-ios", "cook_paused", "manual", "Meal paused", dishId = null),
            story = true, notes = false, actions = true, grill = false, title = "Meal paused"))
        add(Case("cook resumed",
            event("l-resumed", "cook_resumed", "manual", "Cook resumed", dishId = null),
            story = true, notes = false, actions = true, grill = false, title = "Cook resumed"))
        add(Case("cook finished",
            event("l-finished", "cook_finished", "manual", "Cook finished", dishId = null),
            story = true, notes = false, actions = true, grill = false, title = "Cook finished"))
        add(Case("cook reopened",
            event("l-reopened", "cook_reopened", "manual", "Cook reopened", dishId = null),
            story = true, notes = false, actions = true, grill = false, title = "Cook reopened"))
        add(Case("starting setpoint recorded",
            event("l-setpoint", "setpoint_recorded", "manual", "Starting setpoint recorded", "225 °F", dishId = null),
            story = true, notes = true, actions = false, grill = true, title = "Starting setpoint recorded"))
        add(Case("legacy reminder_completed event",
            event("l-reminder", "reminder_completed", "manual", "Reminder completed", dishId = null),
            story = true, notes = false, actions = true, grill = false, title = "Reminder completed"))

        // Written through the user's own MCP connection (source "mcp").
        add(Case("MCP note",
            event("m1", "note", "mcp", "Note from the connection", "Added while testing", dishId = null),
            story = true, notes = true, actions = false, grill = false, title = "Note from the connection"))
        add(Case("MCP action event (raw type)",
            event("m2", "wrap", "mcp", "Wrap"),
            story = true, notes = true, actions = true, grill = false, title = "Wrap"))

        // Unknown types from imports / other writers must still surface.
        add(Case("unknown type, manual source",
            event("u1", "custom_marker", "manual", "Imported marker", dishId = null),
            story = true, notes = false, actions = false, grill = false, title = "Imported marker"))

        // Grill / recording system events (source "controller_cloud").
        add(Case("setpoint changed",
            event("s1", "setpoint_changed", "controller_cloud", "Grill setpoint changed to 250 °F",
                "Observed in two consecutive cloud readings. PitTech did not change the grill.", dishId = null),
            story = true, notes = false, actions = false, grill = true, title = "Grill set to 250 °F"))
        add(Case("probe assigned",
            event("s2", "probe_assigned", "controller_cloud", "Probe 1 → Pork butt"),
            story = true, notes = false, actions = true, grill = true, title = "Probe 1 → Pork butt"))
        add(Case("connection gap",
            event("s3", "connection_gap", "controller_cloud", "Temperature recording interrupted", "Grill unreachable", dishId = null),
            story = false, notes = false, actions = false, grill = true, title = "Temperature recording interrupted"))
        add(Case("connection restored",
            event("s4", "connection_restored", "controller_cloud", "Temperature recording resumed",
                "No readings were recorded for 60 seconds. Missing periods stay blank.", dishId = null),
            story = false, notes = false, actions = false, grill = true, title = "Temperature recording resumed"))
        add(Case("recording started",
            event("s5", "recording_started", "controller_cloud", "Grill attached · temperature recording started", dishId = null),
            story = false, notes = false, actions = false, grill = true, title = "Grill attached · temperature recording started"))
        add(Case("recording resumed",
            event("s6", "recording_resumed", "controller_cloud", "Temperature recording resumed", dishId = null),
            story = false, notes = false, actions = false, grill = true, title = "Temperature recording resumed"))
        add(Case("recording paused",
            event("s7", "recording_paused", "controller_cloud", "Cook paused · temperature recording paused.", dishId = null),
            story = false, notes = false, actions = false, grill = true, title = "Cook paused · temperature recording paused."))
        add(Case("recording stopped",
            event("s8", "recording_stopped", "controller_cloud", "Temperature recording stopped", dishId = null),
            story = false, notes = false, actions = false, grill = true, title = "Temperature recording stopped"))
        add(Case("recording schedule changed",
            event("s9", "recording_schedule_changed", "controller_cloud", "Temperature collection: On log", dishId = null),
            story = false, notes = false, actions = false, grill = true, title = "Temperature collection: On log"))

        // Telemetry internals: excluded from the feed by construction; they
        // surface in the Devices / telemetry status surfaces instead.
        add(Case("telemetry lifecycle",
            event("t1", "telemetry_lifecycle", "telemetry:home_assistant", "Temperature monitoring", "active", dishId = null),
            story = false, notes = false, actions = false, grill = false, title = "Temperature monitoring", builderExcluded = true))
        add(Case("telemetry mapping",
            event("t2", "telemetry_mapping", "telemetry:home_assistant", "Probe 1 assignment", "FOOD", dishId = null),
            story = false, notes = false, actions = false, grill = false, title = "Probe 1 assignment", builderExcluded = true))
        add(Case("telemetry recovery",
            event("t3", "telemetry_recovery", "telemetry:home_assistant", "Temperature history synchronization", "{}", dishId = null),
            story = false, notes = false, actions = false, grill = false, title = "Temperature history synchronization", builderExcluded = true))
        // Legacy temperature event type: temperature content surfaces as
        // reading rows, never as an event row (buildTimelineLines excludes
        // the type). Membership still counts it as story content at the
        // filter layer; the row builder is the layer that excludes it.
        add(Case("legacy temperature event",
            event("t4", "temperature", "manual", "Temperature note"),
            story = true, notes = false, actions = false, grill = false, title = "Temperature note", builderExcluded = true))

        return cases
    }

    @Test fun everyEventShapeMatchesItsFiltersAndRendersWithAMeaningfulTitle() {
        for (case in cases()) {
            val e = case.event
            assertEquals(case.name + " · STORY membership", case.story, CookFeedPresentation.matches(e, TimelineFilter.STORY, false, false))
            assertEquals(case.name + " · NOTES membership", case.notes, CookFeedPresentation.matches(e, TimelineFilter.NOTES, false, false))
            assertEquals(case.name + " · Actions membership", case.actions, CookFeedPresentation.matches(e, TimelineFilter.EVENTS, false, false))
            assertEquals(case.name + " · Grill membership", case.grill, CookFeedPresentation.matches(e, TimelineFilter.GRILL, false, false))
            assertTrue(case.name + " · Everything membership", CookFeedPresentation.matches(e, TimelineFilter.ALL, false, false))
            val title = CookFeedPresentation.title(e)
            assertEquals(case.name + " · title", case.title, title)
            assertTrue(case.name + " · title is not the raw type", title.isNotBlank() && title != e.eventType && !title.contains('_'))
            val d = data(listOf(e))
            val membership = mapOf(TimelineFilter.STORY to case.story, TimelineFilter.NOTES to case.notes,
                TimelineFilter.EVENTS to case.actions, TimelineFilter.GRILL to case.grill, TimelineFilter.ALL to true)
            for ((filter, member) in membership) {
                assertEquals(case.name + " · rendered under " + filter, member && !case.builderExcluded,
                    eventRowIds(d, filter).contains(e.id))
            }
        }
    }

    @Test fun everyUserInitiatedShapeAppearsInTheDefaultStoryTimeline() {
        // The owner's directive in one assertion set: anything the user
        // logged — in the app or through their own MCP connection — is in
        // the default Cook story feed. (The legacy "temperature" event type
        // is the one documented exception: it renders as reading rows.)
        for (case in cases().filter { it.event.source in setOf("manual", "mcp") && !it.builderExcluded }) {
            assertTrue(case.name, eventRowIds(data(listOf(case.event)), TimelineFilter.STORY).contains(case.event.id))
        }
    }

    @Test fun photoEntriesAppearUnderPhotos() {
        val rawPhoto = event("ph1", "photo", "manual", "Photo added")
        assertTrue(CookFeedPresentation.matches(rawPhoto, TimelineFilter.PHOTOS, hasPhotos = false, hasReadings = false))
        val note = event("ph2", "note", "manual", "Photo added", metadata = CookNoteMetadata("photo").encode())
        assertFalse(CookFeedPresentation.matches(note, TimelineFilter.PHOTOS, hasPhotos = false, hasReadings = false))
        val photo = PhotoEntity("ph2-photo", "cook", eventId = note.id, originalFileName = "tray.jpg",
            relativePath = "photos/tray.jpg", mimeType = "image/jpeg", addedAtUtcMillis = 1_000)
        val d = data(listOf(note), photos = listOf(photo))
        assertEquals(listOf(note.id), eventRowIds(d, TimelineFilter.PHOTOS))
        // The attached photo rides its entry in the story; it is not ALSO a standalone photo row.
        val story = buildTimelineLines(d, TimelineFilter.STORY, newestFirst = true)
        assertEquals(listOf(note.id), story.filterIsInstance<TimelineLine.Event>().map { it.event.id })
        assertTrue(story.none { it is TimelineLine.Photo })
    }

    @Test fun temperatureNotesAppearUnderTempsOnlyWithALinkedReading() {
        val note = event("tn1", "note", "manual", "Temperature note", metadata = CookNoteMetadata("temperature").encode())
        assertFalse(CookFeedPresentation.matches(note, TimelineFilter.TEMPERATURES, hasPhotos = false, hasReadings = false))
        assertTrue(CookFeedPresentation.matches(note, TimelineFilter.TEMPERATURES, hasPhotos = false, hasReadings = true))
        assertTrue(eventRowIds(data(listOf(note)), TimelineFilter.TEMPERATURES).isEmpty())
        val reading = SensorReadingEntity("tn1-reading", "cook", "butt", null, "Food", "food", 190.0, "°F", 900, "UTC", "manual",
            qualityStatus = "valid", recordedAtUtcMillis = 900)
        val linked = data(listOf(note), readings = listOf(reading), links = listOf(LogReadingLink(reading.id, note.id)))
        assertEquals(listOf(note.id), eventRowIds(linked, TimelineFilter.TEMPERATURES))
        assertEquals(listOf(note.id), eventRowIds(linked, TimelineFilter.STORY))
    }

    @Test fun dishScopedTimelineShowsCookLevelAndOwnDishEntriesOnly() {
        val cookLevel = event("sc1", "note", "manual", "Wrap", dishId = null, metadata = CookNoteMetadata("action", "wrap").encode())
        val ownDish = event("sc2", "note", "manual", "Wrap", dishId = "butt", metadata = CookNoteMetadata("action", "wrap").encode())
        val otherDish = event("sc3", "note", "manual", "Wrap", dishId = "steaks", metadata = CookNoteMetadata("action", "wrap").encode())
        val d = data(listOf(cookLevel, ownDish, otherDish))
        assertEquals(setOf("sc1", "sc2"), eventRowIds(d, TimelineFilter.STORY, foodId = "butt").toSet())
        assertEquals(setOf("sc1", "sc2", "sc3"), eventRowIds(d, TimelineFilter.STORY).toSet())
    }
}
