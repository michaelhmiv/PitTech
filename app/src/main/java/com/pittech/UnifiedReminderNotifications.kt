package com.pittech

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pittech.data.*
import com.pittech.domain.PlaybookCodec
import com.pittech.domain.ReminderVocabulary

/** All one-shot wakes go through the existing guidance receiver and scheduler. */
object UnifiedReminderNotifications {
    private const val CHANNEL = "meal_reminders"
    /** Carries the fired rule into MainActivity so a tap opens THAT reminder's check-in (PL-02). */
    const val EXTRA_RULE_ID = "com.pittech.extra.REMINDER_RULE_ID"
    fun canNotify(context: Context) = CookGuidanceNotifications.notificationsAvailable(context) && context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun show(context: Context, rule: ReminderRule, occurrence: ReminderOccurrence, announcedAction: String? = null): Boolean {
        if (!canNotify(context)) return false
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Meal reminders", NotificationManager.IMPORTANCE_DEFAULT))
        fun action(name: String, recordAction: String? = null) = PendingIntent.getBroadcast(context, 0, Intent(context, CookGuidanceReceiver::class.java).setAction("reminder_$name").setData(Uri.parse("pittech://reminder/${rule.id}/${occurrence.sequence}/$name")).putExtra("cook", rule.cookId).putExtra("step", "reminder:${rule.id}").putExtra("occurrence", occurrence.sequence).apply { if (recordAction != null) putExtra("recordAction", recordAction) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("pittech://cook/${rule.cookId}")).putExtra(CookReminderNotifications.EXTRA_COOK_ID, rule.cookId).putExtra(EXTRA_RULE_ID, rule.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val announcedLabel = announcedAction?.let { PlaybookCodec.actions[it] ?: it }
        val detail = when {
            announcedLabel != null -> "Time to check your food. If you did it, tap Record $announcedLabel to log it in one tap."
            rule.kind == "historical" -> "Last time this milestone happened around this point. Check your food."
            else -> "Check your food. Acknowledging this reminder does not record an action."
        }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pittech).setContentTitle(rule.title).setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .addAction(0, ReminderVocabulary.DISMISS, action("complete")).addAction(0, ReminderVocabulary.SNOOZE, action("snooze"))
        // PL-01: a prompt that announced an action offers to record exactly
        // that action, in one tap, with the unified vocabulary.
        if (announcedAction != null && announcedLabel != null) builder.addAction(0, "Record $announcedLabel", action("record", announcedAction))
        val notification = builder.build()
        NotificationManagerCompat.from(context).notify("meal-reminder:${rule.id}", 3, notification)
        return true
    }
    fun cancel(context: Context, rule: ReminderRule) {
        CookGuidanceNotifications.cancelAlarm(context, rule.cookId, "reminder:${rule.id}")
        NotificationManagerCompat.from(context).cancel("meal-reminder:${rule.id}", 3)
    }
}
