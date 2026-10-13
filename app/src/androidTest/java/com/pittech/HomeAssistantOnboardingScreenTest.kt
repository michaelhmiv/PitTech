package com.pittech

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeAssistantOnboardingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun advancedHaComponentExplainsUserOwnedTokenSetup() {
        compose.activity.setContent {
            com.pittech.ui.PitTechTheme {
                com.pittech.ui.HomeAssistantOnboardingScreen(onBack = {})
            }
        }
        compose.onNodeWithText("Home Assistant server URL", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Access token", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Connect and find sensors", useUnmergedTree = true).assertExists()
    }
}
