package com.pittech.data

import com.pittech.domain.InsightsCalculations
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsightsArchiveAdapterTest {
    private fun cook(id: String, start: Long, end: Long?, mode: String, dishes: List<DishEntity>, events: List<TimelineEventEntity>) = CookWithDishes(
        cook = CookEntity(id, "Cook $id", CookStatus.COMPLETED, start, "UTC", endedAtUtcMillis = end, createdAtUtcMillis = start, updatedAtUtcMillis = end ?: start),
        dishes = dishes,
        events = events,
        lifecycles = listOf(CookLifecycle(id, mode)),
    )
    private fun dish(id: String, cook: String, cut: String? = null, weight: Double? = null, unit: String? = null) =
        DishEntity(id, cook, "Dish $id", "Pork", cut, weightValue = weight, weightUnit = unit, createdAtUtcMillis = 0, updatedAtUtcMillis = 0)
    private fun event(id: String, cook: String, dish: String?, type: String, at: Long) =
        TimelineEventEntity(id, cook, dish, type, type, occurredAtUtcMillis = at, recordedAtUtcMillis = at, timeZoneId = "UTC", source = "manual", createdAtUtcMillis = at, updatedAtUtcMillis = at)
    private fun reading(id: String, cook: String, dish: String?, value: Double, unit: String, source: String = "manual") =
        SensorReadingEntity(id, cook, dish, "p", "Probe", "food", value, unit, 2000, "UTC", source, recordedAtUtcMillis = 2000)

    @Test fun roomGraphMapsOntoVersionedInputs() {
        val cooks = listOf(cook("c1", 1000, 9000, "explicit_phases", listOf(dish("d1", "c1", "Butt", 8.5, "lb")),
            listOf(event("e1", "c1", "d1", "food_on", 1000), event("e2", "c1", "d1", "remove", 5000))))
        val archive = insightArchive(cooks, listOf(reading("r1", "c1", "d1", 150.0, "°F")),
            listOf(CookResultEntity("res1", "c1", "d1", "overall_rating", numericValue = 4.5, recordedAtUtcMillis = 8000)))
        val dish = InsightsCalculations.compute(archive, 9000).cooks.single().dishes.single()
        assertEquals(4000L, dish.heatingMillis)
        assertEquals("Butt", dish.cut)
        assertEquals(4.5, dish.rating!!, 0.0)
        assertEquals(1, dish.validReadings)
    }

    @Test fun adapterMatchesFixtureDecodeForTheSameCook() {
        val text = checkNotNull(javaClass.getResourceAsStream("/insights-calculations-cases-v1.json")).bufferedReader().use { it.readText() }
        val first = JSONObject(text).getJSONArray("cases").getJSONObject(0)
        val archiveJson = first.getJSONObject("archive")
        val fromJson = InsightsCalculations.compute(InsightsCalculations.archiveWithLifecycles(archiveJson), first.getLong("asOfUtcMillis"))
        val cooks = archiveJson.getJSONArray("cooks").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        val relations = cooks.map { c ->
            val id = c.getString("id")
            val dishes = archiveJson.getJSONArray("dishes").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
                .filter { it.getString("cookId") == id }
                .map { dish(it.getString("id"), id, it.takeUnless { o -> o.isNull("cut") }?.getString("cut"), it.takeUnless { o -> o.isNull("weightValue") }?.getDouble("weightValue"), it.takeUnless { o -> o.isNull("weightUnit") }?.getString("weightUnit")) }
            val events = archiveJson.getJSONArray("events").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
                .filter { it.getString("cookId") == id }
                .flatMap { e ->
                    val scopes = e.getJSONArray("dishIds").let { s -> (0 until s.length()).map { s.getString(it) } }
                    if (scopes.isEmpty()) listOf(event(e.getString("id"), id, null, e.getString("action"), e.getLong("at")))
                    else scopes.map { event(e.getString("id"), id, it, e.getString("action"), e.getLong("at")) }
                }
            val mode = archiveJson.getJSONArray("lifecycles").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.first { it.getString("cookId") == id }.getString("timingMode")
            cook(id, c.getLong("startedAtUtcMillis"), if (c.isNull("endedAtUtcMillis")) null else c.getLong("endedAtUtcMillis"), mode, dishes, events)
        }
        val readings = archiveJson.getJSONArray("readings").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.map { o ->
            SensorReadingEntity(o.getString("id"), o.getString("cookId"), o.takeUnless { it.isNull("dishId") }?.getString("dishId"), o.takeUnless { it.isNull("probeId") }?.getString("probeId"), o.getString("probeName"), o.getString("measurementType"), o.getDouble("value"), o.getString("unit"), o.getLong("measuredAtUtcMillis"), "UTC", o.getString("source"), o.takeUnless { it.isNull("sourceDeviceId") }?.getString("sourceDeviceId"), o.getString("qualityStatus"), o.getLong("measuredAtUtcMillis"))
        }
        val results = archiveJson.getJSONArray("results").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.map { o ->
            CookResultEntity(o.getString("id"), o.getString("cookId"), o.takeUnless { it.isNull("dishId") }?.getString("dishId"), o.getString("resultType"), numericValue = o.takeUnless { it.isNull("numericValue") }?.getDouble("numericValue"), textValue = o.takeUnless { it.isNull("textValue") }?.getString("textValue"), recordedAtUtcMillis = o.getLong("recordedAtUtcMillis"))
        }
        val fromEntities = InsightsCalculations.compute(insightArchive(relations, readings, results), first.getLong("asOfUtcMillis"))
        assertEquals(fromJson, fromEntities)
    }
}
