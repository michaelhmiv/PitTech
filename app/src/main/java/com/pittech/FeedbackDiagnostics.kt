package com.pittech

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class FeedbackEvent(val label: String) {
    APP_OPENED("App opened"),
    OPENED_COOKS("Opened Cooks"),
    OPENED_INSIGHTS("Opened Insights"),
    OPENED_DEVICES("Opened Devices"),
    OPENED_SETTINGS("Opened Settings"),
    STARTED_COOK("Started a cook"),
}

internal object FeedbackEventLog {
    private const val PREFERENCES = "pittech-feedback-diagnostics"
    private const val EVENTS_KEY = "recent-events"
    private const val MAX_EVENTS = 20

    @Synchronized
    fun record(context: Context, event: FeedbackEvent) {
        runCatching {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE,
            )
            val previous = preferences.getString(EVENTS_KEY, "")
                .orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            val timestamp = SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
            val recent = (previous + "$timestamp — ${event.label}").takeLast(MAX_EVENTS)
            preferences.edit().putString(EVENTS_KEY, recent.joinToString("\n")).apply()
        }
    }

    fun recent(context: Context): List<String> = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(EVENTS_KEY, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_EVENTS)
    }.getOrDefault(emptyList())
}
