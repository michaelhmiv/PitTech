package com.pittech.devices

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class HomeAssistantWebSocketTest {
    @Test fun oneAuthenticatedSocketMultiplexesTemperatureSensorsWithoutServiceCalls() = runBlocking {
        val server = MockWebServer()
        val outgoing = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send("{\"type\":\"auth_required\"}") }
            override fun onMessage(socket: WebSocket, text: String) {
                val json = JSONObject(text); outgoing.add(json.getString("type"))
                if (json.optString("type") == "auth") {
                    assertEquals("test-token", json.getString("access_token")); socket.send("{\"type\":\"auth_ok\"}")
                } else if (json.optString("type") == "subscribe_events") {
                    assertEquals("state_changed", json.getString("event_type"))
                    socket.send("{\"id\":1,\"type\":\"result\",\"success\":true}")
                    for ((id, value) in listOf("sensor.a" to "90", "sensor.b" to "100")) {
                        val state = JSONObject().put("entity_id", id).put("state", value).put("last_updated", Instant.now().toString())
                            .put("attributes", JSONObject().put("unit_of_measurement", "°C").put("device_class", "temperature"))
                        socket.send(JSONObject().put("type", "event").put("event", JSONObject().put("data", JSONObject().put("entity_id", id).put("new_state", state))).toString())
                    }
                } else fail("Non-observational WebSocket operation")
            }
        }).build())
        server.start()
        val client = OkHttpClient()
        val connector = HomeAssistantConnector(server.url("/").toString(), "test-token", wsClient = client)
        try {
            val a = async { withTimeout(10_000) { connector.observe("sensor.a").first() } }
            val b = async { withTimeout(10_000) { connector.observe("sensor.b").first() } }
            val result = awaitAll(a, b).map { it.single().value }.toSet()
            assertEquals(setOf(90.0, 100.0), result)
            assertEquals(listOf("auth", "subscribe_events"), outgoing.toList())
            assertEquals(1, server.requestCount)
        } finally { connector.disconnect(); server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
