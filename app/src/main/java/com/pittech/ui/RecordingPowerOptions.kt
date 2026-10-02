package com.pittech.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

@Composable
internal fun RecordingPowerOptions() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var expanded by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var message by remember { mutableStateOf<String?>(null) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "Android battery saving can interrupt temperature recording when the screen is off. Missed readings cannot currently be recovered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("recording-power-warning")
        )
        TextButton(onClick = { expanded = !expanded; allowed = power.isIgnoringBatteryOptimizations(context.packageName); notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() }, modifier = Modifier.testTag("recording-power-options")) { Text(if (expanded) "Hide screen-off options" else "Screen-off recording options") }
        if (expanded) {
            Text(if (allowed) "Android's battery optimization exemption is enabled. Other power-saving restrictions can still interrupt recording." else "Allow screen-off recording to reduce interruptions from Android battery optimization, or keep the phone charging. These steps do not guarantee uninterrupted recording.", style = MaterialTheme.typography.bodySmall)
            if (!allowed) OutlinedButton(onClick = {
                try { batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                catch (_: Exception) { message = "Open this app's Battery settings and allow unrestricted background use." }
            }, modifier = Modifier.fillMaxWidth().testTag("recording-allow-screen-off")) { Text("Allow screen-off recording") }
            if (!notifications && Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.testTag("recording-allow-notifications")) { Text("Show recording notification") }
            if (!notifications) Text("Notifications are off. You can pause recording from Live.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
