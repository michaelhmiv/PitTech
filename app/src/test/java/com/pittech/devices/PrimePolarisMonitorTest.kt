package com.pittech.devices

import kotlinx.coroutines.async
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
        var discoveryFailure: PolarisFailure? = null
        var readingFailure: PolarisFailure? = null
        var devicesGate: CompletableDeferred<PolarisResult<List<PolarisDevice>>>? = null
        var deviceCalls = 0
        var readingsGate: CompletableDeferred<PolarisResult<PolarisPayload>>? = null
        var readingCalls = 0
        var disconnects = 0
        override fun disconnect() { disconnects++ }
        val readingDeviceIds = mutableListOf<String>()
        var cancelled = false
        var signInCalls = 0
        var refreshCalls = 0
        var payload: PolarisPayload? = null
        var refreshFailure: PolarisFailure? = null
        var refreshGate: CompletableDeferred<Unit>? = null
        val tokensUsed = mutableListOf<String>()
        override suspend fun refreshSession(session: PolarisSession): PolarisResult<PolarisSession> {
            refreshCalls++
            refreshGate?.await()
            refreshFailure?.let { throw it }
            return PolarisResult(PolarisSession("renewed-token", 4_000_000L, session.selectedDeviceId, session.refreshToken))
        }
        override suspend fun requestCode(email: String) = PolarisResult(Unit)
        override suspend fun signIn(email: String, code: String): PolarisResult<PolarisSession> {
            signInCalls++
            return PolarisResult(PolarisSession("private-token"))
        }
        override suspend fun devices(session: PolarisSession): PolarisResult<List<PolarisDevice>> {
            deviceCalls++
            discoveryFailure?.let { throw it }
            return devicesGate?.await() ?: PolarisResult(deviceList)
        }
        override suspend fun status(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
            statusFailure?.let { throw it }
            return PolarisResult(PolarisPayload(mapOf("onlineStatus" to 0.0), listOf("onlineStatus"), 0, null))
        }
        override suspend fun readings(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
            readingCalls++
            tokensUsed += session.token
            readingDeviceIds += deviceId
            readingFailure?.let { throw it }
            readingsGate?.let {
                try { return it.await() } catch (error: kotlinx.coroutines.CancellationException) { cancelled = true; throw error }
            }
            return PolarisResult(payload ?: PolarisPayload(mapOf("furnaceTempMeasured" to 225.0, "tempUnit" to 0.0), listOf("furnaceTempMeasured", "tempUnit"), 0, 0))
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

    @Test fun resumeWaitsForDeviceDiscoveryBeforeRestartingPolls() = runBlocking {
        val backend = Backend()
        val monitor = monitor(backend)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.latest != null }
            val replacement = PolarisDevice("replacement-id", "Replacement", null, null, null, null)
            val gate = CompletableDeferred<PolarisResult<List<PolarisDevice>>>()
            backend.devicesGate = gate
            monitor.reloadDevices()
            waitFor { backend.deviceCalls == 2 }
            monitor.setForeground(false)
            monitor.setForeground(true)
            assertEquals(1, backend.readingCalls)
            gate.complete(PolarisResult(listOf(replacement)))
            waitFor { monitor.state.value.selectedDeviceId == replacement.id && monitor.state.value.latest != null }
            assertEquals(listOf("private-id", "replacement-id"), backend.readingDeviceIds)
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
            assertEquals(1_120_000L, monitor.state.value.nextPollAtMillis)
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
    @Test fun recordingOwnsPollingAcrossScreenClosureAndLocksTheGrill() = runBlocking {
        val backend = Backend().apply { deviceList += PolarisDevice("second-id", "Second", null, null, null, null) }
        val monitor = monitor(backend)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.devices.size == 2 && !monitor.state.value.busy }
            monitor.selectDevice("private-id")
            waitFor { monitor.state.value.latest != null }
            monitor.lockDevice("private-id")
            monitor.setRecording(true)
            monitor.setForeground(false)
            val before = backend.readingCalls
            monitor.refresh()
            waitFor { backend.readingCalls > before }
            monitor.selectDevice("second-id")
            assertEquals("private-id", monitor.state.value.selectedDeviceId)
            monitor.setRecording(false)
            assertEquals(PolarisPhase.PAUSED, monitor.state.value.phase)
            assertNull(monitor.state.value.lockedDeviceId)
        } finally { monitor.close() }
    }

    @Test fun restoredExpiredTraegerSessionRefreshesOnceAndPersistsWithoutPassword() = runBlocking {
        val renewal = CompletableDeferred<Unit>()
        val backend = Backend().apply { refreshGate = renewal }
        val store = Store(PolarisSession("old-token", 999L, "private-id", "private-refresh"))
        val monitor = PrimePolarisMonitor(backend, store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), now = { 1_000_000L }, provider = GrillProvider.TRAEGER)
        try {
            monitor.setRecording(true)
            waitFor { backend.refreshCalls == 1 }
            assertEquals(PolarisPhase.DISCOVERING, monitor.state.value.phase)
            assertTrue(monitor.state.value.authenticated)
            assertTrue(monitor.state.value.devices.isEmpty())
            assertEquals(0, backend.deviceCalls)
            renewal.complete(Unit)
            waitFor { monitor.state.value.latest != null }
            assertEquals(1, backend.refreshCalls)
            assertEquals(listOf("renewed-token"), backend.tokensUsed)
            assertEquals("private-refresh", store.session!!.refreshToken)
            assertEquals("private-id", store.session!!.selectedDeviceId)
            assertEquals(GrillProvider.TRAEGER, monitor.state.value.provider)
            assertEquals(0, backend.signInCalls)
        } finally { monitor.close() }
    }
    @Test fun repeatedOutOfOrderAndStaleDeviceReportsCannotRefreshReceiptHistory() = runBlocking {
        var clock = 1_000_000L
        val backend = Backend().apply { payload = PolarisPayload(mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to 225.0), emptyList(), 0, null, reportedAtMillis = clock) }
        val monitor = PrimePolarisMonitor(backend, Store(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), now = { clock }, provider = GrillProvider.TRAEGER)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.samples.size == 1 }
            clock += 15_000
            monitor.refresh()
            waitFor { backend.readingCalls >= 2 && monitor.state.value.nextPollAtMillis != null }
            assertEquals(1, monitor.state.value.samples.size)
            assertEquals(1_000_000L, monitor.state.value.latest!!.fetchedAtMillis)
            clock += 60_000
            monitor.refresh()
            waitFor { monitor.state.value.readingRequestFailed }
            assertEquals(1, monitor.state.value.samples.size)
            assertTrue(monitor.state.value.readingsAreOld(clock))
            backend.payload = backend.payload!!.copy(reportedAtMillis = clock)
            monitor.refresh()
            waitFor { monitor.state.value.samples.size == 2 }
            assertFalse(monitor.state.value.readingsAreOld(clock))
        } finally { monitor.close() }
    }
    @Test fun invalidRefreshTokenStopsPollingAndDisconnectsSavedSession() = runBlocking {
        val backend = Backend().apply { refreshFailure = PolarisFailure(PolarisFailureKind.AUTH, 400) }
        val store = Store(PolarisSession("old-token", 999L, "private-id", "private-refresh"))
        val monitor = PrimePolarisMonitor(backend, store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), now = { 1_000_000L }, provider = GrillProvider.TRAEGER)
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.phase == PolarisPhase.SIGN_IN_REQUIRED }
            assertNull(store.session)
            assertEquals(0, backend.readingCalls)
            assertEquals(0, backend.signInCalls)
            assertTrue(monitor.state.value.message.contains("Traeger"))
        } finally { monitor.close() }
    }
    @Test fun restoredRecordingRetriesTemporaryDiscoveryFailureWithoutLosingCredentialOrLoggingIn() = runBlocking {
        val retry = CompletableDeferred<Unit>()
        val backend = Backend().apply { discoveryFailure = PolarisFailure(PolarisFailureKind.NETWORK) }
        val store = Store()
        var pause = 0L
        val monitor = PrimePolarisMonitor(backend, store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { 1_000_000L }, provider = GrillProvider.TRAEGER, discoveryWait = { pause = it; retry.await() })
        try {
            monitor.setRecording(true)
            waitFor { monitor.state.value.nextPollAtMillis != null }
            assertEquals(PolarisPhase.DISCOVERING, monitor.state.value.phase)
            assertNotNull(store.session)
            assertEquals(120_000L, pause)
            assertEquals(0, backend.signInCalls)
            backend.discoveryFailure = null
            retry.complete(Unit)
            waitFor { monitor.state.value.latest != null }
            assertEquals(2, backend.deviceCalls)
            assertEquals(0, monitor.state.value.consecutiveFailures)
            assertEquals(0, backend.signInCalls)
        } finally { monitor.close() }
    }

    @Test fun loggingOnlyWaitsForAnActionAndDisconnectsWithoutAnyScheduledWork() = runBlocking {
        val backend = Backend()
        val monitor = PrimePolarisMonitor(backend, Store(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { 1_000_000L }, initialSampling = GrillSamplingPolicy(GrillSamplingMode.ON_LOG),
            pollWait = { error("Logging-only must never schedule a wait") })
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.message.startsWith("Temperature queries wait") }
            assertEquals(0, backend.readingCalls)
            assertNull(monitor.state.value.nextPollAtMillis)
            val disconnected = backend.disconnects
            val value = withTimeout(2_000L) { monitor.snapshot() }!!
            assertEquals(0L, value.samplingIntervalMillis)
            assertEquals(1, backend.readingCalls)
            assertTrue(backend.disconnects > disconnected)
            assertNull(monitor.state.value.nextPollAtMillis)
            monitor.setRecording(true)
            monitor.setForeground(false)
            delay(50)
            assertEquals(1, backend.readingCalls)
            assertNull(monitor.state.value.nextPollAtMillis)
        } finally { monitor.close() }
    }

    @Test fun loggingOnlyFailuresAndRepeatedNativeReportsFinishWithoutRetries() = runBlocking {
        var clock = 1_000_000L
        val backend = Backend().apply { payload = PolarisPayload(mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to 225.0), emptyList(), 0, null, reportedAtMillis = clock) }
        val monitor = PrimePolarisMonitor(backend, Store(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { clock }, initialSampling = GrillSamplingPolicy(GrillSamplingMode.ON_LOG),
            pollWait = { error("Unexpected retry timer") })
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.message.startsWith("Temperature queries wait") }
            assertNotNull(monitor.snapshot())
            clock += 1_000L
            assertNull(withTimeout(2_000L) { monitor.snapshot() })
            assertEquals(2, backend.readingCalls)
            backend.readingFailure = PolarisFailure(PolarisFailureKind.NETWORK)
            assertNull(withTimeout(2_000L) { monitor.snapshot() })
            assertEquals(3, backend.readingCalls)
            assertNull(monitor.state.value.nextPollAtMillis)
            assertTrue(monitor.state.value.readingRequestFailed)
            backend.readingFailure = null
            backend.payload = backend.payload!!.copy(reportedAtMillis = clock)
            assertNotNull(withTimeout(2_000L) { monitor.snapshot() })
            assertFalse(monitor.state.value.readingRequestFailed)
        } finally { monitor.close() }
    }

    @Test fun loggingOnlyCancellationClosesTheConnectionAndCannotPublishALateRead() = runBlocking {
        val gate = CompletableDeferred<PolarisResult<PolarisPayload>>()
        val backend = Backend().apply { readingsGate = gate }
        val monitor = PrimePolarisMonitor(backend, Store(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            initialSampling = GrillSamplingPolicy(GrillSamplingMode.ON_LOG))
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.message.startsWith("Temperature queries wait") }
            val request = async { monitor.snapshot() }
            waitFor { backend.readingCalls == 1 }
            request.cancel(); request.join()
            waitFor { backend.cancelled }
            gate.complete(PolarisResult(PolarisPayload(mapOf("tempUnit" to 0.0), emptyList(), 0, null)))
            assertNull(monitor.state.value.latest)
            assertNull(monitor.state.value.nextPollAtMillis)
        } finally { monitor.close() }
    }

    @Test fun selectedIntervalOwnsTheNextPollAndChangingToLoggingOnlyCancelsIt() = runBlocking {
        val pauses = mutableListOf<Long>()
        val hold = CompletableDeferred<Unit>()
        val backend = Backend()
        val monitor = PrimePolarisMonitor(backend, Store(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { 1_000_000L }, pollWait = { pauses += it; hold.await() })
        try {
            monitor.setForeground(true)
            waitFor { pauses.isNotEmpty() }
            assertEquals(60_000L, pauses.last())
            assertEquals(1_060_000L, monitor.state.value.nextPollAtMillis)
            monitor.configureSampling(GrillSamplingPolicy(intervalMillis = 300_000L))
            waitFor { pauses.last() == 300_000L }
            assertEquals(300_000L, monitor.state.value.latest!!.samplingIntervalMillis)
            monitor.configureSampling(GrillSamplingPolicy(GrillSamplingMode.ON_LOG))
            val calls = backend.readingCalls
            delay(50L)
            assertEquals(calls, backend.readingCalls)
            assertNull(monitor.state.value.nextPollAtMillis)
            assertEquals(PolarisPhase.READY, monitor.state.value.phase)
        } finally { monitor.close() }
    }

    @Test fun loggingActionsHonorRateLimitsWithoutCreatingABackgroundRetry() = runBlocking {
        val backend = Backend().apply { statusFailure = PolarisFailure(PolarisFailureKind.RATE_LIMIT, 429, retryAfterMillis = 900_000L) }
        var clock = 1_000_000L
        val monitor = PrimePolarisMonitor(backend, Store(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { clock }, initialSampling = GrillSamplingPolicy(GrillSamplingMode.ON_LOG),
            pollWait = { error("Unexpected rate-limit timer") })
        try {
            monitor.setForeground(true)
            waitFor { monitor.state.value.message.startsWith("Temperature queries wait") }
            assertNull(monitor.snapshot())
            val requests = monitor.state.value.failedRequests
            assertNull(monitor.snapshot())
            assertEquals(requests, monitor.state.value.failedRequests)
            assertEquals(0, backend.readingCalls)
            assertNull(monitor.state.value.nextPollAtMillis)
            clock += 900_000L
            backend.statusFailure = null
            assertNotNull(monitor.snapshot())
            assertEquals(1, backend.readingCalls)
        } finally { monitor.close() }
    }

}
