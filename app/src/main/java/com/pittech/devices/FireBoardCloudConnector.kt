package com.pittech.devices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

internal interface FireBoardTransport {
    suspend fun devices(token: String): JSONArray
}

/** Only FireBoard's published observational API endpoints are used. */
internal class FireBoardHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build(),
) : FireBoardTransport {
    private val userAgent = "PitTech/0.1 (read-only temperature journal)"
    override suspend fun devices(token: String): JSONArray = withContext(Dispatchers.IO) {
        require(token.isNotBlank())
        val request = Request.Builder().url("https://fireboard.io/api/v1/devices.json")
            .header("Authorization", "Token " + token).header("User-Agent", userAgent)
            .header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("FireBoard read failed (HTTP " + response.code + ")")
            JSONArray(response.body?.string() ?: "[]")
        }
    }

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotBlank())
        val payload = JSONObject().put("username", username).put("password", password).toString()
        val request = Request.Builder().url("https://fireboard.io/api/rest-auth/login/")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("FireBoard sign-in failed (HTTP " + response.code + ")")
            JSONObject(response.body?.string() ?: "{}").getString("key")
        }
    }
}

/**
 * FireBoard allows at most 17 account requests in five minutes (2025 docs).
 * One shared GET /devices.json every 30 seconds = at most 10 calls per 5 minutes
 * during continuous sampling, regardless of the number of connected probes.
 */
internal class FireBoardCloudConnector(
    private var token: String,
    private val transport: FireBoardTransport = FireBoardHttpTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "fireboard"
    private val account = accountSnapshots.computeIfAbsent(TelemetryNormalizer.localKey(connectorId, token)) {
        SharedSnapshot()
    }

    private suspend fun list(): JSONArray = account.mutex.withLock {
        require(token.isNotBlank())
        val now = clock()
        if (account.cached != null && now >= account.lastFetch && now - account.lastFetch < 30_000L) return@withLock account.cached!!
        val received = transport.devices(token)
        account.cached = received
        account.lastFetch = now
        received
    }

    override suspend fun discover(): List<TelemetrySource> {
        val data = list()
        return (0 until data.length()).mapNotNull { i ->
            val obj = data.optJSONObject(i) ?: return@mapNotNull null
            val uuid = obj.optString("uuid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val channels = obj.optJSONArray("channels")
            val selected = if (channels == null) emptyList() else (0 until channels.length()).mapNotNull { index ->
                val ch = channels.optJSONObject(index) ?: return@mapNotNull null
                val number = ch.optInt("channel", -1)
                if (number <= 0) return@mapNotNull null
                TelemetryChannel("channel" + number, ch.optString("channel_label").takeIf { it.isNotBlank() }
                    ?: ("Channel " + number), TelemetryKind.FOOD)
            }
            if (selected.isEmpty()) return@mapNotNull null
            TelemetrySource(connectorId, uuid, obj.optString("title").ifBlank { "FireBoard" },
                manufacturer = "FireBoard", channels = selected.distinctBy { it.id })
        }
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
        val data = list()
        val obj = (0 until data.length()).mapNotNull(data::optJSONObject).firstOrNull {
            it.optString("uuid") == deviceId
        } ?: return emptyList()
        return parseTemps(obj, clock())
    }

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(snapshot(deviceId))
            delay(30_000L)
        }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(untilMillis >= fromMillis)
        // Session/chart backfill needs explicit cook/session mapping; not silently guessed.
        return emptyList()
    }

    override suspend fun disconnect() = account.mutex.withLock {
        token = ""
    }

    internal companion object {
        private class SharedSnapshot(val mutex: Mutex = Mutex(),
            var cached: JSONArray? = null, var lastFetch: Long = Long.MIN_VALUE)
        private val accountSnapshots = ConcurrentHashMap<String, SharedSnapshot>()

        fun parseTemps(device: JSONObject, received: Long): List<TelemetryObservation> {
            val temps = device.optJSONArray("latest_temps") ?: return emptyList()
            return (0 until temps.length()).mapNotNull { i ->
                val obj = temps.optJSONObject(i) ?: return@mapNotNull null
                val port = obj.optInt("channel", -1)
                if (port <= 0) return@mapNotNull null
                val unit = when (obj.optInt("degreetype", 0)) { 1 -> "°C"; 2 -> "°F"; else -> return@mapNotNull null }
                val time = runCatching { Instant.parse(obj.getString("created")).toEpochMilli() }.getOrNull()
                val stale = time == null || time > received + 300_000L || received - time > 60_000L
                val temperature = obj.optDouble("temp", Double.NaN)
                TelemetryObservation("channel" + port, temperature.takeIf { it.isFinite() }, unit, time, received,
                    if (stale) TelemetryQuality.STALE else TelemetryQuality.VALID,
                    eventId = obj.optString("created").takeIf { it.isNotBlank() })
            }
        }
    }
}
