package com.pittech.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MongooseRpcInterrogationPlannerTest {
    @Test
    fun fakePeripheralCompletesPingListSafeReadsAndMethodDescriptions() {
        val result = runFakeProbe(listUnavailable = false)

        assertEquals(listOf("RPC.Ping", "RPC.List"), result.calls.take(2).map { it.method })
        assertTrue(result.calls.any { it.method == "Sys.GetInfo" })
        assertTrue(result.calls.any { it.method == "PB.GetFirmwareVersion" })
        assertTrue(result.calls.any { it.method == "PBL.GetLoaderVersion" })
        assertTrue(result.calls.any { it.method == "RPC.Describe" && it.params["name"] == "PB.SetTemperature" })
        assertTrue("PB.SendMCUCommand" in result.inventory)
        assertTrue("Vendor.DoSomething" in result.inventory)
        assertTrue(result.descriptions.containsKey("PB.SetTemperature"))
        assertTrue(result.pingSucceeded)
        assertTrue(result.calls.all { ControllerProbePolicy.canAutoExecute(it.method, it.params) })
        assertFalse(result.calls.any { it.method in FORBIDDEN_TO_EXECUTE })
    }

    @Test
    fun fakePeripheralFallsBackToListExWhenListIsUnavailable() {
        val result = runFakeProbe(listUnavailable = true)

        assertEquals(listOf("RPC.Ping", "RPC.List", "RPC.ListEx"), result.calls.take(3).map { it.method })
        assertEquals(FakeMongooseRpcPeripheral.methods.toSet(), result.inventory.toSet())
        assertTrue(result.pingSucceeded)
        assertTrue(result.calls.all { ControllerProbePolicy.canAutoExecute(it.method, it.params) })
    }

    private fun runFakeProbe(listUnavailable: Boolean): FakeProbeResult {
        val planner = MongooseRpcInterrogationPlanner()
        val peripheral = FakeMongooseRpcPeripheral(listUnavailable)
        val calls = mutableListOf<SafeRpcRequest>()
        var requestId = 0
        var pingSucceeded = false

        planner.start()
        var request = planner.next()
        while (request != null) {
            assertTrue("Unexpected automatic RPC ${request.method}", ControllerProbePolicy.canAutoExecute(request.method, request.params))
            requestId += 1
            val bytes = MongooseRpcFraming.encodeRequest(requestId, request.method, request.params)
            val encoded = JSONObject(bytes.toString(Charsets.UTF_8))
            assertEquals(requestId, encoded.getInt("id"))
            assertEquals(request.method, encoded.getString("method"))
            val responseJson = peripheral.respond(encoded)
            val response = MongooseRpcResponseParser.parse(responseJson, requestId)
            assertTrue("${request.method} should return a framed JSON-RPC result", response.kind in setOf(
                MongooseRpcResponseKind.SUCCESS,
                MongooseRpcResponseKind.RPC_ERROR,
            ))
            if (request.method == "RPC.Ping" && response.kind == MongooseRpcResponseKind.SUCCESS) {
                pingSucceeded = response.resultJson == "pong"
            }
            calls += request
            planner.accept(request, response)
            request = planner.next()
        }

        return FakeProbeResult(calls, planner.rpcMethods, planner.rpcDescriptions, pingSucceeded)
    }

    private data class FakeProbeResult(
        val calls: List<SafeRpcRequest>,
        val inventory: List<String>,
        val descriptions: Map<String, String>,
        val pingSucceeded: Boolean,
    )

    private class FakeMongooseRpcPeripheral(
        private val listUnavailable: Boolean,
    ) {
        fun respond(request: JSONObject): String {
            val id = request.getInt("id")
            val method = request.getString("method")
            val params = request.optJSONObject("params") ?: JSONObject()
            if (method !in setOf("RPC.Ping", "RPC.List", "RPC.ListEx", "RPC.Describe") &&
                method !in SAFE_READ_METHODS
            ) {
                error("Fake peripheral refused unsafe direct RPC $method")
            }
            val result: Any = when (method) {
                "RPC.Ping" -> "pong"
                "RPC.List" -> if (listUnavailable) {
                    return JSONObject()
                        .put("id", id)
                        .put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
                        .toString()
                } else inventoryJson()
                "RPC.ListEx" -> inventoryJson()
                "RPC.Describe" -> JSONObject()
                    .put("name", params.optString("name"))
                    .put("args_fmt", "{}")
                "Sys.GetInfo" -> JSONObject().put("arch", "ESP32").put("version", "2.4").put("uptime", 100)
                "PB.GetState" -> JSONObject().put("state", "idle")
                "PB.GetFirmwareVersion" -> "2.4"
                "PBL.GetLoaderVersion" -> "1.2"
                "Config.Get" -> if (params.optString("key") == "http.enable") true else error("Unsafe config read")
                else -> error("Fake peripheral does not implement $method")
            }
            return JSONObject().put("id", id).put("result", result).toString()
        }

        private fun inventoryJson() = JSONObject().put(
            "methods",
            JSONArray(methods.map { JSONObject().put("name", it) }),
        )

        companion object {
            val methods = listOf(
                "RPC.Ping",
                "RPC.List",
                "RPC.ListEx",
                "RPC.Describe",
                "Sys.GetInfo",
                "PB.GetState",
                "PB.GetFirmwareVersion",
                "PBL.GetLoaderVersion",
                "Config.Get",
                "Wifi.Scan",
                "PB.SetTemperature",
                "PB.SendMCUCommand",
                "Wifi.SetCredentials",
                "FS.Put",
                "Vendor.DoSomething",
            )
        }
    }

    private companion object {
        val SAFE_READ_METHODS = setOf(
            "Sys.GetInfo",
            "PB.GetState",
            "PB.GetFirmwareVersion",
            "PBL.GetLoaderVersion",
            "Config.Get",
        )
        val FORBIDDEN_TO_EXECUTE = setOf(
            "Wifi.Scan",
            "PB.SetTemperature",
            "PB.SendMCUCommand",
            "Wifi.SetCredentials",
            "FS.Put",
            "Vendor.DoSomething",
        )
    }
}
