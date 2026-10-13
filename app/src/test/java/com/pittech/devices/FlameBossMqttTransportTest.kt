package com.pittech.devices
import org.junit.Assert.*
import org.junit.Test

class FlameBossMqttTransportTest {
    @Test fun brokerMustBeVendorTlsWithExplicitPort() {
        assertEquals(8883, FlameBossBroker.parse("mqtts://myflameboss.com:8883").port)
        for (url in listOf("mqtt://myflameboss.com:1883", "mqtts://myflameboss.com.evil.test:8883", "mqtts://user:pass@myflameboss.com:8883", "mqtts://myflameboss.com")) {
            try { FlameBossBroker.parse(url); fail(url) } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun authenticatedConnectAndExactDeviceSubscriptionUseNoPublish() {
        val connect = FlameBossMqttCodec.read(FlameBossMqttCodec.connect("T-123", "test-token", "test-client").inputStream())
        assertEquals(0x10, connect.header)
        assertEquals(0xC2, connect.body[7].toInt() and 255)
        assertTrue(connect.body.toString(Charsets.UTF_8).contains("T-123"))
        val subscribe = FlameBossMqttCodec.read(FlameBossMqttCodec.subscribe("123").inputStream())
        assertEquals(0x82, subscribe.header)
        assertTrue(subscribe.body.toString(Charsets.UTF_8).contains("flameboss/123/send/open"))
        try { FlameBossMqttCodec.subscribe("123/receive/#"); fail("Arbitrary topic allowed") } catch (_: IllegalArgumentException) {}
    }
    @Test fun malformedLengthsAreBounded() {
        try { FlameBossMqttCodec.read(byteArrayOf(0x30, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()).inputStream()); fail("Unbounded MQTT packet") } catch (_: IllegalArgumentException) {}
    }
}
