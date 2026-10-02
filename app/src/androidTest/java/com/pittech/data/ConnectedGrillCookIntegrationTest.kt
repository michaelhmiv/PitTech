package com.pittech.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.devices.*
import com.pittech.domain.DishDraft
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectedGrillCookIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: PitTechDatabase
    private lateinit var cooks: CookRepository
    private lateinit var recording: CookRecordingRepository
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
    private suspend fun cook() = cooks.startCook(NewCookDraft("Multi-provider cook", dishes = listOf(DishDraft("Roast", "Beef"), DishDraft("Loin", "Pork"))))
    private fun traeger(time: Long): PolarisPayload = TraegerBackend.parseStatus(JSONObject().put("thingName", "synthetic")
        .put("status", JSONObject().put("time", time / 1000).put("units", 0).put("connected", true).put("grill", 100).put("set", 120)
            .put("acc", JSONArray("""[{"type":"probe","channel":"p2","con":1,"probe":{"get_temp":68}},{"type":"probe","channel":"p0","con":0,"probe":{"get_temp":900}},{"type":"probe","channel":"p3","con":1,"probe":{"get_temp":0}}]"""))))
    private suspend fun ingest(cook: String, device: PolarisDevice, payload: PolarisPayload, time: Long) {
        recording.ingest(cook, CookTelemetryPolicy.deviceKey(device.id), PolarisSample(time, payload),
            PolarisMonitorState(authenticated = true, devices = listOf(device), selectedDeviceId = device.id,
                onlineStatus = payload.values["onlineStatus"]?.toInt(), statusFetchedAtMillis = time))
    }
    @Test fun traegerFourChannelsKeepStableDishAttributionAndPhotoLogSnapshots() = runBlocking {
        val id = cook()
        val device = PolarisDevice("traeger:synthetic", "Test grill", "Traeger", null, null, null, 4)
        val dishes = db.cookDao().getDishesForCook(id)
        recording.attach(id, device, "°C", mapOf("probe3" to dishes[1].id, "probe4" to dishes[0].id), base)
        ingest(id, device, traeger(base + 1000), base + 1000)
        val readings = db.cookDao().getAllSensorReadings()
        assertEquals(6, readings.size)
        assertEquals("unavailable", readings.single { it.probeName == "Probe 1" }.qualityStatus)
        assertEquals(dishes[1].id, readings.single { it.probeName == "Probe 3" }.dishId)
        assertEquals(68.0, readings.single { it.probeName == "Probe 3" }.value, 0.001)
        assertEquals("valid", readings.single { it.probeName == "Probe 4" }.qualityStatus)
        assertEquals(0.0, readings.single { it.probeName == "Probe 4" }.value, 0.001)
        val event = cooks.addTimelineEvent(id, dishes[1].id, "wrap", "Wrapped loin", null, base + 2000)
        val copied = event.temperatureContextJson
        assertTrue(TemperatureContext.decode(copied).any { it.name == "Probe 3" && it.value == 68.0 })
        val camera = CameraPhotoFiles.create(context)
        try {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                camera.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }; recycle()
            }
            camera.setLastModified(base + 2000)
            val photo = cooks.attachPhotoToTimelineEvent(event.id, id, dishes[1].id, CameraPhotoFiles.uri(context, camera).toString(), "Wrap")
            assertTrue(TemperatureContext.decode(photo.temperatureContextJson).any { it.name == "Probe 3" && it.value == 68.0 })
        } finally { camera.delete() }
        val probe = db.cookDao().getProbesForCook(id).single { it.name == "Probe 3" }
        recording.assignProbe(probe.id, id, dishes[0].id, base + 3000)
        ingest(id, device, traeger(base + 16000), base + 16000)
        assertEquals(dishes[1].id, db.cookDao().getAllSensorReadings().single { it.probeName == "Probe 3" && it.measuredAtUtcMillis == base + 1000 }.dishId)
        assertEquals(dishes[0].id, db.cookDao().getAllSensorReadings().single { it.probeName == "Probe 3" && it.measuredAtUtcMillis == base + 16000 }.dishId)
        assertEquals(copied, db.cookDao().getTimelineEvent(event.id)!!.temperatureContextJson)
    }
    @Test fun pitBossCelsiusFramesNormalizeSentinelsAndCannotWriteIntoAnotherProviderCook() = runBlocking {
        val id = cook()
        val device = PolarisDevice("pitboss:PBA-synthetic", "Test Pit Boss", "Pit Boss", null, "PBA", null, 4)
        val bytes = MutableList(30) { 0 }
        bytes[0] = 254; bytes[1] = 12
        for (offset in listOf(8, 14, 17, 23, 26)) { bytes[offset] = 2; bytes[offset + 1] = 1; bytes[offset + 2] = 3 }
        bytes[11] = 9; bytes[12] = 6; bytes[13] = 0
        val payload = PitBossTelemetry.parse("PBA-synthetic", JSONObject().put("sc_12", bytes.joinToString("") { "%02x".format(it) }))
        recording.attach(id, device, "°F", now = base)
        ingest(id, device, payload, base + 1000)
        val rows = db.cookDao().getAllSensorReadings()
        assertEquals(6, rows.size)
        assertEquals(212.0, rows.single { it.probeName == "Chamber" }.value, 0.001)
        assertEquals("unavailable", rows.single { it.probeName == "Probe 2" }.qualityStatus)
        val other = PolarisDevice("traeger:PBA-synthetic", "Other provider", "Traeger", null, null, null, 4)
        ingest(id, other, traeger(base + 16000), base + 16000)
        assertEquals(6, db.cookDao().getAllSensorReadings().size)
        val originalDevice = db.recordingDao().getRecording(id)!!.deviceId
        recording.pause(id, now = base + 17000)
        recording.attach(id, other, "°C", now = base + 18000)
        ingest(id, other, traeger(base + 19000), base + 19000)
        val newDevice = db.recordingDao().getRecording(id)!!.deviceId
        assertNotEquals(originalDevice, newDevice)
        assertEquals(6, db.cookDao().getAllSensorReadings().count { it.sourceDeviceId == originalDevice })
        assertEquals(6, db.cookDao().getAllSensorReadings().count { it.sourceDeviceId == newDevice })
        cooks.completeCook(id)
        ingest(id, other, traeger(base + 31000), base + 31000)
        assertEquals(12, db.cookDao().getAllSensorReadings().size)
    }
    @Test fun staleNativeTimestampAndOfflineReportsLeaveGapsAndNeverAttachFakePhotoContext() = runBlocking {
        val id = cook()
        val device = PolarisDevice("traeger:synthetic", "Test grill", "Traeger", null, null, null, 4)
        recording.attach(id, device, "°C", now = base)
        ingest(id, device, traeger(base + 1000), base + 1000)
        ingest(id, device, traeger(base - 120_000), base + 16_000)
        val offline = traeger(base + 31_000).copy(values = traeger(base + 31_000).values + ("onlineStatus" to 1.0))
        ingest(id, device, offline, base + 31_000)
        assertEquals(6, db.cookDao().getAllSensorReadings().size)
        assertEquals(1, db.cookDao().getAllTimelineEvents().count { it.eventType == "connection_gap" })
        val gap = cooks.addTimelineEvent(id, null, "note", "Lost network", null, base + 80_000)
        assertNull(gap.temperatureContextJson)
        ingest(id, device, traeger(base + 91_000), base + 91_000)
        assertEquals(12, db.cookDao().getAllSensorReadings().size)
        assertEquals(1, db.cookDao().getAllTimelineEvents().count { it.eventType == "connection_restored" })
        val backdated = cooks.addTimelineEvent(id, null, "note", "Earlier outage", null, base + 80_000)
        assertNull(backdated.temperatureContextJson)
        val restored = cooks.addTimelineEvent(id, null, "note", "Network restored", null, base + 92_000)
        assertNotNull(restored.temperatureContextJson)
    }
}
