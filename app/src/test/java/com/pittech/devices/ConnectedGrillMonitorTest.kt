package com.pittech.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ConnectedGrillMonitorTest {
    private class Engine(id: String) : PolarisMonitorEngine {
        override val state = MutableStateFlow(PolarisMonitorState(authenticated = true,
            devices = listOf(PolarisDevice(id, "Private grill", null, null, null, null)), selectedDeviceId = id))
        var active = false
        var recordings = false
        var logins = 0
        var closed = false
        override fun setForeground(active: Boolean) { this.active = active }
        override fun setRecording(active: Boolean) { recordings = active; if (!active) state.value = state.value.copy(lockedDeviceId = null) }
        override fun lockDevice(id: String) { state.value = state.value.copy(lockedDeviceId = id) }
        override fun requestCode(email: String) {}
        override fun signIn(email: String, code: String) { logins++ }
        override fun reloadDevices() {}
        override fun selectDevice(id: String) { state.value = state.value.copy(selectedDeviceId = id) }
        override fun refresh() {}
        override fun signOut() { state.value = PolarisMonitorState(phase = PolarisPhase.SIGNED_OUT) }
        override fun close() { closed = true }
    }
    @Test fun onlySelectedProviderOwnsPollingAndHistoriesNeverMix() {
        val grillirg = Engine("grillirg-id")
        val traeger = Engine("traeger:id")
        val saved = mutableListOf<GrillProvider>()
        val hub = ConnectedGrillMonitor(mapOf(GrillProvider.GRILLIRG to grillirg, GrillProvider.TRAEGER to traeger),
            saveSelection = { saved += it }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            hub.setForeground(true)
            assertTrue(grillirg.active); assertFalse(traeger.active)
            hub.selectProvider(GrillProvider.TRAEGER)
            assertFalse(grillirg.active); assertTrue(traeger.active)
            assertEquals("traeger:id", hub.state.value.selectedDeviceId)
            grillirg.state.value = grillirg.state.value.copy(message = "old-provider-result", samples = listOf(PolarisSample(1, PolarisPayload(emptyMap(), emptyList(), 0, null))))
            assertFalse(hub.state.value.message.contains("old-provider-result"))
            assertTrue(hub.state.value.samples.isEmpty())
            assertEquals(listOf(GrillProvider.TRAEGER), saved)
        } finally { hub.close() }
        assertTrue(grillirg.closed); assertTrue(traeger.closed)
    }
    @Test fun attachmentLocksProviderAndAccountBeforeServiceStartsAndPauseUnlocks() {
        val grillirg = Engine("grillirg-id")
        val pitBoss = Engine("pitboss:id")
        val hub = ConnectedGrillMonitor(mapOf(GrillProvider.GRILLIRG to grillirg, GrillProvider.PIT_BOSS to pitBoss),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            hub.lockDevice("grillirg-id")
            hub.selectProvider(GrillProvider.PIT_BOSS)
            hub.signIn("other", "secret")
            assertEquals(GrillProvider.GRILLIRG, hub.state.value.provider); assertEquals(0, grillirg.logins)
            hub.setRecording(true)
            hub.setForeground(false)
            assertTrue(grillirg.recordings)
            hub.selectProvider(GrillProvider.PIT_BOSS)
            assertEquals(GrillProvider.GRILLIRG, hub.state.value.provider)
            hub.setRecording(false)
            hub.selectProvider(GrillProvider.PIT_BOSS)
            assertEquals(GrillProvider.PIT_BOSS, hub.state.value.provider)
            assertNull(hub.state.value.lockedDeviceId)
            assertNotEquals(CookTelemetryPolicy.deviceKey("pitboss:id"), CookTelemetryPolicy.deviceKey("traeger:id"))
        } finally { hub.close() }
    }
}

