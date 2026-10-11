package com.pittech

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookStatus
import com.pittech.devices.CookRecordingFakeBackend
import com.pittech.devices.CookRecordingFakeStore
import com.pittech.devices.GrillSamplingMode
import com.pittech.devices.GrillSamplingPolicy
import com.pittech.devices.PrimePolarisMonitor
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookRecordingRecoveryTest {
    @Test fun replacementRespectsPausedStoppedCompletedAndOnLogIntent() = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        app.stopService(Intent(app, CookRecordingService::class.java))
        withTimeout(10_000) { while (app.recordingServiceRunning.value) delay(50) }
        app.database.clearAllTables()
        val backend = CookRecordingFakeBackend()
        lateinit var monitor: PrimePolarisMonitor
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { monitor = PrimePolarisMonitor(backend, CookRecordingFakeStore()); app.grillMonitorForTests = monitor }
        try {
            val id = app.cookRepository.startCook(NewCookDraft("Recovery states"))
            app.recordingRepository.attach(id, backend.device, "°F")
            app.recordingRepository.pause(id)
            repeat(2) { CookRecordingRecovery.afterPackageReplacement(app) }
            assertFalse(app.recordingServiceRunning.value)
            assertEquals("paused", app.database.recordingDao().getRecording(id)!!.status)
            app.recordingRepository.stop(id)
            CookRecordingRecovery.afterPackageReplacement(app)
            assertEquals("stopped", app.database.recordingDao().getRecording(id)!!.status)
            app.recordingRepository.attach(id, backend.device, "°F", sampling = GrillSamplingPolicy(GrillSamplingMode.ON_LOG))
            repeat(2) { CookRecordingRecovery.afterPackageReplacement(app) }
            delay(500)
            assertTrue(app.loggingRecordingActive)
            assertFalse(app.recordingServiceRunning.value)
            val cook = app.database.cookDao().getCook(id)!!
            app.database.cookDao().updateCook(cook.copy(status = CookStatus.COMPLETED))
            CookRecordingRecovery.afterPackageReplacement(app)
            assertFalse(app.loggingRecordingActive)
            assertFalse(app.recordingServiceRunning.value)
        } finally {
            instrumentation.runOnMainSync { monitor.close(); app.grillMonitorForTests = null }
            app.database.clearAllTables()
        }
    }
}
