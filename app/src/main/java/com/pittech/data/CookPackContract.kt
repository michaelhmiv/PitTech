package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object CookPackContract {
    const val MAX_COMPRESSED = 64L * 1024 * 1024
    const val MAX_EXPANDED = 64L * 1024 * 1024
    const val MAX_ENTRY = 16 * 1024 * 1024
    const val MAX_ENTRIES = 1000
    private val keys = setOf("contractVersion", "id", "kind", "recipeRevisionIds", "dishIds", "includedStory", "source")
    private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    private fun ids(array: JSONArray) = (0 until array.length()).map { array.getString(it) }.also { require(it.size <= 20 && it.distinct().size == it.size && it.all(::uuid)) { "Invalid Cook Pack scope." } }.toSet()
    fun validate(metadata: JSONObject, root: JSONObject, snapshot: ExportSnapshot) {
        require(metadata.keys().asSequence().toSet() == keys && metadata.getInt("contractVersion") == 1 && uuid(metadata.getString("id"))) { "Unsupported Cook Pack." }
        val kind = metadata.getString("kind")
        require(kind in setOf("recipe", "collection", "cook")) { "Unsupported Cook Pack content." }
        require(ids(metadata.getJSONArray("recipeRevisionIds")) == snapshot.recipeRevisions.map { it.id }.toSet() && ids(metadata.getJSONArray("dishIds")) == snapshot.dishes.map { it.id }.toSet()) { "This Cook Pack does not match its declared scope." }
        metadata.getBoolean("includedStory")
        require(root.isNull("preferences") && snapshot.devices.isEmpty() && snapshot.probes.isEmpty() && snapshot.recordings.isEmpty() && snapshot.assignments.isEmpty() && snapshot.reminders.isEmpty() && snapshot.activityTimers.isEmpty() && snapshot.reminderRules.isEmpty() && snapshot.reminderOccurrences.isEmpty()) { "Private settings and device configuration do not belong in a Cook Pack." }
        require(snapshot.recipes.size in 1..20 && (kind != "recipe" || snapshot.recipes.size == 1) && (kind == "cook" || snapshot.cooks.isEmpty())) { "Invalid Cook Pack selection." }
        require(snapshot.companionRecords.all { it.kind in setOf("recipe_reference", "collection_revision") && it.cookId == null }) { "This Cook Pack includes unrelated saved content." }
        if (!metadata.isNull("source")) {
            val source = metadata.getJSONObject("source")
            require(source.keys().asSequence().all { it in setOf("itemId", "revisionId", "displayName", "removed") } && uuid(source.getString("itemId")) && uuid(source.getString("revisionId")) && source.optString("displayName").length <= 160) { "Invalid source credit." }
            source.getBoolean("removed")
        }
        rejectPrivateFields(root)
    }
    private fun rejectPrivateFields(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                require(value.isNull(key) || key !in setOf("credentials", "credential", "password", "accessToken", "refreshToken", "secret", "apiKey", "sourceDeviceId", "deviceId", "controllerKey", "macAddress", "serialNumber")) { "Private device information cannot be shared in a Cook Pack." }
                val child = value.opt(key)
                if (key.endsWith("Json") && child is String) {
                    val raw = child.trim()
                    val nested = runCatching { if (raw.startsWith("{")) JSONObject(raw) else if (raw.startsWith("[")) JSONArray(raw) else null }.getOrNull()
                    rejectPrivateFields(nested)
                } else rejectPrivateFields(child)
            }
            is JSONArray -> (0 until value.length()).forEach { rejectPrivateFields(value.opt(it)) }
        }
    }
}
