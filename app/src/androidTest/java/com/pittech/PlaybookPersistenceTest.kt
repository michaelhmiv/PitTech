package com.pittech

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PlaybookPersistenceTest {
    @Test fun revisionsSnapshotsAndArchiveSurviveSourceDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val companion = CookCompanionRepository(db, PhotoStorage(context))
            val source = repo.startCook(NewCookDraft("Brisket", dishes = listOf(DishDraft("Flat", "Beef", "Brisket"))))
            val dish = db.cookDao().getDishesForCook(source).single()
            repo.addTimelineEvent(source, dish.id, "meat_on", "Food on", null, 1_000_000)
            repo.addTimelineEvent(source, dish.id, "wrap", "Check bark then wrap", "Bark was set", 4_600_000)
            val suggestion = companion.suggest(source)
            assertEquals(60L, suggestion.steps.last().minutes)
            val bookId = companion.save(suggestion)
            val cook = repo.startCook(suggestion.draft.copy(playbookId = bookId))
            val planBefore = db.companionDao().get("plan:$cook")!!.payload
            companion.save(suggestion.copy(name = "Variation", steps = emptyList()), bookId)
            assertEquals(planBefore, db.companionDao().get("plan:$cook")!!.payload)
            assertEquals(2, db.companionDao().all().count { it.kind == "playbook" })
            repo.deleteCook(db.cookDao().getCook(source)!!)
            assertNotNull(companion.get(bookId))
            val zip = ByteArrayOutputStream()
            PitTechDataTransfer(context, repo).writeZip(zip)
            val transfer = PitTechDataTransfer(context, CookRepository(restored, PhotoStorage(context)))
            val preview = transfer.previewImport(ByteArrayInputStream(zip.toByteArray()))
            transfer.import(preview)
            assertEquals(3, restored.companionDao().all().size)
            assertTrue(JSONObject(restored.companionDao().get("plan:$cook")!!.payload).getBoolean("paused"))
            transfer.import(preview)
            assertEquals(3, restored.companionDao().all().size)
            assertEquals(1, restored.cookDao().getAllCooks().size)
        } finally { db.close(); restored.close() }
    }
}
