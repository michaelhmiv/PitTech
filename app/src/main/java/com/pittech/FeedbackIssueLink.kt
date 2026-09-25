package com.pittech

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class FeedbackKind(
    val titlePrefix: String,
    val heading: String,
) {
    BUG("[Bug] ", "Problem report"),
    FEATURE("[Feature] ", "Feature request"),
}

/** Builds a GitHub issue draft URL. GitHub requires the user to review and submit the draft. */
internal object FeedbackIssueLink {
    private const val ISSUE_URL = "https://github.com/michaelhmiv/PitTech/issues/new"
    private const val MAX_URL_LENGTH = 6_000
    private const val DIAGNOSTIC_TRUNCATION_NOTICE =
        "\n[Diagnostic excerpt truncated to fit the GitHub draft link.]"
    private const val DESCRIPTION_TRUNCATION_NOTICE =
        "\n[Description excerpt truncated to fit the GitHub draft link.]"

    fun create(
        kind: FeedbackKind,
        title: String,
        description: String,
        appVersion: String,
        androidVersion: String,
        device: String,
        diagnosticReport: CrashDiagnosticReport? = null,
    ): String {
        val safeTitle = title.trim().take(80).ifBlank { kind.heading }
        val issueTitle = kind.titlePrefix + safeTitle
        var descriptionExcerpt = description.trim().take(1_500)
        val fullDiagnostic = diagnosticReport
            ?.takeIf { kind == FeedbackKind.BUG }
            ?.toPlainText()

        fun buildBody(descriptionText: String, diagnosticText: String?): String = buildString {
            appendLine("## ${kind.heading}")
            appendLine(descriptionText.ifBlank { "(No description provided)" })
            appendLine()
            appendLine("## App and device")
            appendLine("- PitTech: ${appVersion.take(40)}")
            appendLine("- Android: ${androidVersion.take(40)}")
            append("- Device: ${device.take(80)}")
            if (diagnosticText != null) {
                appendLine()
                appendLine()
                appendLine("## Saved crash diagnostics")
                appendLine("The app's saved report is included below. Please review it before submitting.")
                appendLine("```text")
                append(diagnosticText)
                if (!diagnosticText.endsWith('\n')) appendLine()
                append("```")
            }
        }

        fun buildUrl(body: String): String = "$ISSUE_URL?title=${encode(issueTitle)}&body=${encode(body)}"
        fun candidateUrl(descriptionText: String, diagnosticText: String?): String =
            buildUrl(buildBody(descriptionText, diagnosticText))

        if (fullDiagnostic != null) {
            val minimumDiagnosticExcerpt = DIAGNOSTIC_TRUNCATION_NOTICE
            if (candidateUrl(descriptionExcerpt, minimumDiagnosticExcerpt).length > MAX_URL_LENGTH) {
                descriptionExcerpt = largestFittingPrefix(descriptionExcerpt, DESCRIPTION_TRUNCATION_NOTICE) { excerpt ->
                    candidateUrl(excerpt, minimumDiagnosticExcerpt)
                } ?: ""
            }
            val diagnosticExcerpt = if (
                candidateUrl(descriptionExcerpt, fullDiagnostic).length <= MAX_URL_LENGTH
            ) {
                fullDiagnostic
            } else {
                largestFittingPrefix(fullDiagnostic, DIAGNOSTIC_TRUNCATION_NOTICE) { excerpt ->
                    candidateUrl(descriptionExcerpt, excerpt)
                } ?: minimumDiagnosticExcerpt
            }
            return candidateUrl(descriptionExcerpt, diagnosticExcerpt)
        }

        val fullUrl = candidateUrl(descriptionExcerpt, null)
        if (fullUrl.length <= MAX_URL_LENGTH) return fullUrl
        descriptionExcerpt = largestFittingPrefix(descriptionExcerpt, DESCRIPTION_TRUNCATION_NOTICE) { excerpt ->
            candidateUrl(excerpt, null)
        } ?: ""
        return candidateUrl(descriptionExcerpt, null)
    }

    private fun largestFittingPrefix(
        text: String,
        truncationNotice: String,
        buildCandidate: (String) -> String,
    ): String? {
        var low = 0
        var high = text.length - 1
        var best: String? = null
        while (low <= high) {
            val middle = (low + high) / 2
            val candidateText = text.take(middle) + truncationNotice
            if (buildCandidate(candidateText).length <= MAX_URL_LENGTH) {
                best = candidateText
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
