package com.pittech

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidanceIntegrationTest {
    @Test fun repeatedCompletionIsAtomicAndQuickActionSatisfiesOneStep() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, PitTechDatabase::class.java).build()
        try {
            val companion = CookCompanionRepository(db, PhotoStorage(app))
            val repo = CookRepository(db, PhotoStorage(app))
            val steps = listOf(PlaybookStep(id = "on", dishIndex = 0, action = "food_on", title = "Food on"), PlaybookStep(id = "spritz", dishIndex = 0, action = "spritz", title = "Spritz", trigger = "elapsed", minutes = 1, repeatMinutes = 30))
            val book = CookPlaybook("Brisket", NewCookDraft("Brisket", dishes = listOf(DishDraft("Flat", "Beef"))), steps)
            val id = companion.save(book)
            val cook = repo.startCook(book.draft.copy(playbookId = id))
            val dish = db.cookDao().getDishesForCook(cook).single().id
            listOf(async { companion.completeStep(cook, "on", 0, 1_000_000) }, async { companion.completeStep(cook, "on", 0, 1_000_000) }).awaitAll()
            assertEquals(1, db.cookDao().getTimelineEventsForCook(cook).count { it.eventType == "meat_on" })
            repo.addTimelineEvent(cook, dish, "spritz", "Spritz", null, 2_000_000)
            assertEquals(1, companion.plan(cook)!!.progress.getValue("spritz").occurrence)
            companion.snooze(cook, "spritz", 0, 9_000_000)
            assertNull(companion.plan(cook)!!.progress.getValue("spritz").snoozedUntil)
            companion.skip(cook, "spritz")
            assertEquals(1, db.cookDao().getTimelineEventsForCook(cook).count { it.eventType == "spritz" })
            assertEquals("skipped", companion.plan(cook)!!.progress.getValue("spritz").status)
        } finally { db.close() }
    }
}
