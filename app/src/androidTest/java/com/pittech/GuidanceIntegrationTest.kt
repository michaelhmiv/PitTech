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
    @Test fun deletingGuidedDishPreservesOtherDishAndRestorablePlan() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, PitTechDatabase::class.java).build()
        try {
            val companion = CookCompanionRepository(db, PhotoStorage(app)); val repo = CookRepository(db, PhotoStorage(app))
            val book = CookPlaybook("Meal", NewCookDraft("Meal", dishes = listOf(DishDraft("Flat", "Beef"), DishDraft("Ribs", "Pork"))), listOf(PlaybookStep(id = "flat", dishIndex = 0, trigger = "elapsed"), PlaybookStep(id = "ribs", dishIndex = 1, trigger = "elapsed")))
            val id = companion.save(book); val cook = repo.startCook(book.draft.copy(playbookId = id))
            val original = companion.plan(cook)!!
            repo.addTimelineEvent(cook, original.dishIds[1], "meat_on", "Ribs on", null, 1_000_000)
            repo.addTimelineEvent(cook, original.dishIds[0], "rest", "Flat rests", null, 2_000_000)
            CookAlertRepository(db).save(cook, "Flat target", CookAlertRule(dishId = original.dishIds[0], targetF = 200.0))
            repo.deleteDish(db.cookDao().getDish(original.dishIds[0])!!)
            val remaining = companion.plan(cook)!!
            assertEquals(listOf(original.dishIds[1]), remaining.dishIds)
            assertEquals(0, remaining.book.steps.single().dishIndex)
            assertFalse(db.companionDao().forCook(cook).any { it.kind == "alert" })
            val events = db.cookDao().getTimelineEventsForCook(cook).map { PlanEvent(it.id, PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) }
            assertEquals("cooking", CookPlanEngine.stage(events, remaining.dishIds.single()))
            val bytes = java.io.ByteArrayOutputStream(); PitTechDataTransfer(app, repo).writeZip(bytes)
            assertEquals(1, PitTechDataTransfer(app, repo).previewImport(java.io.ByteArrayInputStream(bytes.toByteArray())).cookCount)
        } finally { db.close() }
    }
    @Test fun snoozeRearmsReminderAndWholeCookActionsUseStableDishMappings() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, PitTechDatabase::class.java).build()
        try {
            val companion = CookCompanionRepository(db, PhotoStorage(app)); val repo = CookRepository(db, PhotoStorage(app))
            val book = CookPlaybook("Two dishes", NewCookDraft("Two dishes", dishes = listOf(DishDraft("Flat", "Beef"), DishDraft("Ribs", "Pork"))), listOf(
                PlaybookStep(id = "flat-on", dishIndex = 0, action = "food_on"), PlaybookStep(id = "ribs-on", dishIndex = 1, action = "food_on"), PlaybookStep(id = "check", dishIndex = 0, trigger = "elapsed", minutes = 60)))
            val id = companion.save(book); val cook = repo.startCook(book.draft.copy(playbookId = id))
            val plan = companion.plan(cook)!!
            assertEquals("Flat", db.cookDao().getDish(plan.dishIds[0])!!.name)
            assertEquals("Ribs", db.cookDao().getDish(plan.dishIds[1])!!.name)
            repo.addTimelineEvent(cook, null, "meat_on", "Both dishes on", null, 1_000_000)
            assertEquals("done", companion.plan(cook)!!.progress.getValue("flat-on").status)
            assertEquals("done", companion.plan(cook)!!.progress.getValue("ribs-on").status)
            assertTrue(companion.markNotified(cook, "check", 0))
            assertFalse(companion.markNotified(cook, "check", 0))
            companion.snooze(cook, "check", 0, 9_000_000)
            assertNull(companion.plan(cook)!!.progress.getValue("check").notifiedOccurrence)
            assertFalse(CookPlanEngine.evaluate(companion.plan(cook)!!, listOf(PlanEvent("on", "food_on", null, 1_000_000)), emptyList(), 8_000_000).last().ready)
            assertTrue(companion.markNotified(cook, "check", 0))
            assertEquals(1, db.cookDao().getTimelineEventsForCook(cook).count { it.eventType == "meat_on" })
        } finally { db.close() }
    }
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
