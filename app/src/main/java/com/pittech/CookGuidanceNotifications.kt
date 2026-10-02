package com.pittech

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pittech.domain.EvaluatedStep
import kotlinx.coroutines.*

/** Local one-shot wakes; the receiver rechecks persisted state before displaying or acting. */
object CookGuidanceNotifications {
    const val CHANNEL = "cook_guidance"
    fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    fun preciseAvailable(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    private fun pending(context: Context, cook: String, step: String, action: String = "wake", occurrence: Int = 0): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, CookGuidanceReceiver::class.java).setAction(action).setData(Uri.parse("pittech://guidance/$cook/$step/$action/$occurrence"))
            .putExtra("cook", cook).putExtra("step", step).putExtra("occurrence", occurrence), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, cook: String, step: String, at: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val precise = context.getSharedPreferences("pittech-preferences", 0).getBoolean("precise-cook-timers", false) && preciseAvailable(context)
        try { if (precise) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, cook, step))
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, cook, step)) }
        catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, cook, step)) }
    }
    fun cancelAlarm(context: Context, cook: String, step: String) { context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, cook, step)) }
    fun cancel(context: Context, cook: String, step: String) { cancelAlarm(context, cook, step); NotificationManagerCompat.from(context).cancel("guidance:$cook:$step", 1) }
    fun show(context: Context, cook: String, e: EvaluatedStep) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Cook guidance", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Checks and actions in a cook plan" })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("pittech://cook/$cook")).putExtra(CookReminderNotifications.EXTRA_COOK_ID, cook), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(e.step.title)
            .setContentText(e.step.instructions.ifBlank { "Check your food, then confirm what you did." }).setContentIntent(open).setAutoCancel(true)
            .addAction(0, "Done", pending(context, cook, e.step.id, "done", e.progress.occurrence))
            .addAction(0, "Snooze 10 min", pending(context, cook, e.step.id, "snooze", e.progress.occurrence)).build()
        NotificationManagerCompat.from(context).notify("guidance:$cook:${e.step.id}", 1, n)
    }
}

class CookGuidanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val cook = intent?.getStringExtra("cook") ?: return
        val step = intent.getStringExtra("step") ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as PitTechApplication).companionRepository
                val occurrence = intent.getIntExtra("occurrence", 0)
                if (step.startsWith("alert:")) {
                    val app = context.applicationContext as PitTechApplication
                    if (intent.action?.startsWith("alert_") == true) {
                        app.alertRepository.change(step, intent.action!!.removePrefix("alert_"))
                        CookAlertNotifications.cancel(context, step)
                    }
                    app.alertRepository.reconcile(context, cook)
                    return@launch
                }
                when (intent.action) {
                    "done" -> { repo.completeStep(cook, step, occurrence); CookGuidanceNotifications.cancel(context, cook, step) }
                    "snooze" -> { repo.snooze(cook, step, occurrence, System.currentTimeMillis() + 600_000); CookGuidanceNotifications.cancel(context, cook, step) }
                }
                repo.reconcile(context, cook)
            } finally { result.finish() }
        }
    }
}
