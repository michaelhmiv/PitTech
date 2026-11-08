package com.pittech.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Recording-path half of the event × timeline sweep (issue #293): for every
 * playbook action and every path that offers it, a timeline event must
 * actually be written, in the shape the timeline renders (eventType "note"
 * + CookNoteMetadata action, or the lifecycle types CookRepository stores
 * raw). The presentation half lives in EventTimelineVisibilityTest.
 *
 * Only the paths' successful recordings are asserted here. The phase-gate
 * rejections (e.g. Start rest with the dish still in prep on an
 * explicit_phases cook) are the defect findings on issue #293; they are
 * deliberately NOT encoded as passing assertions.
 */
class EventRecordingPathsTest {
    private fun newDb() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<android.content.Context>(), PitTechDatabase::class.java).build()

    @Test fun stageActionsWriteTimelineEventsInValidPhaseOrder(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = newDb()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val companion = CookCompanionRepository(db, PhotoStorage(context))
            val cookId = repo.startCook(NewCookDraft("Stage chain", dishes = listOf(DishDraft("Ribs", "Pork"))))
            val dish = db.cookDao().getDishesForCook(cookId).single()
            // The only actions the stage path offers, in a phase-valid order.
            for (action in listOf("food_on", "rest_start", "hold_start", "dish_done")) {
                companion.stageAction(cookId, dish.id, action)
                val written = db.cookDao().getTimelineEventsForCook(cookId)
                    .single { PlaybookCodec.action(it.eventType, it.noteMetadataJson) == action }
                assertEquals("note", written.eventType)
                assertEquals("manual", written.source)
                assertEquals(PlaybookCodec.actions[action], written.title)
                assertEquals(action, CookNoteMetadata.decode(written.noteMetadataJson)?.action)
                // stageAction stamps events with the wall clock; keep the
                // phase order unambiguous for the next call's validation.
                Thread.sleep(3)
            }
        } finally { db.close() }
    }

    @Test fun composerActionDraftsWriteEventsForEveryPlaybookAction(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = newDb()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val cookId = repo.startCook(NewCookDraft("Composer actions", dishes = listOf(DishDraft("Ribs", "Pork"))))
            val dish = db.cookDao().getDishesForCook(cookId).single()
            val base = System.currentTimeMillis() - 60_000
            // A phase-valid order covering every playbook action.
            val order = listOf("food_on", "spritz", "wrap", "check_in", "remove", "rest_start", "hold_start", "dish_done")
            order.forEachIndexed { index, action ->
                // The exact draft CooksViewModel.recordDishActionNow builds
                // for the one-tap dish-card actions; the composer's own Save
                // writes through the same CookNoteRepository.save.
                val result = repo.notes.save(CookNoteDraft(
                    cookId = cookId, dishId = dish.id, category = "action", action = action,
                    title = PlaybookCodec.actions[action].orEmpty(), occurredAt = base + index * 5_000L))
                assertEquals(action, "note", result.event.eventType)
                assertEquals(action, action, CookNoteMetadata.decode(result.event.noteMetadataJson)?.action)
                assertEquals(action, "manual", result.event.source)
            }
            val stored = db.cookDao().getTimelineEventsForCook(cookId)
                .mapNotNull { CookNoteMetadata.decode(it.noteMetadataJson)?.action }
            assertTrue(stored.containsAll(order))
        } finally { db.close() }
    }

    @Test fun preparationActionsWriteEventsThroughTheComposer(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = newDb()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val cookId = repo.startCook(NewCookDraft("Preparation actions", dishes = listOf(DishDraft("Brisket", "Beef"))))
            val dish = db.cookDao().getDishesForCook(cookId).single()
            val base = System.currentTimeMillis() - 60_000
            // The composer's non-playbook actions; none are phase actions, so
            // all record even before Food on. (Their stored titles are the
            // generic fallback — the presentation layer supplies the label;
            // that half is covered by EventTimelineVisibilityTest.)
            listOf("preheat_start", "season", "trim", "marinate", "add_fuel").forEachIndexed { index, action ->
                val result = repo.notes.save(CookNoteDraft(
                    cookId = cookId, dishId = dish.id, category = "action", action = action,
                    occurredAt = base + index * 5_000L))
                assertEquals(action, "note", result.event.eventType)
                assertEquals(action, action, CookNoteMetadata.decode(result.event.noteMetadataJson)?.action)
            }
        } finally { db.close() }
    }

    @Test fun planStepCompletionWritesTheStepActionEvent(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = newDb()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val companion = CookCompanionRepository(db, PhotoStorage(context))
            val book = CookPlaybook("Meal", NewCookDraft("Meal", dishes = listOf(DishDraft("Ribs", "Pork"))),
                listOf(PlaybookStep(id = "check", dishIndex = 0, action = "check_in", title = "Check the ribs")))
            val bookId = companion.save(book)
            val cookId = repo.startCook(book.draft.copy(playbookId = bookId))
            companion.completeStep(cookId, "check", 0, System.currentTimeMillis())
            val written = db.cookDao().getTimelineEventsForCook(cookId)
                .single { CookNoteMetadata.decode(it.noteMetadataJson)?.action == "check_in" }
            assertEquals("note", written.eventType)
            assertEquals("Check the ribs", written.title)
            assertEquals("manual", written.source)
        } finally { db.close() }
    }

    @Test fun reminderCompletionWritesTheCheckInNote(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = newDb()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val cookId = repo.startCook(NewCookDraft("Reminder cook", dishes = listOf(DishDraft("Ribs", "Pork"))))
            db.cookDao().insertReminder(CookReminderEntity("reminder-1", cookId, "Spritz the ribs",
                System.currentTimeMillis(), "UTC", createdAtUtcMillis = System.currentTimeMillis()))
            repo.completeCookReminder("reminder-1", "Done, bark looks good")
            val written = db.cookDao().getTimelineEventsForCook(cookId)
                .single { CookNoteMetadata.decode(it.noteMetadataJson)?.action == "check_in" }
            assertEquals("note", written.eventType)
            assertEquals("Check-in: Spritz the ribs", written.title)
            assertEquals("Done, bark looks good", written.details)
            assertEquals("manual", written.source)
        } finally { db.close() }
    }

    @Test fun addTimelineEventNormalizesActionTypesAndKeepsLifecycleTypesRaw(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = newDb()
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val cookId = repo.startCook(NewCookDraft("Generic entries", dishes = listOf(DishDraft("Ribs", "Pork"))))
            val dish = db.cookDao().getDishesForCook(cookId).single()
            val at = System.currentTimeMillis()
            // The generic entry point (guidance quick actions, lifecycle
            // buttons) normalizes playbook action spellings — both the stored
            // mapping and the canonical action name — into the note shape.
            val meatOn = repo.addTimelineEvent(cookId, dish.id, "meat_on", "Ribs on", null, at)
            assertEquals("note", meatOn.eventType)
            assertEquals("food_on", CookNoteMetadata.decode(meatOn.noteMetadataJson)?.action)
            val rest = repo.addTimelineEvent(cookId, dish.id, "rest", "Ribs rest", null, at)
            assertEquals("note", rest.eventType)
            assertEquals("rest_start", CookNoteMetadata.decode(rest.noteMetadataJson)?.action)
            // Lifecycle types are not actions: they stay raw, with no metadata.
            val paused = repo.addTimelineEvent(cookId, null, "cook_paused", "Cook paused", null, at)
            assertEquals("cook_paused", paused.eventType)
            assertNull(paused.noteMetadataJson)
            assertEquals("manual", paused.source)
        } finally { db.close() }
    }
}
