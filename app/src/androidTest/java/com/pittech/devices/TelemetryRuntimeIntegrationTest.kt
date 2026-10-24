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
    @Test fun foregroundStartsAreCoalescedAndHiddenUiCannotReplayThem(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "telemetry-start-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getApplicationContext(): Context = this; override fun getNoBackupFilesDir(): File = folder }
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val source = TelemetrySource("meater", "probe", "Probe", channels = listOf(TelemetryChannel("internal", "Food", TelemetryKind.FOOD)))
        val adapter = object : ReadOnlyTelemetryConnector {
            override val connectorId = "meater"
            override suspend fun discover() = listOf(source)
            override suspend fun snapshot(deviceId: String) = emptyList<TelemetryObservation>()
            override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = flow { awaitCancellation() }
            override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
            override suspend fun disconnect() = Unit
        }
        val factory = object : TelemetryConnectorFactory {
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String) = adapter
            override suspend fun restore(context: Context, profile: TelemetryProfile) = adapter
        }
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val runtime = TelemetryRuntime(context, db, backgroundStarter = { starts.incrementAndGet() }) { factory }
        try {
            val cook = CookRepository(db, PhotoStorage(context)).startCook(NewCookDraft("Start coalescing"))
            TelemetryRecordingRepository(db).attach(cook, source)
            runtime.store.save(TelemetryProfile("start-dedup", "meater", "Probe", sources = listOf(source), cookId = cook,
                monitoring = true, background = true, selectedDeviceIds = setOf(source.deviceId)))
            withTimeout(20_000) { while (!runtime.backgroundRequested.value) delay(10) }
            runtime.foreground.value = true
            repeat(20) { runtime.requestBackgroundFromVisibleApp() }
            withTimeout(20_000) { while (starts.get() == 0) delay(10) }
            withContext(Dispatchers.Main) { }
            assertEquals(1, starts.get())
            withContext(Dispatchers.Main) { runtime.serviceHosted.value = true; runtime.requestBackgroundFromVisibleApp() }
            runtime.foreground.value = false; runtime.serviceHosted.value = false
            repeat(20) { runtime.requestBackgroundFromVisibleApp() }
            withContext(Dispatchers.Main) { }
            assertEquals(1, starts.get())
            runtime.foreground.value = true
            // CooksViewModel explicitly requests collection on resume. StateFlow may
            // conflate the brief hidden/hosted transitions above, so exercise that
            // actual UI entry point instead of relying on a transient flow event.
            repeat(20) { runtime.requestBackgroundFromVisibleApp() }
            withTimeout(20_000) { while (starts.get() != 2) delay(10) }
            runtime.serviceHosted.value = true
        } finally { runtime.close(); db.close(); folder.deleteRecursively() }
    }

    @Test fun missingSensorCannotStopAnotherSensorOnTheSameOnLogConnection(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "telemetry-sensor-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getApplicationContext(): Context = this; override fun getNoBackupFilesDir(): File = folder }
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val sources = listOf("sensor.missing", "sensor.healthy").map { TelemetrySource("home_assistant", it, it, channels = listOf(TelemetryChannel("temperature", "Food", TelemetryKind.FOOD)), identityScope = "https://home.example.com") }
        var failing = false
        val adapter = object : ReadOnlyTelemetryConnector {
            override val connectorId = "home_assistant"
            override suspend fun discover() = sources
            override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
                if (!failing) return emptyList()
                if (deviceId == "sensor.missing") throw TelemetryRequestException(TelemetryFailureKind.CONFIGURATION)
                val now = System.currentTimeMillis()
                return listOf(TelemetryObservation("temperature", 180.0, "°F", now, now))
            }
            override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = emptyFlow()
            override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
            override suspend fun disconnect() = Unit
        }
        val factory = object : TelemetryConnectorFactory {
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String) = adapter
            override suspend fun restore(context: Context, profile: TelemetryProfile) = adapter
        }
        val runtime = TelemetryRuntime(context, db) { factory }
        try {
            val cook = CookRepository(db, PhotoStorage(context)).startCook(NewCookDraft("Sensor isolation"))
            var profile = runtime.connect(TelemetryProfile("sensor-isolation", "home_assistant", "HA", onLogOnly = true), "fixture", "dummy")
            for (source in sources) { runtime.attach(profile, cook, source, emptyMap()); profile = runtime.store.profiles.value.single() }
            failing = true
            runtime.onLog(cook)
            assertEquals(1, db.cookDao().getAllSensorReadings().size)
            assertEquals("Connected", runtime.states.value[profile.id + ":sensor.healthy"])
            assertEquals("Unavailable or stale", runtime.states.value[profile.id + ":sensor.missing"])
            assertEquals("Unavailable or stale", runtime.states.value[profile.id + ":sensor.missing:temperature"])
        } finally { runtime.close(); db.close(); folder.deleteRecursively() }
    }

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

    @Test fun homeAssistantCodeConnectExchangesExactlyOnce(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "telemetry-ha-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder
        }
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val source = TelemetrySource("home_assistant", "sensor.probe", "Probe", channels = listOf(TelemetryChannel("temperature", "Food", TelemetryKind.FOOD)), identityScope = "https://home.example.com")
        val adapter = object : ReadOnlyTelemetryConnector {
            override val connectorId = "home_assistant"
            override suspend fun discover() = listOf(source)
            override suspend fun snapshot(deviceId: String) = emptyList<TelemetryObservation>()
            override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = emptyFlow()
            override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
            override suspend fun disconnect() = Unit
        }
        val factory = object : TelemetryConnectorFactory {
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String) = adapter
            override suspend fun restore(context: Context, profile: TelemetryProfile) = adapter
        }
        val exchanges = mutableListOf<String>()
        val runtime = TelemetryRuntime(context, db, homeAssistantExchange = { _, _, code -> exchanges += code }, factories = { factory })
        try {
            val profile = TelemetryProfile("ha-oauth", "home_assistant", "Home Assistant", endpoint = "https://home.example.com")
            val saved = runtime.connectHomeAssistant(profile, "the-code")
            // PL-06 (#172): exactly one token exchange for the whole round trip.
            assertEquals(listOf("the-code"), exchanges)
            assertEquals(listOf(source), saved.sources)
            assertEquals("Connected · read-only", runtime.states.value[profile.id])
            // PL-05: discovery is settled (and no longer in flight) afterwards.
            assertTrue(runtime.discoverySettled.value.contains(profile.id))
            assertFalse(runtime.discoveryInFlight.value.contains(profile.id))
        } finally { runtime.close(); db.close(); folder.deleteRecursively() }
    }

    @Test fun assignmentDraftSurvivesUntilAttachConsumesIt(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "telemetry-draft-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder
        }
        val db = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        val source = TelemetrySource("meater", "draft-probe", "Probe", channels = listOf(TelemetryChannel("internal", "Internal", TelemetryKind.FOOD)))
        val adapter = object : ReadOnlyTelemetryConnector {
            override val connectorId = "meater"
            override suspend fun discover() = listOf(source)
            override suspend fun snapshot(deviceId: String) = emptyList<TelemetryObservation>()
            override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = emptyFlow()
            override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
            override suspend fun disconnect() = Unit
        }
        val factory = object : TelemetryConnectorFactory {
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String) = adapter
            override suspend fun restore(context: Context, profile: TelemetryProfile) = adapter
        }
        val runtime = TelemetryRuntime(context, db) { factory }
        try {
            val cook = CookRepository(db, PhotoStorage(context)).startCook(NewCookDraft("Draft persistence"))
            val profile = runtime.connect(TelemetryProfile("draft-profile", "meater", "Probe"), "fixture", "dummy")
            // PL-05 (#172): an edit written to the runtime draft is still
            // there after the sheet's UI state is gone (the draft outlives
            // any composition), and a successful attach consumes it.
            runtime.updateAssignmentDraft(TelemetryAssignmentDraft(profile.id, sourceId = source.deviceId, cookId = cook, roles = mapOf("internal" to TelemetryKind.CHAMBER)))
            assertEquals(TelemetryKind.CHAMBER, runtime.assignmentDrafts.value.getValue(profile.id).roles["internal"])
            val draft = runtime.assignmentDrafts.value.getValue(profile.id)
            val mapped = source.copy(channels = source.channels.map { it.copy(kind = draft.roles[it.id] ?: it.kind) })
            runtime.attach(profile, cook, mapped, emptyMap())
            assertFalse(runtime.assignmentDrafts.value.containsKey(profile.id))
            assertEquals(TelemetryKind.CHAMBER, runtime.store.profiles.value.single().sources.single().channels.single().kind)
        } finally { runtime.close(); db.close(); folder.deleteRecursively() }
    }

}
