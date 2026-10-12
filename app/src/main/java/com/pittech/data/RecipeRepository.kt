package com.pittech.data

import androidx.room.withTransaction
import com.pittech.domain.*
import org.json.JSONArray
import java.util.UUID

class RecipeRepository(private val database: PitTechDatabase, private val photoStorage: PhotoStorage) {
    private val dao = database.recipeDao()
    fun observeRecipes() = dao.observeRecipes()
    suspend fun get(id: String) = dao.recipe(id)
    suspend fun revision(id: String) = dao.revision(id)
    suspend fun history(id: String) = dao.history(id)
    suspend fun assets(id: String) = dao.assetsFor(id)
    suspend fun source(dishId: String) = dao.source(dishId)

    /** expectedRevisionId prevents a stale editor from overwriting a newer preferred version. */
    suspend fun save(snapshot: RecipeSnapshot, recipeId: String? = null, expectedRevisionId: String? = null,
        sourceCookId: String? = null, sourceDishId: String? = null, changeNotes: String = "",
        photoIds: Set<String> = emptySet(), retainAssetsFromRevisionId: String? = expectedRevisionId): SavedRecipe {
        val payload = RecipeSnapshotCodec.encode(snapshot)
        val now = System.currentTimeMillis()
        val id = recipeId ?: UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val copies = mutableListOf<RecipeAsset>()
        try {
            val sourcePhotos = sourceCookId?.let { database.cookDao().getPhotosForCook(it) }.orEmpty()
            require(photoIds.all { key -> sourcePhotos.any { it.id == key } }) { "Choose photos from this meal." }
            suspend fun copy(path: String, mime: String, caption: String, sourcePhoto: String?) {
                val assetId = UUID.randomUUID().toString()
                val extension = path.substringAfterLast('.').filter(Char::isLetterOrDigit).take(8).ifBlank { "jpg" }
                val destination = "photos/$assetId.$extension"
                // Track the path before writing, so a failed file write also gets cleaned up.
                copies += RecipeAsset(assetId, revisionId, destination, mime, caption, sourcePhoto, now)
                photoStorage.writeImported(destination, photoStorage.read(path) ?: error("A reference photo is unavailable."))
            }
            sourcePhotos.filter { it.id in photoIds }.forEach { copy(it.relativePath, it.mimeType, it.caption.orEmpty(), it.id) }
            retainAssetsFromRevisionId?.let { key ->
                require(dao.revision(key) != null) { "The source recipe revision is unavailable." }
                dao.assetsFor(key).filter { it.sourcePhotoId !in photoIds }.forEach { copy(it.relativeFilePath, it.mimeType, it.caption, it.sourcePhotoId) }
            }
            return database.withTransaction {
                val previous = recipeId?.let { dao.recipe(it) ?: error("This recipe is unavailable.") }
                require(previous == null || previous.currentRevisionId == expectedRevisionId) { "This recipe changed while you were editing. Open its latest version and try again." }
                val prior = previous?.let { dao.revision(it.currentRevisionId) ?: error("The previous recipe version is unavailable.") }
                val saved = SavedRecipe(id, snapshot.name.trim(), snapshot.description, JSONArray(snapshot.tags).toString(), revisionId,
                    previous?.createdAtUtcMillis ?: now, now, previous?.archivedAtUtcMillis, previous?.favorite ?: false)
                if (previous == null) dao.insertRecipe(saved)
                dao.insertRevision(RecipeRevision(revisionId, id, prior?.id, (prior?.revisionNumber ?: 0) + 1,
                    sourceCookId ?: prior?.sourceCookId, sourceDishId ?: prior?.sourceDishId, snapshot.schemaVersion, payload, now, changeNotes))
                dao.insertAssets(copies)
                if (previous != null) dao.updateRecipe(saved)
                saved
            }
        } catch (failure: Throwable) { copies.forEach { photoStorage.delete(it.relativeFilePath) }; throw failure }
    }

    suspend fun duplicate(recipeId: String, name: String): SavedRecipe {
        val recipe = dao.recipe(recipeId) ?: error("This recipe is unavailable.")
        val revision = dao.revision(recipe.currentRevisionId) ?: error("This version is unavailable.")
        return save(RecipeSnapshotCodec.decode(revision.snapshotJson).copy(name = name), sourceCookId = revision.sourceCookId,
            sourceDishId = revision.sourceDishId, retainAssetsFromRevisionId = revision.id)
    }
    suspend fun archive(id: String) = database.withTransaction {
        dao.recipe(id)?.let { dao.updateRecipe(it.copy(archivedAtUtcMillis = System.currentTimeMillis(), updatedAtUtcMillis = System.currentTimeMillis())) }
    }
    suspend fun setFavorite(id: String, favorite: Boolean) = database.withTransaction {
        dao.recipe(id)?.let { dao.updateRecipe(it.copy(favorite = favorite, updatedAtUtcMillis = System.currentTimeMillis())) }
    }
    suspend fun link(dishId: String, recipeId: String, revisionId: String) = database.withTransaction {
        require(database.cookDao().getDish(dishId) != null && dao.revision(revisionId)?.recipeId == recipeId) { "Choose an available dish and recipe revision." }
        dao.insertSource(DishRecipeSource(dishId, recipeId, revisionId, System.currentTimeMillis()))
    }

    internal data class Restore(val recipes: List<SavedRecipe>, val revisions: List<RecipeRevision>,
        val assets: List<RecipeAsset>, val presets: List<MealPreset>)

    internal suspend fun prepareRestore(s: ExportSnapshot): Restore {
        val existingRecipes = dao.recipes().associateBy { it.id }
        val existingRevisions = dao.revisions().associateBy { it.id }
        val existingAssets = dao.assets().associateBy { it.id }
        val existingPresets = dao.presets().associateBy { it.id }
        s.recipeRevisions.forEach { revision ->
            require(existingRevisions[revision.id]?.let { it == revision } != false) { "A recipe revision conflicts with saved history." }
            require(existingRevisions.values.none { it.recipeId == revision.recipeId && it.revisionNumber == revision.revisionNumber && it.id != revision.id }) { "A recipe version number conflicts with saved history." }
        }
        s.recipeAssets.forEach { asset -> require(existingAssets[asset.id]?.let { it == asset } != false) { "A recipe photo conflicts with saved history." } }
        val otherPhotos = database.cookDao().getAllPhotos().map { it.id }.toSet() + database.companionDao().photos().map { it.id }
        require(s.recipeAssets.none { it.id in otherPhotos }) { "A recipe photo conflicts with an existing attachment." }
        require(s.photos.none { it.id in existingAssets } && s.companionPhotos.none { it.id in existingAssets }) { "An attachment conflicts with a saved recipe photo." }
        s.mealPresets.forEach { preset -> require(existingPresets[preset.id]?.let { it == preset } != false) { "A meal preset conflicts with existing data." } }
        return Restore(s.recipes.filter { it.id !in existingRecipes }, s.recipeRevisions.filter { it.id !in existingRevisions },
            s.recipeAssets.filter { it.id !in existingAssets }, s.mealPresets.filter { it.id !in existingPresets })
    }
    internal suspend fun writeRestoreAssets(restore: Restore, attachments: Map<String, ByteArray>) {
        restore.assets.forEach { asset -> photoStorage.writeImported(asset.relativeFilePath,
            attachments[asset.id]?.takeIf { it.isNotEmpty() } ?: error("A recipe attachment is missing.")) }
    }
    internal suspend fun cleanupRestore(restore: Restore) { restore.assets.forEach { photoStorage.delete(it.relativeFilePath) } }
    internal suspend fun restore(restore: Restore, sources: List<DishRecipeSource>) {
        restore.recipes.forEach { dao.insertRecipe(it) }
        restore.revisions.forEach { dao.insertRevision(it) }
        dao.insertAssets(restore.assets)
        restore.presets.forEach { dao.putPreset(it) }
        sources.forEach { dao.insertSource(it) }
    }
}
