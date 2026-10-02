package com.pittech.devices

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One selected provider owns network work. Changing provider never merges histories or credentials. */
internal class ConnectedGrillMonitor(
    private val engines: Map<GrillProvider, PolarisMonitorEngine>,
    initial: GrillProvider = GrillProvider.GRILLIRG,
    private val saveSelection: (GrillProvider) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val saveSampling: (GrillSamplingPolicy) -> Unit = {},
) : PolarisMonitorEngine {
    private var selected = initial.takeIf { it in engines } ?: GrillProvider.GRILLIRG
    private val mutableState = MutableStateFlow(engines.getValue(selected).state.value.copy(provider = selected))
    override val state = mutableState.asStateFlow()
    private var foreground = false
    private var recording = false
    private var locked: String? = null
    private var watcher: Job? = null
    private val engine get() = engines.getValue(selected)
    init { watch() }
    private fun watch() {
        watcher?.cancel()
        val provider = selected
        watcher = scope.launch {
            engines.getValue(provider).state.collect {
                if (selected == provider) mutableState.value = it.copy(provider = provider, lockedDeviceId = locked ?: it.lockedDeviceId)
            }
        }
    }
    override fun selectProvider(provider: GrillProvider) {
        if (provider == selected || provider !in engines) return
        if (recording || locked != null) {
            mutableState.value = state.value.copy(message = "Pause or stop cook recording before changing provider.")
            return
        }
        engine.setForeground(false)
        engine.setRecording(false)
        selected = provider
        saveSelection(provider)
        mutableState.value = engine.state.value.copy(provider = provider)
        watch()
        engine.setForeground(foreground)
    }
    override fun setForeground(active: Boolean) { foreground = active; engine.setForeground(active) }
    override fun setRecording(active: Boolean) {
        recording = active
        if (!active) locked = null
        engine.setRecording(active)
        mutableState.value = state.value.copy(lockedDeviceId = if (active) locked else null)
    }
    override fun lockDevice(id: String) {
        if (state.value.devices.none { it.id == id }) return
        locked = id
        engine.lockDevice(id)
        mutableState.value = state.value.copy(lockedDeviceId = id)
    }
    override fun requestCode(email: String) = engine.requestCode(email)
    override fun signIn(email: String, code: String) {
        if (recording || locked != null) {
            mutableState.value = state.value.copy(message = "Pause or stop cook recording before reconnecting an account.")
            return
        }
        engine.signIn(email, code)
    }
    override fun reloadDevices() = engine.reloadDevices()
    override fun selectDevice(id: String) {
        if (locked != null && locked != id) return
        engine.selectDevice(id)
    }
    override fun refresh() = engine.refresh()
    override suspend fun snapshot() = engine.snapshot()
    override fun configureSampling(policy: GrillSamplingPolicy) {
        engines.values.forEach { it.configureSampling(policy) }
        saveSampling(policy)
        mutableState.value = state.value.copy(sampling = policy)
    }
    override fun signOut() = engine.signOut()
    override fun close() { engines.values.forEach { it.close() }; scope.cancel() }
    companion object {
        fun create(context: Context): ConnectedGrillMonitor {
            val preferences = context.getSharedPreferences("connected-grill-provider", Context.MODE_PRIVATE)
            val initial = runCatching { GrillProvider.valueOf(preferences.getString("selected", "") ?: "") }.getOrDefault(GrillProvider.GRILLIRG)
            val sampling = runCatching { GrillSamplingPolicy.stored(preferences.getString("sampling_mode", "periodic") ?: "periodic", preferences.getLong("sampling_interval", 60_000L)) }.getOrDefault(GrillSamplingPolicy())
            return ConnectedGrillMonitor(
                GrillProvider.entries.associateWith { provider ->
                    val backend: PolarisBackend = when (provider) {
                        GrillProvider.GRILLIRG -> PrimePolarisApi()
                        GrillProvider.PIT_BOSS -> PitBossBackend()
                        GrillProvider.TRAEGER -> TraegerBackend()
                    }
                    PrimePolarisMonitor(backend, AndroidPolarisSessionStore(context, provider), provider = provider, initialSampling = sampling)
                },
                initial, { preferences.edit().putString("selected", it.name).apply() },
                saveSampling = { value -> preferences.edit().putString("sampling_mode", value.mode.key).putLong("sampling_interval", value.intervalMillis).apply() },
            )
        }
    }
}
