package com.pittech.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderIntegrationTest {
    @Test fun immediateManualThresholdDeliversOnceAndOldOccurrenceActionsAreIgnored(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val cooks = CookRepository(db, PhotoStorage(context)); val cook = cooks.startCook(NewCookDraft("Threshold meal", dishes = listOf(DishDraft("Mac", "Pasta"))))
            val dish = db.cookDao().getDishesForCook(cook).single().id; val now = System.currentTimeMillis()
            val reading = cooks.addManualTemperature(cook, dish, "Food", "food_probe", 170.0, "°F", now)
            val reminders = ReminderRepository(db); val trigger = ReminderTrigger.Probe(reading.channelIdentity(), reading.probeId, reading.measurementType, "above", 165.0)
            val id = reminders.save(cook, dish, "Check the food", trigger)
            reminders.reconcile(context, cook); reminders.reconcile(context, cook)
            val receipt = db.reminderDao().history(id).single { it.sequence > 0 }
            assertEquals("delivered", receipt.status)
            reminders.change(context, id, "complete", expectedSequence = receipt.sequence + 1); assertTrue(db.reminderDao().rule(id)!!.enabled)
            reminders.change(context, id, "complete", expectedSequence = receipt.sequence); assertFalse(db.reminderDao().rule(id)!!.enabled)
            reminders.save(cook, dish, "A new decision", ReminderTrigger.Duration(System.currentTimeMillis(), 600000), id = id)
            assertTrue(db.reminderDao().history(id).none { it.sequence > 0 && it.status == "queued" })
            reminders.change(context, id, "remove")
        } finally { db.close() }
    }
    @Test fun rulesAndReceiptsSurviveReopenRestoreInactiveAndRecordOneExplicitAction(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reminders-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build()
        try {
            var cooks = CookRepository(db, PhotoStorage(context)); val cookId = cooks.startCook(NewCookDraft("Meal", dishes = listOf(DishDraft("Mac", "Pasta"))))
            val dishId = db.cookDao().getDishesForCook(cookId).single().id
            var reminders = ReminderRepository(db)
            val id = reminders.save(cookId, dishId, "Stir", ReminderTrigger.Duration(System.currentTimeMillis(), 600000))
            val original = db.reminderDao().rule(id)!!.triggerConfig
            db.close(); db = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build(); cooks = CookRepository(db, PhotoStorage(context)); reminders = ReminderRepository(db)
            assertEquals(original, db.reminderDao().rule(id)!!.triggerConfig)
            reminders.change(context, id, "skip"); assertTrue(db.cookDao().getTimelineEventsForCook(cookId).none { PlaybookCodec.action(it.eventType, it.noteMetadataJson) == "wrap" })
            reminders.change(context, id, "toggle")
            val event = reminders.confirmAction(context, id, "food_on", cooks)
            assertEquals(event, reminders.confirmAction(context, id, "food_on", cooks))
            assertEquals(1, db.cookDao().getTimelineEventsForCook(cookId).count { it.id == event })
            val snapshot = cooks.exportSnapshot(); ReminderArchive.validate(snapshot)
            val restored = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
            try { val repo = CookRepository(restored, PhotoStorage(context)); repo.importSnapshot(snapshot, emptyMap()); repo.importSnapshot(snapshot, emptyMap()); assertEquals(1, restored.reminderDao().rules().size); assertTrue(restored.reminderDao().rules().none { it.enabled }); assertEquals("inactive", restored.reminderDao().rules().single().status); assertEquals(event, restored.reminderDao().occurrences().first { it.sequence == 0 }.actionEventId) } finally { restored.close() }
            val historical = reminders.save(cookId, dishId, "Last time wrapped", ReminderTrigger.Historical("food_on", 12000000), sourceRevision = "frozen-version", sourceMilestone = "original-wrap")
            reminders.reconcile(context, cookId)
            val actual = db.cookDao().getTimelineEvent(event)!!.occurredAtUtcMillis
            assertEquals(actual + 12000000, db.reminderDao().history(historical).first { it.sequence == 0 }.dueAtUtcMillis)
            cooks.completeCook(cookId); reminders.reconcile(context, cookId); assertFalse(db.reminderDao().rule(historical)!!.enabled)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
