package com.pittech.devices

import inc.combustion.framework.service.ProbeScanResult
import inc.combustion.framework.service.ProbeTemperatures
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.isActive

class CombustionBleConnectorTest {
    @Test fun sdkAdvertisementMapsEightNativeCelsiusSensorsWithoutPublishingCommands() {
        val scan = ProbeScanResult("CCABC1", "AA:BB:CC:00:11:22", -55,
            ProbeTemperatures((0..7).map { 50.0 + it }), null)
        val source = CombustionBleConnector.source("CCABC1")
        val observations = CombustionBleConnector.decode(scan, 1_760_000_000_000L)
        assertEquals(8, observations.size)
        assertEquals("combustion", source.connectorId)
        assertEquals(9, source.channels.size)
        assertEquals(TelemetryKind.AMBIENT, source.channels[7].kind)
        assertEquals(TelemetryKind.OTHER, source.channels[1].kind)
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
    @Test fun samplingCancelsIdleRadioAndDisconnectRetainsNoCachedReading() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val radio = kotlinx.coroutines.flow.MutableSharedFlow<ProbeScanResult>()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val scan = ProbeScanResult("CCABC1", null, -50, ProbeTemperatures(listOf(50.0)), null)
        val connector = CombustionBleConnector(scope = scope, advertisements = radio, scan = {
            active.incrementAndGet()
            try { while (kotlinx.coroutines.currentCoroutineContext().isActive) { radio.emit(scan); kotlinx.coroutines.delay(10) } }
            finally { active.decrementAndGet() }
        }, scanWindowMillis = 1_000)
        try {
            val readings = connector.snapshot("CCABC1")
            assertEquals(1, readings.size)
            kotlinx.coroutines.withTimeout(1_000) { while (active.get() != 0) kotlinx.coroutines.delay(10) }
            assertTrue(connector.snapshot("missing").isEmpty())
            kotlinx.coroutines.withTimeout(1_000) { while (active.get() != 0) kotlinx.coroutines.delay(10) }
            connector.disconnect()
            assertTrue(connector.snapshot("CCABC1").isEmpty())
        } finally { connector.disconnect(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun scannerFailureCannotEscapeAndCrashOtherProviders() = kotlinx.coroutines.runBlocking {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> failures.incrementAndGet() })
        val connector = CombustionBleConnector(scope = scope, advertisements = kotlinx.coroutines.flow.emptyFlow(), scan = { throw SecurityException("Permission removed") }, scanWindowMillis = 100)
        try { assertTrue(connector.snapshot("probe").isEmpty()); assertEquals(0, failures.get()) }
        finally { connector.disconnect(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

}
