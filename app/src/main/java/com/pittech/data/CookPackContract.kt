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
        require(root.isNull("preferences") && snapshot.devices.isEmpty() && snapshot.probes.all { it.deviceId == null && uuid(it.id) } && snapshot.recordings.isEmpty() && snapshot.assignments.isEmpty() && snapshot.reminders.isEmpty() && snapshot.activityTimers.isEmpty() && snapshot.reminderRules.isEmpty() && snapshot.reminderOccurrences.isEmpty()) { "Private settings and device configuration do not belong in a Cook Pack." }
        require(snapshot.recipes.size in (if (kind == "cook") 0..20 else 1..20) && (kind != "recipe" || snapshot.recipes.size == 1) && (kind == "cook" || snapshot.cooks.isEmpty())) { "Invalid Cook Pack selection." }
        if (kind == "cook") {
            require(snapshot.cooks.size == 1 && snapshot.dishes.size in 1..20 && snapshot.cooks.all { it.status == CookStatus.COMPLETED && it.endedAtUtcMillis != null && it.endedAtUtcMillis >= it.startedAtUtcMillis } && metadata.getBoolean("includedStory")) { "A recorded Cook Pack must contain a finished meal with its actual times." }
        } else {
            require(listOf(snapshot.cooks, snapshot.dishes, snapshot.ingredients, snapshot.events, snapshot.readings, snapshot.targets, snapshot.results, snapshot.probes, snapshot.photos, snapshot.logReadingLinks, snapshot.lifecycles, snapshot.targetHistory).all { it.isEmpty() }) { "A recipe or collection must not import recorded history." }
        }
        val documents = snapshot.companionRecords.map { com.pittech.domain.SharingDraftCodec.decode(it.payload) }
        require(metadata.getBoolean("includedStory") == (kind == "cook" || documents.any { doc -> doc.entries.any { it.includeStory } })) { "The declared guide does not match this file." }
        require(documents.all { doc -> doc.sourceCookId == null && doc.sourceHistoryJson == null && doc.entries.all { entry ->
            entry.sourceDishId == null && (entry.includeStory || JSONObject(entry.storyJson).let { story ->
                story.isNull("attemptId") && story.isNull("anchorUtcMillis") && (story.optJSONArray("connectionGaps")?.length() ?: 0) == 0 && listOf("observations", "readings", "results").all { story.getJSONArray(it).length() == 0 } && entry.snapshot.rating == null && entry.snapshot.cookAgain == null && entry.snapshot.resultNotes.isEmpty()
            })
        } }) { "This file includes undeclared source facts." }
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
