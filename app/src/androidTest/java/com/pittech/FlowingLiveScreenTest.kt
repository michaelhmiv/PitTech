package com.pittech

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookResultEntity
import com.pittech.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Flowing Slice 2 gate evidence (spec §6): the Live board's verdict and
 * living ETA with basis strings asserted in the UI, the full-screen
 * chart flow with its ghost delta, and the overnight test alert
 * DEMONSTRABLY posting a notification. The seeded series are hand-laid
 * (mirroring the iOS --ui-test-flowing fixture) so the pace engine's
 * statements are deterministic: the live brisket's last hour climbs
 * at 0.2°F/min on 5-minute readings, so the trailing 30-minute pace
 * window holds a qualifying set at any realistic seed-to-open delay
 * (PACE basis), and the finished brisket reached 175° at +398 min
 * against the live cook's +360.
 */
class FlowingLiveScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication

    private fun seedBoard(app: PitTechApplication): String = runBlocking(Dispatchers.IO) {
        app.database.clearAllTables()
        val now = System.currentTimeMillis()
        suspend fun action(cook: String, dish: String, action: String, title: String, at: Long) {
            app.cookRepository.notes.save(CookNoteDraft(cookId = cook, dishId = dish, category = "action", action = action, title = title, occurredAt = at))
        }
        suspend fun temps(cook: String, dish: String, series: List<Pair<Long, Double>>, on: Long) {
            for ((minutes, temp) in series) {
                app.cookRepository.notes.save(CookNoteDraft(cookId = cook, dishId = dish, occurredAt = on + minutes * 60_000,
                    temperatures = listOf(NoteTemperature(probeName = "Brisket", measurementType = "food_probe", valueText = temp.toInt().toString(), measuredAt = on + minutes * 60_000, dishId = dish))))
            }
        }
        // The finished brisket: ghost + history source. (DishDraft's
        // second positional arg is foodType — cut must be named, or the
        // archive carries no cut and no ghost/history can ever match.)
        val ghost = app.cookRepository.startCook(NewCookDraft("Last brisket", dishes = listOf(DishDraft("Brisket", "Beef", cut = "Brisket"))))
        val ghostDish = app.database.cookDao().getDishesForCook(ghost).first().id
        val ghostOn = now - 30L * 3_600_000
        action(ghost, ghostDish, "food_on", "Food on", ghostOn)
        temps(ghost, ghostDish, listOf(0L to 40.0, 30L to 58.0, 60L to 76.0, 90L to 92.0, 120L to 106.0, 150L to 118.0, 180L to 129.0, 210L to 139.0, 240L to 148.0, 270L to 154.0, 300L to 158.0, 330L to 162.0, 360L to 166.0, 390L to 172.0, 398L to 175.0, 420L to 180.0, 450L to 186.0, 480L to 191.0, 510L to 196.0, 540L to 200.0, 570L to 202.0), ghostOn)
        action(ghost, ghostDish, "remove", "Off the pit", ghostOn + 580L * 60_000)
        app.database.cookDao().upsertResult(CookResultEntity("$ghost:$ghostDish:final_temperature", ghost, ghostDish, "final_temperature", numericValue = 202.0, recordedAtUtcMillis = ghostOn + 580L * 60_000))
        app.database.cookDao().finishCook(ghost, now)
        // The live brisket: six hours in, out of the band, climbing.
        val live = app.cookRepository.startCook(NewCookDraft("Flowing brisket", dishes = listOf(DishDraft("Brisket", "Beef", cut = "Brisket"))))
        val liveDish = app.database.cookDao().getDishesForCook(live).first().id
        val liveOn = now - 6L * 3_600_000
        action(live, liveDish, "food_on", "Food on", liveOn)
        app.cookRepository.addTarget(live, liveDish, "personal_finish", 203.0, "°F", null)
        temps(live, liveDish, listOf(0L to 40.0, 15L to 52.0, 30L to 63.0, 45L to 74.0, 60L to 84.0, 75L to 93.0, 90L to 102.0, 105L to 110.0, 120L to 118.0, 135L to 125.0, 150L to 132.0, 165L to 138.0, 180L to 144.0, 195L to 149.0, 210L to 153.0, 225L to 156.0, 240L to 158.0, 255L to 160.0, 270L to 161.0, 285L to 162.0, 300L to 163.0, 305L to 164.0, 310L to 165.0, 315L to 166.0, 320L to 167.0, 325L to 168.0, 330L to 169.0, 335L to 170.0, 340L to 171.0, 345L to 172.0, 350L to 173.0, 355L to 174.0, 360L to 175.0), liveOn)
        app.cookRepository.notes.save(CookNoteDraft(cookId = live, dishId = liveDish, occurredAt = liveOn + 330L * 60_000,
            temperatures = listOf(NoteTemperature(probeName = "Pit setpoint", measurementType = "setpoint", valueText = "250", measuredAt = liveOn + 330L * 60_000, dishId = liveDish))))
        live
    }

    private fun openLiveCook() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Flowing brisket").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Flowing brisket").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("live-verdict-headline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun verdictEtaBasisStepperAndConditionsLog() {
        val app = app()
        val live = seedBoard(app)
        openLiveCook()
        // 1.1 + 1.2: one answer at the top; the ETA always carries its basis.
        // (Wait for the text itself: the board's flows settle a beat after
        // the headline node first composes.)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("On pace — nothing needs doing.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-verdict-headline", useUnmergedTree = true).assertTextEquals("On pace — nothing needs doing.")
        compose.onNodeWithTag("live-eta-basis", useUnmergedTree = true).assertTextEquals("from your last 30 min of readings")
        compose.onNodeWithTag("live-eta-range", useUnmergedTree = true).assertTextContains("Done", substring = true)
        // 1.5: the stepper writes the app's own setpoint target history.
        compose.onNodeWithTag("pit-target-value", useUnmergedTree = true).performScrollTo().assertTextEquals("250°")
        compose.onNodeWithTag("pit-target-plus", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("pit-target-value", useUnmergedTree = true).assertTextEquals("255°")
        val history = runBlocking(Dispatchers.IO) { app.database.experienceDao().allHistory() }
        assertTrue(history.any { it.cookId == live && it.targetType == "setpoint" && it.value == 255.0 })
        // 1.5: one tap logs the conditions change onto the timeline.
        // (Scroll first, like every other below-fold tap in this suite:
        // the button sits at the far end of the quick-adjust row, and a
        // click on a partially off-screen node goes nowhere.)
        compose.onNodeWithTag("live-conditions-changed", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(20_000) {
            runBlocking(Dispatchers.IO) { app.database.cookDao().getTimelineEventsForCook(live) }.any { it.title == "Conditions changed" }
        }
    }

    @Test fun heroOpensFullscreenChartWithGhostDeltaAndPresets() {
        val app = app()
        seedBoard(app)
        openLiveCook()
        // 1.3 + Spread 02: the hero is a door; the chart carries the
        // pinned readout, the ghost's delta at the cursor, and presets.
        compose.onNodeWithTag("live-chart-hero", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("live-chart-readout", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The ghost rides the insights archive, which loads async — wait
        // for the delta line to compose before reading it.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("live-chart-ghost-delta", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-chart-ghost-delta", useUnmergedTree = true).assertTextContains("min vs last brisket", substring = true)
        compose.onNodeWithTag("live-chart-preset-last2h", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("live-chart-readout", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("live-chart-close", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("live-chart-readout", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun overnightTestAlertActuallyPostsAndMenuHoldsFinish() {
        val app = app()
        val live = seedBoard(app)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        openLiveCook()
        // 1.6: Pause/Finish live in the cook menu, out of the scroll path.
        compose.onNodeWithTag("cook-menu-button", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("live-finish-cook", useUnmergedTree = true).assertIsDisplayed()
        Espresso.pressBack()
        // E3: the test alert ACTUALLY FIRES — the posted notification is
        // the evidence, not an in-app claim.
        compose.onNodeWithTag("overnight-test-alert", useUnmergedTree = true).performScrollTo().performClick()
        val notifications = context.getSystemService(NotificationManager::class.java)
        compose.waitUntil(15_000) {
            notifications.activeNotifications.any { it.tag == CookAlertNotifications.testTag(live) }
        }
        compose.onNodeWithTag("overnight-test-sent", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
