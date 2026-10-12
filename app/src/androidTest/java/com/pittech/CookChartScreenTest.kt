package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class CookChartScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun chartAxesAndNoteSelectionOpenTheExactTimelineCard() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        val note = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val cook = app.cookRepository.startCook(NewCookDraft("Chart note cook", dishes = listOf(DishDraft("Brisket", "Beef"))))
            val draft = CookNoteDraft(cookId = cook, description = "Bark check on graph", title = "Graph note", temperatures = listOf(NoteTemperature(valueText = "165")))
            app.cookRepository.notes.save(draft).event
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Chart note cook").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Chart note cook").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cook-tab-charts", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cook-tab-charts", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithTag("chart-y-axis").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-x-axis").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-zoom-in").performScrollTo().performClick()
        compose.onNodeWithTag("chart-reset").performClick()
        compose.onNodeWithTag("chart-marker-${note.id}").performScrollTo().performClick()
        compose.onNodeWithText("Open timeline").performClick()
        compose.onNodeWithTag("timeline-entry-${note.id}").assertIsDisplayed()
    }
}
