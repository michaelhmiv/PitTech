package com.pittech.devices

import inc.combustion.framework.service.ProbeScanResult
import inc.combustion.framework.service.ProbeTemperatures
import org.junit.Assert.*
import org.junit.Test

class CombustionBleConnectorTest {
    @Test fun sdkAdvertisementMapsEightNativeCelsiusSensorsWithoutPublishingCommands() {
        val scan = ProbeScanResult("CCABC1", "AA:BB:CC:00:11:22", -55,
            ProbeTemperatures((0..7).map { 50.0 + it }), null)
        val source = CombustionBleConnector.source("CCABC1")
        val observations = CombustionBleConnector.decode(scan, 1_760_000_000_000L)
        assertEquals(8, observations.size)
        assertEquals("combustion", source.connectorId)
        assertEquals(9, source.channels.size)
        assertEquals("sensor0", observations.first().channelId)
        assertEquals(50.0, observations.first().value!!, .0001)
        assertEquals("receipt", TelemetryNormalizer.normalize(observations.first())!!.timestampBasis)
        assertEquals(122.0, TelemetryNormalizer.normalize(observations.first())!!.fahrenheit, .01)
        assertFalse(observations.first().toString().contains("AA:BB:CC"))
    }

    @Test fun instantReadIsOptionalAndNeverGuessesMissingSensors() {
        val reading = ProbeScanResult("CCABC1", null, -70, null, 82.3)
        val sample = CombustionBleConnector.decode(reading, 1_760_000_000_000L)
        assertEquals(1, sample.size)
        assertEquals("instant", sample.single().channelId)
        assertEquals(82.3, sample.single().value!!, .01)
    }
}
