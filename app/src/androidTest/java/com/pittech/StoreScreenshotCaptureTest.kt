package com.pittech

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookResultEntity
import com.pittech.domain.*
import com.pittech.ui.WelcomePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Store / landing-page screenshot capture. This class is deliberately NOT
 * part of any selector in scripts/run-emulator-ui-tests.sh: the guarded
 * suites there run explicit class lists with fixed expected counts, and
 * this capture must never change them. It runs only from the manual
 * store-screenshots workflow (.github/workflows/store-screenshots.yml),
 * which boots a clean emulator, seeds the same hand-laid brisket board
 * FlowingLiveScreenTest proves (a live cook six hours in with a qualifying
 * pace, plus its finished predecessor for history), walks the real UI,
 * and writes full-device PNGs into files/pittech-ui-test/store-*.png for
 * the workflow to pull.
 *
 * State is prepared BEFORE the activity launches, following
 * WelcomeFlowTest's discipline: the first-run welcome (#187) gates the
 * launch surface off a persisted flag read at composition, and mutating
 * preferences or the database under a running activity races it. The
 * capture therefore marks the welcome seen, seeds, and only then starts
 * MainActivity — on a stone-cold emulator install nothing else gets the
 * Meals surface composed (first dispatch run, 2026-11-10: the seeded
 * cooks never appeared because the welcome held the surface).
 */
@OptIn(ExperimentalTestApi::class)
class StoreScreenshotCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication

    // Same capture discipline as TelemetryConnectionsScreenTest: a single
    // takeScreenshot() can return null under emulator load, so retry and
    // fail here, where the cause is visible, if it stays unavailable.
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        var attempts = 0
        while (bitmap == null && attempts < 10) {
            bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap == null) { attempts++; Thread.sleep(300) }
        }
        val shot = bitmap ?: throw AssertionError("Store screenshot '$name' could not be captured: takeScreenshot() returned null after 10 attempts")
        val file = File(instrumentation.targetContext.filesDir, "pittech-ui-test/store-$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
        // Let charts/animations settle fully before the next navigation so
        // the following capture never catches a transition frame.
        Thread.sleep(1_000)
    }

    // Polling wait in the same shape as the other suites' waitUntil
    // calls, but not tied to a compose-rule flavour: this class runs on
    // createEmptyComposeRule (launch-last discipline), where the
    // node-fetch API is the common denominator.
    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        if (!condition()) throw AssertionError("Condition still not satisfied after $timeoutMs ms")
    }

    private fun dismissNotice() {
        val dismiss = compose.onAllNodesWithContentDescription("Dismiss")
        if (dismiss.fetchSemanticsNodes().isNotEmpty()) dismiss[0].performClick()
    }

    private fun seedShowcase(app: PitTechApplication): Pair<String, String> = runBlocking(Dispatchers.IO) {
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
        // The finished brisket: history entry + the live cook's ghost.
        // (DishDraft's second positional arg is foodType — cut must be
        // named, or the archive carries no cut and no ghost can match.)
        val ghost = app.cookRepository.startCook(NewCookDraft("Last brisket", dishes = listOf(DishDraft("Brisket", "Beef", cut = "Brisket"))))
        val ghostDish = app.database.cookDao().getDishesForCook(ghost).first().id
        val ghostOn = now - 30L * 3_600_000
        action(ghost, ghostDish, "food_on", "Food on", ghostOn)
        temps(ghost, ghostDish, listOf(0L to 40.0, 30L to 58.0, 60L to 76.0, 90L to 92.0, 120L to 106.0, 150L to 118.0, 180L to 129.0, 210L to 139.0, 240L to 148.0, 270L to 154.0, 300L to 158.0, 330L to 162.0, 360L to 166.0, 390L to 172.0, 398L to 175.0, 420L to 180.0, 450L to 186.0, 480L to 191.0, 510L to 196.0, 540L to 200.0, 570L to 202.0), ghostOn)
        action(ghost, ghostDish, "wrap", "Wrapped in butcher paper", ghostOn + 400L * 60_000)
        app.cookRepository.notes.save(CookNoteDraft(cookId = ghost, dishId = ghostDish, title = "Bark check", description = "Deep mahogany bark before the wrap.", occurredAt = ghostOn + 395L * 60_000))
        action(ghost, ghostDish, "remove", "Off the pit", ghostOn + 580L * 60_000)
        app.database.cookDao().upsertResult(CookResultEntity("$ghost:$ghostDish:final_temperature", ghost, ghostDish, "final_temperature", numericValue = 202.0, recordedAtUtcMillis = ghostOn + 580L * 60_000))
        app.database.cookDao().upsertResult(CookResultEntity("$ghost:overall_rating", ghost, null, "overall_rating", numericValue = 5.0, recordedAtUtcMillis = ghostOn + 580L * 60_000))
        app.database.cookDao().finishCook(ghost, now - 20L * 3_600_000)
        // The live brisket: six hours in, climbing at 0.2°F/min on
        // 5-minute readings in the last stretch, so the pace engine's
        // verdict qualifies at any realistic seed-to-open delay (the
        // FlowingLiveScreenTest series, verbatim).
        val live = app.cookRepository.startCook(NewCookDraft("Flowing brisket", dishes = listOf(DishDraft("Brisket", "Beef", cut = "Brisket"))))
        val liveDish = app.database.cookDao().getDishesForCook(live).first().id
        val liveOn = now - 6L * 3_600_000
        action(live, liveDish, "food_on", "Food on", liveOn)
        app.cookRepository.addTarget(live, liveDish, "personal_finish", 203.0, "°F", null)
        temps(live, liveDish, listOf(0L to 40.0, 15L to 52.0, 30L to 63.0, 45L to 74.0, 60L to 84.0, 75L to 93.0, 90L to 102.0, 105L to 110.0, 120L to 118.0, 135L to 125.0, 150L to 132.0, 165L to 138.0, 180L to 144.0, 195L to 149.0, 210L to 153.0, 225L to 156.0, 240L to 158.0, 255L to 160.0, 270L to 161.0, 285L to 162.0, 300L to 163.0, 305L to 164.0, 310L to 165.0, 315L to 166.0, 320L to 167.0, 325L to 168.0, 330L to 169.0, 335L to 170.0, 340L to 171.0, 345L to 172.0, 350L to 173.0, 355L to 174.0, 360L to 175.0), liveOn)
        action(live, liveDish, "wrap", "Wrapped in butcher paper", liveOn + 300L * 60_000)
        app.cookRepository.notes.save(CookNoteDraft(cookId = live, dishId = liveDish, title = "Bark check", description = "Deep mahogany bark; spritzed the edges.", occurredAt = liveOn + 295L * 60_000))
        app.cookRepository.notes.save(CookNoteDraft(cookId = live, dishId = liveDish, occurredAt = liveOn + 330L * 60_000,
            temperatures = listOf(NoteTemperature(probeName = "Pit setpoint", measurementType = "setpoint", valueText = "250", measuredAt = liveOn + 330L * 60_000, dishId = liveDish))))
        ghost to live
    }

    @Test fun captureStoreScreenshots() {
        val app = app()
        // Prepare first, launch last (see the class doc): mark the
        // first-run welcome seen and seed the showcase cooks before
        // MainActivity composes anything.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        WelcomePreferences.markSeen(targetContext.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE))
        val (ghost, live) = seedShowcase(app)

        ActivityScenario.launch(MainActivity::class.java).use {
            // 1 — Home / Meals: the cook log with Start a Cook up front,
            // the live cook and the finished one both listed.
            awaitTrue(15_000) { compose.onAllNodesWithText("Flowing brisket").fetchSemanticsNodes().isNotEmpty() }
            awaitTrue(15_000) { compose.onAllNodesWithText("Last brisket").fetchSemanticsNodes().isNotEmpty() }
            dismissNotice()
            compose.waitForIdle()
            capture("home")

            // 2 — Live Board: verdict headline + ETA range composed
            // means the board has real data on screen, not a loading
            // state.
            compose.onNodeWithText("Flowing brisket").performClick()
            awaitTrue(15_000) { compose.onAllNodesWithTag("live-verdict-headline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            awaitTrue(15_000) { compose.onAllNodesWithTag("live-eta-range", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            capture("live-board")

            // 3 — Cook Timeline: the same cook's story — milestones,
            // notes, and temperature entries.
            compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).performClick()
            // Proof the tab composed: its list exists only on this tab.
            // (Waiting on one entry's text is not viable — the list is
            // virtualized, so an entry an hour into the story is not
            // composed until scrolled to; the second dispatch run timed
            // out on exactly that.)
            awaitTrue(15_000) { compose.onAllNodesWithTag("timeline-list", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            capture("timeline")

            // 4 — Cook History: back to Meals and open the finished
            // cook; a completed cook opens on its summary/timeline
            // record.
            Espresso.pressBack()
            awaitTrue(15_000) { compose.onAllNodesWithText("Last brisket").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cook-card-$ghost").performScrollTo().performClick()
            awaitTrue(15_000) { compose.onAllNodesWithTag("cook-tab-timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            awaitTrue(15_000) { compose.onAllNodesWithTag("timeline-list", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            awaitTrue(15_000) { compose.onAllNodesWithText("Last brisket").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            capture("history")
        }
    }
}
