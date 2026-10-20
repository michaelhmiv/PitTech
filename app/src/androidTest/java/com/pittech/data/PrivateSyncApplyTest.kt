package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.domain.NewCookDraft
import com.pittech.domain.DishDraft
import com.pittech.domain.ReminderTrigger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class) class PrivateSyncApplyTest {
    @Test fun remoteFinishAndDeletionCancelOnlyThatMealsRuntime(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val photos = PhotoStorage(context); val repo = CookRepository(db, photos)
            val cook = repo.startCook(NewCookDraft("Finishing")); val other = repo.startCook(NewCookDraft("Still cooking"))
            db.activityTimerDao().insert(ActivityTimer("timer-finish", cook, null, "Rest", null, 1000, null, null, "note-finish", "running"))
            db.activityTimerDao().insert(ActivityTimer("timer-other", other, null, "Hold", null, 1000, null, null, "note-other", "running"))
            db.recordingDao().saveRecording(CookRecordingEntity(cook, "device", "key", CookRecordingEntity.RECORDING, "°F", 1000, 1000))
            val rule = ReminderRepository(db).save(cook, null, "Check", ReminderTrigger.Duration(1000, 600000))
            val library = PrivateSyncRoomLibrary(db, repo, photos)
            val rows = library.archive().getJSONArray("cooks")
            val ended = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == cook }
                .put("status", "completed").put("endedAtUtcMillis", System.currentTimeMillis())
            library.apply(PrivateSyncApplyPlan(listOf(PrivateSyncUpsert("cooks", cook, ended)), emptyList(), emptyMap()))
            assertEquals("cancelled", db.activityTimerDao().get("timer-finish")!!.status)
            assertEquals("running", db.activityTimerDao().get("timer-other")!!.status)
            assertEquals(CookRecordingEntity.STOPPED, db.recordingDao().getRecording(cook)!!.status)
            assertFalse(db.reminderDao().rule(rule)!!.enabled)
            library.apply(PrivateSyncApplyPlan(emptyList(), listOf(PrivateSyncDelete("cooks", cook)), emptyMap()))
            assertNull(db.cookDao().getCook(cook)); assertNotNull(db.cookDao().getCook(other))
            assertEquals("running", db.activityTimerDao().get("timer-other")!!.status)
        } finally { db.close() }
    }
    private fun eventJson(id: String, cookId: String, title: String) = JSONObject()
        .put("id", id).put("cookId", cookId).put("eventType", "note").put("title", title).put("occurredAtUtcMillis", 1000)
        .put("recordedAtUtcMillis", 1000).put("timeZoneId", "UTC").put("source", "manual").put("createdAtUtcMillis", 1000).put("updatedAtUtcMillis", 2000)

    @Test fun mergedApplyUpsertsDeletesAndAdoptsPhotosWhilePreservingLocalActivation(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val photos = PhotoStorage(context)
        val tag = UUID.randomUUID().toString().take(8)
        val photoNew = "sync-$tag-new"
        val photoOld = "sync-$tag-old"
        try {
            val repo = CookRepository(db, photos)
            val cook = repo.startCook(NewCookDraft("Sync meal", dishes = listOf(DishDraft("Brisket", "Beef"), DishDraft("Spare", "Beef"))))
            val dishes = db.cookDao().getDishesForCook(cook)
            val dish = dishes.single { it.name == "Brisket" }.id; val spare = dishes.single { it.name == "Spare" }.id
            db.cookDao().insertEventsIgnoringDuplicates(listOf(TimelineEventEntity("note-1", cook, null, "note", "Taste check", null, 1000, 1000, "UTC", "manual", null, 1000, 1000, null, null)))
            val reminders = ReminderRepository(db)
            val ruleLocal = reminders.save(cook, dish, "Stir", ReminderTrigger.Duration(1000, 600000))
            val ruleConfig = db.reminderDao().rule(ruleLocal)!!.triggerConfig
            db.recordingDao().saveRecording(CookRecordingEntity(cook, "device-1", "key-1", CookRecordingEntity.RECORDING, "°F", 1000, 1000))
            db.cookDao().insertProbesIgnoringDuplicates(listOf(ProbeEntity("probe-1", cook, null, null, "Pit", "temperature", "manual", 1000)))
            db.recordingDao().insertAssignment(ProbeAssignmentEntity("assignment-1", cook, "probe-1", dish, 1000, null))
            db.activityTimerDao().insert(ActivityTimer("timer-1", cook, dish, "Rest", null, 1000, null, null, "timer-note-1", "running"))
            val doomed = "doomed-bytes".toByteArray()
            photos.writeImported("photos/$photoOld.jpg", doomed)
            db.cookDao().insertPhotosIgnoringDuplicates(listOf(PhotoEntity(photoOld, cook, null, null, "$photoOld.jpg", "photos/$photoOld.jpg", "image/jpeg", null, null, 1000, null)))
            val adopted = "adopted-bytes".toByteArray()
            val library = PrivateSyncRoomLibrary(db, repo, photos)
            library.apply(PrivateSyncApplyPlan(
                upserts = listOf(
                    PrivateSyncUpsert("events", "note-1", eventJson("note-1", cook, "Edited remotely")),
                    PrivateSyncUpsert("events", "note-9", eventJson("note-9", cook, "Remote note")),
                    PrivateSyncUpsert("photos", photoNew, JSONObject().put("id", photoNew).put("cookId", cook).put("mimeType", "image/jpeg").put("addedAtUtcMillis", 2000)),
                    PrivateSyncUpsert("reminderRules", "rule-remote", JSONObject().put("id", "rule-remote").put("cookId", cook).put("title", "Remote rule").put("kind", "duration").put("triggerConfig", ruleConfig).put("createdAtUtcMillis", 2000).put("updatedAtUtcMillis", 2000).put("schemaVersion", 1)),
                ),
                deletes = listOf(PrivateSyncDelete("dishes", spare), PrivateSyncDelete("photos", photoOld)),
                attachments = mapOf(photoNew to adopted),
            ))
            assertEquals("Edited remotely", db.cookDao().getTimelineEvent("note-1")!!.title)
            assertEquals("Remote note", db.cookDao().getTimelineEvent("note-9")!!.title)
            assertNull(db.cookDao().getDish(spare))
            assertEquals("photos/$photoNew.jpg", db.experienceDao().photo(photoNew)!!.relativePath)
            assertArrayEquals(adopted, photos.read("photos/$photoNew.jpg"))
            assertNull(db.experienceDao().photo(photoOld)); assertNull(photos.read("photos/$photoOld.jpg"))
            // Imported definitions arrive inactive with no delivery state. Nothing arms, records or schedules.
            val remote = db.reminderDao().rule("rule-remote")!!
            assertFalse(remote.enabled); assertEquals("inactive", remote.status)
            assertTrue(db.reminderDao().history("rule-remote").isEmpty())
            assertEquals("duration", remote.kind); assertEquals(ruleConfig, remote.triggerConfig)
            // Existing local monitoring and recording state is preserved exactly.
            val local = db.reminderDao().rule(ruleLocal)!!
            assertTrue(local.enabled); assertEquals("pending", local.status)
            assertEquals(listOf("state:$ruleLocal"), db.reminderDao().history(ruleLocal).map { it.id })
            assertEquals("recording", db.recordingDao().getRecording(cook)!!.status)
            assertEquals("assignment-1", db.recordingDao().assignmentAt("probe-1", 2000)!!.id)
            assertEquals("running", db.activityTimerDao().get("timer-1")!!.status)
        } finally {
            runCatching { runBlocking { photos.delete("photos/$photoNew.jpg"); photos.delete("photos/$photoOld.jpg") } }
            db.close()
        }
    }

    @Test fun failedApplyLeavesLibraryByteIdentical(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val photos = PhotoStorage(context)
        val tag = UUID.randomUUID().toString().take(8)
        val photoNew = "sync-$tag-rollback"
        val photoExisting = "sync-$tag-existing"
        try {
            val repo = CookRepository(db, photos)
            val cook = repo.startCook(NewCookDraft("Rollback meal"))
            photos.writeImported("photos/$photoExisting.jpg", "original-photo".toByteArray())
            db.cookDao().insertPhotos(listOf(PhotoEntity(photoExisting, cook, null, null, "$photoExisting.jpg", "photos/$photoExisting.jpg", "image/jpeg", null, null, 1000, null)))
            db.cookDao().insertEventsIgnoringDuplicates(listOf(TimelineEventEntity("note-1", cook, null, "note", "Original", null, 1000, 1000, "UTC", "manual", null, 1000, 1000, null, null)))
            val before = archiveSnapshotJson(repo.exportSnapshot(), null).toString()
            val library = PrivateSyncRoomLibrary(db, repo, photos)
            library.applyInterceptor = { error("Synthetic mid-transaction failure.") }
            try {
                library.apply(PrivateSyncApplyPlan(
                    upserts = listOf(
                        PrivateSyncUpsert("events", "note-1", eventJson("note-1", cook, "Clobbered")),
                        PrivateSyncUpsert("events", "note-9", eventJson("note-9", cook, "Partial")),
                        PrivateSyncUpsert("photos", photoNew, JSONObject().put("id", photoNew).put("cookId", cook).put("mimeType", "image/jpeg").put("addedAtUtcMillis", 2000)),
                        PrivateSyncUpsert("photos", photoExisting, JSONObject().put("id", photoExisting).put("cookId", cook).put("mimeType", "image/jpeg").put("addedAtUtcMillis", 2000)),
                    ),
                    deletes = emptyList(),
                    attachments = mapOf(photoNew to "partial-bytes".toByteArray(), photoExisting to "replacement-photo".toByteArray()),
                ))
                fail()
            } catch (_: Exception) { }
            library.applyInterceptor = null
            assertEquals(before, archiveSnapshotJson(repo.exportSnapshot(), null).toString())
            assertNull(db.cookDao().getTimelineEvent("note-9"))
            assertNull(photos.read("photos/$photoNew.jpg"))
            assertArrayEquals("original-photo".toByteArray(), photos.read("photos/$photoExisting.jpg"))
            assertEquals("Original", db.cookDao().getTimelineEvent("note-1")!!.title)
        } finally {
            runCatching { runBlocking { photos.delete("photos/$photoNew.jpg"); photos.delete("photos/$photoExisting.jpg") } }
            db.close()
        }
    }
}
