package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeAssistantOnboardingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun developmentDevicesRouteExplainsSelfHostedReadOnlySetupAndReturns() {
        compose.onNodeWithTag("nav-devices").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Connect Home Assistant · Beta", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Connect Home Assistant · Beta", useUnmergedTree = true)
            .performClick()
        compose.onNodeWithText("Home Assistant server URL", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Access token", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Connect and find sensors", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Back to Devices", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Connect Home Assistant · Beta", useUnmergedTree = true).assertExists()
    }
}
