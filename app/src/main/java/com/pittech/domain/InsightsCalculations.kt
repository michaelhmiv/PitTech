package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject

data class InsightCookInput(val id: String, val startedAtUtcMillis: Long, val endedAtUtcMillis: Long?, val timingMode: String)
data class InsightDishInput(val id: String, val cookId: String, val cut: String?, val weightValue: Double?, val weightUnit: String?)
data class InsightEventInput(val id: String, val cookId: String, val dishIds: List<String>, val action: String, val at: Long)
data class InsightReadingInput(val id: String, val cookId: String, val dishId: String?, val probeId: String?, val probeName: String, val measurementType: String, val value: Double?, val unit: String, val measuredAtUtcMillis: Long, val source: String, val sourceDeviceId: String?, val qualityStatus: String)
data class InsightResultInput(val id: String, val cookId: String, val dishId: String?, val resultType: String, val numericValue: Double?, val textValue: String?, val recordedAtUtcMillis: Long)
data class InsightArchive(val cooks: List<InsightCookInput>, val dishes: List<InsightDishInput>, val events: List<InsightEventInput>, val readings: List<InsightReadingInput>, val results: List<InsightResultInput>)

data class InsightChannel(val channelId: String, val dishId: String?, val role: String, val readingCount: Int, val manualCount: Int, val minF: Double?, val maxF: Double?, val units: List<String>)
data class InsightDishMetrics(val dishId: String, val cut: String?, val weightValue: Double?, val weightUnit: String?, val methods: List<String>, val foodOnAt: Long?, val wrapAt: Long?, val removeAt: Long?, val restStartAt: Long?, val holdStartAt: Long?, val dishDoneAt: Long?, val heatingMillis: Long?, val restMillis: Long?, val holdMillis: Long?, val heatingIntervalCount: Int?, val validReadings: Int, val manualReadings: Int, val excludedReadings: Int, val rating: Double?, val cookAgain: Boolean?, val hasResultNotes: Boolean)
data class InsightCookMetrics(val cookId: String, val equipmentHeatingMillis: Long?, val channels: List<InsightChannel>, val dishes: List<InsightDishMetrics>)
data class InsightReport(val calculationVersion: Int, val cooks: List<InsightCookMetrics>)

/**
 * Deterministic dish-level calculations pinned by contracts/insights-calculations-v1.json.
 * Session creation time is never cooking time: durations come only from phase anchors
 * under explicit_phases timing. Missing data stays missing; nothing is interpolated.
 * The iOS InsightsCalculations engine mirrors this file rule for rule.
 */
object InsightsCalculations {
    const val CALCULATION_VERSION = 1
    const val DOUBLE_EPSILON = 1e-9
    private val foodTypes = setOf("food", "food_probe", "internal_temperature")
    private val pitTypes = setOf("pit", "pit_ambient", "ambient_temperature", "grill_temperature", "pit_temperature", "pit_probe")
    private val temperatureUnits = setOf("°F", "°C")
    private val heatingPhases = setOf("cooking", "wrapped")

    fun role(measurementType: String): String = when (measurementType) {
        in foodTypes -> "food"
        in pitTypes -> "pit"
        else -> measurementType
    }

    fun channelId(cookId: String, probeId: String?, probeName: String, sourceDeviceId: String?, dishId: String?, measurementType: String, source: String): String =
        listOf(cookId, probeId ?: probeName, sourceDeviceId.orEmpty(), dishId.orEmpty(), measurementType, source).joinToString(":")

    fun fahrenheit(value: Double, unit: String): Double = CookPlanEngine.fahrenheit(value, unit)

    fun compute(archive: InsightArchive, asOfUtcMillis: Long): InsightReport {
        val cooks = archive.cooks.sortedBy { it.id }.map { cook ->
            val end = cook.endedAtUtcMillis ?: asOfUtcMillis
            val dishes = archive.dishes.filter { it.cookId == cook.id }.sortedBy { it.id }
            val events = archive.events.filter { it.cookId == cook.id }.flatMap { e ->
                if (e.dishIds.isEmpty()) listOf(PlanEvent(e.id, e.action, null, e.at))
                else e.dishIds.map { PlanEvent(e.id, e.action, it, e.at) }
            }
            val explicit = cook.timingMode == "explicit_phases"
            val phases = if (explicit && dishes.isNotEmpty()) CookPhases.all(events, dishes.map { it.id }, cook.startedAtUtcMillis, end) else emptyList()
            val dishMetrics = dishes.mapIndexed { index, dish ->
                val scoped = events.filter { (it.dishId == null || it.dishId == dish.id) && it.action in CookPhases.phaseActions && it.at <= end }
                    .sortedWith(compareBy<PlanEvent> { it.at }.thenBy { it.id })
                fun anchor(action: String) = scoped.firstOrNull { it.action == action }?.at
                val phase = phases.getOrNull(index)
                val measured = explicit && phase != null && scoped.any { it.action == "food_on" }
                val rows = archive.results.filter { it.cookId == cook.id && (it.dishId == null || it.dishId == dish.id) }
                val dishRows = archive.readings.filter { it.cookId == cook.id && it.dishId == dish.id }
                InsightDishMetrics(
                    dishId = dish.id, cut = dish.cut, weightValue = dish.weightValue, weightUnit = dish.weightUnit,
                    methods = rows.filter { it.resultType == "method" && !it.textValue.isNullOrBlank() }.map { it.textValue!!.trim() }.distinct().sorted(),
                    foodOnAt = anchor("food_on"), wrapAt = anchor("wrap"), removeAt = anchor("remove"),
                    restStartAt = anchor("rest_start"), holdStartAt = anchor("hold_start"), dishDoneAt = anchor("dish_done"),
                    heatingMillis = if (measured) phase?.cookingMillis else null,
                    restMillis = if (measured) phase?.restMillis else null,
                    holdMillis = if (measured) phase?.holdMillis else null,
                    heatingIntervalCount = if (measured) phase?.intervals?.count { it.phase in heatingPhases } else null,
                    validReadings = dishRows.count { isMeasured(it) },
                    manualReadings = dishRows.count { isMeasured(it) && it.source == "manual" },
                    excludedReadings = dishRows.count { !isMeasured(it) },
                    rating = pickRating(rows), cookAgain = pickCookAgain(rows),
                    hasResultNotes = rows.any { it.resultType == "result_notes" },
                )
            }
            val channels = archive.readings.filter { it.cookId == cook.id && isMeasured(it) }
                .groupBy { channelId(it.cookId, it.probeId, it.probeName, it.sourceDeviceId, it.dishId, it.measurementType, it.source) }
                .map { (id, rows) ->
                    val stats = rows.map { fahrenheit(it.value!!, it.unit) }
                    InsightChannel(id, rows.first().dishId, role(rows.first().measurementType), rows.size, rows.count { it.source == "manual" }, stats.minOrNull(), stats.maxOrNull(), rows.map { it.unit }.distinct().sorted())
                }.sortedBy { it.channelId }
            val heating = phases.flatMap { p -> p.intervals.filter { it.phase in heatingPhases } }
            InsightCookMetrics(cook.id, if (heating.isEmpty()) null else CookPhases.heatingMillis(phases), channels, dishMetrics)
        }
        return InsightReport(CALCULATION_VERSION, cooks)
    }

    private fun isMeasured(r: InsightReadingInput) = r.qualityStatus == "valid" && r.value != null && r.value.isFinite() && r.unit in temperatureUnits

    private fun pickRating(rows: List<InsightResultInput>): Double? {
        val ranked = rows.filter { it.resultType in setOf("overall_rating", "overall") && it.numericValue != null }
            .sortedWith(compareBy<InsightResultInput> { if (it.resultType == "overall_rating") 0 else 1 }.thenBy { if (it.dishId == null) 1 else 0 }.thenByDescending { it.recordedAtUtcMillis }.thenBy { it.id })
        return ranked.firstOrNull()?.numericValue
    }

    private fun pickCookAgain(rows: List<InsightResultInput>): Boolean? {
        val ranked = rows.filter { it.resultType == "cook_again" && it.numericValue != null }
            .sortedWith(compareBy<InsightResultInput> { if (it.dishId == null) 1 else 0 }.thenByDescending { it.recordedAtUtcMillis }.thenBy { it.id })
        return when (ranked.firstOrNull()?.numericValue) { 1.0 -> true; 0.0 -> false; else -> null }
    }

    private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)

    fun archiveFromJson(root: JSONObject): InsightArchive {
        fun objects(key: String): List<JSONObject> {
            val array = root.optJSONArray(key) ?: JSONArray()
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        return InsightArchive(
            cooks = objects("cooks").map { o -> InsightCookInput(o.getString("id"), o.getLong("startedAtUtcMillis"), if (o.isNull("endedAtUtcMillis")) null else o.getLong("endedAtUtcMillis"), o.optString("timingMode", "legacy_preserved")) },
            dishes = objects("dishes").map { o -> InsightDishInput(o.getString("id"), o.getString("cookId"), o.stringOrNull("cut"), if (o.isNull("weightValue")) null else o.getDouble("weightValue"), o.stringOrNull("weightUnit")) },
            events = objects("events").map { o ->
                val scopes = o.optJSONArray("dishIds") ?: JSONArray()
                InsightEventInput(o.getString("id"), o.getString("cookId"), (0 until scopes.length()).map { scopes.getString(it) }, o.getString("action"), o.getLong("at"))
            },
            readings = objects("readings").map { o ->
                InsightReadingInput(o.getString("id"), o.getString("cookId"), o.stringOrNull("dishId"), o.stringOrNull("probeId"), o.getString("probeName"), o.getString("measurementType"), if (o.isNull("value")) null else o.getDouble("value"), o.getString("unit"), o.getLong("measuredAtUtcMillis"), o.getString("source"), o.stringOrNull("sourceDeviceId"), o.getString("qualityStatus"))
            },
            results = objects("results").map { o ->
                InsightResultInput(o.getString("id"), o.getString("cookId"), o.stringOrNull("dishId"), o.getString("resultType"), if (o.isNull("numericValue")) null else o.getDouble("numericValue"), o.stringOrNull("textValue"), o.getLong("recordedAtUtcMillis"))
            },
        )
    }

    /** Lifecycles arrive as a sibling archive table; cooks carry the resolved mode. */
    fun archiveWithLifecycles(root: JSONObject): InsightArchive {
        val archive = archiveFromJson(root)
        val modes = mutableMapOf<String, String>()
        val array = root.optJSONArray("lifecycles") ?: JSONArray()
        (0 until array.length()).forEach { modes[array.getJSONObject(it).getString("cookId")] = array.getJSONObject(it).getString("timingMode") }
        return archive.copy(cooks = archive.cooks.map { it.copy(timingMode = modes[it.id] ?: "legacy_preserved") })
    }
}
