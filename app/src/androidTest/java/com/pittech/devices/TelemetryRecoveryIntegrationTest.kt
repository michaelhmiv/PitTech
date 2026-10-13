package com.pittech.devices

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TelemetryRecoveryIntegrationTest {
    @Test fun boundedHistoryRecoveryDeduplicatesRepeatedProviderStateChanges(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val cooks = CookRepository(db, PhotoStorage(context))
            val recorder = TelemetryRecordingRepository(db)
            val cook = cooks.startCook(NewCookDraft("Backfill",
                dishes = listOf(DishDraft("Pork butt", "Pork"))))
            val started = db.cookDao().getCook(cook)!!.startedAtUtcMillis
            val device = TelemetrySource("home_assistant", "sensor.grillprobe", "Grill",
                channels = listOf(TelemetryChannel("p", "Meat", TelemetryKind.FOOD)))
            val dish = db.cookDao().getDishesForCook(cook).single()
            recorder.attach(cook, device, mapOf("p" to dish.id), started + 100)
            recorder.ingest(cook, device, listOf(TelemetryObservation(
                "p", 150.0, "°F", started + 1_000L, started + 1_000L, eventId = "first")))
            val saved = listOf(started + 4 * 3_600_000L, started + 9 * 3_600_000L)
            val ranges = mutableListOf<LongRange>()
            val fake = object : ReadOnlyTelemetryConnector {
                override val connectorId = "home_assistant"
                override suspend fun discover() = listOf(device)
                override suspend fun snapshot(deviceId: String) = emptyList<TelemetryObservation>()
                override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = emptyFlow()
                override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
                    assertEquals(device.deviceId, deviceId)
                    ranges.add(fromMillis..untilMillis)
                    return saved.filter { it in fromMillis..untilMillis }.map {
                        TelemetryObservation("p", 175.0, "°F", it, started + 10 * 3_600_000L,
                            eventId = it.toString(), fromHistory = true)
                    }
                }
                override suspend fun disconnect() = Unit
            }
            val coordinator = TelemetryCollectionCoordinator(recorder) { started + 10 * 3_600_000L }
            coordinator.backfill(cook, device, fake)
            assertEquals(2, ranges.size)
            assertTrue(ranges.all { it.last - it.first <= TelemetryRecoveryPolicy.WINDOW_MS })
            assertEquals(3, db.cookDao().getAllSensorReadings().size)
            ranges.clear()
            coordinator.backfill(cook, device, fake)
            assertEquals(1, ranges.size)
            assertEquals(3, db.cookDao().getAllSensorReadings().size)
            assertEquals(setOf(dish.id),
                db.cookDao().getAllSensorReadings().mapNotNull { it.dishId }.toSet())
        } finally {
            db.close()
        }
    }
}
