package com.pittech.domain

import java.time.*
import org.json.JSONArray
import org.json.JSONObject

data class DishSchedule(val dishIndex: Int, val cookMinMinutes: Long = 480, val cookMaxMinutes: Long = 600,
    val prepMinutes: Long = 30, val preheatMinutes: Long = 30, val restMinutes: Long = 60, val holdMinutes: Long = 0,
    val pitF: Double? = null, val method: String = "", val evidenceCount: Int = 0, val evidenceNote: String = "Your duration range")
data class ServePlan(val book: CookPlaybook, val serveAt: Long, val zoneId: String, val bufferMinutes: Long = 60,
    val schedules: List<DishSchedule>, val startedCookId: String? = null)
data class PlannedWindow(val dishIndex: Int, val prepAt: Long, val preheatAt: Long, val foodOnAt: Long,
    val removeEarliest: Long, val removeLatest: Long, val readyEarliest: Long, val readyLatest: Long)
data class DurationEvidence(val cut: String?, val foodType: String, val smoker: String?, val weightKg: Double?,
    val startingCondition: String?, val boneIn: Boolean?, val setpointF: Double?, val durationMinutes: Long,
    val cookId: String, val wrapped: Boolean)

object ServeTimePlanner {
    fun validate(plan: ServePlan) {
        PlaybookCodec.validate(plan.book); ZoneId.of(plan.zoneId)
        require(plan.bufferMinutes in 0..1440 && plan.schedules.map { it.dishIndex }.sorted() == plan.book.draft.dishes.indices.toList()) { "Schedule each dish once and use a valid buffer." }
        plan.schedules.forEach { s -> require(s.cookMinMinutes > 0 && s.cookMaxMinutes in s.cookMinMinutes..100_800 && listOf(s.prepMinutes, s.preheatMinutes, s.restMinutes, s.holdMinutes).all { it in 0..10_080 } && (s.pitF == null || s.pitF.isFinite())) { "Check each dish's cooking and preparation durations." } }
    }
    fun localGoal(date: String, time: String, zoneId: String, laterOffset: Boolean = false): Long {
        val local = LocalDate.parse(date).atTime(LocalTime.parse(time)); val zone = ZoneId.of(zoneId)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "This local time is skipped by daylight saving. Choose another time." }
        return local.toInstant(if (laterOffset) offsets.last() else offsets.first()).toEpochMilli()
    }
    fun windows(plan: ServePlan): List<PlannedWindow> {
        validate(plan)
        return plan.schedules.map { s ->
            val latest = plan.serveAt - (s.restMinutes + s.holdMinutes + plan.bufferMinutes) * 60_000
            val foodOn = latest - s.cookMaxMinutes * 60_000
            val earliest = foodOn + s.cookMinMinutes * 60_000
            PlannedWindow(s.dishIndex, foodOn - (s.prepMinutes + s.preheatMinutes) * 60_000, foodOn - s.preheatMinutes * 60_000, foodOn, earliest, latest, earliest + s.restMinutes * 60_000, latest + s.restMinutes * 60_000)
        }
    }
    fun conflicts(plan: ServePlan): List<Pair<Int, Int>> {
        val windows = windows(plan).associateBy { it.dishIndex }
        return plan.schedules.flatMapIndexed { index, a -> plan.schedules.drop(index + 1).mapNotNull { b ->
            val wa = windows.getValue(a.dishIndex); val wb = windows.getValue(b.dishIndex)
            if (a.method.trim().equals(b.method.trim(), true) && a.pitF != null && b.pitF != null && kotlin.math.abs(a.pitF - b.pitF) > 10.0 && wa.foodOnAt < wb.removeLatest && wb.foodOnAt < wa.removeLatest) a.dishIndex to b.dishIndex else null
        } }
    }
    fun comparable(dish: DishDraft, draft: NewCookDraft, evidence: List<DurationEvidence>, wrapped: Boolean): List<DurationEvidence> {
        val weight = weightKg(dish.weightText.toDoubleOrNull(), dish.weightUnit)
        val pit = draft.setpointText.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, draft.setpointUnit) }
        return evidence.filter { e ->
            !dish.cut.isBlank() && e.cut?.equals(dish.cut.trim(), true) == true && e.foodType.equals(dish.foodType, true) &&
                e.smoker?.equals(draft.smokerName.trim(), true) == true && e.wrapped == wrapped && e.startingCondition == dish.startingCondition && e.boneIn == dish.boneIn &&
                (weight == null && e.weightKg == null || weight != null && e.weightKg != null && kotlin.math.abs(weight - e.weightKg) <= weight * 0.25) &&
                (pit == null && e.setpointF == null || pit != null && e.setpointF != null && kotlin.math.abs(pit - e.setpointF) <= 25)
        }.distinctBy { it.cookId }
    }
    fun weightKg(value: Double?, unit: String): Double? = value?.let { if (unit == "lb") it * 0.45359237 else it }
    fun updatedReadyWindow(plan: ServePlan, schedule: DishSchedule, events: List<PlanEvent>, dishId: String): Pair<Long, Long> {
        val scoped = events.filter { it.dishId == dishId || it.dishId == null }
        val remove = scoped.filter { it.action == "remove" }.maxOfOrNull { it.at }
        if (remove != null) return (remove + schedule.restMinutes * 60_000) to (remove + schedule.restMinutes * 60_000)
        val on = scoped.filter { it.action == "food_on" }.maxOfOrNull { it.at } ?: windows(plan).first { it.dishIndex == schedule.dishIndex }.foodOnAt
        return (on + (schedule.cookMinMinutes + schedule.restMinutes) * 60_000) to (on + (schedule.cookMaxMinutes + schedule.restMinutes) * 60_000)
    }
    fun encode(plan: ServePlan): String { validate(plan); return JSONObject().put("version", 1).put("book", JSONObject(PlaybookCodec.encode(plan.book))).put("serveAt", plan.serveAt).put("zoneId", plan.zoneId).put("buffer", plan.bufferMinutes).put("startedCookId", plan.startedCookId).put("schedules", JSONArray(plan.schedules.map { s -> JSONObject().put("dish", s.dishIndex).put("min", s.cookMinMinutes).put("max", s.cookMaxMinutes).put("prep", s.prepMinutes).put("preheat", s.preheatMinutes).put("rest", s.restMinutes).put("hold", s.holdMinutes).put("pitF", s.pitF).put("method", s.method).put("count", s.evidenceCount).put("note", s.evidenceNote) })).toString() }
    fun decode(raw: String): ServePlan {
        val o = JSONObject(raw); require(o.getInt("version") == 1)
        return ServePlan(PlaybookCodec.decode(o.getJSONObject("book").toString()), o.getLong("serveAt"), o.getString("zoneId"), o.optLong("buffer", 60), PlaybookCodec.objects(o, "schedules").map { s -> DishSchedule(s.getInt("dish"), s.getLong("min"), s.getLong("max"), s.optLong("prep", 30), s.optLong("preheat", 30), s.optLong("rest", 60), s.optLong("hold"), if (s.isNull("pitF")) null else s.getDouble("pitF"), s.optString("method"), s.optInt("count"), s.optString("note", "Your duration range")) }, if (o.isNull("startedCookId")) null else o.getString("startedCookId")).also(::validate)
    }
}
