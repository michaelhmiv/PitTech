package com.pittech

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pittech.data.CompanionRecord

object CookAlertNotifications {
    const val CHANNEL = "cook_monitoring_alerts"
    fun canNotify(context: Context): Boolean {
        if (!CookGuidanceNotifications.canNotify(context)) return false
        return context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun show(context: Context, record: CompanionRecord, detail: String) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Cook monitoring alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Opt-in temperature and connection alerts" })
        fun action(name: String) = PendingIntent.getBroadcast(context, 0, Intent(context, CookGuidanceReceiver::class.java).setAction("alert_$name").setData(Uri.parse("pittech://alert/${record.id}/$name")).putExtra("cook", record.cookId).putExtra("step", record.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("pittech://cook/${record.cookId}")).putExtra(CookReminderNotifications.EXTRA_COOK_ID, record.cookId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(record.id, 2, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pittech).setContentTitle(record.title).setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).addAction(0, "Acknowledge", action("acknowledge")).addAction(0, "Snooze 10 min", action("snooze")).build())
    }
    fun cancel(context: Context, id: String) { NotificationManagerCompat.from(context).cancel(id, 2) }
    fun test(context: Context) = show(context, CompanionRecord("test-alert", "alert", title = "PitTech test alert", payload = "{}", createdAtUtcMillis = 0, updatedAtUtcMillis = 0), "Cook monitoring alerts are enabled on this phone.")
}
