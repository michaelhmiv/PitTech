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
    suspend fun chart(token: String, sessionId: String): JSONArray = throw TelemetryRequestException(TelemetryFailureKind.CONFIGURATION)
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
            TelemetryHttpPolicy.check(response)
            JSONArray(response.body?.string() ?: "[]")
        }
    }

    override suspend fun chart(token: String, sessionId: String): JSONArray = withContext(Dispatchers.IO) {
        require(sessionId.matches(Regex("[0-9]{1,18}")))
        val request = Request.Builder().url("https://fireboard.io/api/v1/sessions/" + sessionId + "/chart.json")
            .header("Authorization", "Token " + token).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            TelemetryHttpPolicy.check(response)
            require((response.body?.contentLength() ?: 0) <= 8_388_608L)
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
            TelemetryHttpPolicy.check(response)
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
    private val sessionId: String? = null,
    private val channelLabels: Map<String, Map<String, String>> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "fireboard"
    override val minimumIntervalMillis = 30_000L
    override val supportsHistory get() = sessionId != null && channelLabels.isNotEmpty()
    private val account = accountSnapshots.computeIfAbsent(TelemetryNormalizer.localKey(connectorId, token)) {
        SharedSnapshot()
    }

    private suspend fun list(): JSONArray = account.mutex.withLock {
        require(token.isNotBlank())
        val now = clock()
        if (account.cached != null && now >= account.lastFetch && now - account.lastFetch < 30_000L) return@withLock account.cached!!
        account.budget.reserve(now)
        val received = try { transport.devices(token) } catch (error: Exception) { account.budget.failed(error, now); throw error }
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

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = observe(deviceId, 30_000L)
    override fun observe(deviceId: String, intervalMillis: Long): Flow<List<TelemetryObservation>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(snapshot(deviceId))
            delay(maxOf(minimumIntervalMillis, intervalMillis))
        }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(untilMillis >= fromMillis)
        if (!supportsHistory) return emptyList()
        val id = sessionId ?: return emptyList()
        require(untilMillis - fromMillis <= 24L * 3_600_000L)
        val chart = account.mutex.withLock {
            val now = clock()
            account.history[id]?.takeIf { now - it.first in 0 until 60_000L }?.second ?: run {
                account.budget.reserve(now)
                val values = try { transport.chart(token, id) } catch (error: Exception) { account.budget.failed(error, now); throw error }
                account.history[id] = now to values
                values
            }
        }
        return parseHistory(chart, deviceId, channelLabels[deviceId].orEmpty(), fromMillis, untilMillis, clock())
    }

    override suspend fun disconnect() = account.mutex.withLock {
        token = ""
        account.cached = null
        account.history.clear()
    }

    internal companion object {
        private class SharedSnapshot(val mutex: Mutex = Mutex(),
            var cached: JSONArray? = null, var lastFetch: Long = Long.MIN_VALUE,
            val budget: TelemetryAccountBudget = TelemetryAccountBudget(30_000L),
            val history: MutableMap<String, Pair<Long, JSONArray>> = mutableMapOf())
        private val accountSnapshots = ConcurrentHashMap<String, SharedSnapshot>()

        /** Documented chart labels are mapped only when unique; never guess a port by array position. */
        fun parseHistory(chart: JSONArray, deviceId: String, labels: Map<String, String>, from: Long, until: Long, receipt: Long): List<TelemetryObservation> {
            val unique = labels.entries.groupBy { it.value }.filterValues { it.size == 1 }.mapValues { it.value.single().key }
            return (0 until chart.length().coerceAtMost(100)).flatMap { index ->
                val series = chart.optJSONObject(index) ?: return@flatMap emptyList()
                if (series.optString("device") != deviceId) return@flatMap emptyList()
                val channel = unique[series.optString("label")] ?: return@flatMap emptyList()
                val unit = when (series.optInt("degreetype")) { 1 -> "°C"; 2 -> "°F"; else -> return@flatMap emptyList() }
                val x = series.optJSONArray("x") ?: return@flatMap emptyList()
                val y = series.optJSONArray("y") ?: return@flatMap emptyList()
                if (x.length() != y.length()) return@flatMap emptyList()
                (0 until x.length().coerceAtMost(100_000)).mapNotNull { point ->
                    val time = x.optDouble(point, Double.NaN)
                    if (!time.isFinite() || time <= 0 || time > Long.MAX_VALUE / 1000.0) return@mapNotNull null
                    val at = if (time < 100_000_000_000.0) (time * 1000.0).toLong() else time.toLong()
                    val temp = y.optDouble(point, Double.NaN)
                    if (at !in from..until || !temp.isFinite()) return@mapNotNull null
                    TelemetryObservation(channel, temp, unit, at, receipt, eventId = at.toString(), fromHistory = true)
                }
            }.take(20_000)
        }

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

internal class FireBoardFactory : TelemetryConnectorFactory {
    override suspend fun connect(context: android.content.Context, profile: TelemetryProfile, user: String, secret: String): ReadOnlyTelemetryConnector {
        val credential = if (user.isBlank()) secret else FireBoardHttpTransport().login(user, secret)
        require(credential.isNotBlank()) { "Enter your account credentials or an access token." }
        val connector = FireBoardCloudConnector(credential)
        connector.discover()
        TelemetryTokenStore(context).save(profile.provider, profile.id, credential)
        return connector
    }
    override suspend fun restore(context: android.content.Context, profile: TelemetryProfile): ReadOnlyTelemetryConnector {
        val credential = TelemetryTokenStore(context).load(profile.provider, profile.id)
            ?: throw TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
        return FireBoardCloudConnector(credential, sessionId = profile.deviceHint.takeIf { it.matches(Regex("[0-9]{1,18}")) },
            channelLabels = profile.sources.associate { it.deviceId to it.channels.associate { c -> c.id to c.name } })
    }
}
