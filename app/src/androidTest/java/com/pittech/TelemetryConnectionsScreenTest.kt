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
        compose.onNodeWithTag("telemetry-add").performScrollTo().performClick()
        compose.onNodeWithText("Server URL", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Sign in to Home Assistant", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithText("Advanced token setup", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Access token", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("telemetry-connections").assertExists()
    }
}
