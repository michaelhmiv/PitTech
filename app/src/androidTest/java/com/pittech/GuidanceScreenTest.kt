package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GuidanceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nextActionConfirmsAndFullPlanIsAccessible() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        runBlocking {
            app.database.clearAllTables()
            val book = CookPlaybook("Guided brisket", NewCookDraft("Guided brisket", dishes = listOf(DishDraft("Brisket", "Beef"))), listOf(PlaybookStep(id = "on", dishIndex = 0, action = "food_on", title = "Put brisket on"), PlaybookStep(id = "check", dishIndex = 0, title = "Check bark", trigger = "elapsed", minutes = 60)))
            val id = app.companionRepository.save(book)
            app.cookRepository.startCook(book.draft.copy(playbookId = id))
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Guided brisket").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Guided brisket").performClick()
        compose.onNodeWithTag("next-action-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("guidance-done").performClick()
        compose.onNodeWithTag("view-plan").performScrollTo().performClick()
        compose.onNodeWithText("Pause guidance").assertIsDisplayed()
        compose.onNodeWithTag("guidance-step-check").assertIsDisplayed()
        val file = File(app.filesDir, "pittech-ui-test/guidance-plan.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
