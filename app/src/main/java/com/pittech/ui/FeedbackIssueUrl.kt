package com.pittech.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class FeedbackKind(
    val issueTemplate: String,
    val defaultTitle: String,
    val titleCategory: String,
) {
    BUG("bug_report.yml", "Bug report", "Bug"),
    FEATURE("feature_request.yml", "Feature request", "Feature"),
}

internal object FeedbackIssueUrl {
    private const val NEW_ISSUE_URL = "https://github.com/michaelhmiv/PitTech/issues/new"
    internal const val MAX_URL_LENGTH = 7_000
    private const val MAX_TITLE_LENGTH = 100
    private const val MAX_DETAILS_LENGTH = 1_800
    private const val MAX_DIAGNOSTICS_LENGTH = 1_800

    fun build(
        kind: FeedbackKind,
        title: String,
        details: String,
        diagnostics: String = "",
    ): String {
        val safeTitle = title
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TITLE_LENGTH)
            .ifBlank { kind.defaultTitle }
        var safeDetails = details.trim().take(MAX_DETAILS_LENGTH)
        var safeDiagnostics = if (kind == FeedbackKind.BUG) {
            diagnostics.trim().take(MAX_DIAGNOSTICS_LENGTH)
        } else {
            ""
        }

        fun render(): String {
            val parameters = buildList {
                add("template" to kind.issueTemplate)
                add("title" to "[PitTech ${kind.titleCategory}] $safeTitle")
                if (safeDetails.isNotBlank()) add("details" to safeDetails)
                if (safeDiagnostics.isNotBlank()) add("diagnostics" to safeDiagnostics)
            }
            return NEW_ISSUE_URL + "?" + parameters.joinToString("&") { (key, value) ->
                "$key=${encode(value)}"
            }
        }

        var url = render()
        while (url.length > MAX_URL_LENGTH && safeDiagnostics.isNotEmpty()) {
            safeDiagnostics = safeDiagnostics.dropLast((safeDiagnostics.length / 8).coerceAtLeast(1))
            url = render()
        }
        while (url.length > MAX_URL_LENGTH && safeDetails.isNotEmpty()) {
            safeDetails = safeDetails.dropLast((safeDetails.length / 8).coerceAtLeast(1))
            url = render()
        }
        return url
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
