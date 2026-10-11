package com.pittech.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CookPhaseIntegrationTest {
    @Test fun prepAndAlreadyCookingUseActualActionsAndPauseDoesNotStopPhysicalClock(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val start = System.currentTimeMillis() - 60_000
            val prep = repo.startCook(NewCookDraft("Overnight prep", dishes = listOf(DishDraft("Flat", "Beef"))))
            assertTrue(notePlanEvents(db.cookDao().getTimelineEventsForCook(prep)).none { it.action == "food_on" })
            try { CookCompanionRepository(db, PhotoStorage(context)).stageAction(prep, db.cookDao().getDishesForCook(prep).single().id, "rest_start"); fail("Rest before Food on accepted") } catch (_: IllegalArgumentException) { }
            val active = repo.startCook(NewCookDraft("Already cooking", alreadyCooking = true, foodOnAtUtcMillis = start, dishes = listOf(DishDraft("Ribs", "Pork"))))
            repo.pauseCook(active)
            val detail = repo.observeCookDetail(active).first()!!
            assertEquals(CookStatus.PAUSED, detail.cook.status)
            assertTrue(CookPhases.all(notePlanEvents(detail.events), detail.dishes.map { it.id }, detail.cook.startedAtUtcMillis, start + 120_000).single().cookingMillis >= 120_000)
            db.experienceDao().putLifecycle(CookLifecycle(active))
            assertEquals("legacy_preserved", db.experienceDao().lifecycle(active)!!.timingMode)
            repo.adoptFoodOnTiming(active, null)
            assertEquals("explicit_phases", db.experienceDao().lifecycle(active)!!.timingMode)
            assertEquals(1, notePlanEvents(db.cookDao().getTimelineEventsForCook(active)).count { it.action == "food_on" })
            val added = repo.addDish(active, DishDraft("Chicken", "Poultry"))
            val current = repo.observeCookDetail(active).first()!!
            assertEquals("prep", CookPhases.all(notePlanEvents(current.events), current.dishes.map { it.id }, current.cook.startedAtUtcMillis, System.currentTimeMillis()).first { it.dishId == added }.phase)
            val removedDish = detail.dishes.single()
            repo.notes.save(CookNoteDraft(cookId = active, dishId = removedDish.id, action = "wrap", category = "action", title = "Wrap", occurredAt = start + 1_000))
            repo.deleteDish(removedDish)
            assertTrue(notePlanEvents(db.cookDao().getTimelineEventsForCook(active)).none { it.action == "wrap" })
            assertTrue(db.cookDao().getTimelineEventsForCook(active).any { it.details?.contains("Recorded for removed dish") == true })
        } finally { db.close() }
    }
}
