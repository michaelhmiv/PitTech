package com.pittech.devices

import kotlinx.coroutines.flow.StateFlow

/** Historic recording display contract. The public implementation performs no network work. */
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
    fun configureSampling(policy: GrillSamplingPolicy) {}
    suspend fun snapshot(): PolarisSample? = null
    fun signOut()
    fun close()
}

