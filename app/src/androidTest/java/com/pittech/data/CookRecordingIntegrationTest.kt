package com.pittech.data

import android.content.Context
import android.graphics.Bitmap
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.devices.CookTelemetryPolicy
import com.pittech.devices.PolarisDevice
import com.pittech.devices.PolarisMonitorState
import com.pittech.devices.PolarisPayload
import com.pittech.devices.PolarisSample
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CookRecordingIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: PitTechDatabase
    private lateinit var cooks: CookRepository
    private lateinit var recording: CookRecordingRepository
    private val device = PolarisDevice("synthetic-private-controller", "Test grill", "Test", "P7", null, "test")
    private val base = System.currentTimeMillis() - 30_000L

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        cooks = CookRepository(db, PhotoStorage(context))
        recording = CookRecordingRepository(db)
    }
    @After fun cleanup() {
        runBlocking { db.cookDao().getAllPhotos().forEach { PhotoStorage(context).delete(it.relativePath) } }
        db.close()
    }
    private suspend fun cook() = cooks.startCook(NewCookDraft("Connected test", dishes = listOf(DishDraft("Roast", "Beef"), DishDraft("Loin", "Pork"))))
    private fun sample(time: Long, setpoint: Double = 250.0) = PolarisSample(time, PolarisPayload(mapOf("tempUnit" to 0.0, "furnaceTempMeasured" to 248.0, "furnaceTempSetting" to setpoint, "probeP1Measured" to 150.0, "probeP2Measured" to 0.0), emptyList(), 0, 0))
    private fun state(time: Long, id: String = device.id) = PolarisMonitorState(authenticated = true, devices = listOf(device), selectedDeviceId = id, onlineStatus = 0, statusFetchedAtMillis = time)
    private suspend fun ingest(id: String, time: Long, setpoint: Double = 250.0) = recording.ingest(id, CookTelemetryPolicy.deviceKey(device.id), sample(time, setpoint), state(time))

    @Test fun bindingDeduplicationAndFinishPreventMisattributionAndLateWrites() = runBlocking {
        val id = cook()
        recording.attach(id, device, "°F", now = base)
        recording.ingest(id, CookTelemetryPolicy.deviceKey("wrong"), sample(base + 1_000), state(base + 1_000))
        assertTrue(db.cookDao().getAllSensorReadings().isEmpty())
        ingest(id, base + 1_000); ingest(id, base + 1_000)
        assertEquals(4, db.cookDao().getAllSensorReadings().size)
        assertEquals("unavailable", db.cookDao().getAllSensorReadings().single { it.probeName == "Probe 2" }.qualityStatus)
        cooks.completeCook(id)
        ingest(id, System.currentTimeMillis() + 15_000)
        assertEquals(4, db.cookDao().getAllSensorReadings().size)
        assertEquals(CookRecordingEntity.STOPPED, db.recordingDao().getRecording(id)!!.status)
        val other = cook()
        recording.attach(other, device, "°F", now = base)
        try { recording.attach(id, device, "°F", now = base); fail("Finished cooks cannot record") } catch (_: IllegalArgumentException) { }
    }

    @Test fun assignmentsAndSnapshotsPreserveEarlierDishHistoryAndPhotoCaptureContext() = runBlocking {
        val id = cook()
        val dishes = db.cookDao().getDishesForCook(id)
        recording.attach(id, device, "°F", mapOf("probe1" to dishes[0].id), base)
        ingest(id, base + 1_000)
        val event = cooks.addTimelineEvent(id, dishes[0].id, "wrap", "Wrapped roast", null, base + 2_000)
        val savedContext = event.temperatureContextJson
        assertTrue(TemperatureContext.decode(savedContext).any { it.name == "Probe 1" && it.value == 150.0 })
        val probe = db.cookDao().getProbesForCook(id).single { it.name == "Probe 1" }
        recording.assignProbe(probe.id, id, dishes[1].id, base + 3_000)
        ingest(id, base + 4_000)
        assertEquals(dishes[0].id, db.cookDao().getAllSensorReadings().single { it.probeId == probe.id && it.measuredAtUtcMillis == base + 1_000 }.dishId)
        assertEquals(dishes[1].id, db.cookDao().getAllSensorReadings().single { it.probeId == probe.id && it.measuredAtUtcMillis == base + 4_000 }.dishId)
        cooks.updateSensorReading(db.cookDao().getAllSensorReadings().first { it.probeId == probe.id }.copy(value = 199.0))
        assertEquals(savedContext, db.cookDao().getTimelineEvent(event.id)!!.temperatureContextJson)
        val file = CameraPhotoFiles.create(context)
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle() }
        file.setLastModified(base + 5_000)
        val photo = cooks.attachPhotoToTimelineEvent(event.id, id, dishes[1].id, CameraPhotoFiles.uri(context, file).toString(), "Wrapped")
        assertEquals(base + 5_000, photo.capturedAtUtcMillis)
        assertTrue(TemperatureContext.decode(photo.temperatureContextJson).any { it.name == "Probe 1" })
        val old = CameraPhotoFiles.create(context)
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { old.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle() }
        old.setLastModified(base - 3_600_000L)
        val oldPhoto = cooks.attachPhotoToTimelineEvent(event.id, id, null, CameraPhotoFiles.uri(context, old).toString(), "Earlier prep")
        assertNull(oldPhoto.temperatureContextJson)
        assertEquals(savedContext, db.cookDao().getTimelineEvent(event.id)!!.temperatureContextJson)
        val camera = CameraPhotoFiles.create(context)
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { camera.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle() }
        camera.setLastModified(base + 5_000)
        val cameraPhoto = cooks.addCookPhoto(id, CameraPhotoFiles.uri(context, camera).toString(), "Current photo")
        val cameraEntry = db.cookDao().getTimelineEvent(cameraPhoto.eventId!!)!!
        assertEquals(base + 5_000, cameraEntry.occurredAtUtcMillis)
        assertEquals(cameraPhoto.temperatureContextJson, cameraEntry.temperatureContextJson)
        val gallery = java.io.File(context.cacheDir, "unknown-date-${UUID.randomUUID()}.jpg")
        try {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { gallery.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle() }
            val unknown = cooks.addCookPhoto(id, android.net.Uri.fromFile(gallery).toString(), "Undated gallery photo")
            assertNull(unknown.capturedAtUtcMillis)
            assertNull(unknown.temperatureContextJson)
            assertNull(db.cookDao().getTimelineEvent(unknown.eventId!!)!!.temperatureContextJson)
        } finally { gallery.delete() }
        val revised = event.copy(occurredAtUtcMillis = base - 3_600_000L)
        cooks.updateTimelineEvent(revised)
        assertNull(db.cookDao().getTimelineEvent(event.id)!!.temperatureContextJson)
    }

    @Test fun gapRecoveryAndSetpointConfirmationProduceBoundedMeaningfulEvents() = runBlocking {
        val id = cook()
        recording.attach(id, device, "°C", now = base)
        ingest(id, base + 1_000)
        ingest(id, base + 16_000, 300.0)
        assertTrue(db.cookDao().getAllTimelineEvents().none { it.eventType == "setpoint_changed" })
        ingest(id, base + 31_000, 300.0)
        assertEquals(1, db.cookDao().getAllTimelineEvents().count { it.eventType == "setpoint_changed" })
        recording.markGap(id, "Offline", base + 50_000); recording.markGap(id, "Offline", base + 60_000)
        ingest(id, base + 90_000, 300.0)
        assertEquals(1, db.cookDao().getAllTimelineEvents().count { it.eventType == "connection_gap" })
        assertEquals(1, db.cookDao().getAllTimelineEvents().count { it.eventType == "connection_restored" })
        assertEquals(1, db.cookDao().getAllTimelineEvents().count { it.eventType == "setpoint_changed" })
        recording.pause(id, now = base + 91_000)
        ingest(id, base + 100_000)
        assertEquals(base + 90_000, db.recordingDao().getRecording(id)!!.lastReceivedAtUtcMillis)
    }

    @Test fun completeArchivesRoundTripAssignmentsContextsAndReceiptTimesWithoutCredentialsOrAutoResume() = runBlocking {
        val id = cook()
        recording.attach(id, device, "°F", now = base); ingest(id, base + 1_000)
        val event = cooks.addTimelineEvent(id, null, "note", "Bark developing", null, base + 2_000)
        val transfer = PitTechDataTransfer(context, cooks)
        val output = ByteArrayOutputStream(); transfer.writeZip(output)
        val preview = transfer.previewImport(ByteArrayInputStream(output.toByteArray()))
        assertEquals(3, preview.archiveVersion)
        assertEquals(event.temperatureContextJson, preview.snapshot.events.first { it.id == event.id }.temperatureContextJson)
        assertTrue(preview.snapshot.readings.all { it.timestampBasis == "cloud_receipt" })
        assertEquals("", preview.snapshot.recordings.single().controllerKey)
        assertEquals(CookRecordingEntity.STOPPED, preview.snapshot.recordings.single().status)
        assertFalse(preview.snapshot.toString().contains(device.id))
        assertEquals(2, preview.snapshot.assignments.size)
        val other = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val restored = CookRepository(other, PhotoStorage(context))
            restored.importSnapshot(preview.snapshot, preview.attachments)
            assertNull(other.recordingDao().getActiveRecording())
            assertEquals(4, other.cookDao().getAllSensorReadings().size)
            assertEquals(2, other.recordingDao().getAllAssignments().size)
        } finally { other.close() }
        val csv = ByteArrayOutputStream(); transfer.writeCsv(csv)
        assertTrue(csv.toString("UTF-8").contains("cloud_receipt"))
        val workbook = ByteArrayOutputStream(); transfer.writeWorkbook(workbook)
        assertTrue(workbook.size() > 0)
    }

    @Test fun schema3MigrationPreservesExistingCookAndManualReadings() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val legacy = Room.databaseBuilder(context, PitTechDatabase::class.java, name).build()
        val legacyCooks = CookRepository(legacy, PhotoStorage(context))
        val id = legacyCooks.startCook(NewCookDraft("Legacy cook"))
        legacyCooks.addManualTemperature(id, null, "Manual probe", "food_probe", 165.0, "°F", base)
        legacy.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("DROP TABLE probe_assignments"); sql.execSQL("DROP TABLE cook_recordings")
            sql.execSQL("ALTER TABLE timeline_events DROP COLUMN temperatureContextJson")
            sql.execSQL("ALTER TABLE photos DROP COLUMN temperatureContextJson")
            sql.execSQL("ALTER TABLE sensor_readings DROP COLUMN timestampBasis")
            sql.version = 3
        }
        val migrated = Room.databaseBuilder(context, PitTechDatabase::class.java, name).addMigrations(PitTechDatabase.MIGRATION_3_4).build()
        try {
            assertEquals("Legacy cook", migrated.cookDao().getCook(id)!!.title)
            assertEquals("measurement", migrated.cookDao().getAllSensorReadings().single().timestampBasis)
            assertEquals(165.0, migrated.cookDao().getAllSensorReadings().single().value, 0.01)
            assertTrue(migrated.recordingDao().getAllRecordings().isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
