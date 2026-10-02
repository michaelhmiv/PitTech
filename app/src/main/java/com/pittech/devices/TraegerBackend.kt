package com.pittech.devices

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Independently implemented from the community's documented WiFIRE wire protocol.
 * Only account reads, MQTT subscription, token refresh, and literal status request 90 exist.
 */
internal class TraegerBackend(
    private val http: TraegerHttpTransport = OkHttpTraegerTransport(),
    private val sockets: GrillSocketFactory = OkHttpGrillSocketFactory(),
    private val now: () -> Long = System::currentTimeMillis,
) : PolarisBackend {
    override val hasSeparateStatusRead = false
    private val mutex = Mutex()
    @Volatile private var socket: GrillSocket? = null
    private var socketDevice: String? = null
    private var socketToken: String? = null
    private var socketExpires = 0L
    private val receiveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: Job? = null
    private var updates: Channel<PolarisPayload>? = null
    private var owned = emptySet<String>()

    override suspend fun requestCode(email: String): PolarisResult<Unit> = error("Use your Traeger email and password.")
    override suspend fun signIn(email: String, code: String): PolarisResult<PolarisSession> {
        require(PrimePolarisApi.validEmail(email) && code.isNotEmpty() && code.length <= 1024) { "Enter your Traeger email and password." }
        val data = json(TraegerHttpOperation.LOGIN, JSONObject().put("username", email.trim()).put("password", code).toString())
        return PolarisResult(parseSession(data.value, null), data.httpStatus, null)
    }
    override suspend fun refreshSession(session: PolarisSession): PolarisResult<PolarisSession> {
        val refresh = session.refreshToken ?: throw PolarisFailure(PolarisFailureKind.AUTH)
        val claims = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(session.token.split('.')[1]), Charsets.UTF_8)) }
            .getOrElse { throw PolarisFailure(PolarisFailureKind.AUTH) }
        val issuer = claims.optString("iss")
        val audience = claims.optString("aud")
        if (!issuer.startsWith("https://cognito-idp.us-west-2.amazonaws.com/us-west-2_") || !Regex("[A-Za-z0-9]{1,128}").matches(audience))
            throw PolarisFailure(PolarisFailureKind.AUTH)
        val data = json(TraegerHttpOperation.REFRESH,
            JSONObject().put("AuthFlow", "REFRESH_TOKEN_AUTH").put("ClientId", audience)
                .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", refresh)).toString())
        val result = data.value.optJSONObject("AuthenticationResult") ?: throw PolarisFailure(PolarisFailureKind.AUTH)
        val renewed = parseSession(JSONObject().put("idToken", result.opt("IdToken")).put("expiresIn", result.opt("ExpiresIn"))
            .put("refreshToken", result.opt("RefreshToken") ?: refresh), session.selectedDeviceId)
        disconnect()
        return PolarisResult(renewed, data.httpStatus, null)
    }
    private fun parseSession(data: JSONObject, selected: String?): PolarisSession {
        val token = data.opt("idToken") as? String ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val seconds = number(data.opt("expiresIn"))?.takeIf { it % 1.0 == 0.0 && it in 60.0..604800.0 }
            ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
        if (token.isBlank() || token.length > 16384 || token.any(Char::isWhitespace)) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val refresh = (data.opt("refreshToken") as? String)?.takeIf { it.isNotBlank() }
        if (refresh != null && (refresh.length > 16384 || refresh.any(Char::isWhitespace))) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        return PolarisSession(token, now() + seconds.toLong() * 1000, selected, refresh)
    }
    override suspend fun devices(session: PolarisSession): PolarisResult<List<PolarisDevice>> {
        val data = json(TraegerHttpOperation.DEVICES, token = session.token)
        val things = data.value.optJSONArray("things") ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
        if (things.length() > 100) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val devices = (0 until things.length()).mapNotNull { i ->
            val item = things.optJSONObject(i) ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
            if (item.has("status") && item.optString("status") != "CONFIRMED") return@mapNotNull null
            val thing = item.optString("thingName")
            if (!Regex("[A-Za-z0-9_-]{1,160}").matches(thing)) throw PolarisFailure(PolarisFailureKind.SCHEMA)
            PolarisDevice("traeger:" + thing, item.optString("friendlyName").take(100).ifBlank { "Traeger grill " + (i + 1) },
                "Traeger", item.opt("deviceTypeId")?.toString()?.take(100), "WiFIRE", null, 4)
        }.distinctBy { it.id }
        owned = devices.map { it.id }.toSet()
        return PolarisResult(devices, data.httpStatus, null)
    }
    override suspend fun status(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> {
        requireOwned(deviceId)
        // MQTT observations report connected themselves. A successful API call never implies grill online.
        return PolarisResult(PolarisPayload(emptyMap(), emptyList(), 0, null), null, null, observedRemote = false)
    }
    override suspend fun readings(session: PolarisSession, deviceId: String): PolarisResult<PolarisPayload> = mutex.withLock {
        requireOwned(deviceId)
        try {
            ensureSocket(session, deviceId)
            val current = socket ?: throw PolarisFailure(PolarisFailureKind.NETWORK)
            val incoming = updates ?: throw PolarisFailure(PolarisFailureKind.NETWORK)
            // Discard any observation already used by an earlier poll. The receiver continuously
            // drains and acknowledges MQTT, retaining only the newest matching grill observation.
            incoming.tryReceive()
            current.binary(GrillMqtt.ping())
            json(TraegerHttpOperation.STATUS, token = session.token, thing = deviceId.removePrefix("traeger:"))
            PolarisResult(withTimeout(20_000) { incoming.receive() }, null, null)
        } catch (_: TimeoutCancellationException) { disconnect(); throw PolarisFailure(PolarisFailureKind.NETWORK) }
        catch (error: CancellationException) { disconnect(); throw error }
        catch (error: Exception) { disconnect(); throw (error as? PolarisFailure ?: PolarisFailure(PolarisFailureKind.NETWORK)) }
    }

    private suspend fun ensureSocket(session: PolarisSession, deviceId: String) {
        if (socket != null && socketDevice == deviceId && socketToken == session.token && now() < socketExpires - 60_000) return
        disconnect()
        val response = json(TraegerHttpOperation.MQTT_CONNECTION, token = session.token)
        val url = response.value.optString("signedUrl")
        if (!validSignedUrl(url)) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val seconds = number(response.value.opt("expirationSeconds"))?.takeIf { it in 61.0..86400.0 } ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val current = sockets.open(url, "mqtt")
        socket = current
        current.awaitOpen()
        val stream = MqttStream()
        current.binary(GrillMqtt.connect("pittech-" + UUID.randomUUID()))
        val ack = withTimeout(12_000) { stream.next(current) }
        if (ack.header != 0x20 || ack.body.size != 2 || ack.body[0].toInt() !in 0..1 || ack.body[1].toInt() != 0)
            throw PolarisFailure(if (ack.body.lastOrNull()?.toInt() in listOf(4, 5)) PolarisFailureKind.AUTH else PolarisFailureKind.SCHEMA)
        current.binary(GrillMqtt.subscribe("prod/thing/update/" + deviceId.removePrefix("traeger:")))
        withTimeout(12_000) {
            while (true) {
                val packet = stream.next(current)
                if (packet.header == 0x90) {
                    if (packet.body.size != 3 || packet.body[0].toInt() != 0 || packet.body[1].toInt() != 1 || packet.body[2].toInt() !in 0..1)
                        throw PolarisFailure(PolarisFailureKind.AUTH)
                    break
                } else if (packet.header shr 4 == 3) {
                    GrillMqtt.observation(packet).packetId?.let { current.binary(GrillMqtt.acknowledge(it)) }
                }
            }
        }
        socketDevice = deviceId
        socketToken = session.token
        socketExpires = now() + seconds.toLong() * 1000
        val incoming = Channel<PolarisPayload>(Channel.CONFLATED)
        updates = incoming
        val thing = deviceId.removePrefix("traeger:")
        receiver = receiveScope.launch {
            try {
                while (true) {
                    val packet = stream.next(current)
                    if (packet.header shr 4 != 3) continue
                    val observation = GrillMqtt.observation(packet)
                    observation.packetId?.let { current.binary(GrillMqtt.acknowledge(it)) }
                    if (observation.topic != "prod/thing/update/" + thing) continue
                    val envelope = try { JSONObject(observation.json) } catch (_: Exception) { throw PolarisFailure(PolarisFailureKind.SCHEMA) }
                    if (envelope.optString("thingName") != thing) continue
                    incoming.trySend(parseStatus(envelope))
                }
            } catch (error: CancellationException) { incoming.cancel(); throw error }
            catch (error: Exception) { incoming.close(error as? PolarisFailure ?: PolarisFailure(PolarisFailureKind.NETWORK)); current.close() }
        }
    }
    private class MqttStream {
        private val decoder = GrillMqttDecoder()
        private val packets = ArrayDeque<MqttPacket>()
        suspend fun next(current: GrillSocket): MqttPacket {
            while (packets.isEmpty()) {
                val frame = current.receive() as? GrillSocketFrame.Binary ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
                packets.addAll(decoder.feed(frame.value))
            }
            val packet = packets.removeFirst()
            if (packet.header == 0xE0) throw PolarisFailure(PolarisFailureKind.NETWORK)
            return packet
        }
    }
    private fun requireOwned(deviceId: String) {
        if (deviceId !in owned) throw PolarisFailure(PolarisFailureKind.AUTH)
    }
    private suspend fun json(operation: TraegerHttpOperation, body: String? = null, token: String? = null, thing: String? = null): PolarisResult<JSONObject> {
        val response = http.request(operation, body, token, thing)
        if (response.status == 401 || response.status == 403 || operation in listOf(TraegerHttpOperation.LOGIN, TraegerHttpOperation.REFRESH) && response.status == 400)
            throw PolarisFailure(PolarisFailureKind.AUTH, response.status)
        if (response.status == 429) throw PolarisFailure(PolarisFailureKind.RATE_LIMIT, 429, retryAfterMillis = response.retryAfterMillis)
        if (response.status !in 200..299) throw PolarisFailure(PolarisFailureKind.HTTP, response.status)
        return try { PolarisResult(JSONObject(response.body.ifBlank { "{}" }), response.status, null) } catch (_: Exception) { throw PolarisFailure(PolarisFailureKind.SCHEMA, response.status) }
    }
    override fun disconnect() {
        receiver?.cancel()
        receiver = null
        updates?.cancel()
        updates = null
        socket?.close()
        socket = null
        socketDevice = null
        socketToken = null
        socketExpires = 0
    }
    companion object {
        fun validSignedUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.length <= 16384 && uri.scheme == "wss" && uri.userInfo == null && uri.fragment == null &&
                uri.port in listOf(-1, 443) && uri.host?.endsWith(".iot.us-west-2.amazonaws.com") == true &&
                uri.path == "/mqtt" && !uri.rawQuery.isNullOrBlank()
        }.getOrDefault(false)
        private fun number(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf(Double::isFinite)
        fun parseStatus(envelope: JSONObject): PolarisPayload {
            val status = envelope.optJSONObject("status") ?: throw PolarisFailure(PolarisFailureKind.SCHEMA)
            val raw = mutableMapOf<String, Double>()
            val unit = number(status.opt("units")) ?: number(envelope.optJSONObject("settings")?.opt("units"))
            if (unit == 0.0 || unit == 1.0) raw["tempUnit"] = if (unit == 1.0) 0.0 else 1.0
            fun temperature(key: String, field: Any?) { number(field)?.takeIf { it in -100.0..1000.0 }?.let { raw[key] = it } }
            temperature("furnaceTempMeasured", status.opt("grill"))
            temperature("furnaceTempSetting", status.opt("set"))
            val connected = status.opt("connected") as? Boolean
            val system = number(status.opt("system_status"))
            if (connected != null || system == 99.0) raw["onlineStatus"] = if (connected == true && system != 99.0) 0.0 else 1.0
            system?.let { raw["vendorSystemStatus"] = it }
            number(status.opt("pellet_level"))?.takeIf { it in 0.0..100.0 }?.let { raw["pelletLevel"] = it }
            val accessories = status.optJSONArray("acc")
            val seen = mutableSetOf<Int>()
            if (accessories != null) {
                if (accessories.length() > 32) throw PolarisFailure(PolarisFailureKind.SCHEMA)
                for (i in 0 until accessories.length()) {
                    val accessory = accessories.optJSONObject(i) ?: continue
                    if (accessory.optString("type") != "probe") continue
                    val channel = accessory.optString("channel").takeIf { Regex("p[0-3]").matches(it) }?.drop(1)?.toInt()?.plus(1) ?: continue
                    if (!seen.add(channel)) throw PolarisFailure(PolarisFailureKind.SCHEMA)
                    if (number(accessory.opt("con")) != 1.0) continue
                    val probe = accessory.optJSONObject("probe") ?: continue
                    temperature("probeP" + channel + "Measured", probe.opt("get_temp"))
                    temperature("probeP" + channel + "Setting", probe.opt("set_temp"))
                }
            }
            if (1 !in seen && number(status.opt("probe_con")) == 1.0) {
                temperature("probeP1Measured", status.opt("probe"))
                temperature("probeP1Setting", status.opt("probe_set"))
            }
            val seconds = number(status.opt("time"))?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000.0 }
            return PolarisPayload(raw, raw.keys.sorted(), status.length() - raw.size.coerceAtMost(status.length()), null,
                seconds?.times(1000)?.toLong(), false, 4)
        }
    }
}

