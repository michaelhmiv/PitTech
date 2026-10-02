package com.pittech.ui

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
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
        compose.onNodeWithTag("cook-recording-toggle").performScrollTo().performClick()
        compose.waitUntil(20_000) { runBlocking(Dispatchers.IO) {
            val reattached = app.database.recordingDao().getRecording(firstRecording.cookId)!!
            reattached.deviceId != firstRecording.deviceId && app.database.cookDao().getAllSensorReadings().any { it.sourceDeviceId == reattached.deviceId }
        } }
        compose.onNodeWithTag("cook-quick-wrap").performClick()
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().any { it.eventType == "wrap" } } }
        val wrapped = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().single { it.eventType == "wrap" } }
        assertTrue(com.pittech.data.TemperatureContext.decode(wrapped.temperatureContextJson).any { it.name == "Chamber" })
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("event-temperature-context").assertExists()
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
        compose.onNodeWithTag("grill-sampling").performScrollTo().performClick()
        compose.onNodeWithTag("grill-sampling-on-log").performClick()
        compose.onNodeWithTag("recording-log-only-info").assertExists()
        compose.onNodeWithTag("cook-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.recordingDao().getActiveRecording()?.samplingMode == "on_log" } }
        assertFalse(app.recordingServiceRunning.value)
        assertTrue(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllSensorReadings().isEmpty() })
        val before = backend.calls.get()
        compose.onNodeWithTag("cook-quick-wrap").performClick()
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().any { it.eventType == "wrap" } } }
        val wrap = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().single { it.eventType == "wrap" } }
        assertTrue(backend.calls.get() > before)
        assertTrue(com.pittech.data.TemperatureContext.decode(wrap.temperatureContextJson).isNotEmpty())
        assertTrue(runBlocking(Dispatchers.IO) { app.database.cookDao().getAllSensorReadings().all { it.samplingIntervalMillis == 0L } })
        assertNull(fake!!.state.value.nextPollAtMillis)
        compose.onNodeWithTag("grill-sampling").performScrollTo().performClick()
        compose.onNodeWithTag("grill-sampling-60000").performClick()
        compose.waitUntil(15_000) { app.recordingServiceRunning.value }
        compose.onNodeWithTag("grill-sampling").performScrollTo().performClick()
        compose.onNodeWithTag("grill-sampling-on-log").performClick()
        compose.waitUntil(15_000) { !app.recordingServiceRunning.value && fake!!.state.value.sampling.mode == com.pittech.devices.GrillSamplingMode.ON_LOG }
        backend.failure = PolarisFailure(PolarisFailureKind.NETWORK)
        compose.onNodeWithTag("cook-grill-read-now").performScrollTo().performClick()
        compose.waitUntil(10_000) { fake!!.state.value.readingRequestFailed }
        compose.onNodeWithTag("cook-quick-rest").performClick()
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().any { it.eventType == "rest" } } }
        val rest = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().single { it.eventType == "rest" } }
        assertNull(rest.temperatureContextJson)
        assertFalse(app.recordingServiceRunning.value)
        assertNull(fake!!.state.value.nextPollAtMillis)
        assertEquals(com.pittech.data.CookRecordingEntity.RECORDING, runBlocking(Dispatchers.IO) { app.database.recordingDao().getActiveRecording()!!.status })
    }

}
