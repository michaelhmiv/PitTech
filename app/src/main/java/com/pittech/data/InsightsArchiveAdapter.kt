package com.pittech.data

import com.pittech.domain.InsightArchive
import com.pittech.domain.InsightCookInput
import com.pittech.domain.InsightDishInput
import com.pittech.domain.InsightEventInput
import com.pittech.domain.InsightReadingInput
import com.pittech.domain.InsightResultInput

/** Maps the local Room graph onto the versioned insights input. Later engines consume this; slice 15 proves it with golden tests. */
fun insightArchive(cooks: List<CookWithDishes>, readings: List<SensorReadingEntity>, results: List<CookResultEntity>): InsightArchive {
    val inputs = cooks.map { item ->
        InsightCookInput(item.cook.id, item.cook.startedAtUtcMillis, item.cook.endedAtUtcMillis, item.lifecycles.firstOrNull()?.timingMode ?: "legacy_preserved")
    }
    val dishes = cooks.flatMap { item -> item.dishes.filter { it.cookId == item.cook.id }.map { InsightDishInput(it.id, it.cookId, it.cut?.takeIf(String::isNotBlank), it.weightValue, it.weightUnit) } }
    val events = cooks.flatMap { item ->
        notePlanEvents(item.events.filter { it.cookId == item.cook.id }).map { InsightEventInput(it.id, item.cook.id, listOfNotNull(it.dishId), it.action, it.at) }
    }
    val ids = cooks.map { it.cook.id }.toSet()
    return InsightArchive(
        cooks = inputs,
        dishes = dishes,
        events = events,
        readings = readings.filter { it.cookId in ids }.map { InsightReadingInput(it.id, it.cookId, it.dishId, it.probeId, it.probeName, it.measurementType, it.value, it.unit, it.measuredAtUtcMillis, it.source, it.sourceDeviceId, it.qualityStatus) },
        results = results.filter { it.cookId in ids }.map { InsightResultInput(it.id, it.cookId, it.dishId, it.resultType, it.numericValue, it.textValue, it.recordedAtUtcMillis) },
    )
}
