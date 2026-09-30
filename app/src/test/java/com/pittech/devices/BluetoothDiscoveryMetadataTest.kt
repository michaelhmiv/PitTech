package com.pittech.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDiscoveryMetadataTest {
    @Test
    fun mergesBleAndAndroidAdapterResultsForTheSameDevice() {
        val adapterResult = mergeBluetoothDiscoveryMetadata(
            existing = null,
            advertisedName = null,
            adapterName = "GrillirG Controller",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -42,
            discoveryPath = BluetoothDiscoveryPath.ANDROID_ADAPTER_DISCOVERY,
            bluetoothDeviceType = "DUAL",
        )

        val merged = mergeBluetoothDiscoveryMetadata(
            existing = adapterResult,
            advertisedName = "GRILLIRG-1234",
            adapterName = null,
            address = "aa:bb:cc:dd:ee:ff",
            rssi = -50,
            discoveryPath = BluetoothDiscoveryPath.BLE_ADVERTISEMENT,
            bluetoothDeviceType = "DUAL",
        )

        assertEquals("GRILLIRG-1234", merged.advertisedName)
        assertEquals("GrillirG Controller", merged.adapterName)
        assertEquals(-50, merged.rssi)
        assertEquals("DUAL", merged.bluetoothDeviceType)
        assertEquals(
            setOf(
                BluetoothDiscoveryPath.ANDROID_ADAPTER_DISCOVERY,
                BluetoothDiscoveryPath.BLE_ADVERTISEMENT,
            ),
            merged.discoveryPaths,
        )
    }

    @Test
    fun retainsAnAdapterOnlyCandidateWithoutInventingAdvertisementEvidence() {
        val candidate = mergeBluetoothDiscoveryMetadata(
            existing = null,
            advertisedName = null,
            adapterName = "GrillirG Controller",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = null,
            discoveryPath = BluetoothDiscoveryPath.ANDROID_ADAPTER_DISCOVERY,
            bluetoothDeviceType = "CLASSIC",
        )

        assertNull(candidate.advertisedName)
        assertEquals("GrillirG Controller", candidate.adapterName)
        assertEquals(Int.MIN_VALUE, candidate.rssi)
        assertTrue(candidate.discoveryPaths.contains(BluetoothDiscoveryPath.ANDROID_ADAPTER_DISCOVERY))
        assertEquals("CLASSIC", candidate.bluetoothDeviceType)
    }
}
