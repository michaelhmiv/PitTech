package com.pittech

import android.Manifest
import android.os.Build
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.graphics.Bitmap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import com.pittech.data.CameraPhotoFiles
import com.pittech.data.CookStatus
import com.pittech.data.DeviceEntity
import com.pittech.data.PhotoEntity
import com.pittech.data.ProbeEntity
import com.pittech.data.SensorReadingEntity
import com.pittech.data.TimelineEventEntity
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import com.pittech.ui.PitTechThemeMode
import com.pittech.ui.ZipShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PitTechUserFlowsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clearLocalData() {
        CrashDiagnostics.clearPendingReport(targetContext)
        targetContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE)
            .edit()
            .remove(PitTechThemeMode.PREFERENCE_KEY)
            .apply()
        val application = targetContext.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) {
            application.database.clearAllTables()
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun test01_homeNavigationAndPrimaryActionAreClear() {
        clearLocalData()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
        composeRule.onNodeWithTag("start-cook").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithTag("empty-restore-backup").assertIsDisplayed()
        saveScreenshot("home-empty")

        composeRule.onNodeWithTag("nav-insights").performClick()
        composeRule.onNodeWithText("Learn from your cooks").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-devices").performClick()
        composeRule.onNodeWithText("Controller setup is paused until your grill arrives.").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.onNodeWithTag("theme-mode-system").assertIsSelected()
        composeRule.onNodeWithText("PitTech follows your device appearance.").assertIsDisplayed()
        composeRule.onNodeWithTag("theme-mode-dark").performClick()
        composeRule.onNodeWithTag("theme-mode-dark").assertIsSelected()
        composeRule.onNodeWithText("Dark appearance is selected.").assertIsDisplayed()
        saveScreenshot("settings-dark")
        assertEquals(
            "DARK",
            targetContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE)
                .getString(PitTechThemeMode.PREFERENCE_KEY, null),
        )
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.onNodeWithTag("theme-mode-dark").assertIsSelected()
        composeRule.onNodeWithTag("theme-mode-system").performClick()
        composeRule.onNodeWithTag("theme-mode-system").assertIsSelected()

        composeRule.onNodeWithText("Save full backup (ZIP)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("share-full-backup").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("nav-cooks").performClick()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
    }

    @Test
    fun test02_createCookWithDishAndPreparationAndSaveLocally() {
        clearLocalData()
        composeRule.onNodeWithTag("start-cook").performClick()

        composeRule.onNodeWithTag("cook-add-dish").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("dish-name").assertIsDisplayed().performTextInput("Brisket")
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
        composeRule.onNodeWithTag("dish-add-photos").performScrollTo().performClick()
        composeRule.onNodeWithTag("photo-source-camera").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-source-library").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-source-cancel").performClick()
        composeRule.onNodeWithTag("dish-save").performClick()

        composeRule.onNodeWithTag("cook-title").performTextClearance()
        composeRule.onNodeWithTag("cook-title").performTextInput("Saturday brisket")
        composeRule.onNodeWithTag("cook-smoker").performTextInput("Pit Boss Austin XL")
        composeRule.onNodeWithTag("cook-setpoint").performTextInput("0")
        composeRule.onNodeWithTag("cook-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("cook-setpoint").performTextClearance()
        composeRule.onNodeWithTag("cook-setpoint").performTextInput("250")
        composeRule.onNodeWithTag("cook-save").assertIsEnabled()
        composeRule.onNodeWithTag("cook-notes").performTextInput("Cool morning; used hickory.")

        composeRule.onNodeWithTag("cook-save").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("cook-tab-live", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Saturday brisket").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Whole packer", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cook-tab-live").assertIsDisplayed()
        composeRule.onNodeWithText("Quick actions").assertIsDisplayed()
        composeRule.onNodeWithText("Whole packer", substring = true).performScrollTo().assertIsDisplayed()
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
        clearLocalData()
        val application = targetContext.applicationContext as PitTechApplication
        val cookId = createTestCook("Saturday brisket")
        val dao = application.database.cookDao()
        val now = System.currentTimeMillis()
        val photoPath = "photos/dashboard-photo.jpg"
        val photoFile = File(targetContext.filesDir, photoPath).apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        photoFile.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) }
        bitmap.recycle()
        runBlocking(Dispatchers.IO) {
            dao.insertPhotos(
                listOf(
                    PhotoEntity(
                        id = "dashboard-photo-old",
                        cookId = cookId,
                        originalFileName = "seasoning.jpg",
                        relativePath = photoPath,
                        mimeType = "image/jpeg",
                        caption = "Seasoning",
                        addedAtUtcMillis = now - 60_000,
                    ),
                    PhotoEntity(
                        id = "dashboard-photo",
                        cookId = cookId,
                        originalFileName = "brisket-resting.jpg",
                        relativePath = photoPath,
                        mimeType = "image/jpeg",
                        caption = "Brisket resting",
                        addedAtUtcMillis = now,
                    ),
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("cook-card-$cookId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cook-card-$cookId").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("cook-tab-timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("live-cook-status").assertIsDisplayed()
        composeRule.onNodeWithTag("live-cook-elapsed").assertIsDisplayed()
        val configuration = targetContext.resources.configuration
        if (configuration.screenWidthDp >= 390 && configuration.fontScale <= 1.3f) {
            composeRule.onNodeWithTag("live-overview-grid").assertExists()
        } else {
            assertTrue(composeRule.onAllNodesWithTag("live-overview-grid").fetchSemanticsNodes().isEmpty())
        }
        composeRule.onNodeWithTag("timeline-add").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("timeline-add-temperature").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("timeline-add-photo").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("cook-add-reminder").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        saveScreenshot("cook-live-dashboard-top")
        assertTrue(composeRule.onAllNodesWithText("Starting condition: Refrigerated").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("live-prep-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("Starting condition: Refrigerated").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live-prep-toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("live-more-tools-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("Export").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Add target").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("live-more-tools-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("Brisket resting").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("All photos (2)").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("photo-gallery-title").assertIsDisplayed()
        composeRule.onNodeWithText("Photos (2)").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-gallery-row-dashboard-photo").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-gallery-row-dashboard-photo-old").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-gallery-item-dashboard-photo").performClick()
        composeRule.onNodeWithTag("photo-viewer-title").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-viewer-close").performClick()
        composeRule.onNodeWithTag("live-recent-photo").performScrollTo().assertIsDisplayed()
        saveScreenshot("cook-live-dashboard")
        composeRule.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        val timelinePhotoEventId = "timeline-photo-event"
        val timelinePhotoAt = System.currentTimeMillis() - 30_000
        val sampleStart = System.currentTimeMillis() - 6 * 60_000
        runBlocking(Dispatchers.IO) {
            dao.insertTimelineEvent(
                TimelineEventEntity(
                    id = timelinePhotoEventId,
                    cookId = cookId,
                    eventType = "photo",
                    title = "Bark photo",
                    details = "Bark set before the spritz.",
                    occurredAtUtcMillis = timelinePhotoAt,
                    recordedAtUtcMillis = timelinePhotoAt,
                    timeZoneId = "America/New_York",
                    source = "manual",
                    createdAtUtcMillis = timelinePhotoAt,
                    updatedAtUtcMillis = timelinePhotoAt,
                ),
            )
            dao.insertPhotos(
                listOf(
                    PhotoEntity(
                        id = "timeline-bark-photo",
                        cookId = cookId,
                        eventId = timelinePhotoEventId,
                        originalFileName = "bark.jpg",
                        relativePath = "photos/missing-bark-photo.jpg",
                        mimeType = "image/jpeg",
                        caption = "Bark set",
                        capturedAtUtcMillis = timelinePhotoAt,
                        addedAtUtcMillis = timelinePhotoAt,
                    ),
                ),
            )
            dao.insertReadingsIgnoringDuplicates(
                (0 until 6).map { index ->
                    val measuredAt = sampleStart + index * 60_000
                    SensorReadingEntity(
                        id = "pit-sample-$index",
                        cookId = cookId,
                        probeName = "Pit ambient",
                        measurementType = "ambient_temperature",
                        value = 240.0 + index,
                        unit = "°F",
                        measuredAtUtcMillis = measuredAt,
                        timeZoneId = "America/New_York",
                        source = "controller",
                        sourceDeviceId = "test-controller",
                        qualityStatus = "valid",
                        recordedAtUtcMillis = measuredAt,
                    )
                },
            )
        }
        composeRule.onNodeWithTag("timeline-add-photo").performClick()
        composeRule.onNodeWithTag("photo-source-camera").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-source-library").assertIsDisplayed()
        val photoCountBeforePickerCancel = runBlocking(Dispatchers.IO) { (targetContext.applicationContext as PitTechApplication).database.cookDao().getAllPhotos().size }
        composeRule.onNodeWithTag("photo-source-cancel").performClick()
        assertEquals(photoCountBeforePickerCancel, runBlocking(Dispatchers.IO) { (targetContext.applicationContext as PitTechApplication).database.cookDao().getAllPhotos().size })
        composeRule.onNodeWithTag("timeline-add").performClick()
        waitForText("Add to cook log")
        saveScreenshot("cook-log-entry")
        composeRule.onNodeWithTag("timeline-entry-details").performTextInput("Honey apple cider vinegar")
        composeRule.onNodeWithTag("timeline-entry-more-details").performClick()
        composeRule.onNodeWithTag("timeline-entry-type").performScrollTo().performClick()
        composeRule.onNodeWithText("Spritz").performClick()
        composeRule.onNodeWithTag("timeline-entry-title").performTextClearance()
        composeRule.onNodeWithTag("timeline-entry-title").performTextInput("Spritzed")
        composeRule.onNodeWithTag("timeline-entry-save").performClick()
        waitForText("Spritzed")
        waitForAnyText("Entry added to the timeline.")
        waitForTextsToDisappear("Entry added to the timeline.")
        composeRule.onNodeWithTag("timeline-event-actions-spritz").performScrollTo().performClick()
        composeRule.onNodeWithTag("timeline-event-edit-spritz").performClick()
        captureCurrentScreen("timeline-edit-dialog")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("timeline-entry-title").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("timeline-entry-title").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-entry-title").performTextClearance()
        composeRule.onNodeWithTag("timeline-entry-title").performTextInput("Spritzed lightly")
        composeRule.onNodeWithTag("timeline-entry-save").performClick()
        waitForText("Spritzed lightly")
        waitForAnyText("Timeline entry updated.")
        waitForTextsToDisappear("Timeline entry updated.")
        composeRule.onNodeWithTag("timeline-event-actions-spritz").performScrollTo().performClick()
        composeRule.onNodeWithTag("timeline-event-delete-spritz").performClick()
        waitForText("Undo")
        composeRule.onNodeWithText("Undo").performClick()
        waitForText("Spritzed lightly")
        waitForAnyText("Entry restored.")
        waitForTextsToDisappear("Entry restored.")

        composeRule.onNodeWithTag("timeline-add-temperature").performClick()
        composeRule.onNodeWithTag("temperature-probe").performTextClearance()
        composeRule.onNodeWithTag("temperature-probe").performTextInput("Brisket probe")
        composeRule.onNodeWithTag("temperature-value").performTextInput("155")
        composeRule.onNodeWithTag("temperature-save").performClick()
        waitForText("Brisket probe: 155.0 °F")
        waitForAnyText("Temperature saved.")
        waitForTextsToDisappear("Temperature saved.")
        composeRule.onNodeWithTag("temperature-actions-Brisket probe").performScrollTo().performClick()
        composeRule.onNodeWithTag("temperature-edit-Brisket probe").performClick()
        waitForText("Edit temperature")
        composeRule.onNodeWithTag("temperature-value").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("temperature-value").performTextInput("156")
        composeRule.onNodeWithTag("temperature-save").performClick()
        waitForText("Brisket probe: 156.0 °F")
        waitForAnyText("Temperature entry updated.")
        waitForTextsToDisappear("Temperature entry updated.")
        composeRule.onNodeWithTag("temperature-actions-Brisket probe").performScrollTo().performClick()
        composeRule.onNodeWithTag("temperature-delete-Brisket probe").performClick()
        waitForText("Undo")
        composeRule.onNodeWithText("Undo").performClick()
        waitForText("Brisket probe: 156.0 °F")

        composeRule.onNodeWithTag("timeline-add-temperature").performClick()
        composeRule.onNodeWithTag("temperature-probe").performTextClearance()
        composeRule.onNodeWithTag("temperature-probe").performTextInput("Smoker ambient")
        composeRule.onNodeWithTag("temperature-value").performTextInput("250")
        composeRule.onNodeWithTag("temperature-save").performClick()
        waitForText("Smoker ambient: 250.0 °F")

        composeRule.onNodeWithTag("timeline-filter-temperatures").performClick()
        composeRule.onNodeWithText("Show 6 readings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-group-expand-pit-sample-0").performScrollTo().performClick()
        composeRule.onNodeWithText("Pit ambient: 242.0 °F").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-filter-events").performClick()
        composeRule.onNodeWithText("Spritzed lightly").performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Pit ambient", substring = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("timeline-filter-photos").performClick()
        composeRule.onNodeWithText("Bark photo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-photo-gallery-$timelinePhotoEventId").performScrollTo().performClick()
        composeRule.onNodeWithText("Photos (3)").assertIsDisplayed()
        composeRule.onNodeWithTag("photo-gallery-close").performClick()
        assertTrue(composeRule.onAllNodesWithText("Spritzed lightly").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("timeline-sort").performClick()
        composeRule.onNodeWithTag("timeline-sort-oldest").performClick()
        composeRule.onNodeWithTag("timeline-sort").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-filter-all").performClick()
        composeRule.onNodeWithTag("timeline-sort").performClick()
        composeRule.onNodeWithTag("timeline-sort-newest").performClick()
        composeRule.onNodeWithText("Cook started").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-jump-latest").assertIsDisplayed().performClick()
        saveScreenshot("cook-timeline-dashboard")

        composeRule.onNodeWithTag("cook-tab-charts", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Quick actions").assertIsDisplayed()
        composeRule.onNodeWithText("Temperature over time · °F").assertIsDisplayed()
        composeRule.onNodeWithTag("cook-tab-live", useUnmergedTree = true).performClick()
        saveScreenshot("cook-live-actions")
        composeRule.onNodeWithTag("live-finish-cook").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("results-dialog-title", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("results-finish-toggle", useUnmergedTree = true).performScrollTo().performClick()
        waitForText("Finish cook when saved")
        composeRule.onNodeWithTag("results-save").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking(Dispatchers.IO) { application.database.cookDao().observeCooks().first().single().cook.status == CookStatus.COMPLETED }
        }
        composeRule.onNodeWithContentDescription("Back to cooks").performClick()
        composeRule.onNodeWithTag("nav-insights").performClick()
        composeRule.onNodeWithText("Learn from your cooks").assertIsDisplayed()
        composeRule.onNodeWithText("Saturday brisket").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun test04_portableArchiveAndWorkbookRoundTrip() {
        val application = targetContext.applicationContext as PitTechApplication
        val transfer = application.dataTransfer
        runBlocking(Dispatchers.IO) { application.database.clearAllTables() }
        createTestCook("Portable archive sample")
        targetContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE).edit()
            .putString("temperature-unit", "°C")
            .putString("weight-unit", "kg")
            .putString(PitTechThemeMode.PREFERENCE_KEY, "DARK")
            .apply()
        val zipBytes = java.io.ByteArrayOutputStream()
        runBlocking(Dispatchers.IO) { transfer.writeZip(zipBytes) }
        val preview = transfer.previewImport(java.io.ByteArrayInputStream(zipBytes.toByteArray()))
        assertTrue(preview.cookCount > 0)
        assertTrue(preview.dishCount > 0)
        assertEquals(2, preview.archiveVersion)
        assertEquals("°C", preview.preferences?.temperatureUnit)
        assertEquals("kg", preview.preferences?.weightUnit)
        assertEquals("DARK", preview.preferences?.themeMode)

        val legacyPreview = transfer.previewImport(java.io.ByteArrayInputStream(asLegacyV1Archive(zipBytes.toByteArray())))
        assertEquals(1, legacyPreview.archiveVersion)
        assertEquals(null, legacyPreview.preferences)
        assertEquals(preview.cookCount, legacyPreview.cookCount)

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

    @Test
    fun test06_cameraPhotoUriAcceptsCameraOutput() {
        val photoFile = CameraPhotoFiles.create(targetContext)
        val uri = CameraPhotoFiles.uri(targetContext, photoFile)
        try {
            assertEquals("content", uri.scheme)
            val intent = ActivityResultContracts.TakePicture().createIntent(targetContext, uri)
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
            @Suppress("DEPRECATION")
            val outputUri = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
            assertEquals(uri, outputUri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)

            val expectedBytes = byteArrayOf(12, 34, 56, 78)
            targetContext.contentResolver.openOutputStream(uri, "w")!!.use { it.write(expectedBytes) }
            val actualBytes = targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(expectedBytes, actualBytes)

            CameraPhotoFiles.delete(targetContext, uri)
            assertFalse(photoFile.exists())
        } finally {
            photoFile.delete()
        }
    }

    @Test
    fun test07_photoLogIsOneEntryAndMissingAttachmentsBlockRestore() {
        val application = targetContext.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) { application.database.clearAllTables() }
        createTestCook("Photo backup sample")
        val cook = runBlocking(Dispatchers.IO) { application.database.cookDao().getAllCooks().single() }
        val photoFile = CameraPhotoFiles.create(targetContext)
        val photoUri = CameraPhotoFiles.uri(targetContext, photoFile)
        val imageBytes = byteArrayOf(1, 3, 5, 7, 9, 11)
        targetContext.contentResolver.openOutputStream(photoUri, "w")!!.use { it.write(imageBytes) }

        val beforeEvents = runBlocking(Dispatchers.IO) { application.database.cookDao().getTimelineEventsForCook(cook.id).size }
        val occurredAt = System.currentTimeMillis()
        val saved = runBlocking(Dispatchers.IO) {
            application.cookRepository.addTimelineEventWithPhoto(
                cookId = cook.id,
                dishId = null,
                eventType = "note",
                title = "Photo with a note",
                details = "One event keeps this log entry together.",
                occurredAtUtcMillis = occurredAt,
                photoUri = photoUri.toString(),
                photoCaption = "Bark color",
            )
        }
        assertTrue(saved.photoAttached)
        assertEquals(beforeEvents + 1, runBlocking(Dispatchers.IO) { application.database.cookDao().getTimelineEventsForCook(cook.id).size })
        assertEquals(occurredAt, saved.event.occurredAtUtcMillis)
        assertEquals(java.time.ZoneId.systemDefault().id, saved.event.timeZoneId)
        val photo = runBlocking(Dispatchers.IO) { application.database.cookDao().getPhotosForCook(cook.id).single { it.eventId == saved.event.id } }
        assertEquals("Bark color", photo.caption)
        assertArrayEquals(imageBytes, runBlocking(Dispatchers.IO) { application.cookRepository.readPhoto(photo.relativePath)!! })

        val failedPhoto = runBlocking(Dispatchers.IO) {
            application.cookRepository.addTimelineEventWithPhoto(
                cookId = cook.id,
                dishId = null,
                eventType = "note",
                title = "Note survives failed photo copy",
                details = "The text remains available to retry.",
                occurredAtUtcMillis = System.currentTimeMillis(),
                photoUri = "content://com.pittech.missing/photo.jpg",
                photoCaption = null,
            )
        }
        assertFalse(failedPhoto.photoAttached)
        assertTrue(runBlocking(Dispatchers.IO) { application.database.cookDao().getTimelineEventsForCook(cook.id).any { it.id == failedPhoto.event.id } })

        val archive = java.io.ByteArrayOutputStream()
        runBlocking(Dispatchers.IO) { application.dataTransfer.writeZip(archive, cook.id) }
        val preview = application.dataTransfer.previewImport(java.io.ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, preview.photoCount)
        assertEquals("A single-cook export should not overwrite this phone's preferences.", null, preview.preferences)

        val withoutAttachment = rewriteZip(archive.toByteArray()) { name -> !name.startsWith("attachments/") }
        assertTrue("A backup missing a referenced photo must fail before import.", runCatching {
            application.dataTransfer.previewImport(java.io.ByteArrayInputStream(withoutAttachment))
        }.isFailure)
        val truncated = archive.toByteArray().copyOf(archive.size() - 22)
        assertTrue("A ZIP missing its central directory must fail preflight.", runCatching {
            application.dataTransfer.previewImport(java.io.ByteArrayInputStream(truncated))
        }.isFailure)
        val unsafePath = renameZipEntry(archive.toByteArray(), "data/pittech.json", "../data/pittech.json")
        assertTrue("Unsafe ZIP paths must fail preflight.", runCatching {
            application.dataTransfer.previewImport(java.io.ByteArrayInputStream(unsafePath))
        }.isFailure)
        assertEquals(cook.id, runBlocking(Dispatchers.IO) { application.database.cookDao().getCook(cook.id) }?.id)

        val storedPhotoFile = File(targetContext.filesDir, photo.relativePath)
        storedPhotoFile.delete()
        assertTrue("Full backups must fail instead of silently omitting a missing source photo.", runCatching {
            runBlocking(Dispatchers.IO) { application.dataTransfer.writeZip(java.io.ByteArrayOutputStream(), cook.id) }
        }.isFailure)
        storedPhotoFile.parentFile?.mkdirs()
        storedPhotoFile.writeBytes(imageBytes)

        runBlocking(Dispatchers.IO) {
            application.database.clearAllTables()
            val restored = application.dataTransfer.import(preview)
            assertEquals(1, restored.importedCooks)
            val restoredPhoto = application.database.cookDao().getAllPhotos().single()
            assertEquals(saved.event.id, restoredPhoto.eventId)
            assertArrayEquals(imageBytes, application.cookRepository.readPhoto(restoredPhoto.relativePath)!!)
        }
    }

    @Test
    fun test08_zipShareIntentUsesReadOnlyContentUri() {
        val exportDir = File(targetContext.cacheDir, "exports").apply { mkdirs() }
        val archive = File(exportDir, "share-test.zip").apply { writeBytes(byteArrayOf(4, 8, 12, 16)) }
        val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.fileprovider", archive)
        val intent = ZipShareIntent.create(uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertArrayEquals(byteArrayOf(4, 8, 12, 16), targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        archive.delete()
    }

    @Test
    fun test00_reminderCheckInBecomesCookTimelineNoteAndRestores() {
        clearLocalData()
        val defaultCookName = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        composeRule.onNodeWithTag("start-cook").performClick()
        composeRule.onNodeWithTag("cook-title").assertIsDisplayed()
        composeRule.onNodeWithTag("cook-save").performClick()
        waitForText(defaultCookName)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("cook-tab-live").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cook-tab-live").assertIsDisplayed()

        val application = targetContext.applicationContext as PitTechApplication
        val cook = runBlocking(Dispatchers.IO) { application.database.cookDao().observeCooks().first().single().cook }
        assertEquals(defaultCookName, cook.title)
        composeRule.onNodeWithTag("cook-add-reminder").performClick()
        composeRule.onNodeWithTag("reminder-title").performTextClearance()
        composeRule.onNodeWithTag("reminder-title").performTextInput("Check the brisket")
        composeRule.onNodeWithText("30 min").performClick()
        composeRule.onNodeWithTag("reminder-save").performClick()
        waitForText("Check the brisket")
        waitForAnyText(
            "Reminder set for",
            "Reminder saved. Turn on PitTech notifications",
        )
        waitForTextsToDisappear(
            "Reminder set for",
            "Reminder saved. Turn on PitTech notifications",
        )

        val reminder = runBlocking(Dispatchers.IO) { application.database.cookDao().getAllReminders().single() }
        assertEquals(com.pittech.data.CookReminderEntity.STATUS_PENDING, reminder.status)
        composeRule.onNodeWithText("Log now").performScrollTo().assertIsDisplayed().performClick()
        captureCurrentScreen("reminder-after-log-now")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("reminder-checkin-note").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("reminder-checkin-note").assertIsDisplayed()
        composeRule.onNodeWithText("How did it go?").assertIsDisplayed()
        saveScreenshot("reminder-check-in")
        composeRule.onNodeWithTag("reminder-checkin-note").performTextInput("Wrapped at 160°F")
        composeRule.onNodeWithTag("reminder-checkin-save").performClick()
        waitForText("Check-in added to the cook timeline.")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking(Dispatchers.IO) {
                application.database.cookDao().getAllReminders().single().status == com.pittech.data.CookReminderEntity.STATUS_COMPLETED
            }
        }
        val event = runBlocking(Dispatchers.IO) {
            application.database.cookDao().getTimelineEventsForCook(cook.id).single { it.eventType == "reminder_completed" }
        }
        assertEquals("Check-in: Check the brisket", event.title)
        assertEquals("Wrapped at 160°F", event.details)
        val checkInPhotoFile = CameraPhotoFiles.create(targetContext)
        val checkInPhotoUri = CameraPhotoFiles.uri(targetContext, checkInPhotoFile)
        val checkInImageBytes = byteArrayOf(2, 4, 6, 8)
        targetContext.contentResolver.openOutputStream(checkInPhotoUri, "w")!!.use { it.write(checkInImageBytes) }
        runBlocking(Dispatchers.IO) {
            application.cookRepository.addReminderCheckInPhoto(event.id, cook.id, checkInPhotoUri.toString(), "Grill check")
        }
        assertEquals(1, runBlocking(Dispatchers.IO) {
            application.database.cookDao().getTimelineEventsForCook(cook.id).count { it.eventType == "reminder_completed" }
        })
        assertEquals(event.id, runBlocking(Dispatchers.IO) { application.database.cookDao().getPhotosForCook(cook.id).single().eventId })

        val archive = java.io.ByteArrayOutputStream()
        runBlocking(Dispatchers.IO) { application.dataTransfer.writeZip(archive, cook.id) }
        val preview = application.dataTransfer.previewImport(java.io.ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, preview.snapshot.reminders.size)
        assertEquals(com.pittech.data.CookReminderEntity.STATUS_COMPLETED, preview.snapshot.reminders.single().status)
        assertEquals(1, preview.photoCount)
    }

    private fun screenshotDirectory() = File(targetContext.filesDir, "pittech-ui-test")

    private fun captureCurrentScreen(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = File(screenshotDirectory().apply { mkdirs() }, "$name.png")
        output.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not save $name screenshot." }
        }
        bitmap.recycle()
    }

    private fun asLegacyV1Archive(bytes: ByteArray): ByteArray = rewriteZip(bytes) { true }.let { source ->
        val output = java.io.ByteArrayOutputStream()
        ZipInputStream(source.inputStream()).use { input ->
            ZipOutputStream(output).use { zip ->
                var entry = input.nextEntry
                while (entry != null) {
                    var contents = input.readBytes()
                    if (entry.name == "manifest.json") {
                        contents = JSONObject(contents.toString(Charsets.UTF_8))
                            .put("archiveVersion", 1)
                            .put("includesPreferences", false)
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    } else if (entry.name == "data/pittech.json") {
                        val data = JSONObject(contents.toString(Charsets.UTF_8)).apply {
                            put("schemaVersion", 1)
                            remove("preferences")
                        }
                        contents = data.toString().toByteArray(Charsets.UTF_8)
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(contents)
                    zip.closeEntry()
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        output.toByteArray()
    }

    private fun rewriteZip(bytes: ByteArray, includeEntry: (String) -> Boolean): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipInputStream(bytes.inputStream()).use { input ->
            ZipOutputStream(output).use { zip ->
                var entry = input.nextEntry
                while (entry != null) {
                    val contents = input.readBytes()
                    if (includeEntry(entry.name)) {
                        zip.putNextEntry(ZipEntry(entry.name))
                        zip.write(contents)
                        zip.closeEntry()
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        return output.toByteArray()
    }

    private fun renameZipEntry(bytes: ByteArray, oldName: String, newName: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipInputStream(bytes.inputStream()).use { input ->
            ZipOutputStream(output).use { zip ->
                var entry = input.nextEntry
                while (entry != null) {
                    val contents = input.readBytes()
                    zip.putNextEntry(ZipEntry(if (entry.name == oldName) newName else entry.name))
                    zip.write(contents)
                    zip.closeEntry()
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        return output.toByteArray()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForAnyText(vararg texts: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            texts.any { text ->
                composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun waitForTextsToDisappear(vararg texts: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            texts.all { text ->
                composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    private fun createTestCook(title: String): String {
        val application = targetContext.applicationContext as PitTechApplication
        return runBlocking(Dispatchers.IO) {
            application.cookRepository.startCook(
                NewCookDraft(
                    title = title,
                    dishes = listOf(
                        DishDraft(
                            name = "Brisket",
                            foodType = "Beef",
                            cut = "Whole packer",
                            weightText = "12.5",
                            startingCondition = "Refrigerated",
                        ),
                    ),
                ),
            )
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
