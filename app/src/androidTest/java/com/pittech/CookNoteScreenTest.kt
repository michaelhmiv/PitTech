package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookNoteScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
