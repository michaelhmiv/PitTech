package com.pittech

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pittech.data.CookReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Uses inexact alarms: the reminder is a useful nudge, not a safety-critical timer. */
object CookReminderNotifications {
    private const val CHANNEL_ID = "cook_reminders"
    private const val ACTION_REMINDER = "com.pittech.action.COOK_REMINDER"

    fun schedule(context: Context, reminder: CookReminderEntity) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.dueAtUtcMillis,
            alarmIntent(context, reminder.id, reminder.cookId),
        )
    }

    fun cancel(context: Context, reminderId: String, cookId: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, reminderId, cookId))
        NotificationManagerCompat.from(context).cancel(reminderId.hashCode())
    }

    private fun alarmIntent(context: Context, reminderId: String, cookId: String): PendingIntent {
        val intent = Intent(context, CookReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = android.net.Uri.parse("pittech://cook-reminder/$reminderId")
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_COOK_ID, cookId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun show(context: Context, reminder: CookReminderEntity) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cook reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Reminders you set while tracking a cook"
                },
            )
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_COOK_ID, reminder.cookId)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val open = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(reminder.title)
            .setContentText("Time to check your cook. Tap to log what happened.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), notification)
    }

    const val EXTRA_COOK_ID = "com.pittech.extra.COOK_ID"
    const val EXTRA_REMINDER_ID = "com.pittech.extra.REMINDER_ID"
}

class CookReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val reminderId = intent?.getStringExtra(CookReminderNotifications.EXTRA_REMINDER_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as PitTechApplication
                val reminder = app.database.cookDao().getReminder(reminderId)
                if (reminder?.status == CookReminderEntity.STATUS_PENDING) {
                    CookReminderNotifications.show(context, reminder)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class CookReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.MY_PACKAGE_REPLACED", Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as PitTechApplication
                app.companionRepository.reconcileAll(context)
                app.database.cookDao().getPendingReminders().forEach { reminder ->
                    CookReminderNotifications.schedule(context, reminder)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
