package com.pittech.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SharingIntegrationTest {
    @Test fun porkOnlyDraftOwnsItsPhotosAndNeverEditsOriginalMeal(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val storage = PhotoStorage(context)
        try {
            val repo = CookRepository(db, storage)
            val cook = repo.startCook(NewCookDraft("Pork and potatoes", dishes = listOf(DishDraft("Pork", "Pork"), DishDraft("Potatoes", "Vegetables"))))
            val dishes = db.cookDao().getDishesForCook(cook); val pork = dishes.first { it.name == "Pork" }
            val at = System.currentTimeMillis() - 3600000
            repo.addTimelineEvent(cook, pork.id, "meat_on", "Food on", null, at)
            repo.addTimelineEvent(cook, pork.id, "wrap", "Wrapped", "Bark set", at + 1800000)
            val camera = CameraPhotoFiles.create(context); val uri = CameraPhotoFiles.uri(context, camera)
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(100, 50, 20)) }
            context.contentResolver.openOutputStream(uri, "w")!!.use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }; image.recycle()
            repo.notes.save(CookNoteDraft(cookId = cook, dishId = pork.id, title = "Bark", photos = listOf(NotePhoto(uri = uri.toString(), caption = "Pork only"))))
            repo.completeCook(cook)
            repo.saveDishVerdict(cook, pork.id, null, null, "")
            assertTrue(db.cookDao().getAllResults().none { it.resultType in setOf("overall_rating", "cook_again") })
            repo.saveDishVerdict(cook, pork.id, 4.5, false, "More pepper next time")
            assertEquals(false, RecipeExtraction.fromMeal(repo.observeCookDetail(cook).first()!!, pork.id).snapshot.cookAgain)
            val history = repo.exportSnapshot()
            val id = repo.sharing.fromMeal(cook, dishes.map { it.id }.toSet())
            val row = repo.sharing.get(id)!!; val original = SharingDraftCodec.decode(row.payload)
            val entry = original.entries.first { it.sourceDishId == pork.id }
            val asset = repo.sharing.assets(id).single()
            val bytes = storage.read(asset.relativePath)!!
            val edited = original.copy(entries = original.entries.map { if (it.id == entry.id) it.copy(snapshot = it.snapshot.copy(description = "Add extra pepper"), includeStory = true, photoIds = listOf(asset.id)) else it.copy(included = false) })
            val saved = repo.sharing.save(id, edited, row.updatedAtUtcMillis)
            assertEquals(history.events, repo.exportSnapshot().events)
            assertEquals(history.dishes, repo.exportSnapshot().dishes)
            assertTrue(SharingDraftCodec.sameStory(entry.storyJson, SharingDraftCodec.decode(saved.payload).entries.first { it.id == entry.id }.storyJson))
            val forged = edited.copy(entries = edited.entries.map { if (it.id == entry.id) it.copy(storyJson = JSONObject(it.storyJson).put("attemptId", "fabricated").toString()) else it })
            try { repo.sharing.save(id, forged, saved.updatedAtUtcMillis); fail("Edited source facts accepted") } catch (_: IllegalArgumentException) { }
            val collection = repo.sharing.saveCollection(id)
            assertEquals(1, SharingDraftCodec.decode(repo.sharing.get(collection)!!.payload).entries.size)
            repo.deleteCook(db.cookDao().getCook(cook)!!)
            assertArrayEquals(bytes, storage.read(asset.relativePath))
            assertNotNull(repo.sharing.get(id)); assertNotNull(repo.sharing.get(collection))
            val transfer = PitTechDataTransfer(context, repo); val archive = ByteArrayOutputStream(); transfer.writeZip(archive)
            val preview = transfer.previewImport(ByteArrayInputStream(archive.toByteArray()))
            assertEquals(10, preview.archiveVersion)
            assertEquals(2, preview.snapshot.companionRecords.count { it.kind in setOf("sharing_draft", "collection_revision") })
            assertEquals(2, preview.snapshot.companionPhotos.size)
            val restored = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
            try { CookRepository(restored, storage).importSnapshot(preview.snapshot, preview.attachments); assertEquals(2, restored.companionDao().all().size) } finally { restored.close() }
            camera.delete()
        } finally { db.companionDao().photos().forEach { storage.delete(it.relativePath) }; db.close() }
    }
}
