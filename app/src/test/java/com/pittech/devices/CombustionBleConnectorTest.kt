package com.pittech.devices

import inc.combustion.framework.service.DeviceConnectionState
import inc.combustion.framework.service.Probe
import inc.combustion.framework.service.ProbeTemperatures
import inc.combustion.framework.service.SessionInformation
import inc.combustion.framework.service.LoggedProbeDataPoint
import inc.combustion.framework.service.ProbeVirtualSensors
import inc.combustion.framework.service.ProbePredictionMode
import inc.combustion.framework.service.ProbePredictionState
import inc.combustion.framework.service.ProbePredictionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CombustionBleConnectorTest {
    private val now = 1_760_000_000_000L
    private fun frame(sequence: Long = 100, at: Long = now, session: Long = 41) = CombustionFrame("CCABC1", at,
        (0..7).map { 50.0 + it }, core = 51.0, surface = 54.0, ambient = 57.0,
        connectedStatus = true, session = session, sequence = sequence, samplePeriodMillis = 5000)
    private class Client(var status: CombustionFrame?, var records: List<CombustionLogRecord> = emptyList()) : CombustionReadOnlyClient {
        var snapshots = 0
        var transfers = 0
        var closed = false
        override suspend fun discover() = listOfNotNull(status)
        override suspend fun snapshot(serial: String): CombustionFrame? { snapshots++; return status?.takeIf { it.serial == serial } }
        override suspend fun logs(serial: String): CombustionLogBatch { transfers++; return CombustionLogBatch(status, records) }
        override suspend fun close() { closed = true }
    }
    private fun record(sequence: Long, session: Long = 41) = CombustionLogRecord(session, sequence,
        (0..7).map { 50.0 + it }, 51.0, 54.0, 57.0)

    @Test fun sdkDerivedChannelsKeepRawIdentitiesAndReceiptAdvertisementsDoNotInventTime() {
        val status = frame().copy(connectedStatus = false, session = null, sequence = null)
        val source = CombustionBleConnector.source(status.serial, true)
        val readings = CombustionBleConnector.decode(status)
        assertEquals(12, source.channels.size)
        assertEquals(TelemetryKind.OTHER, source.channels.single { it.id == "surface" }.kind)
        assertEquals("sensor0", readings.first().channelId)
        assertEquals(51.0, readings.single { it.channelId == "core" }.value!!, .001)
        assertEquals("receipt", TelemetryNormalizer.normalize(readings.first())!!.timestampBasis)
        assertTrue(source.model!!.contains("MeatNet"))
    }

    @Test fun sdkConnectionAndStalenessGateAnchors() {
        val original = Probe.create("CCABC1", "raw-private-mac")
        val probe = original.copy(baseDevice = original.baseDevice.copy(connectionState = DeviceConnectionState.CONNECTED),
            temperaturesCelsius = ProbeTemperatures((0..7).map { 50.0 + it }), coreTemperatureCelsius = 51.0,
            surfaceTemperatureCelsius = 54.0, ambientTemperatureCelsius = 57.0, hasReceivedStatus = true,
            sessionInfo = SessionInformation(41u, 5000u), maxSequence = 100u, hopCount = 2u)
        val mapped = OfficialCombustionReadOnlyClient.frame(probe, now)
        assertTrue(mapped.connectedStatus)
        assertTrue(mapped.viaRepeater)
        assertEquals(100L, mapped.sequence)
        assertFalse(mapped.toString().contains("raw-private-mac"))
        val stale = OfficialCombustionReadOnlyClient.frame(probe.copy(statusNotificationsStale = true), now)
        assertFalse(stale.connectedStatus)
        assertTrue(CombustionBleConnector.decode(stale).all { TelemetryNormalizer.normalize(it) == null })
        val point = LoggedProbeDataPoint(41u, 100u, java.util.Date(1), probe.temperaturesCelsius!!,
            ProbeVirtualSensors.VirtualCoreSensor.T2, ProbeVirtualSensors.VirtualSurfaceSensor.T5,
            ProbeVirtualSensors.VirtualAmbientSensor.T8, ProbePredictionState.PROBE_INSERTED,
            ProbePredictionMode.NONE, ProbePredictionType.NONE, 0.0, 0u, 0.0)
        // A cached field/ad cannot substitute for the confirmed normal-status record.
        val confirmed = OfficialCombustionReadOnlyClient.confirmedFrame(probe.copy(coreTemperatureCelsius = 99.0), point, now)!!
        assertEquals(51.0, confirmed.core!!, .001)
        assertNull(OfficialCombustionReadOnlyClient.confirmedFrame(probe, point.copy(sessionId = 99u), now))
        assertNull(OfficialCombustionReadOnlyClient.confirmedFrame(probe, point.copy(sequenceNumber = 99u), now))
        val advertising = probe.copy(baseDevice = probe.baseDevice.copy(connectionState = DeviceConnectionState.ADVERTISING_CONNECTABLE))
        assertNull(OfficialCombustionReadOnlyClient.confirmedFrame(advertising, point, now))
    }

    @Test fun historyUsesSessionSequencesAndDeduplicatesTheLiveWatermark() = runBlocking {
        val client = Client(frame(), listOf(record(98), record(100), record(100, session = 99), record(101)))
        val connector = CombustionBleConnector(client, clock = { now })
        val live = connector.snapshot("CCABC1")
        val history = connector.history("CCABC1", now - 20_000, now)
        assertEquals(22, history.size)
        assertEquals(now - 10_000, history.first().observedAtMillis)
        assertTrue(history.all { it.fromHistory && it.timestampBasis == TelemetryTimestampBasis.PROBE_SEQUENCE_ANCHOR })
        assertEquals(live.first().eventId, history.first { it.observedAtMillis == now }.eventId)
        assertEquals("probe_sequence_anchor", TelemetryNormalizer.normalize(history.first())!!.timestampBasis)
    }

    @Test fun aStoredAnchorSurvivesConnectorReplacementWithoutShiftingOldReadings() = runBlocking {
        val anchors = MemoryCombustionAnchorStore()
        val first = CombustionBleConnector(Client(frame()), anchors, clock = { now })
        first.snapshot("CCABC1")
        first.disconnect()
        val later = now + 100_000
        val restored = CombustionBleConnector(Client(frame(120, later), listOf(record(98))), anchors, clock = { later })
        assertEquals(now - 10_000, restored.history("CCABC1", now - 20_000, later).first().observedAtMillis)
    }

    @Test fun unanchoredStaleAndChangedPeriodRecordsAreNeverGuessed() = runBlocking {
        val store = MemoryCombustionAnchorStore()
        val baseline = CombustionBleConnector(Client(frame()), store, clock = { now })
        baseline.snapshot("CCABC1")
        for (bad in listOf(frame().copy(connectedStatus = false), frame().copy(quality = TelemetryQuality.STALE),
            frame().copy(samplePeriodMillis = 0), frame().copy(samplePeriodMillis = 10000),
            frame().copy(receivedAtMillis = now + 300_000))) {
            val connector = CombustionBleConnector(Client(bad, listOf(record(98))), store, clock = { now + 300_000 })
            try { connector.history("CCABC1", now - 20_000, now + 300_000); fail("ambiguous history cannot advance recovery") }
            catch (_: java.io.IOException) { }
        }
    }

    @Test fun historyWindowsShareOneTransferAndDisconnectClearsCaches() = runBlocking {
        val client = Client(frame(), listOf(record(98)))
        val connector = CombustionBleConnector(client, clock = { now })
        connector.history("CCABC1", now - 20_000, now - 5000)
        connector.history("CCABC1", now - 5000, now)
        assertEquals(1, client.transfers)
        connector.disconnect()
        assertTrue(client.closed)
        assertTrue(connector.snapshot("CCABC1").isEmpty())
        assertTrue(connector.discover().isEmpty())
        assertTrue(connector.history("CCABC1", now - 5000, now).isEmpty())
    }

    @Test fun instantReadAndInvalidValuesRemainExplicitlyMissing() {
        val instant = CombustionBleConnector.decode(CombustionFrame("probe", now, instant = 82.3))
        assertEquals("instant", instant.single().channelId)
        assertEquals("receipt", TelemetryNormalizer.normalize(instant.single())!!.timestampBasis)
        assertNull(TelemetryNormalizer.normalize(CombustionBleConnector.decode(frame().copy(core = Double.NaN))
            .single { it.channelId == "core" }))
    }

    @Test fun sequenceMathHandlesUnsignedLimitsAndRejectsInvalidEpochs() {
        val anchor = CombustionTimeAnchor(1, 4_294_967_290L, now, 5000)
        assertEquals(now + 25_000, anchor.timeFor(4_294_967_295L))
        assertNull(anchor.timeFor(4_294_967_296L))
        assertNull(CombustionTimeAnchor(1, 100, 1000, 5000).timeFor(0))
        assertNull(CombustionTimeAnchor(1, 0, Long.MAX_VALUE, 5000).timeFor(1))
    }

    @Test fun aSaturatedHistoryWindowIsBoundedForTheSharedRecoverySplitter() = runBlocking {
        val client = Client(frame(3000), (1L..3000L).map { record(it) })
        val history = CombustionBleConnector(client, clock = { now }).history("CCABC1", now - 20_000_000, now)
        assertEquals(20_000, history.size)
        assertTrue(history.all { it.fromHistory && TelemetryNormalizer.normalize(it) != null })
        assertTrue(history.last().observedAtMillis!! < now)
    }
}
