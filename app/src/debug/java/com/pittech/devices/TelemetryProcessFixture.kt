package com.pittech.devices

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File

/** Explicit CI fixture, absent from public release and inactive without the private test marker. */
internal object TelemetryProcessFixture {
    @JvmStatic fun restore(context: Context, profile: TelemetryProfile): ReadOnlyTelemetryConnector? {
        if (profile.id != "telemetry-process-ci" || !File(context.noBackupFilesDir, "telemetry-process-ci.marker").exists()) return null
        return object : ReadOnlyTelemetryConnector {
            override val connectorId = "meater"
            override val minimumIntervalMillis = 1_000L
            override suspend fun discover() = profile.sources
            override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
                val now = System.currentTimeMillis()
                return listOf(TelemetryObservation("internal", 180.0, "°F", now, now, eventId = now.toString()))
            }
            override fun observe(deviceId: String) = flow {
                while (currentCoroutineContext().isActive) { emit(snapshot(deviceId)); delay(1_000L) }
            }
            override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
            override suspend fun disconnect() = Unit
        }
    }
}
