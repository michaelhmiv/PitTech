package com.pittech.devices

import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TraegerBackendTest {
    private class Http(val socket: () -> FixtureSocket) : TraegerHttpTransport {
        val operations = CopyOnWriteArrayList<TraegerHttpOperation>()
        var body = ""
        var signedUrl = "wss://example.iot.us-west-2.amazonaws.com/mqtt?X-Amz-Signature=synthetic"
        var failure: PolarisHttpResponse? = null
        var report: () -> List<ByteArray> = { listOf(mqttReport("prod/thing/update/owned_test_grill", traegerEnvelope().toString())) }
        override suspend fun request(operation: TraegerHttpOperation, body: String?, token: String?, thing: String?): PolarisHttpResponse {
            operations += operation
            this.body = body.orEmpty()
            failure?.let { return it }
            val response = when (operation) {
                TraegerHttpOperation.LOGIN -> JSONObject().put("idToken", traegerToken()).put("expiresIn", 3600).put("refreshToken", "private-refresh")
                TraegerHttpOperation.DEVICES -> JSONObject().put("things", JSONArray()
                    .put(JSONObject().put("thingName", "owned_test_grill").put("friendlyName", "Test grill").put("status", "CONFIRMED"))
                    .put(JSONObject().put("thingName", "unconfirmed").put("status", "PENDING")))
                TraegerHttpOperation.MQTT_CONNECTION -> JSONObject().put("signedUrl", signedUrl).put("expirationSeconds", 3600)
                TraegerHttpOperation.STATUS -> { report().forEach(socket()::binaryFrame); JSONObject() }
                TraegerHttpOperation.REFRESH -> JSONObject().put("AuthenticationResult", JSONObject().put("IdToken", traegerToken()).put("ExpiresIn", 3600))
            }
            return PolarisHttpResponse(200, response.toString())
        }
    }
    @Test fun ownedGrillDiscoveryMqttSubscriptionStatusReadAndCrossGrillFilteringAreEndToEnd() = runBlocking {
        val socket = FixtureSocket().apply { mqttHandshake() }
        val http = Http { socket }.apply {
            report = { listOf(
                mqttReport("prod/thing/update/other", traegerEnvelope("other").toString(), 40),
                mqttReport("prod/thing/update/owned_test_grill", traegerEnvelope("other").toString(), 41),
                mqttReport("prod/thing/update/owned_test_grill", traegerEnvelope().apply { remove("thingName") }.toString(), 42)) }
        }
        val urls = mutableListOf<String>()
        val backend = TraegerBackend(http, GrillSocketFactory { url, protocol ->
            urls += url; assertEquals("mqtt", protocol); socket
        }, now = { 1_000_000 })
        try {
            val session = backend.signIn("synthetic@example.test", "private-password").value
            assertFalse(session.toString().contains("private"))
            val device = backend.devices(session).value.single()
            assertEquals("traeger:owned_test_grill", device.id)
            assertEquals(4, device.probeCount)
            val result = backend.readings(session, device.id)
            assertNull(result.httpStatus); assertNull(result.apiCode)
            assertEquals(225.0, result.value.values["furnaceTempMeasured"])
            assertEquals(0.0, result.value.values["tempUnit"])
            assertEquals(1_000_000L, result.value.reportedAtMillis)
            assertEquals(listOf(TraegerHttpOperation.LOGIN, TraegerHttpOperation.DEVICES, TraegerHttpOperation.MQTT_CONNECTION, TraegerHttpOperation.STATUS), http.operations)
            assertEquals(1, urls.size)
            assertTrue(socket.binaries.any { it.contentEquals(byteArrayOf(0x40, 2, 0, 40)) })
            assertTrue(socket.binaries.all { (it[0].toInt() and 255) in listOf(0x10, 0x82, 0xC0, 0x40) })
            try { backend.readings(session, "traeger:not_owned"); fail("Only owned grills") }
            catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.AUTH, error.kind) }
        } finally { backend.disconnect() }
        assertTrue(socket.closed)
    }
    @Test fun continuousReceiverAcknowledgesBurstsAndNextPollDoesNotReplayQueuedHistory() = runBlocking {
        val socket = FixtureSocket().apply { mqttHandshake() }
        val http = Http { socket }
        val backend = TraegerBackend(http, GrillSocketFactory { _, _ -> socket }, now = { 1_000_000 })
        val lastAck = CompletableDeferred<Unit>()
        val releaseAck = CountDownLatch(1)
        try {
            val session = backend.signIn("synthetic@example.test", "private-password").value
            val id = backend.devices(session).value.single().id
            backend.readings(session, id)
            socket.onBinary = { packet ->
                if (packet.contentEquals(byteArrayOf(0x40, 2, 0, 120))) {
                    lastAck.complete(Unit)
                    check(releaseAck.await(5, TimeUnit.SECONDS))
                }
            }
            for (n in 1..20) socket.binaryFrame(mqttReport("prod/thing/update/owned_test_grill",
                traegerEnvelope(time = 1_000_000L + n * 1000).toString(), 100 + n))
            withTimeout(3000) { lastAck.await() }
            assertEquals(21, socket.binaries.count { it[0].toInt() == 0x40 })
            http.report = { listOf(mqttReport("prod/thing/update/owned_test_grill", traegerEnvelope(time = 1_030_000L).toString())) }
            // Begin a poll while the receiver is held inside its final PUBACK. With the
            // previous ordering, the cleared older report was published only afterward.
            val next = async { backend.readings(session, id) }
            withTimeout(3000) { while (http.operations.count { it == TraegerHttpOperation.STATUS } < 2) delay(5) }
            releaseAck.countDown()
            assertEquals(1_030_000L, next.await().value.reportedAtMillis)
            assertEquals(1, http.operations.count { it == TraegerHttpOperation.MQTT_CONNECTION })
        } finally { releaseAck.countDown(); backend.disconnect() }
    }
    @Test fun canonicalUnitsStableChannelsDisconnectedProbesAndRealZeroCelsiusArePreserved() {
        val envelope = traegerEnvelope().apply {
            getJSONObject("status").put("units", 0).put("grill", 100).put("probe_con", 1).put("probe", 999)
                .put("acc", JSONArray()
                    .put(JSONObject("""{"type":"probe","channel":"p2","con":1,"probe":{"get_temp":65,"set_temp":80}}"""))
                    .put(JSONObject("""{"type":"probe","channel":"p0","con":0,"probe":{"get_temp":999}}"""))
                    .put(JSONObject("""{"type":"probe","channel":"p3","con":1,"probe":{"get_temp":0}}""")))
            put("settings", JSONObject().put("ssid", "private-ssid")); put("account", "private-account")
        }
        val payload = TraegerBackend.parseStatus(envelope)
        assertEquals(1.0, payload.values["tempUnit"])
        assertNull(payload.values["probeP1Measured"])
        assertEquals(65.0, payload.values["probeP3Measured"])
        assertEquals(0.0, payload.values["probeP4Measured"])
        val values = CookTelemetryPolicy.values(PolarisSample(1_000_000, payload), "°F")
        assertEquals(32.0, values.single { it.name == "Probe 4" }.value, 0.001)
        assertEquals("valid", values.single { it.name == "Probe 4" }.quality)
        assertEquals("unavailable", values.single { it.name == "Probe 1" }.quality)
        val report = PolarisMonitorPolicy.report(PolarisMonitorState(provider = GrillProvider.TRAEGER,
            latest = PolarisSample(1_000_000, payload), samples = listOf(PolarisSample(1_000_000, payload))), 1_000_000)
        assertFalse(report.contains("private-ssid")); assertFalse(report.contains("private-account"))
    }
    @Test fun duplicateChannelsAndInvalidUnitsCannotProduceAttributedTemperatures() {
        val envelope = traegerEnvelope()
        envelope.getJSONObject("status").put("acc", JSONArray("""[{"type":"probe","channel":"p0","con":1},{"type":"probe","channel":"p0","con":1}]"""))
        try { TraegerBackend.parseStatus(envelope); fail("Ambiguous channels must fail") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.SCHEMA, error.kind) }
        envelope.getJSONObject("status").remove("acc")
        envelope.getJSONObject("status").put("units", 0.5)
        assertTrue(CookTelemetryPolicy.values(PolarisSample(1_000_000, TraegerBackend.parseStatus(envelope)), "°F").isEmpty())
        envelope.getJSONObject("status").put("system_status", 99).remove("connected")
        assertEquals(1.0, TraegerBackend.parseStatus(envelope).values["onlineStatus"])
    }
    @Test fun transportPinsReadOnlyCommandAndRejectsPathInjection() {
        val request = OkHttpTraegerTransport.buildRequest(TraegerHttpOperation.STATUS, """{"command":"11"}""", "private-token", "owned")
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        assertEquals("""{"command":"90"}""", buffer.readUtf8())
        assertEquals("private-token", request.header("Authorization"))
        assertEquals("POST", request.method)
        assertEquals("https://mobile-iot-api.iot.traegergrills.io/things/owned/commands", request.url.toString())
        try { TraegerHttpOperation.STATUS.url("../commands"); fail("Invalid identity") } catch (_: IllegalArgumentException) { }
        assertFalse(TraegerBackend.validSignedUrl("wss://example.test/mqtt?token=private"))
        assertFalse(TraegerBackend.validSignedUrl("wss://user@example.iot.us-west-2.amazonaws.com/mqtt?token=x"))
        assertFalse(TraegerBackend.validSignedUrl("wss://example.iot.us-west-2.amazonaws.com/mqtt?token=x#fragment"))
    }
    @Test fun encryptedRefreshTokenRenewsSessionWithoutSavingOrResendingPassword() = runBlocking {
        val http = Http { FixtureSocket() }
        val backend = TraegerBackend(http, now = { 2_000_000 })
        val session = PolarisSession(traegerToken(), 1, "traeger:owned_test_grill", "private-refresh")
        val result = backend.refreshSession(session).value
        assertEquals(5_600_000L, result.expiresAtMillis)
        assertEquals(session.selectedDeviceId, result.selectedDeviceId)
        assertEquals("private-refresh", result.refreshToken)
        val body = JSONObject(http.body)
        assertEquals("REFRESH_TOKEN_AUTH", body.getString("AuthFlow"))
        assertEquals("publicClient123", body.getString("ClientId"))
        assertEquals("private-refresh", body.getJSONObject("AuthParameters").getString("REFRESH_TOKEN"))
        assertFalse(http.body.contains("password"))
        http.failure = PolarisHttpResponse(400, """{"__type":"NotAuthorizedException","message":"private-token"}""")
        try { backend.refreshSession(session); fail("Expired refresh token requires sign-in") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.AUTH, error.kind); assertFalse(error.toString().contains("private-token")) }
    }
    @Test fun rateLimitsPreserveRetryAfterAndWrongSignedEndpointFailsBeforeSocketOpens() = runBlocking {
        val http = Http { FixtureSocket() }
        var opened = 0
        val backend = TraegerBackend(http, GrillSocketFactory { _, _ -> opened++; FixtureSocket() })
        val session = backend.signIn("synthetic@example.test", "password").value
        val id = backend.devices(session).value.single().id
        http.signedUrl = "wss://untrusted.test/mqtt?token=secret"
        try { backend.readings(session, id); fail("Untrusted host") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.SCHEMA, error.kind) }
        assertEquals(0, opened)
        http.failure = PolarisHttpResponse(429, "private-response", 90_000)
        try { backend.devices(session); fail("Rate limit") }
        catch (error: PolarisFailure) { assertEquals(PolarisFailureKind.RATE_LIMIT, error.kind); assertEquals(90_000L, error.retryAfterMillis) }
    }
}
