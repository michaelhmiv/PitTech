package com.pittech

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.billing.ProPaywallPresentation
import com.pittech.billing.ProPlacement
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 18 native lane through real navigation: the personal entries open the
 * contextual paywall while signed out, free Insights stay intact around them,
 * and a Live cook plus Finish never show a Pro surface. The default build is
 * signed out with no store product configured, so no store or server is
 * contacted. */
@RunWith(AndroidJUnit4::class)
class ProPersonalFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as PitTechApplication

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun closePaywall() {
        compose.onNodeWithTag("pro-paywall-close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isEmpty() }
    }

    private fun assertNoProTag() {
        for (tag in listOf("pro-analysis", "pro-curves", "pro-customization", "pro-more-hub", "pro-recap-scope", "pro-recap-preview")) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun personalEntriesOpenContextualPaywallSignedOut() {
        ProPaywallPresentation.reset()
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        waitFor("nav-insights")
        compose.onNodeWithTag("nav-insights").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
        waitFor("pro-entry-analysis")
        compose.onNodeWithTag("pro-entry-analysis").performScrollTo().performClick()
        waitFor("pro-paywall")
        compose.onNodeWithTag("pro-signin-required").performScrollTo().assertTextContains("Accounts arrive", substring = true)
        assertNoProTag()
        closePaywall()
        compose.onNodeWithTag("pro-entry-curves").performScrollTo().performClick()
        waitFor("pro-paywall")
        assertNoProTag()
        closePaywall()
        assertEquals(listOf(ProPlacement.ANALYSIS, ProPlacement.CURVES), ProPaywallPresentation.recorded)
    }

    @Test
    fun freeToolsStayIntactAroundPersonalEntries() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        waitFor("nav-insights")
        compose.onNodeWithTag("nav-insights").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start a cook").performScrollTo().assertIsDisplayed()
        waitFor("pro-entry-analysis")
        compose.onNodeWithTag("pro-entry-analysis").performScrollTo().performClick()
        waitFor("pro-paywall")
        closePaywall()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start a cook").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pro-entry-analysis").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pro-entry-curves").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun liveCookAndFinishShowNoProSurfaces() {
        ProPaywallPresentation.reset()
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.startCook(NewCookDraft("Saturday brisket", dishes = listOf(DishDraft("Brisket", "Beef"))))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Saturday brisket", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saturday brisket", substring = true).performClick()
        waitFor("cook-menu-button")
        assertNoProTag()
        // Flowing Slice 2 (1.6): Finish lives in the cook menu now.
        compose.onNodeWithTag("cook-menu-button").performClick()
        compose.onNodeWithTag("live-finish-cook").assertIsEnabled().performClick()
        waitFor("finish-cook-dialog")
        assertNoProTag()
        compose.onNodeWithTag("finish-cook-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finish-cook-dialog").fetchSemanticsNodes().isEmpty() }
        assertNoProTag()
        assertTrue(ProPaywallPresentation.recorded.isEmpty())
    }
}
