package com.pittech.data

import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.SharingDraftCodec
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixtures were collected from the successful native writer, including its media derivatives. */
class CookPackCrossPlatformTest {
    @Test fun actualNativeRecordedCookPreservesDatesCelsiusAndHistoryChoice(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build(); val storage = PhotoStorage(context)
        try {
            val repository = CookRepository(database, storage); val transfer = PitTechDataTransfer(context, repository)
            val active = repository.startCook(NewCookDraft("Keep this meal active"))
            val pack = instrumentation.context.assets.open("cross-platform/native-cook-pack-cook-v1.pittech").use(transfer::previewCookPack)
            assertEquals("cook", pack.cookPack!!.getString("kind")); assertEquals(1, pack.cookCount); assertEquals(1, pack.dishCount)
            val history = pack.recordedCookOnly()
            repeat(2) { repository.importSnapshot(history.snapshot, history.attachments) }
            val cooks = database.cookDao().getAllCooks()
            assertEquals(2, cooks.size); assertEquals(CookStatus.ACTIVE, cooks.single { it.id == active }.status)
            val completed = cooks.single { it.status == CookStatus.COMPLETED }
            assertEquals(1792013582659L, completed.startedAtUtcMillis); assertEquals(1792013582669L, completed.endedAtUtcMillis)
            assertEquals("Chicken", database.cookDao().getDishesForCook(completed.id).single().name)
            val reading = database.cookDao().getAllSensorReadings().single()
            assertEquals(74.125, reading.value, 0.0); assertEquals("°C", reading.unit)
            assertEquals(1792013582660L, reading.measuredAtUtcMillis)
            assertEquals(1, database.cookDao().getAllProbes().size)
            assertTrue(database.recipeDao().recipes().isEmpty()); assertTrue(database.reminderDao().rules().isEmpty())
            assertTrue(database.recordingDao().getAllRecordings().isEmpty())
            val photo = database.cookDao().getAllPhotos().single(); val bytes = storage.read(photo.relativePath)!!
            CookPackPhoto.validatePublic(bytes)
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(8, image.width); assertEquals(4, image.height); image.recycle()
        } finally {
            database.cookDao().getAllPhotos().forEach { storage.delete(it.relativePath) }; database.close()
        }
    }

    @Test fun actualNativeRecipeAndCollectionPreserveCelsiusGuidesAndOwnAssets(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        for (kind in listOf("recipe", "collection")) {
            val database = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build(); val storage = PhotoStorage(context)
            try {
                val repository = CookRepository(database, storage); val transfer = PitTechDataTransfer(context, repository)
                val pack = instrumentation.context.assets.open("cross-platform/native-cook-pack-$kind-v1.pittech").use(transfer::previewCookPack)
                assertEquals(kind, pack.cookPack!!.getString("kind")); assertTrue(pack.snapshot.cooks.isEmpty()); assertTrue(pack.snapshot.devices.isEmpty()); assertTrue(pack.snapshot.probes.isEmpty())
                val document = SharingDraftCodec.decode(pack.snapshot.companionRecords.single().payload)
                val chicken = document.entries.single { it.snapshot.name == "Chicken" }
                val samples = JSONObject(chicken.storyJson).getJSONArray("readings"); assertEquals(1, samples.length())
                assertEquals("°C", samples.getJSONObject(0).getString("unit")); assertEquals(74.125, samples.getJSONObject(0).getDouble("value"), 0.0)
                assertEquals(1, pack.snapshot.recipeAssets.size); assertEquals(1, pack.snapshot.companionPhotos.size)
                pack.attachments.values.forEach { bytes ->
                    CookPackPhoto.validatePublic(bytes); val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertEquals(8, image.width); assertEquals(4, image.height); image.recycle()
                }
                val one = pack.selectCookPackRecipes(setOf(chicken.sourceRevisionId!!))
                repeat(2) { repository.importSnapshot(one.snapshot, one.attachments) }
                assertEquals(1, database.recipeDao().recipes().size); assertEquals(1, database.companionDao().photos().size)
                if (kind == "collection") {
                    val whole = pack.selectCookPackRecipes(pack.snapshot.recipes.map { it.currentRevisionId }.toSet())
                    repeat(2) { repository.importSnapshot(whole.snapshot, whole.attachments) }
                    assertEquals(2, database.recipeDao().recipes().size); assertEquals(1, database.recipeDao().assets().size)
                    assertEquals(2, database.companionDao().photos().size); assertEquals(2, database.companionDao().all().size)
                }
                assertTrue(database.cookDao().getAllCooks().isEmpty()); assertTrue(database.reminderDao().rules().isEmpty())
            } finally {
                database.recipeDao().assets().forEach { storage.delete(it.relativeFilePath) }; database.companionDao().photos().forEach { storage.delete(it.relativePath) }; database.close()
            }
        }
    }
}
