package com.pittech.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackIssueUrlTest {
    @Test
    fun bugReportOpensTheBugFormWithEncodedDetailsAndDiagnostics() {
        val url = FeedbackIssueUrl.build(
            kind = FeedbackKind.BUG,
            title = "Cook screen & timer",
            details = "The timer stopped.\nIt happened twice.",
            diagnostics = "Android API 36\nException: IllegalStateException",
        )

        assertTrue(url.startsWith("https://github.com/michaelhmiv/PitTech/issues/new?"))
        assertTrue(url.contains("template=bug_report.yml"))
        assertTrue(url.contains("title=%5BPitTech+Bug%5D+Cook+screen+%26+timer"))
        assertTrue(url.contains("details=The+timer+stopped.%0AIt+happened+twice."))
        assertTrue(url.contains("diagnostics=Android+API+36%0AException%3A+IllegalStateException"))
    }

    @Test
    fun featureRequestUsesItsFormAndNeverIncludesDiagnostics() {
        val url = FeedbackIssueUrl.build(
            kind = FeedbackKind.FEATURE,
            title = "Export cooks",
            details = "Let me export selected cooks.",
            diagnostics = "This must not be included",
        )

        assertTrue(url.contains("template=feature_request.yml"))
        assertTrue(url.contains("title=%5BPitTech+Feature%5D+Export+cooks"))
        assertTrue(url.contains("details=Let+me+export+selected+cooks."))
        assertFalse(url.contains("diagnostics="))
        assertFalse(url.contains("This+must+not+be+included"))
    }

    @Test
    fun longUnicodeInputIsTrimmedBelowGitHubSafeUrlBudget() {
        val url = FeedbackIssueUrl.build(
            kind = FeedbackKind.BUG,
            title = "🔥".repeat(500),
            details = "🥩".repeat(2_000),
            diagnostics = "🌡️".repeat(2_000),
        )

        assertTrue(url.length <= FeedbackIssueUrl.MAX_URL_LENGTH)
        assertTrue(url.contains("template=bug_report.yml"))
    }
}
