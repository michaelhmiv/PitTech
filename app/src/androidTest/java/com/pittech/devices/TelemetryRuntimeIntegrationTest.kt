package com.pittech.devices

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TelemetryRuntimeIntegrationTest {
    @Test fun onLogRecordingRestoresDurableSelectionsAndRemovalKeepsCookHistory(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "telemetry-runtime-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder
        }
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val source = TelemetrySource("meater", "test-probe", "Probe", channels = listOf(TelemetryChannel("internal", "Internal", TelemetryKind.FOOD)))
        var calls = 0
        val connector = object : ReadOnlyTelemetryConnector {
            override val connectorId = "meater"
            override suspend fun discover() = listOf(source)
            override suspend fun snapshot(deviceId: String): List<TelemetryObservation> { calls++; val now = System.currentTimeMillis() + calls; return listOf(TelemetryObservation("internal", 180.0 + calls, "°F", now, now)) }
            override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = emptyFlow()
            override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
            override suspend fun disconnect() = Unit
        }
        val factory = object : TelemetryConnectorFactory {
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String) = connector
            override suspend fun restore(context: Context, profile: TelemetryProfile) = connector
        }
        var runtime = TelemetryRuntime(context, db) { factory }
        try {
            val repo = CookRepository(db, PhotoStorage(context))
            val cook = repo.startCook(NewCookDraft("Telemetry", dishes = listOf(DishDraft("Brisket", "Beef"))))
            val dish = db.cookDao().getDishesForCook(cook).single()
            val profile = runtime.connect(TelemetryProfile("test-profile", "meater", "My probe", onLogOnly = true), "test", "dummy")
            runtime.attach(profile, cook, source, mapOf("internal" to dish.id))
            runtime.onLog(cook)
            assertEquals(2, db.cookDao().getAllSensorReadings().size)
            runtime.close()
            runtime = TelemetryRuntime(context, db) { factory }
            val restored = runtime.store.profiles.value.single()
            assertEquals(cook, restored.cookId)
            assertEquals(setOf(source.deviceId), restored.selectedDeviceIds)
            runtime.onLog(cook)
            assertEquals(3, db.cookDao().getAllSensorReadings().size)
            runtime.remove(restored)
            runtime.onLog(cook)
            assertEquals(3, db.cookDao().getAllSensorReadings().size)
            assertTrue(runtime.store.profiles.value.isEmpty())
            assertEquals(1, db.cookDao().getAllDevices().size)
        } finally { runtime.close(); db.close(); folder.deleteRecursively() }
    }
    @Test fun missingRestoredCredentialsDisableOnlyThatBackgroundSession(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "telemetry-auth-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getApplicationContext(): Context = this; override fun getNoBackupFilesDir(): File = folder }
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val factory = object : TelemetryConnectorFactory {
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String): ReadOnlyTelemetryConnector = throw TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
            override suspend fun restore(context: Context, profile: TelemetryProfile): ReadOnlyTelemetryConnector = throw TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
        }
        val runtime = TelemetryRuntime(context, db, backgroundStarter = {}) { factory }
        try {
            val cook = CookRepository(db, PhotoStorage(context)).startCook(NewCookDraft("Credentials removed"))
            val source = TelemetrySource("meater", "probe", "Probe", channels = listOf(TelemetryChannel("internal", "Food", TelemetryKind.FOOD)))
            val recorder = TelemetryRecordingRepository(db); recorder.attach(cook, source)
            runtime.store.save(TelemetryProfile("missing-credentials", "meater", "Probe", sources = listOf(source), cookId = cook, monitoring = true, background = true, selectedDeviceIds = setOf(source.deviceId)))
            runtime.foreground.value = true
            withTimeout(5_000) { while (runtime.store.profiles.value.single().monitoring) delay(20) }
            assertFalse(recorder.isAttached(cook, source))
            assertEquals(1, db.cookDao().getAllDevices().size)
            assertEquals(1, db.cookDao().getAllCooks().size)
        } finally { runtime.close(); db.close(); folder.deleteRecursively() }
    }

    @Test fun unavailableDeviceConfigurationSurvivesReconnectAndNewServerRequiresRemapping(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "telemetry-discovery-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getApplicationContext(): Context = this; override fun getNoBackupFilesDir(): File = folder }
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        var found = true
        val factory = object : TelemetryConnectorFactory {
            private fun adapter(profile: TelemetryProfile) = object : ReadOnlyTelemetryConnector {
                override val connectorId = "home_assistant"
                private val source = TelemetrySource("home_assistant", "sensor.probe", "Probe", channels = listOf(TelemetryChannel("temperature", "Food", TelemetryKind.FOOD)), identityScope = profile.endpoint)
                override suspend fun discover() = if (found) listOf(source) else emptyList()
                override suspend fun snapshot(deviceId: String) = emptyList<TelemetryObservation>()
                override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = emptyFlow()
                override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
                override suspend fun disconnect() = Unit
            }
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String) = adapter(profile)
            override suspend fun restore(context: Context, profile: TelemetryProfile) = adapter(profile)
        }
        val runtime = TelemetryRuntime(context, db, backgroundStarter = {}) { factory }
        try {
            val cook = CookRepository(db, PhotoStorage(context)).startCook(NewCookDraft("Saved source"))
            val profile = runtime.connect(TelemetryProfile("saved-source", "home_assistant", "HA", endpoint = "https://one.example.com", onLogOnly = true), "fixture", "dummy")
            val source = profile.sources.single(); runtime.attach(profile, cook, source, emptyMap())
            found = false
            val offline = runtime.connect(runtime.store.profiles.value.single(), "fixture", "dummy")
            assertEquals(listOf(source), offline.sources); assertEquals(cook, offline.cookId)
            assertEquals(setOf(source.deviceId), offline.selectedDeviceIds)
            found = true
            val changed = runtime.connect(offline.copy(endpoint = "https://two.example.com"), "fixture", "dummy")
            assertNull(changed.cookId); assertTrue(changed.selectedDeviceIds.isEmpty()); assertFalse(changed.monitoring)
            assertFalse(TelemetryRecordingRepository(db).isAttached(cook, source))
            assertEquals(1, db.cookDao().getAllCooks().size)
        } finally { runtime.close(); db.close(); folder.deleteRecursively() }
    }

}
