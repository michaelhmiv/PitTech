package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

class RecipeIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: PitTechDatabase
    private lateinit var repo: CookRepository
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build(); repo = CookRepository(db, PhotoStorage(context)) }
    @After fun cleanup() { runBlocking { db.recipeDao().assets().forEach { PhotoStorage(context).delete(it.relativeFilePath) }; db.cookDao().getAllPhotos().forEach { PhotoStorage(context).delete(it.relativePath) } }; db.close() }
    private val snapshot = RecipeSnapshot("Mac", DishDraft("Mac", "Pasta", preparationItems = listOf(IngredientDraft("Gouda", amountText = "4", amountUnit = "oz"))))
    @Test fun independentRevisionsPhotosAndAttemptsSurviveSourceDeletion(): Unit = runBlocking {
        val cookId = repo.startCook(NewCookDraft("Meal", dishes = listOf(snapshot.dish)))
        val dishId = db.cookDao().getDishesForCook(cookId).single().id
        val photoId = UUID.randomUUID().toString(); val path = "photos/$photoId.jpg"
        PhotoStorage(context).writeImported(path, byteArrayOf(1, 2, 3))
        db.cookDao().insertPhotos(listOf(PhotoEntity(photoId, cookId, dishId, originalFileName = "mac.jpg", relativePath = path, mimeType = "image/jpeg", addedAtUtcMillis = 1)))
        val original = repo.recipes.save(snapshot, sourceCookId = cookId, sourceDishId = dishId, photoIds = setOf(photoId))
        repo.recipes.link(dishId, original.id, original.currentRevisionId)
        val variant = repo.recipes.duplicate(original.id, "Spicy mac")
        val modified = snapshot.copy(dish = snapshot.dish.copy(preparationItems = listOf(IngredientDraft("Gouda", amountText = "8", amountUnit = "oz"))))
        val next = repo.recipes.save(modified, original.id, original.currentRevisionId)
        assertEquals(2, repo.recipes.history(original.id).size)
        assertEquals(snapshot, RecipeSnapshotCodec.decode(repo.recipes.revision(original.currentRevisionId)!!.snapshotJson))
        assertEquals(original.currentRevisionId, repo.recipes.source(dishId)!!.recipeRevisionId)
        try { repo.recipes.save(snapshot, original.id, original.currentRevisionId); fail("Stale edit accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(next.currentRevisionId, repo.recipes.get(original.id)!!.currentRevisionId)
        val branch = repo.recipes.save(snapshot.copy(description = "Variation based on the original"), original.id, next.currentRevisionId, retainAssetsFromRevisionId = original.currentRevisionId)
        val branchedRevision = repo.recipes.revision(branch.currentRevisionId)!!
        assertEquals(original.currentRevisionId, branchedRevision.parentRevisionId)
        assertEquals(3, branchedRevision.revisionNumber)
        repo.deleteCook(db.cookDao().getCook(cookId)!!)
        assertEquals(2, db.recipeDao().recipes().size)
        assertEquals(4, db.recipeDao().revisions().size)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.readPhoto(repo.recipes.assets(variant.currentRevisionId).single().relativeFilePath))
        assertNull(repo.recipes.source(dishId))
    }
    @Test fun archiveRoundTripIdempotenceAndConflictRejection(): Unit = runBlocking {
        val saved = repo.recipes.save(snapshot)
        val output = ByteArrayOutputStream(); val transfer = PitTechDataTransfer(context, repo); transfer.writeZip(output)
        val preview = transfer.previewImport(ByteArrayInputStream(output.toByteArray()))
        assertEquals(PitTechDataTransfer.ARCHIVE_VERSION, preview.archiveVersion)
        val other = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val restored = CookRepository(other, PhotoStorage(context))
            restored.importSnapshot(preview.snapshot, preview.attachments); restored.importSnapshot(preview.snapshot, preview.attachments)
            assertEquals(1, other.recipeDao().recipes().size)
            assertEquals(saved.currentRevisionId, other.recipeDao().revisions().single().id)
            val conflict = preview.snapshot.copy(recipeRevisions = preview.snapshot.recipeRevisions.map { it.copy(snapshotJson = RecipeSnapshotCodec.encode(snapshot.copy(name = "Tampered"))) })
            try { restored.importSnapshot(conflict, preview.attachments); fail("Conflicting immutable revision accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(snapshot, RecipeSnapshotCodec.decode(other.recipeDao().revisions().single().snapshotJson))
            // Two offline children may both be version 2. Their UUIDs and parents,
            // not the presentation ordinal, identify the immutable histories.
            val parent = preview.snapshot.recipeRevisions.single()
            val left = parent.copy(id = "branch-left", parentRevisionId = parent.id, revisionNumber = 2)
            val right = parent.copy(id = "branch-right", parentRevisionId = parent.id, revisionNumber = 2,
                snapshotJson = RecipeSnapshotCodec.encode(snapshot.copy(name = "Smokier mac")))
            val branches = preview.snapshot.copy(recipeRevisions = listOf(parent, left, right))
            restored.importSnapshot(branches, preview.attachments)
            restored.importSnapshot(branches, preview.attachments)
            assertEquals(setOf(parent.id, left.id, right.id), other.recipeDao().revisions().map { it.id }.toSet())
            assertEquals(saved.currentRevisionId, other.recipeDao().recipe(saved.id)!!.currentRevisionId)
            val roundTrip = ByteArrayOutputStream(); PitTechDataTransfer(context, restored).writeZip(roundTrip)
            assertEquals(3, transfer.previewImport(ByteArrayInputStream(roundTrip.toByteArray())).snapshot.recipeRevisions.size)
            val invalid = preview.snapshot.copy(recipes = preview.snapshot.recipes.map { it.copy(currentRevisionId = "missing") })
            try { restored.importSnapshot(invalid, preview.attachments); fail("Invalid ownership accepted") } catch (_: IllegalArgumentException) { }
        } finally { other.close() }
    }
}
