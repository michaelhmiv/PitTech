package com.pittech.devices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Requests can only target the selected HA instance and strictly allowlisted read-only paths. */
internal class HomeAssistantEndpoint(raw: String) {
    val base: String
    val websocket: String
    init {
        val uri = URI.create(raw.trim().trimEnd('/'))
        val host = uri.host?.lowercase() ?: error("Home Assistant URL needs a hostname")
        require(uri.userInfo == null && uri.fragment == null && uri.query == null)
        require(uri.port in -1..65535)
        val local = host == "localhost" || host.endsWith(".local") ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..+").matches(host)
        require(uri.scheme == "https" || (uri.scheme == "http" && local)) {
            "Use HTTPS for Home Assistant outside your local network"
        }
        base = raw.trim().trimEnd('/')
        websocket = (if (uri.scheme == "https") "wss://" else "ws://") + base.substringAfter("://") + "/api/websocket"
    }

    fun readPath(path: String): String {
        val allowed = path == "api/states" ||
            path.startsWith("api/states/sensor.") && !path.contains("?") ||
            path.startsWith("api/history/period/") && path.contains("filter_entity_id=")
        require(allowed && ".." !in path && "#" !in path && "\\" !in path) { "Only observational Home Assistant APIs are available" }
        return base + "/" + path
    }
}

internal interface HomeAssistantRest {
    suspend fun get(url: String, token: String): String
}

internal class HomeAssistantHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build(),
) : HomeAssistantRest {
    override suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank())
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer " + token).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Home Assistant read failed (HTTP " + response.code + ")")
            response.body?.string() ?: throw IOException("Empty Home Assistant response")
        }
    }
}

/**
 * Connects only to a user's own Home Assistant server. Doesn't install third-party
 * integrations, call services, change device settings or store account credentials.
 * Caller must persist user-provided credentials with Android Keystore.
 */
internal class HomeAssistantConnector(
    url: String,
    private var accessToken: String,
    private val rest: HomeAssistantRest = HomeAssistantHttp(),
    private val wsClient: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "home_assistant"
    private val endpoint = HomeAssistantEndpoint(url)
    private val sockets = mutableSetOf<WebSocket>()

    override suspend fun discover(): List<TelemetrySource> {
        val states = JSONArray(rest.get(endpoint.readPath("api/states"), accessToken))
        return (0 until states.length()).mapNotNull { i ->
            val obj = states.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("entity_id")
            if (!HomeAssistantPayload.isTemperature(obj)) return@mapNotNull null
            TelemetrySource(
                connectorId, id, obj.optJSONObject("attributes")?.optString("friendly_name")
                    ?.takeIf { it.isNotBlank() } ?: id,
                "Home Assistant", channels = listOf(TelemetryChannel("temperature", "Temperature", TelemetryKind.FOOD)),
            )
        }
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
        require(HomeAssistantPayload.isSensorId(deviceId))
        val json = JSONObject(rest.get(endpoint.readPath("api/states/" + deviceId), accessToken))
        return listOfNotNull(HomeAssistantPayload.reading(json, clock(), historical = false))
    }

    /** One observational state_changed subscription. No call_service operations. */
    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = callbackFlow {
        require(HomeAssistantPayload.isSensorId(deviceId))
        val socket = wsClient.newWebSocket(Request.Builder().url(endpoint.websocket).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val message = JSONObject(text)
                        when (message.optString("type")) {
                            "auth_required" -> webSocket.send(JSONObject().put("type", "auth")
                                .put("access_token", accessToken).toString())
                            "auth_ok" -> webSocket.send(JSONObject().put("id", 1)
                                .put("type", "subscribe_events").put("event_type", "state_changed").toString())
                            "auth_invalid" -> close(IOException("Home Assistant authentication expired"))
                            "event" -> {
                                val data = message.optJSONObject("event")?.optJSONObject("data")
                                if (data?.optString("entity_id") == deviceId) {
                                    val state = data.optJSONObject("new_state") ?: return@runCatching
                                    val observation = HomeAssistantPayload.reading(state, clock(), false)
                                    if (observation != null) trySend(listOf(observation))
                                }
                            }
                        }
                    }.onFailure { close(it) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(IOException("Home Assistant connection interrupted", t))
                }
            })
        synchronized(sockets) { sockets.add(socket) }
        awaitClose { socket.close(1000, "PitTech disconnected"); synchronized(sockets) { sockets.remove(socket) } }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(HomeAssistantPayload.isSensorId(deviceId))
        require(fromMillis > 0L && untilMillis >= fromMillis && untilMillis - fromMillis <= 24L * 3600 * 1000) {
            "History is requested in bounded 24-hour windows"
        }
        val entity = URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name())
        val end = URLEncoder.encode(Instant.ofEpochMilli(untilMillis).toString(), StandardCharsets.UTF_8.name())
        val path = "api/history/period/" + Instant.ofEpochMilli(fromMillis).toString() +
            "?filter_entity_id=" + entity + "&end_time=" + end
        val blocks = JSONArray(rest.get(endpoint.readPath(path), accessToken))
        return (0 until blocks.length()).flatMap { i ->
            val entries = blocks.optJSONArray(i) ?: return@flatMap emptyList()
            (0 until entries.length()).mapNotNull { index ->
                val state = entries.optJSONObject(index) ?: return@mapNotNull null
                if (state.optString("entity_id") != deviceId) return@mapNotNull null
                HomeAssistantPayload.reading(state, clock(), historical = true)
            }
        }
    }

    override suspend fun disconnect() {
        synchronized(sockets) { sockets.toList().also { sockets.clear() } }.forEach {
            it.close(1000, "PitTech disconnected")
        }
        accessToken = ""
    }
}

/** HA state-change history is not a fixed-interval sample stream. */
internal object HomeAssistantPayload {
    fun isSensorId(id: String): Boolean =
        id.matches(Regex("sensor\\.[a-z0-9_]+")) && id.length <= 255

    fun isTemperature(json: JSONObject): Boolean {
        val id = json.optString("entity_id")
        val attr = json.optJSONObject("attributes") ?: return false
        val unit = attr.optString("unit_of_measurement")
        return isSensorId(id) && (unit == "°C" || unit == "°F" || unit == "C" || unit == "F") &&
            (attr.optString("device_class").let { it == "temperature" || it.isBlank() })
    }

    fun reading(state: JSONObject, received: Long, historical: Boolean): TelemetryObservation? {
        if (!isTemperature(state)) return null
        val status = state.optString("state")
        val value = status.toDoubleOrNull()
        val quality = if (status == "unavailable" || status == "unknown" || value == null) {
            TelemetryQuality.UNAVAILABLE
        } else TelemetryQuality.VALID
        val timestamp = listOf("last_updated", "last_changed").firstNotNullOfOrNull { key ->
            runCatching { Instant.parse(state.getString(key)).toEpochMilli() }.getOrNull()
        }
        return TelemetryObservation(
            "temperature", value, state.getJSONObject("attributes").getString("unit_of_measurement"),
            timestamp, received, quality, eventId = state.optString("last_updated").takeIf { it.isNotBlank() },
            fromHistory = historical,
        )
    }
}
