package com.pittech.devices

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.math.floor

/** Only explicit, user-owned controller IDs are used; never guessed from a grill model. */
internal class PitBossBackend(private val sockets: GrillSocketFactory = OkHttpGrillSocketFactory()) : PolarisBackend {
    private val mutex = Mutex()
    @Volatile private var socket: GrillSocket? = null
    private var socketId: String? = null
    private var requestId = 0
    private var appId = UUID.randomUUID().toString()
    override suspend fun requestCode(email: String): PolarisResult<Unit> = error("Enter the controller ID and optional controller password.")
    override suspend fun signIn(email: String, code: String): PolarisResult<PolarisSession> {
        val id = PitBossTelemetry.controllerId(email)
        require(code.length <= 256) { "The controller password is too long." }
        disconnect()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("controller", id)
            .put("password", code).toString().toByteArray(Charsets.UTF_8))
        val session = PolarisSession(token, selectedDeviceId = "pitboss:" + id)
        // Prove that this controller responds and that its read-only state/password works.
        readings(session, "pitboss:" + id)
        return PolarisResult(session, null, null)
    }
    override suspend fun devices(session: PolarisSession): PolarisResult<List<PolarisDevice>> {
        val id = configuration(session).getString("controller")
        return PolarisResult(listOf(PolarisDevice("pitboss:" + id, "Dansons " + id.substringBefore('-') + " controller",
            if (id.startsWith("L")) "Louisiana Grills" else "Pit Boss", null, id.substringBefore('-'), null,
            PitBossTelemetry.probeCount(id))), null, null)
    }
    override suspend fun status(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
        requireSelection(session, deviceId)
        return PolarisResult(PolarisPayload(emptyMap(), emptyList(), 0, null), null, null)
    }
    override suspend fun readings(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> = mutex.withLock {
        val config = configuration(session)
        requireSelection(session, deviceId)
        val id = config.getString("controller")
        try {
            if (socket == null || socketId != id) {
                disconnect()
                appId = UUID.randomUUID().toString().substringAfterLast('-')
                socket = sockets.open("https://socket.dansonscorp.com/to/".toHttpUrl().newBuilder().addPathSegment(id).build().toString().replaceFirst("https:", "wss:"), null)
                socket!!.awaitOpen()
                socketId = id
            }
            val password = config.optString("password")
            val params = JSONObject()
            if (password.isNotEmpty()) {
                val time = rpc(PitBossRead.TIME).opt("time") as? Number ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
                params.put("psw", PitBossPassword.encode(password, time.toDouble()))
            }
            val result = rpc(PitBossRead.STATE, params)
            PolarisResult(PitBossTelemetry.parse(id, result), null, null)
        } catch (_: TimeoutCancellationException) { disconnect(); throw PolarisFailure(PolarisFailureKind.NETWORK) }
        catch (error: CancellationException) { disconnect(); throw error }
        catch (error: Exception) { disconnect(); throw (error as? PolarisFailure ?: PolarisFailure(PolarisFailureKind.NETWORK)) }
    }
    private suspend fun rpc(read: PitBossRead, params: JSONObject = JSONObject()): JSONObject {
        val current = socket ?: throw PolarisFailure(PolarisFailureKind.NETWORK)
        val id = ++requestId
        current.text(JSONObject().put("id", id).put("method", read.method).put("params", params).put("app_id", appId).toString())
        return withTimeout(15_000) {
            while (true) {
                val frame = current.receive() as? GrillSocketFrame.Text ?: continue
                val response = runCatching { JSONObject(frame.value) }.getOrNull() ?: continue
                if (response.opt("id") != id) continue
                if (response.has("app_id") && response.optString("app_id") != appId) continue
                if (response.has("src") && !response.optString("src").equals(socketId, ignoreCase = true)) continue
                val error = response.optJSONObject("error")
                if (error != null) throw PolarisFailure(if (error.optInt("code") == 401) PolarisFailureKind.AUTH else PolarisFailureKind.API, apiCode = error.optInt("code"))
                return@withTimeout response.optJSONObject("result") ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
    }
    private fun requireSelection(session: PolarisSession, deviceId: String) {
        if (deviceId != "pitboss:" + configuration(session).getString("controller")) throw PolarisFailure(PolarisFailureKind.AUTH)
    }
    private fun configuration(session: PolarisSession): JSONObject = try {
        val config = JSONObject(String(Base64.getUrlDecoder().decode(session.token), Charsets.UTF_8))
        PitBossTelemetry.controllerId(config.getString("controller"))
        require(config.optString("password").length <= 256)
        config
    } catch (_: Exception) { throw PolarisFailure(PolarisFailureKind.STORAGE) }
    override fun disconnect() { socket?.close(); socket = null; socketId = null }
}
internal enum class PitBossRead(val method: String) { STATE("PB.GetState"), TIME("PB.GetTime") }

/** Compatibility codec for the user's own controller password, based on pytboss (Apache 2.0).
 * This encoding is vendor protocol compatibility, not secure storage; Android Keystore stores it.
 */
internal object PitBossPassword {
    fun encode(password: String, uptime: Double): String {
        require(uptime.isFinite() && uptime in 0.0..315_360_000.0)
        var n = floor((uptime - 5).coerceAtLeast(0.0) / 10).toLong()
        val source = mutableListOf(0x8f, 0x80, 0x19, 0xcf, 0x77, 0x6c, 0xfe, 0xb7)
        val key = mutableListOf<Int>()
        while (source.size > 1) {
            val v = source.removeAt((n % source.size).toInt())
            key += ((v.toLong() xor n) and 255).toInt()
            n = (n * v + v) and 255
        }
        key += source.single()
        val random = SecureRandom()
        val bytes = ByteArray(16) { random.nextInt(255).toByte() } + byteArrayOf(0xff.toByte()) + password.toByteArray(Charsets.UTF_8)
        return bytes.mapIndexed { i, value ->
            val encoded = (value.toInt() xor key[i % key.size]) and 255
            val next = (i + 1) % key.size
            key[next] = ((key[next] xor encoded) + i) and 255
            "%02x".format(encoded)
        }.joinToString("")
    }
}

