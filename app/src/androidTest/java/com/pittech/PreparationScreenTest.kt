package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PreparationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun equipmentAndChecklistFitExistingNavigation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        val cookId = runBlocking {
            app.database.clearAllTables()
            app.cookRepository.startCook(NewCookDraft("Prep sample", dishes = listOf(DishDraft("Ribs", "Pork", preparationItems = listOf(IngredientDraft("House rub", amountText = "2", amountUnit = "tbsp"))))))
        }
        compose.onNodeWithTag("nav-devices").performClick()
        compose.onNodeWithTag("equipment-open").performClick()
        compose.onNodeWithTag("equipment-add").performScrollTo().performClick()
        compose.onNodeWithTag("equipment-name").performTextInput("Backyard")
        compose.onNodeWithTag("equipment-save").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Backyard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("nav-cooks").performClick()
        compose.onNodeWithText("Prep sample").performClick()
        compose.onNodeWithTag("live-more-tools-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("prep-checklist-open").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("House rub · 2.0 tbsp").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(15_000) {
            runBlocking { app.database.companionDao().get("checklist:$cookId")?.let { CookPreparation.decodeChecklist(it.payload).any { row -> row.done } } == true }
        }
        assertTrue(runBlocking { app.database.companionDao().all().any { it.kind == "equipment" } })
        val file = File(app.filesDir, "pittech-ui-test/prep-checklist.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
