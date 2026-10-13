package com.pittech.devices

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Purely observational contract. No generic HTTP, MQTT, device-control or service-call methods. */
internal interface ReadOnlyTelemetryConnector {
    val connectorId: String
    val minimumIntervalMillis: Long get() = 15_000L
    val supportsHistory: Boolean get() = false
    suspend fun discover(): List<TelemetrySource>
    suspend fun snapshot(deviceId: String): List<TelemetryObservation>
    fun observe(deviceId: String): Flow<List<TelemetryObservation>>
    suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation>
    suspend fun disconnect()
}

internal enum class TelemetryKind(val storageType: String) {
    CHAMBER("pit_ambient"), FOOD("food_probe"), AMBIENT("pit_ambient"), SETPOINT("setpoint")
}
internal enum class TelemetryQuality { VALID, UNAVAILABLE, STALE, ERROR }

internal data class TelemetryChannel(val id: String, val name: String, val kind: TelemetryKind)

internal data class TelemetrySource(
    val connectorId: String,
    val deviceId: String,
    val label: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val channels: List<TelemetryChannel>,
) {
    init {
        require(connectorId.matches(Regex("[a-z][a-z0-9_-]{1,39}")))
        require(deviceId.isNotBlank() && deviceId.length <= 512 && label.isNotBlank())
        require(channels.isNotEmpty() && channels.map { it.id }.distinct().size == channels.size)
        require(channels.all { it.id.isNotBlank() && it.id.length <= 512 && it.name.isNotBlank() })
    }
}

internal data class TelemetryObservation(
    val channelId: String,
    val value: Double?,
    val unit: String,
    val observedAtMillis: Long?,
    val receivedAtMillis: Long,
    val quality: TelemetryQuality = TelemetryQuality.VALID,
    val eventId: String? = null,
    val fromHistory: Boolean = false,
)

internal data class NormalizedTelemetry(
    val channelId: String,
    val fahrenheit: Double,
    val measuredAtMillis: Long,
    val receivedAtMillis: Long,
    val timestampBasis: String,
    val eventKey: String,
)

internal object TelemetryNormalizer {
    fun normalize(raw: TelemetryObservation): NormalizedTelemetry? {
        if (raw.quality != TelemetryQuality.VALID || raw.value?.isFinite() != true ||
            raw.channelId.isBlank() || raw.receivedAtMillis <= 0L) return null
        if (raw.fromHistory && raw.observedAtMillis == null) return null
        val time = raw.observedAtMillis ?: raw.receivedAtMillis
        if (time <= 0 || time > raw.receivedAtMillis + 300_000L) return null
        val f = when (raw.unit.trim().lowercase(Locale.ROOT)) {
            "f", "°f", "fahrenheit" -> raw.value
            "c", "°c", "celsius" -> raw.value * 9.0 / 5.0 + 32.0
            else -> return null
        }
        if (!f.isFinite() || f !in -100.0..1000.0) return null
        return NormalizedTelemetry(raw.channelId, f, time, raw.receivedAtMillis,
            if (raw.observedAtMillis == null) "receipt" else "source_observed",
            raw.eventId?.takeIf { it.isNotBlank() } ?: (time.toString() + ":" + f.toBits().toString()))
    }

    /** Unambiguous length-framed SHA-256 keys; no raw device/account identity in Room IDs. */
    fun localKey(vararg parts: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            hash.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal object TelemetryConnectorRegistry {
    val planned = setOf("combustion", "home_assistant", "fireboard", "flame_boss", "meater")
    fun requirePlanned(id: String) { require(id in planned) { "Unapproved source" } }
}
