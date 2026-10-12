package com.pittech.domain

import org.json.JSONObject

data class CookReference(val dishId: String, val sourceCookId: String, val sourceDishId: String, val currentProbe: String = "", val sourceProbe: String = "", val anchor: String = "food_on", val currentSeriesId: String? = null, val sourceSeriesId: String? = null)
data class CurveSample(val value: Double, val unit: String, val at: Long, val valid: Boolean = true)
data class AlignedSample(val minutes: Double, val fahrenheit: Double)
object CookReferenceCodec {
    fun encode(r: CookReference): String {
        require(listOf(r.dishId, r.sourceCookId, r.sourceDishId).all { it.isNotBlank() } && r.anchor in setOf("food_on", "wrap")) { "Choose a dish, reference and alignment." }
        return JSONObject().put("version", 1).put("dishId", r.dishId).put("sourceCookId", r.sourceCookId).put("sourceDishId", r.sourceDishId).put("currentProbe", r.currentProbe).put("sourceProbe", r.sourceProbe).put("anchor", r.anchor).put("currentSeriesId", r.currentSeriesId).put("sourceSeriesId", r.sourceSeriesId).toString()
    }
    fun decode(raw: String): CookReference { val o = JSONObject(raw); require(o.getInt("version") == 1); return CookReference(o.getString("dishId"), o.getString("sourceCookId"), o.getString("sourceDishId"), o.optString("currentProbe"), o.optString("sourceProbe"), o.optString("anchor", "food_on"), if (o.isNull("currentSeriesId")) null else o.getString("currentSeriesId"), if (o.isNull("sourceSeriesId")) null else o.getString("sourceSeriesId")).also { encode(it) } }
    fun anchor(events: List<PlanEvent>, dishId: String, action: String): Long? = events.filter { (it.dishId == dishId || it.dishId == null) && it.action == action }.minOfOrNull { it.at }
    /** No interpolation or invented slope: each value remains a recorded sample. */
    fun align(samples: List<CurveSample>, anchor: Long): List<AlignedSample> = samples.filter { it.valid && it.value.isFinite() && it.unit in setOf("°F", "°C") && it.at >= anchor }.sortedBy { it.at }.map { AlignedSample((it.at - anchor) / 60_000.0, CookPlanEngine.fahrenheit(it.value, it.unit)) }
}

data class HistoricalHeatingInterval(val startedAt: Long, val endedAt: Long?)

/** A projection of frozen observations. Editing instructions never changes historical facts. */
object RecipeReferenceTimeline {
    fun events(snapshot: RecipeSnapshot) = snapshot.observations.sortedWith(compareBy<RecipeObservation> { it.occurredAtUtcMillis }.thenBy { it.sourceEventId })
    fun heating(snapshot: RecipeSnapshot): List<HistoricalHeatingInterval> {
        val intervals = mutableListOf<HistoricalHeatingInterval>()
        var start: Long? = null
        events(snapshot).forEach { event -> when (event.action) {
            "food_on" -> if (start == null) start = event.occurredAtUtcMillis
            "remove", "rest_start", "hold_start", "dish_done" -> start?.let { intervals += HistoricalHeatingInterval(it, event.occurredAtUtcMillis); start = null }
        } }
        start?.let { intervals += HistoricalHeatingInterval(it, null) }
        return intervals
    }
    fun relativeLabel(offset: Long?): String = offset?.let {
        val minutes = kotlin.math.abs(it) / 60_000
        "${if (it < 0) "−" else "+"}${minutes / 60}h ${minutes % 60}m"
    } ?: "No recorded Food on anchor"
}
