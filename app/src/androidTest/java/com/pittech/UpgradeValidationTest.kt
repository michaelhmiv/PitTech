package com.pittech

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run after installing this APK over the previous release without clearing app data. */
@RunWith(AndroidJUnit4::class)
class UpgradeValidationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun previousCookSurvivesUpgradeAndRelaunch() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication

        fun assertCookVisible() {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("Saturday brisket").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Saturday brisket").assertIsDisplayed()
        }

        assertCookVisible()
        val saved = runBlocking(Dispatchers.IO) {
            application.database.cookDao().observeCooks().first().single()
        }
        assertEquals(CookStatus.ACTIVE, saved.cook.status)
        assertEquals("Cool morning; used hickory.", saved.cook.notes)
        assertEquals("Whole packer", saved.dishes.single().cut)
        assertNull(saved.cook.fuelType)
        assertNull(saved.dishes.single().gradeOrSource)
        assertEquals(1, runBlocking(Dispatchers.IO) {
            application.database.cookDao().getIngredientsForCook(saved.cook.id).size
        })

        composeRule.onNodeWithText("Saturday brisket").performClick()
        composeRule.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Cook history").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to cooks").performClick()

        composeRule.activityRule.scenario.recreate()
        assertCookVisible()
    }
}
