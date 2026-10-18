package com.pittech

import android.content.Context
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 16 native lane: starting a Live session and finishing a cook with the
 * paywall code present never presents any Pro surface. */
@RunWith(AndroidJUnit4::class)
class ProPaywallNoInterruptTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertNoProSurface() {
        for (tag in listOf("pro-paywall", "pro-loading", "pro-placeholder", "pro-expired-fallback")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    @Test fun liveStartAndFinishNeverPresentThePaywall() {
        val application = targetContext.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) { application.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithTag("start-cook").performClick()
        compose.onNodeWithTag("cook-add-dish").performClick()
        compose.onNodeWithTag("dish-name").performTextInput("Brisket")
        compose.onNodeWithTag("dish-save").performClick()
        compose.onNodeWithTag("cook-title").performTextInput("Uninterrupted meal")
        compose.onNodeWithTag("cook-save").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("cook-tab-live", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertNoProSurface()

        compose.onNodeWithTag("live-finish-cook").performScrollTo().performClick()
        compose.onNodeWithTag("finish-cook-dialog").assertExists()
        assertNoProSurface()
        compose.onNodeWithTag("finish-cook-save").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking(Dispatchers.IO) { application.database.cookDao().observeCooks().first().single().cook.status == CookStatus.COMPLETED }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("leave-without-saving").fetchSemanticsNodes().isNotEmpty()
        }
        assertNoProSurface()
        compose.onNodeWithTag("leave-without-saving").performScrollTo().performClick()
        assertNoProSurface()
    }
}
