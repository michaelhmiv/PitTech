package com.pittech

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PitTechUserFlowsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearLocalData() {
        CrashDiagnostics.clearPendingReport(targetContext)
        val application = targetContext.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) {
            application.database.clearAllTables()
        }
    }

    @Test
    fun test01_homeNavigationAndPrimaryActionAreClear() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
        composeRule.onNodeWithTag("start-cook").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        saveScreenshot("home-empty")

        composeRule.onNodeWithTag("nav-insights").performClick()
        composeRule.onNodeWithText("Compare temperatures, cook times, and results across your saved cooks.").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-devices").performClick()
        composeRule.onNodeWithText("Connect a grill controller or probe logger when you are ready.").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.onNodeWithText("Your cook records and photos are stored on this phone.").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-cooks").performClick()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
    }

    @Test
    fun test02_createCookWithDishAndPreparationAndSaveLocally() {
        composeRule.onNodeWithTag("start-cook").performClick()

        composeRule.onNodeWithTag("cook-title").performTextInput("Saturday brisket")
        composeRule.onNodeWithTag("cook-smoker").performTextInput("Pit Boss Austin XL")
        composeRule.onNodeWithTag("cook-setpoint").performTextInput("0")
        composeRule.onNodeWithTag("cook-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("cook-setpoint").performTextClearance()
        composeRule.onNodeWithTag("cook-setpoint").performTextInput("250")
        composeRule.onNodeWithTag("cook-save").assertIsEnabled()
        composeRule.onNodeWithTag("cook-notes").performTextInput("Cool morning; used hickory.")

        composeRule.onNodeWithTag("cook-add-dish").performClick()
        composeRule.onNodeWithTag("dish-name").performTextInput("Brisket")
        composeRule.onNodeWithTag("dish-food-type").performClick()
        composeRule.onNodeWithText("Beef").performClick()
        composeRule.onNodeWithTag("dish-cut").performTextInput("Whole packer")
        composeRule.onNodeWithTag("dish-weight").performTextInput("0")
        composeRule.onNodeWithTag("dish-save").performClick()
        composeRule.onNodeWithText("Use a positive number.").assertIsDisplayed()

        composeRule.onNodeWithTag("dish-weight").performTextClearance()
        composeRule.onNodeWithTag("dish-weight").performTextInput("12.5")
        composeRule.onNodeWithTag("dish-details-toggle").performClick()
        composeRule.onNodeWithTag("dish-starting-condition").performScrollTo().performClick()
        composeRule.onNodeWithText("Refrigerated").performClick()
        composeRule.onNodeWithTag("dish-boneless").performScrollTo().performClick()
        composeRule.onNodeWithTag("preparation-name-0").performScrollTo().performTextInput("Salt and pepper rub")
        composeRule.onNodeWithTag("preparation-amount-0").performScrollTo().performTextInput("3")
        composeRule.onNodeWithTag("preparation-notes").performScrollTo().performTextInput("Light, even coating.")
        composeRule.onNodeWithTag("dish-save").performClick()

        composeRule.onNodeWithTag("cook-save").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Saturday brisket").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Whole packer").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Whole packer").performScrollTo().assertIsDisplayed()
        saveScreenshot("cook-saved")

        val application = targetContext.applicationContext as PitTechApplication
        val savedCook = runBlocking(Dispatchers.IO) {
            application.database.cookDao().observeCooks().first().single()
        }
        assertEquals("Saturday brisket", savedCook.cook.title)
        assertEquals(CookStatus.ACTIVE, savedCook.cook.status)
        assertEquals("Pit Boss Austin XL", savedCook.cook.smokerName)
        assertEquals(250.0, savedCook.cook.initialSetpointValue!!, 0.0)
        assertEquals("Cool morning; used hickory.", savedCook.cook.notes)

        val dish = savedCook.dishes.single()
        assertEquals("Brisket", dish.name)
        assertEquals("Beef", dish.foodType)
        assertEquals("Whole packer", dish.cut)
        assertEquals(12.5, dish.weightValue!!, 0.0)
        assertEquals("lb", dish.weightUnit)
        assertEquals("Refrigerated", dish.startingCondition)
        assertEquals(false, dish.boneIn)

        val dao = application.database.cookDao()
        val ingredients = runBlocking(Dispatchers.IO) { dao.getIngredientsForCook(savedCook.cook.id) }
        assertEquals(1, ingredients.size)
        assertEquals("Salt and pepper rub", ingredients.single().name)
        assertEquals("seasoning", ingredients.single().stage)
        assertEquals(3.0, ingredients.single().amountValue!!, 0.0)
        assertEquals("tbsp", ingredients.single().amountUnit)

        val events = runBlocking(Dispatchers.IO) { dao.getTimelineEventsForCook(savedCook.cook.id) }
        assertEquals(setOf("cook_started", "setpoint_recorded"), events.map { it.eventType }.toSet())
        assertEquals(savedCook.cook.startedAtUtcMillis, events.first().occurredAtUtcMillis)
        assertEquals(savedCook.cook.startedTimeZoneId, events.first().timeZoneId)
        assertFalse(events.any { it.source != "manual" })
    }


    @Test
    fun test03_crashReportIsVisibleAndCopyable() {
        val report = CrashDiagnostics.recordUncaughtException(
            targetContext,
            Thread.currentThread(),
            IllegalStateException("Synthetic diagnostic for UI test"),
        )

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("PitTech stopped unexpectedly").assertIsDisplayed()
        composeRule.onNodeWithText(report.referenceCode).assertIsDisplayed()
        composeRule.onNodeWithText("IllegalStateException: Synthetic diagnostic for UI test").assertIsDisplayed()

        composeRule.onNodeWithTag("crash-report-copy").performClick()
        composeRule.waitForIdle()
        val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copiedText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(targetContext)?.toString().orEmpty()
        assertTrue("Copied diagnostic is missing its reference code.", copiedText.contains(report.referenceCode))
        assertTrue("Copied diagnostic is missing the exception summary.", copiedText.contains("Synthetic diagnostic for UI test"))

    }

    private fun screenshotDirectory() = File(targetContext.filesDir, "pittech-ui-test")

    private fun saveScreenshot(name: String) {
        val directory = screenshotDirectory().apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not save $name screenshot." }
        }
    }
}
