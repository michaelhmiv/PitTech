package com.pittech

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.devices.CookRecordingFakeBackend
import com.pittech.devices.CookRecordingFakeStore
import com.pittech.devices.PrimePolarisMonitor
import com.pittech.devices.PolarisFailure
import com.pittech.devices.PolarisFailureKind
import com.pittech.domain.NewCookDraft
import com.pittech.data.CookRecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookRecordingServiceTest {
    @Test fun backgroundCookKeepsRecordingAndAuthenticationFailureStopsWithoutLoginLoops() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        app.stopService(Intent(app, CookRecordingService::class.java))
        app.database.clearAllTables()
        val backend = CookRecordingFakeBackend()
        lateinit var monitor: PrimePolarisMonitor
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { monitor = PrimePolarisMonitor(backend, CookRecordingFakeStore()); app.grillMonitorForTests = monitor }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        suspend fun waitUntil(timeout: Long = 25_000L, condition: suspend () -> Boolean) { withTimeout(timeout) { while (!condition()) delay(40L) } }
        try {
            waitUntil { monitor.state.value.latest != null }
            val id = app.cookRepository.startCook(NewCookDraft("Background recording"))
            app.recordingRepository.attach(id, backend.device, "°F")
            instrumentation.runOnMainSync { CookRecordingService.start(app); monitor.refresh() }
            waitUntil { app.recordingServiceRunning.value && app.database.cookDao().getAllSensorReadings().isNotEmpty() }
            scenario.moveToState(Lifecycle.State.CREATED)
            val before = app.database.cookDao().getAllSensorReadings().size
            waitUntil { app.database.cookDao().getAllSensorReadings().size > before }
            assertTrue(app.recordingServiceRunning.value)
            assertEquals(CookRecordingEntity.RECORDING, app.database.recordingDao().getRecording(id)!!.status)
            assertEquals(backend.device.id, monitor.state.value.lockedDeviceId)
            // Expiry/displacement is terminal for this recording; no automatic login or email.
            backend.failure = PolarisFailure(PolarisFailureKind.AUTH, apiCode = -10108)
            instrumentation.runOnMainSync { monitor.refresh() }
            waitUntil { !app.recordingServiceRunning.value }
            assertEquals(CookRecordingEntity.PAUSED, app.database.recordingDao().getRecording(id)!!.status)
            assertTrue(app.database.recordingDao().getRecording(id)!!.message.contains("Sign in"))
        } finally {
            app.stopService(Intent(app, CookRecordingService::class.java))
            scenario.close()
            instrumentation.runOnMainSync { monitor.close(); app.grillMonitorForTests = null }
            app.database.clearAllTables()
        }
    }
}
