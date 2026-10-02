package com.pittech

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookRecordingEntity
import com.pittech.devices.*
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectedGrillRecordingServiceTest {
    @Test fun traegerRecordingKeepsProviderAndFourProbeBindingAfterActivityBackgrounds() = runBlocking {
        verify(GrillProvider.TRAEGER)
    }
    @Test fun pitBossRecordingKeepsProviderAndControllerBindingAfterActivityBackgrounds() = runBlocking {
        verify(GrillProvider.PIT_BOSS)
    }
    private suspend fun verify(provider: GrillProvider) {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        app.stopService(Intent(app, CookRecordingService::class.java))
        app.database.clearAllTables()
        val active = Backend(provider)
        val otherProvider = if (provider == GrillProvider.TRAEGER) GrillProvider.PIT_BOSS else GrillProvider.TRAEGER
        val inactive = Backend(otherProvider)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var hub: ConnectedGrillMonitor
        instrumentation.runOnMainSync {
            hub = ConnectedGrillMonitor(mapOf(
                provider to PrimePolarisMonitor(active, Store(PolarisSession("synthetic", selectedDeviceId = active.device.id)), provider = provider),
                otherProvider to PrimePolarisMonitor(inactive, Store(PolarisSession("synthetic", selectedDeviceId = inactive.device.id)), provider = otherProvider),
            ), initial = provider)
            app.grillMonitorForTests = hub
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        suspend fun waitFor(condition: suspend () -> Boolean) { withTimeout(25_000) { while (!condition()) delay(40) } }
        try {
            waitFor { hub.state.value.latest != null }
            val cook = app.cookRepository.startCook(NewCookDraft("Provider background cook"))
            app.recordingRepository.attach(cook, active.device, "°F")
            instrumentation.runOnMainSync { hub.lockDevice(active.device.id); CookRecordingService.start(app); hub.refresh() }
            waitFor { app.recordingServiceRunning.value && app.database.cookDao().getAllSensorReadings().isNotEmpty() }
            scenario.moveToState(Lifecycle.State.CREATED)
            val before = app.database.cookDao().getAllSensorReadings().size
            instrumentation.runOnMainSync { hub.selectProvider(otherProvider) }
            waitFor { app.database.cookDao().getAllSensorReadings().size > before }
            assertEquals(provider, hub.state.value.provider)
            assertEquals(active.device.id, hub.state.value.lockedDeviceId)
            assertEquals(0, inactive.reads)
            val lastReceipt = app.database.recordingDao().getRecording(cook)!!.lastReceivedAtUtcMillis
            assertEquals(6, app.database.cookDao().getAllSensorReadings().count { it.measuredAtUtcMillis == lastReceipt })
            instrumentation.runOnMainSync { hub.signOut() }
            waitFor { !app.recordingServiceRunning.value }
            assertEquals(CookRecordingEntity.PAUSED, app.database.recordingDao().getRecording(cook)!!.status)
            assertEquals(0, active.logins)
        } finally {
            app.stopService(Intent(app, CookRecordingService::class.java))
            scenario.close()
            instrumentation.runOnMainSync { hub.close(); app.grillMonitorForTests = null }
            app.database.clearAllTables()
        }
    }
    private class Store(private var value: PolarisSession?) : PolarisSessionStore {
        override fun load() = value
        override fun save(session: PolarisSession) { value = session }
        override fun clear() { value = null }
    }
    private class Backend(private val provider: GrillProvider) : PolarisBackend {
        val device = PolarisDevice(if (provider == GrillProvider.TRAEGER) "traeger:synthetic" else "pitboss:PBA-synthetic",
            "Synthetic grill", provider.label, null, null, null, 4)
        @Volatile var reads = 0
        var logins = 0
        override suspend fun requestCode(email: String) = PolarisResult(Unit)
        override suspend fun signIn(email: String, code: String): PolarisResult<PolarisSession> { logins++; error("No automatic sign-in") }
        override suspend fun devices(session: PolarisSession) = PolarisResult(listOf(device))
        override suspend fun status(session: PolarisSession, deviceId: String) = PolarisResult(PolarisPayload(emptyMap(), emptyList(), 0, null))
        override suspend fun readings(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
            reads++
            val payload = if (provider == GrillProvider.TRAEGER) TraegerBackend.parseStatus(JSONObject()
                .put("thingName", "synthetic").put("status", JSONObject().put("units", 1).put("connected", true)
                    .put("time", System.currentTimeMillis() / 1000).put("grill", 225).put("set", 250).put("probe_con", 1).put("probe", 150)))
            else {
                val bytes = MutableList(30) { 0 }; bytes[0] = 254; bytes[1] = 12; bytes[29] = 1
                for (offset in listOf(8,11,14,17,23,26)) { bytes[offset] = 2; bytes[offset+1] = 2; bytes[offset+2] = 5 }
                PitBossTelemetry.parse("PBA-synthetic", JSONObject().put("sc_12", bytes.joinToString("") { "%02x".format(it) }))
            }
            return PolarisResult(payload, null, null)
        }
    }
}

