package com.pittech.devices

import android.content.Context
import com.pittech.CookRecordingService
import com.pittech.data.CookStatus
import com.pittech.data.PitTechDatabase
import com.pittech.data.TelemetryRecordingRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/** Application-owned runtime; foreground service and visible UI share exactly one set of collection jobs. */
internal class TelemetryRuntime(private val context: Context, private val db: PitTechDatabase) {
    val store = TelemetryProfileStore(context)
    val states = MutableStateFlow<Map<String, String>>(emptyMap())
    val foreground = MutableStateFlow(false)
    val serviceHosted = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectors = ConcurrentHashMap<String, ReadOnlyTelemetryConnector>()
    private val recorder = TelemetryRecordingRepository(db)
    private val activeCooks = db.cookDao().observeCooks().map { cooks -> cooks.filter { it.cook.status == CookStatus.ACTIVE }.map { it.cook.id }.toSet() }.distinctUntilChanged()
    val backgroundSessions = combine(store.profiles, activeCooks) { profiles, active ->
        profiles.any { it.monitoring && it.background && !it.onLogOnly && it.cookId in active && it.selectedDeviceIds.isNotEmpty() }
    }
    val backgroundRequested = backgroundSessions.stateIn(scope, SharingStarted.Eagerly, false)
    init {
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
    private suspend fun connector(profile: TelemetryProfile): ReadOnlyTelemetryConnector {
        connectors[profile.id]?.let { return it }
        val factory = TelemetryFactories.get(profile.provider) ?: throw TelemetryRequestException(TelemetryFailureKind.CONFIGURATION)
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
            val coordinator = TelemetryCollectionCoordinator(recorder, onState = { source, message -> status(profile.id + ":" + source.deviceId, message) })
            coordinator.launch(scope = CoroutineScope(currentCoroutineContext()), cookId = cook, bindings = bindings,
                intervalMillis = profile.intervalMillis).join()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { status(profile.id, error.message?.takeIf { error is TelemetryRequestException } ?: "Reconnect this connection in Devices.") }
    }
    suspend fun connect(profile: TelemetryProfile, user: String, secret: String): TelemetryProfile {
        val factory = TelemetryFactories.get(profile.provider) ?: error("This connector is unavailable in this build.")
        val session = factory.connect(context, profile, user, secret)
        val discovered = session.discover().map { source ->
            val saved = profile.sources.firstOrNull { it.deviceId == source.deviceId }
            source.copy(identityScope = if (profile.provider == "home_assistant") profile.endpoint.trimEnd('/') else source.identityScope,
                channels = source.channels.map { ch -> saved?.channels?.firstOrNull { it.id == ch.id }?.let { ch.copy(kind = it.kind) } ?: ch })
        }
        connectors.put(profile.id, session)?.disconnect()
        val saved = profile.copy(sources = discovered)
        store.save(saved); status(profile.id, "Connected · read-only")
        return saved
    }
    suspend fun attach(profile: TelemetryProfile, cookId: String, source: TelemetrySource, assignments: Map<String, String?>) {
        val old = store.profiles.value.firstOrNull { it.id == profile.id } ?: profile
        if (old.cookId != null && old.cookId != cookId) {
            old.sources.filter { it.deviceId in old.selectedDeviceIds }.forEach { recorder.detach(old.cookId, it) }
        }
        recorder.attach(cookId, source, assignments)
        val updated = old.copy(cookId = cookId, sources = old.sources.filterNot { it.deviceId == source.deviceId } + source,
            selectedDeviceIds = (if (old.cookId == cookId) old.selectedDeviceIds else emptySet()) + source.deviceId, monitoring = true)
        store.save(updated)
        recorder.ingest(cookId, source, connector(updated).snapshot(source.deviceId))
    }
    suspend fun detachSource(profile: TelemetryProfile, source: TelemetrySource) {
        profile.cookId?.let { recorder.detach(it, source) }
        store.save(profile.copy(selectedDeviceIds = profile.selectedDeviceIds - source.deviceId))
    }
    suspend fun remove(profile: TelemetryProfile) {
        store.remove(profile.id)
        connectors.remove(profile.id)?.disconnect()
        profile.cookId?.let { cook -> profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { recorder.detach(cook, it) } }
        TelemetryTokenStore(context).clear(profile.provider, profile.id)
        status(profile.id, "Disconnected")
    }
    fun update(profile: TelemetryProfile) {
        store.save(profile)
        if (profile.monitoring && profile.background && !profile.onLogOnly) {
            try { CookRecordingService.startTelemetry(context) }
            catch (_: Exception) {
                store.save(profile.copy(background = false))
                status(profile.id, "Android could not start background monitoring. Open PitTech and try again.")
            }
        }
    }
    fun requestBackgroundFromVisibleApp() {
        if (foreground.value && backgroundRequested.value) runCatching { CookRecordingService.startTelemetry(context) }
    }
    fun pauseBackground() { store.profiles.value.filter { it.background }.forEach { store.save(it.copy(monitoring = false)) } }
    suspend fun onLog(cookId: String, force: Boolean = false) = supervisorScope {
        store.profiles.value.filter { it.cookId == cookId && it.monitoring && (it.onLogOnly || force) }.forEach { profile ->
            launch {
                try {
                    val session = connector(profile)
                    profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { source -> recorder.ingest(cookId, source, session.snapshot(source.deviceId)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status(profile.id, "A reading was unavailable. Your note is saved.") }
            }
        }
    }
}
