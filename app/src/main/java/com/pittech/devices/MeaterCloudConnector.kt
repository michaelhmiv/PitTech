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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Strictly documented MEATER endpoints; no device-control methods. */
internal interface MeaterTransport {
    suspend fun devices(jwt: String): JSONObject
}

internal class MeaterHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
) : MeaterTransport {
    private val origin = "https://public-api.cloud.meater.com/v1/"

    override suspend fun devices(jwt: String): JSONObject = get("devices", jwt)

    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        require(email.isNotBlank() && password.isNotBlank())
        val json = JSONObject().put("email", email).put("password", password).toString()
        val request = Request.Builder().url(origin + "login")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MEATER sign-in was rejected (HTTP " + response.code + ")")
            JSONObject(response.body?.string() ?: "{}").getJSONObject("data").getString("token")
        }
    }

    private suspend fun get(endpoint: String, jwt: String) = withContext(Dispatchers.IO) {
        require(jwt.isNotBlank())
        require(endpoint == "devices") { "Undocumented endpoint" }
        val request = Request.Builder().url(origin + endpoint)
            .header("Authorization", "Bearer " + jwt)
            .header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MEATER cloud request failed (HTTP " + response.code + ")")
            JSONObject(response.body?.string() ?: "{}")
        }
    }
}

/**
 * MEATER recommends 2 requests per 60 seconds, across the entire account.
 * Every device subscription shares one cached GET /devices fetch. No historical
 * endpoint is documented: return empty history rather than fabricate samples.
 * The caller owns Android Keystore persistence; this class keeps only the JWT.
 */
internal class MeaterCloudConnector(
    private var jwt: String,
    private val transport: MeaterTransport = MeaterHttpTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ReadOnlyTelemetryConnector {
    override val connectorId = "meater"
    private val mutex = Mutex()
    private var lastFetch = Long.MIN_VALUE
    private var cached: JSONArray? = null

    private suspend fun deviceList(): JSONArray = mutex.withLock {
        require(jwt.isNotBlank()) { "Connect your MEATER Cloud account" }
        val now = clock()
        if (cached != null && now >= lastFetch && now - lastFetch < 30_000L) return@withLock cached!!
        val response = transport.devices(jwt)
        if (response.optInt("statusCode", 200) !in 200..299) {
            throw IOException("MEATER returned an API error")
        }
        val devices = response.getJSONObject("data").getJSONArray("devices")
        cached = devices
        lastFetch = now
        devices
    }

    override suspend fun discover(): List<TelemetrySource> {
        val data = deviceList()
        return (0 until data.length()).mapNotNull { i ->
            val obj = data.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TelemetrySource(connectorId, id, "MEATER " + id.takeLast(4), "MEATER",
                channels = listOf(TelemetryChannel("internal", "Internal", TelemetryKind.FOOD),
                    TelemetryChannel("ambient", "Ambient", TelemetryKind.AMBIENT),
                    TelemetryChannel("target", "Target (observed)", TelemetryKind.SETPOINT)))
        }
    }

    override suspend fun snapshot(deviceId: String): List<TelemetryObservation> {
        val all = deviceList()
        val obj = (0 until all.length()).mapNotNull(all::optJSONObject).firstOrNull {
            it.optString("id") == deviceId
        } ?: return emptyList()
        return parseDevice(obj, clock())
    }

    override fun observe(deviceId: String): Flow<List<TelemetryObservation>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(snapshot(deviceId))
            delay(30_000L)
        }
    }

    override suspend fun history(deviceId: String, fromMillis: Long, untilMillis: Long): List<TelemetryObservation> {
        require(untilMillis >= fromMillis)
        return emptyList()
    }

    override suspend fun disconnect() {
        mutex.withLock { jwt = ""; cached = null; lastFetch = Long.MIN_VALUE }
    }

    internal companion object {
        fun parseDevice(device: JSONObject, receipt: Long): List<TelemetryObservation> {
            val updated = device.optLong("updated_at", 0L)
            val observed = updated.takeIf { it > 0 && it < Long.MAX_VALUE / 1000L }?.times(1000L)
            val stale = observed == null || observed > receipt + 300_000L || receipt - observed > 180_000L
            val quality = if (stale) TelemetryQuality.STALE else TelemetryQuality.VALID
            val temperatures = device.optJSONObject("temperature") ?: JSONObject()
            val cook = device.optJSONObject("cook")
            val target = cook?.optJSONObject("temperature")
            fun reading(id: String, value: Double): TelemetryObservation {
                return TelemetryObservation(id, value.takeIf { it.isFinite() },
                    "°C", observed, receipt, quality, eventId = observed?.toString())
            }
            return listOf(
                reading("internal", temperatures.optDouble("internal", Double.NaN)),
                reading("ambient", temperatures.optDouble("ambient", Double.NaN)),
                reading("target", target?.optDouble("target", Double.NaN) ?: Double.NaN),
            )
        }
    }
}
