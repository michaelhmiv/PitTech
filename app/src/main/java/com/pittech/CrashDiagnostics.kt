package com.pittech

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.UUID
import kotlin.system.exitProcess

internal data class CrashDiagnosticReport(
    val referenceCode: String,
    val occurredAtUtc: String,
    val source: String,
    val summary: String,
    val details: String,
) {
    fun toPlainText(): String = buildString {
        appendLine("PitTech local diagnostic report")
        appendLine("Reference code: $referenceCode")
        appendLine("Occurred (UTC): $occurredAtUtc")
        appendLine("Source: $source")
        appendLine("Summary: $summary")
        appendLine()
        append(details)
    }.trimEnd()
}

internal object CrashDiagnostics {
    private const val REPORT_FILE = "pittech-last-crash.properties"
    private const val PREFERENCES = "pittech-diagnostics"
    private const val LAST_EXIT_TIMESTAMP = "last-exit-timestamp"
    private const val MAX_STACK_TRACE_LENGTH = 24_000

    fun install(application: Application) {
        runCatching { recordPreviousSystemExit(application) }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordUncaughtException(application, thread, throwable) }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    internal fun recordUncaughtException(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ): CrashDiagnosticReport {
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString().take(MAX_STACK_TRACE_LENGTH)

        val exceptionType = throwable.javaClass.name
        val message = throwable.localizedMessage?.replace('\n', ' ')?.take(300).orEmpty()
        val summary = if (message.isBlank()) exceptionType else "$exceptionType: $message"
        val report = CrashDiagnosticReport(
            referenceCode = newReferenceCode(),
            occurredAtUtc = formatUtc(System.currentTimeMillis()),
            source = "Uncaught application exception",
            summary = summary,
            details = buildString {
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: $exceptionType")
                appendLine()
                append(stackTrace)
            },
        )
        saveReport(context, report)
        return report
    }

    internal fun pendingReport(context: Context): CrashDiagnosticReport? {
        val file = File(context.filesDir, REPORT_FILE)
        if (!file.isFile) return null

        return runCatching {
            val properties = Properties()
            FileInputStream(file).use { properties.load(it) }
            CrashDiagnosticReport(
                referenceCode = properties.getProperty("referenceCode").orEmpty(),
                occurredAtUtc = properties.getProperty("occurredAtUtc").orEmpty(),
                source = properties.getProperty("source").orEmpty(),
                summary = properties.getProperty("summary").orEmpty(),
                details = properties.getProperty("details").orEmpty(),
            ).takeIf { it.referenceCode.isNotBlank() && it.summary.isNotBlank() }
        }.getOrNull()
    }

    internal fun clearPendingReport(context: Context) {
        runCatching { File(context.filesDir, REPORT_FILE).delete() }
    }

    private fun recordPreviousSystemExit(application: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pendingReport(application) != null) return

        val activityManager = application.getSystemService(ActivityManager::class.java) ?: return
        val exitInfo = activityManager
            .getHistoricalProcessExitReasons(application.packageName, 0, 10)
            .maxByOrNull { it.timestamp }
            ?: return

        val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val lastObservedTimestamp = preferences.getLong(LAST_EXIT_TIMESTAMP, 0L)
        if (exitInfo.timestamp <= lastObservedTimestamp) return

        preferences.edit().putLong(LAST_EXIT_TIMESTAMP, exitInfo.timestamp).apply()
        if (!isUnexpectedExit(exitInfo.reason)) return

        val reason = exitReasonName(exitInfo.reason)
        val systemDescription = exitInfo.description?.takeIf { it.isNotBlank() }
        val report = CrashDiagnosticReport(
            referenceCode = newReferenceCode(),
            occurredAtUtc = formatUtc(exitInfo.timestamp),
            source = "Android process-exit record",
            summary = buildString {
                append("Android reported ")
                append(reason)
                if (systemDescription != null) {
                    append(": ")
                    append(systemDescription.take(500))
                }
            },
            details = buildString {
                appendLine("Android exit reason: $reason (${exitInfo.reason})")
                appendLine("Process: ${exitInfo.processName}")
                appendLine("Process ID: ${exitInfo.pid}")
                appendLine("Exit status: ${exitInfo.status}")
                if (systemDescription != null) {
                    appendLine()
                    appendLine("System description:")
                    append(systemDescription)
                }
            },
        )
        saveReport(application, report)
    }

    private fun isUnexpectedExit(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_OTHER -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            reason == ApplicationExitInfo.REASON_FREEZER
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "application crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "app not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "app startup failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "system memory pressure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Android dependency stopped"
        ApplicationExitInfo.REASON_SIGNALED -> "process stopped by an OS signal"
        ApplicationExitInfo.REASON_OTHER -> "Android stopped the app"
        else -> "app process stopped"
    }

    private fun saveReport(context: Context, report: CrashDiagnosticReport) {
        val properties = Properties().apply {
            setProperty("referenceCode", report.referenceCode)
            setProperty("occurredAtUtc", report.occurredAtUtc)
            setProperty("source", report.source)
            setProperty("summary", report.summary)
            setProperty("details", report.details)
        }
        val file = File(context.filesDir, REPORT_FILE)
        FileOutputStream(file).use { output ->
            properties.store(output, "PitTech local crash diagnostic")
            output.fd.sync()
        }
    }

    private fun newReferenceCode(): String =
        "PT-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.ROOT)

    private fun formatUtc(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timestamp))
}
