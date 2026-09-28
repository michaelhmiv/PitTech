package com.pittech.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerStableFingerprintTest {
    @Test
    fun systemFingerprintUsesStableHardwareFieldsAndIgnoresUptimeAndNetworkAddress() {
        val first = ControllerProtocolDetector.fingerprintEvidence(
            device("AA:BB:CC:DD:EE:FF", -45),
            null,
            probeWithSystemInfo("""{"arch":"ESP32","version":"2.4","uptime":10,"ip":"192.168.1.5"}"""),
        )
        val second = ControllerProtocolDetector.fingerprintEvidence(
            device("11:22:33:44:55:66", -81),
            null,
            probeWithSystemInfo("""{"arch":"ESP32","version":"2.4","uptime":9800,"ip":"192.168.1.9"}"""),
        )

        assertEquals(first.value, second.value)
    }

    private fun probeWithSystemInfo(info: String) = ControllerProbeReport(
        address = "AA:BB:CC:DD:EE:FF",
        startedAtUtc = "start",
        finishedAtUtc = "end",
        outcome = "done",
        protocols = setOf(ControllerProtocolFamily.MONGOOSE_RPC),
        rpcMethods = emptyList(),
        rpcDescriptions = emptyMap(),
        observations = listOf(
            RpcProbeObservation("System information", "Sys.GetInfo", ProbeStepState.SUCCESS, response = info),
        ),
        debugMessages = emptyList(),
        transportCapabilities = emptyList(),
        events = emptyList(),
        omittedEventCount = 0,
    )

    private fun device(address: String, rssi: Int) = NearbyBluetoothDevice(
        key = address,
        advertisedName = "PBL-ABC123",
        address = address,
        rssi = rssi,
        advertisements = listOf(
            BluetoothAdvertisementVariant(
                firstSeenOffsetMillis = 0,
                lastSeenOffsetMillis = 100,
                observationCount = 1,
                weakestRssi = rssi,
                strongestRssi = rssi,
                advertisedName = "PBL-ABC123",
                txPower = null,
                connectable = true,
                advertiseFlags = 6,
                primaryPhy = 1,
                secondaryPhy = null,
                advertisingSid = null,
                periodicAdvertisingInterval = null,
                dataStatus = 0,
                serviceUuids = emptyList(),
                serviceSolicitationUuids = emptyList(),
                manufacturerData = emptyMap(),
                serviceData = emptyMap(),
                rawRecordHex = "020106",
            ),
        ),
        omittedAdvertisementVariants = 0,
    )
}
