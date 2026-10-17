package com.pittech.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Source IDs are provenance, deliberately not cascading foreign keys into meal history. */
@Entity(tableName = "saved_recipes")
data class SavedRecipe(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val tagsJson: String,
    val currentRevisionId: String,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
    val archivedAtUtcMillis: Long? = null,
    val favorite: Boolean = false,
)

@Entity(tableName = "recipe_revisions", foreignKeys = [ForeignKey(entity = SavedRecipe::class,
    parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeId"), Index(value = ["recipeId", "revisionNumber"], unique = true)])
data class RecipeRevision(
    @PrimaryKey val id: String,
    val recipeId: String,
    val parentRevisionId: String?,
    val revisionNumber: Int,
    val sourceCookId: String?,
    val sourceDishId: String?,
    val snapshotSchemaVersion: Int,
    val snapshotJson: String,
    val createdAtUtcMillis: Long,
    val changeNotes: String,
)

@Entity(tableName = "dish_recipe_sources", foreignKeys = [ForeignKey(entity = DishEntity::class,
    parentColumns = ["id"], childColumns = ["dishId"], onDelete = ForeignKey.CASCADE)])
data class DishRecipeSource(@PrimaryKey val dishId: String, val recipeId: String,
    val recipeRevisionId: String, val appliedAtUtcMillis: Long)

@Entity(tableName = "recipe_assets", foreignKeys = [ForeignKey(entity = RecipeRevision::class,
    parentColumns = ["id"], childColumns = ["recipeRevisionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeRevisionId")])
data class RecipeAsset(@PrimaryKey val id: String, val recipeRevisionId: String,
    val relativeFilePath: String, val mimeType: String, val caption: String,
    val sourcePhotoId: String?, val createdAtUtcMillis: Long)

@Entity(tableName = "meal_presets")
data class MealPreset(@PrimaryKey val id: String, val name: String, val itemsJson: String,
    val createdAtUtcMillis: Long, val updatedAtUtcMillis: Long)

@Dao
interface RecipeDao {
    @Query("SELECT * FROM saved_recipes ORDER BY updatedAtUtcMillis DESC") fun observeRecipes(): Flow<List<SavedRecipe>>
    @Query("SELECT * FROM saved_recipes") suspend fun recipes(): List<SavedRecipe>
    @Query("SELECT * FROM saved_recipes WHERE id = :id") suspend fun recipe(id: String): SavedRecipe?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRecipe(recipe: SavedRecipe)
    @Update suspend fun updateRecipe(recipe: SavedRecipe)
    @Query("SELECT * FROM recipe_revisions") fun observeRevisions(): Flow<List<RecipeRevision>>
    @Query("SELECT * FROM dish_recipe_sources") fun observeSources(): Flow<List<DishRecipeSource>>
    @Query("SELECT * FROM meal_presets") fun observePresets(): Flow<List<MealPreset>>
    @Query("SELECT * FROM recipe_revisions") suspend fun revisions(): List<RecipeRevision>
    @Query("SELECT * FROM recipe_revisions WHERE id = :id") suspend fun revision(id: String): RecipeRevision?
    @Query("SELECT * FROM recipe_revisions WHERE recipeId = :recipeId ORDER BY revisionNumber DESC") suspend fun history(recipeId: String): List<RecipeRevision>
    // No update/upsert API: every saved revision is immutable.
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRevision(revision: RecipeRevision)
    @Query("SELECT * FROM recipe_assets") suspend fun assets(): List<RecipeAsset>
    @Query("SELECT * FROM recipe_assets WHERE recipeRevisionId = :revisionId") suspend fun assetsFor(revisionId: String): List<RecipeAsset>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAssets(assets: List<RecipeAsset>)
    @Query("SELECT * FROM dish_recipe_sources") suspend fun sources(): List<DishRecipeSource>
    @Query("SELECT * FROM dish_recipe_sources WHERE dishId = :dishId") suspend fun source(dishId: String): DishRecipeSource?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSource(source: DishRecipeSource)
    @Query("SELECT * FROM meal_presets") suspend fun presets(): List<MealPreset>
    @Upsert suspend fun putPreset(preset: MealPreset)
    /** Private-sync tombstone application only. Additive; no schema change. */
    @Query("DELETE FROM saved_recipes WHERE id = :id") suspend fun deleteRecipeById(id: String)
    @Query("DELETE FROM recipe_revisions WHERE id = :id") suspend fun deleteRevisionById(id: String)
    @Query("DELETE FROM recipe_assets WHERE id = :id") suspend fun deleteAssetById(id: String)
    @Query("DELETE FROM dish_recipe_sources WHERE dishId = :id") suspend fun deleteSourceByDishId(id: String)
    @Query("DELETE FROM meal_presets WHERE id = :id") suspend fun deletePresetById(id: String)
}
