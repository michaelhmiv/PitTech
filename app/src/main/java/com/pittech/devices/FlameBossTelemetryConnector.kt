package com.pittech.devices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The manufacturer documents token issuance at myflameboss.com/api/v4/sessions. */
internal class FlameBossSessionApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build(),
) {
    internal data class Session(val userId: Long, val token: String) {
        val mqttUsername: String get() = "T-" + userId
    }

    suspend fun login(email: String, password: String): Session = withContext(Dispatchers.IO) {
        require(email.isNotBlank() && password.isNotBlank())
        val form = FormBody.Builder().add("session[login]", email)
            .add("session[password]", password).build()
        val request = Request.Builder().url("https://myflameboss.com/api/v4/sessions")
            .post(form).header("User-Agent", "PitTech/0.1 (read-only)").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Flame Boss login failed (HTTP " + response.code + ")")
            val data = JSONObject(response.body?.string() ?: "{}")
            Session(data.getLong("user_id"), data.getString("auth_token"))
        }
    }
}

/** Documented MQTT topic and sensor-frame decoder; it never parses any control messages. */
internal object FlameBossTemperatureFrame {
    private const val DISCONNECTED = -32767

    fun parse(deviceId: String, topic: String, json: String, received: Long): List<TelemetryObservation>? {
        require(deviceId.matches(Regex("[0-9]{1,16}")))
        if (topic != "flameboss/" + deviceId + "/send/open") return null
        val frame = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (frame.optString("name") != "temps") return null
        val seconds = frame.optLong("sec", 0L)
        val observed = seconds.takeIf { it in 1..(Long.MAX_VALUE / 1000L) }?.times(1000L)
        if (observed == null) return null
        val temperatures = frame.optJSONArray("temps") ?: return null
        val list = (0 until temperatures.length().coerceAtMost(8)).map { i ->
            val counts = temperatures.optInt(i, DISCONNECTED)
            val available = counts != DISCONNECTED && counts in -200..9000
            TelemetryObservation(
                "probe" + i, if (available) counts / 10.0 else null, "°C", observed, received,
                if (available) TelemetryQuality.VALID else TelemetryQuality.UNAVAILABLE,
                eventId = observed.toString(),
            )
        }
        val setpoint = frame.optInt("set_temp", DISCONNECTED)
        if (setpoint != DISCONNECTED && setpoint in -200..9000) {
            return list + TelemetryObservation("setpoint", setpoint / 10.0, "°C", observed, received,
                eventId = observed.toString())
        }
        return list
    }
}

/**
 * Core Flame Boss adapter over an **already-authorized, read-only MQTT stream**.
 * The actual broker connection must use manufacturer-confirmed host/TLS/topic
 * credentials; do not guess broker endpoints from third-party screenshots.
 *
 * Caller supplies a subscription-only message flow and owns its lifecycle.
 */
internal class FlameBossTelemetryConnector(
    private val deviceId: String,
    private val frames: Flow<Pair<String, String>>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "flame_boss"
    private var last: List<TelemetryObservation> = emptyList()

    init { require(deviceId.matches(Regex("[0-9]{1,16}"))) }

    override suspend fun discover(): List<TelemetrySource> = listOf(
        TelemetrySource(connectorId, deviceId, "Flame Boss " + deviceId, "Flame Boss",
            // Flame Boss frame slot zero is the pit/chamber sensor; subsequent
            // channels represent removable food probes.
            channels = listOf(TelemetryChannel("probe0", "Pit chamber", TelemetryKind.CHAMBER)) +
                (1..3).map { TelemetryChannel("probe" + it, "Food probe " + it, TelemetryKind.FOOD) } +
                TelemetryChannel("setpoint", "Observed setpoint", TelemetryKind.SETPOINT))
    )

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> =
        if (deviceId == this.deviceId) last else emptyList()

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> {
        require(deviceId == this.deviceId)
        return frames.mapNotNull { (topic, payload) ->
            FlameBossTemperatureFrame.parse(deviceId, topic, payload, clock())?.also { last = it }
        }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(untilMillis >= fromMillis)
        return emptyList()
    }

    override suspend fun disconnect() { last = emptyList() }
}
