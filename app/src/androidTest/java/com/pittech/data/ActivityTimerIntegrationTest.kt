package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ActivityTimerIntegrationTest {
    @Test fun overlappingTimersSurviveReopenAndSaveEachMeasuredDurationOnce(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "activity-timers-test"; context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build()
        try {
            var repo = CookRepository(db, PhotoStorage(context))
            val cook = repo.startCook(NewCookDraft("Mac", dishes = listOf(DishDraft("Mac", "Pasta"))))
            val dish = db.cookDao().getDishesForCook(cook).single().id
            val now = System.currentTimeMillis()
            val pasta = repo.activities.start(cook, dish, "Boiled macaroni", "stovetop", 480000, now - 480000)
            val sauce = repo.activities.start(cook, dish, "Made sauce", "stovetop", at = now - 120000)
            db.close(); db = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build(); repo = CookRepository(db, PhotoStorage(context))
            assertEquals(2, repo.activities.observe(cook).first().count { it.status == "running" })
            val note = repo.activities.stop(pasta.id, now)
            assertEquals(480000L, note.durationMillis)
            assertEquals(note.id, repo.activities.stop(pasta.id, now + 100).id)
            repo.notes.save(note); repo.notes.save(note)
            assertEquals(1, db.cookDao().getTimelineEventsForCook(cook).count { it.id == note.id })
            assertEquals("logged", db.activityTimerDao().get(pasta.id)!!.status)
            assertEquals("running", db.activityTimerDao().get(sauce.id)!!.status)
            val loaded = repo.notes.load(note.id); assertEquals(note.durationMillis, loaded.durationMillis); assertEquals("stovetop", loaded.method)
            val snapshot = repo.exportSnapshot()
            val other = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
            try {
                CookRepository(other, PhotoStorage(context)).importSnapshot(snapshot, emptyMap())
                assertTrue(other.activityTimerDao().all().all { it.status == "inactive" })
                assertEquals(now - 480000, other.activityTimerDao().get(pasta.id)!!.startedAtUtcMillis)
            } finally { other.close() }
            repo.completeCook(cook); assertEquals("cancelled", db.activityTimerDao().get(sauce.id)!!.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
