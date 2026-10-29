package com.pittech

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookResultEntity
import com.pittech.data.PhotoEntity
import com.pittech.domain.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Flowing Slice 3 gate evidence (spec §6): Spread 06's story on the
 * recipe screen — provenance up top with the year, the four stat cells,
 * the curve and role-ordered photos, keep/change chips, Cook This Again,
 * and Full chart opening Spread 02's engine on the historical cook —
 * plus the honest-absence render for a recipe with no source cook, and
 * E4: a finished cook opens on its story with the one-time
 * "Protect your cook log?" prompt. The seeded cook mirrors the Slice 1
 * provenance corpus shape (a stall, a wrap at 195 min, a 75-minute rest)
 * so every stat cell is earned by the data.
 */
class FlowingStoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val anchor = Instant.parse("2026-10-12T10:05:00Z").toEpochMilli() // Mon Oct 12, 2026, 6:05 AM EDT
    private fun at(minute: Long) = anchor + minute * 60_000L

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A finished Boston-butt cook in the corpus shape; returns cook id + dish id. */
    private fun seedFinishedCook(app: PitTechApplication, title: String): Pair<String, String> = runBlocking(Dispatchers.IO) {
        val cook = app.cookRepository.startCook(NewCookDraft(
            title = title, smokerName = "Austin XL", setpointText = "250", woodOrPelletBlend = "Hickory",
            dishes = listOf(DishDraft("Boston Butt", "Pork", cut = "Boston butt", weightText = "9.4", weightUnit = "lb")),
        ))
        val dish = app.database.cookDao().getDishesForCook(cook).first().id
        suspend fun action(action: String, title: String, minute: Long) {
            app.cookRepository.notes.save(CookNoteDraft(cookId = cook, dishId = dish, category = "action", action = action, title = title, occurredAt = at(minute)))
        }
        action("food_on", "Food on", 0); action("wrap", "Wrap", 195); action("remove", "Off the pit", 580)
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
        dao.upsertResult(CookResultEntity("$cook:keep", cook, dish, "keep_doing", textValue = "Paper wrap, no spritz", recordedAtUtcMillis = at(655)))
        dao.upsertResult(CookResultEntity("$cook:change", cook, dish, "change_next_time", textValue = "Start an hour earlier", recordedAtUtcMillis = at(655)))
        // Photos ride on their events: on the pit at food-on, sliced at the rest.
        val photoPath = "photos/story-photo.jpg"
        val photoFile = File(targetContext().filesDir, photoPath).apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        photoFile.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) }
        bitmap.recycle()
        val events = dao.getTimelineEventsForCook(cook)
        val foodOn = events.first { it.title == "Food on" }; val wrap = events.first { it.title == "Wrap" }
        dao.insertPhotos(listOf(
            PhotoEntity(id = "$cook:photo-on", cookId = cook, dishId = dish, eventId = foodOn.id, originalFileName = "on.jpg", relativePath = photoPath, mimeType = "image/jpeg", caption = "On the pit", capturedAtUtcMillis = at(5), addedAtUtcMillis = at(5)),
            PhotoEntity(id = "$cook:photo-wrap", cookId = cook, dishId = dish, eventId = wrap.id, originalFileName = "wrap.jpg", relativePath = photoPath, mimeType = "image/jpeg", caption = "At the wrap", capturedAtUtcMillis = at(200), addedAtUtcMillis = at(200)),
        ))
        dao.finishCook(cook, at(660))
        val row = dao.getCook(cook)!!
        dao.updateCook(row.copy(startedAtUtcMillis = anchor, startedTimeZoneId = "America/New_York"))
        cook to dish
    }

    @Test fun recipeOpensWithItsStory() {
        val app = app()
        val recipeId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val (cook, dish) = seedFinishedCook(app, "Story night")
            val snapshot = RecipeSnapshot("Boston Butt", DishDraft("Boston Butt", "Pork", cut = "Boston butt", weightText = "9.4", weightUnit = "lb"))
            app.cookRepository.recipes.save(snapshot, sourceCookId = cook, sourceDishId = dish).id
        }
        compose.onNodeWithTag("nav-recipes").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("recipe-open-$recipeId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recipe-open-$recipeId").performClick()
        // 6.1: provenance up top — date with year, pit, setpoint, wood.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("recipe-provenance", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recipe-provenance", useUnmergedTree = true)
            .assertTextContains("From your October 12, 2026 cook", substring = true)
            .assertTextContains("Austin XL", substring = true)
            .assertTextContains("250°", substring = true)
            .assertTextContains("Hickory", substring = true)
        // 6.2: the cook in stat cells (total is exact in this corpus shape).
        compose.onNodeWithTag("recipe-story-stats", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("10H 55M", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Wrapped at", useUnmergedTree = true).assertExists()
        // 6.3: the curve and the photos ride along.
        compose.onNodeWithTag("recipe-story-curve", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("recipe-story-photos", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("On the pit", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("At the wrap", useUnmergedTree = true).assertExists()
        // 6.5: keep / change ride forward as chips, in the cook's own words.
        compose.onNodeWithText("Paper wrap, no spritz", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Start an hour earlier", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("recipe-cook-again", useUnmergedTree = true).assertExists()
        // 6.3: Full chart opens Spread 02's engine on the historical cook.
        compose.onNodeWithTag("recipe-full-chart", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("live-chart-readout", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-chart-close", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("live-chart-readout", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("recipe-details-close").performClick()
    }

    @Test fun recipeWithoutASourceCookRendersHonestly() {
        val app = app()
        val recipeId = runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val snapshot = RecipeSnapshot("Handwritten Beans", DishDraft("Beans", "Sides"))
            app.cookRepository.recipes.save(snapshot).id
        }
        compose.onNodeWithTag("nav-recipes").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("recipe-open-$recipeId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recipe-open-$recipeId").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("recipe-share").fetchSemanticsNodes().isNotEmpty() }
        // No story block, no provenance line — and the screen is intact.
        compose.onAllNodesWithTag("recipe-story", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("recipe-provenance", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("recipe-share").assertIsDisplayed()
        compose.onNodeWithTag("recipe-details-close").performClick()
    }

    @Test fun finishedCookOpensOnItsStoryWithTheProtectPrompt() {
        val app = app()
        targetContext().getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE).edit().remove("protect-log-prompt-shown").commit()
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            seedFinishedCook(app, "First finished butt")
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("First finished butt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("First finished butt").performClick()
        // E4: the story (Timeline) is the opening tab, not Live.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cook-tab-timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithTag("cook-tab-live", useUnmergedTree = true).assertIsNotSelected()
        // E4: the first finished cook prompts to protect the log.
        compose.onNodeWithTag("protect-log-prompt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Protect your cook log?", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("protect-log-dismiss", useUnmergedTree = true).performClick()
        compose.onAllNodesWithTag("protect-log-prompt", useUnmergedTree = true).assertCountEquals(0)
        // Answered once: reopening the same cook does not prompt again.
        Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("First finished butt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("First finished butt").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("cook-tab-timeline", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cook-tab-timeline", useUnmergedTree = true).assertIsSelected()
        compose.onAllNodesWithTag("protect-log-prompt", useUnmergedTree = true).assertCountEquals(0)
    }
}
