package com.pittech.data

import com.pittech.domain.ReminderTriggerCodec
import org.json.*

object ReminderArchive {
    fun write(root: JSONObject, s: ExportSnapshot) {
        root.put("reminderRules", JSONArray(s.reminderRules.map { r -> JSONObject().put("id", r.id).put("cookId", r.cookId).put("dishId", r.dishId).put("title", r.title).put("kind", r.kind).put("triggerConfig", r.triggerConfig).put("sourceRecipeRevisionId", r.sourceRecipeRevisionId).put("sourceMilestoneId", r.sourceMilestoneId).put("enabled", r.enabled).put("status", r.status).put("createdAtUtcMillis", r.createdAtUtcMillis).put("updatedAtUtcMillis", r.updatedAtUtcMillis).put("schemaVersion", r.schemaVersion) }))
        root.put("reminderOccurrences", JSONArray(s.reminderOccurrences.map { o -> JSONObject().put("id", o.id).put("ruleId", o.ruleId).put("sequence", o.sequence).put("status", o.status).put("dueAtUtcMillis", o.dueAtUtcMillis).put("deliveredAtUtcMillis", o.deliveredAtUtcMillis).put("acknowledgedAtUtcMillis", o.acknowledgedAtUtcMillis).put("snoozedUntilUtcMillis", o.snoozedUntilUtcMillis).put("badSinceUtcMillis", o.badSinceUtcMillis).put("lastSampleAtUtcMillis", o.lastSampleAtUtcMillis).put("active", o.active).put("actionEventId", o.actionEventId) }))
    }
    fun read(root: JSONObject, base: ExportSnapshot): ExportSnapshot {
        fun rows(k: String) = root.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val rules = rows("reminderRules").map { o -> ReminderRule(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.getString("title"), o.getString("kind"), o.getString("triggerConfig"), o.text("sourceRecipeRevisionId"), o.text("sourceMilestoneId"), o.getBoolean("enabled"), o.getString("status"), o.getLong("createdAtUtcMillis"), o.getLong("updatedAtUtcMillis"), o.getInt("schemaVersion")) }
        val occurrences = rows("reminderOccurrences").map { o -> ReminderOccurrence(o.getString("id"), o.getString("ruleId"), o.getInt("sequence"), o.getString("status"), o.long("dueAtUtcMillis"), o.long("deliveredAtUtcMillis"), o.long("acknowledgedAtUtcMillis"), o.long("snoozedUntilUtcMillis"), o.long("badSinceUtcMillis"), o.long("lastSampleAtUtcMillis"), o.getBoolean("active"), o.text("actionEventId")) }
        return base.copy(reminderRules = rules, reminderOccurrences = occurrences).also(::validate)
    }
    fun validate(s: ExportSnapshot) {
        require(s.reminderRules.map { it.id }.distinct().size == s.reminderRules.size && s.reminderOccurrences.map { it.id }.distinct().size == s.reminderOccurrences.size && s.reminderOccurrences.map { it.ruleId to it.sequence }.distinct().size == s.reminderOccurrences.size)
        require(s.reminderRules.all { r -> r.schemaVersion == 1 && r.title.isNotBlank() && r.status in setOf("pending", "scheduled", "waiting", "due", "inactive", "completed", "cancelled") && r.createdAtUtcMillis > 0 && r.updatedAtUtcMillis >= r.createdAtUtcMillis && s.cooks.any { it.id == r.cookId } && (r.dishId == null || s.dishes.any { it.id == r.dishId && it.cookId == r.cookId }) && ReminderTriggerCodec.kind(ReminderTriggerCodec.decode(r.triggerConfig)) == r.kind }) { "Invalid reminder ownership or trigger." }
        require(s.reminderOccurrences.all { o -> val rule = s.reminderRules.firstOrNull { it.id == o.ruleId }; rule != null && o.sequence >= 0 && o.status in setOf("pending", "scheduled", "waiting", "due", "inactive", "completed", "cancelled", "queued", "delivered", "snoozed", "skipped", "acknowledged") && listOfNotNull(o.dueAtUtcMillis, o.deliveredAtUtcMillis, o.acknowledgedAtUtcMillis, o.snoozedUntilUtcMillis, o.badSinceUtcMillis, o.lastSampleAtUtcMillis).all { it > 0 } && (o.actionEventId == null || s.events.any { it.id == o.actionEventId && it.cookId == rule.cookId }) }) { "Invalid reminder delivery history." }
    }
    private fun JSONObject.text(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.long(k: String) = if (isNull(k)) null else getLong(k)
}
