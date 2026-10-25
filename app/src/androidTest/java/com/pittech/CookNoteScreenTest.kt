package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookNoteScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /**
     * PL-02 (issue #172): the legacy check-in offers Log it / Snooze 10 min /
     * Dismiss explicitly — and closing it with Back changes nothing. The
     * hidden 1-hour snooze is gone.
     */
    @Test fun legacyCheckInBackChangesNothingAndSnoozeIsTenMinutes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        val seeded = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val id = app.cookRepository.startCook(NewCookDraft("Check-in semantics cook", dishes = listOf(DishDraft("Brisket", "beef"))))
            val reminder = app.cookRepository.createCookReminder(id, "Check the brisket", System.currentTimeMillis() + 300_000)
            id to reminder
        }
        val (cookId, reminder) = seeded
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Check-in semantics cook").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check-in semantics cook").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cook-tab-live", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cook-tab-live", useUnmergedTree = true).performClick()
        fun openCheckIn() {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Log now").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Log now").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("reminder-checkin-note").fetchSemanticsNodes().isNotEmpty() }
        }
        openCheckIn()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reminder-checkin-note").fetchSemanticsNodes().isEmpty() }
        val afterBack = runBlocking(Dispatchers.IO) { app.database.cookDao().getReminder(reminder.id)!! }
        assertEquals(com.pittech.data.CookReminderEntity.STATUS_PENDING, afterBack.status)
        assertEquals(reminder.dueAtUtcMillis, afterBack.dueAtUtcMillis)
        openCheckIn()
        compose.onNodeWithTag("reminder-checkin-snooze").performClick()
        compose.waitUntil(15_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getReminder(reminder.id)?.dueAtUtcMillis != reminder.dueAtUtcMillis } }
        val afterSnooze = runBlocking(Dispatchers.IO) { app.database.cookDao().getReminder(reminder.id)!! }
        assertEquals(com.pittech.data.CookReminderEntity.STATUS_PENDING, afterSnooze.status)
        assertTrue(afterSnooze.dueAtUtcMillis > reminder.dueAtUtcMillis + 240_000)
        assertTrue(afterSnooze.dueAtUtcMillis <= System.currentTimeMillis() + 660_000)
        openCheckIn()
        compose.onNodeWithTag("reminder-checkin-dismiss").performClick()
        compose.waitUntil(15_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getReminder(reminder.id)?.status == com.pittech.data.CookReminderEntity.STATUS_COMPLETED } }
        assertEquals(0, runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEventsForCook(cookId).count { PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "check_in" } })
    }
    /**
     * PL-07 (issue #172): on a photo-first entry, cancelling the photo
     * source returns to the composer (it used to eject to Cook Detail);
     * text typed in the composer then survives a dismiss, because dismiss
     * keeps the durable draft, and Resume brings the same text back.
     */
    @Test fun photoFirstCancelReturnsToComposerAndTextSurvivesDismissAndResume() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        val id = runBlocking(Dispatchers.IO) { app.database.clearAllTables(); app.cookRepository.startCook(NewCookDraft("Photo cancel cook", dishes = listOf(DishDraft("Brisket", "beef")))) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Photo cancel cook").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Photo cancel cook").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("timeline-add-photo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timeline-add-photo", useUnmergedTree = true).performClick()
        // Photo-first: the source chooser opens on top of the composer.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("photo-source-cancel").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("photo-source-cancel").performClick()
        // The composer is still open — the cancel did not eject us.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("timeline-entry-details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timeline-entry-details").assertIsDisplayed().performTextInput("Bark check at dawn")
        closeFocusedSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("timeline-entry-details").fetchSemanticsNodes().isEmpty() }
        val drafts = runBlocking(Dispatchers.IO) { app.database.experienceDao().observeDrafts(id).first() }
        assertEquals(1, drafts.size)
        assertEquals("Bark check at dawn", CookNoteDraft.decode(drafts.single().payload).description)
        // Resume brings the same composer back with the typed text intact.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cook-resume-note", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cook-resume-note", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("timeline-entry-details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timeline-entry-details").assertTextContains("Bark check at dawn")
        compose.onNodeWithText("Keep draft").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("timeline-entry-details").fetchSemanticsNodes().isEmpty() }
    }

    /**
     * PL-07 (issue #172): a standalone manual reading (the retired
     * Temperature Entry dialog's old job) now edits in the note composer —
     * probe and value fields inline, no "More details" — and saving adopts
     * the reading into the note under its own identity.
     */
    @Test fun standaloneManualReadingEditsInTheComposer() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        val seeded = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val cookId = app.cookRepository.startCook(NewCookDraft("Standalone reading cook", dishes = listOf(DishDraft("Brisket", "beef"))))
            val dish = app.database.cookDao().getDishesForCook(cookId).single()
            cookId to app.cookRepository.addManualTemperature(cookId, dish.id, "Hand thermometer", "food_probe", 150.0, "°F", System.currentTimeMillis())
        }
        val (cookId, reading) = seeded
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Standalone reading cook").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Standalone reading cook").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cook-tab-timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("temperature-actions-Hand thermometer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("temperature-actions-Hand thermometer").performScrollTo().performClick()
        compose.onNodeWithTag("temperature-edit-Hand thermometer").performClick()
        // The composer — not a temperature dialog — with the probe field
        // visible inline, before any "More details" tap.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Edit temperature").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("temperature-probe").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("temperature-value").performTextClearance()
        compose.onNodeWithTag("temperature-value").performTextInput("171")
        closeFocusedSoftKeyboard()
        compose.onNodeWithTag("temperature-save").performClick()
        compose.waitUntil(15_000) { runBlocking(Dispatchers.IO) { app.database.experienceDao().reading(reading.id)?.value == 171.0 } }
        val events = runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEventsForCook(cookId).filter { it.eventType == "note" } }
        assertEquals(1, events.size)
        assertEquals(listOf(reading.id), runBlocking(Dispatchers.IO) { app.database.experienceDao().readingsForLog(events.single().id).map { it.id } })
    }
    @Test fun temperatureShortcutSavesDescriptionAndReadingInOneEditableNote() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        val id = runBlocking(Dispatchers.IO) { app.database.clearAllTables(); app.cookRepository.startCook(NewCookDraft("Unified note cook", dishes = listOf(DishDraft("Brisket", "beef")))) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Unified note cook").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unified note cook").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("timeline-add-temperature", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timeline-add-temperature", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithTag("timeline-entry-details").performTextInput("Bark is set")
        compose.onNodeWithTag("temperature-value").performTextInput("165")
        closeFocusedSoftKeyboard()
        compose.onNodeWithTag("temperature-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEventsForCook(id).any { it.details == "Bark is set" } } }
        val original = runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEventsForCook(id).single { it.details == "Bark is set" } }
        assertEquals("note", original.eventType)
        assertEquals("temperature", CookNoteMetadata.decode(original.noteMetadataJson)!!.category)
        assertEquals(1, runBlocking(Dispatchers.IO) { app.database.experienceDao().readingsForLog(original.id).size })
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cook-tab-timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithTag("timeline-entry-${original.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("timeline-event-actions-note").performScrollTo().performClick()
        compose.onNodeWithTag("timeline-event-edit-note").performClick()
        compose.onNodeWithTag("timeline-entry-details").performTextClearance()
        compose.onNodeWithTag("timeline-entry-details").performTextInput("Wrapped after checking bark")
        closeFocusedSoftKeyboard()
        compose.onNodeWithTag("temperature-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEvent(original.id)?.details == "Wrapped after checking bark" } }
        val edited = runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEvent(original.id)!! }
        assertEquals(original.occurredAtUtcMillis, edited.occurredAtUtcMillis)
        assertEquals(original.createdAtUtcMillis, edited.createdAtUtcMillis)
        assertEquals(1, runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEventsForCook(id).count { it.source == "manual" && it.eventType == "note" } })
    }
}
