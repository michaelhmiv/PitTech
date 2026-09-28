package com.pittech

internal enum class FeedbackKind {
    BUG,
    FEATURE,
    DEVICE_DIAGNOSTIC,
}

internal data class FeedbackRequest(
    val kind: FeedbackKind,
    val title: String,
    val description: String,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val diagnosticReport: FeedbackDiagnosticReport? = null,
) {
    fun toJson(): String = buildString {
        append("{")
        appendJsonField("kind", kind.name)
        append(",")
        appendJsonField("title", title.trim().take(120))
        append(",")
        appendJsonField("description", description.trim().take(1_500))
        append(",")
        appendJsonField("appVersion", appVersion.take(40))
        append(",")
        appendJsonField("androidVersion", androidVersion.take(40))
        append(",")
        appendJsonField("device", device.take(120))

        val report = diagnosticReport.takeIf { kind != FeedbackKind.FEATURE }
        if (report != null) {
            append(",\"diagnosticReport\":{")
            appendJsonField("referenceCode", report.referenceCode.take(64))
            append(",")
            appendJsonField("occurredAtUtc", report.occurredAtUtc.take(80))
            append(",")
            appendJsonField("source", report.source.take(120))
            append(",")
            appendJsonField("summary", report.summary.take(500))
            append(",")
            appendJsonField("details", report.details.take(46_000))
            append("}")
        }
        append("}")
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append("\"")
        append(name)
        append("\":\"")
        append(escapeJson(value))
        append("\"")
    }

    private fun escapeJson(value: String): String = buildString(value.length + 16) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
}

internal sealed interface FeedbackSubmitResult {
    data class Success(val issueNumber: Int?) : FeedbackSubmitResult
    data class Failure(val message: String) : FeedbackSubmitResult
}
