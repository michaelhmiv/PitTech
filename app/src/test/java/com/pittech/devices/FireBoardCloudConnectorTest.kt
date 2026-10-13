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
}
