package com.pittech.devices

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrillirGProtocolTest {
    @Test
    fun vendorVerificationAndWifiScanFramesMatchCapturedAppProtocol() {
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 1, 5, 1, 0xA5.toByte(), 0xA7.toByte(), 0xAA.toByte()),
            GrillirGProtocol.verificationFrame(),
        )
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 1, 1, 1, 1, 0x79, 0xAA.toByte()),
            GrillirGProtocol.wifiScanRequestFrame(),
        )
    }

    @Test
    fun parserValidatesFrameLengthAndCrc() {
        val encoded = GrillirGProtocol.wifiScanRequestFrame()
        val parsed = GrillirGProtocol.parseFrame(encoded)

        assertNotNull(parsed)
        assertEquals(1, parsed?.version)
        assertEquals(1, parsed?.command)
        assertArrayEquals(byteArrayOf(1), parsed?.payload)

        val badCrc = encoded.copyOf().also { it[it.lastIndex - 1] = 0 }
        assertNull(GrillirGProtocol.parseFrame(badCrc))
        assertNull(GrillirGProtocol.parseFrame(encoded.copyOfRange(0, encoded.lastIndex)))
    }

    @Test
    fun twoByteWifiStatusDiagnosticsExposeOnlyRecognizedStatusCodeCandidates() {
        val frame = GrillirGProtocol.Frame(
            version = 1,
            command = 4,
            payload = byteArrayOf(3, 0x7F),
        )

        assertEquals(listOf(0 to 3), GrillirGProtocol.wifiStatusCodeCandidates(frame))
        assertTrue(
            GrillirGProtocol.wifiStatusCodeCandidates(frame.copy(command = 2)).isEmpty(),
        )
        assertTrue(
            GrillirGProtocol.wifiStatusCodeCandidates(frame.copy(payload = byteArrayOf(0x7F, 0x7E))).isEmpty(),
        )
    }

    @Test
    fun longCredentialFramesSplitIntoDefaultAttWritesWithoutChangingFrameBytes() {
        val network = GrillirGProtocol.WifiNetwork(
            signalIndicator = 80,
            rssiRaw = 50,
            authMode = 3,
            cipher = 4,
            groupCipher = 4,
            primaryChannel = 6,
            bssid = byteArrayOf(1, 2, 3, 4, 5, 6),
            ssid = "Test-Grill-WiFi",
        )
        val frame = GrillirGProtocol.createWifiCredentialFrame(network, "temporary-password")
        val chunks = GrillirGProtocol.splitForGattWrites(frame)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= GrillirGProtocol.DEFAULT_GATT_WRITE_BYTES })
        assertArrayEquals(frame, chunks.fold(byteArrayOf()) { joined, chunk -> joined + chunk })
    }

    @Test
    fun parsesNetworkRowsAndScanCompletionMarker() {
        val ssid = "Test-Grill-WiFi".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(72, (-48).toByte(), 3, 4, 4, 11) +
            byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60) +
            byteArrayOf(ssid.size.toByte()) + ssid
        val frame = GrillirGProtocol.Frame(version = 1, command = 2, payload = payload)

        val network = GrillirGProtocol.parseWifiNetwork(payload)
        assertNotNull(network)
        assertEquals("Test-Grill-WiFi", network?.ssid)
        assertEquals(208, network?.rssiRaw)
        assertEquals(3, network?.authMode)
        assertFalse(network?.isOpen ?: true)
        assertEquals("102030405060", network?.key)
        assertFalse(GrillirGProtocol.isWifiScanComplete(frame))
        assertTrue(
            GrillirGProtocol.isWifiScanComplete(
                frame.copy(payload = byteArrayOf(1)),
            ),
        )
    }

    @Test
    fun securedCredentialFrameContainsLengthPrefixedSsidBssidAuthCiphertextAndIv() {
        val network = GrillirGProtocol.WifiNetwork(
            signalIndicator = 80,
            rssiRaw = 50,
            authMode = 3,
            cipher = 4,
            groupCipher = 4,
            primaryChannel = 6,
            bssid = byteArrayOf(1, 2, 3, 4, 5, 6),
            ssid = "Test-Grill-WiFi",
        )

        val parsed = GrillirGProtocol.parseFrame(
            GrillirGProtocol.createWifiCredentialFrame(network, "temporary-password"),
        )
        assertNotNull(parsed)
        assertEquals(3, parsed?.command)
        val payload = parsed!!.payload
        val ssidLength = payload[0].toInt() and 0xFF
        assertEquals(network.ssid, String(payload.copyOfRange(1, 1 + ssidLength), Charsets.UTF_8))
        var offset = 1 + ssidLength
        assertArrayEquals(network.bssid, payload.copyOfRange(offset, offset + 6))
        offset += 6
        assertEquals(network.authMode, payload[offset].toInt() and 0xFF)
        offset += 1
        val ciphertextLength = payload[offset].toInt() and 0xFF
        offset += 1
        assertTrue(ciphertextLength > 0)
        assertEquals(0, ciphertextLength % 16)
        assertEquals(offset + ciphertextLength + 16, payload.size)

        val second = GrillirGProtocol.parseFrame(
            GrillirGProtocol.createWifiCredentialFrame(network, "temporary-password"),
        )!!.payload
        assertFalse(payload.contentEquals(second)) // each password encryption uses a fresh random IV
    }

    @Test
    fun openNetworkCredentialFrameOmitsPasswordCipherAndIv() {
        val network = GrillirGProtocol.WifiNetwork(
            signalIndicator = 80,
            rssiRaw = 50,
            authMode = 0,
            cipher = 0,
            groupCipher = 0,
            primaryChannel = 1,
            bssid = byteArrayOf(1, 2, 3, 4, 5, 6),
            ssid = "Open-Grill-WiFi",
        )

        val frame = GrillirGProtocol.parseFrame(
            GrillirGProtocol.createWifiCredentialFrame(network, password = ""),
        )
        assertNotNull(frame)
        assertEquals(3, frame?.command)
        assertEquals(1 + network.ssid.toByteArray().size + 6 + 1, frame?.payload?.size)
    }
}
