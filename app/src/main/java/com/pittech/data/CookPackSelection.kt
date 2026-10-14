package com.pittech.data

import com.pittech.domain.SharingDraftCodec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Selection happens after validation; copies own reference assets under deterministic local identities. */
fun PitTechDataTransfer.ImportDraft.selectCookPackRecipes(selected: Set<String>): PitTechDataTransfer.ImportDraft {
    val metadata = cookPack ?: error("Choose recipes from a Cook Pack.")
    val available = snapshot.recipes.map { it.currentRevisionId }.toSet()
    require(selected.isNotEmpty() && selected.all { it in available }) { "Choose recipes included in this file." }
    fun identity(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    val recipeIds = snapshot.recipes.filter { it.currentRevisionId in selected }.map { it.id }.toSet()
    val revisions = snapshot.recipeRevisions.filter { it.recipeId in recipeIds }
    val revisionIds = revisions.map { it.id }.toSet()
    val references = mutableListOf<CompanionRecord>(); val photos = mutableListOf<CompanionPhoto>(); val copiedBytes = mutableMapOf<String, ByteArray>()
    snapshot.companionRecords.forEach { record ->
        val document = SharingDraftCodec.decode(record.payload)
        val entries = document.entries.filter { it.sourceRevisionId?.let(selected::contains) == true }
        if (entries.isEmpty()) return@forEach
        val recordId = identity("PitTech Cook Pack selection:${record.id}:${selected.sorted().joinToString(",")}")
        val mappings = entries.flatMap { it.photoIds }.associateWith { identity("PitTech Cook Pack photo:$recordId:$it") }
        val sourcePhotos = snapshot.companionPhotos.filter { it.recordId == record.id && it.id in mappings }
        require(sourcePhotos.size == mappings.size) { "A selected reference photo is missing." }
        sourcePhotos.forEach { photo ->
            val id = mappings.getValue(photo.id)
            photos += photo.copy(id = id, recordId = recordId, relativePath = "photos/$id.jpg")
            copiedBytes[id] = attachments[photo.id] ?: error("A selected photo could not be opened.")
        }
        val scoped = document.copy(title = if (entries.size == 1) entries.single().snapshot.name else document.title,
            entries = entries.map { it.copy(photoIds = it.photoIds.map(mappings::getValue)) }, heroPhotoId = document.heroPhotoId?.let(mappings::get), collectionId = recordId, parentRevisionId = null)
        references += record.copy(id = recordId, kind = if (entries.size == 1) "recipe_reference" else SharingDraftCodec.COLLECTION,
            title = scoped.title, payload = SharingDraftCodec.encode(scoped))
    }
    val assets = snapshot.recipeAssets.filter { it.recipeRevisionId in revisionIds }
    assets.forEach { copiedBytes[it.id] = attachments[it.id] ?: error("A selected recipe photo is missing.") }
    val scope = snapshot.copy(cooks = emptyList(), dishes = emptyList(), ingredients = emptyList(), events = emptyList(), readings = emptyList(), targets = emptyList(), results = emptyList(), devices = emptyList(), probes = emptyList(), photos = emptyList(),
        reminders = emptyList(), recordings = emptyList(), assignments = emptyList(), logReadingLinks = emptyList(), lifecycles = emptyList(), targetHistory = emptyList(), dishRecipeSources = emptyList(), mealPresets = emptyList(), activityTimers = emptyList(), reminderRules = emptyList(), reminderOccurrences = emptyList(),
        recipes = snapshot.recipes.filter { it.id in recipeIds }, recipeRevisions = revisions, recipeAssets = assets, companionRecords = references, companionPhotos = photos)
    RecipeArchive.validate(scope)
    val header = JSONObject(metadata.toString()).put("kind", if (scope.recipes.size == 1) "recipe" else "collection").put("recipeRevisionIds", JSONArray(revisions.map { it.id })).put("dishIds", JSONArray())
        .put("includedStory", references.any { SharingDraftCodec.decode(it.payload).entries.any { entry -> entry.includeStory } })
    return copy(snapshot = scope, attachments = copiedBytes, preferences = null, cookPack = header)
}
