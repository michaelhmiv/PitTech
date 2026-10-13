package com.pittech.devices

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Public builds retain old cook records; historical controller clients are absent. */
internal class ConnectedGrillMonitor private constructor() : PolarisMonitorEngine {
    override val state = MutableStateFlow(PolarisMonitorState(phase = PolarisPhase.SIGNED_OUT, message = "Historical grill recordings are available in your cook history."))
    override fun setForeground(active: Boolean) = Unit
    override fun setRecording(active: Boolean) = Unit
    override fun requestCode(email: String) = Unit
    override fun signIn(email: String, code: String) = Unit
    override fun reloadDevices() = Unit
    override fun selectDevice(id: String) = Unit
    override fun refresh() = Unit
    override fun signOut() = Unit
    override fun close() = Unit
    companion object { fun create(context: Context) = ConnectedGrillMonitor() }
}
