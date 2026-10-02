package com.pittech.devices

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PitBossBackendTest {
    // Published pytboss regression fixtures. These are synthetic protocol vectors, not grill captures.
    private val pba = "FE0C" + "000000000000020205" + "090600".repeat(6) + "00"
    private val pbva = "FE0C" + "00".repeat(12) + "020500" + "020205" + "01FF"
    @Test fun upstreamCelsiusUnpluggedSentinelAndSetpointAliasRegressionsStayFixed() {
        val celsius = PitBossTelemetry.parse("PBA-test", JSONObject().put("sc_12", pba))
        assertEquals(107.0, celsius.values["probeP1Measured"])
        assertNull(celsius.values["probeP2Measured"])
        assertEquals(1.0, celsius.values["tempUnit"])
        val vertical = PitBossTelemetry.parse("PBVA-test", JSONObject().put("sc_12", pbva))
        assertEquals(250.0, vertical.values["furnaceTempSetting"])
        assertEquals(225.0, vertical.values["furnaceTempMeasured"])
        assertEquals(2, vertical.probeCount)
        assertFalse(vertical.values.containsKey("probeP4Measured"))
        val converted = PitBossTelemetry.parse("PBVA-test", JSONObject().put("sc_12", pbva.dropLast(4) + "00FF"))
        assertEquals(120.0, converted.values["furnaceTempSetting"]) // vendor's nonlinear setpoint table
        assertEquals(107.0, converted.values["furnaceTempMeasured"])
    }
    @Test fun controllerFamilyNativeCelsiusAndFahrenheitWireProfilesRemainDistinct() {
        val bytes = MutableList(27) { 0 }
        bytes[0] = 254; bytes[1] = 12
        for (offset in listOf(5, 8, 11, 14, 20, 23)) { bytes[offset] = 2; bytes[offset + 1] = 1; bytes[offset + 2] = 3 }
        fun hex() = bytes.joinToString("") { "%02x".format(it) }
        val native = PitBossTelemetry.parse("PBL2-test", JSONObject().put("sc_12", hex()))
        val wire = PitBossTelemetry.parse("PBL3-test", JSONObject().put("sc_12", hex()))
        assertEquals(213.0, native.values["furnaceTempMeasured"])
        assertEquals(100.0, wire.values["furnaceTempMeasured"]) // floors, does not round to 101
        assertEquals(100.0, wire.values["probeP4Measured"])
        bytes[26] = 1
        assertEquals(213.0, PitBossTelemetry.parse("PBL3-test", JSONObject().put("sc_12", hex())).values["furnaceTempMeasured"])
        bytes[26] = 3
        schema { PitBossTelemetry.parse("PBL3-test", JSONObject().put("sc_12", hex())) }
    }
    @Test fun emptyResetFramesUnknownFamiliesAndTruncationNeverBecomeZeroTemperatures() {
        schema { PitBossTelemetry.parse("PBL-test", JSONObject().put("sc_11", "FE0B").put("sc_12", "")) }
        schema { PitBossTelemetry.parse("PBL-test", JSONObject().put("sc_12", "FE0C0000")) }
        schema { PitBossTelemetry.parse("NEW-test", JSONObject().put("sc_12", pbva)) }
        schema { PitBossTelemetry.parse("PBL-test", JSONObject().put("sc_12", "FE0G")) }
        try { PitBossTelemetry.controllerId("NEW-abcdef"); fail("Unknown boards must not be guessed") } catch (_: IllegalArgumentException) { }
        try { PitBossTelemetry.controllerId("PBL-../other"); fail("Path segments must not be accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("PBL-Patio", PitBossTelemetry.controllerId("PBL-Patio"))
        assertEquals("PBL-abcdef", PitBossTelemetry.controllerId("pbl-abcdef"))
        assertEquals(3, PitBossTelemetry.probeCount("PBM2-test"))
    }
    @Test fun websocketReadsCorrelateIdAppAndSourceAndKeepRenamedIdentityCase() = runBlocking {
        val socket = FixtureSocket()
        socket.onText = { text ->
            val request = JSONObject(text)
            assertEquals("PB.GetState", request.getString("method"))
            fun response(source: String, app: String, id: Int) = JSONObject().put("id", id).put("src", source).put("app_id", app)
                .put("result", JSONObject().put("sc_12", pbva)).toString()
            socket.textFrame(response("PBVA-Patio", request.getString("app_id"), 999))
            socket.textFrame(response("PBVA-Other", request.getString("app_id"), request.getInt("id")))
            socket.textFrame(response("PBVA-Patio", "other-app", request.getInt("id")))
            socket.textFrame(response("PBVA-Patio", request.getString("app_id"), request.getInt("id")))
        }
        var url = ""
        val backend = PitBossBackend(GrillSocketFactory { value, protocol -> url = value; assertNull(protocol); socket })
        try {
            val session = backend.signIn("PBVA-Patio", "").value
            assertEquals("wss://socket.dansonscorp.com/to/PBVA-Patio", url)
            val device = backend.devices(session).value.single()
            assertEquals("pitboss:PBVA-Patio", device.id)
            assertEquals(2, device.probeCount)
            val result = backend.readings(session, device.id)
            assertNull(result.httpStatus); assertNull(result.apiCode)
            assertEquals(225.0, result.value.values["furnaceTempMeasured"])
            assertTrue(socket.texts.all { JSONObject(it).getString("method") == "PB.GetState" })
            try { backend.readings(session, "pitboss:PBVA-Other"); fail("Only configured controller") }
            catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.AUTH, error.kind) }
        } finally { backend.disconnect() }
        assertTrue(socket.closed)
    }
    @Test fun passwordProtectionUsesOnlyTimeAndStateReadsNeverAccountLoginOrWriteRpc() = runBlocking {
        val socket = FixtureSocket()
        socket.onText = { text ->
            val request = JSONObject(text)
            val result = when (request.getString("method")) {
                "PB.GetTime" -> JSONObject().put("time", 12345)
                "PB.GetState" -> {
                    val encoded = request.getJSONObject("params").getString("psw")
                    assertTrue(encoded.matches(Regex("[a-f0-9]+")))
                    assertEquals((17 + "controller-password".length) * 2, encoded.length)
                    assertFalse(text.contains("controller-password"))
                    JSONObject().put("sc_12", pbva)
                }
                else -> error("No mutating RPC may be sent")
            }
            socket.textFrame(JSONObject().put("id", request.getInt("id")).put("result", result).toString())
        }
        val backend = PitBossBackend(GrillSocketFactory { _, _ -> socket })
        try {
            backend.signIn("PBVA-Patio", "controller-password")
            assertEquals(listOf("PB.GetTime", "PB.GetState"), socket.texts.map { JSONObject(it).getString("method") })
        } finally { backend.disconnect() }
    }
    @Test fun authenticationFailureClosesSocketAndTimeoutIsARetryableNetworkFailure() = runBlocking {
        val socket = FixtureSocket()
        socket.onText = { text -> socket.textFrame(JSONObject().put("id", JSONObject(text).getInt("id"))
            .put("error", JSONObject().put("code", 401).put("message", "private")).toString()) }
        val backend = PitBossBackend(GrillSocketFactory { _, _ -> socket })
        try { backend.signIn("PBVA-Patio", "wrong"); fail("Controller rejected password") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.AUTH, error.kind); assertFalse(error.toString().contains("private")) }
        assertTrue(socket.closed)
        val stalled = object : GrillSocket {
            var closed = false
            override suspend fun awaitOpen() {}
            override suspend fun receive(): GrillSocketFrame = withTimeout(1) { awaitCancellation() }
            override fun text(value: String) {}
            override fun binary(value: ByteArray) {}
            override fun close() { closed = true }
        }
        try { PitBossBackend(GrillSocketFactory { _, _ -> stalled }).signIn("PBVA-Patio", ""); fail("Timeout") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.NETWORK, error.kind) }
        assertTrue(stalled.closed)
    }
    private fun schema(block: () -> Unit) {
        try { block(); fail("Incomplete or unknown frame") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.SCHEMA, error.kind) }
    }
}

