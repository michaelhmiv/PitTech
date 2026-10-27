package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class TelemetryConnectionsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    // The emulator harness pulls this test's screenshots from the app
    // sandbox as required evidence and fails the cell when one is absent.
    // A single takeScreenshot() can return null under emulator load, which
    // the old `?.let` silently skipped — the suite passed and the harness
    // then failed the cell on the missing file (PR #222, API 36 / font
    // 1.15). Retry the capture, and fail HERE, where the cause is visible,
    // if it stays unavailable.
    private fun captureRequired(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        var bitmap: android.graphics.Bitmap? = null
        var attempts = 0
        while (bitmap == null && attempts < 10) {
            bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap == null) { attempts++; Thread.sleep(300) }
        }
        val shot = bitmap ?: throw AssertionError("Required evidence screenshot '$name' could not be captured: takeScreenshot() returned null after 10 attempts")
        val folder = java.io.File(compose.activity.filesDir, "pittech-ui-test").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }
    @Test fun devicesRouteOffersBetaConnectionsAndHaOAuthWithoutExposingDiagnostics() {
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("more-devices").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("telemetry-connections").fetchSemanticsNodes().isNotEmpty() }
        captureRequired("telemetry-connections")
        // PL-05 (#172): the Connect → Attach → Assign stepper is visible and
        // every step renders its body.
        compose.onNodeWithTag("telemetry-step-1").assertExists()
        compose.onNodeWithTag("telemetry-step-2").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Attach to meal", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Connect a connection first", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("telemetry-step-3").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Apply assignments", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Nothing to assign yet", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("telemetry-step-1").performScrollTo().performClick()
        compose.onNodeWithTag("telemetry-add").performScrollTo().performClick()
        compose.onNodeWithText("Server URL", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("telemetry-ha-authorize").assertIsNotEnabled()
        compose.onNodeWithText("Advanced token setup", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Access token", useUnmergedTree = true).assertExists()
        captureRequired("telemetry-ha-onboarding")
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("telemetry-connections").assertExists()
    }
}
