package com.pittech

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CookResultEntity
import com.pittech.data.ProbeEntity
import com.pittech.data.SensorReadingEntity
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InsightsFreeToolsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun seedTwoFinishedCooks(): Pair<String, String> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        return runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val now = System.currentTimeMillis()
            val first = app.cookRepository.startCook(
                NewCookDraft(title = "Saturday brisket", smokerName = "Offset smoker", dishes = listOf(DishDraft(name = "Brisket", foodType = "Beef", cut = "Whole packer", weightText = "12.5")))
            )
            val second = app.cookRepository.startCook(
                NewCookDraft(title = "Sunday ribs", dishes = listOf(DishDraft(name = "Ribs", foodType = "Pork", cut = "Baby back", weightText = "5", weightUnit = "kg")))
            )
            val dao = app.database.cookDao()
            val firstDish = dao.getDishesForCook(first).single().id
            val secondDish = dao.getDishesForCook(second).single().id
            dao.finishCook(first, now)
            dao.finishCook(second, now)
            dao.upsertResult(CookResultEntity("fav-$first", first, null, "favorite", numericValue = 1.0, recordedAtUtcMillis = now))
            dao.upsertResult(CookResultEntity("rate-$first", first, null, "overall_rating", numericValue = 4.5, recordedAtUtcMillis = now))
            dao.upsertResult(CookResultEntity("rate-$second", second, null, "overall_rating", numericValue = 3.0, recordedAtUtcMillis = now))
            dao.upsertResult(CookResultEntity("method-$first", first, null, "method", textValue = "Smoked", recordedAtUtcMillis = now))
            dao.upsertResult(CookResultEntity("method-$second", second, null, "method", textValue = "Grilled", recordedAtUtcMillis = now))
            fun reading(id: String, cook: String, dish: String?, type: String, value: Double, unit: String, source: String = "controller_cloud", quality: String = "valid") =
                SensorReadingEntity(id, cook, dish, "p-$cook", "Probe", type, value, unit, now - 3_600_000, "UTC", source, qualityStatus = quality, recordedAtUtcMillis = now)
            // Readings carry probeId FKs, so the probes they name must exist (production always
            // resolves readings against real probe rows).
            dao.insertProbesIgnoringDuplicates(listOf(
                ProbeEntity("p-$first", first, null, null, "Probe", "food", "controller_cloud", now),
                ProbeEntity("p-$second", second, null, null, "Probe", "food", "controller_cloud", now),
            ))
            dao.insertReadingsIgnoringDuplicates(listOf(
                reading("r1", first, firstDish, "food", 150.0, "°F"), reading("r2", first, firstDish, "food", 160.0, "°F", source = "manual"),
                reading("r3", first, null, "pit", 250.0, "°F"), reading("r4", first, firstDish, "food", 999.0, "°F", quality = "unavailable"),
                reading("r5", second, secondDish, "food", 70.0, "°C"), reading("r6", second, secondDish, "food", 75.0, "°C"),
            ))
            first to second
        }
    }

    private fun openInsights() {
        compose.onNodeWithTag("nav-insights").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Learn from your cooks").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun freeHistorySearchFiltersAndStatisticsWorkSignedOut() {
        seedTwoFinishedCooks()
        openInsights()
        compose.onNodeWithText("Saturday brisket", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sunday ribs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("insights-search").performScrollTo().performTextInput("Brisket")
        compose.onNodeWithText("Saturday brisket", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Sunday ribs").assertCountEquals(0)
        compose.onNodeWithTag("insights-search").performTextClearance()
        compose.onNodeWithText("Sunday ribs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rated 4+").performScrollTo().performClick()
        compose.onAllNodesWithText("Sunday ribs").assertCountEquals(0)
        compose.onNodeWithText("Rated 4+").performScrollTo().performClick()
        compose.onNodeWithText("Sunday ribs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onAllNodesWithText("Sunday ribs").assertCountEquals(0)
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithText("Sunday ribs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Smoker: All smokers").performScrollTo().performClick()
        compose.onNodeWithText("Offset smoker").performClick()
        compose.onAllNodesWithText("Sunday ribs").assertCountEquals(0)
        compose.onNodeWithContentDescription("Smoker: Offset smoker").performScrollTo().performClick()
        compose.onNodeWithText("All smokers").performClick()
        compose.onNodeWithText("Sunday ribs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("insights-statistics").performScrollTo().performClick()
        compose.onNodeWithText("Completed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Avg. rating").performScrollTo().assertIsDisplayed()
    }

    /** Slice 6 seed: a completed cook with §4.2 inputs recorded, a
     * completed cook predating capture (no input rows at all), and a cook
     * still on the pit with a finish target and a climbing probe. */
    private fun seedCompareCooks(): Triple<String, String, String> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PitTechApplication
        return runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            val now = System.currentTimeMillis()
            val recorded = app.cookRepository.startCook(
                NewCookDraft(title = "Paper brisket", setpointText = "250", dishes = listOf(DishDraft(name = "Brisket", foodType = "Beef", cut = "Whole packer", weightText = "12")))
            )
            val legacy = app.cookRepository.startCook(
                NewCookDraft(title = "Old ribs", dishes = listOf(DishDraft(name = "Ribs", foodType = "Pork", cut = "Spare ribs", weightText = "4")))
            )
            val live = app.cookRepository.startCook(
                NewCookDraft(title = "Live butt", dishes = listOf(DishDraft(name = "Pork butt", foodType = "Pork", cut = "Boston butt", weightText = "8")))
            )
            val dao = app.database.cookDao()
            val recordedDish = dao.getDishesForCook(recorded).single().id
            val liveDish = dao.getDishesForCook(live).single().id
            dao.finishCook(recorded, now)
            dao.finishCook(legacy, now)
            dao.upsertResult(CookResultEntity("wrap-$recorded", recorded, recordedDish, "wrap_material", textValue = "butcher_paper", recordedAtUtcMillis = now))
            dao.upsertResult(CookResultEntity("rub-$recorded", recorded, recordedDish, "rub", textValue = "House rub", recordedAtUtcMillis = now))
            dao.upsertResult(CookResultEntity("spritz-$recorded", recorded, recordedDish, "spritz", textValue = "yes", recordedAtUtcMillis = now))
            dao.upsertResult(CookResultEntity("medium-$recorded", recorded, recordedDish, "spritz_medium", textValue = "Apple juice", recordedAtUtcMillis = now))
            app.cookRepository.addTarget(live, liveDish, "personal_finish", 203.0, "°F", null)
            dao.insertProbesIgnoringDuplicates(listOf(
                ProbeEntity("p-$live", live, null, null, "Probe", "food", "controller_cloud", now),
            ))
            dao.insertReadingsIgnoringDuplicates((0..18).map { i ->
                SensorReadingEntity(
                    "lr-$i", live, liveDish, "p-$live", "Probe", "food", 100.0 + i, "°F",
                    now - (90 - i * 5) * 60_000L, "UTC", "controller_cloud", recordedAtUtcMillis = now,
                )
            })
            Triple(recorded, legacy, live)
        }
    }

    @Test fun compareShowsRecordedInputsProjectedTagAndOffsetStepper() {
        val (recorded, legacy, live) = seedCompareCooks()
        openInsights()
        compose.onNodeWithText("Select cooks").performScrollTo().performClick()
        compose.onNodeWithTag("insights-cook-$recorded").performScrollTo().performClick()
        compose.onNodeWithTag("insights-cook-$legacy").performScrollTo().performClick()
        compose.onNodeWithTag("insights-cook-$live").performScrollTo().performClick()
        compose.onNodeWithText("Selected comparison").performScrollTo().assertIsDisplayed()
        // 5.2: the recorded inputs diff beside the outcomes.
        compose.onNodeWithText("Butcher paper").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("House rub").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yes · Apple juice", substring = true).performScrollTo().assertIsDisplayed()
        // 5.5: the pre-capture cook's input cells say so, in so many words.
        assertTrue(compose.onAllNodesWithText("Not recorded").fetchSemanticsNodes().size >= 4)
        // 5.3: the in-progress cook's total wears the PROJECTED tag, and
        // its basis is stated next to the comparison.
        compose.onNodeWithTag("compare-projected-tag-$live").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("compare-projection-basis-$live").performScrollTo().assertIsDisplayed()
        // 5.1: the offset stepper nudges one cook's curve off food-on.
        compose.onNodeWithTag("compare-offset-plus-$recorded").performScrollTo().performClick()
        compose.onNodeWithTag("compare-offset-value-$recorded").assertTextEquals("+5m")
        compose.onNodeWithTag("compare-offset-minus-$recorded").performScrollTo().performClick()
        compose.onNodeWithTag("compare-offset-value-$recorded").assertTextEquals("Food-on")
    }

    @Test fun freeComparisonShowsRecordedDifferences() {
        val (first, second) = seedTwoFinishedCooks()
        openInsights()
        compose.onNodeWithText("Select cooks").performScrollTo().performClick()
        compose.onNodeWithTag("insights-cook-$first").performScrollTo().performClick()
        compose.onNodeWithTag("insights-cook-$second").performScrollTo().performClick()
        compose.onNodeWithText("Selected comparison").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("comparison-line-0").performScrollTo().assertTextContains("Smoked", substring = true).assertTextContains("Grilled", substring = true)
        compose.onNodeWithTag("comparison-line-1").performScrollTo().assertTextContains("lb", substring = true).assertTextContains("kg", substring = true)
        compose.onNodeWithTag("comparison-line-2").performScrollTo().assertTextContains("°F", substring = true).assertTextContains("°C", substring = true)
        compose.onNodeWithTag("comparison-line-3").performScrollTo().assertTextContains("unavailable", substring = true)
        compose.onNodeWithTag("comparison-line-4").performScrollTo().assertTextContains("pit", substring = true)
        compose.onNodeWithTag("chart-y-axis").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-x-axis").performScrollTo().assertIsDisplayed()
    }
}
