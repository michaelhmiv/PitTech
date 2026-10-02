package com.pittech.devices

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface PolarisMonitorEngine {
    val state: StateFlow<PolarisMonitorState>
    fun setForeground(active: Boolean)
    fun setRecording(active: Boolean) {}
    fun lockDevice(id: String) {}
    fun selectProvider(provider: GrillProvider) {}
    fun requestCode(email: String)
    fun signIn(email: String, code: String)
    fun reloadDevices()
    fun selectDevice(id: String)
    fun refresh()
    fun signOut()
    fun close()
}

/** One serial foreground poller. Cancellation propagates to HTTP; stale results cannot publish. */
internal class PrimePolarisMonitor(
    private val backend: PolarisBackend,
    private val storage: PolarisSessionStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000L },
    private val provider: GrillProvider = GrillProvider.GRILLIRG,
) : PolarisMonitorEngine {
    private val mutableState = MutableStateFlow(PolarisMonitorState(provider = provider))
    override val state = mutableState.asStateFlow()
    private var session: PolarisSession? = null
    private var foreground = false
    private var screenForeground = false
    private var recording = false
    private var closed = false
    private var generation = 0L
    private var action: Job? = null
    private var poller: Job? = null
    private val storageMutex = Mutex()

    init {
        scope.launch {
            try {
                session = storageMutex.withLock { withContext(Dispatchers.IO) { storage.load() } }
                if (session?.expired(now()) == true && session?.refreshToken == null) {
                    session = null
                    storageMutex.withLock { withContext(Dispatchers.IO) { storage.clear() } }
                }
                mutableState.value = state.value.copy(
                    phase = if (session == null) PolarisPhase.SIGNED_OUT else PolarisPhase.READY,
                    authenticated = session != null,
                    selectedDeviceId = session?.selectedDeviceId,
                    message = if (session == null) { if (provider == GrillProvider.PIT_BOSS) "Connect your provisioned controller below." else "Sign in to the account your grill is added to." } else "Saved sign-in opened. Loading your grills…",
                )
                if (foreground && session != null) reloadDevices()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                session = null
                mutableState.value = state.value.copy(phase = PolarisPhase.SIGNED_OUT, message = PolarisFailure(PolarisFailureKind.STORAGE).message(provider))
            }
        }
    }

    override fun setForeground(active: Boolean) {
        screenForeground = active
        updateActive()
    }

    override fun setRecording(active: Boolean) {
        recording = active
        if (!active) mutableState.value = state.value.copy(lockedDeviceId = null)
        updateActive()
    }

    override fun lockDevice(id: String) {
        mutableState.value = state.value.copy(lockedDeviceId = id)
    }

    private fun updateActive() {
        val active = screenForeground || recording
        if (closed || foreground == active) return
        foreground = active
        if (!active) {
            stopPolling()
            if (session != null) mutableState.value = state.value.copy(phase = PolarisPhase.PAUSED, nextPollAtMillis = null, message = "Monitoring paused while PitTech is closed and no cook is recording.")
        } else if (session != null && action?.isActive != true) {
            if (state.value.devices.isEmpty()) reloadDevices() else startPolling()
        }
    }

    override fun requestCode(email: String) = runAction {
        track(PolarisOperation.REQUEST_CODE) { backend.requestCode(email) }
        mutableState.value = state.value.copy(phase = PolarisPhase.CODE_SENT, busy = false, message = "Code sent. Check your email and enter the six-digit code.")
    }

    override fun signIn(email: String, code: String) = runAction {
        session = track(PolarisOperation.SIGN_IN) { backend.signIn(email, code) }
        mutableState.value = state.value.copy(authenticated = true, devices = emptyList(), selectedDeviceId = null, latest = null, samples = emptyList(), onlineStatus = null, statusFetchedAtMillis = null)
        saveSession()
        discoverDevices()
    }

    override fun reloadDevices() {
        if (session == null) return
        runAction { discoverDevices() }
    }

    private suspend fun discoverDevices() {
        var current = session ?: return
        if (current.expired(now())) {
            current = track(PolarisOperation.REFRESH_SESSION) { backend.refreshSession(current) }
            session = current
            saveSession()
        }
        mutableState.value = state.value.copy(phase = PolarisPhase.DISCOVERING, message = "Finding grills on your account…")
        val devices = track(PolarisOperation.DEVICES) { backend.devices(current) }
        val selected = state.value.lockedDeviceId?.takeIf { id -> devices.any { it.id == id } }
            ?: current.selectedDeviceId?.takeIf { id -> devices.any { it.id == id } }
            ?: devices.singleOrNull()?.id
        val changed = state.value.selectedDeviceId != selected
        session = current.select(selected)
        mutableState.value = state.value.copy(
            phase = PolarisPhase.READY, busy = false, devices = devices, selectedDeviceId = selected,
            latest = if (changed) null else state.value.latest, samples = if (changed) emptyList() else state.value.samples,
            onlineStatus = if (changed) null else state.value.onlineStatus,
            statusFetchedAtMillis = if (changed) null else state.value.statusFetchedAtMillis,
            message = if (devices.isEmpty()) "No grills were returned. Check the selected provider account in Devices and finish pairing in its supported app." else "Select a grill to monitor.",
        )
        saveSession()
        startPolling()
    }

    override fun selectDevice(id: String) {
        if (state.value.lockedDeviceId?.let { it != id } == true) {
            mutableState.value = state.value.copy(message = "Pause or stop cook recording before selecting another grill.")
            return
        }
        if (state.value.devices.none { it.id == id } || session == null) return
        runAction {
            session = session?.select(id)
            mutableState.value = state.value.copy(selectedDeviceId = id, latest = null, samples = emptyList(), onlineStatus = null, statusFetchedAtMillis = null, consecutiveFailures = 0, readingRequestFailed = false)
            saveSession()
            mutableState.value = state.value.copy(busy = false)
            startPolling()
        }
    }

    override fun refresh() {
        if (!foreground || session == null) return
        if (state.value.selectedDeviceId == null) reloadDevices() else {
            stopPolling()
            startPolling()
        }
    }

    override fun signOut() {
        stopPolling()
        action?.cancel()
        session = null
        mutableState.value = PolarisMonitorState(phase = PolarisPhase.SIGNED_OUT, message = "Disconnected on this phone.", provider = provider)
        action = scope.launch {
            try { withContext(NonCancellable) { storageMutex.withLock { withContext(Dispatchers.IO) { storage.clear() } } } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(message = "The saved sign-in could not be removed. Clear PitTech's app data to remove it.") }
        }
    }

    override fun close() {
        closed = true
        stopPolling()
        session = null
        scope.cancel()
    }

    private fun stopPolling() {
        generation += 1
        poller?.cancel()
        poller = null
        backend.disconnect()
    }

    private fun runAction(block: suspend () -> Unit) {
        if (closed) return
        stopPolling()
        action?.cancel()
        mutableState.value = state.value.copy(busy = true, nextPollAtMillis = null)
        action = scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val failure = error as? PolarisFailure
                if (failure?.kind == PolarisFailureKind.AUTH) invalidateSession(failure)
                else mutableState.value = state.value.copy(busy = false, message = failure?.message(provider) ?: "Check the connection details, then try again.")
            }
        }
    }

    private suspend fun saveSession() {
        val value = session ?: return
        try {
            storageMutex.withLock { withContext(Dispatchers.IO) { storage.save(value) } }
            mutableState.value = state.value.copy(sessionSaved = true)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutableState.value = state.value.copy(sessionSaved = false) }
    }

    private fun startPolling() {
        if (!foreground || closed || poller?.isActive == true) return
        var current = session ?: return
        val id = state.value.selectedDeviceId?.takeIf { selected -> state.value.devices.any { it.id == selected } } ?: return
        val attemptGeneration = generation
        poller = scope.launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (attemptGeneration != generation) return@launch
                if (current.expired(now())) {
                    try {
                        current = track(PolarisOperation.REFRESH_SESSION) { backend.refreshSession(current) }
                        session = current
                        saveSession()
                    } catch (error: CancellationException) { throw error }
                    catch (error: PolarisFailure) {
                        if (error.kind == PolarisFailureKind.AUTH) { invalidateSession(error); return@launch }
                        val count = state.value.consecutiveFailures + 1
                        val pause = PolarisMonitorPolicy.nextDelay(count, error.retryAfterMillis)
                        mutableState.value = state.value.copy(readingRequestFailed = true, consecutiveFailures = count,
                            message = error.message(provider), nextPollAtMillis = now() + pause)
                        delay(pause)
                        continue
                    }
                }
                mutableState.value = state.value.copy(phase = PolarisPhase.MONITORING, busy = false, nextPollAtMillis = null)
                var failure: PolarisFailure? = null
                if (backend.hasSeparateStatusRead) try {
                    val status = track(PolarisOperation.STATUS) { backend.status(current, id) }
                    mutableState.value = state.value.copy(onlineStatus = status.values["onlineStatus"]?.toInt(), statusFetchedAtMillis = now())
                } catch (error: CancellationException) { throw error }
                catch (error: PolarisFailure) { failure = error }
                if (failure?.kind == PolarisFailureKind.AUTH) { invalidateSession(failure); return@launch }
                if (failure?.kind != PolarisFailureKind.RATE_LIMIT) {
                    try {
                        val readings = track(PolarisOperation.READINGS) { backend.readings(current, id) }
                        val sample = PolarisSample(now(), readings)
                        val reported = readings.reportedAtMillis
                        val stale = reported?.let { now() - it > 45_000L || it - now() > 300_000L } == true
                        val repeated = reported != null && state.value.latest?.payload?.reportedAtMillis?.let { reported <= it } == true
                        mutableState.value = state.value.copy(
                            latest = if (stale || repeated) state.value.latest else sample,
                            samples = if (stale || repeated) state.value.samples else (state.value.samples + sample).takeLast(120), readingRequestFailed = stale,
                            onlineStatus = readings.values["onlineStatus"]?.toInt() ?: state.value.onlineStatus,
                            statusFetchedAtMillis = if (readings.values.containsKey("onlineStatus")) now() else state.value.statusFetchedAtMillis,
                        )
                        if (stale) failure = PolarisFailure(PolarisFailureKind.SCHEMA)
                    } catch (error: CancellationException) { throw error }
                    catch (error: PolarisFailure) {
                        failure = error
                        mutableState.value = state.value.copy(readingRequestFailed = true)
                    }
                }
                if (failure?.kind == PolarisFailureKind.AUTH) { invalidateSession(failure); return@launch }
                val failures = if (failure == null) 0 else state.value.consecutiveFailures + 1
                val delayMillis = PolarisMonitorPolicy.nextDelay(failures, failure?.retryAfterMillis)
                mutableState.value = state.value.copy(
                    consecutiveFailures = failures, nextPollAtMillis = now() + delayMillis,
                    message = failure?.message(provider) ?: "Monitoring your grill. Readings are fetched every 15 seconds while PitTech is open or a cook is recording.",
                )
                delay(delayMillis)
            }
        }
    }

    private suspend fun invalidateSession(failure: PolarisFailure) {
        backend.disconnect()
        session = null
        generation += 1
        var removed = true
        try { storageMutex.withLock { withContext(Dispatchers.IO) { storage.clear() } } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { removed = false }
        mutableState.value = state.value.copy(phase = PolarisPhase.SIGN_IN_REQUIRED, authenticated = false, busy = false, nextPollAtMillis = null, message = failure.message(provider) + if (removed) "" else " Saved sign-in could not be removed.")
    }

    private suspend fun <T> track(operation: PolarisOperation, request: suspend () -> PolarisResult<T>): T {
        val started = elapsed()
        try {
            val result = request()
            currentCoroutineContext().ensureActive()
            val event = PolarisExchange(now(), operation, (elapsed() - started).coerceAtLeast(0), result.httpStatus, result.apiCode)
            mutableState.value = state.value.copy(lastApiSuccessMillis = if (result.observedRemote) now() else state.value.lastApiSuccessMillis, successfulRequests = state.value.successfulRequests + 1, exchanges = (state.value.exchanges + event).takeLast(80))
            return result.value
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val failure = error as? PolarisFailure ?: PolarisFailure(PolarisFailureKind.NETWORK)
            val event = PolarisExchange(now(), operation, (elapsed() - started).coerceAtLeast(0), failure.httpStatus, failure.apiCode, failure.kind)
            mutableState.value = state.value.copy(failedRequests = state.value.failedRequests + 1, exchanges = (state.value.exchanges + event).takeLast(80))
            throw failure
        }
    }
}
