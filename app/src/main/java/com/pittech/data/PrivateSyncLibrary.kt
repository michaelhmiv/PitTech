package com.pittech.data

import androidx.room.withTransaction
import org.json.JSONObject

internal data class PrivateSyncPhotoSource(val bytes: ByteArray, val mime: String)
internal data class PrivateSyncUpsert(val type: String, val id: String, val payload: JSONObject)
internal data class PrivateSyncDelete(val type: String, val id: String)
internal data class PrivateSyncApplyPlan(val upserts: List<PrivateSyncUpsert>, val deletes: List<PrivateSyncDelete>, val attachments: Map<String, ByteArray>)

/** Library boundary for private sync. Build reads the full journal; apply writes one
 * validated merge result. Recording, probe-assignment, timer, occurrence and delivery
 * state are never touched here. */
internal interface PrivateSyncLibrary {
    suspend fun archive(): JSONObject
    suspend fun photoBytes(type: String, id: String): PrivateSyncPhotoSource?
    suspend fun apply(plan: PrivateSyncApplyPlan)
}

internal class PrivateSyncRoomLibrary(private val database: PitTechDatabase, private val repository: CookRepository, private val photos: PhotoStorage) : PrivateSyncLibrary {
    /** Test seam, invoked inside the apply transaction. Null in production. */
    internal var applyInterceptor: (suspend () -> Unit)? = null
    override suspend fun archive(): JSONObject = archiveSnapshotJson(repository.exportSnapshot(), null)
    override suspend fun photoBytes(type: String, id: String): PrivateSyncPhotoSource? {
        return when (type) {
            "photos" -> database.experienceDao().photo(id)?.let { row -> photos.read(row.relativePath)?.let { PrivateSyncPhotoSource(it, row.mimeType) } }
            "recipeAssets" -> database.recipeDao().assets().firstOrNull { it.id == id }?.let { row -> photos.read(row.relativeFilePath)?.let { PrivateSyncPhotoSource(it, row.mimeType) } }
            "companionPhotos" -> database.companionDao().photos().firstOrNull { it.id == id }?.let { row -> photos.read(row.relativePath)?.let { PrivateSyncPhotoSource(it, row.mimeType) } }
            else -> null
        }
    }
    override suspend fun apply(plan: PrivateSyncApplyPlan) {
        plan.upserts.forEach { require(it.payload.optString(keyField(it.type), "") == it.id) { "A synced entry does not match its identity." } }
        val written = mutableListOf<String>()
        // Tombstoned and relocated photo paths must be captured before the transaction deletes them.
        val removed = mutableListOf<String>()
        for (tombstone in plan.deletes.filter { it.type in setOf("photos", "recipeAssets", "companionPhotos") }) photoRowPath(tombstone.type, tombstone.id)?.let { removed += it }
        for (row in plan.upserts.filter { it.id in plan.attachments }) {
            val prior = photoRowPath(row.type, row.id)
            if (prior != null && prior != photoPath(row)) removed += prior
        }
        try {
            for ((id, bytes) in plan.attachments) {
                require(bytes.isNotEmpty() && bytes.size <= PrivateSyncProtocol.MAX_PHOTO_BYTES)
                val row = plan.upserts.firstOrNull { it.id == id && it.type in setOf("photos", "recipeAssets", "companionPhotos") } ?: error("An attachment has no synced entry.")
                val path = photoPath(row)
                photos.writeImported(path, bytes)
                written += path
            }
            database.withTransaction {
                applyDeletes(plan.deletes)
                applyInterceptor?.invoke()
                applyUpserts(plan.upserts, plan.attachments.keys)
            }
        } catch (failure: Throwable) {
            for (path in written) try { photos.delete(path) } catch (_: Exception) { }
            throw failure
        }
        for (path in removed) try { photos.delete(path) } catch (_: Exception) { }
    }
    private suspend fun photoRowPath(type: String, id: String): String? = when (type) {
        "photos" -> database.experienceDao().photo(id)?.relativePath
        "recipeAssets" -> database.recipeDao().assets().firstOrNull { it.id == id }?.relativeFilePath
        "companionPhotos" -> database.companionDao().photos().firstOrNull { it.id == id }?.relativePath
        else -> null
    }
    private fun keyField(type: String) = when (type) { "dishRecipeSources" -> "dishId"; "lifecycles" -> "cookId"; "logReadingLinks" -> "readingId"; else -> "id" }
    private fun photoExtension(mime: String) = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
    private fun photoPath(row: PrivateSyncUpsert) = "photos/${row.id}.${photoExtension(row.payload.optString("mimeType", "image/jpeg"))}"
    private suspend fun applyDeletes(deletes: List<PrivateSyncDelete>) {
        val cook = database.cookDao(); val experience = database.experienceDao(); val recipes = database.recipeDao(); val companion = database.companionDao(); val reminders = database.reminderDao()
        // Children before parents; cascades make repeats harmless.
        deletes.filter { it.type == "logReadingLinks" }.forEach { experience.deleteLinkByReadingId(it.id) }
        deletes.filter { it.type == "photos" }.forEach { cook.deletePhotoById(it.id) }
        deletes.filter { it.type == "recipeAssets" }.forEach { recipes.deleteAssetById(it.id) }
        deletes.filter { it.type == "companionPhotos" }.forEach { companion.deletePhotoById(it.id) }
        deletes.filter { it.type == "dishRecipeSources" }.forEach { recipes.deleteSourceByDishId(it.id) }
        deletes.filter { it.type == "ingredients" }.forEach { cook.deleteIngredientById(it.id) }
        deletes.filter { it.type == "readings" }.forEach { cook.deleteReadingById(it.id) }
        deletes.filter { it.type == "results" }.forEach { cook.deleteResult(it.id) }
        deletes.filter { it.type == "targets" }.forEach { cook.deleteTargetById(it.id) }
        deletes.filter { it.type == "targetHistory" }.forEach { experience.deleteHistoryById(it.id) }
        deletes.filter { it.type == "probes" }.forEach { cook.deleteProbeById(it.id) }
        deletes.filter { it.type == "devices" }.forEach { cook.deleteDeviceById(it.id) }
        deletes.filter { it.type == "events" }.forEach { cook.deleteEventById(it.id) }
        deletes.filter { it.type == "reminderRules" }.forEach { reminders.delete(it.id) }
        deletes.filter { it.type == "companionRecords" }.forEach { companion.delete(it.id) }
        deletes.filter { it.type == "lifecycles" }.forEach { experience.deleteLifecycleByCookId(it.id) }
        deletes.filter { it.type == "dishes" }.forEach { cook.deleteDishById(it.id) }
        deletes.filter { it.type == "recipeRevisions" }.forEach { recipes.deleteRevisionById(it.id) }
        deletes.filter { it.type == "recipes" }.forEach { recipes.deleteRecipeById(it.id) }
        deletes.filter { it.type == "mealPresets" }.forEach { recipes.deletePresetById(it.id) }
        deletes.filter { it.type == "cooks" }.forEach { cook.deleteCookById(it.id) }
    }
    private suspend fun applyUpserts(upserts: List<PrivateSyncUpsert>, relocated: Set<String>) {
        val cook = database.cookDao(); val experience = database.experienceDao(); val recipes = database.recipeDao(); val companion = database.companionDao(); val reminders = database.reminderDao()
        val order = listOf("cooks", "dishes", "recipes", "recipeRevisions", "recipeAssets", "dishRecipeSources", "mealPresets", "devices", "probes", "events", "readings", "ingredients", "targets", "results", "photos", "companionRecords", "companionPhotos", "lifecycles", "targetHistory", "logReadingLinks", "reminderRules")
        require(upserts.all { it.type in order })
        val cooks = cook.getAllCooks().associateBy { it.id }; val dishes = cook.getAllDishes().associateBy { it.id }
        val ingredients = cook.getAllIngredients().associateBy { it.id }; val events = cook.getAllTimelineEvents().associateBy { it.id }
        val readings = cook.getAllSensorReadings().associateBy { it.id }; val targets = cook.getAllTargets().associateBy { it.id }
        val results = cook.getAllResults().associateBy { it.id }; val devices = cook.getAllDevices().associateBy { it.id }
        val probes = cook.getAllProbes().associateBy { it.id }; val photoRows = cook.getAllPhotos().associateBy { it.id }
        val saved = recipes.recipes().associateBy { it.id }; val revisions = recipes.revisions().associateBy { it.id }
        val assets = recipes.assets().associateBy { it.id }; val sources = recipes.sources().associateBy { it.dishId }
        val presets = recipes.presets().associateBy { it.id }; val records = companion.all().associateBy { it.id }
        val recordPhotos = companion.photos().associateBy { it.id }; val links = experience.allLinks().associateBy { it.readingId }
        val lifecycles = experience.allLifecycles().associateBy { it.cookId }; val history = experience.allHistory().associateBy { it.id }
        val rules = reminders.rules().associateBy { it.id }
        for (type in order) for (row in upserts.filter { it.type == type }) {
            val o = row.payload
            when (type) {
                "cooks" -> { val prior = cooks[row.id]; val next = decodeCook(o); if (prior == null) cook.insertCooksIgnoringDuplicates(listOf(next)).also { check(cook.getCook(next.id) != null) } else if (prior != next) cook.updateCook(next) }
                "dishes" -> { val prior = dishes[row.id]; val next = decodeDish(o); if (prior == null) { cook.insertDishesIgnoringDuplicates(listOf(next)); check(cook.getDish(next.id) != null) } else if (prior != next) cook.updateDish(next) }
                "recipes" -> { val prior = saved[row.id]; val next = decodeRecipe(o); if (prior == null) recipes.insertRecipe(next) else if (prior != next) recipes.updateRecipe(next) }
                "recipeRevisions" -> { val prior = revisions[row.id]; val next = decodeRevision(o); if (prior == null) recipes.insertRevision(next) else check(prior == next) { "A recipe version conflicts with saved history." } }
                "recipeAssets" -> { val prior = assets[row.id]; val next = decodeAsset(o, prior, row.id in relocated); if (prior == null) recipes.insertAssets(listOf(next)) else if (prior != next) { recipes.deleteAssetById(next.id); recipes.insertAssets(listOf(next)) } }
                "dishRecipeSources" -> { val prior = sources[row.id]; val next = decodeSource(o); if (prior == null) recipes.insertSource(next) else if (prior != next) { recipes.deleteSourceByDishId(next.dishId); recipes.insertSource(next) } }
                "mealPresets" -> recipes.putPreset(decodePreset(o))
                "devices" -> { val prior = devices[row.id]; val next = decodeDevice(o); if (prior == null) cook.insertDevicesIgnoringDuplicates(listOf(next)) else if (prior != next) cook.updateTelemetryDevice(next) }
                "probes" -> { val prior = probes[row.id]; val next = decodeProbe(o); if (prior == null) cook.insertProbesIgnoringDuplicates(listOf(next)) else if (prior != next) cook.updateProbe(next) }
                "events" -> { val prior = events[row.id]; val next = decodeEvent(o); if (prior == null) { cook.insertEventsIgnoringDuplicates(listOf(next)); check(cook.getTimelineEvent(next.id) != null) } else if (prior != next) cook.updateTimelineEvent(next) }
                "readings" -> { val prior = readings[row.id]; val next = decodeReading(o); if (prior == null) cook.insertReadingsIgnoringDuplicates(listOf(next)) else if (prior != next) cook.updateSensorReading(next) }
                "ingredients" -> cook.saveIngredient(decodeIngredient(o))
                "targets" -> { val prior = targets[row.id]; val next = decodeTarget(o); if (prior == null) cook.insertTargetsIgnoringDuplicates(listOf(next)) else if (prior != next) cook.updateTarget(next) }
                "results" -> cook.upsertResult(decodeResult(o))
                "photos" -> { val prior = photoRows[row.id]; val next = decodePhoto(o, prior, row.id in relocated); if (prior == null) { cook.insertPhotosIgnoringDuplicates(listOf(next)); check(experience.photo(next.id) != null) } else if (prior != next) experience.updatePhoto(next) }
                "companionRecords" -> companion.put(decodeRecord(o))
                "companionPhotos" -> { val prior = recordPhotos[row.id]; val next = decodeRecordPhoto(o, prior, row.id in relocated); if (prior == null) companion.putPhotos(listOf(next)) else if (prior != next) { companion.deletePhotoById(next.id); companion.putPhotos(listOf(next)) } }
                "lifecycles" -> experience.putLifecycle(decodeLifecycle(o))
                "targetHistory" -> { val prior = history[row.id]; val next = decodeHistory(o); if (prior == null) experience.putHistory(listOf(next)) else check(prior == next) { "Target history conflicts with saved history." } }
                "logReadingLinks" -> { if (links[row.id] == null) experience.linkReadings(listOf(decodeLink(o))) else check(links[row.id] == decodeLink(o)) { "A note temperature conflicts with saved history." } }
                "reminderRules" -> reminders.putRule(decodeRule(o, rules[row.id]))
            }
        }
    }
    private fun JSONObject.text(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.long(key: String) = getLong(key)
    private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
    private fun JSONObject.double(key: String) = getDouble(key)
    private fun JSONObject.doubleOrNull(key: String) = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.intOrNull(key: String) = if (isNull(key)) null else getInt(key)
    private fun JSONObject.booleanOrNull(key: String) = if (isNull(key)) null else getBoolean(key)
    private fun decodeCook(o: JSONObject) = CookEntity(o.getString("id"), o.getString("title"), o.getString("status"), o.long("startedAtUtcMillis"), o.getString("startedTimeZoneId"), o.longOrNull("endedAtUtcMillis"), o.text("smokerName"), o.doubleOrNull("initialSetpointValue"), o.text("initialSetpointUnit"), o.text("notes"), o.long("createdAtUtcMillis"), o.long("updatedAtUtcMillis"), o.text("fuelType"), o.text("woodOrPelletBlend"), o.doubleOrNull("outdoorTemperatureValue"), o.text("outdoorTemperatureUnit"), o.text("weatherNotes"), o.text("windNotes"))
    private fun decodeDish(o: JSONObject) = DishEntity(o.getString("id"), o.getString("cookId"), o.getString("name"), o.getString("foodType"), o.text("cut"), o.doubleOrNull("weightValue"), o.text("weightUnit"), o.intOrNull("pieceCount"), o.text("startingCondition"), o.booleanOrNull("boneIn"), o.text("placement"), o.text("prepNotes"), o.long("createdAtUtcMillis"), o.long("updatedAtUtcMillis"), o.text("gradeOrSource"), o.text("thicknessNotes"))
    private fun decodeIngredient(o: JSONObject) = IngredientEntity(o.getString("id"), o.getString("cookId"), o.getString("dishId"), o.getString("stage"), o.getString("name"), o.text("brand"), o.doubleOrNull("amountValue"), o.text("amountUnit"), o.longOrNull("appliedAtUtcMillis"), o.text("notes"), o.long("createdAtUtcMillis"), o.text("amountText"))
    private fun decodeEvent(o: JSONObject) = TimelineEventEntity(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.getString("eventType"), o.getString("title"), o.text("details"), o.long("occurredAtUtcMillis"), o.long("recordedAtUtcMillis"), o.getString("timeZoneId"), o.getString("source"), o.text("sourceDeviceId"), o.long("createdAtUtcMillis"), o.long("updatedAtUtcMillis"), o.text("temperatureContextJson"), o.text("noteMetadataJson")?.also { com.pittech.domain.CookNoteMetadata.decode(it) })
    private fun decodeReading(o: JSONObject) = SensorReadingEntity(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.text("probeId"), o.getString("probeName"), o.getString("measurementType"), o.double("value"), o.getString("unit"), o.long("measuredAtUtcMillis"), o.getString("timeZoneId"), o.getString("source"), o.text("sourceDeviceId"), o.optString("qualityStatus", "valid"), o.long("recordedAtUtcMillis"), o.optString("timestampBasis", "measurement"), o.optLong("samplingIntervalMillis", 15_000L))
    private fun decodeTarget(o: JSONObject) = TargetEntity(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.getString("targetType"), o.double("value"), o.getString("unit"), o.getString("scope"), o.text("explanation"), o.text("sourceReference"), o.long("createdAtUtcMillis"))
    private fun decodeResult(o: JSONObject) = CookResultEntity(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.getString("resultType"), o.doubleOrNull("numericValue"), o.text("unit"), o.text("textValue"), o.text("notes"), o.long("recordedAtUtcMillis"))
    private fun decodeDevice(o: JSONObject) = DeviceEntity(o.getString("id"), o.getString("cookId"), o.text("brand"), o.text("model"), o.getString("deviceName"), o.getString("role"), o.text("firmwareVersion"), o.long("createdAtUtcMillis"))
    private fun decodeProbe(o: JSONObject) = ProbeEntity(o.getString("id"), o.getString("cookId"), o.text("deviceId"), o.text("assignedDishId"), o.getString("name"), o.getString("measurementType"), o.getString("source"), o.long("createdAtUtcMillis"))
    private fun decodePhoto(o: JSONObject, prior: PhotoEntity?, relocated: Boolean = false) = PhotoEntity(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.text("eventId"), prior?.originalFileName ?: (o.getString("id") + "." + photoExtension(o.optString("mimeType", "image/jpeg"))), if (prior != null && !relocated) prior.relativePath else photoPath(PrivateSyncUpsert("photos", o.getString("id"), o)), o.getString("mimeType"), o.text("caption"), o.longOrNull("capturedAtUtcMillis"), o.long("addedAtUtcMillis"), o.text("temperatureContextJson"))
    private fun decodeRecipe(o: JSONObject) = SavedRecipe(o.getString("id"), o.getString("name"), o.getString("description"), o.getString("tagsJson"), o.getString("currentRevisionId"), o.long("createdAtUtcMillis"), o.long("updatedAtUtcMillis"), o.longOrNull("archivedAtUtcMillis"), o.getBoolean("favorite"))
    private fun decodeRevision(o: JSONObject) = RecipeRevision(o.getString("id"), o.getString("recipeId"), o.text("parentRevisionId"), o.getInt("revisionNumber"), o.text("sourceCookId"), o.text("sourceDishId"), o.getInt("snapshotSchemaVersion"), o.getString("snapshotJson"), o.long("createdAtUtcMillis"), o.getString("changeNotes"))
    private fun decodeAsset(o: JSONObject, prior: RecipeAsset?, relocated: Boolean = false) = RecipeAsset(o.getString("id"), o.getString("recipeRevisionId"), if (prior != null && !relocated) prior.relativeFilePath else photoPath(PrivateSyncUpsert("recipeAssets", o.getString("id"), o)), o.getString("mimeType"), o.getString("caption"), o.text("sourcePhotoId"), o.long("createdAtUtcMillis"))
    private fun decodeSource(o: JSONObject) = DishRecipeSource(o.getString("dishId"), o.getString("recipeId"), o.getString("recipeRevisionId"), o.long("appliedAtUtcMillis"))
    private fun decodePreset(o: JSONObject) = MealPreset(o.getString("id"), o.getString("name"), o.getString("itemsJson"), o.long("createdAtUtcMillis"), o.long("updatedAtUtcMillis"))
    private fun decodeRecord(o: JSONObject): CompanionRecord {
        val kind = o.getString("kind")
        val payload = o.getJSONObject("payload").toString()
        when (kind) { "guide_presentation" -> GuidePresentation.decode(payload); "sharing_draft", "collection_revision", "recipe_reference" -> com.pittech.domain.SharingDraftCodec.decode(payload); "recipe_attempt" -> RecipeAttemptCodec.snapshot(payload); "plan" -> com.pittech.domain.CookPlanEngine.decode(payload); "alert" -> com.pittech.domain.CookAlertEngine.decode(payload); "upcoming", "serve_goal" -> com.pittech.domain.ServeTimePlanner.decode(payload); "reference" -> com.pittech.domain.CookReferenceCodec.decode(payload); "prep_combo" -> com.pittech.domain.CookPreparation.decodeCombo(payload); "checklist" -> com.pittech.domain.CookPreparation.decodeChecklist(payload); "equipment" -> com.pittech.domain.CookPreparation.decodeEquipment(payload); "fuel" -> com.pittech.domain.CookPreparation.decodeFuel(payload); else -> com.pittech.domain.PlaybookCodec.decode(payload) }
        return CompanionRecord(o.getString("id"), kind, o.text("cookId"), o.getString("title"), payload, o.long("created"), o.long("updated"))
    }
    private fun decodeRecordPhoto(o: JSONObject, prior: CompanionPhoto?, relocated: Boolean = false) = CompanionPhoto(o.getString("id"), o.getString("recordId"), if (prior != null && !relocated) prior.relativePath else photoPath(PrivateSyncUpsert("companionPhotos", o.getString("id"), o)), o.getString("mimeType"), o.getString("caption"), o.intOrNull("dishIndex"), o.text("action"))
    private fun decodeLifecycle(o: JSONObject) = CookLifecycle(o.getString("cookId"), o.getString("timingMode"))
    private fun decodeHistory(o: JSONObject) = TargetHistory(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.getString("targetType"), o.double("value"), o.getString("unit"), o.long("effectiveAt"))
    private fun decodeLink(o: JSONObject) = LogReadingLink(o.getString("readingId"), o.getString("eventId"))
    /** Imported definitions arrive inactive and create no delivery state. Existing local
     * rules keep their own activation; enabling one is an explicit local adoption. */
    private fun decodeRule(o: JSONObject, prior: ReminderRule?): ReminderRule {
        require(o.getInt("schemaVersion") == 1 && o.getString("title").isNotBlank())
        require(com.pittech.domain.ReminderTriggerCodec.kind(com.pittech.domain.ReminderTriggerCodec.decode(o.getString("triggerConfig"))) == o.getString("kind")) { "Invalid reminder trigger." }
        return ReminderRule(o.getString("id"), o.getString("cookId"), o.text("dishId"), o.getString("title"), o.getString("kind"), o.getString("triggerConfig"), o.text("sourceRecipeRevisionId"), o.text("sourceMilestoneId"), prior?.enabled ?: false, prior?.status ?: "inactive", o.long("createdAtUtcMillis"), o.long("updatedAtUtcMillis"), o.getInt("schemaVersion"))
    }
}
