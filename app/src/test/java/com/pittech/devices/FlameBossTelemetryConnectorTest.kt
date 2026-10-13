package com.pittech.devices

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FlameBossTelemetryConnectorTest {
    private val device = "8838"
    private val topic = "flameboss/8838/send/open"
    private val payload = """{"name":"temps","cook_id":1892487,"sec":1621028684,"set_temp":1056,"temps":[298,249,-32767,-32767],"blower":10000}"""

    @Test fun manufacturerExampleHasCorrectCelsiusTenthsAndSentinelHandling() {
        val samples = FlameBossTemperatureFrame.parse(device, topic, payload, 1621028685000)!!
        assertEquals(5, samples.size)
        assertEquals(29.8, samples[0].value!!, .01)
        assertEquals(24.9, samples[1].value!!, .01)
        assertEquals(TelemetryQuality.UNAVAILABLE, samples[2].quality)
        assertNull(samples[2].value)
        assertEquals(105.6, samples.last().value!!, .01)
        assertEquals(TelemetryKind.FOOD.storageType, TelemetryKind.FOOD.storageType)
        assertEquals(1621028684000L, samples[0].observedAtMillis)
        assertEquals(85.64, TelemetryNormalizer.normalize(samples[0])!!.fahrenheit, .01)
    }

    @Test fun rejectsOtherDeviceTopicsAndNonTemperatureFrames() {
        assertNull(FlameBossTemperatureFrame.parse("8838", "flameboss/9999/send/open", payload, 1621028685000))
        assertNull(FlameBossTemperatureFrame.parse("8838", topic, """{"name":"control","sec":1621028684}""", 1621028685000))
        assertNull(FlameBossTemperatureFrame.parse("8838", topic, "not-json", 1621028685000))
    }

    @Test fun onlySelectedDeviceIsObservedWithoutHistoryFabrication() = runBlocking {
        val connector = FlameBossTelemetryConnector(device, flowOf(topic to payload)) { 1621028685000 }
        assertEquals(1, connector.discover().size)
        assertEquals(TelemetryKind.CHAMBER, connector.discover().single().channels.first().kind)
        assertEquals(TelemetryKind.FOOD, connector.discover().single().channels[1].kind)
        assertEquals(1, connector.observe(device).toList().size)
        assertEquals(5, connector.snapshot(device).size)
        assertTrue(connector.history(device, 0, 1000).isEmpty())
        connector.disconnect()
        assertTrue(connector.snapshot(device).isEmpty())
    }
}
