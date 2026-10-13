package com.pittech.devices

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FireBoardCloudConnectorTest {
    private val at = Instant.parse("2026-10-12T20:00:00Z").toEpochMilli()
    private fun fixture(): JSONArray {
        val latest = JSONArray().put(JSONObject().put("channel", 1).put("temp", 100.0)
            .put("degreetype", 1).put("created", "2026-10-12T20:00:00Z"))
            .put(JSONObject().put("channel", 2).put("temp", 210.0)
                .put("degreetype", 2).put("created", "2026-10-12T20:00:00Z"))
        val channels = JSONArray().put(JSONObject().put("channel", 1).put("channel_label", "Pork"))
            .put(JSONObject().put("channel", 2).put("channel_label", "Chamber"))
        return JSONArray().put(JSONObject().put("uuid", "fb1").put("title", "Patio smoker")
            .put("channels", channels).put("latest_temps", latest))
    }

    @Test fun preservesChannelIdentityAndDegrees() {
        val result = FireBoardCloudConnector.parseTemps(fixture().getJSONObject(0), at + 1000)
        assertEquals(listOf("channel1", "channel2"), result.map { it.channelId })
        assertEquals("°C", result[0].unit)
        assertEquals("°F", result[1].unit)
        assertEquals(212.0, TelemetryNormalizer.normalize(result[0])!!.fahrenheit, .01)
    }

    @Test fun staleAndUnknownTemperatureUnitsAreNotAccepted() {
        val value = FireBoardCloudConnector.parseTemps(fixture().getJSONObject(0), at + 61_000)
        assertTrue(value.all { it.quality == TelemetryQuality.STALE })
        assertNull(TelemetryNormalizer.normalize(value.first()))
        val malformed = fixture().getJSONObject(0)
        malformed.getJSONArray("latest_temps").getJSONObject(0).put("degreetype", 99)
        assertEquals(1, FireBoardCloudConnector.parseTemps(malformed, at).size)
    }

    @Test fun oneAccountFetchFeedsAllProbesAndRespectsGlobalRateBudget() = runBlocking {
        var now = at
        var count = 0
        val connector = FireBoardCloudConnector("token", object : FireBoardTransport {
            override suspend fun devices(token: String): JSONArray {
                assertEquals("token", token)
                count++
                return fixture()
            }
        }) { now }
        val device = connector.discover().single()
        assertEquals("Patio smoker", device.label)
        assertEquals(2, device.channels.size)
        assertEquals(2, connector.snapshot("fb1").size)
        assertEquals(1, count)
        now += 30_000
        assertEquals(2, connector.snapshot("fb1").size)
        assertEquals(2, count)
        connector.disconnect()
        try { connector.snapshot("fb1"); fail("Disconnected token was used") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun accountWideCacheIsSharedAcrossIndependentDeviceScreens() = runBlocking {
        val now = at
        var one = 0
        var two = 0
        val adapter = object : FireBoardTransport {
            override suspend fun devices(token: String): JSONArray {
                assertEquals("shared-fireboard-a", token)
                one++
                return fixture()
            }
        }
        val other = object : FireBoardTransport {
            override suspend fun devices(token: String): JSONArray {
                assertEquals("shared-fireboard-b", token)
                two++
                return fixture()
            }
        }
        val first = FireBoardCloudConnector("shared-fireboard-a", adapter) { now }
        val second = FireBoardCloudConnector("shared-fireboard-a", adapter) { now }
        val independent = FireBoardCloudConnector("shared-fireboard-b", other) { now }
        assertEquals(1, first.discover().size)
        assertEquals(2, second.snapshot("fb1").size)
        assertEquals(1, one)
        assertEquals(1, independent.discover().size)
        assertEquals(1, two)
    }
    @Test fun outageAnd429AcrossInstancesCannotBypassTheSharedQuota() = runBlocking {
        var now = 1_760_000_000_000L
        var calls = 0
        val transport = object : FireBoardTransport {
            override suspend fun devices(token: String): JSONArray {
                calls++
                throw TelemetryRequestException(TelemetryFailureKind.RATE_LIMIT, now + 300_000)
            }
        }
        val first = FireBoardCloudConnector("quota-failure-test", transport) { now }
        val second = FireBoardCloudConnector("quota-failure-test", transport) { now }
        try { first.discover(); fail("Failure missing") } catch (_: TelemetryRequestException) {}
        now += 30_000
        try { second.discover(); fail("Cooldown ignored") } catch (_: TelemetryRequestException) {}
        assertEquals(1, calls)
    }

    @Test fun documentedChartHistoryRejectsAmbiguousLabelsAndPreservesSourceTimes() {
        val row = JSONObject().put("device", "fb1").put("label", "Pork").put("degreetype", 1)
            .put("x", JSONArray().put(at / 1000L).put((at + 1000) / 1000L))
            .put("y", JSONArray().put(90.0).put(JSONObject.NULL))
        val chart = JSONArray().put(row)
        val result = FireBoardCloudConnector.parseHistory(chart, "fb1", mapOf("channel1" to "Pork"), at, at + 5000, at + 6000)
        assertEquals(1, result.size)
        assertEquals(at, result.single().observedAtMillis)
        assertTrue(result.single().fromHistory)
        assertTrue(FireBoardCloudConnector.parseHistory(chart, "fb1", mapOf("channel1" to "Pork", "channel2" to "Pork"), at, at + 5000, at + 6000).isEmpty())
        assertTrue(FireBoardCloudConnector.parseHistory(chart, "different-device", mapOf("channel1" to "Pork"), at, at + 5000, at + 6000).isEmpty())
    }

    @Test fun overlappingLiveAndHistoryResponsesHaveTheSameEventIdentity() {
        val live = FireBoardCloudConnector.parseTemps(fixture().getJSONObject(0), at).first()
        val history = JSONArray().put(JSONObject().put("device", "fb1").put("label", "Pork").put("degreetype", 1)
            .put("x", JSONArray().put(at / 1000L)).put("y", JSONArray().put(100.0)))
        val recovered = FireBoardCloudConnector.parseHistory(history, "fb1", mapOf("channel1" to "Pork"), at, at, at).single()
        assertEquals(TelemetryNormalizer.normalize(live)!!.eventKey, TelemetryNormalizer.normalize(recovered)!!.eventKey)
    }

}
