package com.pittech

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import com.pittech.ui.WelcomePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #187 first-run welcome: shows once on a fresh install, every exit marks it
 * seen permanently, and a pending crash report takes precedence.
 *
 * Each test prepares the persisted state first and then launches its own
 * activity scenario: mutating SharedPreferences or the database under a
 * running (or recreated) activity races the composition that already read
 * them, so the launch always comes last.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WelcomeFlowTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun preferences() =
        targetContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE)

    /** Reset to a true first run: no welcome flag, no crash report, no cooks. */
    private fun resetFirstRun() {
        CrashDiagnostics.clearPendingReport(targetContext)
        // PL-20 (#172): the open-cook recovery record is diagnostics
        // state too — a stale record from an earlier test in this
        // process must not leak into the next launch.
        CrashDiagnostics.recordOpenCook(targetContext, null)
        preferences().edit().remove(WelcomePreferences.PREFERENCE_KEY).commit()
        val application = targetContext.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) { application.database.clearAllTables() }
    }

    private fun launchApp(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java)

    private fun awaitWelcome() {
        composeRule.waitUntilExactlyOneExists(hasTestTag("welcome-screen"), 10_000)
    }

    private fun awaitNoWelcome() {
        composeRule.waitUntilDoesNotExist(hasTestTag("welcome-screen"), 10_000)
    }

    @Test
    fun welcomeShowsOnceAndStartCookingMarksItSeen() {
        resetFirstRun()
        launchApp().use {
            awaitWelcome()
            composeRule.onNodeWithTag("welcome-mascot").assertIsDisplayed()
            composeRule.onNodeWithText("Welcome to PitTech").assertIsDisplayed()
            // At font scale 1.5 the later rows sit below the fold of the
            // scrollable welcome; scroll each into view before asserting.
            composeRule.onNodeWithText("Live cook tracking").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Recipes, playbooks & reminders").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Share & Discover").performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithTag("welcome-start-cooking").performScrollTo().performClick()
            awaitNoWelcome()
            composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
            assertTrue(WelcomePreferences.hasSeen(preferences()))
        }
        // Relaunch: the welcome never auto-shows again.
        launchApp().use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
            composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
        }
    }

    @Test
    fun connectThermometerMarksSeenAndLandsOnDevices() {
        resetFirstRun()
        launchApp().use {
            awaitWelcome()
            composeRule.onNodeWithTag("welcome-connect-thermometer").performScrollTo().performClick()
            awaitNoWelcome()
            assertTrue(WelcomePreferences.hasSeen(preferences()))
            composeRule.onNodeWithTag("telemetry-connections").assertIsDisplayed()
        }
        launchApp().use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
        }
    }

    @Test
    fun restoreFromBackupMarksSeen() {
        resetFirstRun()
        launchApp().use {
            awaitWelcome()
            composeRule.onNodeWithTag("welcome-restore-backup").performScrollTo().performClick()
            // The system document picker opens over the app; the flag is already set.
            Thread.sleep(1_000)
            assertTrue(WelcomePreferences.hasSeen(preferences()))
            // Espresso.pressBack() cannot run here (no app activity is
            // RESUMED while the picker owns the foreground); send the key
            // straight to the foreground window instead.
            InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
        }
    }

    @Test
    fun pendingCrashReportTakesPrecedenceAndWelcomeWaitsForNextLaunch() {
        resetFirstRun()
        CrashDiagnostics.recordUncaughtException(
            targetContext,
            Thread.currentThread(),
            IllegalStateException("Welcome precedence fixture"),
        )
        launchApp().use {
            composeRule.waitForIdle()
            // Crash recovery shows; the welcome does not.
            composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
            composeRule.onNodeWithTag("crash-report-continue").assertIsDisplayed()
            // PL-20 (#172): the recovery screen offers the direct attach
            // action instead of only the copy-and-ferry clipboard step.
            composeRule.onNodeWithTag("crash-report-attach").assertIsDisplayed()
            assertFalse(WelcomePreferences.hasSeen(preferences()))

            // Continuing recovery lands in the app — still no welcome this session.
            composeRule.onNodeWithTag("crash-report-continue").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
        }
        // Next clean launch: the still-unseen welcome shows.
        launchApp().use {
            awaitWelcome()
            composeRule.onNodeWithTag("welcome-start-cooking").performScrollTo().performClick()
            awaitNoWelcome()
            assertTrue(WelcomePreferences.hasSeen(preferences()))
        }
    }

    /**
     * PL-20 (#172): crash recovery's Continue returns to the cook that
     * was open when the crash hit instead of always dumping to Meals
     * home, and "Attach to a problem report" lands on Settings with the
     * feedback form open and the saved report still attachable.
     */
    @Test
    fun crashRecoveryContinueReturnsToTheOpenCook() {
        resetFirstRun()
        WelcomePreferences.markSeen(preferences())
        val application = targetContext.applicationContext as PitTechApplication
        val cookId = runBlocking(Dispatchers.IO) {
            application.cookRepository.startCook(
                NewCookDraft("Crash resume brisket", dishes = listOf(DishDraft("Brisket", "Beef"))),
            )
        }
        CrashDiagnostics.recordOpenCook(targetContext, cookId)
        CrashDiagnostics.recordUncaughtException(
            targetContext,
            Thread.currentThread(),
            IllegalStateException("Crash resume fixture"),
        )
        launchApp().use {
            composeRule.waitUntilExactlyOneExists(hasTestTag("crash-report-continue"), 10_000)
            composeRule.onNodeWithTag("crash-report-continue").performClick()
            // The open cook's detail returns — not Meals home.
            composeRule.waitUntilExactlyOneExists(hasTestTag("cook-tab-live"), 15_000)
            composeRule.onNodeWithText("Crash resume brisket").assertIsDisplayed()
        }
    }

    @Test
    fun crashRecoveryAttachOpensFeedbackWithTheReportAttachable() {
        resetFirstRun()
        WelcomePreferences.markSeen(preferences())
        val report = CrashDiagnostics.recordUncaughtException(
            targetContext,
            Thread.currentThread(),
            IllegalStateException("Crash attach fixture"),
        )
        launchApp().use {
            composeRule.waitUntilExactlyOneExists(hasTestTag("crash-report-attach"), 10_000)
            composeRule.onNodeWithTag("crash-report-attach").performClick()
            // Settings opens with the feedback form up (PL-27 shape:
            // kind chosen in the form), and the saved report — which
            // acknowledging recovery does not delete — is attachable.
            composeRule.waitUntilExactlyOneExists(hasTestTag("feedback-kind-bug"), 15_000)
            composeRule.onNodeWithText("Include the saved crash report", substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(report.referenceCode, substring = true).performScrollTo().assertIsDisplayed()
        }
    }
}
