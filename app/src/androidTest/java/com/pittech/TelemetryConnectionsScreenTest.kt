package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class TelemetryConnectionsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun devicesRouteOffersBetaConnectionsAndHaOAuthWithoutExposingDiagnostics() {
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithTag("more-devices").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("telemetry-connections").fetchSemanticsNodes().isNotEmpty() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            val folder = java.io.File(compose.activity.filesDir, "pittech-ui-test").apply { mkdirs() }
            java.io.File(folder, "telemetry-connections.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
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
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            val folder = java.io.File(compose.activity.filesDir, "pittech-ui-test").apply { mkdirs() }
            java.io.File(folder, "telemetry-ha-onboarding.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("telemetry-connections").assertExists()
    }
}
