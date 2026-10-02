package com.pittech.domain

import org.json.JSONObject
import java.util.UUID

data class CookAlertRule(val id: String = UUID.randomUUID().toString(), val type: String = "target", val dishId: String? = null,
    val probeName: String = "", val targetF: Double? = null, val lowF: Double? = null, val highF: Double? = null,
    val dwellMillis: Long = 120_000, val missingMillis: Long = 180_000, val recoveryF: Double = 5.0,
    val repeatMillis: Long = 900_000, val enabled: Boolean = true, val stage: String = "cooking")
data class CookAlertState(val active: Boolean = false, val badSince: Long? = null, val lastSampleAt: Long? = null,
    val lastNotifiedAt: Long? = null, val snoozedUntil: Long? = null, val suppressedUntil: Long? = null, val acknowledged: Boolean = false, val notifiedCount: Int = 0)
data class AlertSample(val fahrenheit: Double, val at: Long, val receiptAt: Long, val valid: Boolean, val freshnessMillis: Long = 180_000, val ageKnown: Boolean = true)
data class AlertEvaluation(val state: CookAlertState, val notify: Boolean, val nextWake: Long?, val detail: String)

object CookAlertEngine {
    val types = linkedMapOf("target" to "Food target", "pit_range" to "Pit range", "missing" to "Missing readings", "offline" to "Reported offline")
    fun validate(rule: CookAlertRule) {
        require(rule.type in types && rule.stage in setOf("cooking", "resting", "holding")) { "Choose an alert type and stage." }
        require(rule.dwellMillis in 0..3_600_000 && rule.missingMillis in 60_000..3_600_000 && rule.repeatMillis >= 300_000 && rule.recoveryF in 0.0..50.0) { "Choose valid alert intervals." }
        require(rule.type != "target" || rule.targetF?.let { it.isFinite() && it in 32.0..600.0 } == true) { "Enter a food target." }
        require(rule.type != "pit_range" || (rule.lowF?.isFinite() == true && rule.highF?.isFinite() == true && rule.lowF < rule.highF)) { "Low temperature must be below high temperature." }
    }
    fun encode(rule: CookAlertRule, state: CookAlertState = CookAlertState()): String {
        validate(rule)
        return JSONObject().put("version", 1).put("id", rule.id).put("type", rule.type).put("dishId", rule.dishId).put("probeName", rule.probeName).put("targetF", rule.targetF).put("lowF", rule.lowF).put("highF", rule.highF).put("dwell", rule.dwellMillis).put("missing", rule.missingMillis).put("recoveryF", rule.recoveryF).put("repeat", rule.repeatMillis).put("enabled", rule.enabled).put("stage", rule.stage)
            .put("state", JSONObject().put("active", state.active).put("badSince", state.badSince).put("lastSampleAt", state.lastSampleAt).put("lastNotifiedAt", state.lastNotifiedAt).put("snoozedUntil", state.snoozedUntil).put("suppressedUntil", state.suppressedUntil).put("acknowledged", state.acknowledged).put("notifiedCount", state.notifiedCount)).toString()
    }
    fun decode(raw: String): Pair<CookAlertRule, CookAlertState> {
        val o = JSONObject(raw); require(o.getInt("version") == 1)
        fun JSONObject.nLong(k: String) = if (isNull(k)) null else getLong(k)
        fun JSONObject.nDouble(k: String) = if (isNull(k)) null else getDouble(k)
        val rule = CookAlertRule(o.getString("id"), o.getString("type"), if (o.isNull("dishId")) null else o.getString("dishId"), o.optString("probeName"), o.nDouble("targetF"), o.nDouble("lowF"), o.nDouble("highF"), o.optLong("dwell", 120_000), o.optLong("missing", 180_000), o.optDouble("recoveryF", 5.0), o.optLong("repeat", 900_000), o.optBoolean("enabled", true), o.optString("stage", "cooking"))
        validate(rule)
        val s = o.optJSONObject("state") ?: JSONObject()
        return rule to CookAlertState(s.optBoolean("active"), s.nLong("badSince"), s.nLong("lastSampleAt"), s.nLong("lastNotifiedAt"), s.nLong("snoozedUntil"), s.nLong("suppressedUntil"), s.optBoolean("acknowledged"), s.optInt("notifiedCount"))
    }
    fun evaluate(rule: CookAlertRule, previous: CookAlertState, sample: AlertSample?, lastReceipt: Long?, reportedOffline: Boolean?, stage: String, now: Long, running: Boolean = true): AlertEvaluation {
        if (!rule.enabled || !running || (rule.type == "target" && stage != rule.stage)) return AlertEvaluation(CookAlertState(), false, null, "Inactive for this stage")
        if (rule.type == "pit_range" && previous.suppressedUntil?.let { now < it } == true) return AlertEvaluation(previous.copy(badSince = null, lastSampleAt = null), false, previous.suppressedUntil, "Lid-open suppression")
        val fresh = sample?.let { it.valid && it.fahrenheit.isFinite() && now - it.receiptAt in 0..it.freshnessMillis && (it.ageKnown.not() || now - it.at in 0..it.freshnessMillis) } == true
        if (rule.type in setOf("target", "pit_range") && !fresh) return AlertEvaluation(previous.copy(badSince = null, lastSampleAt = null), false, null, "Waiting for a fresh valid reading")
        val bad = when (rule.type) {
            "target" -> sample!!.fahrenheit >= rule.targetF!! - if (previous.active) rule.recoveryF else 0.0
            "pit_range" -> if (previous.active) sample!!.fahrenheit < rule.lowF!! + rule.recoveryF || sample.fahrenheit > rule.highF!! - rule.recoveryF else sample!!.fahrenheit < rule.lowF!! || sample.fahrenheit > rule.highF!!
            "missing" -> lastReceipt != null && now - lastReceipt >= rule.missingMillis
            "offline" -> reportedOffline == true
            else -> false
        }
        if (!bad) return AlertEvaluation(CookAlertState(snoozedUntil = previous.snoozedUntil, suppressedUntil = previous.suppressedUntil), false,
            if (rule.type == "missing") lastReceipt?.plus(rule.missingMillis)?.takeIf { it > now } else null, "Within limits")
        // Continuity is proved only by accepted samples. A timer cannot extend a pit dwell on one old value.
        val gap = previous.lastSampleAt?.let { sample != null && sample.at - it > sample.freshnessMillis } == true
        val since = if (gap) now else previous.badSince ?: now
        val latestSampleAt = sample?.at ?: previous.lastSampleAt
        val sustained = rule.type != "pit_range" || (latestSampleAt != null && latestSampleAt - since >= rule.dwellMillis)
        val active = (previous.active && !gap) || sustained
        val repeatsDue = previous.lastNotifiedAt == null || now - previous.lastNotifiedAt >= rule.repeatMillis
        val notify = active && repeatsDue && (previous.snoozedUntil == null || now >= previous.snoozedUntil) && !previous.acknowledged && previous.notifiedCount < 3
        val state = previous.copy(active = active, badSince = since, lastSampleAt = latestSampleAt)
        val nextWake = when { previous.snoozedUntil?.let { it > now } == true -> previous.snoozedUntil; active && !previous.acknowledged && previous.notifiedCount < 3 -> (previous.lastNotifiedAt ?: now) + rule.repeatMillis; else -> null }
        return AlertEvaluation(state, notify, nextWake, when (rule.type) { "missing" -> "No accepted cloud reading for ${(now - (lastReceipt ?: now)) / 60_000} min"; "offline" -> "The controller service reports the grill offline"; else -> "${sample?.fahrenheit} °F" + if (sample?.ageKnown == false) " · cloud receipt is recent; sensor age is unknown" else "" })
    }
}
