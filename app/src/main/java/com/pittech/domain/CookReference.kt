package com.pittech.domain

import org.json.JSONObject

data class CookReference(val dishId: String, val sourceCookId: String, val sourceDishId: String, val currentProbe: String = "", val sourceProbe: String = "", val anchor: String = "food_on")
data class CurveSample(val value: Double, val unit: String, val at: Long, val valid: Boolean = true)
data class AlignedSample(val minutes: Double, val fahrenheit: Double)
object CookReferenceCodec {
    fun encode(r: CookReference): String {
        require(listOf(r.dishId, r.sourceCookId, r.sourceDishId).all { it.isNotBlank() } && r.anchor in setOf("food_on", "wrap")) { "Choose a dish, reference and alignment." }
        return JSONObject().put("version", 1).put("dishId", r.dishId).put("sourceCookId", r.sourceCookId).put("sourceDishId", r.sourceDishId).put("currentProbe", r.currentProbe).put("sourceProbe", r.sourceProbe).put("anchor", r.anchor).toString()
    }
    fun decode(raw: String): CookReference { val o = JSONObject(raw); require(o.getInt("version") == 1); return CookReference(o.getString("dishId"), o.getString("sourceCookId"), o.getString("sourceDishId"), o.optString("currentProbe"), o.optString("sourceProbe"), o.optString("anchor", "food_on")).also { encode(it) } }
    fun anchor(events: List<PlanEvent>, dishId: String, action: String): Long? = events.filter { (it.dishId == dishId || it.dishId == null) && it.action == action }.minOfOrNull { it.at }
    /** No interpolation or invented slope: each value remains a recorded sample. */
    fun align(samples: List<CurveSample>, anchor: Long): List<AlignedSample> = samples.filter { it.valid && it.value.isFinite() && it.unit in setOf("°F", "°C") && it.at >= anchor }.sortedBy { it.at }.map { AlignedSample((it.at - anchor) / 60_000.0, CookPlanEngine.fahrenheit(it.value, it.unit)) }
}
