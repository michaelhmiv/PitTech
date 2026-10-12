package com.pittech.data

import com.pittech.domain.CookingMethods
import org.json.*

object ActivityTimerArchive {
    fun write(root: JSONObject, s: ExportSnapshot) {
        root.put("activityTimers", JSONArray(s.activityTimers.map { t -> JSONObject().put("id", t.id).put("cookId", t.cookId).put("dishId", t.dishId)
            .put("title", t.title).put("method", t.method).put("startedAtUtcMillis", t.startedAtUtcMillis).put("stoppedAtUtcMillis", t.stoppedAtUtcMillis)
            .put("desiredDurationMillis", t.desiredDurationMillis).put("noteId", t.noteId).put("status", t.status) }))
    }
    fun read(root: JSONObject, base: ExportSnapshot): ExportSnapshot {
        val timers = root.optJSONArray("activityTimers")?.let { a -> (0 until a.length()).map { i -> val o = a.getJSONObject(i)
            ActivityTimer(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.getString("title"), o.text("method"), o.getLong("startedAtUtcMillis"),
                o.long("stoppedAtUtcMillis"), o.long("desiredDurationMillis"), o.getString("noteId"), o.getString("status")) } }.orEmpty()
        val snapshot = base.copy(activityTimers = timers); validate(snapshot); return snapshot
    }
    fun validate(s: ExportSnapshot) {
        require(s.activityTimers.map { it.id }.distinct().size == s.activityTimers.size && s.activityTimers.map { it.noteId }.distinct().size == s.activityTimers.size)
        require(s.activityTimers.all { t -> s.cooks.any { it.id == t.cookId } && (t.dishId == null || s.dishes.any { it.id == t.dishId && it.cookId == t.cookId }) &&
            t.title.isNotBlank() && (t.method == null || t.method in CookingMethods.labels) && t.startedAtUtcMillis > 0 &&
            (t.stoppedAtUtcMillis == null || t.stoppedAtUtcMillis >= t.startedAtUtcMillis) &&
            (t.desiredDurationMillis == null || t.desiredDurationMillis in 1..2_592_000_000L) && t.status in setOf("running", "stopped", "logged", "cancelled", "inactive") &&
            s.events.none { it.id == t.noteId && it.cookId != t.cookId } }) { "Invalid timer ownership or timestamps." }
    }
    private fun JSONObject.text(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.long(k: String) = if (isNull(k)) null else getLong(k)
}
