package com.pittech.devices

import org.junit.Assert.assertEquals
import org.junit.Test

class PassiveNotificationAccumulatorTest {
    @Test
    fun recordsEventCountPayloadChangesBytesAndObservedFrequencyWithoutRetainingPayloads() {
        val accumulator = PassiveNotificationAccumulator("service", "characteristic")
        accumulator.record(byteArrayOf(1, 2))
        accumulator.record(byteArrayOf(1, 2))
        accumulator.record(byteArrayOf(3, 4, 5))

        val observation = accumulator.snapshot(durationMillis = 1_500)

        assertEquals(3, observation.eventCount)
        assertEquals(2, observation.payloadChangeCount)
        assertEquals(7, observation.totalPayloadBytes)
        assertEquals(1_500L, observation.observationDurationMillis)
        assertEquals(2.0, observation.frequencyHz, 0.001)
    }
}
