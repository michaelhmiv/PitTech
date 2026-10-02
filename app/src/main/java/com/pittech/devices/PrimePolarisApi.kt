package com.pittech.devices

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface PolarisBackend {
    suspend fun refreshSession(session: PolarisSession): PolarisResult<PolarisSession> = throw PolarisFailure(PolarisFailureKind.AUTH)
    fun disconnect() {}
    suspend fun requestCode(email: String): PolarisResult<Unit>
    suspend fun signIn(email: String, code: String): PolarisResult<PolarisSession>
    suspend fun devices(session: PolarisSession): PolarisResult<List<PolarisDevice>>
    suspend fun status(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload>
    suspend fun readings(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload>
}

internal data class PolarisHttpResponse(val status: Int, val body: String, val retryAfterMillis: Long? = null)
internal fun interface PolarisHttpTransport {
    suspend fun post(operation: PolarisOperation, body: String, token: String?): PolarisHttpResponse
}

internal class OkHttpPolarisTransport : PolarisHttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false) // In particular, never replay email-code or sign-in requests.
        .build()

    override suspend fun post(operation: PolarisOperation, body: String, token: String?): PolarisHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url("https://api.prime-polaris.com/api" + operation.path)
                .header("Accept", "application/json")
                .header("User-Agent", "PitTech-GrillirG-Monitor/1")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(PolarisFailure(PolarisFailureKind.NETWORK))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val stream = it.body.byteStream()
                            val bytes = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val count = stream.read(chunk)
                                if (count < 0) break
                                if (bytes.size() + count > 512 * 1024) throw PolarisFailure(PolarisFailureKind.SCHEMA, it.code)
                                bytes.write(chunk, 0, count)
                            }
                            PolarisHttpResponse(
                                it.code,
                                bytes.toString("UTF-8"),
                                it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 300)?.times(1000),
                            )
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        val failure = error as? PolarisFailure ?: PolarisFailure(PolarisFailureKind.NETWORK)
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
}

/** Independent implementation of the observed vendor REST protocol; no control endpoint. */
internal class PrimePolarisApi(private val transport: PolarisHttpTransport = OkHttpPolarisTransport()) : PolarisBackend {
    override suspend fun requestCode(email: String): PolarisResult<Unit> {
        require(validEmail(email)) { "Enter the email used in GrillirG." }
        val result = request(PolarisOperation.REQUEST_CODE, JSONObject().put("email", email.trim()).put("emailType", "Login"))
        return PolarisResult(Unit, result.httpStatus, result.apiCode)
    }

    override suspend fun signIn(email: String, code: String): PolarisResult<PolarisSession> {
        require(validEmail(email) && Regex("[0-9]{6}").matches(code)) { "Enter your email and the six-digit code." }
        val result = request(PolarisOperation.SIGN_IN, JSONObject().put("email", email.trim()).put("verifyCode", code).put("emailType", "Login"))
        val token = result.value.opt("token") as? String
        if (token.isNullOrBlank() || token.length > 16_384 || token.any { it.isWhitespace() }) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        return PolarisResult(PolarisSession(token, tokenExpiry(token)), result.httpStatus, result.apiCode)
    }

    override suspend fun devices(session: PolarisSession): PolarisResult<List<PolarisDevice>> {
        val result = request(PolarisOperation.DEVICES, JSONObject(), session)
        val list = result.value.optJSONArray("list") ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
        if (list.length() > 100) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val devices = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index) ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
            val id = when (val value = item.opt("id")) {
                is String -> value.takeIf { it.isNotBlank() && it.length <= 160 }
                is Number -> value.toString()
                else -> null
            } ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
            PolarisDevice(id, text(item, "deviceName") ?: "Grill ${index + 1}", text(item, "grillBrand"), text(item, "grillModel"), text(item, "modelName"), text(item, "firmwareVersion"))
        }.distinctBy { it.id }
        return PolarisResult(devices, result.httpStatus, result.apiCode)
    }

    override suspend fun status(session: PolarisSession, deviceId: String) = payload(PolarisOperation.STATUS, session, deviceId)
    override suspend fun readings(session: PolarisSession, deviceId: String) = payload(PolarisOperation.READINGS, session, deviceId)

    private suspend fun payload(operation: PolarisOperation, session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
        val result = request(operation, JSONObject().put("deviceId", deviceId), session)
        return PolarisResult(parsePayload(result.value), result.httpStatus, result.apiCode)
    }

    private suspend fun request(operation: PolarisOperation, body: JSONObject, session: PolarisSession? = null): PolarisResult<JSONObject> {
        if (session?.expired(System.currentTimeMillis()) == true) throw PolarisFailure(PolarisFailureKind.AUTH)
        val response = transport.post(operation, body.toString(), session?.token)
        if (response.status == 401 || response.status == 403) throw PolarisFailure(PolarisFailureKind.AUTH, response.status)
        if (response.status == 429) throw PolarisFailure(PolarisFailureKind.RATE_LIMIT, 429, retryAfterMillis = response.retryAfterMillis)
        if (response.status !in 200..299) throw PolarisFailure(PolarisFailureKind.HTTP, response.status)
        val envelope = try { JSONObject(response.body) } catch (_: Exception) { throw PolarisFailure(PolarisFailureKind.SCHEMA, response.status) }
        val code = number(envelope.opt("respCode"))?.takeIf { it % 1.0 == 0.0 }?.toInt()
            ?: throw PolarisFailure(PolarisFailureKind.SCHEMA, response.status)
        if (code != 10000) {
            throw PolarisFailure(if (code in AUTH_CODES) PolarisFailureKind.AUTH else PolarisFailureKind.API, response.status, code)
        }
        val data = envelope.opt("data")
        val objectData = when {
            data is JSONObject -> data
            (data == null || data == JSONObject.NULL) && operation == PolarisOperation.REQUEST_CODE -> JSONObject()
            else -> throw PolarisFailure(PolarisFailureKind.SCHEMA, response.status, code)
        }
        return PolarisResult(objectData, response.status, code)
    }

    companion object {
        private val AUTH_CODES = setOf(-10001, -10002, -10003, -10007, -10108)
        val NUMERIC_FIELDS = setOf(
            "onlineStatus", "runningStatus", "deviceSwitch", "furnaceTempMeasured", "furnaceTempSetting",
            "probeP1Measured", "probeP1Setting", "probeP1Status", "probeP2Measured", "probeP2Setting", "probeP2Status",
            "tempUnit", "tempScale", "smokeMode", "smokeLevel", "winter", "refTemp", "alarmSwitch",
            "forwardTimingStatus", "forwardTimerValue", "countdownStatus", "countdownSetTimerValue", "countdownTimerValue",
        )
        fun validEmail(email: String) = email.trim().length in 3..254 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email.trim())
        private fun text(data: JSONObject, key: String) = (data.opt(key) as? String)?.take(100)?.takeIf { it.isNotBlank() }
        private fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }

        fun parsePayload(data: JSONObject): PolarisPayload {
            val values = NUMERIC_FIELDS.mapNotNull { key ->
                val value = number(data.opt(key)) ?: return@mapNotNull null
                val isTemperature = key.contains("Temp") || key.startsWith("probe") && !key.endsWith("Status")
                if (isTemperature && value !in -100.0..1000.0) return@mapNotNull null
                if (!isTemperature && (value % 1.0 != 0.0 || value !in -1_000_000.0..1_000_000.0)) return@mapNotNull null
                key to value
            }.toMap()
            val fields = data.keys().asSequence().toList()
            return PolarisPayload(values, fields.filter { it in NUMERIC_FIELDS || it == "alarmEvent" }.sorted(), fields.count { it !in NUMERIC_FIELDS && it != "alarmEvent" }, (data.opt("alarmEvent") as? JSONArray)?.length())
        }

        fun tokenExpiry(token: String): Long? = runCatching {
            val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return@runCatching null
            val expiry = number(JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).opt("exp"))
            expiry?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000.0 }?.times(1000)?.toLong()
        }.getOrNull()
    }
}
