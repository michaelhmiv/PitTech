package com.pittech

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pittech.data.*

/** All one-shot wakes go through the existing guidance receiver and scheduler. */
object UnifiedReminderNotifications {
    private const val CHANNEL = "meal_reminders"
    fun canNotify(context: Context) = CookGuidanceNotifications.notificationsAvailable(context) && context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun show(context: Context, rule: ReminderRule, occurrence: ReminderOccurrence): Boolean {
        if (!canNotify(context)) return false
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Meal reminders", NotificationManager.IMPORTANCE_DEFAULT))
        fun action(name: String) = PendingIntent.getBroadcast(context, 0, Intent(context, CookGuidanceReceiver::class.java).setAction("reminder_$name").setData(Uri.parse("pittech://reminder/${rule.id}/${occurrence.sequence}/$name")).putExtra("cook", rule.cookId).putExtra("step", "reminder:${rule.id}"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("pittech://cook/${rule.cookId}")).putExtra(CookReminderNotifications.EXTRA_COOK_ID, rule.cookId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = if (rule.kind == "historical") "Last time this milestone happened around this point. Check your food." else "Check your food. Acknowledging this reminder does not record an action."
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pittech).setContentTitle(rule.title).setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(0, "Dismiss", action("complete")).addAction(0, "Snooze 10 min", action("snooze")).build()
        NotificationManagerCompat.from(context).notify("meal-reminder:${rule.id}", 3, notification)
        return true
    }
    fun cancel(context: Context, rule: ReminderRule) {
        CookGuidanceNotifications.cancelAlarm(context, rule.cookId, "reminder:${rule.id}")
        NotificationManagerCompat.from(context).cancel("meal-reminder:${rule.id}", 3)
    }
}
