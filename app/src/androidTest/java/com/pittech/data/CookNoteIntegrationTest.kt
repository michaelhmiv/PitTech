package com.pittech.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookNoteIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: PitTechDatabase
    private lateinit var repo: CookRepository
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build(); repo = CookRepository(db, PhotoStorage(context)) }
    @After fun cleanup() { runBlocking { db.cookDao().getAllPhotos().forEach { PhotoStorage(context).delete(it.relativePath) } }; db.close() }
    private suspend fun cook() = repo.startCook(NewCookDraft("Note test", dishes = listOf(DishDraft("Flat", "Beef"), DishDraft("Point", "Beef"))))
    private suspend fun photo(id: String): NotePhoto {
        val image = File.createTempFile("note-test", ".jpg", context.cacheDir)
        image.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return repo.notes.stagePhoto(id, Uri.fromFile(image).toString()).also { image.delete() }
    }
    @Test fun combinedNoteEditAndUndoKeepOneIdentityAndOwnedReadings(): Unit = runBlocking {
        val id = cook(); val dishes = db.cookDao().getDishesForCook(id)
        var draft = CookNoteDraft(cookId = id, category = "temperature", action = "wrap", dishIds = dishes.map { it.id },
            description = "Bark set", occurredAt = 42L, temperatures = listOf(NoteTemperature(valueText = "165", measuredAt = 42L)))
        draft = draft.copy(photos = listOf(photo(draft.id), photo(draft.id)))
        repo.notes.save(CookNoteDraft(cookId = id, action = "food_on", occurredAt = 40L))
        repo.notes.save(draft)
        repo.notes.save(draft.copy(description = "Wrapped with broth"))
        val detail = repo.observeCookDetail(id).first()!!
        assertEquals(1, detail.events.count { it.id == draft.id })
        assertEquals(42L, detail.events.first { it.id == draft.id }.occurredAtUtcMillis)
        assertEquals(2, detail.photos.count { it.eventId == draft.id })
        assertEquals(1, detail.logReadingLinks.size)
        assertEquals(2, notePlanEvents(detail.events.filter { it.id == draft.id }).size)
        val bundle = repo.notes.delete(draft.id)
        assertTrue(db.experienceDao().readingsForLog(draft.id).isEmpty())
        assertTrue(db.cookDao().getPhotosForCook(id).all { it.eventId == null })
        repo.notes.restore(bundle)
        assertEquals(bundle.readings.single(), db.experienceDao().readingsForLog(draft.id).single())
        assertEquals(2, db.cookDao().getPhotosForCook(id).count { it.eventId == draft.id })
    }
    @Test fun failedPhotoRetryUsesSameNoteAndCrossCookReadingIsRejected(): Unit = runBlocking {
        val id = cook(); val other = cook()
        val draft = CookNoteDraft(cookId = id, description = "Retry", photos = listOf(NotePhoto(uri = "file:///missing-photo.jpg")))
        assertFalse(repo.notes.save(draft).photoAttached)
        assertFalse(repo.notes.save(draft).photoAttached)
        assertEquals(1, db.cookDao().getTimelineEventsForCook(id).count { it.id == draft.id })
        assertNotNull(db.experienceDao().draft(draft.id))
        val foreign = CookNoteDraft(cookId = other, temperatures = listOf(NoteTemperature(valueText = "150")))
        repo.notes.save(foreign)
        try { repo.notes.save(CookNoteDraft(cookId = id, temperatures = foreign.temperatures)); fail("Cross-cook reading accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(other, db.experienceDao().reading(foreign.temperatures.single().id)!!.cookId)
        assertEquals(1, db.cookDao().getTimelineEventsForCook(id).count { it.eventType == "note" })
    }
    /**
     * PL-07 (issue #172): the composer is the only temperature editor now
     * that the standalone Temperature Entry dialog is retired. Saving a
     * composer draft whose temperature carries a standalone manual reading's
     * identity adopts that reading into the note — but a reading owned by
     * another note, a device reading, or another cook's reading can never
     * be taken over.
     */
    @Test fun composerAdoptsStandaloneManualReadingButNeverSteals(): Unit = runBlocking {
        val id = cook(); val dishes = db.cookDao().getDishesForCook(id)
        val standalone = repo.addManualTemperature(id, dishes[0].id, "Hand thermometer", "food_probe", 150.0, "°F", 100L)
        val adoption = CookNoteDraft(cookId = id, dishId = dishes[0].id, category = "temperature", occurredAt = 100L,
            temperatures = listOf(NoteTemperature(id = standalone.id, probeName = "Hand thermometer", measurementType = "food_probe",
                valueText = "171", unit = "°F", measuredAt = 100L, dishId = dishes[0].id, probeId = standalone.probeId)))
        repo.notes.save(adoption)
        assertEquals(171.0, db.experienceDao().reading(standalone.id)!!.value, 0.0)
        assertEquals(listOf(standalone.id), db.experienceDao().readingsForLog(adoption.id).map { it.id })
        // The adopted reading is now owned; a second note cannot steal it.
        try { repo.notes.save(CookNoteDraft(cookId = id, category = "temperature", temperatures = adoption.temperatures)); fail("Owned reading stolen") } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(standalone.id), db.experienceDao().readingsForLog(adoption.id).map { it.id })
        // A device reading is never adoptable.
        db.cookDao().insertSensorReading(SensorReadingEntity("device-1", id, null, null, "Pit", "pit", 225.0, "°F", 100L, "UTC", "controller", recordedAtUtcMillis = 100L))
        try { repo.notes.save(CookNoteDraft(cookId = id, category = "temperature",
            temperatures = listOf(NoteTemperature(id = "device-1", probeName = "Pit", measurementType = "pit", valueText = "226", unit = "°F", measuredAt = 100L)))); fail("Device reading adopted") } catch (_: IllegalArgumentException) { }
        assertEquals(225.0, db.experienceDao().reading("device-1")!!.value, 0.0)
        // Another cook's standalone manual reading is not adoptable either.
        val other = cook()
        val foreignStandalone = repo.addManualTemperature(other, null, "Other probe", "food", 140.0, "°F", 100L)
        try { repo.notes.save(CookNoteDraft(cookId = id, category = "temperature",
            temperatures = listOf(NoteTemperature(id = foreignStandalone.id, probeName = "Other probe", measurementType = "food", valueText = "141", unit = "°F", measuredAt = 100L)))); fail("Cross-cook reading adopted") } catch (_: IllegalArgumentException) { }
        assertEquals(140.0, db.experienceDao().reading(foreignStandalone.id)!!.value, 0.0)
    }
    /**
     * PL-07 (issue #172): the note-link deletion contract, stated plainly —
     * deleting a note deletes the note and the readings it owns; its photos
     * are NOT deleted, they stay in the cook's gallery with the link severed
     * (and undo restores the link).
     */
    @Test fun deletingNoteDeletesOwnedReadingsButPhotosStayInGalleryUnlinked(): Unit = runBlocking {
        val id = cook()
        var draft = CookNoteDraft(cookId = id, category = "temperature", description = "Delete semantics",
            temperatures = listOf(NoteTemperature(valueText = "160")))
        draft = draft.copy(photos = listOf(photo(draft.id)))
        repo.notes.save(draft)
        assertEquals(1, db.cookDao().getPhotosForCook(id).count { it.eventId == draft.id })
        val bundle = repo.notes.delete(draft.id)
        assertNull(db.cookDao().getTimelineEvent(draft.id))
        assertTrue(db.experienceDao().readingsForLog(draft.id).isEmpty())
        assertNull(db.experienceDao().reading(draft.temperatures.single().id))
        val gallery = db.cookDao().getPhotosForCook(id)
        assertEquals(1, gallery.size)
        assertNull(gallery.single().eventId)
        repo.notes.restore(bundle)
        assertEquals(1, db.cookDao().getPhotosForCook(id).count { it.eventId == draft.id })
        assertEquals(1, db.experienceDao().readingsForLog(draft.id).size)
    }
    /**
     * PL-07 (issue #172): a note created by a reminder check-in is a real
     * composer note — it loads into the composer model and edits there under
     * the same identity instead of living in a side channel.
     */
    @Test fun reminderCheckInNoteLoadsAndEditsAsAComposerNote(): Unit = runBlocking {
        val id = cook()
        val reminder = repo.createCookReminder(id, "Check the bark", System.currentTimeMillis() + 60_000)
        val checkIn = repo.completeCookReminder(reminder.id, "Bark looks dark")!!
        val loaded = repo.notes.load(checkIn.event.id)
        assertEquals("Bark looks dark", loaded.description)
        assertEquals("check_in", loaded.action)
        repo.notes.save(loaded.copy(description = "Bark looks dark; wrapped"))
        assertEquals("Bark looks dark; wrapped", db.cookDao().getTimelineEvent(checkIn.event.id)!!.details)
        assertEquals(1, db.cookDao().getTimelineEventsForCook(id).count { it.id == checkIn.event.id })
    }
    @Test fun portableArchiveKeepsMetadataLinksAndTimingWithoutDrafts(): Unit = runBlocking {
        val id = cook(); val draft = CookNoteDraft(cookId = id, category = "temperature", action = "food_on", temperatures = listOf(NoteTemperature(valueText = "64", unit = "°C")))
        repo.notes.save(draft); repo.notes.persistDraft(CookNoteDraft(cookId = id, description = "Unfinished"))
        val bytes = ByteArrayOutputStream(); val transfer = PitTechDataTransfer(context, repo); transfer.writeZip(bytes)
        val preview = transfer.previewImport(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(PitTechDataTransfer.ARCHIVE_VERSION, preview.archiveVersion)
        assertEquals(draft.metadata.encode(), preview.snapshot.events.first { it.id == draft.id }.noteMetadataJson)
        assertEquals("explicit_phases", preview.snapshot.lifecycles.single().timingMode)
        val other = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val restored = CookRepository(other, PhotoStorage(context)); restored.importSnapshot(preview.snapshot, preview.attachments)
            assertEquals(draft.temperatures.single().id, other.experienceDao().readingsForLog(draft.id).single().id)
            assertEquals("°C", other.experienceDao().readingsForLog(draft.id).single().unit)
            assertTrue(other.experienceDao().observeDrafts(id).first().isEmpty())
        } finally { other.close() }
    }
}
