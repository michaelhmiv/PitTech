package com.pittech.devices

import java.io.ByteArrayOutputStream

/** Minimal MQTT 3.1.1 subscriber. It cannot publish application data. */
internal data class MqttPacket(val header: Int, val body: ByteArray)
internal data class MqttObservation(val topic: String, val json: String, val packetId: Int?)
internal object GrillMqtt {
    private fun string(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..65535 && value.none { it == '\u0000' })
        return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    }
    private fun packet(header: Int, body: ByteArray = byteArrayOf()): ByteArray {
        require(body.size <= 256 * 1024)
        var left = body.size
        val out = ByteArrayOutputStream()
        out.write(header)
        do {
            var next = left % 128
            left /= 128
            if (left > 0) next = next or 128
            out.write(next)
        } while (left > 0)
        out.write(body)
        return out.toByteArray()
    }
    fun connect(clientId: String) = packet(0x10, string("MQTT") + byteArrayOf(4, 2, 0, 60) + string(clientId))
    fun subscribe(topic: String) = packet(0x82, byteArrayOf(0, 1) + string(topic) + byteArrayOf(1))
    fun acknowledge(id: Int) = packet(0x40, byteArrayOf((id shr 8).toByte(), id.toByte()))
    fun ping() = packet(0xC0)
    fun observation(packet: MqttPacket): MqttObservation {
        if (packet.header shr 4 != 3) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val qos = (packet.header shr 1) and 3
        if (qos !in 0..1 || packet.body.size < 2) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        fun unsigned(i: Int) = packet.body[i].toInt() and 255
        val topicLength = (unsigned(0) shl 8) + unsigned(1)
        val start = 2 + topicLength
        if (topicLength !in 1..1024 || start + (if (qos == 1) 2 else 0) >= packet.body.size) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val topicBytes = packet.body.copyOfRange(2, start)
        val topic = topicBytes.toString(Charsets.UTF_8)
        if (!topic.toByteArray(Charsets.UTF_8).contentEquals(topicBytes) || topic.any { it == '\u0000' || it == '#' || it == '+' }) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val id = if (qos == 1) (unsigned(start) shl 8) + unsigned(start + 1) else null
        if (id == 0) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        val bytes = packet.body.copyOfRange(start + (if (qos == 1) 2 else 0), packet.body.size)
        val json = bytes.toString(Charsets.UTF_8)
        if (!json.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        return MqttObservation(topic, json, id)
    }
}
/** One WebSocket message can carry partial or several MQTT packets. */
internal class GrillMqttDecoder {
    private var buffer = byteArrayOf()
    fun feed(bytes: ByteArray): List<MqttPacket> {
        if (buffer.size + bytes.size > 512 * 1024) throw PolarisFailure(PolarisFailureKind.SCHEMA)
        buffer += bytes
        val result = mutableListOf<MqttPacket>()
        while (buffer.size >= 2) {
            var length = 0
            var multiplier = 1
            var index = 1
            var complete = false
            while (index < buffer.size) {
                val digit = buffer[index++].toInt() and 255
                length += (digit and 127) * multiplier
                if (length > 256 * 1024) throw PolarisFailure(PolarisFailureKind.SCHEMA)
                if (digit and 128 == 0) { complete = true; break }
                if (index > 4) throw PolarisFailure(PolarisFailureKind.SCHEMA)
                multiplier *= 128
            }
            if (!complete || buffer.size < index + length) break
            val header = buffer[0].toInt() and 255
            val publish = header shr 4 == 3 && ((header shr 1) and 3) in 0..1
            if (!publish && header !in listOf(0x20, 0x90, 0xD0, 0xE0) || header in listOf(0xD0, 0xE0) && length != 0)
                throw PolarisFailure(PolarisFailureKind.SCHEMA)
            if (result.size >= 1024) throw PolarisFailure(PolarisFailureKind.SCHEMA)
            result += MqttPacket(header, buffer.copyOfRange(index, index + length))
            buffer = buffer.copyOfRange(index + length, buffer.size)
        }
        return result
    }
}

