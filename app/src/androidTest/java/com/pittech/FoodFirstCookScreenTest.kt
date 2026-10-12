package com.pittech

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.data.*
import com.pittech.devices.*
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class FoodFirstCookScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as PitTechApplication
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(app.filesDir, "pittech-ui-test/" + name + ".png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().apply {
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
    private fun chooseFilter(tag: String) {
        compose.onNodeWithTag("timeline-filter-menu").performClick()
        compose.onNodeWithTag("timeline-filter-" + tag).performClick()
    }
    private fun dismissNotice() {
        val dismiss = compose.onAllNodesWithContentDescription("Dismiss")
        if (dismiss.fetchSemanticsNodes().isNotEmpty()) dismiss[0].performClick()
        compose.waitUntil(5_000) { dismiss.fetchSemanticsNodes().isEmpty() }
    }
    private fun saveEntryWithKeyboardOpen() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var saveBounds = Rect()
        var diagnostic = "Waiting for the keyboard and Save entry."
        try {
            compose.waitUntil(10_000) {
                var keyboardTop: Int? = null
                val dialogOrigin = IntArray(2)
                // Measure the actual keyboard occlusion, rather than its accessibility window,
                // which can include transparent space above the keys.
                onView(isRoot()).inRoot(isDialog()).check { view, failure ->
                    if (failure != null) throw failure
                    val root = requireNotNull(view)
                    root.getLocationOnScreen(dialogOrigin)
                    val insets = ViewCompat.getRootWindowInsets(root)
                    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                        keyboardTop = dialogOrigin[1] + root.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    }
                }
                val bounds = compose.onNodeWithTag("temperature-save").fetchSemanticsNode().boundsInWindow
                saveBounds = Rect((bounds.left + dialogOrigin[0]).roundToInt(), (bounds.top + dialogOrigin[1]).roundToInt(),
                    (bounds.right + dialogOrigin[0]).roundToInt(), (bounds.bottom + dialogOrigin[1]).roundToInt())
                diagnostic = "Save bounds: $saveBounds; keyboard top: $keyboardTop"
                keyboardTop != null && !saveBounds.isEmpty && saveBounds.bottom <= keyboardTop!!
            }
        } catch (failure: Throwable) {
            throw AssertionError("Save entry must remain above the open keyboard. $diagnostic", failure)
        } finally {
            screenshot("food-first-note")
        }
        compose.onNodeWithTag("temperature-save").assertIsDisplayed().assertIsEnabled()
        // Compose's performClick can reach a node covered by another window. Inject a real
        // screen tap so this also proves the keyboard does not intercept the save button.
        val downAt = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                saveBounds.exactCenterX(), saveBounds.exactCenterY(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(this, true)) } finally { recycle() }
            }
        }
    }

    @Test fun threeFoodsKeepIndependentHistoryAndQuickEntryFiltersSurviveReopening() {
        lateinit var butt: DishEntity
        lateinit var steaks: DishEntity
        lateinit var potatoes: DishEntity
        lateinit var combined: TimelineEventEntity
        lateinit var setupPhoto: PhotoEntity
        val cookId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val id = app.cookRepository.startCook(NewCookDraft("Sunday mixed grill", dishes = listOf(DishDraft("Pork butt", "Pork"), DishDraft("Shoulder steaks", "Pork"))))
            val foods = app.database.cookDao().getDishesForCook(id)
            butt = foods.first { it.name == "Pork butt" }; steaks = foods.first { it.name == "Shoulder steaks" }
            val now = System.currentTimeMillis()
            app.database.cookDao().getCook(id)!!.let { app.database.cookDao().updateCook(it.copy(startedAtUtcMillis = now - 8 * 3_600_000)) }
            app.database.cookDao().getTimelineEventsForCook(id).filter { it.eventType == "cook_started" }.forEach {
                app.database.cookDao().updateTimelineEvent(it.copy(occurredAtUtcMillis = now - 8 * 3_600_000))
            }
            app.cookRepository.addTimelineEvent(id, butt.id, "meat_on", "Butt on", null, now - 8 * 3_600_000)
            app.cookRepository.addTimelineEvent(id, butt.id, "remove", "Off grill and venting", null, now - 3 * 3_600_000)
            val potatoId = app.cookRepository.addDish(id, DishDraft("Potatoes", "Vegetables"))
            potatoes = app.database.cookDao().getDish(potatoId)!!
            app.cookRepository.addTimelineEvent(id, potatoId, "meat_on", "Potatoes on", null, now - 2 * 3_600_000)
            app.cookRepository.addTimelineEvent(id, steaks.id, "meat_on", "Steaks on", null, now - 100 * 60_000)
            val device = PolarisDevice("synthetic-mixed-grill", "Backyard", "Test", null, null, null)
            app.recordingRepository.attach(id, device, "°F", mapOf("probe1" to butt.id, "probe2" to steaks.id), now = now - 60_000)
            val sample = PolarisSample(now - 30_000, PolarisPayload(mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to 283.0,
                "furnaceTempSetting" to 280.0, "probeP1Measured" to 210.0, "probeP2Measured" to 186.0), emptyList(), 0, 0))
            app.recordingRepository.ingest(id, CookTelemetryPolicy.deviceKey(device.id), sample,
                PolarisMonitorState(authenticated = true, devices = listOf(device), selectedDeviceId = device.id, onlineStatus = 0, statusFetchedAtMillis = now - 30_000))
            app.recordingRepository.stop(id)
            val source = File(app.cacheDir, "mixed-grill-fixture.jpg")
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(174, 113, 61))
                source.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 100, it) }; recycle()
            }
            setupPhoto = PhotoStorage(app).copyIntoLibrary(Uri.fromFile(source).toString(), id, potatoId, now - 60_000, caption = "Potatoes seasoned")
            app.database.cookDao().insertPhotos(listOf(setupPhoto))
            combined = app.cookRepository.notes.save(CookNoteDraft(cookId = id, category = "action", action = "wrap",
                dishIds = listOf(steaks.id, potatoId), description = "Checked bark and wrapped the tray", occurredAt = now,
                temperatures = listOf(NoteTemperature(valueText = "186", dishId = steaks.id)),
                photos = listOf(NotePhoto(uri = Uri.fromFile(source).toString(), caption = "Tray after wrapping")))).event
            source.delete()
            id
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Sunday mixed grill").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sunday mixed grill").performClick()
        compose.onNodeWithTag("food-card-" + steaks.id, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("food-card-" + potatoes.id, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Off the grill").performScrollTo().assertIsDisplayed()
        screenshot("food-first-live")
        compose.onNodeWithTag("food-card-" + potatoes.id, useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("food-ingredients-" + potatoes.id).performScrollTo().performClick()
        compose.onNodeWithTag("food-ingredient-name").performTextInput("Butter")
        assertTrue(compose.onAllNodesWithTag("food-ingredient-amount").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("food-ingredient-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getIngredientsForCook(cookId).any { it.name == "Butter" && it.dishId == potatoes.id && it.amountValue == null } } }
        dismissNotice()
        compose.onNodeWithTag("food-probes-" + potatoes.id).performScrollTo().performClick()
        compose.onNodeWithTag("probe-move-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getProbesForCook(cookId).any { it.name == "Probe 1" && it.assignedDishId == potatoes.id } } }
        dismissNotice()
        compose.onNodeWithText("Probe 1 · waiting for a reading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("food-temperature-" + potatoes.id + "-manual", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("food-temperature-" + butt.id + "-manual", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("food-temperature-" + steaks.id + "-manual", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("food-probe-assignment-" + steaks.id, useUnmergedTree = true).assertTextContains("Assigned: Probe 2")
        compose.onNodeWithTag("food-temperature-" + potatoes.id).performScrollTo().performClick()
        compose.onNodeWithTag("note-food-scope").assertTextContains("Potatoes")
        assertTrue(compose.onAllNodesWithTag("temperature-probe").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("temperature-value").performTextInput("180")
        compose.onNodeWithTag("timeline-entry-details").performClick().performTextInput("Tender at the center")
        saveEntryWithKeyboardOpen()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllSensorReadings().any { it.dishId == potatoes.id && it.source == "manual" && it.value == 180.0 } } }
        dismissNotice()
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("timeline-filter-menu").assertTextContains("Cook story")
        chooseFilter("photos")
        compose.onNodeWithTag("timeline-food-filter").performClick()
        compose.onNodeWithTag("timeline-food-filter-option-potatoes").performClick()
        compose.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-entry-" + combined.id))
        compose.onNodeWithTag("timeline-entry-" + combined.id).assertExists()
        screenshot("food-first-timeline")
        compose.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-photo-" + setupPhoto.id))
        compose.onNodeWithTag("timeline-photo-" + setupPhoto.id).assertHeightIsAtLeast(170.dp)
        assertNull(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllPhotos().single { it.id == setupPhoto.id }.eventId })
        compose.onNodeWithContentDescription("Back to cooks").performClick()
        compose.onNodeWithTag("cook-card-" + cookId).performScrollTo().performClick()
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("timeline-filter-menu").assertTextContains("Photos")
        compose.onNodeWithTag("timeline-food-filter").assertTextContains("Potatoes")
        chooseFilter("notes")
        compose.onNodeWithTag("timeline-list").performScrollToNode(hasText("Tender at the center"))
        compose.onNodeWithText("Tender at the center").assertIsDisplayed()
    }
}
