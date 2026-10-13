package com.pittech.devices

import org.junit.Assert.*
import org.junit.Test

class TelemetryContractTest {
    @Test fun validatesSourceChannelUniquenessAndIds() {
        val food = TelemetryChannel("one", "Food", TelemetryKind.FOOD)
        val src = TelemetrySource("home_assistant", "sensor.1", "My grill", channels = listOf(food))
        assertEquals("sensor.1", src.deviceId)
        try { src.copy(channels = listOf(food, food)); fail("duplicate channel") } catch (_: IllegalArgumentException) {}
        try { src.copy(connectorId = "https://remote"); fail("arbitrary connector") } catch (_: IllegalArgumentException) {}
    }

    @Test fun conversionPreservesObservedAndReceiptTimes() {
        val v = TelemetryNormalizer.normalize(TelemetryObservation("one", 100.0, "°C", 10000, 10500))!!
        assertEquals(212.0, v.fahrenheit, 0.0001)
        assertEquals(10000L, v.measuredAtMillis)
        assertEquals(10500L, v.receivedAtMillis)
        assertEquals("source_observed", v.timestampBasis)
    }

    @Test fun historyCannotInventTimestampsAndInvalidSamplesStayMissing() {
        val o = TelemetryObservation("one", 90.0, "°F", null, 1000, fromHistory = true)
        assertNull(TelemetryNormalizer.normalize(o))
        assertNull(TelemetryNormalizer.normalize(o.copy(fromHistory = false, quality = TelemetryQuality.STALE)))
        assertNull(TelemetryNormalizer.normalize(o.copy(fromHistory = false, value = Double.NaN)))
        assertNull(TelemetryNormalizer.normalize(o.copy(fromHistory = false, unit = "kelvin")))
        assertNull(TelemetryNormalizer.normalize(o.copy(observedAtMillis = 999999)))
        assertEquals("receipt", TelemetryNormalizer.normalize(o.copy(fromHistory = false))!!.timestampBasis)
    }

    @Test fun hashedKeysAreStableSeparatedAndDoNotLeakAccountIds() {
        val a = TelemetryNormalizer.localKey("owner", "id:secret")
        assertEquals(64, a.length)
        assertEquals(a, TelemetryNormalizer.localKey("owner", "id:secret"))
        assertNotEquals(a, TelemetryNormalizer.localKey("owner-id:", "secret"))
        assertFalse(a.contains("secret"))
    }

    @Test fun onlyApprovedPublicConnectors() {
        TelemetryConnectorRegistry.planned.forEach(TelemetryConnectorRegistry::requirePlanned)
        try { TelemetryConnectorRegistry.requirePlanned("grillirg"); fail("legacy accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
