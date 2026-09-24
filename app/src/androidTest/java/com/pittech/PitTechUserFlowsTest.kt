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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.pittech.data.DeviceEntity
import com.pittech.data.ProbeEntity
import com.pittech.data.SensorReadingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun clearLocalData() {
        CrashDiagnostics.clearPendingReport(targetContext)
        val application = targetContext.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) {
            application.database.clearAllTables()
        }
    }

    @Test
    fun test01_homeNavigationAndPrimaryActionAreClear() {
        clearLocalData()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
        composeRule.onNodeWithTag("start-cook").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        saveScreenshot("home-empty")

        composeRule.onNodeWithTag("nav-insights").performClick()
        composeRule.onNodeWithText("Learn from your cooks").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-devices").performClick()
        composeRule.onNodeWithText("Controller setup is paused until your grill arrives.").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.onNodeWithText("Export complete backup (ZIP)").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("nav-cooks").performClick()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
    }

    @Test
    fun test02_createCookWithDishAndPreparationAndSaveLocally() {
        clearLocalData()
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
    fun test03_timelineTemperatureResultsAndInsightsWork() {
        composeRule.onNodeWithText("Saturday brisket").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("cook-tab-timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("timeline-add").performClick()
        waitForText("Add to timeline")
        composeRule.onNodeWithTag("timeline-entry-title").performTextInput("Spritzed")
        composeRule.onNodeWithTag("timeline-entry-details").performTextInput("Honey apple cider vinegar")
        composeRule.onNodeWithTag("timeline-entry-type").performScrollTo().performClick()
        composeRule.onNodeWithText("Spritz").performClick()
        composeRule.onNodeWithTag("timeline-entry-save").performClick()
        waitForText("Spritzed")
        composeRule.onNodeWithTag("timeline-event-edit-spritz").performScrollTo().performClick()
        waitForText("Edit timeline entry")
        composeRule.onNodeWithTag("timeline-entry-title").performTextClearance()
        composeRule.onNodeWithTag("timeline-entry-title").performTextInput("Spritzed lightly")
        composeRule.onNodeWithTag("timeline-entry-save").performClick()
        waitForText("Spritzed lightly")
        composeRule.onNodeWithTag("timeline-event-delete-spritz").performScrollTo().performClick()
        waitForText("Undo")
        composeRule.onNodeWithText("Undo").performClick()
        waitForText("Spritzed lightly")

        composeRule.onNodeWithTag("timeline-add-temperature").performClick()
        composeRule.onNodeWithTag("temperature-probe").performTextClearance()
        composeRule.onNodeWithTag("temperature-probe").performTextInput("Brisket probe")
        composeRule.onNodeWithTag("temperature-value").performTextInput("155")
        composeRule.onNodeWithTag("temperature-save").performClick()
        waitForText("Brisket probe: 155.0 °F")
        composeRule.onNodeWithTag("temperature-edit-Brisket probe").performScrollTo().performClick()
        waitForText("Edit temperature")
        composeRule.onNodeWithTag("temperature-value").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("temperature-value").performTextInput("156")
        composeRule.onNodeWithTag("temperature-save").performClick()
        waitForText("Brisket probe: 156.0 °F")
        composeRule.onNodeWithTag("temperature-delete-Brisket probe").performScrollTo().performClick()
        waitForText("Undo")
        composeRule.onNodeWithText("Undo").performClick()
        waitForText("Brisket probe: 156.0 °F")

        composeRule.onNodeWithTag("timeline-add-temperature").performClick()
        composeRule.onNodeWithTag("temperature-probe").performTextClearance()
        composeRule.onNodeWithTag("temperature-probe").performTextInput("Smoker ambient")
        composeRule.onNodeWithTag("temperature-value").performTextInput("250")
        composeRule.onNodeWithTag("temperature-save").performClick()
        waitForText("Smoker ambient: 250.0 °F")

        composeRule.onNodeWithTag("cook-tab-charts", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Temperature over time · °F").assertIsDisplayed()
        composeRule.onNodeWithTag("cook-tab-live", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("live-finish-cook", useUnmergedTree = true).performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("results-save", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("results-finish-toggle", useUnmergedTree = true).performScrollTo().performClick()
        waitForText("Finish cook when saved")
        composeRule.onNodeWithTag("results-save", useUnmergedTree = true).performClick()

        val application = targetContext.applicationContext as PitTechApplication
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking(Dispatchers.IO) { application.database.cookDao().observeCooks().first().single().cook.status == CookStatus.COMPLETED }
        }
        composeRule.onNodeWithContentDescription("Back to cooks").performClick()
        composeRule.onNodeWithTag("nav-insights").performClick()
        composeRule.onNodeWithText("Learn from your cooks").assertIsDisplayed()
        composeRule.onNodeWithText("Saturday brisket").assertIsDisplayed()
    }

    @Test
    fun test04_portableArchiveAndWorkbookRoundTrip() {
        val application = targetContext.applicationContext as PitTechApplication
        val transfer = application.dataTransfer
        val zipBytes = java.io.ByteArrayOutputStream()
        runBlocking(Dispatchers.IO) { transfer.writeZip(zipBytes) }
        val preview = transfer.previewImport(java.io.ByteArrayInputStream(zipBytes.toByteArray()))
        assertTrue(preview.cookCount > 0)
        assertTrue(preview.dishCount > 0)

        val workbook = java.io.ByteArrayOutputStream()
        runBlocking(Dispatchers.IO) { transfer.writeWorkbook(workbook) }
        val workbookZip = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(workbook.toByteArray()))
        val workbookEntries = mutableListOf<String>()
        workbookZip.use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                workbookEntries += entry.name
                entry = zip.nextEntry
            }
        }
        assertTrue(workbookEntries.contains("xl/workbook.xml"))
        assertTrue(workbookEntries.contains("xl/worksheets/sheet5.xml"))

        runBlocking(Dispatchers.IO) {
            application.database.clearAllTables()
            val restored = transfer.import(preview)
            assertEquals(preview.cookCount, restored.importedCooks)
            assertEquals(preview.cookCount, application.database.cookDao().observeCooks().first().size)
            val duplicate = transfer.import(preview)
            assertEquals(0, duplicate.importedCooks)
            assertEquals(preview.cookCount, duplicate.skippedCooks)

            val snapshot = application.cookRepository.exportSnapshot()
            val cook = snapshot.cooks.single()
            val dishId = snapshot.dishes.firstOrNull()?.id
            val device = DeviceEntity(
                id = "test-controller",
                cookId = cook.id,
                deviceName = "Imported controller record",
                role = "controller",
                createdAtUtcMillis = cook.createdAtUtcMillis,
            )
            val probe = ProbeEntity(
                id = "test-probe",
                cookId = cook.id,
                deviceId = device.id,
                assignedDishId = dishId,
                name = "Imported probe record",
                measurementType = "internal_temperature",
                source = "manual",
                createdAtUtcMillis = cook.createdAtUtcMillis,
            )
            val reading = SensorReadingEntity(
                id = "test-reading",
                cookId = cook.id,
                dishId = dishId,
                probeId = probe.id,
                probeName = probe.name,
                measurementType = "internal_temperature",
                value = 220.0,
                unit = "°F",
                measuredAtUtcMillis = cook.startedAtUtcMillis,
                timeZoneId = cook.startedTimeZoneId,
                source = "manual",
                recordedAtUtcMillis = cook.startedAtUtcMillis,
            )
            val relatedSnapshot = snapshot.copy(
                devices = snapshot.devices + device,
                probes = snapshot.probes + probe,
                readings = listOf(reading),
            )
            application.database.clearAllTables()
            val relationRestore = application.cookRepository.importSnapshot(relatedSnapshot, emptyMap())
            assertEquals(1, relationRestore.importedCooks)
            assertEquals(device.id, application.database.cookDao().getAllDevices().single().id)
            assertEquals(probe.id, application.database.cookDao().getAllProbes().single().id)
            assertEquals(probe.id, application.database.cookDao().getAllSensorReadings().single().probeId)
        }
    }

    @Test
    fun test05_crashReportIsVisibleAndCopyable() {
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

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saveScreenshot(name: String) {
        val directory = screenshotDirectory().apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not save $name screenshot." }
        }
    }
}
