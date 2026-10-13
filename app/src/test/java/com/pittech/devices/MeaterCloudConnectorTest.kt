package com.pittech.devices

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeaterCloudConnectorTest {
    private fun response(now: Long) = JSONObject().put("statusCode", 200).put("data",
        JSONObject().put("devices", org.json.JSONArray().put(
            JSONObject().put("id", "probe01").put("updated_at", now / 1000L)
                .put("temperature", JSONObject().put("internal", 100.0).put("ambient", 120.0))
                .put("cook", JSONObject().put("temperature", JSONObject().put("target", 105.0)))
        )))

    @Test fun parsingPreservesCelsiusAndSourceTimestamp() {
        val raw = response(1_700_000_000_000L).getJSONObject("data").getJSONArray("devices").getJSONObject(0)
        val parsed = MeaterCloudConnector.parseDevice(raw, 1_700_000_001_000L)
        assertEquals(3, parsed.size)
        assertEquals(TelemetryQuality.VALID, parsed.first().quality)
        assertEquals("°C", parsed.first().unit)
        assertEquals(100.0, parsed.first().value!!, 0.001)
        assertEquals(1_700_000_000_000L, parsed.first().observedAtMillis)
        assertEquals("target", parsed.last().channelId)
    }

    @Test fun cloudStalenessAndMissingProbeNeverBecomeValidData() {
        val obj = JSONObject().put("updated_at", 1000L)
            .put("temperature", JSONObject().put("internal", JSONObject.NULL))
        val observations = MeaterCloudConnector.parseDevice(obj, 1_300_000L)
        assertEquals(TelemetryQuality.STALE, observations.first().quality)
        assertNull(observations.first().value)
        assertNull(TelemetryNormalizer.normalize(observations.first()))
    }

    @Test fun globalCachePreventsPerProbeOverPolling() = runBlocking {
        var requests = 0
        val transport = object : MeaterTransport {
            override suspend fun devices(jwt: String): JSONObject {
                assertEquals("test-token", jwt)
                requests++
                return response(1_700_000_000_000L)
            }
        }
        var now = 1_700_000_000_000L
        val client = MeaterCloudConnector("test-token", transport) { now }
        assertEquals(1, client.discover().size)
        assertEquals(3, client.snapshot("probe01").size)
        assertEquals(1, requests)
        now += 29_999
        client.snapshot("probe01")
        assertEquals(1, requests)
        now += 1
        client.snapshot("probe01")
        assertEquals(2, requests)
        client.disconnect()
        try { client.discover(); fail("Disconnected session queried") } catch (_: IllegalArgumentException) {}
    }

    @Test fun unknownDeviceAndMissingHistoryAreExplicit() = runBlocking {
        val connector = MeaterCloudConnector("test", object : MeaterTransport {
            override suspend fun devices(jwt: String) = response(1_700_000_000_000L)
        }) { 1_700_000_000_000L }
        assertTrue(connector.snapshot("not-present").isEmpty())
        assertTrue(connector.history("probe01", 0, 1000).isEmpty())
    }

    @Test fun differentConnectorsForSameAccountShareOneQuotaWithoutMixingAccounts() = runBlocking {
        val time = 1_750_000_000_000L
        var firstCalls = 0
        var secondCalls = 0
        val first = object : MeaterTransport {
            override suspend fun devices(jwt: String): JSONObject {
                assertEquals("shared-meater-account-a", jwt)
                firstCalls++
                return response(time)
            }
        }
        val other = object : MeaterTransport {
            override suspend fun devices(jwt: String): JSONObject {
                assertEquals("shared-meater-account-b", jwt)
                secondCalls++
                return response(time)
            }
        }
        val a = MeaterCloudConnector("shared-meater-account-a", first) { time }
        val b = MeaterCloudConnector("shared-meater-account-a", first) { time }
        val c = MeaterCloudConnector("shared-meater-account-b", other) { time }
        assertEquals(1, a.discover().size)
        assertEquals(3, b.snapshot("probe01").size)
        assertEquals(1, firstCalls)
        assertEquals(1, c.discover().size)
        assertEquals(1, secondCalls)
    }
    @Test fun outageAnd429AcrossInstancesCannotBypassTheSharedQuota() = runBlocking {
        var now = 1_760_000_000_000L
        var calls = 0
        val transport = object : MeaterTransport {
            override suspend fun devices(jwt: String): JSONObject {
                calls++
                throw TelemetryRequestException(TelemetryFailureKind.RATE_LIMIT, now + 300_000)
            }
        }
        val first = MeaterCloudConnector("quota-failure-test", transport) { now }
        val second = MeaterCloudConnector("quota-failure-test", transport) { now }
        try { first.discover(); fail("Failure missing") } catch (_: TelemetryRequestException) {}
        now += 30_000
        try { second.discover(); fail("Cooldown ignored") } catch (_: TelemetryRequestException) {}
        assertEquals(1, calls)
    }

}
