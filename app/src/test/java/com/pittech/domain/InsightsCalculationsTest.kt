package com.pittech.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsightsCalculationsTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private val epsilon = 1e-9

    private fun assertDish(name: String, expected: JSONObject, actual: InsightDishMetrics) {
        fun actualValue(key: String): Long? = when (key) {
            "foodOnAt" -> actual.foodOnAt; "wrapAt" -> actual.wrapAt; "removeAt" -> actual.removeAt
            "restStartAt" -> actual.restStartAt; "holdStartAt" -> actual.holdStartAt; "dishDoneAt" -> actual.dishDoneAt
            "heatingMillis" -> actual.heatingMillis; "restMillis" -> actual.restMillis; "holdMillis" -> actual.holdMillis
            else -> error(key)
        }
        fun longs(key: String) = if (expected.isNull(key)) assertNull("$name/$key", actualValue(key)) else assertEquals("$name/$key", expected.getLong(key), actualValue(key))
        listOf("foodOnAt", "wrapAt", "removeAt", "restStartAt", "holdStartAt", "dishDoneAt", "heatingMillis", "restMillis", "holdMillis").forEach(::longs)
        if (expected.isNull("heatingIntervalCount")) assertNull("$name/count", actual.heatingIntervalCount)
        else assertEquals("$name/count", expected.getInt("heatingIntervalCount"), actual.heatingIntervalCount)
        assertEquals("$name/dish", expected.getString("dishId"), actual.dishId)
        assertEquals("$name/cut", if (expected.isNull("cut")) null else expected.getString("cut"), actual.cut)
        if (expected.isNull("weightValue")) assertNull("$name/weightValue", actual.weightValue)
        else assertEquals("$name/weightValue", expected.getDouble("weightValue"), actual.weightValue!!, epsilon)
        assertEquals("$name/weightUnit", if (expected.isNull("weightUnit")) null else expected.getString("weightUnit"), actual.weightUnit)
        assertEquals("$name/methods", (0 until expected.getJSONArray("methods").length()).map { expected.getJSONArray("methods").getString(it) }, actual.methods)
        assertEquals("$name/valid", expected.getInt("validReadings"), actual.validReadings)
        assertEquals("$name/manual", expected.getInt("manualReadings"), actual.manualReadings)
        assertEquals("$name/excluded", expected.getInt("excludedReadings"), actual.excludedReadings)
        if (expected.isNull("rating")) assertNull("$name/rating", actual.rating)
        else assertEquals("$name/rating", expected.getDouble("rating"), actual.rating!!, epsilon)
        if (expected.isNull("cookAgain")) assertNull("$name/again", actual.cookAgain)
        else assertEquals("$name/again", expected.getBoolean("cookAgain"), actual.cookAgain)
        assertEquals("$name/notes", expected.getBoolean("hasResultNotes"), actual.hasResultNotes)
    }

    private fun assertCook(name: String, expected: JSONObject, actual: InsightCookMetrics) {
        assertEquals("$name/cook", expected.getString("cookId"), actual.cookId)
        if (expected.isNull("equipmentHeatingMillis")) assertNull("$name/equipment", actual.equipmentHeatingMillis)
        else assertEquals("$name/equipment", expected.getLong("equipmentHeatingMillis"), actual.equipmentHeatingMillis)
        val channels = expected.getJSONArray("channels")
        assertEquals("$name/channelCount", channels.length(), actual.channels.size)
        (0 until channels.length()).forEach { i ->
            val c = channels.getJSONObject(i); val got = actual.channels[i]
            assertEquals("$name/channel", c.getString("channelId"), got.channelId)
            assertEquals("$name/role", c.getString("role"), got.role)
            assertEquals("$name/dish", if (c.isNull("dishId")) null else c.getString("dishId"), got.dishId)
            assertEquals("$name/count", c.getInt("readingCount"), got.readingCount)
            assertEquals("$name/manual", c.getInt("manualCount"), got.manualCount)
            if (c.isNull("minF")) assertNull("$name/min", got.minF) else assertEquals("$name/min", c.getDouble("minF"), got.minF!!, epsilon)
            if (c.isNull("maxF")) assertNull("$name/max", got.maxF) else assertEquals("$name/max", c.getDouble("maxF"), got.maxF!!, epsilon)
            assertEquals("$name/units", (0 until c.getJSONArray("units").length()).map { c.getJSONArray("units").getString(it) }, got.units)
        }
        val dishes = expected.getJSONArray("dishes")
        assertEquals("$name/dishCount", dishes.length(), actual.dishes.size)
        (0 until dishes.length()).forEach { assertDish(name, dishes.getJSONObject(it), actual.dishes[it]) }
    }

    @Test fun sharedGoldenArchivesProduceVersionedDishMetrics() {
        val fixture = JSONObject(resource("insights-calculations-cases-v1"))
        assertEquals(1, fixture.getInt("contractVersion"))
        val cases = fixture.getJSONArray("cases")
        assertEquals(14, cases.length())
        (0 until cases.length()).forEach { i ->
            val c = cases.getJSONObject(i)
            val report = InsightsCalculations.compute(InsightsCalculations.archiveWithLifecycles(c.getJSONObject("archive")), c.getLong("asOfUtcMillis"))
            val expected = c.getJSONObject("expected")
            assertEquals(c.getString("name"), 1, expected.getInt("calculationVersion"))
            assertEquals(c.getString("name"), InsightsCalculations.CALCULATION_VERSION, report.calculationVersion)
            assertEquals(c.getString("name"), expected.getJSONArray("cooks").length(), report.cooks.size)
            (0 until expected.getJSONArray("cooks").length()).forEach { k -> assertCook(c.getString("name"), expected.getJSONArray("cooks").getJSONObject(k), report.cooks[k]) }
        }
        assertEquals(1, JSONObject(resource("insights-calculations-v1")).getInt("calculationVersion"))
    }

    @Test fun missingAnchorsStayMissingAndNeverBecomeWallClock() {
        val archive = InsightsCalculations.archiveWithLifecycles(JSONObject()
            .put("cooks", org.json.JSONArray().put(JSONObject().put("id", "c").put("startedAtUtcMillis", 1000).put("endedAtUtcMillis", 9000)))
            .put("dishes", org.json.JSONArray().put(JSONObject().put("id", "d").put("cookId", "c")))
            .put("events", org.json.JSONArray()).put("readings", org.json.JSONArray()).put("results", org.json.JSONArray())
            .put("lifecycles", org.json.JSONArray().put(JSONObject().put("cookId", "c").put("timingMode", "explicit_phases"))))
        val dish = InsightsCalculations.compute(archive, 9000).cooks.single().dishes.single()
        assertNull(dish.heatingMillis); assertNull(dish.restMillis); assertNull(dish.holdMillis); assertNull(dish.foodOnAt)
        assertNull(dish.cut); assertNull(dish.weightValue); assertNull(dish.rating)
    }

    @Test fun equipmentHoursNeverDoubleCountAndRoundTripStaysStable() {
        val archive = InsightArchive(
            cooks = listOf(InsightCookInput("c", 0, 100, "explicit_phases")),
            dishes = listOf(InsightDishInput("a", "c", null, null, null), InsightDishInput("b", "c", null, null, null)),
            events = listOf(InsightEventInput("1", "c", listOf("a"), "food_on", 0), InsightEventInput("2", "c", listOf("a"), "remove", 100), InsightEventInput("3", "c", listOf("b"), "food_on", 0), InsightEventInput("4", "c", listOf("b"), "remove", 100)),
            readings = emptyList(), results = emptyList(),
        )
        val cook = InsightsCalculations.compute(archive, 100).cooks.single()
        assertEquals(100L, cook.dishes[0].heatingMillis)
        assertEquals(100L, cook.dishes[1].heatingMillis)
        assertEquals(100L, cook.equipmentHeatingMillis)
        listOf(32.0, 150.0, 212.0, 225.0, 100.0).forEach { f ->
            val c = (f - 32.0) * 5.0 / 9.0
            assertEquals(f, c * 9.0 / 5.0 + 32.0, epsilon)
            assertEquals(f, InsightsCalculations.fahrenheit(InsightsCalculations.fahrenheit(f, "°F").let { (it - 32.0) * 5.0 / 9.0 }, "°C"), epsilon)
        }
    }

    @Test fun engineRoleMatchesCanonicalGuideRoles() {
        mapOf("food" to "food", "food_probe" to "food", "internal_temperature" to "food", "pit" to "pit", "pit_ambient" to "pit", "ambient_temperature" to "pit", "grill_temperature" to "pit", "pit_temperature" to "pit", "pit_probe" to "pit", "setpoint" to "setpoint").forEach { (raw, role) ->
            assertEquals(role, InsightsCalculations.role(raw))
            assertEquals(role, com.pittech.data.RecipeGuideProjection.role(raw))
        }
    }
}
