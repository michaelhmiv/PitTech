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
        compose.onNodeWithText("Saturday brisket").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sunday ribs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("insights-search").performScrollTo().performTextInput("Brisket")
        compose.onNodeWithText("Saturday brisket").performScrollTo().assertIsDisplayed()
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

    @Test fun freeComparisonShowsRecordedDifferences() {
        val (first, second) = seedTwoFinishedCooks()
        openInsights()
        compose.onNodeWithText("Select cooks").performScrollTo().performClick()
        compose.onNodeWithTag("insights-cook-$first").performScrollTo().performClick()
        compose.onNodeWithTag("insights-cook-$second").performScrollTo().performClick()
        compose.onNodeWithText("Selected comparison").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("comparison-line-0").performScrollTo().assertTextContains("Smoked").assertTextContains("Grilled")
        compose.onNodeWithTag("comparison-line-1").performScrollTo().assertTextContains("lb").assertTextContains("kg")
        compose.onNodeWithTag("comparison-line-2").performScrollTo().assertTextContains("°F").assertTextContains("°C")
        compose.onNodeWithTag("comparison-line-3").performScrollTo().assertTextContains("unavailable")
        compose.onNodeWithTag("comparison-line-4").performScrollTo().assertTextContains("pit")
        compose.onNodeWithTag("chart-y-axis").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-x-axis").performScrollTo().assertIsDisplayed()
    }
}
