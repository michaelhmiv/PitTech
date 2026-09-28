package com.pittech.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothScanDiagnosticsTest {
    private val summary = BluetoothScanSummary(
        startedAtUtc = "2026-09-28 15:00:00 UTC",
        finishedAtUtc = "2026-09-28 15:00:12 UTC",
        durationMillis = 12_000,
        scanMode = "LOW_LATENCY",
        totalResults = 9,
        capturedDeviceCount = 2,
        omittedDeviceCount = 0,
        error = null,
    )

    private fun sample(rawRecordHex: String = "020106") = BluetoothAdvertisementVariant(
        firstSeenOffsetMillis = 35,
        lastSeenOffsetMillis = 9_200,
        observationCount = 9,
        weakestRssi = -71,
        strongestRssi = -49,
        advertisedName = "PitBoss-ABC123",
        txPower = 4,
        connectable = true,
        advertiseFlags = 6,
        primaryPhy = 1,
        secondaryPhy = 0,
        advertisingSid = null,
        periodicAdvertisingInterval = null,
        dataStatus = 0,
        serviceUuids = listOf("0000180F-0000-1000-8000-00805F9B34FB"),
        serviceSolicitationUuids = listOf("00001812-0000-1000-8000-00805F9B34FB"),
        manufacturerData = mapOf("0x1234" to "AABB"),
        serviceData = mapOf("0000180F-0000-1000-8000-00805F9B34FB" to "01"),
        rawRecordHex = rawRecordHex,
    )

    private fun device(
        rawRecordHex: String = "020106",
    ) = NearbyBluetoothDevice(
        key = "AA:BB:CC:DD:EE:FF",
        advertisedName = "PitBoss-ABC123",
        address = "AA:BB:CC:DD:EE:FF",
        rssi = -49,
        advertisements = listOf(sample(rawRecordHex)),
        omittedAdvertisementVariants = 2,
    )


    private fun gattReport(address: String = "AA:BB:CC:DD:EE:FF") = BluetoothGattInspectionReport(
        address = address,
        startedAtUtc = "2026-09-28 15:00:12.000 UTC",
        finishedAtUtc = "2026-09-28 15:00:13.000 UTC",
        outcome = "GATT service inspection completed.",
        connected = true,
        connectionStatusCode = 0,
        serviceDiscoveryStatusCode = 0,
        services = listOf(
            BluetoothGattServiceInfo(
                uuid = "0000180F-0000-1000-8000-00805F9B34FB",
                kind = "primary",
                characteristics = listOf(
                    BluetoothGattCharacteristicInfo(
                        uuid = "00002A19-0000-1000-8000-00805F9B34FB",
                        properties = listOf("READ", "NOTIFY"),
                        permissions = listOf("READ"),
                        descriptors = listOf(
                            BluetoothGattDescriptorInfo(
                                uuid = "00002902-0000-1000-8000-00805F9B34FB",
                                permissions = listOf("READ"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        totalCharacteristicCount = 1,
        readableCharacteristicCount = 1,
        omittedReadableCharacteristicCount = 0,
        reads = listOf(
            BluetoothGattReadResult(
                serviceUuid = "0000180F-0000-1000-8000-00805F9B34FB",
                characteristicUuid = "00002A19-0000-1000-8000-00805F9B34FB",
                initiated = true,
                statusCode = 0,
                valueLengthBytes = 1,
                valueHex = "64",
                valueText = "d",
                truncatedValueBytes = 0,
                note = null,
            ),
        ),
        events = listOf("2026-09-28 15:00:12.000 UTC +20ms onServicesDiscovered status=GATT_SUCCESS."),
        omittedEventCount = 0,
    )

    @Test
    fun reportContainsSelectedDeviceAddressAndFullBleAdvertisementFieldsOnly() {
        val selected = device()
        val details = BluetoothScanDiagnostics.details(
            summary = summary,
            selectedDevice = selected,
            relayStage = "DISCONNECTED",
            relayStatus = "Not connected",
            relayMessages = emptyList(),
        )

        assertTrue(details.contains("Bluetooth address: AA:BB:CC:DD:EE:FF"))
        assertTrue(details.contains("Service UUIDs: 0000180F"))
        assertTrue(details.contains("Service solicitation UUIDs: 00001812"))
        assertTrue(details.contains("0x1234 -> AABB"))
        assertTrue(details.contains("Raw advertisement record (hex): 020106"))
        assertTrue(details.contains("Distinct advertisement variants omitted: 2"))
        assertFalse(details.contains("unselected-device-address"))
    }

    @Test
    fun everyDeviceRemainsUnverifiedUntilItsProfileIsApproved() {
        assertFalse(ControllerSupportRegistry.isApproved(device()))
        assertEquals(
            "Not yet verified by PitTech",
            ControllerSupportRegistry.label(device()),
        )
    }

    @Test
    fun reportTruncatesAtTheRelayLimitAndSaysThatItWasTruncated() {
        val details = BluetoothScanDiagnostics.details(
            summary = summary,
            selectedDevice = device("AB".repeat(25_000)),
            relayStage = "DISCONNECTED",
            relayStatus = "Not connected",
            relayMessages = emptyList(),
        )

        assertEquals(BluetoothScanDiagnostics.MAX_DETAILS_CHARS, details.length)
        assertTrue(details.contains("Report truncated to fit the feedback service limit"))
    }

    @Test
    fun reportIncludesReadOnlyGattInventoryAndAppEventsForTheSelectedDevice() {
        val details = BluetoothScanDiagnostics.details(
            summary = summary,
            selectedDevice = device(),
            relayStage = "DISCONNECTED",
            relayStatus = "Not connected",
            relayMessages = emptyList(),
            inspection = gattReport(),
            sessionEvents = listOf("2026-09-28 15:00:10 UTC BLE scan finished; results=9"),
        )

        assertTrue(details.contains("Bluetooth GATT inspection"))
        assertTrue(details.contains("Characteristic 00002A19"))
        assertTrue(details.contains("Descriptor 00002902"))
        assertTrue(details.contains("Value (hex): 64"))
        assertTrue(details.contains("this inventory phase does not write characteristics"))
        assertTrue(details.contains("BLE scan finished; results=9"))
    }

    @Test
    fun reportExcludesGattInspectionWhenItBelongsToAnotherDevice() {
        val details = BluetoothScanDiagnostics.details(
            summary = summary,
            selectedDevice = device(),
            relayStage = "DISCONNECTED",
            relayStatus = "Not connected",
            relayMessages = emptyList(),
            inspection = gattReport("11:22:33:44:55:66"),
        )

        assertFalse(details.contains("Bluetooth GATT inspection"))
        assertTrue(details.contains("GATT inspection excluded"))
    }

    @Test
    fun gattFormatterBoundsLargeReportsAndMarksTruncation() {
        val report = gattReport().copy(
            outcome = "x".repeat(20_000),
        )
        val details = BluetoothGattDiagnostics.format(report)

        assertEquals(BluetoothGattDiagnostics.MAX_DETAILS_CHARS, details.length)
        assertTrue(details.contains("[GATT section truncated"))
    }

}
