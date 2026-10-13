package com.pittech

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.data.*
import com.pittech.devices.*
import com.pittech.domain.NewCookDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class TelemetryRecordingServiceTest {
    @Test fun backgroundServiceRestoresProfilesIsolatesOutagesAndStopsOnCookCompletion(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PitTechApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        app.stopService(Intent(app, CookRecordingService::class.java))
        app.database.clearAllTables()
        val folder = File(app.cacheDir, "telemetry-service-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder
        }
        val clock = AtomicLong(System.currentTimeMillis())
        val healthy = TelemetrySource("meater", "service-probe", "Food probe", channels = listOf(TelemetryChannel("p", "Food", TelemetryKind.FOOD)))
        val offline = healthy.copy(connectorId = "fireboard", deviceId = "offline")
        fun factory(provider: String) = object : TelemetryConnectorFactory {
            private fun connector() = object : ReadOnlyTelemetryConnector {
                override val connectorId = provider
                override suspend fun discover() = listOf(if (provider == "meater") healthy else offline)
                override suspend fun snapshot(deviceId: String) = emptyList<TelemetryObservation>()
                override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = flow {
                    if (provider == "fireboard") throw java.io.IOException("Fixture outage")
                    while (currentCoroutineContext().isActive) {
                        val at = System.currentTimeMillis()
                        clock.addAndGet(20_000L)
                        emit(listOf(TelemetryObservation("p", 180.0, "°F", at, at, eventId = at.toString())))
                        delay(200L)
                    }
                }
                override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
                override suspend fun disconnect() = Unit
            }
            override suspend fun connect(context: Context, profile: TelemetryProfile, user: String, secret: String) = connector()
            override suspend fun restore(context: Context, profile: TelemetryProfile) = connector()
        }
        fun runtime() = TelemetryRuntime(context, app.database, clock = clock::get, factories = ::factory)
        var current = runtime()
        app.telemetryRuntimeForTests = current
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        suspend fun waitUntil(condition: suspend () -> Boolean) = withTimeout(25_000L) { while (!condition()) delay(50) }
        try {
            val cook = app.cookRepository.startCook(NewCookDraft("Telemetry foreground service"))
            for (source in listOf(healthy, offline)) {
                val profile = current.connect(TelemetryProfile("service-" + source.connectorId, source.connectorId, source.label, background = true, intervalMillis = 15_000), "fixture", "dummy")
                current.attach(profile, cook, source, emptyMap())
            }
            instrumentation.runOnMainSync { CookRecordingService.startTelemetry(app) }
            waitUntil { current.serviceHosted.value && app.database.cookDao().getAllSensorReadings().size >= 2 }
            waitUntil { app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 } }
            scenario.moveToState(Lifecycle.State.CREATED)
            val before = app.database.cookDao().getAllSensorReadings().size
            waitUntil { app.database.cookDao().getAllSensorReadings().size >= before + 3 }
            assertFalse(current.foreground.value)
            waitUntil { current.states.value["service-fireboard:offline"]?.contains("Reconnecting") == true }

            // Recreate application-owned jobs from durable profiles/Room without a new cook.
            app.stopService(Intent(app, CookRecordingService::class.java))
            waitUntil { !current.serviceHosted.value }
            current.close()
            current = runtime(); app.telemetryRuntimeForTests = current
            assertEquals(2, current.store.profiles.value.size)
            assertEquals(1, app.database.cookDao().getAllCooks().size)
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.runOnMainSync { CookRecordingService.startTelemetry(app) }
            waitUntil { current.serviceHosted.value && app.database.cookDao().getAllSensorReadings().size > before + 3 }
            val profile = current.store.profiles.value.first { it.provider == "meater" }
            current.update(profile.copy(monitoring = false))
            waitUntil { !current.store.profiles.value.first { it.provider == "meater" }.monitoring }
            val paused = app.database.cookDao().getAllSensorReadings().size
            delay(600)
            assertEquals(paused, app.database.cookDao().getAllSensorReadings().size)
            current.update(current.store.profiles.value.first { it.provider == "meater" }.copy(monitoring = true))
            waitUntil { app.database.cookDao().getAllSensorReadings().size > paused }
            app.database.cookDao().finishCook(cook, System.currentTimeMillis())
            waitUntil { !current.serviceHosted.value }
            val finished = app.database.cookDao().getAllSensorReadings().size
            delay(600)
            assertEquals(finished, app.database.cookDao().getAllSensorReadings().size)
        } finally {
            app.stopService(Intent(app, CookRecordingService::class.java))
            scenario.close(); current.close(); app.telemetryRuntimeForTests = null
            app.database.clearAllTables(); folder.deleteRecursively()
        }
    }
}
