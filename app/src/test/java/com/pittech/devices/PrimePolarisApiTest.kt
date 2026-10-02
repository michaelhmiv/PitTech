package com.pittech.devices

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PrimePolarisApiTest {
    private data class Request(val operation: PolarisOperation, val body: JSONObject, val token: String?)
    private class Transport(var response: PolarisHttpResponse) : PolarisHttpTransport {
        val requests = mutableListOf<Request>()
        override suspend fun post(operation: PolarisOperation, body: String, token: String?): PolarisHttpResponse {
            requests += Request(operation, JSONObject(body), token)
            return response
        }
    }
    private fun ok(data: String) = PolarisHttpResponse(200, "{\"respCode\":10000,\"data\":$data}")
    private fun readings(data: String): PolarisPayload = runBlocking {
        PrimePolarisApi(Transport(ok(data))).readings(PolarisSession("private-token"), "private-device").value
    }

    @Test fun emailCodeUsesOnlyExplicitUnauthenticatedOperation() = runBlocking {
        val transport = Transport(ok("null"))
        PrimePolarisApi(transport).requestCode(" user@example.com ")
        assertEquals(1, transport.requests.size)
        assertEquals(PolarisOperation.REQUEST_CODE, transport.requests.single().operation)
        assertNull(transport.requests.single().token)
        assertEquals("user@example.com", transport.requests.single().body.getString("email"))
        assertEquals("Login", transport.requests.single().body.getString("emailType"))
    }

    @Test fun emailAndOtpValidationPreventsAnyRequest() = runBlocking {
        val transport = Transport(ok("{}"))
        try { PrimePolarisApi(transport).requestCode("not-an-email"); fail() } catch (_: IllegalArgumentException) { }
        try { PrimePolarisApi(transport).signIn("test@example.com", "12345"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun signInExtractsExpiryWithoutExposingTokenInStringRepresentation() = runBlocking {
        val jwt = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":2000000000}".toByteArray()) + ".signature"
        val transport = Transport(ok("{\"token\":\"$jwt\"}"))
        val session = PrimePolarisApi(transport).signIn("test@example.com", "123456").value
        assertEquals(2_000_000_000_000L, session.expiresAtMillis)
        assertFalse(session.toString().contains(jwt))
        assertNull(transport.requests.single().token)
    }

    @Test fun discoversDevicesWithoutRequiringUserToKnowAnId() = runBlocking {
        val transport = Transport(ok("{\"list\":[{\"id\":123,\"deviceName\":\"My grill\",\"modelName\":\"P7\"},{\"id\":\"abc\"}]}"))
        val devices = PrimePolarisApi(transport).devices(PolarisSession("secret")).value
        assertEquals(listOf("123", "abc"), devices.map { it.id })
        assertEquals("P7", devices.first().controllerModel)
        assertEquals("secret", transport.requests.single().token)
    }

    @Test fun nullableAndMissingReadingsDoNotBecomeZeroOrFahrenheit() {
        val payload = readings("{\"furnaceTempMeasured\":null,\"probeP1Measured\":\"212.5\",\"probeP2Measured\":false,\"privateDeviceId\":\"hidden\"}")
        assertFalse(payload.values.containsKey("furnaceTempMeasured"))
        assertFalse(payload.values.containsKey("probeP2Measured"))
        assertEquals(212.5, payload.values["probeP1Measured"]!!, 0.01)
        assertEquals(1, payload.unknownFieldCount)
        assertFalse(payload.recognizedFields.contains("privateDeviceId"))
        assertEquals("212.5 (unit unknown)", PolarisMonitorPolicy.temperature(PolarisSample(1, payload), "probeP1Measured"))
    }

    @Test fun rejectsSentinelTemperaturesAndFractionalEnumsButPreservesCelsius() {
        val payload = readings("{\"furnaceTempMeasured\":32767,\"probeP1Measured\":82,\"tempUnit\":1,\"onlineStatus\":0.4,\"alarmEvent\":[{\"secret\":\"hidden\"}]}")
        assertFalse(payload.values.containsKey("furnaceTempMeasured"))
        assertFalse(payload.values.containsKey("onlineStatus"))
        assertEquals("82 °C", PolarisMonitorPolicy.temperature(PolarisSample(1, payload), "probeP1Measured"))
        assertEquals(1, payload.alarmCount)
    }

    @Test fun authenticationAndDisplacedSessionsRemainDistinctFromTransportErrors() = runBlocking {
        for (code in listOf(-10001, -10002, -10003, -10007, -10108)) {
            val transport = Transport(PolarisHttpResponse(200, "{\"respCode\":$code,\"respMessage\":\"secret-token private-email\"}"))
            try { PrimePolarisApi(transport).devices(PolarisSession("private")); fail() }
            catch (failure: PolarisFailure) {
                assertEquals(PolarisFailureKind.AUTH, failure.kind)
                assertEquals(code, failure.apiCode)
                assertFalse(failure.userMessage.contains("private-email"))
            }
        }
    }

    @Test fun missingSuccessEnvelopeAndUnexpectedListShapeAreRejected() = runBlocking {
        for (body in listOf("{}", "not-json", "{\"respCode\":10000,\"data\":[]}", "{\"respCode\":10000,\"data\":{}}")) {
            val transport = Transport(PolarisHttpResponse(200, body))
            try { PrimePolarisApi(transport).devices(PolarisSession("private")); fail() }
            catch (failure: PolarisFailure) { assertEquals(PolarisFailureKind.SCHEMA, failure.kind) }
        }
    }

    @Test fun rateLimitRetainsRetryDelayAndDoesNotReplayRequest() = runBlocking {
        val transport = Transport(PolarisHttpResponse(429, "credentials should never be logged", 90_000))
        try { PrimePolarisApi(transport).devices(PolarisSession("private")); fail() }
        catch (failure: PolarisFailure) {
            assertEquals(PolarisFailureKind.RATE_LIMIT, failure.kind)
            assertEquals(90_000L, failure.retryAfterMillis)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun expiredTokensAreNotSentToBackend() = runBlocking {
        val transport = Transport(ok("{\"list\":[]}"))
        try { PrimePolarisApi(transport).devices(PolarisSession("private", 1)); fail() }
        catch (failure: PolarisFailure) { assertEquals(PolarisFailureKind.AUTH, failure.kind) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun publicReportExcludesIdentitiesAndPreservesUsefulConnectionFacts() {
        val payload = readings("{\"furnaceTempMeasured\":225,\"tempUnit\":0,\"email\":\"person@example.com\",\"token\":\"secret\"}")
        val state = PolarisMonitorState(
            devices = listOf(PolarisDevice("private-device-id", "private-device-name", null, null, null, null)),
            selectedDeviceId = "private-device-id", latest = PolarisSample(1, payload), samples = listOf(PolarisSample(1, payload)),
            exchanges = listOf(PolarisExchange(1, PolarisOperation.READINGS, 84, 200, 10000)),
        )
        val report = PolarisMonitorPolicy.report(state, 2)
        for (secret in listOf("person@example.com", "secret", "private-device-id", "private-device-name")) assertFalse(report.contains(secret))
        assertTrue(report.contains("furnaceTempMeasured=225"))
        assertTrue(report.contains("duration=84ms HTTP=200 API=10000"))
    }

    @Test fun monitoringPolicySeparatesOnlineZeroOfflineUnknownAndStale() {
        assertEquals("Grill online", PolarisMonitorState(onlineStatus = 0, statusFetchedAtMillis = 100).onlineLabel(1000))
        assertTrue(PolarisMonitorState(onlineStatus = 1, statusFetchedAtMillis = 100).onlineLabel(1000).contains("offline"))
        assertTrue(PolarisMonitorState().onlineLabel(1000).contains("not reported"))
        assertTrue(PolarisMonitorState(onlineStatus = 0, statusFetchedAtMillis = 100).onlineLabel(100000).contains("old"))
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L), (0..3).map { PolarisMonitorPolicy.nextDelay(it) })
        assertEquals(900_000L, PolarisMonitorPolicy.nextDelay(100, 900_000))
    }
}
