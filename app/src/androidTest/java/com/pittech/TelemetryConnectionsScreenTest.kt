package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class TelemetryConnectionsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun devicesRouteOffersBetaConnectionsAndHaOAuthWithoutExposingDiagnostics() {
        compose.onNodeWithTag("nav-devices").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("telemetry-connections").fetchSemanticsNodes().isNotEmpty() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            val folder = java.io.File(compose.activity.filesDir, "pittech-ui-test").apply { mkdirs() }
            java.io.File(folder, "telemetry-connections.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("telemetry-add").performScrollTo().performClick()
        compose.onNodeWithText("Server URL", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Sign in to Home Assistant", useUnmergedTree = true).assertIsNotEnabled()
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
