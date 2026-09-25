package com.pittech.ui

import com.pittech.CrashDiagnosticReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackDiagnosticBundleTest {
    @Test
    fun bundleIncludesVersionDeviceRecentActionsAndCrashSummary() {
        val report = CrashDiagnosticReport(
            referenceCode = "PT-AB12CD34",
            occurredAtUtc = "2026-09-25 14:00:00 UTC",
            source = "Uncaught application exception",
            summary = "IllegalStateException",
            details = "java.lang.IllegalStateException: synthetic test",
        )

        val bundle = FeedbackDiagnosticBundle.format(
            appVersion = "0.2.0",
            versionCode = 12,
            androidRelease = "17",
            androidApi = 37,
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            recentEvents = listOf("14:00:00 UTC — Opened Settings"),
            crashReport = report,
        )

        assertTrue(bundle.contains("PitTech version: 0.2.0 (build 12)"))
        assertTrue(bundle.contains("Android: 17 (API 37)"))
        assertTrue(bundle.contains("Device: Google Pixel 8 Pro"))
        assertTrue(bundle.contains("Opened Settings"))
        assertTrue(bundle.contains("PT-AB12CD34"))
        assertTrue(bundle.contains("IllegalStateException"))
        assertFalse(bundle.contains("cook notes"))
        assertTrue(bundle.length <= FeedbackDiagnosticBundle.MAX_CHARS)
    }

    @Test
    fun bundleWorksWithoutSavedCrashReport() {
        val bundle = FeedbackDiagnosticBundle.format(
            appVersion = "0.2.0",
            versionCode = 12,
            androidRelease = "17",
            androidApi = 37,
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            recentEvents = emptyList(),
            crashReport = null,
        )

        assertTrue(bundle.contains("No recent activity recorded."))
        assertFalse(bundle.contains("Most recent saved crash report"))
    }
}
