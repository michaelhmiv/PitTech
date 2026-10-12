package com.pittech.ui

import com.pittech.data.*
import com.pittech.domain.CookNoteMetadata
import org.junit.Assert.*
import org.junit.Test

class CookFeedPresentationTest {
    private fun event(type: String = "note", source: String = "manual", food: String? = "butt",
        metadata: String? = null, title: String = "Observation") =
        TimelineEventEntity("entry", "cook", food, type, title, "Bark is set", 100, 100, "UTC", source,
            createdAtUtcMillis = 100, updatedAtUtcMillis = 100, noteMetadataJson = metadata)
    private val recording = CookRecordingEntity("cook", "grill", "binding", CookRecordingEntity.RECORDING, "°F", 0, 0)
    private fun probe(id: String, food: String = "steaks") = ProbeEntity(id, "cook", "grill", food, "Probe", "food_probe", "controller_cloud", 0)
    private fun sample(id: String, food: String, probe: String?, at: Long, value: Double = 186.0,
        quality: String = "valid", source: String = "controller_cloud") =
        SensorReadingEntity(id, "cook", food, probe, "Probe", "food_probe", value, "°F", at, "UTC", source,
            qualityStatus = quality, recordedAtUtcMillis = at)

    @Test fun combinedNotesAppearInEveryRelevantFilterAndRetainMultiFoodScope() {
        val note = event(metadata = CookNoteMetadata("action", "wrap", listOf("butt", "steaks")).encode(), food = null)
        for (filter in listOf(TimelineFilter.STORY, TimelineFilter.NOTES, TimelineFilter.EVENTS, TimelineFilter.PHOTOS, TimelineFilter.TEMPERATURES))
            assertTrue(filter.name, CookFeedPresentation.matches(note, filter, hasPhotos = true, hasReadings = true))
        assertTrue(CookFeedPresentation.matchesFood(note, "steaks"))
        assertFalse(CookFeedPresentation.matchesFood(note, "potatoes"))
        assertTrue(CookFeedPresentation.matchesFood(event(type = "setpoint_changed", source = "controller_cloud", food = null), "potatoes"))
    }

    @Test fun storyKeepsSetpointsButRoutineDeviceMessagesRemainAvailableInEverything() {
        val gap = event(type = "connection_gap", source = "controller_cloud", food = null)
        assertFalse(CookFeedPresentation.matches(gap, TimelineFilter.STORY, false, false))
        assertTrue(CookFeedPresentation.matches(gap, TimelineFilter.GRILL, false, false))
        assertTrue(CookFeedPresentation.matches(gap, TimelineFilter.ALL, false, false))
        assertTrue(CookFeedPresentation.matches(event(type = "setpoint_changed", source = "controller_cloud"), TimelineFilter.STORY, false, false))
        assertEquals("Spritzed lightly", CookFeedPresentation.title(event(type = "spritz", title = "Spritzed lightly")))
    }

    @Test fun movedProbeWaitsForANewFoodReadingAndEarlierFoodHistoryRemainsSeparate() {
        val probe = probe("p2", "potatoes")
        val assignment = ProbeAssignmentEntity("move", "cook", "p2", "potatoes", 200)
        val old = sample("steak-reading", "steaks", "p2", 150)
        val waiting = FoodLivePresentation.temperatures("potatoes", listOf(probe), listOf(assignment), listOf(old), recording, 300).single()
        assertTrue(waiting.waiting); assertNull(waiting.reading); assertFalse(waiting.live)
        assertEquals(old, FoodLivePresentation.temperatures("steaks", listOf(probe), listOf(assignment), listOf(old), recording, 300).single().reading)
        val fresh = sample("potato-reading", "potatoes", "p2", 250, 140.0)
        assertEquals(fresh, FoodLivePresentation.temperatures("potatoes", listOf(probe), listOf(assignment), listOf(old, fresh), recording, 300).single().reading)
    }

    @Test fun disconnectAndStoppedRecordingNeverPresentAnOldValidReadingAsCurrent() {
        val old = sample("valid", "steaks", "p2", 100)
        val unavailable = sample("unplugged", "steaks", "p2", 200, 0.0, "unavailable")
        val result = FoodLivePresentation.temperatures("steaks", listOf(probe("p2")), emptyList(), listOf(old, unavailable), recording, 300).single()
        assertFalse(result.live); assertEquals(old, result.reading)
        assertEquals("Unavailable", timelineTemperatureValue(unavailable))
        assertEquals("186.0 °F", timelineTemperatureValue(old))
        assertFalse(FoodLivePresentation.temperatures("steaks", listOf(probe("p2")), emptyList(), listOf(old), recording.copy(status = CookRecordingEntity.STOPPED), 300).single().live)
    }

    @Test fun multipleProbesWithTheSameDisplayNameDoNotMergeTheirValues() {
        val probes = listOf(probe("p1"), probe("p2"))
        val readings = listOf(sample("one", "steaks", "p1", 100, 170.0), sample("two", "steaks", "p2", 100, 186.0))
        val values = FoodLivePresentation.temperatures("steaks", probes, emptyList(), readings, recording, 300)
        assertEquals(listOf(170.0, 186.0), values.map { it.reading!!.value })
        assertEquals(2, values.map { it.probe!!.id }.distinct().size)
    }

    @Test fun manualChecksAreExplicitLastReadingsEvenWithoutAProbe() {
        val check = sample("manual", "potatoes", null, 200, 180.0, source = "manual")
        val value = FoodLivePresentation.temperatures("potatoes", emptyList(), emptyList(), listOf(check), recording, 300).single()
        assertEquals(check, value.reading); assertFalse(value.live); assertFalse(value.waiting)
    }

    @Test fun aNewerManualCheckRemainsVisibleWhenAnAssignedProbeIsUnavailable() {
        val old = sample("old", "steaks", "p2", 100)
        val unplugged = sample("unavailable", "steaks", "p2", 150, 0.0, "unavailable")
        val check = sample("check", "steaks", null, 200, 190.0, source = "manual")
        val values = FoodLivePresentation.temperatures("steaks", listOf(probe("p2")), emptyList(), listOf(old, unplugged, check), recording, 300)
        assertEquals(check, values.first().reading)
        assertFalse(values.any { it.live })
    }

    @Test fun filteredRowsKeepCombinedNotesAndStandalonePhotosWithoutCreatingEvents() {
        val cook = CookEntity("cook", "Mixed grill", CookStatus.ACTIVE, 0, "UTC", createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
        val food = DishEntity("potatoes", "cook", "Potatoes", "Vegetables", cut = null, createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
        val note = event(food = null, metadata = CookNoteMetadata("action", "wrap", listOf("steaks", "potatoes")).encode())
        val linkedPhoto = PhotoEntity("linked", "cook", eventId = note.id, originalFileName = "tray.jpg", relativePath = "photos/tray.jpg", mimeType = "image/jpeg", addedAtUtcMillis = 100)
        val standalone = linkedPhoto.copy(id = "setup", dishId = food.id, eventId = null)
        val reading = sample("manual", "steaks", null, 100, source = "manual")
        val automatic = sample("sensor", "potatoes", "p2", 200)
        val data = CookDetailData(cook, listOf(food), emptyList(), listOf(note), listOf(reading, automatic), emptyList(), emptyList(), listOf(linkedPhoto, standalone), emptyList(),
            logReadingLinks = listOf(LogReadingLink(reading.id, note.id)))
        for (filter in listOf(TimelineFilter.NOTES, TimelineFilter.EVENTS, TimelineFilter.PHOTOS, TimelineFilter.TEMPERATURES)) {
            val rows = buildTimelineLines(data, filter, true, food.id)
            assertEquals(1, rows.filterIsInstance<TimelineLine.Event>().size)
            assertEquals(note, rows.filterIsInstance<TimelineLine.Event>().single().event)
        }
        val story = buildTimelineLines(data, TimelineFilter.STORY, true, food.id)
        assertEquals(listOf(note.id), story.filterIsInstance<TimelineLine.Event>().map { it.event.id })
        assertEquals(standalone, story.filterIsInstance<TimelineLine.Photo>().single().photo)
        assertTrue(story.none { it is TimelineLine.Reading || it is TimelineLine.ReadingGroup })
        assertEquals(1, data.events.size)
        assertNull(standalone.eventId)
    }

    @Test fun ambiguousLegacyWholeCookActionsDoNotInventAPhaseForEveryFood() {
        val cook = CookEntity("cook", "Legacy mixed grill", CookStatus.ACTIVE, 0, "UTC", createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
        val foods = listOf("butt", "steaks", "potatoes").map { DishEntity(it, cook.id, it, "Other", null, createdAtUtcMillis = 0, updatedAtUtcMillis = 0) }
        val wrap = event(type = "wrap", food = null)
        val removed = event(type = "remove", food = "butt").copy(id = "removed", occurredAtUtcMillis = 200)
        val data = CookDetailData(cook, foods, emptyList(), listOf(wrap, removed), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val phases = FoodLivePresentation.phases(data, 300).associate { it.dishId to it.phase }
        assertEquals("removed", phases["butt"])
        assertEquals("prep", phases["steaks"])
        assertEquals("prep", phases["potatoes"])
        assertEquals(listOf(wrap, removed), data.events)
    }
    @Test fun phaseTimerTracksTheCurrentHoldAfterFoodReturnsToTheGrill() {
        val cook = CookEntity("cook", "Mixed phases", CookStatus.ACTIVE, 0, "UTC", createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
        val food = DishEntity("butt", cook.id, "Pork butt", "Pork", null, createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
        val actions = listOf("food_on", "remove", "rest_start", "hold_start", "food_on", "remove", "rest_start", "hold_start")
        val events = actions.mapIndexed { index, action -> event(type = action).copy(id = "action-" + index, occurredAtUtcMillis = (index + 1) * 10L) }
        val data = CookDetailData(cook, listOf(food), emptyList(), events, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            lifecycle = CookLifecycle(cook.id, "explicit_phases"))
        val phase = FoodLivePresentation.phases(data, 100).single()
        assertEquals("holding", phase.phase)
        assertEquals(30L, phase.holdMillis)
        assertEquals(20L, FoodLivePresentation.phaseElapsed(phase))
    }

}
