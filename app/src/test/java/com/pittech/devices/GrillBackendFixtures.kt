package com.pittech.devices

import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic protocol fixtures: no production accounts, grill IDs, or network requests. */
internal fun serverMqtt(header: Int, body: ByteArray = byteArrayOf()): ByteArray {
    val prefix = mutableListOf(header.toByte())
    var left = body.size
    do {
        var digit = left % 128
        left /= 128
        if (left > 0) digit = digit or 128
        prefix += digit.toByte()
    } while (left > 0)
    return prefix.toByteArray() + body
}
internal fun mqttReport(topic: String, json: String, id: Int = 42): ByteArray {
    val bytes = topic.toByteArray(Charsets.UTF_8)
    return serverMqtt(0x32, byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes +
        byteArrayOf((id shr 8).toByte(), id.toByte()) + json.toByteArray(Charsets.UTF_8))
}
internal class FixtureSocket : GrillSocket {
    val frames = Channel<GrillSocketFrame>(Channel.UNLIMITED)
    val texts = CopyOnWriteArrayList<String>()
    val binaries = CopyOnWriteArrayList<ByteArray>()
    var onText: (String) -> Unit = {}
    var onBinary: (ByteArray) -> Unit = {}
    @Volatile var closed = false
    override suspend fun awaitOpen() { if (closed) throw PolarisFailure(PolarisFailureKind.NETWORK) }
    override suspend fun receive() = frames.receive()
    override fun text(value: String) { texts += value; onText(value) }
    override fun binary(value: ByteArray) { binaries += value; onBinary(value) }
    override fun close() { closed = true; frames.close(PolarisFailure(PolarisFailureKind.NETWORK)) }
    fun textFrame(value: String) { frames.trySend(GrillSocketFrame.Text(value)) }
    fun binaryFrame(value: ByteArray) { frames.trySend(GrillSocketFrame.Binary(value)) }
    fun mqttHandshake() {
        onBinary = { packet ->
            when (packet[0].toInt() and 255) {
                0x10 -> binaryFrame(serverMqtt(0x20, byteArrayOf(0, 0)))
                0x82 -> binaryFrame(serverMqtt(0x90, byteArrayOf(0, 1, 1)))
            }
        }
    }
}
internal fun traegerToken(): String {
    val claims = JSONObject().put("iss", "https://cognito-idp.us-west-2.amazonaws.com/us-west-2_test")
        .put("aud", "publicClient123").toString()
    return "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".signature"
}
internal fun traegerEnvelope(thing: String = "owned_test_grill", time: Long = 1_000_000L): JSONObject = JSONObject()
    .put("thingName", thing).put("status", JSONObject().put("grill", 225).put("set", 250)
        .put("units", 1).put("connected", true).put("system_status", 6).put("time", time / 1000)
        .put("probe_con", 0).put("probe", 960))

