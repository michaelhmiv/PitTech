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
}
