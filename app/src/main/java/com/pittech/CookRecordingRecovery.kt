package com.pittech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pittech.data.CookStatus
import com.pittech.devices.CookTelemetryPolicy
import com.pittech.devices.GrillSamplingMode
import com.pittech.devices.GrillSamplingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reconciles persisted user intent; package replacement never attaches a different grill. */
internal object CookRecordingRecovery {
    private val mutex = Mutex()
    private const val CHANNEL = "pittech-recording-recovery"
    private const val NOTIFICATION = 9503

    suspend fun reconcile(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (!BuildConfig.CONTROLLER_TESTING_ENABLED) return@withContext
        mutex.withLock {
            val app = context.applicationContext as PitTechApplication
            val recording = app.database.recordingDao().getActiveRecording()
            val active = recording?.let { app.database.cookDao().getCook(it.cookId)?.status == CookStatus.ACTIVE } == true
            if (!active || recording == null) {
                app.loggingRecordingActive = false
                app.grillMonitor.setRecording(false)
                context.stopService(Intent(context, CookRecordingService::class.java))
                return@withLock
            }
            val policy = GrillSamplingPolicy.stored(recording.samplingMode, recording.samplingIntervalMillis)
            app.loggingRecordingActive = policy.mode == GrillSamplingMode.ON_LOG
            app.grillMonitor.configureSampling(policy)
            app.grillMonitor.state.value.devices.firstOrNull { CookTelemetryPolicy.deviceKey(it.id) == recording.controllerKey }
                ?.let { app.grillMonitor.lockDevice(it.id) }
            app.grillMonitor.setRecording(true)
            if (policy.mode == GrillSamplingMode.ON_LOG) {
                context.stopService(Intent(context, CookRecordingService::class.java))
            } else if (!app.recordingServiceRunning.value) {
                CookRecordingService.start(context)
            }
        }
    }

    suspend fun afterPackageReplacement(context: Context) {
        try { reconcile(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val app = context.applicationContext as PitTechApplication
            app.database.recordingDao().getActiveRecording()?.let {
                pauseWithNotice(context, it.cookId, "Android could not resume recording after the update. Open PitTech and tap Resume recording.")
            }
        }
    }

    suspend fun pauseWithNotice(context: Context, cookId: String, message: String) {
        val app = context.applicationContext as PitTechApplication
        app.recordingRepository.pause(cookId, message)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Recording recovery", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, NOTIFICATION,
            Intent(context, MainActivity::class.java).putExtra(CookReminderNotifications.EXTRA_COOK_ID, cookId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pittech).setContentTitle("Cook saved · recording needs attention")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    fun clearNotice(context: Context) { NotificationManagerCompat.from(context).cancel(NOTIFICATION) }
}
