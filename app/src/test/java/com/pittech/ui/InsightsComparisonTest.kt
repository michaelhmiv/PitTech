package com.pittech.ui

import com.pittech.data.CookEntity
import com.pittech.data.CookResultEntity
import com.pittech.data.CookStatus
import com.pittech.data.CookWithDishes
import com.pittech.data.DishEntity
import com.pittech.data.SensorReadingEntity
import org.junit.Assert.*
import org.junit.Test

class InsightsComparisonTest {
    private fun cook(id: String, dishes: List<DishEntity>) = CookWithDishes(
        cook = CookEntity(id, "Cook $id", CookStatus.COMPLETED, 1000, "UTC", endedAtUtcMillis = 9000, createdAtUtcMillis = 1000, updatedAtUtcMillis = 9000),
        dishes = dishes,
    )
    private fun dish(id: String, cook: String, weight: Double? = null, unit: String? = null) =
        DishEntity(id, cook, "Dish $id", "Pork", null, weightValue = weight, weightUnit = unit, createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
    private fun reading(id: String, cook: String, dish: String?, type: String, value: Double, unit: String, source: String = "controller_cloud", quality: String = "valid") =
        SensorReadingEntity(id, cook, dish, "p-$cook", "Probe", type, value, unit, 2000, "UTC", source, qualityStatus = quality, recordedAtUtcMillis = 2000)

    @Test fun comparisonShowsMethodsWeightsUnitsQualityAndCoverage() {
        val cooks = listOf(cook("c1", listOf(dish("d1", "c1", 8.5, "lb"))), cook("c2", listOf(dish("d2", "c2"))))
        val results = listOf(CookResultEntity("m1", "c1", "d1", "method", textValue = "Smoked", recordedAtUtcMillis = 8000))
        val readings = listOf(
            reading("r1", "c1", "d1", "food", 150.0, "°F"), reading("r2", "c1", "d1", "food", 160.0, "°F", source = "manual"),
            reading("r3", "c1", null, "pit", 250.0, "°F"), reading("r4", "c1", "d1", "food", 999.0, "°F", quality = "unavailable"),
            reading("r5", "c2", "d2", "food", 70.0, "°C"), reading("r6", "c2", "d2", "food", 75.0, "°C"),
        )
        assertEquals(
            listOf(
                "Methods: Smoked · not recorded",
                "Weights: 8.5 lb · not recorded",
                "Reading units: °F · °C",
                "Recorded readings: 3 (1 manual), 1 unavailable · 2",
                "Channels: food, pit · food",
            ),
            comparisonDifferenceLines(cooks, readings, results),
        )
    }

    @Test fun comparisonLinesNeverClaimCausationOrDoneness() {
        val cooks = listOf(cook("c1", listOf(dish("d1", "c1", 8.5, "lb"))), cook("c2", listOf(dish("d2", "c2", 4.0, "kg"))))
        val lines = comparisonDifferenceLines(cooks, listOf(reading("r1", "c1", "d1", "food", 150.0, "°F"), reading("r2", "c2", "d2", "food", 70.0, "°C")), emptyList())
        assertEquals(5, lines.size)
        val banned = listOf("faster", "slower", "because", "caus", "predict", "safe", "doneness", "undercook", "overcook", "better", "worse", "should")
        lines.forEach { line -> banned.forEach { word -> assertFalse("$line contains $word", line.lowercase().contains(word)) } }
    }

    @Test fun singleCookHasNoComparisonLines() {
        assertTrue(comparisonDifferenceLines(listOf(cook("c1", emptyList())), emptyList(), emptyList()).isEmpty())
    }
}
