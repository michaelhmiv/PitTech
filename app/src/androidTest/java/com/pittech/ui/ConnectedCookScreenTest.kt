package com.pittech.ui

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.CookRecordingService
import com.pittech.MainActivity
import com.pittech.PitTechApplication
import com.pittech.devices.CookRecordingFakeBackend
import com.pittech.devices.CookRecordingFakeStore
import com.pittech.devices.PrimePolarisMonitor
import com.pittech.devices.PolarisFailure
import com.pittech.devices.PolarisFailureKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConnectedCookScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var fake: PrimePolarisMonitor? = null
    private val app get() = compose.activity.application as PitTechApplication
    @After fun cleanup() {
        // Keep the UI and binding evidence before clearing the fixture, including
        // when a real touch fails to reach a control beneath the save snackbar.
        runCatching {
            compose.onRoot(useUnmergedTree = true).printToLog("ConnectedCookEvidence")
            val file = File(app.filesDir, "pittech-ui-test/connected-cook-final.png").apply { parentFile?.mkdirs() }
            compose.onRoot().captureToImage().asAndroidBitmap().apply {
                file.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; recycle()
            }
            val state = fake?.state?.value
            val recording = runBlocking(Dispatchers.IO) { app.database.recordingDao().getActiveRecording() }
            android.util.Log.i("ConnectedCookEvidence", "mode=${state?.sampling?.mode} phase=${state?.phase} service=${app.recordingServiceRunning.value} active=${recording != null} bindingMatches=${recording?.controllerKey == state?.selectedDeviceId?.let(com.pittech.devices.CookTelemetryPolicy::deviceKey)} received=${recording?.lastReceivedAtUtcMillis}")
        }
        app.loggingRecordingActive = false
        app.stopService(Intent(app, CookRecordingService::class.java))
        compose.runOnUiThread { fake?.close(); app.grillMonitorForTests = null }
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
    }
    @Test fun startCookRecordingShowsLiveValuesAndWrapEntriesKeepTemperatureContext() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        val backend = CookRecordingFakeBackend()
        compose.runOnUiThread { fake = PrimePolarisMonitor(backend, CookRecordingFakeStore()); app.grillMonitorForTests = fake }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { fake?.state?.value?.selectedDevice != null }
        compose.onNodeWithTag("start-cook").performClick()
        compose.onNodeWithTag("cook-record-grill").performScrollTo().performClick()
        compose.onNodeWithTag("cook-save").performClick()
        compose.waitUntil(20_000) {
            runBlocking(Dispatchers.IO) { app.database.cookDao().getAllSensorReadings().isNotEmpty() }
        }
        dismissCookNotice()
        // Slice 2 (#172 PL-04): the grill panel is a default-expanded Live
        // panel now; the old "Cook tools" toggle no longer exists.
        compose.onNodeWithTag("cook-grill-live").assertExists()
        compose.onNodeWithTag("cook-reading-chamber").assertExists()
        val screenshot = File(app.filesDir, "pittech-ui-test/connected-cook-live.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("cook-grill-live").performScrollTo()
        compose.onRoot().captureToImage().asAndroidBitmap().apply { screenshot.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; recycle() }
        // Imported recordings have no account binding and stay stopped. Reattach explicitly.
        val firstRecording = runBlocking(Dispatchers.IO) { app.database.recordingDao().getActiveRecording()!! }
        runBlocking(Dispatchers.IO) { app.recordingRepository.stop(firstRecording.cookId) }
        compose.waitUntil(10_000) { !app.recordingServiceRunning.value }
        runBlocking(Dispatchers.IO) { app.database.recordingDao().saveRecording(firstRecording.copy(controllerKey = "", status = com.pittech.data.CookRecordingEntity.STOPPED)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Attach grill & record").fetchSemanticsNodes().isNotEmpty() }
        dismissCookNotice()
        compose.onNodeWithTag("cook-recording-toggle").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(20_000) { runBlocking(Dispatchers.IO) {
            val reattached = app.database.recordingDao().getRecording(firstRecording.cookId)!!
            reattached.deviceId != firstRecording.deviceId && app.database.cookDao().getAllSensorReadings().any { it.sourceDeviceId == reattached.deviceId }
        } }
        logInitialFoodOn()
        openAction("wrap")
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().any { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "wrap" } } }
        val wrapped = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().single { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "wrap" } }
        assertTrue(com.pittech.data.TemperatureContext.decode(wrapped.temperatureContextJson).any { it.name == "Chamber" })
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("timeline-details-toggle-" + wrapped.id).performScrollTo().performClick()
        compose.onNode(hasTestTag("event-temperature-context") and hasAnyAncestor(hasTestTag("timeline-entry-${wrapped.id}")), useUnmergedTree = true).assertExists()
    }
    @Test fun loggingOnlyQueriesForANoteSavesFailuresAndSwitchesTheForegroundServiceOff() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        val backend = CookRecordingFakeBackend()
        compose.runOnUiThread { fake = PrimePolarisMonitor(backend, CookRecordingFakeStore()); app.grillMonitorForTests = fake }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { fake?.state?.value?.selectedDevice != null }
        compose.onNodeWithTag("start-cook").performClick()
        compose.onNodeWithTag("cook-record-grill").performScrollTo().performClick()
        compose.onNodeWithTag("recording-power-warning").assertExists()
        selectSampling("grill-sampling-on-log")
        compose.onNodeWithTag("recording-log-only-info").assertExists()
        compose.onNodeWithTag("cook-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.recordingDao().getActiveRecording()?.samplingMode == "on_log" } }
        assertFalse(app.recordingServiceRunning.value)
        assertTrue(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllSensorReadings().isEmpty() })
        // Slice 2 (#172 PL-04): grill controls live in the default-expanded
        // Grill & recording panel; no tools toggle to open first.
        val before = backend.calls.get()
        logInitialFoodOn()
        openAction("wrap")
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().any { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "wrap" } } }
        val wrap = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().single { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "wrap" } }
        assertTrue(backend.calls.get() > before)
        assertTrue(com.pittech.data.TemperatureContext.decode(wrap.temperatureContextJson).isNotEmpty())
        assertTrue(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllSensorReadings().all { it.samplingIntervalMillis == 0L } })
        assertNull(fake!!.state.value.nextPollAtMillis)
        selectSampling("grill-sampling-60000")
        compose.waitUntil(15_000) { app.recordingServiceRunning.value }
        selectSampling("grill-sampling-on-log")
        compose.waitUntil(15_000) { !app.recordingServiceRunning.value && fake!!.state.value.sampling.mode == com.pittech.devices.GrillSamplingMode.ON_LOG }
        backend.failure = PolarisFailure(PolarisFailureKind.NETWORK)
        compose.onNodeWithTag("cook-grill-read-now").performScrollTo().performClick()
        compose.waitUntil(10_000) { fake!!.state.value.readingRequestFailed }
        openAction("rest_start")
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().any { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "rest_start" } } }
        val rest = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().single { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "rest_start" } }
        assertNull(rest.temperatureContextJson)
        assertFalse(app.recordingServiceRunning.value)
        assertNull(fake!!.state.value.nextPollAtMillis)
        assertEquals(com.pittech.data.CookRecordingEntity.RECORDING, runBlocking(Dispatchers.IO) { app.database.recordingDao().getActiveRecording()!!.status })
        backend.failure = PolarisFailure(PolarisFailureKind.AUTH, apiCode = -10108)
        openAction("food_on")
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().count { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "food_on" } == 2 } }
        assertNull(runBlocking(Dispatchers.IO) { app.database.recordingDao().getActiveRecording() })
        assertNull(fake!!.state.value.lockedDeviceId)
        assertFalse(fake!!.state.value.authenticated)
        assertFalse(app.recordingServiceRunning.value)
    }

    private fun openAction(action: String) {
        dismissCookNotice()
        compose.onNodeWithTag("timeline-add").performClick()
        compose.onNodeWithTag("timeline-entry-more-details").performScrollTo().performClick()
        compose.onNodeWithTag("timeline-entry-type").performScrollTo().performClick()
        val label = com.pittech.domain.PlaybookCodec.actions.getValue(action).lowercase(java.util.Locale.ROOT).replace(' ', '-')
        compose.onNodeWithTag("timeline-entry-type-option-" + label).performClick()
    }

    private fun logInitialFoodOn() {
        dismissCookNotice()
        openAction("food_on")
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().count { com.pittech.domain.PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "food_on" } == 1 } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("timeline-add") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        dismissCookNotice()
    }

    private fun selectSampling(optionTag: String) {
        // Saving a note includes asynchronous Room work; a database row can
        // arrive before the screen releases its busy state.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("grill-sampling") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        dismissCookNotice()
        compose.onNodeWithTag("grill-sampling").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(optionTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(optionTag).performClick()
    }

    private fun dismissCookNotice() {
        // performScrollTo places a control at the viewport edge. The transient
        // save snackbar overlays that edge, especially with enlarged text.
        // Dismiss it using the same visible action available to the user.
        val dismiss = compose.onAllNodesWithContentDescription("Dismiss")
        if (dismiss.fetchSemanticsNodes().isNotEmpty()) dismiss[0].performClick()
        compose.waitUntil(5_000) { dismiss.fetchSemanticsNodes().isEmpty() }
    }

    @Test fun alertNotificationTapOpensAlertsSheetAtTheFiringRule() {
        // PL-03 (#172): seed an active cook with one firing alert rule, then
        // deliver the same intent the alert notification's content intent
        // carries — cook id PLUS the firing rule's record id.
        val rule = com.pittech.domain.CookAlertRule(type = "target", targetF = 203.0)
        val recordId = "alert:${rule.id}"
        val cookId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val cook = app.cookRepository.startCook(com.pittech.domain.NewCookDraft("Alert routing"))
            app.database.companionDao().put(com.pittech.data.CompanionRecord(
                recordId, "alert", cook, "Brisket target",
                com.pittech.domain.CookAlertEngine.encode(rule, com.pittech.domain.CookAlertState(active = true)),
                System.currentTimeMillis(), System.currentTimeMillis()))
            cook
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.onNewIntent(Intent(activity, MainActivity::class.java)
                .putExtra(com.pittech.CookReminderNotifications.EXTRA_COOK_ID, cookId)
                .putExtra(com.pittech.CookAlertNotifications.EXTRA_ALERT_ID, recordId))
        }
        // The Live Zone strip shows the needs-attention count without
        // opening anything.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("live-alerts-strip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alerts · 1 needs attention", substring = true).assertExists()
        // The Alerts sheet opens at the firing rule, highlighted.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("alert-focus-$recordId").fetchSemanticsNodes().isNotEmpty() }
        // Acknowledging in the sheet confirms on Live: the strip names it.
        compose.onNodeWithText("Acknowledge").performClick()
        compose.waitUntil(15_000) {
            runBlocking(Dispatchers.IO) {
                val record = app.database.companionDao().get(recordId)
                record != null && com.pittech.domain.CookAlertEngine.decode(record.payload).second.acknowledged
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Acknowledged: Brisket target", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

}
