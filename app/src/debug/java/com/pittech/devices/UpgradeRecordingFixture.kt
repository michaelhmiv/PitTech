package com.pittech.devices

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only, opt-in deterministic controller that also survives package/process replacement. */
internal object UpgradeRecordingFixture {
    @JvmStatic fun create(context: Context): PolarisMonitorEngine? {
        if (!File(context.filesDir, "upgrade-recording-fixture.enabled").exists()) return null
        val backend = object : PolarisBackend {
            val calls = AtomicInteger()
            val device = PolarisDevice("upgrade-fixture-device", "Upgrade test grill", "Test", "P7", null, "test")
            override suspend fun requestCode(email: String) = PolarisResult(Unit)
            override suspend fun signIn(email: String, code: String) = PolarisResult(PolarisSession("fixture-session"))
            override suspend fun devices(session: PolarisSession) = PolarisResult(listOf(device))
            override suspend fun status(session: PolarisSession, deviceId: String) = PolarisResult(PolarisPayload(mapOf("onlineStatus" to 0.0), emptyList(), 0, 0))
            override suspend fun readings(session: PolarisSession, deviceId: String) = PolarisResult(PolarisPayload(
                mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to (225.0 + calls.incrementAndGet() % 10),
                    "furnaceTempSetting" to 250.0, "probeP1Measured" to 155.0), emptyList(), 0, 0))
        }
        val store = object : PolarisSessionStore {
            override fun load() = PolarisSession("fixture-session")
            override fun save(session: PolarisSession) {}
            override fun clear() {}
        }
        return PrimePolarisMonitor(backend, store)
    }
}
