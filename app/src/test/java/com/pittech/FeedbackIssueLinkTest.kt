package com.pittech

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackIssueLinkTest {
    @Test
    fun bugDraftIncludesDiagnosticReportAndDeviceContext() {
        val report = CrashDiagnosticReport(
            referenceCode = "PT-AB12CD34",
            occurredAtUtc = "2026-09-25 12:00:00 UTC",
            source = "Uncaught application exception",
            summary = "IllegalStateException: failed to save",
            details = "Thread: main\nstack frame",
        )

        val url = FeedbackIssueLink.create(
            kind = FeedbackKind.BUG,
            title = "App closes after saving",
            description = "It happened when I saved a cook.",
            appVersion = "0.1.0",
            androidVersion = "17 (API 37)",
            device = "Google Pixel 8 Pro",
            diagnosticReport = report,
        )
        val query = queryParameters(url)
        val body = query.getValue("body")

        assertEquals("[Bug] App closes after saving", query.getValue("title"))
        assertTrue(body.contains("Google Pixel 8 Pro"))
        assertTrue(body.contains("PT-AB12CD34"))
        assertTrue(body.contains("IllegalStateException: failed to save"))
        assertTrue(body.contains("stack frame"))
    }

    @Test
    fun featureDraftDoesNotIncludeCrashReport() {
        val report = CrashDiagnosticReport("PT-AB12CD34", "now", "crash", "failure", "private trace")

        val query = queryParameters(
            FeedbackIssueLink.create(
                kind = FeedbackKind.FEATURE,
                title = "Add cook timer",
                description = "Let me set a timer.",
                appVersion = "0.1.0",
                androidVersion = "17 (API 37)",
                device = "Google Pixel 8 Pro",
                diagnosticReport = report,
            ),
        )

        assertEquals("[Feature] Add cook timer", query.getValue("title"))
        assertFalse(query.getValue("body").contains("private trace"))
        assertFalse(query.getValue("body").contains("Saved crash diagnostics"))
    }

    @Test
    fun longDiagnosticDraftStaysWithinLinkLimitAndMarksTruncation() {
        val report = CrashDiagnosticReport("PT-AB12CD34", "now", "crash", "failure", "frame ".repeat(4_000))
        val url = FeedbackIssueLink.create(
            kind = FeedbackKind.BUG,
            title = "A crash",
            description = "It crashed.",
            appVersion = "0.1.0",
            androidVersion = "17 (API 37)",
            device = "Google Pixel 8 Pro",
            diagnosticReport = report,
        )

        assertTrue(url.length <= 6_000)
        assertTrue(queryParameters(url).getValue("body").contains("Diagnostic excerpt truncated"))
    }

    @Test
    fun unusuallyLongDescriptionIsShortenedToFitDraftUrl() {
        val url = FeedbackIssueLink.create(
            kind = FeedbackKind.FEATURE,
            title = "Add timer",
            description = "🔥".repeat(1_500),
            appVersion = "0.1.0",
            androidVersion = "17 (API 37)",
            device = "Google Pixel 8 Pro",
        )

        assertTrue(url.length <= 6_000)
        assertTrue(queryParameters(url).getValue("body").contains("Description excerpt truncated"))
    }

    private fun queryParameters(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { parameter ->
            val (key, value) = parameter.split('=', limit = 2)
            URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }
}
