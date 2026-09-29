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
            "PB.GetState",
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
    fun automaticProbeNeverExecutesSysReboot() {
        assertFalse(ControllerProbePolicy.canAutoExecute("Sys.Reboot"))
    }

    @Test
    fun automaticProbeNeverExecutesConfigSet() {
        assertFalse(ControllerProbePolicy.canAutoExecute("Config.Set"))
    }

    @Test
    fun automaticProbeNeverExecutesPasswordMutation() {
        assertFalse(ControllerProbePolicy.canAutoExecute("PB.SetDevicePassword"))
        assertFalse(ControllerProbePolicy.canAutoExecute("Wifi.SetCredentials"))
    }

    @Test
    fun automaticProbeNeverExecutesMcuCommands() {
        assertFalse(ControllerProbePolicy.canAutoExecute("PB.SendMCUCommand"))
    }

    @Test
    fun automaticProbeNeverExecutesUnknownRpc() {
        assertFalse(ControllerProbePolicy.canAutoExecute("Vendor.DoSomething"))
    }

    @Test
    fun automaticProbeNeverExecutesControlAndUpdateCommands() {
        listOf(
            "PB.SetTemperature",
            "PB.SetPower",
            "PB.IgnitionOn",
            "PB.PrimerOn",
            "PB.AugerOn",
            "OTA.Update",
            "FS.Put",
            "FS.Write",
            "Wifi.SetConfig",
        ).forEach { assertFalse(it, ControllerProbePolicy.canAutoExecute(it)) }
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
    fun probePlanOnlyExecutesKnownSafeMethodsAndDescribesOtherInventoryEntries() {
        val inventory = setOf(
            "RPC.Describe",
            "Sys.GetInfo",
            "PB.GetState",
            "PB.SetTemperature",
            "PB.SendMCUCommand",
            "Wifi.SetCredentials",
            "FS.Put",
            "Vendor.DoSomething",
            "Config.Get",
        )
        val safeRequests = ControllerProbePolicy.initialRequests() +
            ControllerProbePolicy.plannedReads(inventory)
        val descriptionRequests = ControllerProbePolicy.describeCandidates(inventory)
            .map { name -> SafeRpcRequest("Describe $name", "RPC.Describe", mapOf("name" to name)) }
        val allRequests = safeRequests + descriptionRequests

        assertEquals(listOf("RPC.Ping", "RPC.List"), ControllerProbePolicy.initialRequests().map { it.method })
        assertEquals(
            listOf("Sys.GetInfo", "PB.GetState", "Config.Get"),
            ControllerProbePolicy.plannedReads(inventory).map { it.method },
        )
        assertEquals(mapOf("key" to "http.enable"), ControllerProbePolicy.plannedReads(inventory).last().params)
        assertTrue(allRequests.all { ControllerProbePolicy.canAutoExecute(it.method, it.params) })
        assertFalse(allRequests.any { it.method in inventory && it.method !in setOf("RPC.Describe", "Sys.GetInfo", "PB.GetState", "Config.Get") })
        assertTrue(descriptionRequests.any { it.params["name"] == "PB.SetTemperature" })
        assertTrue(descriptionRequests.any { it.params["name"] == "PB.SendMCUCommand" })
        assertTrue(descriptionRequests.any { it.params["name"] == "Vendor.DoSomething" })
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
