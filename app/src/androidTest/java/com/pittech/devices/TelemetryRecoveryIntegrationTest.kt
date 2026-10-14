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
                override val supportsHistory = true
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
    @Test fun interruptedHistoryResumesOlderCursorEvenAfterNewerLiveSamples(): Unit = runBlocking {
        withRecoveryCook { db, recorder, cook, source, started ->
            val first = started + 2 * 3_600_000L
            val missed = started + 8 * 3_600_000L
            val now = started + 10 * 3_600_000L
            var offline = true
            val requests = mutableListOf<LongRange>()
            val connector = historyFixture(source) { from, until ->
                requests += from..until
                if (offline && from >= started + 6 * 3_600_000L) throw java.io.IOException("Offline")
                listOf(first, missed).filter { it in from..until }.map {
                    TelemetryObservation("p", 170.0, "°F", it, now, eventId = it.toString(), fromHistory = true)
                }
            }
            try { TelemetryCollectionCoordinator(recorder, clock = { now }).backfill(cook, source, connector); fail("Expected the second window to fail") }
            catch (_: java.io.IOException) { }
            val pending = recorder.pendingRecovery(cook, source)!!
            assertTrue(pending.first < missed)
            recorder.ingest(cook, source, listOf(TelemetryObservation("p", 180.0, "°F", now, now, eventId = "live")))
            offline = false; requests.clear()
            // Recreate the recorder/coordinator as after process death.
            TelemetryCollectionCoordinator(TelemetryRecordingRepository(db), clock = { now }).backfill(cook, source, connector)
            assertEquals(pending.first, requests.first().first)
            assertEquals(setOf(first, missed, now), db.cookDao().getAllSensorReadings().map { it.measuredAtUtcMillis }.toSet())
            assertNull(recorder.pendingRecovery(cook, source))
        }
    }

    @Test fun saturatedHistoryWindowSplitsAndOverlapsRemainIdempotent(): Unit = runBlocking {
        withRecoveryCook { db, recorder, cook, source, started ->
            val now = started + 20_000L
            var calls = 0
            val sample = TelemetryObservation("p", 180.0, "°F", started + 5_000, now, eventId = "sample", fromHistory = true)
            val connector = historyFixture(source) { from, until ->
                calls++
                if (until - from > 10_000L) List(20_000) { sample }
                else listOf(sample).filter { it.observedAtMillis!! in from..until }
            }
            assertEquals(1, TelemetryCollectionCoordinator(recorder, clock = { now }).backfill(cook, source, connector))
            assertEquals(3, calls)
            assertEquals(1, db.cookDao().getAllSensorReadings().size)
            assertNull(recorder.pendingRecovery(cook, source))
        }
    }

    private suspend fun withRecoveryCook(block: suspend (PitTechDatabase, TelemetryRecordingRepository, String, TelemetrySource, Long) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val cook = CookRepository(db, PhotoStorage(context)).startCook(NewCookDraft("Recovery"))
            val started = db.cookDao().getCook(cook)!!.startedAtUtcMillis
            val source = TelemetrySource("home_assistant", "sensor.test", "Test", channels = listOf(TelemetryChannel("p", "Probe", TelemetryKind.FOOD)))
            val recorder = TelemetryRecordingRepository(db)
            recorder.attach(cook, source, now = started)
            block(db, recorder, cook, source, started)
        } finally { db.close() }
    }

    private fun historyFixture(source: TelemetrySource, get: suspend (Long, Long) -> List<TelemetryObservation>) = object : ReadOnlyTelemetryConnector {
        override val connectorId = source.connectorId
        override val supportsHistory = true
        override suspend fun discover() = listOf(source)
        override suspend fun snapshot(deviceId: String) = emptyList<TelemetryObservation>()
        override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = emptyFlow()
        override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = get(fromMillis, untilMillis)
        override suspend fun disconnect() = Unit
    }

}
