package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.devices.*
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TelemetryRecordingIntegrationTest {
    private lateinit var db: PitTechDatabase
    private lateinit var repo: CookRepository
    private lateinit var telemetry: TelemetryRecordingRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        repo = CookRepository(db, PhotoStorage(context))
        telemetry = TelemetryRecordingRepository(db)
    }
    @After fun cleanup() { db.close() }

    private fun source(provider: String, key: String) = TelemetrySource(provider, key, "Smoker",
        channels = listOf(TelemetryChannel("chamber", "Chamber", TelemetryKind.CHAMBER),
            TelemetryChannel("meat", "Meat", TelemetryKind.FOOD)))

    @Test fun multiSourceHistoryIsDeduplicatedAndIsolated(): Unit = runBlocking {
        val cook = repo.startCook(NewCookDraft("Mixed", dishes = listOf(DishDraft("Ribs", "Pork"))))
        val dish = db.cookDao().getDishesForCook(cook).single()
        val start = db.cookDao().getCook(cook)!!.startedAtUtcMillis
        val home = source("home_assistant", "grill")
        val meater = source("meater", "thermometer")
        telemetry.attach(cook, home, mapOf("meat" to dish.id), start + 1L)
        telemetry.attach(cook, meater, mapOf("meat" to dish.id), start + 1L)
        val one = TelemetryObservation("meat", 100.0, "°C", start + 2L, start + 3L, eventId = "1", fromHistory = true)
        telemetry.ingest(cook, home, listOf(one))
        telemetry.ingest(cook, home, listOf(one))
        telemetry.ingest(cook, meater, listOf(one.copy(unit = "°F", value = 190.0)))
        val records = db.cookDao().getAllSensorReadings()
        assertEquals(2, records.size)
        assertEquals(setOf(212.0, 190.0), records.map { it.value }.toSet())
        assertEquals(2, db.cookDao().getAllDevices().size)
        assertTrue(records.all { it.dishId == dish.id })
        assertEquals(setOf("telemetry:home_assistant", "telemetry:meater"), records.map { it.source }.toSet())
    }

    @Test fun reassignmentKeepsPriorFoodHistory(): Unit = runBlocking {
        val cook = repo.startCook(NewCookDraft("Mixed", dishes = listOf(DishDraft("A", "Pork"), DishDraft("B", "Pork"))))
        val foods = db.cookDao().getDishesForCook(cook)
        val start = db.cookDao().getCook(cook)!!.startedAtUtcMillis
        val src = source("fireboard", "fb")
        telemetry.attach(cook, src, mapOf("meat" to foods[0].id), start + 1L)
        telemetry.ingest(cook, src, listOf(TelemetryObservation("meat", 170.0, "°F", start+2, start+2)))
        telemetry.assignFood(cook, src, "meat", foods[1].id, start+3)
        telemetry.ingest(cook, src, listOf(TelemetryObservation("meat", 180.0, "°F", start+4, start+4)))
        assertEquals(listOf(foods[0].id, foods[1].id), db.cookDao().getAllSensorReadings().sortedBy { it.value }.map { it.dishId })
    }

    @Test fun rejectsUnknownOrUnavailableReadings(): Unit = runBlocking {
        val cook = repo.startCook(NewCookDraft("Unknown", dishes = listOf(DishDraft("A", "Pork"))))
        val src = source("combustion", "cx")
        val t = db.cookDao().getCook(cook)!!.startedAtUtcMillis + 2L
        val value = TelemetryObservation("meat", 90.0, "°F", t, t)
        assertEquals(0, telemetry.ingest(cook, src, listOf(value)))
        telemetry.attach(cook, src)
        assertEquals(0, telemetry.ingest(cook, src, listOf(value.copy(channelId = "not-selected"))))
        assertEquals(0, telemetry.ingest(cook, src, listOf(value.copy(quality = TelemetryQuality.UNAVAILABLE))))
    }

    @Test fun mixedSourcesRoundTripThroughNormalCookArchiveWithoutCredentials(): Unit = runBlocking {
        val cook = repo.startCook(NewCookDraft("Export mixed probes",
            dishes = listOf(DishDraft("Ribs", "Pork"))))
        val dish = db.cookDao().getDishesForCook(cook).single()
        val started = db.cookDao().getCook(cook)!!.startedAtUtcMillis
        val ha = source("home_assistant", "sensor.private-backyard-identity")
        val fb = source("fireboard", "account-private-fireboard-identity")
        telemetry.attach(cook, ha, mapOf("meat" to dish.id), started + 1L)
        telemetry.attach(cook, fb, mapOf("meat" to dish.id), started + 1L)
        telemetry.ingest(cook, ha, listOf(TelemetryObservation(
            "meat", 93.0, "°C", started + 2L, started + 3L, eventId = "ha-record"
        )))
        telemetry.ingest(cook, fb, listOf(TelemetryObservation(
            "meat", 201.0, "°F", started + 4L, started + 5L, eventId = "fb-record"
        )))
        val archive = repo.exportSnapshot(cook)
        assertEquals(2, archive.devices.size)
        assertEquals(2, archive.readings.size)
        assertEquals(2, archive.assignments.size)
        assertTrue(archive.devices.none { it.id.contains("private") })
        assertTrue(archive.readings.none { it.id.contains("private") })
        val restoredDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PitTechDatabase::class.java
        ).build()
        try {
            val restored = CookRepository(restoredDb, PhotoStorage(ApplicationProvider.getApplicationContext()))
            restored.importSnapshot(archive, emptyMap())
            restored.importSnapshot(archive, emptyMap())
            val restoredRows = restoredDb.cookDao().getAllSensorReadings()
            assertEquals(2, restoredRows.size)
            assertEquals(2, restoredDb.cookDao().getAllDevices().size)
            assertEquals(2, restoredDb.recordingDao().getAllAssignments().size)
            assertEquals(archive.readings.map { it.id }.toSet(), restoredRows.map { it.id }.toSet())
            assertEquals(setOf(dish.id), restoredRows.mapNotNull { it.dishId }.toSet())
        } finally {
            restoredDb.close()
        }
    }

    @Test fun changingSensorRoleDoesNotRelabelPreviousFoodReadings(): Unit = runBlocking {
        val cook = repo.startCook(NewCookDraft("Role switch",
            dishes = listOf(DishDraft("Brisket", "Beef"))))
        val dish = db.cookDao().getDishesForCook(cook).single()
        val start = db.cookDao().getCook(cook)!!.startedAtUtcMillis
        val food = source("home_assistant", "patio")
        telemetry.attach(cook, food, mapOf("meat" to dish.id), start + 1)
        telemetry.ingest(cook, food, listOf(TelemetryObservation(
            "meat", 180.0, "°F", start + 2, start + 2
        )))
        val chamber = food.copy(channels = food.channels.map {
            if (it.id == "meat") it.copy(name = "Chamber", kind = TelemetryKind.CHAMBER) else it
        })
        telemetry.attach(cook, chamber, now = start + 3)
        telemetry.ingest(cook, chamber, listOf(TelemetryObservation(
            "meat", 250.0, "°F", start + 4, start + 4
        )))
        val readings = db.cookDao().getAllSensorReadings().sortedBy { it.measuredAtUtcMillis }
        assertEquals(2, readings.size)
        assertEquals(dish.id, readings[0].dishId)
        assertEquals("food_probe", readings[0].measurementType)
        assertNull(readings[1].dishId)
        assertEquals("pit_ambient", readings[1].measurementType)
        val current = db.cookDao().getProbesForCook(cook).first { it.id == telemetry.channelKey(cook, chamber, "meat") }
        assertEquals("pit_ambient", current.measurementType)
        assertNull(current.assignedDishId)
        assertEquals(start + 3, db.recordingDao().getAllAssignments().single().endedAtUtcMillis)
    }
}
