package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Reuses versioned companion documents and their owned attachments; no parallel result tables. */
class SharingRepository(private val database: PitTechDatabase, private val photos: PhotoStorage, private val cooks: CookRepository) {
    private val dao = database.companionDao()
    fun observeAll() = dao.observeAll()
    suspend fun get(id: String) = dao.get(id)?.takeIf { it.kind in setOf(SharingDraftCodec.DRAFT, SharingDraftCodec.COLLECTION) }
    suspend fun assets(id: String) = dao.photosFor(id)
    fun photoUri(path: String) = photos.uri(path).toString()
    suspend fun photoBytes(path: String) = photos.read(path)

    suspend fun fromRecipe(revisionId: String): String {
        val revision = database.recipeDao().revision(revisionId) ?: error("Recipe version unavailable.")
        val snapshot = RecipeSnapshotCodec.decode(revision.snapshotJson)
        val entry = SharingEntry(sourceDishId = revision.sourceDishId, sourceRevisionId = revision.id,
            snapshot = snapshot, storyJson = cooks.guide.pin(revision, snapshot).getJSONObject("story").toString())
        val id = UUID.randomUUID().toString(); val copies = mutableListOf<CompanionPhoto>()
        try {
            database.recipeDao().assetsFor(revisionId).forEach { asset ->
                val photoId = UUID.randomUUID().toString(); val path = "photos/$photoId.${asset.relativeFilePath.substringAfterLast('.')}"
                photos.writeImported(path, photos.read(asset.relativeFilePath) ?: error("A recipe photo is unavailable."))
                copies += CompanionPhoto(photoId, id, path, asset.mimeType, asset.caption, 0, entry.id)
            }
            val document = SharingDocument(snapshot.name.take(160), listOf(entry)); val now = System.currentTimeMillis()
            database.withTransaction {
                dao.put(CompanionRecord(id, SharingDraftCodec.DRAFT, title = document.title,
                    payload = SharingDraftCodec.encode(document), createdAtUtcMillis = now, updatedAtUtcMillis = now))
                dao.putPhotos(copies)
            }
            return id
        } catch (failure: Throwable) { copies.forEach { photos.delete(it.relativePath) }; throw failure }
    }

    internal suspend fun saveOwnedPublicationDraft(draft: OwnedPublicationDraft) {
        require(get(draft.id)==null);SharingDraftCodec.validate(draft.document);val copied=mutableListOf<CompanionPhoto>()
        try {
            for(photo in draft.photos) {
                val path="photos/${photo.id}.jpg";photos.writeImported(path,photo.bytes)
                copied+=CompanionPhoto(photo.id,draft.id,path,"image/jpeg",photo.caption,draft.document.entries.indexOfFirst { it.id==photo.entryId },photo.entryId)
            }
            val now=System.currentTimeMillis()
            database.withTransaction { require(get(draft.id)==null);dao.put(CompanionRecord(draft.id,SharingDraftCodec.DRAFT,title=draft.document.title,payload=SharingDraftCodec.encode(draft.document),createdAtUtcMillis=now,updatedAtUtcMillis=now));dao.putPhotos(copied) }
        } catch(e: Throwable) { copied.forEach { photos.delete(it.relativePath) };throw e }
    }

    suspend fun fromMeal(cookId: String, dishIds: Set<String>): String {
        val meal = cooks.observeCookDetail(cookId).first() ?: error("This meal is unavailable.")
        require(dishIds.isNotEmpty() && dishIds.all { id -> meal.dishes.any { it.id == id } }) { "Choose dishes from this meal." }
        val id = UUID.randomUUID().toString(); val copies = mutableListOf<CompanionPhoto>(); val photoSources = mutableListOf<Pair<PhotoEntity, CompanionPhoto>>()
        try {
            val entries = meal.dishes.filter { it.id in dishIds }.mapIndexed { index, dish ->
                val extraction = RecipeExtraction.fromMeal(meal, dish.id)
                val working = cooks.recipeAttempt(dish.id)
                val recipe = if (working == null) extraction.snapshot else RecipeExtraction.mergeAttempt(working, extraction.snapshot)
                val source = database.recipeDao().source(dish.id)
                val entry = SharingEntry(sourceDishId = dish.id, sourceRevisionId = source?.recipeRevisionId, snapshot = recipe, storyJson = story(meal, dish.id))
                meal.photos.filter { it.id in extraction.photoIds }.forEach { sourcePhoto ->
                    val photoId = UUID.randomUUID().toString(); val path = "photos/$photoId.${sourcePhoto.relativePath.substringAfterLast('.')}"
                    photos.writeImported(path, photos.read(sourcePhoto.relativePath) ?: error("A source photo is unavailable. Your meal is unchanged."))
                    val owned = CompanionPhoto(photoId, id, path, sourcePhoto.mimeType, sourcePhoto.caption.orEmpty(), index, entry.id)
                    copies += owned; photoSources += sourcePhoto to owned
                }
                entry
            }
            val history = CookPackHistory.freeze(cooks.exportSnapshot(cookId), dishIds, photoSources)
            val document = SharingDocument(meal.cook.title.take(160), entries, cookId, sourceHistoryJson = history, sessionJson = sessionCapture(meal))
            val now = System.currentTimeMillis()
            database.withTransaction {
                dao.put(CompanionRecord(id, SharingDraftCodec.DRAFT, title = document.title, payload = SharingDraftCodec.encode(document), createdAtUtcMillis = now, updatedAtUtcMillis = now))
                dao.putPhotos(copies)
            }
            return id
        } catch (failure: Throwable) { copies.forEach { photos.delete(it.relativePath) }; throw failure }
    }

    suspend fun save(id: String, value: SharingDocument, expectedUpdatedAt: Long): CompanionRecord = database.withTransaction {
        val previous = get(id) ?: error("This sharing draft is unavailable.")
        require(previous.kind == SharingDraftCodec.DRAFT && previous.updatedAtUtcMillis == expectedUpdatedAt) { "This draft changed. Reopen its saved version." }
        val before = SharingDraftCodec.decode(previous.payload)
        require(value.sourceHistoryJson == null && before.sourceHistoryJson == null || value.sourceHistoryJson != null && before.sourceHistoryJson != null && SharingDraftCodec.sameStory(value.sourceHistoryJson, before.sourceHistoryJson)) { "Recorded meal facts cannot be edited as instructions." }
        require(value.sessionJson == null && before.sessionJson == null || value.sessionJson != null && before.sessionJson != null && SharingDraftCodec.sameStory(value.sessionJson, before.sessionJson)) { "Recorded session facts cannot be edited as instructions." }
        require(value.entries.all { e -> before.entries.firstOrNull { it.id == e.id }?.storyJson?.let { SharingDraftCodec.sameStory(it, e.storyJson) } == true }) { "Recorded source facts cannot be edited as instructions." }
        val owned = assets(id)
        require(value.entries.all { entry -> entry.photoIds.all { photoId -> owned.any { it.id == photoId && it.action == entry.id } } }) { "Choose photos owned by this draft's dish." }
        previous.copy(title = value.title, payload = SharingDraftCodec.encode(value), updatedAtUtcMillis = maxOf(System.currentTimeMillis(), previous.updatedAtUtcMillis + 1)).also { dao.put(it) }
    }

    suspend fun saveCollection(id: String): String {
        val draft = get(id) ?: error("Draft unavailable."); val value = SharingDraftCodec.decode(draft.payload)
        require(value.entries.any { it.included }) { "Select at least one dish." }
        return copyDocument(draft, value.copy(entries = value.entries.filter { it.included }, collectionId = value.collectionId ?: UUID.randomUUID().toString()), SharingDraftCodec.COLLECTION)
    }
    suspend fun duplicate(id: String): String {
        val source = get(id) ?: error("Saved composition unavailable.")
        val value = SharingDraftCodec.decode(source.payload)
        return copyDocument(source, value.copy(parentRevisionId = if (source.kind == SharingDraftCodec.COLLECTION) id else value.parentRevisionId), SharingDraftCodec.DRAFT)
    }
    private suspend fun copyDocument(source: CompanionRecord, value: SharingDocument, kind: String): String {
        val id = UUID.randomUUID().toString(); val copies = mutableListOf<CompanionPhoto>(); val mapping = mutableMapOf<String, String>()
        try {
            val entryIds = value.entries.map { it.id }.toSet()
            assets(source.id).filter { it.action in entryIds }.forEach { asset ->
                val newId = UUID.randomUUID().toString(); mapping[asset.id] = newId; val path = "photos/$newId.${asset.relativePath.substringAfterLast('.')}"
                photos.writeImported(path, photos.read(asset.relativePath) ?: error("A saved reference photo is unavailable."))
                copies += asset.copy(id = newId, recordId = id, relativePath = path)
            }
            val copied = value.copy(entries = value.entries.map { it.copy(photoIds = it.photoIds.map { old -> mapping[old] ?: error("Reference photo unavailable.") }) }, heroPhotoId = value.heroPhotoId?.let(mapping::get), sourceHistoryJson = CookPackHistory.remapOwnedPhotos(value.sourceHistoryJson, mapping, value.entries.mapNotNull { it.sourceDishId }.toSet()))
            val now = System.currentTimeMillis()
            database.withTransaction { dao.put(CompanionRecord(id, kind, title = copied.title, payload = SharingDraftCodec.encode(copied), createdAtUtcMillis = now, updatedAtUtcMillis = now)); dao.putPhotos(copies) }
            return id
        } catch (failure: Throwable) { copies.forEach { photos.delete(it.relativePath) }; throw failure }
    }
    suspend fun discard(id: String) {
        val row = get(id) ?: return; require(row.kind == SharingDraftCodec.DRAFT)
        val owned = assets(id); database.withTransaction { dao.delete(id) }; owned.forEach { photos.delete(it.relativePath) }
    }

    suspend fun addRecipe(id: String, revisionId: String, expectedUpdatedAt: Long): CompanionRecord {
        val before = get(id) ?: error("Draft unavailable.")
        require(before.kind == SharingDraftCodec.DRAFT && before.updatedAtUtcMillis == expectedUpdatedAt) { "This draft changed. Reopen it." }
        val revision = database.recipeDao().revision(revisionId) ?: error("Recipe version unavailable.")
        val snapshot = RecipeSnapshotCodec.decode(revision.snapshotJson)
        val story = cooks.guide.pin(revision, snapshot).getJSONObject("story").toString()
        val entry = SharingEntry(sourceDishId = revision.sourceDishId, sourceRevisionId = revision.id, snapshot = snapshot, storyJson = story)
        val document = SharingDraftCodec.decode(before.payload); val copies = mutableListOf<CompanionPhoto>()
        try {
            database.recipeDao().assetsFor(revisionId).forEach { asset ->
                val newId = UUID.randomUUID().toString(); val path = "photos/$newId.${asset.relativeFilePath.substringAfterLast('.')}"
                photos.writeImported(path, photos.read(asset.relativeFilePath) ?: error("A recipe photo is unavailable."))
                copies += CompanionPhoto(newId, id, path, asset.mimeType, asset.caption, document.entries.size, entry.id)
            }
            val value = document.copy(entries = document.entries + entry)
            return database.withTransaction {
                require(dao.get(id)?.updatedAtUtcMillis == expectedUpdatedAt) { "This draft changed. Reopen it." }
                dao.putPhotos(copies)
                before.copy(payload = SharingDraftCodec.encode(value), updatedAtUtcMillis = maxOf(System.currentTimeMillis(), expectedUpdatedAt + 1)).also { dao.put(it) }
            }
        } catch (failure: Throwable) { copies.forEach { photos.delete(it.relativePath) }; throw failure }
    }

    suspend fun editEntry(id: String, entryId: String, snapshot: RecipeSnapshot, addedUris: List<String>, captions: Map<String, String>, expectedUpdatedAt: Long): CompanionRecord {
        val previous = get(id) ?: error("Draft unavailable."); val document = SharingDraftCodec.decode(previous.payload)
        require(document.entries.any { it.id == entryId }) { "Dish unavailable." }
        val copies = mutableListOf<CompanionPhoto>(); val index = document.entries.indexOfFirst { it.id == entryId }
        try {
            addedUris.forEach { uri ->
                val photo = photos.copyIntoLibrary(uri, id, null, System.currentTimeMillis())
                copies += CompanionPhoto(photo.id, id, photo.relativePath, photo.mimeType, captions[uri].orEmpty(), index, entryId)
            }
            val updated = document.copy(entries = document.entries.map { if (it.id == entryId) it.copy(snapshot = snapshot, photoIds = it.photoIds + copies.map { p -> p.id }) else it })
            return database.withTransaction { dao.putPhotos(copies); save(id, updated, expectedUpdatedAt) }
        } catch (failure: Throwable) { copies.forEach { photos.delete(it.relativePath) }; throw failure }
    }

    /**
     * Publishable Cooks (issue #250): the recorded session facts a cook
     * publication carries, captured from the cook itself plus the serve plan
     * that started it (its planned serve time), when one exists. Everything
     * is the cook's own recorded data — nothing is asked of the author.
     */
    private suspend fun sessionCapture(meal: CookDetailData): String {
        val cook = meal.cook
        val serveAt = dao.observeAll().first().asSequence().filter { it.kind == "upcoming" }
            .mapNotNull { record -> runCatching { ServeTimePlanner.decode(record.payload) }.getOrNull() }
            .firstOrNull { it.startedCookId == cook.id }?.serveAt
        fun text(value: String?, limit: Int) = value?.trim()?.takeIf { it.isNotEmpty() }?.take(limit) ?: JSONObject.NULL
        return JSONObject().put("startedAtUtcMillis", cook.startedAtUtcMillis)
            .put("endedAtUtcMillis", cook.endedAtUtcMillis ?: JSONObject.NULL)
            .put("smokerName", text(cook.smokerName, 160)).put("fuelType", text(cook.fuelType, 160)).put("woodOrPelletBlend", text(cook.woodOrPelletBlend, 160))
            .put("setpointValue", cook.initialSetpointValue ?: JSONObject.NULL).put("setpointUnit", text(cook.initialSetpointUnit, 16))
            .put("outdoorTemperatureValue", cook.outdoorTemperatureValue ?: JSONObject.NULL).put("outdoorTemperatureUnit", text(cook.outdoorTemperatureUnit, 16))
            .put("weatherNotes", (cook.weatherNotes ?: "").take(2000)).put("windNotes", (cook.windNotes ?: "").take(2000)).put("notes", (cook.notes ?: "").take(2000))
            .put("serveAtUtcMillis", serveAt ?: JSONObject.NULL).toString()
    }

    internal fun story(meal: CookDetailData, dishId: String): String {
        val scoped = notePlanEvents(meal.events).filter { it.dishId == null || it.dishId == dishId }.sortedBy { it.at }
        val anchor = scoped.firstOrNull { it.action == "food_on" }?.at
        val source = RecipeExtraction.fromMeal(meal, dishId).snapshot
        val readings = meal.readings.filter { it.dishId == dishId || it.dishId == null && it.measurementType in setOf("pit", "pit_ambient", "setpoint", "grill_temperature", "pit_temperature", "pit_probe", "ambient_temperature") }
        return JSONObject().put("version", 1).put("attemptId", dishId).put("anchorUtcMillis", anchor ?: JSONObject.NULL)
            .put("connectionGaps", JSONArray(meal.events.filter { it.eventType == "connection_gap" && (it.dishId == null || it.dishId == dishId) }.map { it.occurredAtUtcMillis }))
            .put("observations", JSONObject(RecipeSnapshotCodec.encode(source)).getJSONArray("observations"))
            .put("readings", JSONArray(readings.map { r -> JSONObject().put("id", r.id).put("probeId", r.probeId ?: JSONObject.NULL)
                .put("probeName", r.probeName).put("measurementType", r.measurementType).put("value", r.value).put("unit", r.unit)
                .put("measuredAtUtcMillis", r.measuredAtUtcMillis).put("timeZoneId", r.timeZoneId).put("timestampBasis", r.timestampBasis).put("samplingIntervalMillis", r.samplingIntervalMillis)
                .put("qualityStatus", r.qualityStatus).put("source", if (r.source == "manual") "manual" else "recorded") }))
            .put("results", JSONArray(meal.results.filter { it.dishId == dishId }.map { r -> JSONObject().put("type", r.resultType)
                .put("numericValue", r.numericValue ?: JSONObject.NULL).put("textValue", r.textValue ?: JSONObject.NULL).put("unit", r.unit ?: JSONObject.NULL) })).toString()
    }
}
