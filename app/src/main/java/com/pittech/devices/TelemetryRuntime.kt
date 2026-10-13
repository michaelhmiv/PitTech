package com.pittech.devices

import android.content.Context
import com.pittech.CookRecordingService
import com.pittech.data.CookStatus
import com.pittech.data.PitTechDatabase
import com.pittech.data.TelemetryRecordingRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned runtime; foreground service and visible UI share exactly one set of collection jobs. */
internal class TelemetryRuntime(private val context: Context, private val db: PitTechDatabase, private val clock: () -> Long = System::currentTimeMillis, private val backgroundStarter: (Context) -> Unit = CookRecordingService::startTelemetry, private val factories: (String) -> TelemetryConnectorFactory? = TelemetryFactories::get) {
    val store = TelemetryProfileStore(context)
    val states = MutableStateFlow<Map<String, String>>(emptyMap())
    val foreground = MutableStateFlow(false)
    val serviceHosted = MutableStateFlow(false)
    private val backgroundStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectors = ConcurrentHashMap<String, ReadOnlyTelemetryConnector>()
    private val recorder = TelemetryRecordingRepository(db)
    private val updateMutex = Mutex()
    private val activeCooks = db.cookDao().observeCooks().map { cooks -> cooks.filter { it.cook.status == CookStatus.ACTIVE }.map { it.cook.id }.toSet() }.distinctUntilChanged()
    val backgroundSessions = combine(store.profiles, activeCooks) { profiles, active ->
        profiles.any { it.monitoring && it.background && !it.onLogOnly && it.cookId in active && it.selectedDeviceIds.isNotEmpty() }
    }
    val backgroundRequested = backgroundSessions.stateIn(scope, SharingStarted.Eagerly, false)
    init {
        scope.launch {
            combine(backgroundRequested, foreground, serviceHosted) { requested, visible, hosted -> Triple(requested, visible, hosted) }
                .distinctUntilChanged().collect { requestBackgroundFromVisibleApp() }
        }
        scope.launch {
            combine(store.profiles, activeCooks, foreground, serviceHosted) { p, active, visible, hosted ->
                p.filter { it.monitoring && !it.onLogOnly && it.cookId in active && (visible || it.background && hosted) }
            }.collectLatest { profiles ->
                supervisorScope {
                    profiles.forEach { profile -> launch { collectProfile(profile) } }
                }
            }
        }
    }
    private fun status(id: String, text: String) { states.update { it + (id to text) } }
    private fun publishBatch(profile: TelemetryProfile, source: TelemetrySource, batch: List<TelemetryObservation>) {
        val valid = TelemetryChannelHealth.validChannels(source, batch)
        states.update { old -> old + source.channels.associate { channel -> (profile.id + ":" + source.deviceId + ":" + channel.id) to if (channel.id in valid) "Connected" else "Unavailable or stale" } }
    }
    private suspend fun connector(profile: TelemetryProfile): ReadOnlyTelemetryConnector {
        connectors[profile.id]?.let { return it }
        val fixture = if (com.pittech.BuildConfig.DEBUG) runCatching {
            Class.forName("com.pittech.devices.TelemetryProcessFixture").getMethod("restore", Context::class.java, TelemetryProfile::class.java)
                .invoke(null, context, profile) as? ReadOnlyTelemetryConnector
        }.getOrNull() else null
        if (fixture != null) return fixture.also { connectors[profile.id] = it }
        val factory = factories(profile.provider) ?: throw TelemetryRequestException(TelemetryFailureKind.CONFIGURATION)
        return factory.restore(context, profile).also { fresh ->
            val prior = connectors.putIfAbsent(profile.id, fresh)
            if (prior != null) fresh.disconnect()
        }.let { connectors.getValue(profile.id) }
    }
    private suspend fun collectProfile(profile: TelemetryProfile) {
        val cook = profile.cookId ?: return
        try {
            val connected = connector(profile)
            val bindings = profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.map { TelemetryBinding(it, connected) }
            val coordinator = TelemetryCollectionCoordinator(recorder, clock = clock, onBatch = { source, batch -> publishBatch(profile, source, batch) }, onState = { source, message ->
                status(profile.id + ":" + source.deviceId, message)
                if (message == "Sign in again") store.profiles.value.firstOrNull { it.id == profile.id && it.generation == profile.generation }
                    ?.let { update(it.copy(monitoring = false)) }
            })
            coordinator.launch(scope = CoroutineScope(currentCoroutineContext()), cookId = cook, bindings = bindings,
                intervalMillis = profile.intervalMillis).join()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            status(profile.id, error.message?.takeIf { error is TelemetryRequestException } ?: "Reconnect this connection in Devices.")
            if (error is IllegalArgumentException || error is TelemetryRequestException && error.kind in setOf(TelemetryFailureKind.AUTHORIZATION, TelemetryFailureKind.CONFIGURATION)) {
                store.profiles.value.firstOrNull { it.id == profile.id && it.generation == profile.generation }?.let { update(it.copy(monitoring = false)) }
            }
        }
    }
    suspend fun connect(profile: TelemetryProfile, user: String, secret: String): TelemetryProfile {
        val factory = factories(profile.provider) ?: error("This connector is unavailable in this build.")
        val session = if (user.isBlank() && secret.isBlank()) factory.restore(context, profile) else factory.connect(context, profile, user, secret)
        val old = store.profiles.value.firstOrNull { it.id == profile.id }
        val differentServer = old != null && profile.provider == "home_assistant" && endpointScope(old.endpoint) != endpointScope(profile.endpoint)
        val discovered = session.discover().map { source ->
            val saved = if (differentServer) null else profile.sources.firstOrNull { it.deviceId == source.deviceId }
            source.copy(identityScope = source.identityScope,
                channels = source.channels.map { ch -> saved?.channels?.firstOrNull { it.id == ch.id }?.let { ch.copy(kind = it.kind) } ?: ch })
        }
        connectors.put(profile.id, session)?.disconnect()
        val missing = if (differentServer) emptyList() else profile.sources.filter { prior -> discovered.none { it.deviceId == prior.deviceId } }
        missing.forEach { status(profile.id + ":" + it.deviceId, "Unavailable or stale") }
        if (differentServer) old?.cookId?.let { cook -> old.sources.filter { it.deviceId in old.selectedDeviceIds }.forEach { recorder.detach(cook, it) } }
        val configured = if (differentServer) profile.copy(cookId = null, selectedDeviceIds = emptySet(), monitoring = false) else profile
        val saved = configured.copy(sources = discovered + missing, generation = (store.profiles.value.firstOrNull { it.id == profile.id }?.generation ?: profile.generation) + 1)
        store.save(saved); status(profile.id, "Connected · read-only")
        return saved
    }
    private fun endpointScope(raw: String): String = runCatching {
        val uri = java.net.URI(raw.trim()); val scheme = uri.scheme.lowercase()
        val port = uri.port.takeUnless { it == -1 || scheme == "https" && it == 443 || scheme == "http" && it == 80 }
        scheme + "://" + uri.host.lowercase() + (port?.let { ":" + it } ?: "")
    }.getOrDefault(raw)
    suspend fun attach(profile: TelemetryProfile, cookId: String, source: TelemetrySource, assignments: Map<String, String?>) {
        val old = store.profiles.value.firstOrNull { it.id == profile.id } ?: profile
        if (old.cookId != null && old.cookId != cookId) {
            old.sources.filter { it.deviceId in old.selectedDeviceIds }.forEach { recorder.detach(old.cookId, it) }
        }
        recorder.attach(cookId, source, assignments)
        val updated = old.copy(cookId = cookId, sources = old.sources.filterNot { it.deviceId == source.deviceId } + source,
            selectedDeviceIds = (if (old.cookId == cookId) old.selectedDeviceIds else emptySet()) + source.deviceId, monitoring = true)
        store.save(updated)
        val initial = connector(updated).snapshot(source.deviceId)
        publishBatch(updated, source, initial)
        recorder.ingest(cookId, source, initial, if (updated.onLogOnly) 0L else maxOf(updated.intervalMillis, connector(updated).minimumIntervalMillis))
        status(profile.id + ":" + source.deviceId, if (initial.any { TelemetryNormalizer.normalize(it) != null }) "Connected" else "Unavailable or stale")
    }
    suspend fun detachSource(profile: TelemetryProfile, source: TelemetrySource) {
        profile.cookId?.let { recorder.detach(it, source) }
        store.save(profile.copy(selectedDeviceIds = profile.selectedDeviceIds - source.deviceId))
    }
    suspend fun remove(profile: TelemetryProfile) {
        store.remove(profile.id)
        connectors.remove(profile.id)?.disconnect()
        profile.cookId?.let { cook -> profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { recorder.detach(cook, it) } }
        try { factories(profile.provider)?.revoke(context, profile) } catch (_: Exception) { status(profile.id, "Local connection removed. Revoke the original token in your provider profile if the server was unavailable.") } finally { TelemetryTokenStore(context).clear(profile.provider, profile.id) }
        status(profile.id, "Disconnected")
    }
    fun update(profile: TelemetryProfile) {
        scope.launch {
            updateMutex.withLock {
                val old = store.profiles.value.firstOrNull { it.id == profile.id } ?: return@withLock
                if (old.generation != profile.generation) return@withLock
                // Commit the lifecycle marker before emitting a profile that restarts collection.
                if (old.monitoring != profile.monitoring) profile.cookId?.let { cook ->
                    profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { recorder.setMonitoring(cook, it, profile.monitoring) }
                }
                store.save(profile)

            }
        }
    }
    fun requestBackgroundFromVisibleApp() {
        scope.launch(Dispatchers.Main.immediate) {
            // UI and automatic requests share one pending start. Recheck current state
            // on Main so a queued visible-state event cannot start from the background.
            if (!backgroundRequested.value || serviceHosted.value) { backgroundStarting.set(false); return@launch }
            if (!foreground.value ||
                !backgroundStarting.compareAndSet(false, true)) return@launch
            runCatching { backgroundStarter(context) }.onFailure {
                backgroundStarting.set(false)
                store.profiles.value.filter { it.monitoring && it.background }.forEach { profile ->
                    store.save(profile.copy(background = false))
                    status(profile.id, "Android could not start background monitoring. Open PitTech and try again.")
                }
            }
        }
    }
    fun pauseBackground() { store.profiles.value.filter { it.background }.forEach { update(it.copy(monitoring = false)) } }
    suspend fun close() = withContext(NonCancellable) {
        // Cancellation is asynchronous: wait for Room observers and collection jobs
        // before a caller closes the database or replaces this runtime.
        scope.coroutineContext[Job]?.cancelAndJoin()
        connectors.values.forEach { it.disconnect() }
        connectors.clear()
    }
    suspend fun onLog(cookId: String, force: Boolean = false) = supervisorScope {
        store.profiles.value.filter { it.cookId == cookId && it.monitoring && (it.onLogOnly || force) }.forEach { profile ->
            launch {
                try {
                    val session = connector(profile)
                    profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { source ->
                        try {
                            val batch = session.snapshot(source.deviceId)
                            publishBatch(profile, source, batch)
                            recorder.ingest(cookId, source, batch, if (profile.onLogOnly) 0L else maxOf(profile.intervalMillis, session.minimumIntervalMillis))
                            status(profile.id + ":" + source.deviceId, if (batch.any { TelemetryNormalizer.normalize(it) != null }) "Connected" else "Unavailable or stale")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            publishBatch(profile, source, emptyList())
                            status(profile.id + ":" + source.deviceId, "Unavailable or stale")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status(profile.id, "A reading was unavailable. Your note is saved.") }
            }
        }
    }
}
