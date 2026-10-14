package com.pittech.data

import com.pittech.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PreparedCookPack(val snapshot: ExportSnapshot, val attachments: Map<String, ByteArray>, val metadata: JSONObject)

/** A public-safe projection, built from the selected draft rather than a whole-library backup. */
class CookPackProjection {
    private val identities = mutableMapOf<String, String>()
    private fun alias(value: Any?): Any = if (value is String && value.isNotBlank()) identities.getOrPut(value) { UUID.randomUUID().toString() } else JSONObject.NULL
    private fun known(value: JSONObject, keys: Set<String>) = JSONObject().apply { keys.forEach { if (value.has(it)) put(it, value.get(it)) } }
    private fun objects(value: JSONArray): List<JSONObject> = (0 until value.length()).map { value.getJSONObject(it) }
    private fun measurements(values: JSONArray) = JSONArray(objects(values).map { row ->
        known(row, setOf("probeName", "measurementType", "value", "unit", "measuredAtUtcMillis", "timeZoneId", "source", "samplingIntervalMillis", "qualityStatus")).apply {
            for (key in listOf("id", "sourceReadingId", "probeId")) if (row.has(key)) put(key, alias(row.opt(key)))
        }
    })
    private fun observations(values: JSONArray) = JSONArray(objects(values).map { row ->
        known(row, setOf("occurredAtUtcMillis", "timeZoneId", "title", "details", "action", "offsetMillis")).apply {
            put("sourceEventId", alias(row.opt("sourceEventId")))
            put("recordedTemperatures", measurements(row.optJSONArray("recordedTemperatures") ?: JSONArray()))
        }
    })
    fun story(entry: SharingEntry): JSONObject {
        val raw = JSONObject(entry.storyJson)
        return JSONObject().put("version", 1).put("attemptId", if (entry.includeStory) alias(raw.opt("attemptId")) else JSONObject.NULL)
            .put("anchorUtcMillis", if (entry.includeStory) raw.opt("anchorUtcMillis") ?: JSONObject.NULL else JSONObject.NULL)
            .put("observations", if (entry.includeStory) observations(raw.optJSONArray("observations") ?: JSONArray()) else JSONArray())
            .put("readings", if (entry.includeStory) measurements(raw.getJSONArray("readings")) else JSONArray())
            .put("results", JSONArray(if (entry.includeStory) objects(raw.optJSONArray("results") ?: JSONArray()).map { known(it, setOf("type", "numericValue", "textValue", "unit")) } else emptyList<JSONObject>()))
    }
    fun recipe(entry: SharingEntry, story: JSONObject): RecipeSnapshot {
        val value = JSONObject(RecipeSnapshotCodec.encode(entry.snapshot))
        value.put("observations", story.getJSONArray("observations")).put("smokingAnchorUtcMillis", story.opt("anchorUtcMillis") ?: JSONObject.NULL)
        if (!entry.includeStory) value.put("rating", JSONObject.NULL).put("cookAgain", JSONObject.NULL).put("resultNotes", "")
        value.put("instructions", JSONArray(objects(value.getJSONArray("instructions")).map { instruction ->
            known(instruction, setOf("title", "details", "method", "offsetMillis", "durationMillis")).put("id", alias(instruction.opt("id"))).put("sourceEventId", alias(instruction.opt("sourceEventId")))
        }))
        return RecipeSnapshotCodec.decode(value.toString())
    }
}

suspend fun SharingRepository.prepareCookPack(id: String): PreparedCookPack {
    val row = get(id) ?: error("This saved composition is unavailable.")
    val draft = SharingDraftCodec.decode(row.payload); val selected = draft.entries.filter { it.included }
    require(selected.isNotEmpty()) { "Choose at least one dish to share." }
    val packId = UUID.randomUUID().toString(); val projector = CookPackProjection(); val bytes = linkedMapOf<String, ByteArray>()
    val owned = assets(id).associateBy { it.id }; val recipes = mutableListOf<SavedRecipe>(); val revisions = mutableListOf<RecipeRevision>()
    val recipePhotos = mutableListOf<RecipeAsset>(); val referencePhotos = mutableListOf<CompanionPhoto>()
    var expandedPhotos = 0L; var heroPhotoId: String? = null
    val entries = selected.mapIndexed { index, entry ->
        val recipeId = UUID.randomUUID().toString(); val revisionId = UUID.randomUUID().toString(); val entryId = UUID.randomUUID().toString()
        val story = projector.story(entry); val snapshot = projector.recipe(entry, story)
        recipes += SavedRecipe(recipeId, snapshot.name, snapshot.description, JSONArray(snapshot.tags).toString(), revisionId, row.updatedAtUtcMillis, row.updatedAtUtcMillis)
        revisions += RecipeRevision(revisionId, recipeId, null, 1, null, null, 1, RecipeSnapshotCodec.encode(snapshot), row.updatedAtUtcMillis, "Shared Cook Pack")
        val photos = entry.photoIds.sortedBy { if (it == draft.heroPhotoId) 0 else 1 }.map { sourceId ->
            val source = owned[sourceId]?.takeIf { it.action == entry.id } ?: error("A selected dish photo is unavailable.")
            val derivative = CookPackPhoto.derivative(photoBytes(source.relativePath) ?: error("A selected photo is missing. Your saved composition is unchanged."))
            expandedPhotos += derivative.size.toLong() * 2
            require(expandedPhotos <= CookPackContract.MAX_EXPANDED && bytes.size + 2 <= CookPackContract.MAX_ENTRIES - 32) { "This Cook Pack is too large. Choose fewer photos or dishes." }
            val photoId = UUID.randomUUID().toString(); bytes[photoId] = derivative
            if (sourceId == draft.heroPhotoId) heroPhotoId = photoId
            referencePhotos += CompanionPhoto(photoId, packId, "photos/$photoId.jpg", "image/jpeg", source.caption, index, entryId)
            val assetId = UUID.randomUUID().toString(); bytes[assetId] = derivative
            recipePhotos += RecipeAsset(assetId, revisionId, "photos/$assetId.jpg", "image/jpeg", source.caption, null, row.updatedAtUtcMillis)
            photoId
        }
        SharingEntry(entryId, null, revisionId, snapshot, story.toString(), true, entry.includeStory, photos)
    }
    val kind = if (selected.size == 1 && row.kind != SharingDraftCodec.COLLECTION) "recipe" else "collection"
    val document = SharingDocument(draft.title, entries, heroPhotoId = heroPhotoId, collectionId = packId)
    val record = CompanionRecord(packId, if (kind == "recipe") "recipe_reference" else SharingDraftCodec.COLLECTION, title = draft.title,
        payload = SharingDraftCodec.encode(document), createdAtUtcMillis = row.updatedAtUtcMillis, updatedAtUtcMillis = row.updatedAtUtcMillis)
    val snapshot = ExportSnapshot(cooks = emptyList(), dishes = emptyList(), ingredients = emptyList(), events = emptyList(), readings = emptyList(), targets = emptyList(), results = emptyList(), devices = emptyList(), probes = emptyList(), photos = emptyList(),
        recipes = recipes, recipeRevisions = revisions, recipeAssets = recipePhotos, companionRecords = listOf(record), companionPhotos = referencePhotos)
    RecipeArchive.validate(snapshot)
    val metadata = JSONObject().put("contractVersion", 1).put("id", packId).put("kind", kind).put("recipeRevisionIds", JSONArray(revisions.map { it.id })).put("dishIds", JSONArray())
        .put("includedStory", entries.any { it.includeStory }).put("source", JSONObject.NULL)
    return PreparedCookPack(snapshot, bytes, metadata)
}
