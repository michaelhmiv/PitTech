package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class DenseSharingPersistenceTest {
    @Test fun storyLargerThanCursorWindowReopensObservesAndRestoresEverySample(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "dense-sharing-${UUID.randomUUID()}"
        var database = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build()
        val recipient = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            var repository = CookRepository(database, PhotoStorage(context))
            val cook = repository.startCook(NewCookDraft("Dense guide", dishes = listOf(DishDraft("Pork", "Pork"))))
            val dish = database.cookDao().getDishesForCook(cook).single()
            val snapshot = RecipeExtraction.fromMeal(repository.observeCookDetail(cook).first()!!, dish.id).snapshot
            val readings = JSONArray((0 until 20000).map { i -> JSONObject().put("id", UUID.randomUUID().toString()).put("probeId", "logical-food")
                .put("probeName", "Food 🌶").put("measurementType", "food").put("value", 20.125 + i / 1000.0).put("unit", "°C")
                .put("measuredAtUtcMillis", 1700000000000L + i * 15000L).put("samplingIntervalMillis", 15000).put("qualityStatus", "valid").put("source", "recorded") })
            val story = JSONObject().put("version", 1).put("attemptId", dish.id).put("anchorUtcMillis", 1700000000000L).put("observations", JSONArray()).put("readings", readings).put("results", JSONArray())
            val id = UUID.randomUUID().toString(); val payload = SharingDraftCodec.encode(SharingDocument("Dense guide 🌶", listOf(SharingEntry(snapshot = snapshot, storyJson = story.toString(), includeStory = true))))
            assertTrue("The fixture must exceed the old single-row boundary", payload.toByteArray(Charsets.UTF_8).size > 3 * 1024 * 1024)
            database.companionDao().put(CompanionRecord(id, SharingDraftCodec.DRAFT, title = "Dense guide 🌶", payload = payload, createdAtUtcMillis = 1700000000000, updatedAtUtcMillis = 1700000000000))
            assertEquals(payload, database.companionDao().get(id)!!.payload)
            assertEquals(payload, database.companionDao().observeAll().first().single().payload)
            database.close(); database = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build(); repository = CookRepository(database, PhotoStorage(context))
            assertEquals(payload, repository.sharing.get(id)!!.payload)
            val transfer = PitTechDataTransfer(context, repository); val file = ByteArrayOutputStream(); transfer.writeZip(file)
            val restored = transfer.previewImport(ByteArrayInputStream(file.toByteArray())); val destination = CookRepository(recipient, PhotoStorage(context))
            destination.importSnapshot(restored.snapshot, restored.attachments)
            val imported = SharingDraftCodec.decode(destination.sharing.get(id)!!.payload)
            assertEquals(20000, JSONObject(imported.entries.single().storyJson).getJSONArray("readings").length())
            assertTrue(SharingDraftCodec.sameStory(story.toString(), imported.entries.single().storyJson))
            assertEquals(payload, recipient.companionDao().all().single().payload)
        } finally { database.close(); recipient.close(); context.deleteDatabase(name) }
    }
}
