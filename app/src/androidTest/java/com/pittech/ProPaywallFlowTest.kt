package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.billing.ProPaywallPresentation
import com.pittech.billing.ProPlacement
import com.pittech.domain.RecipeSnapshot
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 16 native lane through real navigation: the contextual paywall opens
 * from Pro entry points in Insights, More, and the share-card preview, and
 * never interrupts a Live cook or Finish. The default build is signed out
 * with no store product configured, so no store or server is contacted. */
@RunWith(AndroidJUnit4::class)
class ProPaywallFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as PitTechApplication

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openPaywall(entryTag: String) {
        waitFor(entryTag)
        compose.onNodeWithTag(entryTag).performScrollTo().performClick()
        waitFor("pro-paywall")
    }

    private fun closePaywall() {
        compose.onNodeWithTag("pro-paywall-close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isEmpty() }
    }

    private fun assertNoProSurface() {
        for (tag in listOf("pro-paywall", "pro-loading", "pro-placeholder", "pro-analysis", "pro-curves", "pro-customization", "pro-more-hub", "pro-recap-scope", "pro-recap-preview", "pro-expired-fallback")) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun proEntriesOpenContextualPaywallWithLinksAndClose() {
        ProPaywallPresentation.reset()
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        waitFor("nav-insights")
        compose.onNodeWithTag("nav-insights").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
        openPaywall("pro-entry-analysis")
        compose.onNodeWithTag("pro-restore").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pro-manage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pro-privacy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pro-terms").performScrollTo().assertIsDisplayed()
        closePaywall()
        openPaywall("pro-entry-curves")
        closePaywall()
        compose.onNodeWithTag("nav-more").performClick()
        waitFor("more-pro")
        compose.onNodeWithTag("more-pro").performScrollTo().performClick()
        waitFor("pro-paywall")
        closePaywall()
        assertEquals(
            listOf(ProPlacement.ANALYSIS, ProPlacement.CURVES, ProPlacement.MORE),
            ProPaywallPresentation.recorded,
        )
    }

    @Test
    fun shareCardThemesEntryOpensThePaywall() {
        ProPaywallPresentation.reset()
        val recipe = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.recipes.save(RecipeSnapshot("Pepper pork", DishDraft("Pepper pork", "Pork")))
        }
        compose.activityRule.scenario.recreate()
        waitFor("nav-recipes")
        compose.onNodeWithTag("nav-recipes").performClick()
        waitFor("recipe-open-${recipe.id}")
        compose.onNodeWithTag("recipe-open-${recipe.id}").performScrollTo().performClick()
        compose.onNodeWithTag("recipe-share").performScrollTo().performClick()
        waitFor("sharing-preview-card")
        compose.onNodeWithTag("sharing-preview-card").performScrollTo().performClick()
        waitFor("share-card-preview")
        openPaywall("pro-entry-themes")
        closePaywall()
        assertEquals(listOf(ProPlacement.THEMES), ProPaywallPresentation.recorded)
    }

    @Test
    fun unconfiguredProductShowsNoPriceAndNoPurchase() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        compose.activityRule.scenario.recreate()
        waitFor("nav-insights")
        compose.onNodeWithTag("nav-insights").performClick()
        openPaywall("pro-entry-analysis")
        compose.onNodeWithTag("pro-product-unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pro-check-again").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("pro-price").assertCountEquals(0)
        compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
        compose.onNodeWithTag("pro-signin-required").performScrollTo().assertIsDisplayed()
        closePaywall()
    }

    @Test
    fun liveCookAndFinishNeverPresentThePaywall() {
        ProPaywallPresentation.reset()
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.cookRepository.startCook(NewCookDraft("Saturday brisket", dishes = listOf(DishDraft("Brisket", "Beef"))))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Saturday brisket", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saturday brisket", substring = true).performClick()
        waitFor("live-finish-cook")
        assertNoProSurface()
        compose.onNodeWithTag("live-finish-cook").performScrollTo().assertIsEnabled().performClick()
        waitFor("finish-cook-dialog")
        assertNoProSurface()
        compose.onNodeWithTag("finish-cook-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finish-cook-dialog").fetchSemanticsNodes().isEmpty() }
        assertNoProSurface()
        assertTrue(ProPaywallPresentation.recorded.isEmpty())
    }
}
