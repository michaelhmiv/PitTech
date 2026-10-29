package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How a retained conflict is resolved. A restore replays loser/chosen content through the
 * same transactional apply as a merge; just dropping the copy still sends its explicit ID. */
internal data class ConflictResolution(val resolvedId: String, val restoreType: String? = null, val restoreId: String? = null, val restorePayload: JSONObject? = null, val restoreAssetId: String? = null, val newIdentity: Boolean = false)
/** One readable field of a conflicting version's content, for the PL-16
 * side-by-side preview. Derived from the payload only — presentation, never
 * a change to what sync stores or sends. */
internal data class ConflictPreviewField(val label: String, val value: String)
internal data class PrivateSyncConflictView(val id: String, val mineSummary: String, val theirsSummary: String, val reason: String, val mineIsWinner: Boolean, val canKeepBoth: Boolean, val recordType: String, val recordLabel: String, val mineFields: List<ConflictPreviewField>, val theirsFields: List<ConflictPreviewField>)

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
        require(PrivateSyncMerge.stable(body).toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) { "This library exceeds the 32 MB sync snapshot limit." }
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
        val type = winner.getString("recordType")
        return PrivateSyncConflictView(conflict.getString("id"), summarize(mine), summarize(theirs), reason, content(mine) == content(winner), type !in noKeepBoth,
            type, label(type), previewFields(mine), previewFields(theirs))
    }

    /** Record kinds whose identity is a link or a singleton, so two versions
     * can never coexist: `logReadingLinks` is keyed by reading (a reading
     * links to one note) and `lifecycles` by cook (a meal has one timing
     * record). Every other projected kind is keyed by its own id and can
     * keep both versions (the loser restores under a fresh identity). */
    fun keepBothUnavailableTypes(): Set<String> = noKeepBoth

    // PL-16: the content preview behind the side-by-side sheet. Fields render
    // in a curated order with human labels; reference ids and bookkeeping
    // timestamps are omitted because they carry no judging value, and the
    // payload itself is never altered.
    private val previewOrder = listOf(
        "title", "name", "status", "eventType", "kind", "details", "description", "caption",
        "value", "numericValue", "weightValue", "amountValue", "amountText", "targetType", "resultType", "textValue",
        "changeNotes", "revisionNumber", "timingMode", "method", "targetTypeNote",
        "foodType", "cut", "gradeOrSource", "thicknessNotes", "startingCondition", "boneIn", "placement", "pieceCount", "prepNotes",
        "stage", "brand", "smokerName", "fuelType", "woodOrPelletBlend", "initialSetpointValue", "outdoorTemperatureValue",
        "weatherNotes", "windNotes", "measurementType", "probeName", "scope", "explanation", "sourceReference",
        "deviceName", "role", "firmwareVersion", "mimeType", "favorite", "tagsJson",
        "occurredAtUtcMillis", "measuredAtUtcMillis", "startedAtUtcMillis", "endedAtUtcMillis", "appliedAtUtcMillis",
        "capturedAtUtcMillis", "addedAtUtcMillis", "effectiveAt", "archivedAtUtcMillis",
        "notes", "snapshotJson", "itemsJson", "payload", "triggerConfig", "noteMetadataJson", "temperatureContextJson",
    )
    private val previewLabels = mapOf(
        "title" to "Title", "name" to "Name", "status" to "Status", "eventType" to "Type", "kind" to "Kind",
        "details" to "Details", "description" to "Description", "caption" to "Caption", "value" to "Value",
        "numericValue" to "Value", "weightValue" to "Weight", "amountValue" to "Amount", "amountText" to "Amount",
        "targetType" to "Target", "resultType" to "Result", "textValue" to "Text", "changeNotes" to "Change notes",
        "revisionNumber" to "Version", "timingMode" to "Timing mode", "foodType" to "Food", "cut" to "Cut",
        "gradeOrSource" to "Grade / source", "thicknessNotes" to "Thickness", "startingCondition" to "Starting condition",
        "boneIn" to "Bone in", "placement" to "Placement", "pieceCount" to "Pieces", "prepNotes" to "Prep notes",
        "stage" to "Stage", "brand" to "Brand", "smokerName" to "Smoker", "fuelType" to "Fuel",
        "woodOrPelletBlend" to "Wood / pellets", "initialSetpointValue" to "Starting setpoint",
        "outdoorTemperatureValue" to "Outdoor temperature", "weatherNotes" to "Weather", "windNotes" to "Wind",
        "measurementType" to "Measurement", "probeName" to "Probe", "scope" to "Scope", "explanation" to "Why",
        "sourceReference" to "Source", "deviceName" to "Device name", "role" to "Role", "firmwareVersion" to "Firmware",
        "mimeType" to "File type", "favorite" to "Favorite", "tagsJson" to "Tags",
        "occurredAtUtcMillis" to "Occurred", "measuredAtUtcMillis" to "Measured", "startedAtUtcMillis" to "Started",
        "endedAtUtcMillis" to "Ended", "appliedAtUtcMillis" to "Applied", "capturedAtUtcMillis" to "Captured",
        "addedAtUtcMillis" to "Added", "effectiveAt" to "Effective", "archivedAtUtcMillis" to "Archived",
        "notes" to "Notes", "snapshotJson" to "Contents", "itemsJson" to "Items", "payload" to "Contents",
        "triggerConfig" to "Trigger", "noteMetadataJson" to "Note details", "temperatureContextJson" to "Temperature context",
    )
    private val previewSkip = setOf(
        "id", "cookId", "dishId", "recipeId", "recipeRevisionId", "parentRevisionId", "currentRevisionId",
        "sourceCookId", "sourceDishId", "sourcePhotoId", "sourceMilestoneId", "sourceRecipeRevisionId",
        "eventId", "readingId", "deviceId", "probeId", "recordId", "assetId",
        "createdAtUtcMillis", "updatedAtUtcMillis", "recordedAtUtcMillis", "created", "updated",
        "schemaVersion", "snapshotSchemaVersion", "source", "sourceDeviceId", "timeZoneId", "startedTimeZoneId", "timestampBasis",
    )
    private val previewTimestamps = setOf(
        "occurredAtUtcMillis", "measuredAtUtcMillis", "startedAtUtcMillis", "endedAtUtcMillis", "appliedAtUtcMillis",
        "capturedAtUtcMillis", "addedAtUtcMillis", "effectiveAt", "archivedAtUtcMillis",
    )
    private val previewUnitFor = mapOf(
        "value" to "unit", "numericValue" to "unit", "weightValue" to "weightUnit",
        "amountValue" to "amountUnit", "initialSetpointValue" to "initialSetpointUnit",
        "outdoorTemperatureValue" to "outdoorTemperatureUnit",
    )
    private val previewTimeFormat = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a 'UTC'", java.util.Locale.US).withZone(java.time.ZoneOffset.UTC)

    fun previewFields(row: JSONObject): List<ConflictPreviewField> {
        val type = row.getString("recordType"); val id = row.getString("recordId")
        if (row.getBoolean("deleted")) return listOf(
            ConflictPreviewField("Status", "Deleted"),
            ConflictPreviewField("Entry", label(type) + " " + shortId(id)),
        )
        val payload = row.getJSONObject("payload")
        val consumed = mutableSetOf<String>(); val fields = mutableListOf<ConflictPreviewField>()
        fun render(key: String) {
            if (key in consumed || key in previewSkip || !payload.has(key) || payload.isNull(key)) return
            val raw = payload.get(key)
            if (raw is String && raw.isBlank()) return
            consumed += key
            val unitKey = previewUnitFor[key]
            val unit = if (unitKey != null && payload.has(unitKey) && !payload.isNull(unitKey)) {
                consumed += unitKey; payload.optString(unitKey, "").trim()
            } else ""
            val value = previewValue(key, raw, unit)
            if (value.isNotBlank()) fields += ConflictPreviewField(previewLabels[key] ?: previewLabel(key), value)
        }
        for (key in previewOrder) render(key)
        val remaining = payload.keys().asSequence().toList().sorted()
        for (key in remaining) render(key)
        if (fields.isEmpty()) fields += ConflictPreviewField("Entry", label(type) + " " + shortId(id))
        return fields.take(18)
    }

    private fun previewLabel(key: String): String =
        key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    private fun previewValue(key: String, raw: Any, unit: String): String = when {
        key in previewTimestamps && raw is Number ->
            runCatching { previewTimeFormat.format(java.time.Instant.ofEpochMilli(raw.toLong())) }.getOrElse { trimNumber(raw.toDouble()) }
        raw is Boolean -> if (raw) "Yes" else "No"
        raw is Number -> {
            val base = if (key.endsWith("Millis")) trimNumber(raw.toDouble()) + " ms" else trimNumber(raw.toDouble())
            if (unit.isNotEmpty()) "$base $unit" else base
        }
        raw is String -> previewText(key, raw)
        raw is JSONArray -> previewArray(raw)
        raw is JSONObject -> previewObject(raw)
        else -> raw.toString().take(200)
    }

    private fun previewText(key: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            runCatching {
                if (trimmed.startsWith("[")) return previewArray(JSONArray(trimmed))
                return previewObject(JSONObject(trimmed))
            }
        }
        return trimmed.take(200)
    }

    private fun previewArray(array: JSONArray): String {
        val strings = (0 until array.length()).mapNotNull { array.opt(it) as? String }
        if (strings.size == array.length() && strings.isNotEmpty()) return strings.joinToString(", ").take(200)
        return if (array.length() == 1) "1 item" else "${array.length()} items"
    }

    private fun previewObject(obj: JSONObject): String {
        for (key in listOf("title", "name")) {
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty()) return value.take(200)
        }
        return if (obj.length() == 1) "1 detail" else "${obj.length()} details"
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
