package com.pittech.devices

import org.junit.Assert.*
import org.junit.Test

class GrillMqttTest {
    @Test fun fragmentedAndCoalescedPacketsPreserveTopicPayloadAndQosAcknowledgement() {
        val report = mqttReport("prod/thing/update/owned", """{"status":{"grill":225}}""", 513)
        val bytes = serverMqtt(0x20, byteArrayOf(0, 0)) + report + serverMqtt(0xD0)
        val decoder = GrillMqttDecoder()
        assertTrue(decoder.feed(bytes.copyOfRange(0, 1)).isEmpty())
        val first = decoder.feed(bytes.copyOfRange(1, 5))
        assertEquals(0x20, first.single().header)
        val rest = decoder.feed(bytes.copyOfRange(5, bytes.size))
        assertEquals(listOf(0x32, 0xD0), rest.map { it.header })
        val observation = GrillMqtt.observation(rest.first())
        assertEquals("prod/thing/update/owned", observation.topic)
        assertEquals("""{"status":{"grill":225}}""", observation.json)
        assertEquals(513, observation.packetId)
        assertArrayEquals(byteArrayOf(0x40, 2, 2, 1), GrillMqtt.acknowledge(observation.packetId!!))
    }
    @Test fun subscriberOutboundPacketsOnlyConnectSubscribeAckAndPing() {
        assertEquals(0x10, GrillMqtt.connect("pittech-test")[0].toInt())
        assertEquals(0x82, GrillMqtt.subscribe("prod/thing/update/owned")[0].toInt() and 255)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0), GrillMqtt.ping())
        val connect = GrillMqtt.connect("pittech-test")
        assertEquals(2, connect[9].toInt()) // clean session; no username/password/will flags
    }
    @Test fun malformedLengthsUnsupportedQosAndCorruptUtf8FailClosed() {
        schema { GrillMqttDecoder().feed(byteArrayOf(0x30, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())) }
        schema { GrillMqttDecoder().feed(serverMqtt(0x36, byteArrayOf(0, 1, 65, 0, 1, 123, 125))) }
        schema { GrillMqtt.observation(MqttPacket(0x32, byteArrayOf(0, 1, 65, 0, 0, 123, 125))) }
        schema { GrillMqtt.observation(MqttPacket(0x30, byteArrayOf(0, 1, 0xff.toByte(), 123, 125))) }
        schema { GrillMqtt.observation(MqttPacket(0x30, byteArrayOf(0, 1, 35, 123, 125))) }
        schema { GrillMqttDecoder().feed(serverMqtt(0xD0, byteArrayOf(1))) }
        schema { GrillMqttDecoder().feed(ByteArray(512 * 1024 + 1)) }
    }
    @Test fun qosZeroRequiresNoAckAndRemainingLengthSupportsLargePayloads() {
        val json = """{"padding":"""" + "x".repeat(500) + "\"}"
        val packet = GrillMqttDecoder().feed(serverMqtt(0x30, byteArrayOf(0, 1, 65) + json.toByteArray())).single()
        assertNull(GrillMqtt.observation(packet).packetId)
        assertEquals(json, GrillMqtt.observation(packet).json)
    }
    private fun schema(block: () -> Unit) {
        try { block(); fail("Malformed MQTT must be rejected") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.SCHEMA, error.kind) }
    }
}

