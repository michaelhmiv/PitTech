package com.pittech

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.ui.WelcomePreferences
import org.junit.Rule
import org.junit.Test

/**
 * GA4 DebugView probe (issue #322, Phase C-2). THROWAWAY: this class lives
 * only on the work/ga4-debugview-probe branch and must never merge. It is
 * deliberately not part of any selector in scripts/run-emulator-ui-tests.sh.
 *
 * The probe script (scripts/ga4-debugview-probe.sh) installs the configured
 * debug build (package com.pittech.debug, present in
 * app/google-services.json), sets `debug.firebase.analytics.app` plus the
 * FA verbose log tags BEFORE first launch, and captures logcat while this
 * test walks the real UI: Home -> Recipes -> Settings -> start a cook
 * through the real Start-a-cook flow (fires the production `cook_started`
 * hook in CooksViewModel.startCook) -> Live board -> Timeline. Every stop
 * fires the production screen-view hook, so GA4 DebugView for property
 * 564773286 should show first_open / session_start / screen_view x5 /
 * cook_started from the com.pittech.debug stream inside the script's
 * recorded UTC window.
 */
@OptIn(ExperimentalTestApi::class)
class Ga4DebugViewProbeTest {
    @get:Rule val compose = createEmptyComposeRule()

    // Same polling-wait shape as StoreScreenshotCaptureTest: not tied to a
    // compose-rule flavour, and every interaction waits for the node to
    // EXIST first (the issue #316 existence-race lesson).
    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        if (!condition()) throw AssertionError("Condition still not satisfied after $timeoutMs ms")
    }

    private fun awaitNode(tag: String, timeoutMs: Long = 30_000) {
        awaitTrue(timeoutMs) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun settle() {
        compose.waitForIdle()
        // Give the LaunchedEffect screen-view hook and the FA SDK a beat
        // on each surface so the events land as distinct log lines.
        Thread.sleep(2_000)
    }

    @Test fun ga4DebugViewProbe() {
        // Prepare first, launch last (StoreScreenshotCaptureTest
        // discipline): the first-run welcome gates the launch surface off
        // a persisted flag read at composition.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        WelcomePreferences.markSeen(targetContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE))

        ActivityScenario.launch(MainActivity::class.java).use {
            // 1 — Home / Meals: screen_view "Home" (+ automatic
            // first_open / session_start on this fresh install).
            awaitNode("nav-cooks")
            awaitNode("start-cook")
            settle()

            // 2 — Recipes: screen_view "Recipes".
            compose.onNodeWithTag("nav-recipes").performClick()
            settle()

            // 3 — More -> Settings: screen_view "Settings".
            compose.onNodeWithTag("nav-more").performClick()
            awaitNode("more-settings")
            compose.onNodeWithTag("more-settings").performClick()
            awaitNode("theme-mode-dark")
            settle()

            // Back to Meals for the start-a-cook flow.
            compose.onNodeWithTag("nav-cooks").performClick()
            awaitNode("start-cook")
            compose.waitForIdle()

            // 4 — Start a cook through the real UI: the ViewModel's
            // startCook fires cook_started, and the cook detail opens on
            // the Live tab: screen_view "Live board".
            compose.onNodeWithTag("start-cook").performClick()
            awaitNode("cook-add-dish")
            compose.onNodeWithTag("cook-add-dish").performClick()
            awaitNode("dish-name")
            compose.onNodeWithTag("dish-name").performTextInput("Brisket")
            compose.onNodeWithTag("dish-save").performClick()
            awaitNode("cook-save")
            compose.onNodeWithTag("cook-save").performClick()
            awaitNode("cook-tab-live", 60_000)
            settle()

            // 5 — Timeline tab of the live cook: screen_view "Timeline".
            compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
            awaitNode("timeline-list")
            settle()

            // Dwell with the app alive and foregrounded so the FA SDK
            // flushes its queue while debug mode streams to DebugView.
            // The probe script adds a further dwell after the run.
            Thread.sleep(45_000)
        }
    }
}
