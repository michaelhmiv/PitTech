package com.pittech

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Flowing Slice 4 gate evidence (spec §6): Spread 03's day-board — the
 * start-time headline and lanes on the saved plan and in its editor
 * (3.1/3.2), warn-and-stretch that resolves and saves instead of
 * blocking (3.3), a lane seeded from a recipe with its recorded
 * durations (3.4), and the plan riding on Live with its derived alerts
 * armed (3.5 + E5).
 */
class FlowingPlanBoardScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private val anchor = Instant.parse("2026-10-12T10:05:00Z").toEpochMilli() // Mon Oct 12, 2026, 6:05 AM EDT
    private fun at(minute: Long) = anchor + minute * 60_000L

    /** The book's canonical conflict: brisket at 250°, ribs at 275°, one pit. */
    private fun conflictingPlan(): ServePlan {
        val draft = NewCookDraft(
            title = "Saturday", smokerName = "Austin XL", setpointText = "250",
            dishes = listOf(
                DishDraft("Brisket", "Beef", "Packer brisket"),
                DishDraft("Ribs", "Pork", "Spare ribs"),
            ),
        )
        return ServePlan(
            book = CookPlaybook("Saturday", draft, emptyList()),
            serveAt = System.currentTimeMillis() + 2 * 86_400_000L,
            zoneId = java.time.ZoneId.systemDefault().id,
            schedules = listOf(
                DishSchedule(dishIndex = 0, cookMinMinutes = 600, cookMaxMinutes = 780, restMinutes = 60, pitF = 250.0),
                DishSchedule(dishIndex = 1, cookMinMinutes = 240, cookMaxMinutes = 280, restMinutes = 15, pitF = 275.0),
            ),
        )
    }

    private fun expandPlanningSection() {
        compose.onNodeWithTag("home-planning-toggle").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun expandPlanning() {
        expandPlanningSection()
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("upcoming-not-started").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun stretchStripCount() = compose.onAllNodesWithTag("plan-board-stretch-1").fetchSemanticsNodes().size

    @Test fun conflictingPlanWarnsStretchesAndStillSaves() {
        val app = app()
        val planId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.servePlanRepository.save(conflictingPlan(), null)
        }
        expandPlanning()
        // 3.1/3.2 on the saved plan: the board, its headline, both lanes.
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("plan-board-headline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan-board-lane-0").assertExists()
        compose.onNodeWithTag("plan-board-lane-1").assertExists()
        // 3.3: the conflict is a warning with the resolution applied —
        // the ribs lane already carries its stretch at the default pit.
        compose.onNodeWithTag("plan-board-stretch-1").performScrollTo().assertExists()
        compose.onNodeWithText("stretched +35 min", substring = true).assertExists()
        // Into the editor (a dialog — the home board stays in the tree,
        // so the editor's stretch strip is the second one): the cook can
        // pick the other pit, and the stretch follows the choice.
        compose.onNodeWithText("Adjust schedule").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("serve-plan-not-started").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(timeoutMillis = 20_000) { stretchStripCount() == 2 }
        compose.onNodeWithTag("plan-board-pit-275").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 20_000) { stretchStripCount() == 1 }
        compose.onNodeWithTag("plan-board-pit-250").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { stretchStripCount() == 2 }
        // The save is never held hostage by the conflict (it was a hard
        // block before this slice): the editor closes on Save, and the
        // saved plan's board no longer needs a stretch strip at all.
        compose.onNodeWithText("Save plan").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("serve-plan-not-started").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(timeoutMillis = 20_000) { stretchStripCount() == 0 }
        // And the resolution is durable: the saved ribs schedule carries
        // the stretched durations at the chosen pit.
        val saved = runBlocking(Dispatchers.IO) {
            ServeTimePlanner.decode(app.database.companionDao().get(planId)!!.payload)
        }
        val ribs = saved.schedules.first { it.dishIndex == 1 }
        assertEquals(315L, ribs.cookMaxMinutes)
        assertEquals(250.0, ribs.pitF!!, 0.001)
        assertTrue(ribs.evidenceNote.contains("Stretched +35 min"))
        assertTrue(ServeTimePlanner.conflicts(saved).isEmpty())
    }

    @Test fun planLanesRideOnLiveAndStartingArmsTheDerivedAlerts() {
        val app = app()
        val cookId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val draft = NewCookDraft(
                title = "Planned brisket", smokerName = "Austin XL", setpointText = "250",
                dishes = listOf(DishDraft("Brisket", "Beef", "Packer brisket")),
            )
            val book = CookPlaybook("Planned brisket", draft, listOf(
                PlaybookStep(dishIndex = 0, action = "wrap", title = "Wrap the brisket", trigger = "elapsed", minutes = 360),
            ))
            val plan = ServePlan(book, System.currentTimeMillis() + 2 * 86_400_000L, java.time.ZoneId.systemDefault().id,
                schedules = listOf(DishSchedule(dishIndex = 0, cookMinMinutes = 600, cookMaxMinutes = 780, restMinutes = 60, pitF = 250.0)))
            val planId = app.servePlanRepository.save(plan, null)
            app.cookRepository.startCook(NewCookDraft(title = "Planned brisket", smokerName = "Austin XL", dishes = draft.dishes, scheduledPlanId = planId))
        }
        // 3.5: food-on, wrap, and off rules armed by the start.
        val ruleIds = runBlocking(Dispatchers.IO) { app.database.reminderDao().rules().filter { it.cookId == cookId }.map { it.id } }
        assertEquals(
            setOf("plan:$cookId:0:food_on", "plan:$cookId:0:wrap", "plan:$cookId:0:off"),
            ruleIds.toSet(),
        )
        // E5: open the cook — Live carries the plan's lanes as a strip.
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Planned brisket").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Planned brisket").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("live-plan-strip", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-plan-lane-0", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Today's plan", useUnmergedTree = true).assertExists()
    }

    /** A finished Boston-butt cook in the story corpus shape (food on 0, done 655). */
    private fun seedFinishedCook(app: PitTechApplication): Pair<String, String> = runBlocking(Dispatchers.IO) {
        val cook = app.cookRepository.startCook(NewCookDraft(
            title = "Story night", smokerName = "Austin XL", setpointText = "250", woodOrPelletBlend = "Hickory",
            dishes = listOf(DishDraft("Boston Butt", "Pork", cut = "Boston butt", weightText = "9.4", weightUnit = "lb")),
        ))
        val dish = app.database.cookDao().getDishesForCook(cook).first().id
        suspend fun action(action: String, title: String, minute: Long) {
            app.cookRepository.notes.save(CookNoteDraft(cookId = cook, dishId = dish, category = "action", action = action, title = title, occurredAt = at(minute)))
        }
        action("food_on", "Food on", 0); action("wrap", "Wrap", 195)
        action("remove", "Off the pit", 578)
        action("rest_start", "Rest", 580); action("dish_done", "Done", 655)
        var minute = 0L
        while (minute <= 580) {
            val value = when {
                minute <= 120 -> 40.0 + minute
                minute <= 210 -> 160.0
                minute <= 296 -> 160.0 + (minute - 210) * 0.5
                else -> 203.0
            }
            app.cookRepository.notes.save(CookNoteDraft(cookId = cook, dishId = dish, occurredAt = at(minute),
                temperatures = listOf(NoteTemperature(probeName = "Butt", measurementType = "food_probe", valueText = value.toInt().toString(), measuredAt = at(minute), dishId = dish))))
            minute += 10
        }
        val dao = app.database.cookDao()
        dao.finishCook(cook, at(660))
        val row = dao.getCook(cook)!!
        dao.updateCook(row.copy(startedAtUtcMillis = anchor, startedTimeZoneId = "America/New_York"))
        cook to dish
    }

    @Test fun recipeSeedsALaneWithItsRecordedDurations() {
        val app = app()
        val recipeId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val (cook, dish) = seedFinishedCook(app)
            val snapshot = RecipeSnapshot("Boston Butt", DishDraft("Boston Butt", "Pork", cut = "Boston butt", weightText = "9.4", weightUnit = "lb"))
            app.cookRepository.recipes.save(snapshot, sourceCookId = cook, sourceDishId = dish).id
        }
        expandPlanningSection()
        compose.onNodeWithTag("plan-serving-time").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("serve-plan-not-started").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan-add-from-recipe").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("plan-seed-picker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan-seed-option-$recipeId").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithTag("plan-seed-picker").fetchSemanticsNodes().isEmpty() }
        // The seeded lane is on the board and its dish card carries the
        // provenance basis — the source cook's date, with the year.
        compose.onNodeWithText("Seeded from your October 12, 2026 cook", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Boston Butt").assertExists()
    }
}
