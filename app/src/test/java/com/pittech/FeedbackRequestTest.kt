package com.pittech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackRequestTest {
    @Test
    fun bugPayloadIncludesOptedInDiagnosticsAndEscapesJson() {
        val report = CrashDiagnosticReport(
            referenceCode = "PT-AB12CD34",
            occurredAtUtc = "2026-09-28 14:00:00 UTC",
            source = "Uncaught application exception",
            summary = "IllegalStateException: \"failed\"",
            details = "Thread: main\nstack\\frame",
        )

        val json = FeedbackRequest(
            kind = FeedbackKind.BUG,
            title = "App closes after saving",
            description = "It happened when I saved a cook.",
            appVersion = "0.1.0",
            androidVersion = "17 (API 37)",
            device = "Google Pixel 8 Pro",
            diagnosticReport = report,
        ).toJson()

        assertTrue(json.contains("\"kind\":\"BUG\""))
        assertTrue(json.contains("PT-AB12CD34"))
        assertTrue(json.contains("\\\"failed\\\""))
        assertTrue(json.contains("stack\\\\frame"))
    }

    @Test
    fun deviceDiagnosticPayloadIncludesBleCapture() {
        val report = FeedbackDiagnosticReport(
            referenceCode = "BLE-12345678",
            occurredAtUtc = "2026-09-28 15:00:00 UTC",
            source = "Bluetooth controller discovery",
            summary = "Unverified BLE device; 8 observations captured.",
            details = "Address: 11:22:33:44:55:66\\nRaw advertisement: 020106",
        )
        val json = FeedbackRequest(
            kind = FeedbackKind.DEVICE_DIAGNOSTIC,
            title = "BLE controller report",
            description = "Unverified controller.",
            appVersion = "0.1.0-dev",
            androidVersion = "17 (API 37)",
            device = "Google Pixel 8 Pro",
            diagnosticReport = report,
        ).toJson()
        assertTrue(json.contains("\\"kind\\":\\"DEVICE_DIAGNOSTIC\\""))
        assertTrue(json.contains("11:22:33:44:55:66"))
        assertTrue(json.contains("020106"))
    }

    @Test
    fun featurePayloadNeverIncludesCrashDiagnostics() {
        val report = CrashDiagnosticReport(
            referenceCode = "PT-PRIVATE",
            occurredAtUtc = "now",
            source = "crash",
            summary = "private",
            details = "private trace",
        )

        val json = FeedbackRequest(
            kind = FeedbackKind.FEATURE,
            title = "Add a cook timer",
            description = "Let me set a timer.",
            appVersion = "0.1.0",
            androidVersion = "17 (API 37)",
            device = "Google Pixel 8 Pro",
            diagnosticReport = report,
        ).toJson()

        assertTrue(json.contains("\"kind\":\"FEATURE\""))
        assertFalse(json.contains("diagnosticReport"))
        assertFalse(json.contains("private trace"))
    }
}
