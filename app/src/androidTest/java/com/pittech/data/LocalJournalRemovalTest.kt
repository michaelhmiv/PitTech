package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class) class LocalJournalRemovalTest {
    private val token = "A".repeat(43)
    private val user = "12345678-1234-4234-8234-123456789abc"
    private fun database(context: Context, name: String) = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build()
    private suspend fun populatedCook(db: PitTechDatabase, photos: PhotoStorage): String {
        val repo = CookRepository(db, photos)
        val cook = repo.startCook(NewCookDraft("Brisket", dishes = listOf(DishDraft("Point", "Beef"))))
        val dish = db.cookDao().getDishesForCook(cook).single().id
        val now = System.currentTimeMillis()
        repo.addTimelineEvent(cook, dish, "note", "Wrapped", "Butcher paper", now)
        repo.activities.start(cook, dish, "Rested the point", "resting", 1800000, now - 1800000)
        ReminderRepository(db).save(cook, dish, "Check the bark", ReminderTrigger.Duration(now, 600000))
        return cook
    }
    @Test fun removalClearsEveryJournalTableAndFile(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "journal-removal-${UUID.randomUUID()}.db"; context.deleteDatabase(name)
        val photos = PhotoStorage(context)
        val photo = "photos/journal-removal-${UUID.randomUUID()}.jpg"
        val draft = File(context.filesDir, "note-drafts/journal-removal-${UUID.randomUUID()}/staged.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val db = database(context, name)
        try {
            val cook = populatedCook(db, photos)
            photos.writeImported(photo, byteArrayOf(4, 5, 6))
            assertEquals(1, db.cookDao().getAllCooks().size)
            // startCook seeds a "cook_started" timeline event; populatedCook adds the note event.
            assertEquals(2, db.cookDao().getTimelineEventsForCook(cook).size)
            assertEquals(1, db.activityTimerDao().all().size)
            assertEquals(1, db.reminderDao().rules().size)
            assertTrue(File(context.filesDir, photo).isFile); assertTrue(draft.isFile)
            db.cookDao().getCook(cook)!!.let { assertNotEquals(CookStatus.COMPLETED, it.status) }
            CookRepository(db, photos).completeCook(cook)
            LocalJournalRemoval(db, photos).remove()
            assertTrue(db.cookDao().getAllCooks().isEmpty())
            assertTrue(db.cookDao().getTimelineEventsForCook(cook).isEmpty())
            assertTrue(db.activityTimerDao().all().isEmpty())
            assertTrue(db.reminderDao().rules().isEmpty())
            assertTrue(db.reminderDao().occurrences().isEmpty())
            assertTrue(db.recordingDao().getAllRecordings().isEmpty())
            assertTrue(db.cookDao().getAllPhotos().isEmpty())
            assertFalse(File(context.filesDir, photo).exists())
            assertFalse(File(context.filesDir, "photos").exists())
            assertFalse(File(context.filesDir, "note-drafts").exists())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun removalRefusesActiveCooksAndInProgressRecordings(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "journal-removal-refusal-${UUID.randomUUID()}.db"; context.deleteDatabase(name)
        val photos = PhotoStorage(context)
        val db = database(context, name)
        try {
            val repo = CookRepository(db, photos)
            val cook = repo.startCook(NewCookDraft("Ribs", dishes = listOf(DishDraft("Rack", "Pork"))))
            val removal = LocalJournalRemoval(db, photos)
            try { removal.remove(); fail("Active cook removed") } catch (blocked: LocalJournalRemovalBlocked) { assertTrue(blocked.message!!.contains("active cook")) }
            repo.pauseCook(cook)
            try { removal.remove(); fail("Paused cook removed") } catch (blocked: LocalJournalRemovalBlocked) { assertTrue(blocked.message!!.contains("active cook")) }
            repo.resumeCook(cook)
            repo.completeCook(cook)
            // completeCook stops recordings, so the in-progress row is written directly afterwards.
            val now = System.currentTimeMillis()
            db.recordingDao().saveRecording(CookRecordingEntity(cook, "device", "controller", CookRecordingEntity.RECORDING, "F", now, now))
            try { removal.remove(); fail("In-progress recording removed") } catch (blocked: LocalJournalRemovalBlocked) { assertTrue(blocked.message!!.contains("recording")) }
            assertEquals(1, db.cookDao().getAllCooks().size)
            assertEquals(1, db.recordingDao().getAllRecordings().size)
            db.recordingDao().saveRecording(db.recordingDao().getRecording(cook)!!.copy(status = CookRecordingEntity.STOPPED))
            removal.remove()
            assertTrue(db.cookDao().getAllCooks().isEmpty())
            assertTrue(db.recordingDao().getAllRecordings().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun removalLeavesAccountCredentialsAndCommunityStateUntouched(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "test-" + UUID.randomUUID().toString()
        val file = File(context.noBackupFilesDir, "account-$namespace-v1/state")
        val alias = "pittech-account-$namespace-v1"
        val root = File(context.noBackupFilesDir, "publication-test-" + UUID.randomUUID())
        val name = "journal-removal-untouched-${UUID.randomUUID()}.db"; context.deleteDatabase(name)
        val db = database(context, name)
        try {
            val vault = AccountVault(context, namespace)
            val state = AccountState(
                session = AccountSession(token, token, "2100-01-01T00:00:00.000Z", "2100-02-01T00:00:00.000Z", user),
                deletion = DeletionRecord(user, UUID.randomUUID().toString(), token),
            )
            vault.save(state)
            val communityBefore = CommunityVault(context).load()
            val operations = PublicationOperationsStore(root)
            val item = UUID.randomUUID().toString()
            val op = operations.prepare(user, "POST", "/v1/recipes/$item/like", "{}".toByteArray(), false)
            val photos = PhotoStorage(context)
            CookRepository(db, photos).completeCook(populatedCook(db, photos))
            LocalJournalRemoval(db, photos).remove()
            assertTrue(db.cookDao().getAllCooks().isEmpty())
            assertEquals(state, vault.load())
            assertEquals(communityBefore, CommunityVault(context).load())
            assertEquals(op.id, PublicationOperationsStore(root).pending(user)?.id)
            assertTrue(PublicationOperationsStore(root).body(operations.pending(user)!!).exists())
        } finally { db.close(); context.deleteDatabase(name); root.deleteRecursively(); file.parentFile!!.deleteRecursively(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
    @Test fun emptiedJournalStaysEmptyAfterReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "journal-removal-reopen-${UUID.randomUUID()}.db"; context.deleteDatabase(name)
        val photos = PhotoStorage(context)
        val photo = "photos/journal-removal-${UUID.randomUUID()}.jpg"
        val draft = File(context.filesDir, "note-drafts/journal-removal-${UUID.randomUUID()}/staged.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7)) }
        var db = database(context, name)
        try {
            val repo = CookRepository(db, photos)
            repo.completeCook(populatedCook(db, photos))
            photos.writeImported(photo, byteArrayOf(8))
            LocalJournalRemoval(db, photos).remove()
            db.close(); db = database(context, name)
            assertTrue(db.cookDao().getAllCooks().isEmpty())
            assertTrue(db.reminderDao().rules().isEmpty())
            assertTrue(db.activityTimerDao().all().isEmpty())
            assertFalse(File(context.filesDir, photo).exists()); assertFalse(draft.exists())
            assertFalse(File(context.filesDir, "photos").exists())
            assertFalse(File(context.filesDir, "note-drafts").exists())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
