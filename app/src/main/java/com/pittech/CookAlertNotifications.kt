package com.pittech

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pittech.data.CompanionRecord

object CookAlertNotifications {
    const val CHANNEL = "cook_monitoring_alerts"
    /** PL-03 (#172): the firing rule's record id, carried end-to-end so a tap lands on that alert. */
    const val EXTRA_ALERT_ID = "com.pittech.extra.ALERT_ID"
    fun canNotify(context: Context): Boolean {
        if (!CookGuidanceNotifications.notificationsAvailable(context)) return false
        return context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun show(context: Context, record: CompanionRecord, detail: String) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Cook monitoring alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Opt-in temperature and connection alerts" })
        fun action(name: String) = PendingIntent.getBroadcast(context, 0, Intent(context, CookGuidanceReceiver::class.java).setAction("alert_$name").setData(Uri.parse("pittech://alert/${record.id}/$name")).putExtra("cook", record.cookId).putExtra("step", record.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("pittech://cook/${record.cookId}")).putExtra(CookReminderNotifications.EXTRA_COOK_ID, record.cookId).putExtra(EXTRA_ALERT_ID, record.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(record.id, 2, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pittech).setContentTitle(record.title).setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).addAction(0, "Acknowledge", action("acknowledge")).addAction(0, "Snooze 10 min", action("snooze")).build())
    }
    fun cancel(context: Context, id: String) { NotificationManagerCompat.from(context).cancel(id, 2) }

    /** The overnight test alert's notification tag — distinct from every rule's, so it never collides and tests can prove it posted. */
    fun testTag(cookId: String) = "overnight-test:$cookId"

    /**
     * Flowing Slice 2 (E3): the overnight panel's test alert — a REAL
     * notification on the real channel, because rehearsing the wake-up is
     * the entire point: if this does not buzz the phone on the nightstand,
     * the 3 AM stall alert will not either.
     *
     * This answers the PL-29 history below rather than repeating it: the
     * old poster failed because its Acknowledge/Snooze broadcast actions
     * were swallowed by a receiver special case, so the "test" silently
     * did nothing the user could act on. This poster carries NO actions —
     * there is nothing to swallow — and its tap simply opens the cook,
     * like a real alert's content intent.
     */
    fun showTest(context: Context, cookId: String, cookTitle: String) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Cook monitoring alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Opt-in temperature and connection alerts" })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("pittech://cook/$cookId")).putExtra(CookReminderNotifications.EXTRA_COOK_ID, cookId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(testTag(cookId), 3, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pittech).setContentTitle("Test alert — $cookTitle").setContentText("This is the sound and buzz your overnight alerts use. If this reached you, the safety net can wake you.").setStyle(NotificationCompat.BigTextStyle().bigText("This is the sound and buzz your overnight alerts use. If this reached you, the safety net can wake you.")).setContentIntent(open).setAutoCancel(true).build())
    }
    // PL-29 rider (#172, pulled into Slice 4): there is deliberately no
    // test() poster here anymore. A "test alert" used to fire a real
    // HIGH-importance notification whose actions a receiver special case
    // swallowed; test feedback is now routed in-app (see the call sites).
    // (E3's showTest above is the considered exception: it posts on this
    // channel with no actions at all — see its doc comment.)
}
