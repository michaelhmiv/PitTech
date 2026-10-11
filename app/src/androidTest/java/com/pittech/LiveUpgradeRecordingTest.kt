package com.pittech

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookRecordingEntity
import com.pittech.devices.GrillSamplingPolicy
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The preparation method is also compiled against the previous APK, with only the fixture overlaid. */
@RunWith(AndroidJUnit4::class)
class LiveUpgradeRecordingTest {
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(45_000L) {
        while (!condition()) delay(100L)
    }

    @Test fun prepareRunningCook(): Unit = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        File(app.filesDir, "upgrade-recording-fixture.enabled").writeText("enabled")
        app.database.clearAllTables()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await { app.grillMonitor.state.value.selectedDevice != null }
            val cookId = app.cookRepository.startCook(NewCookDraft("Live update cook",
                dishes = listOf(DishDraft("Brisket", "beef"))))
            val dish = app.database.cookDao().observeCooks().first().single().dishes.single()
            val at = System.currentTimeMillis() - 60_000L
            val image = File(app.filesDir, "upgrade-photo.jpg")
            image.outputStream().use { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val saved = app.cookRepository.addTimelineEventWithPhoto(cookId, dish.id, "note", "Wrapped",
                "Photo and description survive the update.", at, Uri.fromFile(image).toString(), "Bark")
            assertTrue(saved.photoAttached)
            app.cookRepository.addManualTemperature(cookId, dish.id, "Manual food", "food", 155.0, "°F", at)
            app.cookRepository.addTarget(cookId, dish.id, "food", 203.0, "°F", null)
            val device = app.grillMonitor.state.value.selectedDevice!!
            app.recordingRepository.attach(cookId, device, "°F", mapOf("probe1" to dish.id),
                sampling = GrillSamplingPolicy(intervalMillis = 15_000L))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { CookRecordingService.start(app) }
            await { app.recordingServiceRunning.value && app.database.recordingDao().getRecording(cookId)?.lastReceivedAtUtcMillis != null }
            val cook = app.database.cookDao().getCook(cookId)!!
            val recording = app.database.recordingDao().getRecording(cookId)!!
            File(app.filesDir, "upgrade-recording-witness.json").writeText(JSONObject()
                .put("cookId", cookId).put("startedAt", cook.startedAtUtcMillis).put("eventId", saved.event.id)
                .put("occurredAt", at).put("dishId", dish.id).put("deviceId", recording.deviceId)
                .put("resumedAt", recording.resumedAtUtcMillis).toString())
        } finally { scenario.close() }
    }

    @Test fun recordingRecoversWithoutOpeningActivity(): Unit = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val witness = JSONObject(File(app.filesDir, "upgrade-recording-witness.json").readText())
        val id = witness.getString("cookId")
        val installedAfter = File(app.filesDir, "upgrade-install-started.txt").readText().trim().toLong()
        // No ActivityScenario or Compose rule: only the package receiver may recover the service.
        // The shell already proved foreground service + accepted receipt without an activity.
        // Starting Android instrumentation recreates the target process; read the durable DB proof.
        assertTrue(File(app.filesDir, "upgrade-recording-receipt.txt").readText().trim().toLong() > installedAfter)
        await { (app.database.recordingDao().getRecording(id)?.lastReceivedAtUtcMillis ?: 0) > installedAfter }
        val cook = app.database.cookDao().getCook(id)!!
        val recording = app.database.recordingDao().getRecording(id)!!
        assertEquals(witness.getLong("startedAt"), cook.startedAtUtcMillis)
        assertEquals(witness.getLong("resumedAt"), recording.resumedAtUtcMillis)
        assertEquals(witness.getString("deviceId"), recording.deviceId)
        assertEquals(15_000L, recording.samplingIntervalMillis)
        assertEquals(CookRecordingEntity.RECORDING, recording.status)
        assertEquals(witness.getLong("occurredAt"), app.database.cookDao().getTimelineEvent(witness.getString("eventId"))!!.occurredAtUtcMillis)
        val detail = app.cookRepository.observeCookDetail(id).first()!!
        assertEquals(1, detail.photos.size)
        assertNotNull(app.cookRepository.readPhoto(detail.photos.single().relativePath))
        assertEquals(1, detail.targets.size)
        assertTrue(detail.readings.any { it.source == "manual" && it.value == 155.0 })
        assertEquals(witness.getString("dishId"), detail.assignments.first { it.endedAtUtcMillis == null && it.probeId.endsWith(":probe1") }.dishId)
        assertEquals(detail.readings.size, detail.readings.map { it.id }.distinct().size)
        app.recordingRepository.pause(id)
        await { !app.recordingServiceRunning.value }
        File(app.filesDir, "upgrade-recording-fixture.enabled").delete()
    }
}
