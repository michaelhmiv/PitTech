package com.pittech.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.CameraPhotoFiles
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Inputs come from a successful native simulator run, not a hand-written JSON approximation. */
@RunWith(AndroidJUnit4::class)
class CrossPlatformArchiveTest {
    @Test fun nativeVersionNineDefinitionsAndDeliveryStateRestoreInactive(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context)); val transfer = PitTechDataTransfer(context, repo)
            val bytes = instrumentation.context.assets.open("cross-platform/native-guide-meal-v10.zip").use { it.readBytes() }
            val preview = transfer.previewImport(ByteArrayInputStream(bytes)); assertTrue(preview.archiveVersion in 9..PitTechDataTransfer.ARCHIVE_VERSION); assertEquals(1, preview.snapshot.reminderRules.size)
            repo.importSnapshot(preview.snapshot, preview.attachments); repo.importSnapshot(preview.snapshot, preview.attachments)
            val rule = db.reminderDao().rules().single(); assertFalse(rule.enabled); assertEquals("inactive", rule.status)
            assertEquals("historical", rule.kind); assertEquals(12000000L, (ReminderTriggerCodec.decode(rule.triggerConfig) as ReminderTrigger.Historical).offsetMillis)
            assertTrue(db.reminderDao().occurrences().filter { it.sequence == 0 }.none { it.active })
            val stateRecord = db.companionDao().observeAll().first().single { it.kind == "guide_presentation" }
            val state = GuidePresentation.decode(stateRecord.payload)
            assertTrue(state.visible);assertEquals(2,state.dishIds!!.size)
            val pinned = db.companionDao().observeAll().first().filter { it.kind == "recipe_attempt" }.mapNotNull { JSONObject(it.payload).optJSONObject("guide") }
            assertEquals(3,pinned.size)
            val sample = pinned.flatMap { CookPackHistory.rows(it.getJSONObject("story"),"readings") }.single()
            assertEquals("°C",sample.getString("unit"));assertEquals(74.125,sample.getDouble("value"),0.0)
        } finally { db.close() }
    }
    @Test fun nativeVersionEightReadsAndAndroidWriterProducesOwnedVersionNineFixture(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repo = CookRepository(db, PhotoStorage(context)); val transfer = PitTechDataTransfer(context, repo)
            val nativeBytes = instrumentation.context.assets.open("cross-platform/native-meal-v8.zip").use { it.readBytes() }
            val preview = transfer.previewImport(ByteArrayInputStream(nativeBytes)); assertEquals(8, preview.archiveVersion)
            assertEquals("°F", preview.preferences!!.temperatureUnit)
            repo.importSnapshot(preview.snapshot, preview.attachments)
            assertEquals(3, db.recipeDao().recipes().size); assertEquals(3, db.recipeDao().sources().size)
            val meal = db.cookDao().getAllCooks().first { it.status != CookStatus.COMPLETED }
            val mac = db.cookDao().getDishesForCook(meal.id).first { it.name.contains("Mac", true) }
            val at = System.currentTimeMillis() - 600000
            val cameraFile = CameraPhotoFiles.create(context); val uri = CameraPhotoFiles.uri(context, cameraFile)
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.YELLOW) }
            context.contentResolver.openOutputStream(uri, "w")!!.use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; image.recycle()
            val note = repo.notes.save(CookNoteDraft(cookId = meal.id, dishId = mac.id, title = "Android pasta", description = "Four ounces of Gouda", occurredAt = at, method = "stovetop", durationMillis = 480000,
                ingredients = listOf(IngredientDraft("Gouda", amountText = "4.00", amountUnit = "oz")),
                temperatures = listOf(NoteTemperature(probeName = "Android food", valueText = "85", unit = "°C", measuredAt = at, dishId = mac.id)),
                photos = listOf(NotePhoto(uri = uri.toString(), caption = "Android preparation photo")))).event
            repo.addTimelineEvent(meal.id, mac.id, "meat_on", "Food on", null, at + 300000)
            repo.addTimelineEvent(meal.id, mac.id, "wrap", "Wrapped", "Recorded bark check", at + 500000)
            val draft = RecipeExtraction.fromMeal(repo.observeCookDetail(meal.id).first()!!, mac.id)
            val saved = repo.recipes.save(draft.snapshot.copy(name = "Android mac variation", targets = listOf(PlaybookTarget(0, "personal_finish", 0.0, "°C", "Portable zero-Celsius target"))), sourceCookId = meal.id, sourceDishId = mac.id, photoIds = draft.photoIds)
            val originals = db.recipeDao().recipes().filter { it.id != saved.id }
            val next = repo.startRecipeMeal("Portable new meal", listOf(originals.first().currentRevisionId, saved.currentRevisionId, originals.last().currentRevisionId))
            val currentMac = db.cookDao().getDishesForCook(next).first { db.recipeDao().source(it.id)?.recipeId == saved.id }
            val milestone = draft.snapshot.observations.first { it.action == "wrap" }
            val reminders = ReminderRepository(db)
            reminders.save(next, currentMac.id, "Last time wrapped · check the bark", ReminderTrigger.Historical("food_on", milestone.offsetMillis!!), sourceRevision = saved.currentRevisionId, sourceMilestone = milestone.sourceEventId)
            reminders.save(next, null, "Stir in ten minutes", ReminderTrigger.Duration(System.currentTimeMillis(), 600000))
            repo.createCookReminder(next, "Legacy pending timer", System.currentTimeMillis() + 600000)
            repo.guide.save(next, GuidePresentation(true, db.cookDao().getDishesForCook(next).take(2).map { it.id }.toSet()))
            val output = ByteArrayOutputStream(); transfer.writeZip(output)
            val final = transfer.previewImport(ByteArrayInputStream(output.toByteArray()))
            assertEquals(PitTechDataTransfer.ARCHIVE_VERSION, final.archiveVersion); assertEquals(2, final.snapshot.reminderRules.size)
            assertEquals(480000L, CookNoteMetadata.decode(final.snapshot.events.first { it.id == note.id }.noteMetadataJson)!!.durationMillis)
            assertEquals("4.00", CookNoteMetadata.decode(final.snapshot.events.first { it.id == note.id }.noteMetadataJson)!!.ingredients.single().amountText)
            assertEquals(1, final.snapshot.recipeAssets.size); assertTrue(final.snapshot.recipeAssets.none { asset -> final.snapshot.photos.any { it.id == asset.id } })
            repo.completeCook(meal.id)
            val sharingId = repo.sharing.fromMeal(meal.id, db.cookDao().getDishesForCook(meal.id).map { it.id }.toSet())
            val record = repo.sharing.get(sharingId)!!; val sharing = SharingDraftCodec.decode(record.payload)
            val photos = repo.sharing.assets(sharingId)
            repo.sharing.save(sharingId, sharing.copy(entries = sharing.entries.map { entry -> entry.copy(included = entry.sourceDishId == mac.id, includeStory = true, photoIds = photos.filter { it.action == entry.id }.map { it.id }) }), record.updatedAtUtcMillis)
            val one = ByteArrayOutputStream(); transfer.writeCookPack(one, sharingId)
            val recorded = ByteArrayOutputStream(); transfer.writeCookPack(recorded, sharingId, includeRecordedCook = true)
            val latest = repo.sharing.get(sharingId)!!; val selected = SharingDraftCodec.decode(latest.payload)
            repo.sharing.save(sharingId, selected.copy(entries = selected.entries.map { it.copy(included = true) }), latest.updatedAtUtcMillis)
            val all = ByteArrayOutputStream(); transfer.writeCookPack(all, sharingId)
            repoDaoSetFixtureNotes(db, meal.id)
            val workbook = ByteArrayOutputStream(); transfer.writeWorkbook(workbook)
            val csv = ByteArrayOutputStream(); transfer.writeCsv(csv)
            File(context.filesDir, "pittech-portable/android-meal-v9.zip").apply {
                parentFile!!.mkdirs()
                ZipOutputStream(outputStream().buffered()).use { zip ->
                    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { original ->
                        while (true) { val entry = original.nextEntry ?: break; zip.putNextEntry(ZipEntry(entry.name)); original.copyTo(zip); zip.closeEntry() }
                    }
                    for ((name, data) in listOf("fixtures/recipe.pittech" to one.toByteArray(), "fixtures/cook.pittech" to recorded.toByteArray(), "fixtures/collection.pittech" to all.toByteArray(), "fixtures/portable.xlsx" to workbook.toByteArray(), "fixtures/readings.csv" to csv.toByteArray())) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                }
            }
            cameraFile.delete()
        } finally { db.close() }
    }
    private suspend fun repoDaoSetFixtureNotes(db: PitTechDatabase, id: String) {
        db.cookDao().updateCook(db.cookDao().getCook(id)!!.copy(notes = "🌶".repeat(17000)))
    }
}
