package com.pittech

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
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
 */
@RunWith(AndroidJUnit4::class)
class WelcomeFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun preferences() =
        targetContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE)

    /** Reset to a true first run: no welcome flag, no crash report, no cooks. */
    private fun resetFirstRun() {
        CrashDiagnostics.clearPendingReport(targetContext)
        preferences().edit().remove(WelcomePreferences.PREFERENCE_KEY).commit()
        val application = targetContext.applicationContext as PitTechApplication
        runBlocking(Dispatchers.IO) { application.database.clearAllTables() }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun welcomeShowsOnceAndStartCookingMarksItSeen() {
        resetFirstRun()
        composeRule.onNodeWithTag("welcome-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome-mascot").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome to PitTech").assertIsDisplayed()
        composeRule.onNodeWithText("Live cook tracking").assertIsDisplayed()
        composeRule.onNodeWithText("Recipes, playbooks & reminders").assertIsDisplayed()
        composeRule.onNodeWithText("Share & Discover").assertIsDisplayed()

        composeRule.onNodeWithTag("welcome-start-cooking").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
        assertTrue(WelcomePreferences.hasSeen(preferences()))

        // Relaunch: the welcome never auto-shows again.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
        composeRule.onNodeWithText("Your cook log is ready").assertIsDisplayed()
    }

    @Test
    fun connectThermometerMarksSeenAndLandsOnDevices() {
        resetFirstRun()
        composeRule.onNodeWithTag("welcome-connect-thermometer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
        assertTrue(WelcomePreferences.hasSeen(preferences()))
        composeRule.onNodeWithTag("telemetry-connections").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
    }

    @Test
    fun restoreFromBackupMarksSeen() {
        resetFirstRun()
        composeRule.onNodeWithTag("welcome-restore-backup").performClick()
        // The system document picker opens over the app; the flag is already set.
        Thread.sleep(1_000)
        assertTrue(WelcomePreferences.hasSeen(preferences()))
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
    }

    @Test
    fun pendingCrashReportTakesPrecedenceAndWelcomeWaitsForNextLaunch() {
        resetFirstRun()
        CrashDiagnostics.recordUncaughtException(
            targetContext,
            Thread.currentThread(),
            IllegalStateException("Welcome precedence fixture"),
        )
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        // Crash recovery shows; the welcome does not.
        composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()
        composeRule.onNodeWithTag("crash-report-continue").assertIsDisplayed()
        assertFalse(WelcomePreferences.hasSeen(preferences()))

        // Continuing recovery lands in the app — still no welcome this session.
        composeRule.onNodeWithTag("crash-report-continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome-screen").assertDoesNotExist()

        // Next clean launch: the still-unseen welcome shows.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome-start-cooking").performClick()
        composeRule.waitForIdle()
        assertTrue(WelcomePreferences.hasSeen(preferences()))
    }
}
