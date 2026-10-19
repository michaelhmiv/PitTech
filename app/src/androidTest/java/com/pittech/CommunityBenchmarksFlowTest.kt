package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.billing.ProPaywallPresentation
import com.pittech.billing.ProPlacement
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 17 native lane through real navigation: the benchmarks entry opens the
 * contextual paywall while signed out, free Insights stay intact around it,
 * and a Live cook plus Finish never show a benchmark surface. The default
 * build is signed out with no store product configured, so no store or server
 * is contacted. */
@RunWith(AndroidJUnit4::class)
class CommunityBenchmarksFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as PitTechApplication

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun closePaywall() {
        compose.onNodeWithTag("pro-paywall-close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isEmpty() }
    }

    private fun assertNoBenchmarkTag() {
        for (tag in listOf("community-benchmarks", "community-empty", "community-unavailable", "community-loading")) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun benchmarksEntryOpensContextualPaywallSignedOut() {
        ProPaywallPresentation.reset()
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        waitFor("nav-insights")
        compose.onNodeWithTag("nav-insights").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
        waitFor("pro-entry-benchmarks")
        compose.onNodeWithTag("pro-entry-benchmarks").performScrollTo().performClick()
        waitFor("pro-paywall")
        compose.onNodeWithTag("pro-signin-required").performScrollTo().assertTextContains("Accounts arrive", substring = true)
        compose.onNodeWithTag("pro-restore").performScrollTo().assertIsDisplayed()
        assertNoBenchmarkTag()
        closePaywall()
        assertEquals(listOf(ProPlacement.BENCHMARKS), ProPaywallPresentation.recorded)
    }

    @Test
    fun freeToolsStayIntactAroundBenchmarksEntry() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        waitFor("nav-insights")
        compose.onNodeWithTag("nav-insights").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start a cook").performScrollTo().assertIsDisplayed()
        waitFor("pro-entry-benchmarks")
        compose.onNodeWithTag("pro-entry-benchmarks").performScrollTo().performClick()
        waitFor("pro-paywall")
        closePaywall()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start a cook").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pro-entry-analysis").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun liveCookAndFinishShowNoBenchmarkSurfaces() {
        ProPaywallPresentation.reset()
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.startCook(NewCookDraft("Saturday brisket", dishes = listOf(DishDraft("Brisket", "Beef"))))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Saturday brisket", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saturday brisket", substring = true).performClick()
        waitFor("live-finish-cook")
        assertNoBenchmarkTag()
        compose.onNodeWithTag("live-finish-cook").performScrollTo().assertIsEnabled().performClick()
        waitFor("finish-cook-dialog")
        assertNoBenchmarkTag()
        compose.onNodeWithTag("finish-cook-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finish-cook-dialog").fetchSemanticsNodes().isEmpty() }
        assertNoBenchmarkTag()
        assertTrue(ProPaywallPresentation.recorded.isEmpty())
    }
}
