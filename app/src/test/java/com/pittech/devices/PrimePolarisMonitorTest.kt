package com.pittech.devices

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PrimePolarisMonitorTest {
    private class Store(initial: PolarisSession? = PolarisSession("private-token")) : PolarisSessionStore {
        @Volatile var session = initial
        override fun load() = session
        override fun save(session: PolarisSession) { this.session = session }
        override fun clear() { session = null }
    }
    private class Backend : PolarisBackend {
        var deviceList = listOf(PolarisDevice("private-id", "Grill", null, null, null, null))
        var statusFailure: PolarisFailure? = null
        var readingFailure: PolarisFailure? = null
        var readingsGate: CompletableDeferred<PolarisResult<PolarisPayload>>? = null
        var readingCalls = 0
        var cancelled = false
        var signInCalls = 0
        override suspend fun requestCode(email: String) = PolarisResult(Unit)
        override suspend fun signIn(email: String, code: String): PolarisResult<PolarisSession> {
            signInCalls++
            return PolarisResult(PolarisSession("private-token"))
        }
        override suspend fun devices(session: PolarisSession) = PolarisResult(deviceList)
        override suspend fun status(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
            statusFailure?.let { throw it }
            return PolarisResult(PolarisPayload(mapOf("onlineStatus" to 0.0), listOf("onlineStatus"), 0, null))
        }
        override suspend fun readings(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
            readingCalls++
            readingFailure?.let { throw it }
            readingsGate?.let {
                try { return it.await() } catch (error: kotlinx.coroutines.CancellationException) { cancelled = true; throw error }
            }
            return PolarisResult(PolarisPayload(mapOf("furnaceTempMeasured" to 225.0, "tempUnit" to 0.0), listOf("furnaceTempMeasured", "tempUnit"), 0, 0))
        }
    }
    private suspend fun waitFor(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) delay(5) } }
    private fun monitor(backend: Backend, store: Store = Store()) = PrimePolarisMonitor(backend, store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), now = { 1_000_000L })

    @Test fun savedSessionDiscoversAndSelectsOnlyGrillWithoutBluetooth() = runBlocking {
        val backend = Backend()
        val monitor = monitor(backend)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.latest != null }
            assertEquals("private-id", monitor.state.value.selectedDeviceId)
            assertEquals("Grill online", monitor.state.value.onlineLabel(1_000_000))
            assertEquals(225.0, monitor.state.value.latest!!.payload.values["furnaceTempMeasured"]!!, 0.01)
            assertEquals(0, backend.signInCalls)
        } finally { monitor.close() }
    }

    @Test fun multipleGrillsRequireSelectionAndDoNotGuessAnIdentity() = runBlocking {
        val backend = Backend().apply { deviceList += PolarisDevice("second-id", "Second", null, null, null, null) }
        val monitor = monitor(backend)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.devices.size == 2 && !monitor.state.value.busy }
            assertNull(monitor.state.value.selectedDeviceId)
            assertEquals(0, backend.readingCalls)
            monitor.selectDevice("second-id")
            waitFor { monitor.state.value.latest != null }
            assertEquals("second-id", monitor.state.value.selectedDeviceId)
        } finally { monitor.close() }
    }

    @Test fun closingScreenCancelsInFlightResponseAndPreservesSession() = runBlocking {
        val gate = CompletableDeferred<PolarisResult<PolarisPayload>>()
        val backend = Backend().apply { readingsGate = gate }
        val store = Store()
        val monitor = monitor(backend, store)
        try {
            monitor.setForeground(true)
            waitFor { backend.readingCalls == 1 }
            monitor.setForeground(false)
            waitFor { backend.cancelled }
            gate.complete(PolarisResult(PolarisPayload(mapOf("furnaceTempMeasured" to 999.0), emptyList(), 0, null)))
            assertNull(monitor.state.value.latest)
            assertEquals(PolarisPhase.PAUSED, monitor.state.value.phase)
            assertNotNull(store.session)
        } finally { monitor.close() }
    }

    @Test fun statusEndpointFailureStillCollectsReadingsWithoutClaimingOnline() = runBlocking {
        val backend = Backend().apply { statusFailure = PolarisFailure(PolarisFailureKind.HTTP, 404) }
        val monitor = monitor(backend)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.nextPollAtMillis != null }
            assertNotNull(monitor.state.value.latest)
            assertNull(monitor.state.value.onlineStatus)
            assertEquals(1, monitor.state.value.failedRequests)
            assertEquals(1, monitor.state.value.consecutiveFailures)
            assertFalse(monitor.state.value.readingsAreOld(1_000_000))
        } finally { monitor.close() }
    }

    @Test fun readFailureRetainsPriorValuesAndRecoveryClearsStaleFlag() = runBlocking {
        val backend = Backend()
        val monitor = monitor(backend)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.latest != null }
            backend.readingFailure = PolarisFailure(PolarisFailureKind.NETWORK)
            monitor.refresh()
            waitFor { monitor.state.value.readingRequestFailed }
            assertEquals(225.0, monitor.state.value.latest!!.payload.values["furnaceTempMeasured"]!!, 0.01)
            assertTrue(monitor.state.value.readingsAreOld(1_000_000))
            backend.readingFailure = null
            monitor.refresh()
            waitFor { !monitor.state.value.readingRequestFailed && monitor.state.value.consecutiveFailures == 0 }
            assertFalse(monitor.state.value.readingsAreOld(1_000_000))
        } finally { monitor.close() }
    }

    @Test fun authLossStopsPollingAndClearsSavedCredentialsWithoutAutomaticLogin() = runBlocking {
        val backend = Backend().apply { readingFailure = PolarisFailure(PolarisFailureKind.AUTH, 200, -10108) }
        val store = Store()
        val monitor = monitor(backend, store)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.phase == PolarisPhase.SIGN_IN_REQUIRED }
            assertFalse(monitor.state.value.authenticated)
            assertNull(store.session)
            assertNull(monitor.state.value.nextPollAtMillis)
            assertEquals(0, backend.signInCalls)
            assertTrue(monitor.state.value.message.contains("elsewhere"))
        } finally { monitor.close() }
    }

    @Test fun rateLimitSkipsSecondEndpointAndUsesServerDelay() = runBlocking {
        val backend = Backend().apply { statusFailure = PolarisFailure(PolarisFailureKind.RATE_LIMIT, 429, retryAfterMillis = 90_000) }
        val monitor = monitor(backend)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.nextPollAtMillis != null }
            assertEquals(0, backend.readingCalls)
            assertEquals(1_090_000L, monitor.state.value.nextPollAtMillis)
        } finally { monitor.close() }
    }

    @Test fun signOutForgetsCredentialAndStopsMonitor() = runBlocking {
        val store = Store()
        val monitor = monitor(Backend(), store)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.latest != null }
            monitor.signOut()
            waitFor { store.session == null }
            assertFalse(monitor.state.value.authenticated)
            assertTrue(monitor.state.value.devices.isEmpty())
            assertNull(monitor.state.value.latest)
        } finally { monitor.close() }
    }
}
