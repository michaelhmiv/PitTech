package com.pittech.ui

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pittech.CookRecordingService
import com.pittech.devices.GrillSamplingMode

@Composable
internal fun RecordingPowerOptions(mode: GrillSamplingMode = GrillSamplingMode.PERIODIC) {
    if (mode == GrillSamplingMode.ON_LOG) {
        Text(
            "Queries run when you save a cook entry or tap Read grill now. Between entries, there is no recording service, wake lock, or scheduled temperature query. This creates snapshots, not a continuous temperature history.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("recording-log-only-info")
        )
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = context.getSystemService(PowerManager::class.java)
    val activity = context.getSystemService(ActivityManager::class.java)
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    fun notificationsVisible() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        notificationManager.getNotificationChannel(CookRecordingService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var saver by remember { mutableStateOf(power.isPowerSaveMode) }
    var restricted by remember { mutableStateOf(Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted) }
    var notifications by remember { mutableStateOf(notificationsVisible()) }
    var message by remember { mutableStateOf<String?>(null) }
    val refresh = {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
        saver = power.isPowerSaveMode
        restricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted
        notifications = notificationsVisible()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    fun openSettings(intent: Intent) {
        try { settingsLauncher.launch(intent) }
        catch (_: Exception) {
            try { settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { message = "Open Android Settings → Apps → PitTech to change Battery or Notifications settings." }
        }
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.testTag("recording-power-options")) {
        Text(
            "Timed recording uses a persistent notification and keeps the CPU awake during this cook. Android battery saving can still interrupt it. Missed readings cannot currently be recovered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("recording-power-warning")
        )
        Text(if (allowed) "Screen-off access: allowed by Android." else "Screen-off access: battery optimization is enabled.", style = MaterialTheme.typography.bodySmall)
        if (!allowed) OutlinedButton(onClick = {
            openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }, modifier = Modifier.fillMaxWidth().testTag("recording-allow-screen-off")) { Text("Allow screen-off recording") }
        Text(if (notifications) "Recording notification: enabled." else "Recording notification: hidden by Android settings.", style = MaterialTheme.typography.bodySmall)
        if (!notifications) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                OutlinedButton(onClick = { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.testTag("recording-allow-notifications")) { Text("Allow recording notification") }
            }
            OutlinedButton(onClick = {
                val channel = notificationManager.getNotificationChannel(CookRecordingService.CHANNEL)
                openSettings(Intent(if (channel == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CookRecordingService.CHANNEL))
            }, modifier = Modifier.testTag("recording-notification-settings")) { Text("Open notification settings") }
        }
        Text(if (saver) "Battery Saver: on." else "Battery Saver: off.", style = MaterialTheme.typography.bodySmall)
        if (saver) OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }, modifier = Modifier.testTag("recording-battery-saver-settings")) { Text("Open Battery Saver settings") }
        if (restricted) {
            Text("Android has restricted this app's background use.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.testTag("recording-app-battery-settings")) { Text("Open app battery settings") }
        }
        Text("Other phone power-saving settings and stopping PitTech can still interrupt recording. Charging may help during long cooks.", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
