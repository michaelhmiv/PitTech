package com.pittech.devices

import org.junit.Assert.*
import org.junit.Test

class TelemetryChannelHealthTest {
    @Test fun validNeighborCannotHideAnUnavailableOrNewerInvalidProbe() {
        val source = TelemetrySource("fireboard", "device", "FireBoard", channels = listOf(TelemetryChannel("a", "Food 1", TelemetryKind.FOOD), TelemetryChannel("b", "Food 2", TelemetryKind.FOOD)))
        val batch = listOf(TelemetryObservation("a", 180.0, "°F", 1_000, 2_000), TelemetryObservation("b", null, "°F", 1_000, 2_000, TelemetryQuality.UNAVAILABLE))
        assertEquals(setOf("a"), TelemetryChannelHealth.validChannels(source, batch))
        assertTrue(TelemetryChannelHealth.validChannels(source, batch + TelemetryObservation("a", null, "°F", 1_500, 2_000, TelemetryQuality.UNAVAILABLE)).isEmpty())
    }
}
