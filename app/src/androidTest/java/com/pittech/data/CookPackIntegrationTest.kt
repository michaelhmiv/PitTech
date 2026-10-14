package com.pittech.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CookPackIntegrationTest {
    @Test fun selectedPackPreservesActualCelsiusGuideAndPrivateOriginalPhoto(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val recipient = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val storage = PhotoStorage(context)
        try {
            val repository = CookRepository(source, storage)
            val cook = repository.startCook(NewCookDraft("Pork and potatoes", dishes = listOf(DishDraft("Pork", "Pork"), DishDraft("Potatoes", "Vegetables"))))
            val dishes = source.cookDao().getDishesForCook(cook); val pork = dishes.first { it.name == "Pork" }
            val anchor = System.currentTimeMillis() - 7200000
            repository.addTimelineEvent(cook, pork.id, "meat_on", "Food on", null, anchor)
            val camera = CameraPhotoFiles.create(context); val uri = CameraPhotoFiles.uri(context, camera)
            val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.YELLOW) }
            context.contentResolver.openOutputStream(uri, "w")!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
            ExifInterface(camera.absolutePath).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "32/1,0/1,0/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "80/1,0/1,0/1"); setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
            }
            val original = camera.readBytes()
            repository.notes.save(CookNoteDraft(cookId = cook, dishId = pork.id, action = "wrap", title = "Wrapped", occurredAt = anchor + 3600000,
                temperatures = listOf(NoteTemperature(probeName = "Pork probe", valueText = "74", unit = "°C", measuredAt = anchor + 3600000, dishId = pork.id)), photos = listOf(NotePhoto(uri = uri.toString()))))
            repository.completeCook(cook)
            val id = repository.sharing.fromMeal(cook, dishes.map { it.id }.toSet()); val row = repository.sharing.get(id)!!
            val draft = SharingDraftCodec.decode(row.payload); val photo = repository.sharing.assets(id).single()
            repository.sharing.save(id, draft.copy(title = "Pepper pork", entries = draft.entries.map { entry -> if (entry.sourceDishId == pork.id) entry.copy(includeStory = true, photoIds = listOf(photo.id)) else entry.copy(included = false) }), row.updatedAtUtcMillis)
            val transfer = PitTechDataTransfer(context, repository); val file = ByteArrayOutputStream(); transfer.writeCookPack(file, id)
            val preview = transfer.previewCookPack(ByteArrayInputStream(file.toByteArray()))
            assertEquals("recipe", preview.cookPack!!.getString("kind")); assertEquals(1, preview.snapshot.recipes.size); assertEquals(0, preview.cookCount)
            assertTrue(preview.snapshot.devices.isEmpty()); assertTrue(preview.snapshot.recordings.isEmpty())
            val recipe = RecipeSnapshotCodec.decode(preview.snapshot.recipeRevisions.single().snapshotJson)
            assertEquals(2, recipe.observations.size); assertEquals(3600000L, recipe.observations.last().offsetMillis)
            assertEquals("°C", recipe.observations.last().recordedTemperatures.single().unit)
            val story = JSONObject(SharingDraftCodec.decode(preview.snapshot.companionRecords.single().payload).entries.single().storyJson)
            assertEquals(74.0, story.getJSONArray("readings").getJSONObject(0).getDouble("value"), 0.0)
            val packedJson = preview.snapshot.recipeRevisions.single().snapshotJson + preview.snapshot.companionRecords.single().payload
            assertFalse(packedJson.contains("Potatoes")); assertFalse(packedJson.contains(cook)); assertFalse(packedJson.contains(pork.id))
            preview.attachments.values.forEach { bytes ->
                CookPackPhoto.validatePublic(bytes)
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertEquals(4, decoded.width); assertEquals(8, decoded.height); decoded.recycle()
            }
            assertArrayEquals(original, storage.read(photo.relativePath))
            val destination = CookRepository(recipient, storage)
            destination.importSnapshot(preview.snapshot, preview.attachments); destination.importSnapshot(preview.snapshot, preview.attachments)
            assertEquals(1, recipient.recipeDao().recipes().size); assertEquals(1, recipient.recipeDao().assets().size)
            assertEquals(1, recipient.companionDao().all().size)
            assertEquals(2, source.cookDao().getDishesForCook(cook).size)
            try { CookPackPhoto.validatePublic(original); fail("Located image accepted") } catch (_: IllegalArgumentException) { }
        } finally {
            source.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) }; source.companionDao().photos().forEach { storage.delete(it.relativePath) }; source.cookDao().getAllPhotos().forEach { storage.delete(it.relativePath) }
            recipient.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) }; recipient.companionDao().photos().forEach { storage.delete(it.relativePath) }
            source.close(); recipient.close()
        }
    }
}
