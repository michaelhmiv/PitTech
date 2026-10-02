package com.pittech.ui

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.CookRecordingService
import com.pittech.MainActivity
import com.pittech.PitTechApplication
import com.pittech.devices.CookRecordingFakeBackend
import com.pittech.devices.CookRecordingFakeStore
import com.pittech.devices.PrimePolarisMonitor
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
        compose.onNodeWithTag("cook-quick-wrap").performClick()
        compose.onNodeWithTag("timeline-entry-save").performClick()
        compose.waitUntil(10_000) { runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().any { it.eventType == "wrap" } } }
        val wrapped = runBlocking(Dispatchers.IO) { app.database.cookDao().getAllTimelineEvents().single { it.eventType == "wrap" } }
        assertTrue(com.pittech.data.TemperatureContext.decode(wrapped.temperatureContextJson).any { it.name == "Chamber" })
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("event-temperature-context").assertExists()
    }
}
