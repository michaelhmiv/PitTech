package com.pittech.ui

import com.pittech.data.CookResultEntity
import com.pittech.data.CookWithDishes
import com.pittech.data.RecipeGuideProjection
import com.pittech.data.SensorReadingEntity

/**
 * Factual per-cook difference lines for the free comparison card. Methods, weights,
 * units, telemetry quality, and channel coverage stay next to the numbers; the lines
 * never attribute causation and never predict doneness.
 */
fun comparisonDifferenceLines(cooks: List<CookWithDishes>, readings: List<SensorReadingEntity>, results: List<CookResultEntity>): List<String> {
    if (cooks.size < 2) return emptyList()
    val ordered = cooks.sortedBy { it.cook.id }
    fun methods(id: String) = results.filter { it.cookId == id && it.resultType == "method" && !it.textValue.isNullOrBlank() }.map { it.textValue!!.trim() }.distinct().sorted()
    fun weights(id: String, dishes: List<com.pittech.data.DishEntity>) = dishes.filter { it.cookId == id }.mapNotNull { dish ->
        dish.weightValue?.let { "$it ${dish.weightUnit.orEmpty()}".trim() }
    }
    fun valid(id: String) = readings.filter { it.cookId == id && it.qualityStatus == "valid" && it.value.isFinite() && it.unit in setOf("°F", "°C") }
    fun excluded(id: String) = readings.count { it.cookId == id && !(it.qualityStatus == "valid" && it.value.isFinite() && it.unit in setOf("°F", "°C")) }
    val dishes = ordered.flatMap { it.dishes }
    return listOf(
        "Methods: " + ordered.joinToString(" · ") { methods(it.cook.id).joinToString(", ").ifBlank { "not recorded" } },
        "Weights: " + ordered.joinToString(" · ") { weights(it.cook.id, dishes).joinToString(", ").ifBlank { "not recorded" } },
        "Reading units: " + ordered.joinToString(" · ") { valid(it.cook.id).map { row -> row.unit }.distinct().sorted().joinToString(", ").ifBlank { "no readings" } },
        "Recorded readings: " + ordered.joinToString(" · ") { cook ->
            val rows = valid(cook.cook.id)
            if (rows.isEmpty()) "none" + (excluded(cook.cook.id).takeIf { it > 0 }?.let { ", $it unavailable" } ?: "")
            else "${rows.size}" + (rows.count { it.source == "manual" }.takeIf { it > 0 }?.let { " ($it manual)" } ?: "") + (excluded(cook.cook.id).takeIf { it > 0 }?.let { ", $it unavailable" } ?: "")
        },
        "Channels: " + ordered.joinToString(" · ") { valid(it.cook.id).map { row -> RecipeGuideProjection.role(row.measurementType) }.distinct().sorted().joinToString(", ").ifBlank { "no readings" } },
    )
}
