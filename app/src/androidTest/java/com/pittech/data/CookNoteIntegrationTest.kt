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
    @Test fun portableArchiveKeepsMetadataLinksAndTimingWithoutDrafts(): Unit = runBlocking {
        val id = cook(); val draft = CookNoteDraft(cookId = id, category = "temperature", action = "food_on", temperatures = listOf(NoteTemperature(valueText = "64", unit = "°C")))
        repo.notes.save(draft); repo.notes.persistDraft(CookNoteDraft(cookId = id, description = "Unfinished"))
        val bytes = ByteArrayOutputStream(); val transfer = PitTechDataTransfer(context, repo); transfer.writeZip(bytes)
        val preview = transfer.previewImport(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(6, preview.archiveVersion)
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
