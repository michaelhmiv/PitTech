package com.pittech.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerFingerprintTest {
    @Test
    fun fingerprintIsDeterministicAndIgnoresAddressAndTransientRssi() {
        val first = ControllerProtocolDetector.fingerprintEvidence(device("AA:BB:CC:DD:EE:FF", -45), null, null)
        val second = ControllerProtocolDetector.fingerprintEvidence(device("11:22:33:44:55:66", -81), null, null)

        assertEquals(first.value, second.value)
        assertTrue(first.value.startsWith("pittech:sha256:"))
    }

    @Test
    fun knownFamilyNameKeepsOnlyFamilyAndUnknownNameDoesNotBecomeAnIdentitySignal() {
        val known = ControllerDiagnosticSanitizer.advertisedNameFingerprintSignal("PBL-ABC123")
        val unknown = ControllerDiagnosticSanitizer.advertisedNameFingerprintSignal("Jamie’s Backyard Grill")

        assertEquals("name-family:PBL", known)
        assertEquals("name-present", unknown)
        assertNotEquals("name-family:JAMIE’S BACKYARD GRILL", unknown)
    }

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
                serviceUuids = listOf("0000180F-0000-1000-8000-00805F9B34FB"),
                serviceSolicitationUuids = emptyList(),
                manufacturerData = mapOf("0x1234" to "AABB"),
                serviceData = emptyMap(),
                rawRecordHex = "020106",
            ),
        ),
        omittedAdvertisementVariants = 0,
    )
}
