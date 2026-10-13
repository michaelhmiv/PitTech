package com.pittech.devices

import android.content.Context
import com.pittech.PitTechApplication
import com.pittech.data.TelemetryRecordingRepository
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
                while (currentCoroutineContext().isActive) {
                    emit(snapshot(deviceId))
                    // The collector may cancel or throttle a frame. Only persisted Room
                    // evidence is a receipt that the shell can use before replacing the process.
                    val app = context.applicationContext as PitTechApplication
                    val cook = profile.cookId
                    val source = profile.sources.firstOrNull { it.deviceId == deviceId }
                    if (cook != null && source != null) {
                        TelemetryRecordingRepository(app.database).latestObservation(cook, source)?.let { accepted ->
                            File(context.noBackupFilesDir, "telemetry-process-ci.receipt").writeText(accepted.toString())
                        }
                    }
                    delay(1_000L)
                }
            }
            override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long) = emptyList<TelemetryObservation>()
            override suspend fun disconnect() = Unit
        }
    }
}
