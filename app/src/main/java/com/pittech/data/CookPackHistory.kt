package com.pittech.data

import org.json.JSONArray
import org.json.JSONObject

/** Only recorded facts enter history. This projection never reads editable recipe instructions. */
object CookPackHistory {
    private val pitRoles = setOf("pit", "pit_ambient", "setpoint", "grill_temperature", "pit_temperature", "pit_probe", "ambient_temperature")
    private val fields = mapOf(
        "cooks" to "id title status startedAtUtcMillis startedTimeZoneId endedAtUtcMillis initialSetpointValue initialSetpointUnit createdAtUtcMillis updatedAtUtcMillis fuelType woodOrPelletBlend outdoorTemperatureValue outdoorTemperatureUnit",
        "dishes" to "id cookId name foodType cut weightValue weightUnit pieceCount startingCondition boneIn placement prepNotes createdAtUtcMillis updatedAtUtcMillis gradeOrSource thicknessNotes",
        "ingredients" to "id cookId dishId stage name amountText brand amountValue amountUnit appliedAtUtcMillis notes createdAtUtcMillis",
        "events" to "id cookId dishId eventType title details occurredAtUtcMillis recordedAtUtcMillis timeZoneId source createdAtUtcMillis updatedAtUtcMillis noteMetadataJson temperatureContextJson",
        "readings" to "id cookId dishId probeId probeName measurementType value unit measuredAtUtcMillis timeZoneId source qualityStatus recordedAtUtcMillis timestampBasis samplingIntervalMillis",
        "targets" to "id cookId dishId targetType value unit scope explanation createdAtUtcMillis",
        "results" to "id cookId dishId resultType numericValue unit textValue notes recordedAtUtcMillis",
        "probes" to "id cookId assignedDishId name measurementType source createdAtUtcMillis",
        "photos" to "id cookId dishId eventId mimeType caption capturedAtUtcMillis addedAtUtcMillis originalFileName archivePath temperatureContextJson",
        "logReadingLinks" to "readingId eventId", "lifecycles" to "cookId timingMode",
        "targetHistory" to "id cookId dishId targetType value unit effectiveAt",
    ).mapValues { it.value.split(' ').toSet() }
    private val identityKeys = setOf("id", "cookId", "dishId", "probeId", "eventId", "readingId", "assignedDishId")
    fun rows(root: JSONObject, key: String): List<JSONObject> = root.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()
    private fun optional(row: JSONObject, key: String) = if (row.isNull(key)) null else row.getString(key)
    private fun metadata(row: JSONObject) = optional(row, "noteMetadataJson")?.let(::JSONObject)
    private fun dishScope(row: JSONObject): Set<String> = metadata(row)?.optJSONArray("dishIds")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    fun scope(source: JSONObject, dishIds: Set<String>, photoIds: Set<String>? = null, title: String? = null, alias: ((String) -> String)? = null): JSONObject {
        val cook = rows(source, "cooks").single()
        require(cook.getString("status") == CookStatus.COMPLETED && !cook.isNull("endedAtUtcMillis") && cook.getLong("startedAtUtcMillis") > 0 && cook.getLong("endedAtUtcMillis") >= cook.getLong("startedAtUtcMillis")) { "Only a draft captured from a finished meal can include recorded history." }
        val available = rows(source, "dishes").map { it.getString("id") }.toSet()
        require(dishIds.isNotEmpty() && available.containsAll(dishIds)) { "Recorded history is available only for the original dishes in this draft." }
        val readings = rows(source, "readings").filter { optional(it, "dishId") in dishIds || it.isNull("dishId") && it.optString("measurementType") in pitRoles }
        val events = rows(source, "events").filter { row ->
            optional(row, "dishId") in dishIds || row.isNull("dishId") && (dishScope(row).isEmpty() || dishScope(row).any(dishIds::contains))
        }
        val readingIds = readings.map { it.getString("id") }.toSet(); val eventIds = events.map { it.getString("id") }.toSet()
        val probeIds = readings.mapNotNull { optional(it, "probeId") }.toSet()
        val scoped = mapOf(
            "cooks" to listOf(cook), "dishes" to rows(source, "dishes").filter { it.getString("id") in dishIds },
            "ingredients" to rows(source, "ingredients").filter { optional(it, "dishId") in dishIds },
            "events" to events, "readings" to readings,
            "targets" to rows(source, "targets").filter { it.isNull("dishId") || optional(it, "dishId") in dishIds },
            "results" to rows(source, "results").filter { optional(it, "dishId") in dishIds },
            "probes" to rows(source, "probes").filter { it.getString("id") in probeIds },
            "photos" to rows(source, "photos").filter { (photoIds == null || it.getString("id") in photoIds) && (optional(it, "dishId") in dishIds || it.isNull("dishId")) },
            "logReadingLinks" to rows(source, "logReadingLinks").filter { it.getString("readingId") in readingIds && it.getString("eventId") in eventIds },
            "lifecycles" to rows(source, "lifecycles"),
            "targetHistory" to rows(source, "targetHistory").filter { it.isNull("dishId") || optional(it, "dishId") in dishIds },
        )
        val root = JSONObject().put("schemaVersion", PitTechDataTransfer.ARCHIVE_VERSION).put("preferences", JSONObject.NULL)
        for ((key, values) in scoped) root.put(key, JSONArray(values.map { original ->
            val row = JSONObject(); fields.getValue(key).forEach { field -> if (original.has(field)) row.put(field, original.get(field)) }
            if (key == "photos" && optional(row, "eventId") !in eventIds) row.put("eventId", JSONObject.NULL)
            if (key == "cooks" && title != null) row.put("title", title)
            if (key == "probes" && optional(row, "assignedDishId") !in dishIds) row.put("assignedDishId", JSONObject.NULL)
            if (row.has("source")) row.put("source", if (row.optString("source") == "manual") "manual" else "recorded")
            for (unit in listOf("unit", "initialSetpointUnit", "outdoorTemperatureUnit")) if (row.optString(unit) in setOf("C", "F")) row.put(unit, "°" + row.getString(unit))
            metadata(row)?.let { raw ->
                val note = JSONObject(); for (field in listOf("version", "category", "action", "method", "durationMillis", "startedAtUtcMillis", "endedAtUtcMillis")) if (raw.has(field)) note.put(field, raw.get(field))
                note.put("dishIds", JSONArray(dishScope(row).filter(dishIds::contains).map { alias?.invoke(it) ?: it }))
                note.put("ingredients", JSONArray(rows(raw, "ingredients").map { ingredient -> JSONObject().apply { for (field in listOf("name", "stage", "brand", "amount", "unit")) if (ingredient.has(field)) put(field, ingredient.get(field)) } }))
                row.put("noteMetadataJson", note.toString())
            }
            optional(row, "temperatureContextJson")?.let { context ->
                val values = TemperatureContext.decode(context).filter { value ->
                    dishIds == available || optional(row, "dishId") in dishIds || readings.any { sample ->
                        sample.optString("probeName") == value.name && sample.getDouble("value") == value.value &&
                            sample.optString("unit").removePrefix("°") == value.unit.removePrefix("°") && sample.getLong("measuredAtUtcMillis") == value.observedAtUtcMillis
                    }
                }
                row.put("temperatureContextJson", TemperatureContext.encode(values) ?: JSONObject.NULL)
            }
            if (alias != null) {
                for (field in identityKeys) optional(row, field)?.let { row.put(field, alias(it)) }
                if (key == "photos") row.put("mimeType", "image/jpeg").put("originalFileName", row.getString("id") + ".jpg").put("archivePath", "attachments/${row.getString("id")}.jpg")
            }
            row
        }))
        return root
    }

    fun freeze(source: ExportSnapshot, dishIds: Set<String>, copiedPhotos: List<Pair<PhotoEntity, CompanionPhoto>>): String? {
        val cook = source.cooks.single()
        if (cook.status != CookStatus.COMPLETED || cook.endedAtUtcMillis == null || cook.startedAtUtcMillis <= 0 || cook.endedAtUtcMillis < cook.startedAtUtcMillis) return null
        val photos = copiedPhotos.map { (original, owned) -> original.copy(id = owned.id, relativePath = owned.relativePath, originalFileName = "${owned.id}.${owned.relativePath.substringAfterLast('.')}") }
        return scope(archiveSnapshotJson(source.copy(photos = photos), null), dishIds).toString()
    }

    fun remapOwnedPhotos(raw: String?, mappings: Map<String, String>, dishIds: Set<String>): String? {
        if (raw == null) return null
        val source = JSONObject(raw); val selected = rows(source, "dishes").map { it.getString("id") }.toSet().intersect(dishIds)
        if (selected.isEmpty()) return null
        val result = scope(source, selected, mappings.keys)
        rows(result, "photos").forEach { photo ->
            val id = mappings.getValue(photo.getString("id")); val extension = photo.getString("archivePath").substringAfterLast('.')
            photo.put("id", id).put("archivePath", "attachments/$id.$extension").put("originalFileName", "$id.$extension")
        }
        return result.toString()
    }
}
