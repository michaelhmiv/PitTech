package com.pittech.domain

import org.json.JSONArray
import org.json.JSONObject

/** Pure local guidance: actual timeline actions are the only anchors for physical stages. */
data class PlanEvent(val id: String, val action: String, val dishId: String?, val at: Long)
data class PlanReading(val dishId: String?, val fahrenheit: Double, val at: Long, val valid: Boolean = true, val type: String = "food", val probeName: String = "")
data class StepProgress(val status: String = "pending", val eventIds: List<String> = emptyList(), val lastDoneAt: Long? = null,
    val snoozedUntil: Long? = null, val occurrence: Int = 0, val notifiedOccurrence: Int? = null, val skippedAnchorAt: Long? = null)
data class CookPlan(val book: CookPlaybook, val dishIds: List<String>, val playbookId: String?, val paused: Boolean = false, val progress: Map<String, StepProgress> = emptyMap())
data class EvaluatedStep(val step: PlaybookStep, val progress: StepProgress, val dishId: String?, val dueAt: Long?, val ready: Boolean, val waitingFor: String?)

object CookPlanEngine {
    fun encode(plan: CookPlan): String = JSONObject(PlaybookCodec.encode(plan.book))
        .put("dishIds", JSONArray(plan.dishIds)).put("playbookId", plan.playbookId).put("paused", plan.paused)
        .put("progress", JSONObject().apply { plan.progress.forEach { (id, p) -> put(id, JSONObject().put("status", p.status).put("eventIds", JSONArray(p.eventIds)).put("lastDoneAt", p.lastDoneAt).put("snoozedUntil", p.snoozedUntil).put("occurrence", p.occurrence).put("notifiedOccurrence", p.notifiedOccurrence).put("skippedAnchorAt", p.skippedAnchorAt)) } }).toString()
    fun decode(raw: String): CookPlan {
        val o = JSONObject(raw)
        val ids = o.getJSONArray("dishIds").let { a -> (0 until a.length()).map { a.getString(it) } }
        val progress = o.optJSONObject("progress")?.let { p -> p.keys().asSequence().associateWith { id ->
            val v = p.getJSONObject(id)
            val events = v.optJSONArray("eventIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            StepProgress(v.optString("status", "pending"), events, v.longOrNull("lastDoneAt"), v.longOrNull("snoozedUntil"), v.optInt("occurrence"), if (v.isNull("notifiedOccurrence")) null else v.getInt("notifiedOccurrence"), v.longOrNull("skippedAnchorAt"))
        } }.orEmpty()
        val book = PlaybookCodec.decode(raw)
        require(ids.size == book.draft.dishes.size && ids.distinct().size == ids.size) { "The plan dish mapping is invalid." }
        require(progress.keys.all { id -> book.steps.any { it.id == id } } && progress.values.all { it.status in setOf("pending", "done", "skipped") && it.occurrence >= 0 }) { "Invalid step progress." }
        return CookPlan(book, ids, if (o.isNull("playbookId")) null else o.getString("playbookId"), o.optBoolean("paused"), progress)
    }
    private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
    fun fahrenheit(value: Double, unit: String): Double = if (unit == "°C") value * 9.0 / 5.0 + 32.0 else value
    fun stage(events: List<PlanEvent>, dishId: String?): String = when (events.filter { it.dishId == null || it.dishId == dishId }.maxByOrNull { it.at }?.action) {
        "dish_done" -> "done"
        else -> events.filter { (it.dishId == null || it.dishId == dishId) && it.action in setOf("food_on", "remove", "rest_start", "hold_start", "dish_done") }.maxByOrNull { it.at }?.action?.let {
            when (it) { "food_on" -> "cooking"; "remove" -> "removed"; "rest_start" -> "resting"; "hold_start" -> "holding"; else -> "done" }
        } ?: "prep"
    }
    fun evaluate(plan: CookPlan, events: List<PlanEvent>, readings: List<PlanReading>, now: Long): List<EvaluatedStep> = plan.book.steps.map { s ->
        val p = plan.progress[s.id] ?: StepProgress()
        val dishId = s.dishIndex?.let { plan.dishIds[it] }
        val scopeEvents = events.filter { it.dishId == null || it.dishId == dishId || (dishId == null && plan.dishIds.size == 1) }
        val currentStage = if (dishId == null && plan.dishIds.isNotEmpty()) plan.dishIds.map { stage(events, it) }.let { stages -> if (stages.distinct().size == 1) stages.first() else "cooking" } else stage(events, dishId)
        val anchor = if (s.trigger == "elapsed") "food_on" else s.anchor
        val anchorTime = scopeEvents.filter { it.action == anchor }.maxOfOrNull { it.at }
            ?: plan.book.steps.firstOrNull { it.action == anchor && it.dishIndex == s.dishIndex }?.let { plan.progress[it.id]?.skippedAnchorAt }
        val lastActual = p.eventIds.mapNotNull { id -> events.firstOrNull { it.id == id }?.at }.maxOrNull() ?: p.lastDoneAt
        val due = if (s.repeatMinutes != null && lastActual != null) lastActual + s.repeatMinutes * 60_000 else when (s.trigger) {
            "elapsed", "after_action" -> anchorTime?.plus(s.minutes * 60_000)
            "clock" -> s.clockAtUtcMillis
            else -> null
        }
        val eligibleStage = when (s.action) { "food_on" -> currentStage == "prep"; "rest_start" -> currentStage in setOf("cooking", "removed"); "hold_start" -> currentStage in setOf("removed", "resting", "holding"); "dish_done" -> currentStage in setOf("removed", "resting", "holding"); else -> currentStage == s.stage }
        val temperatureReady = readings.filter { it.valid && it.type in setOf("food", "food_probe") && (s.probeName.isBlank() || it.probeName == s.probeName) && (dishId == null || it.dishId == dishId) && now - it.at in 0..330_000 }.maxByOrNull { it.at }?.fahrenheit?.let { it >= (s.temperatureF ?: Double.MAX_VALUE) } == true
        val primaryReady = when (s.trigger) { "manual" -> true; "temperature" -> if (lastActual != null && s.repeatMinutes != null) due != null && now >= due else temperatureReady; else -> due != null && now >= due }
        val ready = !plan.paused && p.status == "pending" && eligibleStage && primaryReady && (p.snoozedUntil == null || now >= p.snoozedUntil)
        EvaluatedStep(s, p, dishId, p.snoozedUntil?.let { snooze -> maxOf(snooze, due ?: snooze) } ?: due, ready,
            if (!eligibleStage) "Waiting for ${s.stage}" else if (anchorTime == null && s.trigger in setOf("elapsed", "after_action")) "Waiting for ${PlaybookCodec.actions[anchor]}" else if (s.trigger == "temperature" && !temperatureReady && !(lastActual != null && s.repeatMinutes != null)) "Waiting for a fresh temperature" else null)
    }
    fun satisfy(plan: CookPlan, step: PlaybookStep, event: PlanEvent): CookPlan {
        val p = plan.progress[step.id] ?: StepProgress()
        if (p.status != "pending" || event.id in p.eventIds) return plan
        return plan.copy(progress = plan.progress + (step.id to p.copy(status = if (step.repeatMinutes == null) "done" else "pending", eventIds = p.eventIds + event.id,
            lastDoneAt = event.at, snoozedUntil = null, occurrence = p.occurrence + 1)))
    }
}
