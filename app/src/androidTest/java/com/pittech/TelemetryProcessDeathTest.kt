package com.pittech

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.*
import com.pittech.devices.*
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Separate staged instrumentation invocations allow the CI script to kill the real app process. */
class TelemetryProcessDeathTest {
    @Test fun preparePersistedBackgroundCook(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        app.stopService(Intent(app, CookRecordingService::class.java)); app.database.clearAllTables()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val marker = File(app.noBackupFilesDir, "telemetry-process-ci.marker"); marker.writeText("Explicit synthetic CI telemetry")
        val cook = app.cookRepository.startCook(NewCookDraft("CI synthetic telemetry restart"))
        val source = TelemetrySource("meater", "ci-synthetic-probe", "CI synthetic probe", channels = listOf(TelemetryChannel("internal", "Food", TelemetryKind.FOOD)))
        TelemetryRecordingRepository(app.database).attach(cook, source)
        app.telemetryRuntime.store.save(TelemetryProfile("telemetry-process-ci", "meater", "CI synthetic probe", sources = listOf(source), cookId = cook, monitoring = true, background = true, selectedDeviceIds = setOf(source.deviceId), intervalMillis = 15_000))
        assertEquals(1, app.database.cookDao().getAllCooks().size)
    }
    @Test fun validateRestoredCookWithoutOpeningAnActivity(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val previous = InstrumentationRegistry.getArguments().getString("previousReceipt")?.toLong() ?: error("CI must provide the pre-kill receipt")
        try {
            withTimeout(60_000) { while (!app.telemetryRuntime.serviceHosted.value || app.database.cookDao().getAllSensorReadings().none { it.measuredAtUtcMillis > previous }) delay(100) }
            assertEquals(1, app.database.cookDao().getAllCooks().size)
            assertEquals(1, app.database.cookDao().getAllDevices().size)
            val readings = app.database.cookDao().getAllSensorReadings()
            assertEquals(readings.size, readings.map { it.id }.toSet().size)
            assertTrue(app.telemetryRuntime.store.profiles.value.single().monitoring)
            val cook = app.database.cookDao().getAllCooks().single().id
            app.database.cookDao().finishCook(cook, System.currentTimeMillis())
            withTimeout(15_000) { while (app.telemetryRuntime.serviceHosted.value) delay(100) }
            val finished = app.database.cookDao().getAllSensorReadings().size
            delay(1_200)
            assertEquals(finished, app.database.cookDao().getAllSensorReadings().size)
        } finally {
            app.stopService(Intent(app, CookRecordingService::class.java))
            app.telemetryRuntime.store.remove("telemetry-process-ci")
            File(app.noBackupFilesDir, "telemetry-process-ci.marker").delete(); app.database.clearAllTables()
            File(app.noBackupFilesDir, "telemetry-process-ci.receipt").delete()
        }
    }
}
