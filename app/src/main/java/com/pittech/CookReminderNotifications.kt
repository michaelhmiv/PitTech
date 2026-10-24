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
    private const val ACTION_SNOOZE = "com.pittech.action.COOK_REMINDER_SNOOZE"
    private const val ACTION_DISMISS = "com.pittech.action.COOK_REMINDER_DISMISS"

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
        // PL-02 (issue #172): never CLEAR_TOP — destroying the activity also
        // destroyed whatever the cook was doing in another meal. SINGLE_TOP
        // delivers the intent to the running activity via onNewIntent.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_COOK_ID, reminder.cookId)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val open = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The same vocabulary as every other reminder prompt: Snooze 10 min
        // and Dismiss act in place; Log it is the tap itself, which opens
        // this reminder's check-in.
        fun actionIntent(action: String, requestCode: Int) = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CookReminderReceiver::class.java).apply {
                this.action = action
                data = android.net.Uri.parse("pittech://cook-reminder/${reminder.id}/$action")
                putExtra(EXTRA_REMINDER_ID, reminder.id)
                putExtra(EXTRA_COOK_ID, reminder.cookId)
            },
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
            .addAction(0, com.pittech.domain.ReminderVocabulary.SNOOZE, actionIntent(ACTION_SNOOZE, reminder.id.hashCode() + 1))
            .addAction(0, com.pittech.domain.ReminderVocabulary.DISMISS, actionIntent(ACTION_DISMISS, reminder.id.hashCode() + 2))
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
                when (intent?.action) {
                    // PL-02: the notification's own Snooze 10 min / Dismiss,
                    // with exactly the meanings the unified reminders use.
                    "com.pittech.action.COOK_REMINDER_SNOOZE" -> {
                        val dueAt = System.currentTimeMillis() + com.pittech.domain.ReminderVocabulary.SNOOZE_MILLIS
                        app.cookRepository.snoozeCookReminder(reminderId, dueAt)?.let { updated ->
                            CookReminderNotifications.cancel(context, updated.id, updated.cookId)
                            CookReminderNotifications.schedule(context, updated)
                        }
                    }
                    "com.pittech.action.COOK_REMINDER_DISMISS" -> {
                        app.cookRepository.dismissCookReminder(reminderId)?.let { CookReminderNotifications.cancel(context, it.id, it.cookId) }
                    }
                    else -> {
                        val reminder = app.database.cookDao().getReminder(reminderId)
                        if (reminder?.status == CookReminderEntity.STATUS_PENDING) {
                            CookReminderNotifications.show(context, reminder)
                        }
                    }
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
                if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    CookRecordingRecovery.afterPackageReplacement(context)
                }
                app.companionRepository.reconcileAll(context)
                app.alertRepository.reconcileAll(context)
                app.reminderRepository.reconcileAll(context)
                app.database.cookDao().getPendingReminders().forEach { reminder ->
                    CookReminderNotifications.schedule(context, reminder)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
