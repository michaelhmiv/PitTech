package com.pittech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pittech.data.CookRecordingEntity
import com.pittech.devices.CookTelemetryPolicy
import com.pittech.devices.PolarisPhase
import com.pittech.devices.GrillSamplingMode
import com.pittech.devices.GrillSamplingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Explicit active-cook observation, never a grill-control service. Debug/Dev manifests only. */
class CookRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as PitTechApplication
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cook recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.CONTROLLER_TESTING_ENABLED) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Opening cook recording…"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) {
            scope.launch { app.database.recordingDao().getActiveRecording()?.let { app.recordingRepository.pause(it.cookId, "Android could not start recording. Open PitTech to resume.") }; stopSelf() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            scope.launch { app.database.recordingDao().getActiveRecording()?.let { app.recordingRepository.pause(it.cookId) }; stopSelf() }
            return START_NOT_STICKY
        }
        app.recordingServiceRunning.value = true
        if (!observing) {
            observing = true
            scope.launch {
                app.database.recordingDao().observeActiveRecording().distinctUntilChangedBy { it?.let { value -> listOf(value.cookId, value.deviceId, value.resumedAtUtcMillis.toString(), value.samplingMode, value.samplingIntervalMillis.toString()) } }.collectLatest { recording ->
                    if (recording == null) { app.loggingRecordingActive = false; stopSelf(); return@collectLatest }
                    val sampling = GrillSamplingPolicy.stored(recording.samplingMode, recording.samplingIntervalMillis)
                    app.grillMonitor.configureSampling(sampling)
                    if (sampling.mode == GrillSamplingMode.ON_LOG) {
                        app.loggingRecordingActive = true
                        app.grillMonitor.setRecording(true)
                        stopSelf()
                        return@collectLatest
                    }
                    app.loggingRecordingActive = false
                    app.grillMonitor.setRecording(true)
                    coroutineScope {
                    launch {
                        while (true) {
                            renewWakeLease()
                            val current = app.database.recordingDao().getRecording(recording.cookId)
                            if (current?.status != CookRecordingEntity.RECORDING) return@launch
                            val last = current.lastReceivedAtUtcMillis ?: current.resumedAtUtcMillis
                            if (System.currentTimeMillis() - last > GrillSamplingPolicy.receiptWindow(current.samplingIntervalMillis)) app.recordingRepository.markGap(recording.cookId, "Waiting for cloud readings. Missing periods are not filled in.")
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("${sampling.label} · ${current.message}"))
                            delay(30_000L)
                        }
                    }
                    app.grillMonitor.state.collect { state ->
                        if (state.phase == PolarisPhase.RESTORING || state.phase == PolarisPhase.DISCOVERING) return@collect
                        if (!state.authenticated) {
                            app.recordingRepository.pause(recording.cookId, if (state.provider == com.pittech.devices.GrillProvider.PIT_BOSS) "Connect your controller in Devices, then resume recording." else "Sign in to " + state.provider.label + " in Devices, then resume recording.")
                            return@collect
                        }
                        val device = state.devices.firstOrNull { CookTelemetryPolicy.deviceKey(it.id) == recording.controllerKey }
                        if (device == null) {
                            if (state.devices.isNotEmpty() || state.phase == PolarisPhase.READY) app.recordingRepository.pause(recording.cookId, "The attached grill was not found. Check the selected provider in Devices.")
                            return@collect
                        }
                        app.grillMonitor.lockDevice(device.id)
                        if (state.selectedDeviceId != device.id) { app.grillMonitor.selectDevice(device.id); return@collect }
                        if (state.readingRequestFailed || state.onlineStatus?.let { it != 0 } == true) app.recordingRepository.markGap(recording.cookId, "Grill offline or cloud readings unavailable.")
                        state.latest?.let { app.recordingRepository.ingest(recording.cookId, recording.controllerKey, it, state) }
                    }
                    }
                }
            }.invokeOnCompletion { error ->
                if (error != null && error !is CancellationException) {
                    scope.launch {
                        try { app.database.recordingDao().getActiveRecording()?.let { app.recordingRepository.pause(it.cookId, "Recording interrupted. Open PitTech to resume.") } }
                        finally { stopSelf() }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun renewWakeLease() {
        val lease = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:cook-recording").apply { setReferenceCounted(false); wakeLock = this }
        // A timeout releases the lease even if cleanup is interrupted. Renewal happens only
        // during an explicit active cook, and onDestroy always releases it.
        lease.acquire(120_000L)
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_pittech)
        .setContentTitle("PitTech · recording your cook")
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 9501, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Pause recording", PendingIntent.getService(this, 9502, Intent(this, CookRecordingService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        scope.cancel()
        if (!app.loggingRecordingActive) app.grillMonitor.setRecording(false)
        app.recordingServiceRunning.value = false
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL = "pittech-cook-recording"
        private const val NOTIFICATION_ID = 9501
        private const val ACTION_PAUSE = "com.pittech.PAUSE_RECORDING"
        internal fun start(context: Context) {
            require(BuildConfig.CONTROLLER_TESTING_ENABLED) { "Controller recording is available in the test build." }
            ContextCompat.startForegroundService(context, Intent(context, CookRecordingService::class.java))
        }
    }
}
