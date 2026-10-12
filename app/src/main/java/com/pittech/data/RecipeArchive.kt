package com.pittech.data

import com.pittech.domain.RecipeSnapshotCodec
import org.json.JSONArray
import org.json.JSONObject

/** Archive v7 recipe records; validate the entire graph before copying or committing anything. */
object RecipeArchive {
    fun attachmentPath(asset: RecipeAsset) = "attachments/${asset.id}.${asset.relativeFilePath.substringAfterLast('.')}"
    fun write(root: JSONObject, snapshot: ExportSnapshot) {
        root.put("recipes", JSONArray(snapshot.recipes.map { json(it) }))
        root.put("recipeRevisions", JSONArray(snapshot.recipeRevisions.map { json(it) }))
        root.put("recipeAssets", JSONArray(snapshot.recipeAssets.map { json(it).put("archivePath", attachmentPath(it)) }))
        root.put("dishRecipeSources", JSONArray(snapshot.dishRecipeSources.map { json(it) }))
        root.put("mealPresets", JSONArray(snapshot.mealPresets.map { json(it) }))
    }
    fun read(root: JSONObject, base: ExportSnapshot, attachment: (String, String) -> ByteArray?): ExportSnapshot {
        val snapshot = base.copy(
            recipes = objects(root, "recipes").map { readSavedRecipe(it) },
            recipeRevisions = objects(root, "recipeRevisions").map { readRecipeRevision(it) },
            recipeAssets = objects(root, "recipeAssets").map { readRecipeAsset(it) },
            dishRecipeSources = objects(root, "dishRecipeSources").map { readDishRecipeSource(it) },
            mealPresets = objects(root, "mealPresets").map { readMealPreset(it) },
        )
        validate(snapshot)
        objects(root, "recipeAssets").forEach { o ->
            val id = o.getString("id"); val path = o.getString("archivePath")
            require(path.matches(Regex("attachments/${Regex.escape(id)}\\.[A-Za-z0-9]{1,8}"))) { "Invalid recipe attachment path." }
            require(attachment(id, path)?.isNotEmpty() == true) { "A recipe reference photo is missing." }
            require(snapshot.recipeAssets.first { it.id == id }.relativeFilePath == "photos/${path.substringAfterLast('/')}") { "Invalid recipe photo destination." }
        }
        return snapshot
    }
    fun validate(s: ExportSnapshot) {
        fun unique(ids: List<String>) { require(ids.distinct().size == ids.size && ids.all { it.isNotBlank() && it.length <= 128 }) { "Duplicate or invalid recipe identities." } }
        unique(s.recipes.map { it.id }); unique(s.recipeRevisions.map { it.id }); unique(s.dishRecipeSources.map { it.dishId }); unique(s.mealPresets.map { it.id })
        unique(s.recipeAssets.map { it.id } + s.photos.map { it.id } + s.companionPhotos.map { it.id })
        val recipes = s.recipes.associateBy { it.id }; val revisions = s.recipeRevisions.associateBy { it.id }
        require(s.recipes.all { it.name.isNotBlank() && revisions[it.currentRevisionId]?.recipeId == it.id && JSONArray(it.tagsJson).length() <= 100 }) { "Invalid preferred recipe version." }
        require(s.recipeRevisions.map { it.recipeId to it.revisionNumber }.distinct().size == s.recipeRevisions.size)
        s.recipeRevisions.forEach { revision ->
            require(revision.recipeId in recipes && revision.revisionNumber > 0 && revision.snapshotSchemaVersion == 1)
            val parent = revision.parentRevisionId?.let { revisions[it] ?: error("Missing parent recipe revision.") }
            require(parent == null || parent.recipeId == revision.recipeId && parent.revisionNumber < revision.revisionNumber) { "Invalid recipe revision ancestry." }
            RecipeSnapshotCodec.decode(revision.snapshotJson)
            require(revision.sourceDishId == null || revision.sourceCookId != null)
            s.dishes.firstOrNull { it.id == revision.sourceDishId }?.let { require(it.cookId == revision.sourceCookId) { "Recipe source belongs to a different meal." } }
        }
        require(s.dishRecipeSources.all { source -> s.dishes.any { it.id == source.dishId } && revisions[source.recipeRevisionId]?.recipeId == source.recipeId }) { "Invalid dish recipe source." }
        require(s.recipeAssets.all { asset -> asset.recipeRevisionId in revisions && asset.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && asset.relativeFilePath.matches(Regex("photos/${Regex.escape(asset.id)}\\.[A-Za-z0-9]{1,8}")) && asset.mimeType.startsWith("image/") }) { "Invalid recipe media ownership." }
        s.companionRecords.filter { it.kind == "recipe_attempt" }.forEach { record ->
            val o = JSONObject(record.payload); val dishId = o.getString("dishId")
            require(s.dishes.any { it.id == dishId && it.cookId == record.cookId } &&
                s.dishRecipeSources.any { it.dishId == dishId && it.recipeRevisionId == o.getString("revisionId") }) { "Invalid recipe attempt ownership." }
            RecipeAttemptCodec.snapshot(record.payload)
        }
        s.mealPresets.forEach { preset ->
            require(preset.name.isNotBlank())
            val items = com.pittech.domain.MealPresetCodec.items(preset.itemsJson); com.pittech.domain.MealPresetCodec.setup(preset.itemsJson); require(items.length() <= 30)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (!item.isNull("setupSnapshotJson")) RecipeSnapshotCodec.decode(item.getString("setupSnapshotJson"))
                if (!item.isNull("recipeRevisionId")) require(item.getString("recipeRevisionId") in revisions) { "A meal preset references an unavailable recipe." }
                else RecipeSnapshotCodec.decode(item.getString("snapshotJson"))
            }
        }
    }
    private fun objects(o: JSONObject, k: String): List<JSONObject> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun json(v: SavedRecipe) = JSONObject().put("id", v.id).put("name", v.name).put("description", v.description).put("tagsJson", v.tagsJson).put("currentRevisionId", v.currentRevisionId).put("createdAtUtcMillis", v.createdAtUtcMillis).put("updatedAtUtcMillis", v.updatedAtUtcMillis).put("archivedAtUtcMillis", v.archivedAtUtcMillis).put("favorite", v.favorite)
    private fun readSavedRecipe(o: JSONObject) = SavedRecipe(o.getString("id"), o.getString("name"), o.getString("description"), o.getString("tagsJson"), o.getString("currentRevisionId"), o.getLong("createdAtUtcMillis"), o.getLong("updatedAtUtcMillis"), if (o.isNull("archivedAtUtcMillis")) null else o.getLong("archivedAtUtcMillis"), o.getBoolean("favorite"))
    private fun json(v: RecipeRevision) = JSONObject().put("id", v.id).put("recipeId", v.recipeId).put("parentRevisionId", v.parentRevisionId).put("revisionNumber", v.revisionNumber).put("sourceCookId", v.sourceCookId).put("sourceDishId", v.sourceDishId).put("snapshotSchemaVersion", v.snapshotSchemaVersion).put("snapshotJson", v.snapshotJson).put("createdAtUtcMillis", v.createdAtUtcMillis).put("changeNotes", v.changeNotes)
    private fun readRecipeRevision(o: JSONObject) = RecipeRevision(o.getString("id"), o.getString("recipeId"), if (o.isNull("parentRevisionId")) null else o.getString("parentRevisionId"), o.getInt("revisionNumber"), if (o.isNull("sourceCookId")) null else o.getString("sourceCookId"), if (o.isNull("sourceDishId")) null else o.getString("sourceDishId"), o.getInt("snapshotSchemaVersion"), o.getString("snapshotJson"), o.getLong("createdAtUtcMillis"), o.getString("changeNotes"))
    private fun json(v: RecipeAsset) = JSONObject().put("id", v.id).put("recipeRevisionId", v.recipeRevisionId).put("relativeFilePath", v.relativeFilePath).put("mimeType", v.mimeType).put("caption", v.caption).put("sourcePhotoId", v.sourcePhotoId).put("createdAtUtcMillis", v.createdAtUtcMillis)
    private fun readRecipeAsset(o: JSONObject) = RecipeAsset(o.getString("id"), o.getString("recipeRevisionId"), o.getString("relativeFilePath"), o.getString("mimeType"), o.getString("caption"), if (o.isNull("sourcePhotoId")) null else o.getString("sourcePhotoId"), o.getLong("createdAtUtcMillis"))
    private fun json(v: DishRecipeSource) = JSONObject().put("dishId", v.dishId).put("recipeId", v.recipeId).put("recipeRevisionId", v.recipeRevisionId).put("appliedAtUtcMillis", v.appliedAtUtcMillis)
    private fun readDishRecipeSource(o: JSONObject) = DishRecipeSource(o.getString("dishId"), o.getString("recipeId"), o.getString("recipeRevisionId"), o.getLong("appliedAtUtcMillis"))
    private fun json(v: MealPreset) = JSONObject().put("id", v.id).put("name", v.name).put("itemsJson", v.itemsJson).put("createdAtUtcMillis", v.createdAtUtcMillis).put("updatedAtUtcMillis", v.updatedAtUtcMillis)
    private fun readMealPreset(o: JSONObject) = MealPreset(o.getString("id"), o.getString("name"), o.getString("itemsJson"), o.getLong("createdAtUtcMillis"), o.getLong("updatedAtUtcMillis"))
}
