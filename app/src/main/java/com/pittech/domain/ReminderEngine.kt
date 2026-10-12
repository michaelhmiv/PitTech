package com.pittech.domain

import org.json.JSONObject

sealed interface ReminderTrigger {
    data class Duration(val startedAt: Long, val delayMillis: Long) : ReminderTrigger
    data class Clock(val at: Long) : ReminderTrigger
    data class Action(val action: String, val offsetMillis: Long, val eventId: String? = null) : ReminderTrigger
    data class Historical(val anchorAction: String, val offsetMillis: Long) : ReminderTrigger
    data class Probe(val seriesId: String, val probeId: String?, val measurementType: String, val direction: String,
        val thresholdF: Double, val sustainMillis: Long = 0, val repeatMillis: Long? = null, val hysteresisF: Double = 3.0) : ReminderTrigger
}
data class ReminderState(val active: Boolean = false, val badSince: Long? = null, val lastSampleAt: Long? = null,
    val lastDeliveredAt: Long? = null, val snoozedUntil: Long? = null, val completed: Boolean = false)
data class ReminderProbeSample(val seriesId: String, val probeId: String?, val measurementType: String, val sample: AlertSample, val assignmentValid: Boolean, val continuous: Boolean)
data class ReminderEvaluation(val state: ReminderState, val dueAt: Long?, val ready: Boolean, val detail: String)

object ReminderTriggerCodec {
    private val food = setOf("food", "food_probe", "internal_temperature")
    private val pit = setOf("pit", "pit_ambient", "ambient_temperature")
    fun kind(trigger: ReminderTrigger) = when (trigger) { is ReminderTrigger.Duration -> "duration"; is ReminderTrigger.Clock -> "clock"; is ReminderTrigger.Action -> "action"; is ReminderTrigger.Historical -> "historical"; is ReminderTrigger.Probe -> "probe" }
    fun encode(trigger: ReminderTrigger): String {
        val o = JSONObject().put("version", 1).put("type", kind(trigger))
        when (trigger) {
            is ReminderTrigger.Duration -> { require(trigger.startedAt > 0 && trigger.delayMillis in 1..2_592_000_000 && trigger.startedAt <= Long.MAX_VALUE - trigger.delayMillis); o.put("startedAt", trigger.startedAt).put("delayMillis", trigger.delayMillis) }
            is ReminderTrigger.Clock -> { require(trigger.at > 0); o.put("at", trigger.at) }
            is ReminderTrigger.Action -> { require(trigger.action in CookNoteMetadata.actions && trigger.offsetMillis in -2_592_000_000..2_592_000_000); o.put("action", trigger.action).put("offsetMillis", trigger.offsetMillis).put("eventId", trigger.eventId) }
            is ReminderTrigger.Historical -> { require(trigger.anchorAction in CookPhases.phaseActions && trigger.offsetMillis in -2_592_000_000..2_592_000_000); o.put("anchorAction", trigger.anchorAction).put("offsetMillis", trigger.offsetMillis) }
            is ReminderTrigger.Probe -> {
                require(trigger.seriesId.isNotBlank() && trigger.measurementType in food + pit && trigger.direction in setOf("above", "below") && trigger.thresholdF.isFinite() && trigger.thresholdF in -100.0..1000.0 && trigger.sustainMillis in 0..3_600_000 && (trigger.repeatMillis == null || trigger.repeatMillis >= 300_000) && trigger.hysteresisF in 0.5..50.0)
                o.put("seriesId", trigger.seriesId).put("probeId", trigger.probeId).put("measurementType", trigger.measurementType).put("direction", trigger.direction).put("thresholdF", trigger.thresholdF).put("sustainMillis", trigger.sustainMillis).put("repeatMillis", trigger.repeatMillis).put("hysteresisF", trigger.hysteresisF)
            }
        }
        return o.toString()
    }
    fun decode(raw: String): ReminderTrigger {
        val o = JSONObject(raw); require(o.getInt("version") == 1)
        fun JSONObject.integer(key: String): Long {
            val value = get(key); require(value is Number)
            val number = value.toDouble(); require(number.isFinite() && number == kotlin.math.floor(number) && number >= Long.MIN_VALUE.toDouble() && number < Long.MAX_VALUE.toDouble())
            return getLong(key)
        }
        val trigger = when (o.getString("type")) {
            "duration" -> ReminderTrigger.Duration(o.integer("startedAt"), o.integer("delayMillis"))
            "clock" -> ReminderTrigger.Clock(o.integer("at"))
            "action" -> ReminderTrigger.Action(o.getString("action"), o.integer("offsetMillis"), if (o.isNull("eventId")) null else o.getString("eventId"))
            "historical" -> ReminderTrigger.Historical(o.getString("anchorAction"), o.integer("offsetMillis"))
            "probe" -> ReminderTrigger.Probe(o.getString("seriesId"), if (o.isNull("probeId")) null else o.getString("probeId"), o.getString("measurementType"), o.getString("direction"), o.getDouble("thresholdF"), o.optLong("sustainMillis"), if (o.isNull("repeatMillis")) null else o.integer("repeatMillis"), o.optDouble("hysteresisF", 3.0))
            else -> error("Unknown reminder type")
        }
        encode(trigger); return trigger
    }
}

/** Evaluates accepted observations; it never polls controllers or records physical actions. */
object ReminderEngine {
    fun evaluate(trigger: ReminderTrigger, previous: ReminderState, events: List<PlanEvent>, dishId: String?, now: Long,
        sample: ReminderProbeSample? = null, enabled: Boolean = true): ReminderEvaluation {
        if (!enabled || previous.completed) return ReminderEvaluation(previous, null, false, "Inactive")
        if (trigger !is ReminderTrigger.Probe) {
            val anchor = when (trigger) {
                is ReminderTrigger.Duration -> trigger.startedAt + trigger.delayMillis
                is ReminderTrigger.Clock -> trigger.at
                is ReminderTrigger.Action -> events.filter { (it.dishId == dishId || it.dishId == null) && it.action == trigger.action && (trigger.eventId == null || it.id == trigger.eventId) }.minOfOrNull { it.at }?.plus(trigger.offsetMillis)
                is ReminderTrigger.Historical -> events.filter { (it.dishId == dishId || it.dishId == null) && it.action == trigger.anchorAction }.minOfOrNull { it.at }?.plus(trigger.offsetMillis)
                else -> null
            }
            if (anchor == null) return ReminderEvaluation(previous, null, false, "Waiting for the selected actual action")
            val due = previous.snoozedUntil ?: anchor
            return ReminderEvaluation(previous, due, due <= now && previous.lastDeliveredAt == null, if (due <= now) "Due · check your food" else "Scheduled")
        }
        val s = sample?.sample
        val valid = sample != null && s != null && s.valid && s.ageKnown && sample.assignmentValid && sample.seriesId == trigger.seriesId && sample.probeId == trigger.probeId && sample.measurementType == trigger.measurementType && s.fahrenheit.isFinite() && now - s.at in 0..s.freshnessMillis && now - s.receiptAt in 0..s.freshnessMillis
        if (!valid) return ReminderEvaluation(previous.copy(active = false, badSince = null), null, false, "Waiting for a fresh reading from the selected channel")
        val value = s!!.fahrenheit
        val crossed = if (trigger.direction == "above") value >= trigger.thresholdF else value <= trigger.thresholdF
        val recovered = if (trigger.direction == "above") value < trigger.thresholdF - trigger.hysteresisF else value > trigger.thresholdF + trigger.hysteresisF
        if (recovered) return ReminderEvaluation(previous.copy(active = false, badSince = null, lastSampleAt = s.at), null, false, "Monitoring selected channel")
        if (!crossed) return ReminderEvaluation(previous.copy(lastSampleAt = s.at), null, false, "Monitoring selected channel")
        if (!sample!!.continuous && trigger.sustainMillis > 0) return ReminderEvaluation(previous.copy(active = false, badSince = null, lastSampleAt = s.at), null, false, "Sustained monitoring unavailable · readings are collected on logging")
        val since = previous.badSince?.takeIf { previous.lastSampleAt != null && s.at - previous.lastSampleAt <= s.freshnessMillis } ?: s.at
        val state = previous.copy(active = true, badSince = since, lastSampleAt = s.at)
        val due = maxOf(since + trigger.sustainMillis, previous.snoozedUntil ?: 0, previous.lastDeliveredAt?.let { at -> trigger.repeatMillis?.let { at + it } ?: Long.MAX_VALUE } ?: 0)
        // Only a distinct accepted sample may trigger again. A repeated wake cannot manufacture telemetry.
        val ready = due <= now && s.at >= since + trigger.sustainMillis && (previous.lastDeliveredAt == null || s.at > previous.lastDeliveredAt)
        return ReminderEvaluation(state, if (sample.continuous && due > now && due != Long.MAX_VALUE) due else null, ready, if (ready) "Selected ${trigger.measurementType.replace('_', ' ')} crossed the threshold · check your food" else if (!sample.continuous) "Evaluated when a reading is logged" else "Waiting for sustained threshold")
    }
}
