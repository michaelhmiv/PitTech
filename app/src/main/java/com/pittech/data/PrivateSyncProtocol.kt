package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How a retained conflict is resolved. A restore replays loser/chosen content through the
 * same transactional apply as a merge; just dropping the copy still sends its explicit ID. */
internal data class ConflictResolution(val resolvedId: String, val restoreType: String? = null, val restoreId: String? = null, val restorePayload: JSONObject? = null, val restoreAssetId: String? = null, val newIdentity: Boolean = false)
internal data class PrivateSyncConflictView(val id: String, val mineSummary: String, val theirsSummary: String, val reason: String, val mineIsWinner: Boolean, val canKeepBoth: Boolean)

/** Pure sync-envelope helpers. No storage, clock, account or network access. */
internal object PrivateSyncProtocol {
    const val CHUNK_BYTES = 1024 * 1024
    const val MAX_PHOTO_BYTES = 16 * 1024 * 1024
    const val MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
    const val MAX_COUNTER = 9007199254740990L
    private val photoKinds = setOf("photos", "recipeAssets", "companionPhotos")
    private val noKeepBoth = setOf("logReadingLinks", "lifecycles")
    private fun keyField(type: String) = when (type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
    private fun content(row: JSONObject) = PrivateSyncMerge.stable(JSONObject().put("deleted", row.get("deleted")).put("payload", row.get("payload")).put("assetIds", row.get("assetIds")))
    private fun assets(row: JSONObject) = (0 until row.getJSONArray("assetIds").length()).map { row.getJSONArray("assetIds").getString(it) }

    /** Stamp the projection output with per-record mutation metadata. Rows whose portable
     * content still matches the acknowledged base keep their versions; every other row —
     * including explicit tombstones for base rows missing locally — advances to the
     * device's current logical counter. Phone time never enters a version. */
    fun stampLocal(projected: JSONArray, base: JSONArray, deviceId: String, counter: Long): JSONArray {
        require(publicationUuid(deviceId) && deviceId == deviceId.lowercase() && counter in 1..MAX_COUNTER)
        PrivateSyncMerge.validateRecords(base)
        val old = (0 until base.length()).associate { base.getJSONObject(it).let { row -> row.getString("recordType") + "|" + row.getString("recordId") to row } }
        val seen = mutableSetOf<String>(); val rows = JSONArray()
        for (i in 0 until projected.length()) {
            val row = projected.getJSONObject(i)
            val type = row.getString("recordType"); val id = row.getString("recordId")
            val key = "$type|$id"; require(seen.add(key))
            val candidate = JSONObject().put("recordType", type).put("recordId", id).put("deleted", false).put("payload", row.getJSONObject("payload")).put("assetIds", row.getJSONArray("assetIds"))
            val prior = old[key]
            if (prior != null && !prior.getBoolean("deleted") && content(prior) == PrivateSyncMerge.stable(JSONObject().put("deleted", false).put("payload", row.getJSONObject("payload")).put("assetIds", row.getJSONArray("assetIds")))) rows.put(JSONObject(prior.toString()))
            else rows.put(candidate.put("version", JSONObject().put("counter", counter).put("clientId", deviceId)))
        }
        for (key in old.keys.sorted()) {
            if (key in seen) continue
            val prior = old.getValue(key)
            if (prior.getBoolean("deleted")) rows.put(JSONObject(prior.toString()))
            else rows.put(JSONObject().put("recordType", prior.getString("recordType")).put("recordId", prior.getString("recordId"))
                .put("version", JSONObject().put("counter", counter).put("clientId", deviceId)).put("deleted", true).put("payload", JSONObject.NULL).put("assetIds", JSONArray()))
        }
        val sorted = (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { it.getString("recordType") + "|" + it.getString("recordId") }
        val result = JSONArray(sorted)
        PrivateSyncMerge.validateRecords(result)
        return result
    }

    /** Build the exact PUT body. Retained and freshly merged conflicts merge by ID; every
     * locally resolved conflict drops, but only IDs the server already holds are sent as
     * explicit resolutions. The body must be persisted before any network access. */
    fun putBody(baseVersion: Long, mutationId: String, deviceId: String, records: JSONArray, retained: JSONArray, fresh: JSONArray, resolved: List<String>, serverConflictIds: Set<String>): JSONObject {
        require(baseVersion in 0..MAX_COUNTER && publicationUuid(mutationId) && mutationId == mutationId.lowercase())
        require(publicationUuid(deviceId) && deviceId == deviceId.lowercase())
        PrivateSyncMerge.validateRecords(records)
        val merged = linkedMapOf<String, JSONObject>()
        for (source in listOf(retained, fresh)) for (i in 0 until source.length()) {
            val conflict = source.getJSONObject(i); merged[conflict.getString("id")] = conflict
        }
        for (id in resolved) merged.remove(id)
        val conflicts = JSONArray(merged.values.sortedBy { it.getString("id") })
        PrivateSyncMerge.validateConflicts(conflicts)
        val resolvedToSend = JSONArray(resolved.filter { it in serverConflictIds }.sorted())
        for (id in resolvedToSend.let { array -> (0 until array.length()).map { array.getString(it) } }) require(!merged.containsKey(id))
        val body = JSONObject().put("contractVersion", 1).put("baseVersion", baseVersion).put("mutationId", mutationId).put("clientId", deviceId).put("records", records).put("conflicts", conflicts)
        if (resolvedToSend.length() > 0) body.put("resolvedConflictIds", resolvedToSend)
        require(body.toString().toByteArray().size <= MAX_SNAPSHOT_BYTES) { "This library exceeds the 32 MB sync snapshot limit." }
        return body
    }

    fun parseSnapshotGet(body: JSONObject): Pair<Long, JSONObject?> {
        require(body.has("version") && body.get("version") is Number)
        val version = body.getLong("version"); require(version in 0..MAX_COUNTER)
        if (body.isNull("envelope")) return version to null
        val envelope = body.getJSONObject("envelope")
        require(envelope.getInt("contractVersion") == 1)
        PrivateSyncMerge.validateRecords(envelope.getJSONArray("records"))
        PrivateSyncMerge.validateConflicts(envelope.getJSONArray("conflicts"))
        return version to envelope
    }

    fun conflictSides(conflict: JSONObject, deviceId: String): Pair<JSONObject, JSONObject> {
        val winner = conflict.getJSONObject("winner"); val loser = conflict.getJSONObject("loser")
        val mine = if (winner.getJSONObject("version").getString("clientId") == deviceId) winner
        else if (loser.getJSONObject("version").getString("clientId") == deviceId) loser else winner
        return mine to if (mine === winner) loser else winner
    }

    /** Keep mine / Use theirs / Keep both, matching merge semantics. A choice that differs
     * from a live winner overwrites under the same identity; anything that would rewrite a
     * tombstone or an immutable row instead restores as a fresh identity. */
    fun resolve(conflict: JSONObject, choice: String, deviceId: String, freshId: String = UUID.randomUUID().toString()): ConflictResolution {
        require(choice in setOf("mine", "theirs", "both") && publicationUuid(deviceId))
        require(publicationUuid(freshId) && freshId == freshId.lowercase())
        PrivateSyncMerge.validateConflicts(JSONArray().put(conflict))
        val id = conflict.getString("id"); val reason = conflict.getString("reason")
        val winner = conflict.getJSONObject("winner"); val loser = conflict.getJSONObject("loser")
        val (mine, theirs) = conflictSides(conflict, deviceId)
        if (choice == "both") {
            require(winner.getString("recordType") !in noKeepBoth) { "This entry cannot be duplicated. Keep one version." }
            return restore(id, loser, freshId, true)
        }
        val chosen = if (choice == "mine") mine else theirs
        if (content(chosen) == content(winner)) return ConflictResolution(id)
        if (winner.getBoolean("deleted") || reason == "immutable_identity") return restore(id, chosen, freshId, true)
        return restore(id, chosen, chosen.getString("recordId"), false)
    }

    private fun restore(id: String, chosen: JSONObject, targetId: String, newIdentity: Boolean): ConflictResolution {
        val type = chosen.getString("recordType")
        val payload = JSONObject(chosen.getJSONObject("payload").toString())
        payload.put(keyField(type), targetId)
        if (type == "companionRecords") {
            // iOS readers use either timestamp convention; keep both keys equal.
            payload.put("createdAtUtcMillis", payload.getLong("created")); payload.put("updatedAtUtcMillis", payload.getLong("updated"))
        }
        val chosenAssets = assets(chosen)
        val asset = if (type in photoKinds && chosenAssets.isNotEmpty()) chosenAssets.first() else null
        return ConflictResolution(id, type, targetId, payload, asset, newIdentity)
    }

    fun conflictView(conflict: JSONObject, deviceId: String): PrivateSyncConflictView {
        val (mine, theirs) = conflictSides(conflict, deviceId)
        val winner = conflict.getJSONObject("winner")
        val reason = when (conflict.getString("reason")) {
            "delete_edit" -> "Deleted on one device"
            "immutable_identity" -> "Measured history changed"
            else -> "Edited on two devices"
        }
        return PrivateSyncConflictView(conflict.getString("id"), summarize(mine), summarize(theirs), reason, content(mine) == content(winner), winner.getString("recordType") !in noKeepBoth)
    }

    fun summarize(row: JSONObject): String {
        val type = row.getString("recordType"); val id = row.getString("recordId")
        if (row.getBoolean("deleted")) return label(type) + " " + shortId(id) + " · deleted"
        val payload = row.getJSONObject("payload")
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key -> payload.opt(key)?.takeIf { it is String } as? String }?.takeIf { it.isNotBlank() }
        val title = when (type) {
            "readings" -> (payload.optDouble("value", Double.NaN).takeIf { it.isFinite() }?.let { trimNumber(it) + " " + payload.optString("unit", "") }?.trim().orEmpty())
            "recipeRevisions" -> (text("changeNotes") ?: ("Version " + payload.optInt("revisionNumber", 0).takeIf { it > 0 }?.toString().orEmpty()).trim())
            "targets", "targetHistory" -> ((payload.optString("targetType", "") + " " + payload.optDouble("value", Double.NaN).takeIf { it.isFinite() }?.let(::trimNumber).orEmpty() + " " + payload.optString("unit", "")).trim())
            "results" -> payload.optString("resultType", "")
            "dishRecipeSources" -> "Recipe link"
            "lifecycles" -> "Meal timing"
            "logReadingLinks" -> "Note temperature"
            else -> text("title", "name", "deviceName", "caption", "changeNotes").orEmpty()
        }.ifBlank { shortId(id) }
        return if (type in setOf("readings", "targets", "targetHistory")) label(type) + " " + title else label(type) + " \"" + title.take(120) + "\""
    }

    private fun trimNumber(value: Double) = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    private fun shortId(id: String) = id.take(8)
    private fun label(type: String) = when (type) {
        "cooks" -> "Meal"; "dishes" -> "Dish"; "ingredients" -> "Ingredient"; "events" -> "Note"; "readings" -> "Reading"
        "targets" -> "Target"; "results" -> "Result"; "devices" -> "Device"; "probes" -> "Probe"; "photos" -> "Photo"
        "recipes" -> "Recipe"; "recipeRevisions" -> "Recipe version"; "recipeAssets" -> "Recipe photo"
        "companionRecords" -> "Saved document"; "companionPhotos" -> "Photo"; "mealPresets" -> "Meal preset"
        "dishRecipeSources" -> "Recipe link"; "lifecycles" -> "Meal timing"; "targetHistory" -> "Target history"
        "logReadingLinks" -> "Note temperature"; "reminderRules" -> "Reminder rule"; else -> "Entry"
    }
}
