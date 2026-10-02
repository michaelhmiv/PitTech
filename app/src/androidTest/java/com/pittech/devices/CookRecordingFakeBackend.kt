package com.pittech.devices

import java.util.concurrent.atomic.AtomicInteger

internal class CookRecordingFakeBackend : PolarisBackend {
    val calls = AtomicInteger(0)
    val device = PolarisDevice("synthetic-recording-device", "Test grill", "Test", "P7", null, "test")
    @Volatile var failure: PolarisFailure? = null
    override suspend fun requestCode(email: String) = PolarisResult(Unit)
    override suspend fun signIn(email: String, code: String) = PolarisResult(PolarisSession("synthetic-token"))
    override suspend fun devices(session: PolarisSession) = PolarisResult(listOf(device))
    override suspend fun status(session: PolarisSession, deviceId: String) = PolarisResult(PolarisPayload(mapOf("onlineStatus" to 0.0), emptyList(), 0, 0))
    override suspend fun readings(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
        failure?.let { throw it }
        val call = calls.incrementAndGet()
        return PolarisResult(PolarisPayload(mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to (225.0 + call), "furnaceTempSetting" to 250.0, "probeP1Measured" to 150.0, "probeP2Measured" to 0.0), emptyList(), 0, 0))
    }
}
internal class CookRecordingFakeStore : PolarisSessionStore {
    private var session: PolarisSession? = PolarisSession("synthetic-token")
    override fun load() = session
    override fun save(session: PolarisSession) { this.session = session }
    override fun clear() { session = null }
}
