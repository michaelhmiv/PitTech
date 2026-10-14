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
    @Test fun partialThenWholeCollectionKeepsStableRecipesAndIndependentReferencePhotos(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val recipient = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val storage = PhotoStorage(context)
        try {
            val repository = CookRepository(source, storage)
            val cook = repository.startCook(NewCookDraft("Pork and potatoes", dishes = listOf(DishDraft("Pork", "Pork"), DishDraft("Potatoes", "Vegetables"))))
            val dishes = source.cookDao().getDishesForCook(cook)
            for (dish in dishes) {
                val file = CameraPhotoFiles.create(context); val uri = CameraPhotoFiles.uri(context, file)
                val image = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.YELLOW) }
                context.contentResolver.openOutputStream(uri, "w")!!.use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; image.recycle()
                repository.notes.save(CookNoteDraft(cookId = cook, dishId = dish.id, title = "${dish.name} result", temperatures = listOf(NoteTemperature(probeName = "Same name", valueText = if (dish.name == "Pork") "74.125" else "88.375", unit = "°C", dishId = dish.id)), photos = listOf(NotePhoto(uri = uri.toString()))))
                file.delete()
            }
            repository.completeCook(cook)
            val actualCook = source.cookDao().getCook(cook)!!
            val porkDish = dishes.single { it.name == "Pork" }
            repository.saveDishVerdict(cook, porkDish.id, 4.5, false, "More pepper")
            val id = repository.sharing.fromMeal(cook, dishes.map { it.id }.toSet()); val row = repository.sharing.get(id)!!
            val document = SharingDraftCodec.decode(row.payload); val photos = repository.sharing.assets(id)
            repository.sharing.save(id, document.copy(entries = document.entries.map { e -> e.copy(includeStory = true, photoIds = photos.filter { it.action == e.id }.map { it.id }) }), row.updatedAtUtcMillis)
            val transfer = PitTechDataTransfer(context, repository); val bytes = ByteArrayOutputStream(); transfer.writeCookPack(bytes, id)
            val pack = transfer.previewCookPack(ByteArrayInputStream(bytes.toByteArray()))
            val pork = pack.snapshot.recipes.single { it.name == "Pork" }.currentRevisionId
            val all = pack.snapshot.recipes.map { it.currentRevisionId }.toSet()
            val destination = CookRepository(recipient, storage)
            val one = pack.selectCookPackRecipes(setOf(pork))
            repeat(2) { destination.importSnapshot(one.snapshot, one.attachments) }
            assertEquals(1, recipient.recipeDao().recipes().size); assertEquals(1, recipient.recipeDao().assets().size); assertEquals(1, recipient.companionDao().photos().size)
            val whole = pack.selectCookPackRecipes(all)
            repeat(2) { destination.importSnapshot(whole.snapshot, whole.attachments) }
            assertEquals(2, recipient.recipeDao().recipes().size); assertEquals(2, recipient.recipeDao().assets().size)
            assertEquals(2, recipient.companionDao().all().size); assertEquals(3, recipient.companionDao().photos().size)
            assertTrue(recipient.cookDao().getAllCooks().isEmpty()); assertNull(pack.preferences)
            for (ids in listOf(emptySet<String>(), setOf(java.util.UUID.randomUUID().toString()))) {
                try { pack.selectCookPackRecipes(ids); fail("An invalid selection was saved") } catch (_: IllegalArgumentException) { }
            }
            recipient.companionDao().photos().forEach { CookPackPhoto.validatePublic(storage.read(it.relativePath)!!) }
            val duplicate = repository.sharing.duplicate(id)
            val cloned = repository.sharing.get(duplicate)!!; val frozen = SharingDraftCodec.decode(cloned.payload)
            repository.sharing.save(duplicate, frozen.copy(entries = frozen.entries.map { entry -> entry.copy(included = entry.sourceDishId == porkDish.id, snapshot = entry.snapshot.copy(name = "Edited recipe")) }), cloned.updatedAtUtcMillis)
            repository.deleteCook(actualCook)
            assertNull(source.cookDao().getCook(cook))
            val historicalBytes = ByteArrayOutputStream(); transfer.writeCookPack(historicalBytes, duplicate, includeRecordedCook = true)
            val historical = transfer.previewCookPack(ByteArrayInputStream(historicalBytes.toByteArray()))
            assertEquals("cook", historical.cookPack!!.getString("kind")); assertEquals(1, historical.snapshot.dishes.size)
            assertEquals("Pork", historical.snapshot.dishes.single().name)
            assertEquals(actualCook.startedAtUtcMillis, historical.snapshot.cooks.single().startedAtUtcMillis)
            assertEquals(actualCook.endedAtUtcMillis, historical.snapshot.cooks.single().endedAtUtcMillis)
            assertEquals(74.125, historical.snapshot.readings.single().value, 0.0); assertEquals("°C", historical.snapshot.readings.single().unit)
            assertEquals(1, historical.snapshot.photos.size)
            assertTrue(historical.snapshot.results.any { it.numericValue == 4.5 })
            assertTrue(historical.snapshot.results.any { it.resultType == "cook_again" && it.numericValue == 0.0 })
            val projected = archiveSnapshotJson(historical.snapshot, null).toString()
            assertFalse(projected.contains("Potatoes")); assertFalse(projected.contains(cook)); assertFalse(projected.contains(porkDish.id))
            val recipesOnly = historical.selectCookPackRecipes(historical.snapshot.recipes.map { it.currentRevisionId }.toSet())
            assertTrue(recipesOnly.snapshot.cooks.isEmpty()); assertTrue(recipesOnly.snapshot.readings.isEmpty())
            val active = destination.startCook(NewCookDraft("Existing active meal"))
            val historyOnly = historical.recordedCookOnly()
            repeat(2) { destination.importSnapshot(historyOnly.snapshot, historyOnly.attachments) }
            assertEquals(2, recipient.cookDao().getAllCooks().size)
            assertEquals(CookStatus.ACTIVE, recipient.cookDao().getCook(active)!!.status)
            assertEquals(2, recipient.recipeDao().recipes().size) // History import does not save additional recipes.
            assertTrue(recipient.recordingDao().getAllRecordings().isEmpty()); assertTrue(recipient.reminderDao().rules().isEmpty())

        } finally {
            source.companionDao().photos().forEach { storage.delete(it.relativePath) }; source.cookDao().getAllPhotos().forEach { storage.delete(it.relativePath) }
            recipient.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) }; recipient.companionDao().photos().forEach { storage.delete(it.relativePath) }; recipient.cookDao().getAllPhotos().forEach { storage.delete(it.relativePath) }
            source.close(); recipient.close()
        }
    }
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
            repository.saveDishVerdict(cook, pork.id, 4.5, false, "More pepper")
            val id = repository.sharing.fromMeal(cook, dishes.map { it.id }.toSet()); val row = repository.sharing.get(id)!!
            val draft = SharingDraftCodec.decode(row.payload); val photo = repository.sharing.assets(id).single()
            repository.sharing.save(id, draft.copy(title = "Pepper pork", entries = draft.entries.map { entry -> if (entry.sourceDishId == pork.id) entry.copy(includeStory = true, photoIds = listOf(photo.id)) else entry.copy(included = false) }), row.updatedAtUtcMillis)
            val transfer = PitTechDataTransfer(context, repository); val file = ByteArrayOutputStream(); transfer.writeCookPack(file, id)
            val preview = transfer.previewCookPack(ByteArrayInputStream(file.toByteArray()))
            assertEquals("recipe", preview.cookPack!!.getString("kind")); assertEquals(1, preview.snapshot.recipes.size); assertEquals(0, preview.cookCount)
            assertTrue(preview.snapshot.devices.isEmpty()); assertTrue(preview.snapshot.recordings.isEmpty())
            val safeRoot = JSONObject().put("preferences", JSONObject.NULL)
            CookPackContract.validate(preview.cookPack!!, safeRoot, preview.snapshot)
            val forged = JSONObject(safeRoot.toString()).put("snapshotJson", " \n{\"apiKey\":\"synthetic-unshareable\"}")
            try { CookPackContract.validate(preview.cookPack!!, forged, preview.snapshot); fail("Whitespace-prefixed private fields accepted") } catch (_: IllegalArgumentException) { }
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
            val latest = repository.sharing.get(id)!!; val current = SharingDraftCodec.decode(latest.payload)
            repository.sharing.save(id, current.copy(entries = current.entries.map { it.copy(includeStory = false) }), latest.updatedAtUtcMillis)
            val instructionsOnly = ByteArrayOutputStream(); transfer.writeCookPack(instructionsOnly, id)
            val clean = transfer.previewCookPack(ByteArrayInputStream(instructionsOnly.toByteArray()))
            val definition = RecipeSnapshotCodec.decode(clean.snapshot.recipeRevisions.single().snapshotJson)
            assertTrue(definition.observations.isEmpty()); assertEquals(2, definition.instructions.size)
            assertNull(definition.rating); assertNull(definition.cookAgain); assertEquals("", definition.resultNotes)
        } finally {
            source.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) }; source.companionDao().photos().forEach { storage.delete(it.relativePath) }; source.cookDao().getAllPhotos().forEach { storage.delete(it.relativePath) }
            recipient.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) }; recipient.companionDao().photos().forEach { storage.delete(it.relativePath) }
            source.close(); recipient.close()
        }
    }
}
