package com.pittech.ui

import android.content.Context
import android.os.Build
import com.pittech.BuildConfig
import com.pittech.CrashDiagnostics
import com.pittech.CrashDiagnosticReport
import com.pittech.FeedbackEventLog

internal object FeedbackDiagnosticBundle {
    internal const val MAX_CHARS = 1_800
    private const val MAX_CRASH_DETAILS_CHARS = 950

    fun create(context: Context): String = format(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        androidRelease = Build.VERSION.RELEASE ?: "Unknown",
        androidApi = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        recentEvents = FeedbackEventLog.recent(context),
        crashReport = CrashDiagnostics.pendingReport(context),
    )

    internal fun format(
        appVersion: String,
        versionCode: Int,
        androidRelease: String,
        androidApi: Int,
        manufacturer: String,
        model: String,
        recentEvents: List<String>,
        crashReport: CrashDiagnosticReport?,
    ): String = buildString {
        appendLine("PitTech version: ${clean(appVersion)} (build $versionCode)")
        appendLine("Android: ${clean(androidRelease)} (API $androidApi)")
        appendLine("Device: ${clean(manufacturer)} ${clean(model)}")
        appendLine()
        appendLine("Recent app activity (screen/action names only):")
        if (recentEvents.isEmpty()) {
            appendLine("No recent activity recorded.")
        } else {
            recentEvents.takeLast(20).forEach { appendLine(it) }
        }

        if (crashReport != null) {
            appendLine()
            appendLine("Most recent saved crash report:")
            appendLine("Reference: ${clean(crashReport.referenceCode)}")
            appendLine("Occurred: ${clean(crashReport.occurredAtUtc)}")
            appendLine("Source: ${clean(crashReport.source)}")
            appendLine("Summary: ${clean(crashReport.summary)}")
            appendLine("Technical details (truncated):")
            append(crashReport.details.take(MAX_CRASH_DETAILS_CHARS))
        }
    }.trim().take(MAX_CHARS)

    private fun clean(value: String): String =
        value.filterNot(Char::isISOControl).take(240)
}
