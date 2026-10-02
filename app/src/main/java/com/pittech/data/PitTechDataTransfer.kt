package com.pittech.data

import android.content.Context
import com.pittech.devices.GrillSamplingPolicy
import com.pittech.devices.GrillSamplingMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

/** Portable, account-free export and restore formats for the local cook library. */
class PitTechDataTransfer(
    private val context: Context,
    private val repository: CookRepository,
) {
    suspend fun writeCsv(output: OutputStream, cookId: String? = null) {
        val snapshot = repository.exportSnapshot(cookId)
        output.write(csvTable("readings.csv", snapshot).toByteArray(Charsets.UTF_8))
    }

    suspend fun writeZip(output: OutputStream, cookId: String? = null) {
        val snapshot = repository.exportSnapshot(cookId)
        val preferences = if (cookId == null) readPreferences() else null
        ZipOutputStream(output.buffered()).use { zip ->
            putText(zip, "manifest.json", manifest(snapshot, preferences).toString(2))
            putText(zip, "README.txt", README)
            putText(zip, "data/pittech.json", snapshotJson(snapshot, preferences).toString())
            csvTables(snapshot).forEach { (name, content) -> putText(zip, "csv/$name", content) }
            snapshot.companionPhotos.forEach { photo ->
                val bytes = repository.readPhoto(photo.relativePath) ?: error("A playbook reference photo could not be read for backup.")
                putBytes(zip, "attachments/${photo.id}.${photo.relativePath.substringAfterLast('.')}", bytes)
            }
            snapshot.photos.forEach { photo ->
                val bytes = repository.readPhoto(photo.relativePath)
                    ?: error("The photo '${photo.originalFileName}' could not be read for backup.")
                val extension = photo.originalFileName.substringAfterLast('.', "jpg")
                    .filter(Char::isLetterOrDigit).take(8).ifBlank { "jpg" }
                putBytes(zip, "attachments/${photo.id}.$extension", bytes)
            }
        }
    }

    suspend fun writeWorkbook(output: OutputStream, cookId: String? = null) {
        val snapshot = repository.exportSnapshot(cookId)
        val sheets = workbookSheets(snapshot)
        ZipOutputStream(output.buffered()).use { zip -> writeWorkbookZip(zip, sheets) }
    }

    fun previewImport(input: InputStream): ImportDraft {
        val stagedArchive = File.createTempFile("pittech_restore_", ".zip", context.cacheDir)
        try {
            var compressedBytes = 0L
            stagedArchive.outputStream().buffered().use { output ->
                input.use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        compressedBytes += count
                        require(compressedBytes <= MAX_ARCHIVE_COMPRESSED_BYTES) { "This backup file is too large to inspect on this phone." }
                        output.write(buffer, 0, count)
                    }
                }
            }

            val entries = linkedMapOf<String, ByteArray>()
            val seenEntries = mutableSetOf<String>()
            var total = 0L
            var entryCount = 0
            ZipFile(stagedArchive).use { zip ->
                val archiveEntries = zip.entries()
                while (archiveEntries.hasMoreElements()) {
                    val entry = archiveEntries.nextElement()
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "This backup contains too many files." }
                    require(isSafeArchivePath(entry.name, entry.isDirectory)) { "The backup contains an invalid file path." }
                    require(seenEntries.add(entry.name)) { "The backup contains a duplicate file path." }
                    require(entry.size in 0..MAX_ARCHIVE_ENTRY_BYTES.toLong()) { "A file in this backup is too large." }
                    total += entry.size
                    require(total <= MAX_ARCHIVE_BYTES) { "This backup is too large to preview on this phone." }
                    if (entry.isDirectory) continue

                    val bytes = zip.getInputStream(entry).use { it.readBounded(MAX_ARCHIVE_ENTRY_BYTES) }
                    require(bytes.size.toLong() == entry.size) { "This backup is incomplete or corrupted." }
                    val checksum = CRC32().apply { update(bytes) }.value
                    require(checksum == entry.crc) { "This backup is incomplete or corrupted." }
                    if (entry.name == "manifest.json" || entry.name == "data/pittech.json" || entry.name.startsWith("attachments/")) {
                        entries[entry.name] = bytes
                    }
                }
            }

            val manifestBytes = entries["manifest.json"] ?: error("This ZIP does not contain a PitTech backup manifest.")
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            val jsonBytes = entries["data/pittech.json"] ?: error("This ZIP does not contain a PitTech backup.")
            val root = JSONObject(jsonBytes.toString(Charsets.UTF_8))
            val archiveVersion = root.optInt("schemaVersion", -1)
            require(archiveVersion in MIN_SUPPORTED_ARCHIVE_VERSION..ARCHIVE_VERSION) {
                "This PitTech backup version is not supported by this app."
            }
            require(manifest.optString("format") == "PitTech complete cook archive") {
                "This ZIP is not a PitTech cook backup."
            }
            require(manifest.optInt("archiveVersion", archiveVersion) == archiveVersion) {
                "The backup manifest and cook data use different versions."
            }
            require(manifest.optBoolean("includesPreferences", false) == (root.optJSONObject("preferences") != null)) {
                "The backup manifest and app preferences do not match."
            }
            val attachments = mutableMapOf<String, ByteArray>()
            val snapshot = parseSnapshot(root) { photoId, archivePath ->
                entries[archivePath]?.also { attachments[photoId] = it }
            }
            val preferences = parsePreferences(root.optJSONObject("preferences"))
            return ImportDraft(
                snapshot = snapshot,
                attachments = attachments,
                preferences = preferences,
                archiveVersion = archiveVersion,
                exportedAtUtc = manifest.optString("exportedAtUtc").takeIf { it.isNotBlank() },
            )
        } catch (_: ZipException) {
            error("This backup is incomplete or corrupted.")
        } finally {
            stagedArchive.delete()
        }
    }

    suspend fun import(draft: ImportDraft): ImportSummary = repository.importSnapshot(draft.snapshot, draft.attachments)

    private fun manifest(snapshot: ExportSnapshot, preferences: BackupPreferences?) = JSONObject()
        .put("format", "PitTech complete cook archive")
        .put("archiveVersion", ARCHIVE_VERSION)
        .put("exportedAtUtc", Instant.now().toString())
        .put("appVersion", "0.1.0")
        .put("includesPreferences", preferences != null)
        .put("counts", JSONObject()
            .put("cooks", snapshot.cooks.size)
            .put("dishes", snapshot.dishes.size)
            .put("ingredients", snapshot.ingredients.size)
            .put("timelineEvents", snapshot.events.size)
            .put("readings", snapshot.readings.size)
            .put("recordings", snapshot.recordings.size).put("probeAssignments", snapshot.assignments.size)
            .put("results", snapshot.results.size)
            .put("photos", snapshot.photos.size)
            .put("reminders", snapshot.reminders.size))

    private fun snapshotJson(snapshot: ExportSnapshot, preferences: BackupPreferences?) = JSONObject()
        .put("schemaVersion", ARCHIVE_VERSION)
        .putNullable("preferences", preferences?.let {
            JSONObject()
                .put("temperatureUnit", it.temperatureUnit)
                .put("weightUnit", it.weightUnit)
                .put("themeMode", it.themeMode)
        })
        .put("cooks", JSONArray().apply { snapshot.cooks.forEach { put(it.toJson()) } })
        .put("dishes", JSONArray().apply { snapshot.dishes.forEach { put(it.toJson()) } })
        .put("ingredients", JSONArray().apply { snapshot.ingredients.forEach { put(it.toJson()) } })
        .put("events", JSONArray().apply { snapshot.events.forEach { put(it.toJson()) } })
        .put("readings", JSONArray().apply { snapshot.readings.forEach { put(it.toJson()) } })
        .put("targets", JSONArray().apply { snapshot.targets.forEach { put(it.toJson()) } })
        .put("results", JSONArray().apply { snapshot.results.forEach { put(it.toJson()) } })
        .put("devices", JSONArray().apply { snapshot.devices.forEach { put(it.toJson()) } })
        .put("probes", JSONArray().apply { snapshot.probes.forEach { put(it.toJson()) } })
        .put("reminders", JSONArray().apply { snapshot.reminders.forEach { put(it.toJson()) } })
        .put("recordings", JSONArray().apply { snapshot.recordings.forEach { put(it.toJson()) } })
        .put("probeAssignments", JSONArray().apply { snapshot.assignments.forEach { put(it.toJson()) } })
        .put("companionRecords", JSONArray(snapshot.companionRecords.map { JSONObject().put("id", it.id).put("kind", it.kind).put("cookId", it.cookId).put("title", it.title).put("payload", JSONObject(it.payload)).put("created", it.createdAtUtcMillis).put("updated", it.updatedAtUtcMillis) }))
        .put("companionPhotos", JSONArray(snapshot.companionPhotos.map { JSONObject().put("id", it.id).put("recordId", it.recordId).put("archivePath", "attachments/${it.id}.${it.relativePath.substringAfterLast('.')}").put("mimeType", it.mimeType).put("caption", it.caption).put("dishIndex", it.dishIndex).put("action", it.action) }))
        .put("photos", JSONArray().apply {
            snapshot.photos.forEach { photo ->
                val extension = photo.originalFileName.substringAfterLast('.', "jpg").filter(Char::isLetterOrDigit).take(8).ifBlank { "jpg" }
                put(photo.toJson().put("archivePath", "attachments/${photo.id}.$extension"))
            }
        })

    private fun csvTables(snapshot: ExportSnapshot): Map<String, String> = linkedMapOf(
        "cooks.csv" to csv("cook_id,title,status,started_at_utc,started_at_local,started_time_zone,ended_at_utc,ended_at_local,smoker,fuel_type,wood_or_pellet_blend,outdoor_temperature,outdoor_temperature_unit,weather,wind,initial_setpoint,initial_setpoint_unit,notes,created_at_utc,updated_at_utc", snapshot.cooks.map { listOf(it.id,it.title,it.status,utc(it.startedAtUtcMillis),local(it.startedAtUtcMillis,it.startedTimeZoneId),it.startedTimeZoneId,utc(it.endedAtUtcMillis),local(it.endedAtUtcMillis,it.startedTimeZoneId),it.smokerName,it.fuelType,it.woodOrPelletBlend,it.outdoorTemperatureValue,it.outdoorTemperatureUnit,it.weatherNotes,it.windNotes,it.initialSetpointValue,it.initialSetpointUnit,it.notes,utc(it.createdAtUtcMillis),utc(it.updatedAtUtcMillis)) }),
        "dishes.csv" to csv("dish_id,cook_id,name,food_type,cut,weight,weight_unit,piece_count,starting_condition,bone_in,placement,grade_or_source,thickness_notes,preparation_notes", snapshot.dishes.map { listOf(it.id,it.cookId,it.name,it.foodType,it.cut,it.weightValue,it.weightUnit,it.pieceCount,it.startingCondition,it.boneIn,it.placement,it.gradeOrSource,it.thicknessNotes,it.prepNotes) }),
        "ingredients.csv" to csv("ingredient_use_id,cook_id,dish_id,stage,name,brand,amount,amount_unit,applied_at_utc,notes", snapshot.ingredients.map { listOf(it.id,it.cookId,it.dishId,it.stage,it.name,it.brand,it.amountValue,it.amountUnit,utc(it.appliedAtUtcMillis),it.notes) }),
        "events.csv" to csv("event_id,cook_id,dish_id,event_type,title,details,occurred_at_utc,occurred_at_local,recorded_at_utc,time_zone,source,source_device_id,temperature_context_json", snapshot.events.map { listOf(it.id,it.cookId,it.dishId,it.eventType,it.title,it.details,utc(it.occurredAtUtcMillis),local(it.occurredAtUtcMillis,it.timeZoneId),utc(it.recordedAtUtcMillis),it.timeZoneId,it.source,it.sourceDeviceId,it.temperatureContextJson) }),
        "readings.csv" to csv("reading_id,cook_id,dish_id,probe_id,probe_name,measurement_type,value,unit,measured_at_utc,measured_at_local,time_zone,source,source_device_id,quality_status,recorded_at_utc,timestamp_basis,sampling_interval_millis", snapshot.readings.map { listOf(it.id,it.cookId,it.dishId,it.probeId,it.probeName,it.measurementType,it.value,it.unit,utc(it.measuredAtUtcMillis),local(it.measuredAtUtcMillis,it.timeZoneId),it.timeZoneId,it.source,it.sourceDeviceId,it.qualityStatus,utc(it.recordedAtUtcMillis),it.timestampBasis,it.samplingIntervalMillis) }),
        "targets.csv" to csv("target_id,cook_id,dish_id,target_type,value,unit,scope,explanation,source_reference,created_at_utc", snapshot.targets.map { listOf(it.id,it.cookId,it.dishId,it.targetType,it.value,it.unit,it.scope,it.explanation,it.sourceReference,utc(it.createdAtUtcMillis)) }),
        "results.csv" to csv("result_id,cook_id,dish_id,result_type,numeric_value,unit,text_value,notes,recorded_at_utc", snapshot.results.map { listOf(it.id,it.cookId,it.dishId,it.resultType,it.numericValue,it.unit,it.textValue,it.notes,utc(it.recordedAtUtcMillis)) }),
        "devices.csv" to csv("device_id,cook_id,brand,model,device_name,role,firmware_version,created_at_utc", snapshot.devices.map { listOf(it.id,it.cookId,it.brand,it.model,it.deviceName,it.role,it.firmwareVersion,utc(it.createdAtUtcMillis)) }),
        "probes.csv" to csv("probe_id,cook_id,device_id,assigned_dish_id,name,measurement_type,source,created_at_utc", snapshot.probes.map { listOf(it.id,it.cookId,it.deviceId,it.assignedDishId,it.name,it.measurementType,it.source,utc(it.createdAtUtcMillis)) }),
        "photos.csv" to csv("photo_id,cook_id,dish_id,event_id,file_name,mime_type,caption,captured_at_utc,added_at_utc,temperature_context_json", snapshot.photos.map { listOf(it.id,it.cookId,it.dishId,it.eventId,it.originalFileName,it.mimeType,it.caption,utc(it.capturedAtUtcMillis),utc(it.addedAtUtcMillis),it.temperatureContextJson) }),
        "recordings.csv" to csv("cook_id,device_id,status,unit,started_at_utc,last_received_at_utc,sampling_mode,sampling_interval_millis", snapshot.recordings.map { listOf(it.cookId,it.deviceId,CookRecordingEntity.STOPPED,it.unit,utc(it.startedAtUtcMillis),utc(it.lastReceivedAtUtcMillis),it.samplingMode,it.samplingIntervalMillis) }),
        "probe_assignments.csv" to csv("assignment_id,cook_id,probe_id,dish_id,started_at_utc,ended_at_utc", snapshot.assignments.map { listOf(it.id,it.cookId,it.probeId,it.dishId,utc(it.startedAtUtcMillis),utc(it.endedAtUtcMillis)) }),
        "saved_guidance.csv" to csv("record_id,kind,cook_id,title,payload_json,created_at_utc,updated_at_utc", snapshot.companionRecords.map { listOf(it.id,it.kind,it.cookId,it.title,it.payload,utc(it.createdAtUtcMillis),utc(it.updatedAtUtcMillis)) }),
        "reference_photos.csv" to csv("photo_id,record_id,caption,dish_index,action,mime_type", snapshot.companionPhotos.map { listOf(it.id,it.recordId,it.caption,it.dishIndex,it.action,it.mimeType) }),
        "reminders.csv" to csv("reminder_id,cook_id,title,due_at_utc,time_zone,status,created_at_utc,completed_at_utc", snapshot.reminders.map { listOf(it.id,it.cookId,it.title,utc(it.dueAtUtcMillis),it.timeZoneId,it.status,utc(it.createdAtUtcMillis),utc(it.completedAtUtcMillis)) }),
    )

    private fun utc(millis: Long?): String? = millis?.let { Instant.ofEpochMilli(it).toString() }

    private fun local(millis: Long?, timeZoneId: String): String? = millis?.let { timestamp ->
        runCatching { DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(timeZoneId))) }
            .getOrElse { Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("UTC")).toString() }
    }

    private fun csvTable(fileName: String, snapshot: ExportSnapshot): String = csvTables(snapshot)[fileName]
        ?: error("Unknown CSV table.")

    private fun csv(header: String, rows: List<List<Any?>>): String = buildString {
        append(header).append("\r\n")
        rows.forEach { row -> append(row.joinToString(",") { csvCell(it) }).append("\r\n") }
    }

    private fun csvCell(value: Any?): String {
        val raw = value?.toString().orEmpty()
        val safe = if (value is String && raw.firstOrNull()?.let { it in "=+-@\t\r" } == true) "'$raw" else raw
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    private fun putText(zip: ZipOutputStream, name: String, value: String) = putBytes(zip, name, value.toByteArray(Charsets.UTF_8))
    private fun putBytes(zip: ZipOutputStream, name: String, value: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value)
        zip.closeEntry()
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "A file in this backup is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun workbookSheets(s: ExportSnapshot): List<Sheet> {
        fun rows(header: List<Cell>, values: List<List<Cell>>) = listOf(header) + values
        fun text(value: Any?) = Cell.Text(value?.toString().orEmpty())
        fun num(value: Number?) = value?.let { Cell.Number(it.toString()) } ?: Cell.Text("")
        val summaryHeader = listOf("Cook ID", "Cook", "Status", "Started UTC", "Started local time", "Time zone", "Finished UTC", "Finished local time", "Duration minutes", "Smoker", "Fuel", "Wood or pellet blend", "Outdoor temperature", "Outdoor unit", "Weather", "Wind", "Dishes", "Overall rating", "Notes")
        val summary = s.cooks.map { cook ->
            val duration = cook.endedAtUtcMillis?.let { (it - cook.startedAtUtcMillis).coerceAtLeast(0) / 60_000.0 }
            listOf(text(cook.id),text(cook.title),text(cook.status),text(utc(cook.startedAtUtcMillis)),text(local(cook.startedAtUtcMillis,cook.startedTimeZoneId)),text(cook.startedTimeZoneId),text(utc(cook.endedAtUtcMillis)),text(local(cook.endedAtUtcMillis,cook.startedTimeZoneId)),num(duration),text(cook.smokerName),text(cook.fuelType),text(cook.woodOrPelletBlend),num(cook.outdoorTemperatureValue),text(cook.outdoorTemperatureUnit),text(cook.weatherNotes),text(cook.windNotes),text(s.dishes.filter { it.cookId == cook.id }.joinToString { it.name }),num(s.results.firstOrNull { it.cookId == cook.id && it.resultType == "overall_rating" }?.numericValue),text(cook.notes))
        }
        val readings = s.readings.sortedBy { it.measuredAtUtcMillis }.map { r -> listOf(text(r.id),text(r.cookId),text(r.dishId),text(r.probeName),text(r.measurementType),num(r.value),text(r.unit),text(utc(r.measuredAtUtcMillis)),text(local(r.measuredAtUtcMillis,r.timeZoneId)),text(r.timeZoneId),text(r.source),text(r.qualityStatus),text(r.timestampBasis),num(r.samplingIntervalMillis)) }
        val derived = s.readings.filter { it.qualityStatus == "valid" }.groupBy { Triple(it.cookId, it.probeName, it.unit) }.flatMap { (key, values) ->
            val sorted = values.sortedBy { it.measuredAtUtcMillis }
            listOf("reading_count" to values.size.toDouble(), "minimum" to values.minOf { it.value }, "maximum" to values.maxOf { it.value }, "last" to sorted.last().value).map { (metric, value) ->
                listOf(text(key.first),text(key.second),text(metric),num(value),text(key.third),text("Valid recorded values; no interpolation"),text(utc(sorted.first().measuredAtUtcMillis)),text(utc(sorted.last().measuredAtUtcMillis)),text("PitTech summary v1"))
            }
        }
        return listOf(
            Sheet("Cook Summary", rows(summaryHeader.map(::text), summary)),
            Sheet("Dishes", rows(listOf("Dish ID","Cook ID","Name","Food type","Cut","Weight","Weight unit","Pieces","Starting condition","Bone in","Placement","Grade or source","Thickness notes","Prep notes").map(::text), s.dishes.map { listOf(text(it.id),text(it.cookId),text(it.name),text(it.foodType),text(it.cut),num(it.weightValue),text(it.weightUnit),num(it.pieceCount),text(it.startingCondition),text(it.boneIn),text(it.placement),text(it.gradeOrSource),text(it.thicknessNotes),text(it.prepNotes)) })),
            Sheet("Ingredients", rows(listOf("Ingredient use ID","Cook ID","Dish ID","Stage","Name","Brand","Amount","Unit","Applied UTC","Notes").map(::text), s.ingredients.map { listOf(text(it.id),text(it.cookId),text(it.dishId),text(it.stage),text(it.name),text(it.brand),num(it.amountValue),text(it.amountUnit),text(utc(it.appliedAtUtcMillis)),text(it.notes)) })),
            Sheet("Timeline", rows(listOf("Event ID","Cook ID","Dish ID","Type","Title","Details","Occurred UTC","Recorded UTC","Time zone","Source","Temperature context").map(::text), s.events.map { listOf(text(it.id),text(it.cookId),text(it.dishId),text(it.eventType),text(it.title),text(it.details),text(utc(it.occurredAtUtcMillis)),text(utc(it.recordedAtUtcMillis)),text(it.timeZoneId),text(it.source),text(it.temperatureContextJson)) })),
            Sheet("Readings", rows(listOf("Reading ID","Cook ID","Dish ID","Probe","Measurement","Value","Unit","Measured UTC","Measured local time","Time zone","Source","Quality","Timestamp basis","Sampling interval millis (0 = on log)").map(::text), readings)),
            Sheet("Targets Results", rows(listOf("Record ID","Cook ID","Dish ID","Type","Value","Unit","Text","Notes","Recorded UTC").map(::text), (s.targets.map { listOf(text(it.id),text(it.cookId),text(it.dishId),text(it.targetType),num(it.value),text(it.unit),text(it.scope),text(it.explanation),text(utc(it.createdAtUtcMillis))) } + s.results.map { listOf(text(it.id),text(it.cookId),text(it.dishId),text(it.resultType),num(it.numericValue),text(it.unit),text(it.textValue),text(it.notes),text(utc(it.recordedAtUtcMillis))) }))),
            Sheet("Devices", rows(listOf("Device ID","Cook ID","Brand","Model","Name","Role","Firmware","Created UTC").map(::text), s.devices.map { listOf(text(it.id),text(it.cookId),text(it.brand),text(it.model),text(it.deviceName),text(it.role),text(it.firmwareVersion),text(utc(it.createdAtUtcMillis))) })),
            Sheet("Probes", rows(listOf("Probe ID","Cook ID","Device ID","Dish ID","Name","Measurement","Source","Created UTC").map(::text), s.probes.map { listOf(text(it.id),text(it.cookId),text(it.deviceId),text(it.assignedDishId),text(it.name),text(it.measurementType),text(it.source),text(utc(it.createdAtUtcMillis))) })),
            Sheet("Photos", rows(listOf("Photo ID","Cook ID","Dish ID","Event ID","Caption","File name","MIME type","Captured UTC","Added UTC","Temperature context").map(::text), s.photos.map { listOf(text(it.id),text(it.cookId),text(it.dishId),text(it.eventId),text(it.caption),text(it.originalFileName),text(it.mimeType),text(utc(it.capturedAtUtcMillis)),text(utc(it.addedAtUtcMillis)),text(it.temperatureContextJson)) })),
            Sheet("Derived Metrics", rows(listOf("Cook ID","Probe","Metric","Value","Unit","Method","Source start UTC","Source end UTC","Method version").map(::text), derived)),
            Sheet("Recording History", rows(listOf("Cook ID","Device ID","Status","Unit","Started UTC","Last received UTC","Sampling mode","Sampling interval millis").map(::text), s.recordings.map { listOf(text(it.cookId),text(it.deviceId),text(CookRecordingEntity.STOPPED),text(it.unit),text(utc(it.startedAtUtcMillis)),text(utc(it.lastReceivedAtUtcMillis)),text(it.samplingMode),num(it.samplingIntervalMillis)) })),
            Sheet("Probe Assignments", rows(listOf("ID","Cook ID","Probe ID","Dish ID","Started UTC","Ended UTC").map(::text), s.assignments.map { listOf(text(it.id),text(it.cookId),text(it.probeId),text(it.dishId),text(utc(it.startedAtUtcMillis)),text(utc(it.endedAtUtcMillis))) })),
            Sheet("Saved Guidance", rows(listOf("Record ID","Kind","Cook ID","Title","Payload JSON","Created UTC","Updated UTC").map(::text), s.companionRecords.map { listOf(text(it.id),text(it.kind),text(it.cookId),text(it.title),text(it.payload),text(utc(it.createdAtUtcMillis)),text(utc(it.updatedAtUtcMillis))) })),
            Sheet("Reference Photos", rows(listOf("Photo ID","Record ID","Caption","Dish index","Action","MIME type").map(::text), s.companionPhotos.map { listOf(text(it.id),text(it.recordId),text(it.caption),num(it.dishIndex),text(it.action),text(it.mimeType)) })),
            Sheet("Reminders", rows(listOf("Reminder ID","Cook ID","Title","Due UTC","Time zone","Status","Created UTC","Completed UTC").map(::text), s.reminders.map { listOf(text(it.id),text(it.cookId),text(it.title),text(utc(it.dueAtUtcMillis)),text(it.timeZoneId),text(it.status),text(utc(it.createdAtUtcMillis)),text(utc(it.completedAtUtcMillis))) })),
        )
    }

    private fun writeWorkbookZip(zip: ZipOutputStream, sheets: List<Sheet>) {
        putText(zip, "[Content_Types].xml", contentTypes(sheets.size))
        putText(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        putText(zip, "xl/workbook.xml", workbookXml(sheets))
        putText(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
        sheets.forEachIndexed { index, sheet -> putText(zip, "xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet)) }
    }

    private fun contentTypes(count: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        repeat(count) { append("<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
        append("</Types>")
    }

    private fun workbookXml(sheets: List<Sheet>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
        sheets.forEachIndexed { index, sheet -> append("<sheet name=\"${xml(sheet.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>") }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        repeat(count) { append("<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>") }
        append("</Relationships>")
    }

    private fun sheetXml(sheet: Sheet) = buildString {
        val rows = sheet.rows
        val columns = rows.maxOfOrNull { it.size } ?: 1
        val lastCell = "${columnName(columns)}${rows.size}"
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            append("<row r=\"${rowIndex + 1}\">")
            row.forEachIndexed { columnIndex, cell ->
                val reference = "${columnName(columnIndex + 1)}${rowIndex + 1}"
                when (cell) {
                    is Cell.Number -> append("<c r=\"$reference\"><v>${xml(cell.value)}</v></c>")
                    is Cell.Text -> append("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(cell.value)}</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData><autoFilter ref=\"A1:$lastCell\"/></worksheet>")
    }

    private fun columnName(number: Int): String {
        var n = number
        var result = ""
        while (n > 0) {
            val digit = (n - 1) % 26
            result = ('A' + digit) + result
            n = (n - 1) / 26
        }
        return result
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private data class Sheet(val name: String, val rows: List<List<Cell>>)
    private sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: String) : Cell
    }

    data class ImportDraft internal constructor(
        val snapshot: ExportSnapshot,
        internal val attachments: Map<String, ByteArray>,
        val preferences: BackupPreferences?,
        val archiveVersion: Int,
        val exportedAtUtc: String?,
    ) {
        val cookCount: Int get() = snapshot.cooks.size
        val dishCount: Int get() = snapshot.dishes.size
        val photoCount: Int get() = snapshot.photos.size
        val eventCount: Int get() = snapshot.events.size
        val readingCount: Int get() = snapshot.readings.size
        val resultCount: Int get() = snapshot.results.size
    }

    private companion object {
        const val ARCHIVE_VERSION = 5
        const val MIN_SUPPORTED_ARCHIVE_VERSION = 1
        const val MAX_ARCHIVE_ENTRY_BYTES = 45 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 300L * 1024L * 1024L
        const val MAX_ARCHIVE_COMPRESSED_BYTES = 350L * 1024L * 1024L
        const val MAX_ARCHIVE_ENTRIES = 10_000
        const val README = "PitTech portable cook archive\n\nThe CSV files use one row per record and include stable IDs for joining related tables. Timestamps are ISO 8601 UTC; each event or reading also includes its local timestamp and time zone. Numeric measurements retain their value and explicit unit. Original photos are in attachments/. The structured data/pittech.json file preserves all records and relationships and can be restored in PitTech. Full-library archives also preserve temperature, weight, and appearance preferences. Older schema v1/v2 archives remain restorable. Cloud readings use receipt time; sensor sample age is unknown. Recording credentials and cloud identifiers are excluded. Restoring never starts a recording session.\n"
    }

    private fun readPreferences(): BackupPreferences {
        val values = context.getSharedPreferences("pittech-preferences", Context.MODE_PRIVATE)
        return BackupPreferences(
            temperatureUnit = values.getString("temperature-unit", "°F")?.takeIf { it in setOf("°F", "°C") } ?: "°F",
            weightUnit = values.getString("weight-unit", "lb")?.takeIf { it in setOf("lb", "oz", "kg", "g") } ?: "lb",
            themeMode = values.getString("theme-mode", "SYSTEM")?.uppercase()?.takeIf { it in setOf("SYSTEM", "LIGHT", "DARK") } ?: "SYSTEM",
        )
    }

    private fun parsePreferences(json: JSONObject?): BackupPreferences? {
        if (json == null) return null
        val temperatureUnit = json.optString("temperatureUnit")
        val weightUnit = json.optString("weightUnit")
        val themeMode = json.optString("themeMode").uppercase()
        require(temperatureUnit in setOf("°F", "°C")) { "The backup contains an invalid temperature preference." }
        require(weightUnit in setOf("lb", "oz", "kg", "g")) { "The backup contains an invalid weight preference." }
        require(themeMode in setOf("SYSTEM", "LIGHT", "DARK")) { "The backup contains an invalid appearance preference." }
        return BackupPreferences(temperatureUnit, weightUnit, themeMode)
    }

    private fun isSafeArchivePath(path: String, isDirectory: Boolean = false): Boolean {
        val candidate = if (isDirectory) path.removeSuffix("/") else path
        if (candidate.isBlank() || candidate.startsWith('/') || candidate.contains('\\') || candidate.contains("//")) return false
        return candidate.split('/').none { it.isBlank() || it == "." || it == ".." }
    }
}

data class BackupPreferences(
    val temperatureUnit: String,
    val weightUnit: String,
    val themeMode: String,
)

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

private fun CookEntity.toJson() = JSONObject()
    .put("id", id).put("title", title).put("status", status).put("startedAtUtcMillis", startedAtUtcMillis)
    .put("startedTimeZoneId", startedTimeZoneId).putNullable("endedAtUtcMillis", endedAtUtcMillis)
    .putNullable("smokerName", smokerName).putNullable("initialSetpointValue", initialSetpointValue)
    .putNullable("initialSetpointUnit", initialSetpointUnit).putNullable("notes", notes)
    .put("createdAtUtcMillis", createdAtUtcMillis).put("updatedAtUtcMillis", updatedAtUtcMillis)
    .putNullable("fuelType", fuelType).putNullable("woodOrPelletBlend", woodOrPelletBlend)
    .putNullable("outdoorTemperatureValue", outdoorTemperatureValue).putNullable("outdoorTemperatureUnit", outdoorTemperatureUnit)
    .putNullable("weatherNotes", weatherNotes).putNullable("windNotes", windNotes)

private fun DishEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).put("name", name).put("foodType", foodType).putNullable("cut", cut)
    .putNullable("weightValue", weightValue).putNullable("weightUnit", weightUnit).putNullable("pieceCount", pieceCount)
    .putNullable("startingCondition", startingCondition).putNullable("boneIn", boneIn).putNullable("placement", placement)
    .putNullable("prepNotes", prepNotes).put("createdAtUtcMillis", createdAtUtcMillis).put("updatedAtUtcMillis", updatedAtUtcMillis)
    .putNullable("gradeOrSource", gradeOrSource).putNullable("thicknessNotes", thicknessNotes)

private fun IngredientEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).put("dishId", dishId).put("stage", stage).put("name", name)
    .putNullable("brand", brand).putNullable("amountValue", amountValue).putNullable("amountUnit", amountUnit)
    .putNullable("appliedAtUtcMillis", appliedAtUtcMillis).putNullable("notes", notes).put("createdAtUtcMillis", createdAtUtcMillis)

private fun TimelineEventEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).putNullable("dishId", dishId).put("eventType", eventType).put("title", title)
    .putNullable("details", details).put("occurredAtUtcMillis", occurredAtUtcMillis).put("recordedAtUtcMillis", recordedAtUtcMillis)
    .put("timeZoneId", timeZoneId).put("source", source).putNullable("sourceDeviceId", sourceDeviceId)
    .put("createdAtUtcMillis", createdAtUtcMillis).put("updatedAtUtcMillis", updatedAtUtcMillis)
    .putNullable("temperatureContextJson", temperatureContextJson)

private fun SensorReadingEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).putNullable("dishId", dishId).putNullable("probeId", probeId)
    .put("probeName", probeName).put("measurementType", measurementType).put("value", value).put("unit", unit)
    .put("measuredAtUtcMillis", measuredAtUtcMillis).put("timeZoneId", timeZoneId).put("source", source)
    .putNullable("sourceDeviceId", sourceDeviceId).put("qualityStatus", qualityStatus).put("recordedAtUtcMillis", recordedAtUtcMillis).put("timestampBasis", timestampBasis).put("samplingIntervalMillis", samplingIntervalMillis)

private fun TargetEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).putNullable("dishId", dishId).put("targetType", targetType).put("value", value)
    .put("unit", unit).put("scope", scope).putNullable("explanation", explanation).putNullable("sourceReference", sourceReference)
    .put("createdAtUtcMillis", createdAtUtcMillis)

private fun CookResultEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).putNullable("dishId", dishId).put("resultType", resultType)
    .putNullable("numericValue", numericValue).putNullable("unit", unit).putNullable("textValue", textValue)
    .putNullable("notes", notes).put("recordedAtUtcMillis", recordedAtUtcMillis)

private fun DeviceEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).putNullable("brand", brand).putNullable("model", model)
    .put("deviceName", deviceName).put("role", role).putNullable("firmwareVersion", firmwareVersion).put("createdAtUtcMillis", createdAtUtcMillis)

private fun ProbeEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).putNullable("deviceId", deviceId).putNullable("assignedDishId", assignedDishId)
    .put("name", name).put("measurementType", measurementType).put("source", source).put("createdAtUtcMillis", createdAtUtcMillis)

private fun PhotoEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).putNullable("dishId", dishId).putNullable("eventId", eventId)
    .put("originalFileName", originalFileName).put("mimeType", mimeType).putNullable("caption", caption)
    .putNullable("capturedAtUtcMillis", capturedAtUtcMillis).put("addedAtUtcMillis", addedAtUtcMillis).putNullable("temperatureContextJson", temperatureContextJson)

private fun CookRecordingEntity.toJson() = JSONObject()
    .put("cookId", cookId).put("deviceId", deviceId).put("status", CookRecordingEntity.STOPPED).put("unit", unit)
    .put("samplingMode", samplingMode).put("samplingIntervalMillis", samplingIntervalMillis)
    .put("startedAtUtcMillis", startedAtUtcMillis).put("resumedAtUtcMillis", resumedAtUtcMillis)
    .putNullable("lastReceivedAtUtcMillis", lastReceivedAtUtcMillis)

private fun ProbeAssignmentEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).put("probeId", probeId).putNullable("dishId", dishId)
    .put("startedAtUtcMillis", startedAtUtcMillis).putNullable("endedAtUtcMillis", endedAtUtcMillis)

private fun CookReminderEntity.toJson() = JSONObject()
    .put("id", id).put("cookId", cookId).put("title", title).put("dueAtUtcMillis", dueAtUtcMillis)
    .put("timeZoneId", timeZoneId).put("status", status).put("createdAtUtcMillis", createdAtUtcMillis)
    .putNullable("completedAtUtcMillis", completedAtUtcMillis)

private fun JSONObject.string(key: String): String = getString(key)
private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.long(key: String): Long = getLong(key)
private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.double(key: String): Double = getDouble(key)
private fun JSONObject.doubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
private fun JSONObject.booleanOrNull(key: String): Boolean? = if (!has(key) || isNull(key)) null else getBoolean(key)

private fun JSONObject.arrayObjects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: JSONArray()
    return (0 until array.length()).map { array.getJSONObject(it) }
}

private fun parseSnapshot(root: JSONObject, attachment: (String, String) -> ByteArray?): ExportSnapshot {
    val cooks = root.arrayObjects("cooks").map { o ->
        CookEntity(
            id=o.string("id"), title=o.string("title"), status=o.string("status"), startedAtUtcMillis=o.long("startedAtUtcMillis"),
            startedTimeZoneId=o.string("startedTimeZoneId"), endedAtUtcMillis=o.longOrNull("endedAtUtcMillis"),
            smokerName=o.stringOrNull("smokerName"), initialSetpointValue=o.doubleOrNull("initialSetpointValue"),
            initialSetpointUnit=o.stringOrNull("initialSetpointUnit"), notes=o.stringOrNull("notes"),
            createdAtUtcMillis=o.long("createdAtUtcMillis"), updatedAtUtcMillis=o.long("updatedAtUtcMillis"),
            fuelType=o.stringOrNull("fuelType"), woodOrPelletBlend=o.stringOrNull("woodOrPelletBlend"),
            outdoorTemperatureValue=o.doubleOrNull("outdoorTemperatureValue"), outdoorTemperatureUnit=o.stringOrNull("outdoorTemperatureUnit"),
            weatherNotes=o.stringOrNull("weatherNotes"), windNotes=o.stringOrNull("windNotes"),
        )
    }
    val dishes = root.arrayObjects("dishes").map { o ->
        DishEntity(
            id=o.string("id"), cookId=o.string("cookId"), name=o.string("name"), foodType=o.string("foodType"), cut=o.stringOrNull("cut"),
            weightValue=o.doubleOrNull("weightValue"), weightUnit=o.stringOrNull("weightUnit"), pieceCount=o.intOrNull("pieceCount"),
            startingCondition=o.stringOrNull("startingCondition"), boneIn=o.booleanOrNull("boneIn"), placement=o.stringOrNull("placement"),
            prepNotes=o.stringOrNull("prepNotes"), createdAtUtcMillis=o.long("createdAtUtcMillis"), updatedAtUtcMillis=o.long("updatedAtUtcMillis"),
            gradeOrSource=o.stringOrNull("gradeOrSource"), thicknessNotes=o.stringOrNull("thicknessNotes"),
        )
    }
    val ingredients = root.arrayObjects("ingredients").map { o ->
        IngredientEntity(
            id=o.string("id"), cookId=o.string("cookId"), dishId=o.string("dishId"), stage=o.string("stage"), name=o.string("name"),
            brand=o.stringOrNull("brand"), amountValue=o.doubleOrNull("amountValue"), amountUnit=o.stringOrNull("amountUnit"),
            appliedAtUtcMillis=o.longOrNull("appliedAtUtcMillis"), notes=o.stringOrNull("notes"), createdAtUtcMillis=o.long("createdAtUtcMillis"),
        )
    }
    val events = root.arrayObjects("events").map { o ->
        TimelineEventEntity(
            id=o.string("id"), cookId=o.string("cookId"), dishId=o.stringOrNull("dishId"), eventType=o.string("eventType"),
            title=o.string("title"), details=o.stringOrNull("details"), occurredAtUtcMillis=o.long("occurredAtUtcMillis"),
            recordedAtUtcMillis=o.long("recordedAtUtcMillis"), timeZoneId=o.string("timeZoneId"), source=o.string("source"),
            sourceDeviceId=o.stringOrNull("sourceDeviceId"), createdAtUtcMillis=o.long("createdAtUtcMillis"), updatedAtUtcMillis=o.long("updatedAtUtcMillis"),
            temperatureContextJson=validatedContext(o),
        )
    }
    val readings = root.arrayObjects("readings").map { o ->
        SensorReadingEntity(
            id=o.string("id"), cookId=o.string("cookId"), dishId=o.stringOrNull("dishId"), probeId=o.stringOrNull("probeId"),
            probeName=o.string("probeName"), measurementType=o.string("measurementType"), value=o.double("value"), unit=o.string("unit"),
            measuredAtUtcMillis=o.long("measuredAtUtcMillis"), timeZoneId=o.string("timeZoneId"), source=o.string("source"),
            sourceDeviceId=o.stringOrNull("sourceDeviceId"), qualityStatus=o.optString("qualityStatus", "valid"), recordedAtUtcMillis=o.long("recordedAtUtcMillis"),
            timestampBasis=o.optString("timestampBasis", "measurement"),
            samplingIntervalMillis=o.optLong("samplingIntervalMillis", 15_000L),
        )
    }
    val targets = root.arrayObjects("targets").map { o ->
        TargetEntity(
            id=o.string("id"), cookId=o.string("cookId"), dishId=o.stringOrNull("dishId"), targetType=o.string("targetType"),
            value=o.double("value"), unit=o.string("unit"), scope=o.string("scope"), explanation=o.stringOrNull("explanation"),
            sourceReference=o.stringOrNull("sourceReference"), createdAtUtcMillis=o.long("createdAtUtcMillis"),
        )
    }
    val results = root.arrayObjects("results").map { o ->
        CookResultEntity(
            id=o.string("id"), cookId=o.string("cookId"), dishId=o.stringOrNull("dishId"), resultType=o.string("resultType"),
            numericValue=o.doubleOrNull("numericValue"), unit=o.stringOrNull("unit"), textValue=o.stringOrNull("textValue"),
            notes=o.stringOrNull("notes"), recordedAtUtcMillis=o.long("recordedAtUtcMillis"),
        )
    }
    val devices = root.arrayObjects("devices").map { o ->
        DeviceEntity(
            id=o.string("id"), cookId=o.string("cookId"), brand=o.stringOrNull("brand"), model=o.stringOrNull("model"),
            deviceName=o.string("deviceName"), role=o.string("role"), firmwareVersion=o.stringOrNull("firmwareVersion"), createdAtUtcMillis=o.long("createdAtUtcMillis"),
        )
    }
    val probes = root.arrayObjects("probes").map { o ->
        ProbeEntity(
            id=o.string("id"), cookId=o.string("cookId"), deviceId=o.stringOrNull("deviceId"), assignedDishId=o.stringOrNull("assignedDishId"),
            name=o.string("name"), measurementType=o.string("measurementType"), source=o.string("source"), createdAtUtcMillis=o.long("createdAtUtcMillis"),
        )
    }
    val photos = root.arrayObjects("photos").map { o ->
        val id = o.string("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "The backup contains an invalid photo ID." }
        val archivePath = o.stringOrNull("archivePath")
            ?: error("This backup is incomplete: a photo attachment path is missing.")
        require(archivePath.matches(Regex("attachments/${Regex.escape(id)}\\.[A-Za-z0-9]{1,8}"))) {
            "The backup contains an invalid photo attachment path."
        }
        val photoBytes = attachment(id, archivePath)
            ?: error("This backup is incomplete: photo '${o.optString("originalFileName", "")}' is missing.")
        require(photoBytes.isNotEmpty()) { "This backup contains an empty photo attachment." }
        val name = o.string("originalFileName")
        val extension = name.substringAfterLast('.', "jpg").filter(Char::isLetterOrDigit).take(8).ifBlank { "jpg" }
        PhotoEntity(
            id=id, cookId=o.string("cookId"), dishId=o.stringOrNull("dishId"), eventId=o.stringOrNull("eventId"),
            originalFileName=name, relativePath="photos/$id.$extension", mimeType=o.optString("mimeType", "image/jpeg"),
            caption=o.stringOrNull("caption"), capturedAtUtcMillis=o.longOrNull("capturedAtUtcMillis"), addedAtUtcMillis=o.long("addedAtUtcMillis"), temperatureContextJson=validatedContext(o),
        )
    }
    val reminders = root.arrayObjects("reminders").map { o ->
        CookReminderEntity(
            id = o.string("id"), cookId = o.string("cookId"), title = o.string("title"),
            dueAtUtcMillis = o.long("dueAtUtcMillis"), timeZoneId = o.string("timeZoneId"),
            status = o.optString("status", CookReminderEntity.STATUS_PENDING),
            createdAtUtcMillis = o.long("createdAtUtcMillis"), completedAtUtcMillis = o.longOrNull("completedAtUtcMillis"),
        )
    }
    val recordings = root.arrayObjects("recordings").map { o ->
        CookRecordingEntity(o.string("cookId"), o.string("deviceId"), "", CookRecordingEntity.STOPPED,
            o.string("unit"), o.long("startedAtUtcMillis"), o.long("resumedAtUtcMillis"),
            lastReceivedAtUtcMillis = o.longOrNull("lastReceivedAtUtcMillis"), message = "Restored history · attach a grill to record again.",
            samplingMode = o.optString("samplingMode", GrillSamplingMode.PERIODIC.key),
            samplingIntervalMillis = o.optLong("samplingIntervalMillis", 15_000L))
    }
    val assignments = root.arrayObjects("probeAssignments").map { o ->
        ProbeAssignmentEntity(o.string("id"), o.string("cookId"), o.string("probeId"), o.stringOrNull("dishId"),
            o.long("startedAtUtcMillis"), o.longOrNull("endedAtUtcMillis"))
    }
    val companionRecords = root.arrayObjects("companionRecords").map { o ->
        val kind = o.string("kind")
        require(kind in setOf("playbook", "plan", "alert")) { "Unsupported saved guidance in this archive." }
        val payload = o.getJSONObject("payload").toString()
        when (kind) { "plan" -> com.pittech.domain.CookPlanEngine.decode(payload); "alert" -> com.pittech.domain.CookAlertEngine.decode(payload); else -> com.pittech.domain.PlaybookCodec.decode(payload) }
        CompanionRecord(o.string("id"), kind, o.stringOrNull("cookId"), o.string("title"), payload, o.long("created"), o.long("updated"))
    }
    require(companionRecords.map { it.id }.distinct().size == companionRecords.size) { "Duplicate saved guidance IDs." }
    val companionPhotos = root.arrayObjects("companionPhotos").map { o ->
        val id = o.string("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid reference photo ID." }
        val path = o.string("archivePath")
        require(path.matches(Regex("attachments/${Regex.escape(id)}\\.[A-Za-z0-9]{1,8}"))) { "Invalid reference photo path." }
        require(attachment(id, path)?.isNotEmpty() == true) { "A reference photo is missing." }
        CompanionPhoto(id, o.string("recordId"), "photos/${path.substringAfterLast('/')}", o.string("mimeType"), o.string("caption"), o.intOrNull("dishIndex"), o.stringOrNull("action"))
    }
    require(companionRecords.all { it.cookId == null || cooks.any { c -> c.id == it.cookId } } &&
        companionPhotos.all { p -> companionRecords.any { it.id == p.recordId } } &&
        (companionPhotos.map { it.id } + photos.map { it.id }).distinct().size == companionPhotos.size + photos.size) { "Reference records do not match this archive." }
    require(cooks.map { it.id }.distinct().size == cooks.size) { "The backup contains duplicate cook IDs." }
    val cookIds = cooks.map { it.id }.toSet()
    val dishesById = dishes.associateBy { it.id }
    val eventsById = events.associateBy { it.id }
    require(dishes.map { it.id }.distinct().size == dishes.size && events.map { it.id }.distinct().size == events.size) {
        "The backup contains duplicate dish or timeline IDs."
    }
    require(
        ingredients.map { it.id }.distinct().size == ingredients.size &&
            readings.map { it.id }.distinct().size == readings.size &&
            targets.map { it.id }.distinct().size == targets.size &&
            results.map { it.id }.distinct().size == results.size &&
            devices.map { it.id }.distinct().size == devices.size &&
            probes.map { it.id }.distinct().size == probes.size &&
            photos.map { it.id }.distinct().size == photos.size &&
            reminders.map { it.id }.distinct().size == reminders.size &&
            recordings.map { it.cookId }.distinct().size == recordings.size &&
            assignments.map { it.id }.distinct().size == assignments.size
    ) { "The backup contains duplicate record IDs." }
    require(
        dishes.all { it.cookId in cookIds } &&
            ingredients.all { it.cookId in cookIds && dishesById[it.dishId]?.cookId == it.cookId } &&
            events.all { it.cookId in cookIds && (it.dishId == null || dishesById[it.dishId]?.cookId == it.cookId) } &&
            readings.all { it.cookId in cookIds && (it.dishId == null || dishesById[it.dishId]?.cookId == it.cookId) } &&
            targets.all { it.cookId in cookIds && (it.dishId == null || dishesById[it.dishId]?.cookId == it.cookId) } &&
            results.all { it.cookId in cookIds && (it.dishId == null || dishesById[it.dishId]?.cookId == it.cookId) } &&
            devices.all { it.cookId in cookIds } &&
            probes.all { it.cookId in cookIds && (it.deviceId == null || devices.any { device -> device.id == it.deviceId && device.cookId == it.cookId }) && (it.assignedDishId == null || dishesById[it.assignedDishId]?.cookId == it.cookId) } &&
            photos.all { it.cookId in cookIds && (it.dishId == null || dishesById[it.dishId]?.cookId == it.cookId) && (it.eventId == null || eventsById[it.eventId]?.cookId == it.cookId) } &&
            reminders.all { it.cookId in cookIds } &&
            readings.all { it.value.isFinite() && it.timestampBasis in setOf("measurement", "cloud_receipt") && GrillSamplingPolicy.validSampleInterval(it.samplingIntervalMillis) } &&
            recordings.all { it.cookId in cookIds && it.unit in setOf("°F", "°C") &&
                it.samplingMode in GrillSamplingMode.entries.map { mode -> mode.key } && it.samplingIntervalMillis in GrillSamplingPolicy.INTERVALS && devices.any { device -> device.id == it.deviceId && device.cookId == it.cookId } } &&
            assignments.all { it.cookId in cookIds && probes.any { probe -> probe.id == it.probeId && probe.cookId == it.cookId } &&
                (it.dishId == null || dishesById[it.dishId]?.cookId == it.cookId) && (it.endedAtUtcMillis == null || it.endedAtUtcMillis >= it.startedAtUtcMillis) }
    ) {
        "The backup has records that do not belong to a cook in this archive."
    }
    return ExportSnapshot(cooks, dishes, ingredients, events, readings, targets, results, devices, probes, photos, reminders, recordings, assignments, companionRecords, companionPhotos)
}

private fun validatedContext(o: JSONObject): String? = o.stringOrNull("temperatureContextJson")?.also {
    require(TemperatureContext.decode(it).isNotEmpty()) { "The backup contains invalid temperature context." }
}
