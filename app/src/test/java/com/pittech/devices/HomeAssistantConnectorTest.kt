package com.pittech.devices

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeAssistantConnectorTest {
    private fun state(value: String, date: String) = JSONObject()
        .put("entity_id", "sensor.patio_probe").put("state", value)
        .put("last_updated", date).put("last_changed", date)
        .put("attributes", JSONObject().put("friendly_name", "Patio thermometer")
            .put("device_class", "temperature").put("unit_of_measurement", "°C"))

    @Test fun acceptsHttpsRemoteAndLocalHttpButRejectsInsecureRemote() {
        assertEquals("https://example.com/api/states", HomeAssistantEndpoint("https://example.com").readPath("api/states"))
        assertEquals("ws://homeassistant.local:8123/api/websocket", HomeAssistantEndpoint("http://homeassistant.local:8123").websocket)
        for (url in listOf("http://10.attacker.com", "http://192.168.evil.com",
            "http://172.16.attacker.com", "http://10.12.999.2",
            "https://example.com/unexpected-path")) {
            try { HomeAssistantEndpoint(url); fail("Accepted unsafe endpoint: " + url) }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals("http://10.0.0.20:8123/api/states",
            HomeAssistantEndpoint("http://10.0.0.20:8123").readPath("api/states"))
        try { HomeAssistantEndpoint("http://public.example.com"); fail("public plain HTTP accepted") }
        catch (_: IllegalArgumentException) {}
        try { HomeAssistantEndpoint("https://example.com").readPath("api/services/light/turn_on"); fail("service write accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun parsesRecordedSourceTimeAndUnavailableValues() {
        val timestamp = "2026-10-12T13:10:00Z"
        val o = HomeAssistantPayload.reading(state("22.5", timestamp), 1_760_000_000_000L, true)!!
        assertEquals("temperature", o.channelId)
        assertEquals(22.5, o.value!!, .01)
        assertEquals(java.time.Instant.parse(timestamp).toEpochMilli(), o.observedAtMillis)
        assertTrue(o.fromHistory)
        assertEquals(TelemetryQuality.UNAVAILABLE,
            HomeAssistantPayload.reading(state("unavailable", timestamp), 1_760_000_000_000L, false)!!.quality)
        assertNull(TelemetryNormalizer.normalize(HomeAssistantPayload.reading(state("unavailable", timestamp), 1_760_000_000_000L, false)!!))
    }

    @Test fun discoverSkipsUnrelatedAndUnqualifiedEntities() = runBlocking {
        val date = "2026-10-12T13:10:00Z"
        val mixed = org.json.JSONArray()
            .put(state("22.5", date))
            .put(JSONObject().put("entity_id", "switch.grill").put("state", "on")
                .put("attributes", JSONObject().put("unit_of_measurement", "°C")))
        val rest = object : HomeAssistantRest {
            override suspend fun get(url: String, token: String): String {
                assertEquals("test-token", token)
                return mixed.toString()
            }
        }
        val client = HomeAssistantConnector("https://ha.example.com", "test-token", rest)
        val sources = client.discover()
        assertEquals(1, sources.size)
        assertEquals("sensor.patio_probe", sources.single().deviceId)
        assertEquals("home_assistant", sources.single().connectorId)
    }

    @Test fun historyIsBoundedAndNeverCreatesFixedIntervalSamples() = runBlocking {
        val date = "2026-10-12T13:10:00Z"
        val outer = org.json.JSONArray().put(org.json.JSONArray().put(state("21.0", date)).put(state("21.5", date)))
        val rest = object : HomeAssistantRest {
            override suspend fun get(url: String, token: String): String {
                assertTrue(url.contains("api/history/period/"))
                assertTrue(url.contains("filter_entity_id=sensor.patio_probe"))
                return outer.toString()
            }
        }
        val client = HomeAssistantConnector("https://ha.example.com", "test-token", rest)
        val values = client.history("sensor.patio_probe", 1_760_000_000_000L, 1_760_000_600_000L)
        assertEquals(2, values.size)
        assertTrue(values.all { it.fromHistory })
        try { client.history("sensor.patio_probe", 1, 4 * 86_400_000L); fail("Unbounded history") }
        catch (_: IllegalArgumentException) {}
    }
}
