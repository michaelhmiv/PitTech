package com.pittech.devices

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Manufacturer-confirmed configuration is required; there is no guessed default broker. */
internal data class FlameBossBroker(val host: String, val port: Int) {
    companion object {
        fun parse(raw: String): FlameBossBroker {
            val uri = URI(raw)
            val host = uri.host?.lowercase() ?: throw TelemetryRequestException(TelemetryFailureKind.CONFIGURATION)
            require(uri.scheme == "mqtts" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                uri.path.isNullOrEmpty()) { "Use the TLS broker URL supplied by Flame Boss." }
            require(host == "myflameboss.com" || host.endsWith(".myflameboss.com")) { "Credentials can only be sent to a Flame Boss broker." }
            require(uri.port in 1..65535) { "Enter the manufacturer-confirmed TLS port." }
            return FlameBossBroker(host, uri.port)
        }
    }
}

/** MQTT 3.1.1, subscription-only. CONNECT, SUBSCRIBE, PUBACK and PINGREQ are the only outgoing packets. */
internal object FlameBossMqttCodec {
    class IdleTimeout : java.net.SocketTimeoutException()
    data class Packet(val header: Int, val body: ByteArray)
    private fun string(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size in 1..65535 && '\u0000' !in s)
        return byteArrayOf((b.size ushr 8).toByte(), b.size.toByte()) + b
    }
    private fun packet(header: Int, body: ByteArray): ByteArray {
        require(body.size <= 65536)
        val out = ByteArrayOutputStream(); out.write(header)
        var remaining = body.size
        do { val n = remaining % 128; remaining /= 128; out.write(n or if (remaining > 0) 128 else 0) } while (remaining > 0)
        out.write(body); return out.toByteArray()
    }
    fun connect(username: String, token: String, clientId: String) = packet(0x10,
        string("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 60) + string(clientId) + string(username) + string(token))
    fun subscribe(deviceId: String): ByteArray {
        require(deviceId.matches(Regex("[0-9]{1,16}")))
        return packet(0x82, byteArrayOf(0, 1) + string("flameboss/" + deviceId + "/send/open") + byteArrayOf(1))
    }
    fun ack(id: Int) = packet(0x40, byteArrayOf((id ushr 8).toByte(), id.toByte()))
    fun ping() = byteArrayOf(0xC0.toByte(), 0)
    fun read(input: InputStream): Packet {
        // Only an idle timeout before the header can safely send PING and retry.
        // A timeout inside a packet must reconnect, otherwise framing is lost.
        val header = try { input.read() } catch (_: java.net.SocketTimeoutException) { throw IdleTimeout() }
        require(header >= 0) { "MQTT connection closed" }
        var length = 0; var multiplier = 1; var digits = 0
        do {
            val b = input.read(); require(b >= 0 && digits++ < 4)
            length += (b and 127) * multiplier; require(length <= 65536)
            multiplier *= 128
            if (b and 128 == 0) break
        } while (true)
        val body = ByteArray(length); var at = 0
        while (at < length) { val n = input.read(body, at, length - at); require(n > 0); at += n }
        return Packet(header, body)
    }
    fun observation(p: Packet): Triple<String, String, Int?>? {
        if (p.header ushr 4 != 3) return null
        val qos = (p.header ushr 1) and 3; require(qos in 0..1 && p.body.size >= 2)
        fun u(i: Int) = p.body[i].toInt() and 255
        val size = (u(0) shl 8) + u(1); var offset = size + 2
        require(size in 1..512 && offset + (if (qos == 1) 2 else 0) < p.body.size)
        val topic = p.body.copyOfRange(2, offset).toString(Charsets.UTF_8)
        require(topic.none { it == '\u0000' || it == '+' || it == '#' })
        val id = if (qos == 1) ((u(offset) shl 8) + u(offset + 1)).also { offset += 2; require(it > 0) } else null
        return Triple(topic, p.body.copyOfRange(offset, p.body.size).toString(Charsets.UTF_8), id)
    }
}

internal class FlameBossMqttTransport(private val broker: FlameBossBroker, private val session: FlameBossSessionApi.Session, private val deviceId: String) {
    @Volatile private var closed = false
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<SSLSocket>()
    fun frames(): Flow<Pair<String, String>> = callbackFlow {
        if (closed) { close(); return@callbackFlow }
        val socket = AtomicReference<SSLSocket?>()
        fun closeOwnedSocket() { socket.getAndSet(null)?.let { sockets.remove(it); runCatching { it.close() } } }
        val worker = launch(Dispatchers.IO) {
            try {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
                socket.set(tls)
                sockets.add(tls)
                check(!closed) { "Connection removed" }
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.connect(InetSocketAddress(broker.host, broker.port), 20_000); tls.soTimeout = 30_000
                tls.startHandshake()
                val input = tls.inputStream; val output = tls.outputStream
                fun send(bytes: ByteArray) { output.write(bytes); output.flush() }
                send(FlameBossMqttCodec.connect(session.mqttUsername, session.token, "pittech-" + UUID.randomUUID()))
                val ack = FlameBossMqttCodec.read(input)
                require(ack.header == 0x20 && ack.body.size == 2)
                if (ack.body[1].toInt() != 0) throw TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
                send(FlameBossMqttCodec.subscribe(deviceId))
                var subscribed = false
                while (isActive) {
                    val p = try { FlameBossMqttCodec.read(input) } catch (_: FlameBossMqttCodec.IdleTimeout) {
                        send(FlameBossMqttCodec.ping()); continue
                    }
                    if (p.header == 0x90) {
                        require(p.body.size == 3 && p.body[0].toInt() == 0 && p.body[1].toInt() == 1)
                        if (p.body[2].toInt() !in 0..1) throw TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
                        subscribed = true
                    }
                    // Brokers may deliver a retained PUBLISH before SUBACK. Do not discard it.
                    FlameBossMqttCodec.observation(p)?.let { (topic, json, packetId) ->
                        if (topic == "flameboss/" + deviceId + "/send/open") trySend(topic to json)
                        packetId?.let { send(FlameBossMqttCodec.ack(it)) }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { close(error) }
            finally { closeOwnedSocket() }
        }
        awaitClose { closeOwnedSocket(); worker.cancel() }
    }
    fun close() { closed = true; sockets.toList().forEach { runCatching { it.close() } }; sockets.clear() }
}
