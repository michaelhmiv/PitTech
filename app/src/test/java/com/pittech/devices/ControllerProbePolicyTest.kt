package com.pittech.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProbePolicyTest {
    @Test
    fun automaticProbeAllowsOnlyExplicitObservationalMethods() {
        listOf(
            "RPC.Ping",
            "RPC.List",
            "RPC.ListEx",
            "RPC.Describe",
            "Sys.GetInfo",
            "PB.GetFirmwareVersion",
            "PBL.GetLoaderVersion",
        ).forEach { method ->
            assertEquals(method, RpcSafetyClass.SAFE_AUTOPROBE, ControllerProbePolicy.classify(method))
            assertTrue(method, ControllerProbePolicy.canAutoExecute(method))
        }
    }

    @Test
    fun automaticProbeNeverExecutesKnownMutationFamilies() {
        listOf(
            "Sys.Reboot",
            "Config.Set",
            "PB.SetDevicePassword",
            "PB.SetWiFiCredentials",
            "PB.SendMCUCommand",
            "FS.Put",
            "OTA.Update",
            "PB.SetTemperature",
            "PB.PrimerOn",
        ).forEach { method ->
            assertFalse(method, ControllerProbePolicy.canAutoExecute(method))
        }
    }

    @Test
    fun automaticProbeNeverExecutesUnknownRpc() {
        assertEquals(RpcSafetyClass.UNKNOWN, ControllerProbePolicy.classify("Vendor.DoSomething"))
        assertFalse(ControllerProbePolicy.canAutoExecute("Vendor.DoSomething"))
    }

    @Test
    fun configGetIsAllowedOnlyForNonSecretCapabilityKeys() {
        ControllerProbePolicy.safeConfigKeys.forEach { key ->
            assertTrue(
                key,
                ControllerProbePolicy.canAutoExecute(
                    "Config.Get",
                    mapOf("key" to key),
                ),
            )
        }
        listOf("wifi.sta.ssid", "wifi.sta.pass", "device.password", "cloud.token").forEach { key ->
            assertFalse(
                key,
                ControllerProbePolicy.canAutoExecute(
                    "Config.Get",
                    mapOf("key" to key),
                ),
            )
        }
        assertFalse(ControllerProbePolicy.canAutoExecute("Config.Get"))
    }

    @Test
    fun rpcDescriptionsAreBoundedAndPitBossMethodsArePrioritized() {
        val methods = buildSet {
            add("RPC.Describe")
            add("Vendor.Unknown")
            repeat(50) { add("Z.Method$it") }
            add("PB.GetFirmwareVersion")
            add("PBL.GetLoaderVersion")
            add("Sys.GetInfo")
        }

        val candidates = ControllerProbePolicy.describeCandidates(methods)

        assertTrue(candidates.size <= ControllerProbePolicy.MAX_DESCRIPTIONS)
        assertTrue("PB.GetFirmwareVersion" in candidates)
        assertTrue("PBL.GetLoaderVersion" in candidates)
        assertTrue("Sys.GetInfo" in candidates)
    }

    @Test
    fun protocolDetectorRecognizesMongooseServicesFromGattEvidence() {
        val inspection = BluetoothGattInspectionReport(
            address = "AA:BB:CC:DD:EE:FF",
            startedAtUtc = "start",
            finishedAtUtc = "end",
            outcome = "done",
            connected = true,
            connectionStatusCode = 0,
            serviceDiscoveryStatusCode = 0,
            services = listOf(
                BluetoothGattServiceInfo(
                    uuid = ControllerProtocolDetector.MONGOOSE_RPC_SERVICE,
                    kind = "primary",
                    characteristics = emptyList(),
                ),
                BluetoothGattServiceInfo(
                    uuid = ControllerProtocolDetector.MONGOOSE_CONFIG_SERVICE,
                    kind = "primary",
                    characteristics = emptyList(),
                ),
            ),
            totalCharacteristicCount = 0,
            readableCharacteristicCount = 0,
            omittedReadableCharacteristicCount = 0,
            reads = emptyList(),
            events = emptyList(),
            omittedEventCount = 0,
        )

        val protocols = ControllerProtocolDetector.detect(inspection)

        assertTrue(ControllerProtocolFamily.MONGOOSE_RPC in protocols)
        assertTrue(ControllerProtocolFamily.MONGOOSE_CONFIG_GATT in protocols)
        assertFalse(ControllerProtocolFamily.UNKNOWN in protocols)
    }
}
