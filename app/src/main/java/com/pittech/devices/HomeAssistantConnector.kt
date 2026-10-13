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
        val host = requireNotNull(uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")) { "Home Assistant URL needs a hostname" }
        require(uri.userInfo == null && uri.fragment == null && uri.query == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Enter only the server origin, without a path" }
        require(uri.port == -1 || uri.port in 1..65535)
        val octets = host.split('.').map { it.toIntOrNull() }
        val privateIpv4 = octets.size == 4 && octets.all { it != null && it in 0..255 } &&
            (octets[0] == 10 || octets[0] == 127 || octets[0] == 192 && octets[1] == 168 ||
                octets[0] == 172 && octets[1] in 16..31)
        val privateIpv6 = host.contains(":") && runCatching { HomeAssistantLanDns.isLan(java.net.InetAddress.getByName(host)) }.getOrDefault(false)
        val local = host == "localhost" || host.endsWith(".local") || privateIpv6 || privateIpv4
        require(uri.scheme == "https" || (uri.scheme == "http" && local)) {
            "Use HTTPS for Home Assistant outside your local network"
        }
        val port = if (uri.scheme == "https" && uri.port == 443 || uri.scheme == "http" && uri.port == 80) -1 else uri.port
        base = URI(uri.scheme, null, host, port, null, null, null).toASCIIString()
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
        .followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build(),
) : HomeAssistantRest {
    override suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank())
        val chosenClient = if (url.startsWith("http://")) client.newBuilder().dns(HomeAssistantLanDns).build() else client
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer " + token).header("Accept", "application/json").build()
        chosenClient.newCall(request).execute().use { response ->
            TelemetryHttpPolicy.check(response)
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
    private val tokenProvider: (suspend () -> String)? = null,
    private val tokenRefresh: (suspend () -> String)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "home_assistant"
    override val supportsHistory = true
    private val endpoint = HomeAssistantEndpoint(url)
    private val socketLock = Any()
    private var sharedSocket: WebSocket? = null
    private val listeners = mutableMapOf<String, MutableSet<(List<TelemetryObservation>?, Throwable?) -> Unit>>()
    private fun failSocket(socket: WebSocket, error: Throwable) {
        val callbacks = synchronized(socketLock) {
            if (socket !== sharedSocket) return
            sharedSocket = null
            listeners.values.flatMap { it.toList() }.also { listeners.clear() }
        }
        callbacks.forEach { it(null, error) }
        socket.cancel()
    }
    private suspend fun get(path: String): String {
        accessToken = tokenProvider?.invoke() ?: accessToken
        return try { rest.get(endpoint.readPath(path), accessToken) }
        catch (e: TelemetryRequestException) {
            if (e.kind != TelemetryFailureKind.AUTHORIZATION || tokenRefresh == null) throw e
            accessToken = tokenRefresh.invoke()
            rest.get(endpoint.readPath(path), accessToken)
        }
    }

    override suspend fun discover(): List<TelemetrySource> {
        val states = JSONArray(get("api/states"))
        return (0 until states.length()).mapNotNull { i ->
            val obj = states.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("entity_id")
            if (!HomeAssistantPayload.isTemperature(obj)) return@mapNotNull null
            TelemetrySource(
                connectorId, id, obj.optJSONObject("attributes")?.optString("friendly_name")
                    ?.takeIf { it.isNotBlank() } ?: id,
                "Home Assistant", channels = listOf(TelemetryChannel("temperature", "Temperature", TelemetryKind.FOOD)), identityScope = endpoint.base,
            )
        }
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
        require(HomeAssistantPayload.isSensorId(deviceId))
        val json = JSONObject(get("api/states/" + deviceId))
        return listOfNotNull(HomeAssistantPayload.reading(json, clock(), historical = false))
    }

    /** Multiplex all sensors in this account through one observational subscription. */
    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = callbackFlow {
        require(HomeAssistantPayload.isSensorId(deviceId))
        accessToken = tokenProvider?.invoke() ?: accessToken
        val callback: (List<TelemetryObservation>?, Throwable?) -> Unit = { values, error ->
            if (error != null) close(error) else if (values != null) trySend(values)
        }
        synchronized(socketLock) {
            listeners.getOrPut(deviceId) { mutableSetOf() }.add(callback)
            if (sharedSocket == null) {
                val chosen = wsClient.newBuilder().followRedirects(false).followSslRedirects(false)
                    .apply { if (endpoint.websocket.startsWith("ws://")) dns(HomeAssistantLanDns) }.build()
                sharedSocket = chosen.newWebSocket(Request.Builder().url(endpoint.websocket).build(), object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val message = JSONObject(text)
                            when (message.optString("type")) {
                                "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", accessToken).toString())
                                "auth_ok" -> webSocket.send(JSONObject().put("id", 1).put("type", "subscribe_events").put("event_type", "state_changed").toString())
                                "auth_invalid" -> failSocket(webSocket, TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION))
                                "result" -> if (!message.optBoolean("success", true)) failSocket(webSocket, TelemetryRequestException(TelemetryFailureKind.CONFIGURATION))
                                "event" -> {
                                    val data = message.optJSONObject("event")?.optJSONObject("data") ?: return@runCatching
                                    val state = data.optJSONObject("new_state") ?: return@runCatching
                                    val reading = HomeAssistantPayload.reading(state, clock(), false) ?: return@runCatching
                                    val callbacks = synchronized(socketLock) { listeners[data.optString("entity_id")]?.toList().orEmpty() }
                                    callbacks.forEach { it(listOf(reading), null) }
                                }
                            }
                        }.onFailure { failSocket(webSocket, it) }
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failSocket(webSocket, IOException("Home Assistant disconnected"))
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failSocket(webSocket, IOException("Home Assistant connection interrupted", t))
                })
            }
        }
        awaitClose {
            synchronized(socketLock) {
                listeners[deviceId]?.remove(callback)
                if (listeners[deviceId]?.isEmpty() == true) listeners.remove(deviceId)
                if (listeners.isEmpty()) { sharedSocket?.cancel(); sharedSocket = null }
            }
        }
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
        val blocks = JSONArray(get(path))
        return (0 until blocks.length()).flatMap { i ->
            val entries = blocks.optJSONArray(i) ?: return@flatMap emptyList()
            (0 until entries.length()).mapNotNull { index ->
                val state = entries.optJSONObject(index) ?: return@mapNotNull null
                if (state.optString("entity_id") != deviceId) return@mapNotNull null
                HomeAssistantPayload.reading(state, clock(), historical = true)?.takeIf { it.observedAtMillis?.let { t -> t in fromMillis..untilMillis } == true }
            }
        }
    }

    override suspend fun disconnect() {
        val socket = synchronized(socketLock) { sharedSocket }
        if (socket != null) failSocket(socket, IOException("Home Assistant disconnected"))
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
        val timestamp = listOf("last_reported", "last_updated", "last_changed").firstNotNullOfOrNull { key ->
            runCatching { Instant.parse(state.getString(key)).toEpochMilli() }.getOrNull()
        }
        return TelemetryObservation(
            "temperature", value, state.getJSONObject("attributes").getString("unit_of_measurement"),
            timestamp, received, quality, eventId = timestamp?.toString(),
            fromHistory = historical,
        )
    }
}
