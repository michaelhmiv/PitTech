package com.pittech.devices

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PitBossCloudProtocolTest {
    @Test
    fun relayUrlUsesGrillIdPathAndEncodesItAsOneSegment() {
        assertEquals(
            "wss://socket.dansonscorp.com/to/grill%20one%2Fpart",
            PitBossCloudProtocol.webSocketUrl(" grill one/part "),
        )
        assertEquals(
            "wss://socket.dansonscorp.com/to/PBL-3B22CD",
            PitBossCloudProtocol.webSocketUrl("PBL-3B22CD"),
        )
    }

    @Test
    fun pingPayloadUsesRpcPingAndSessionId() {
        val payload = JSONObject(PitBossCloudProtocol.pingPayload("abc123"))
        assertEquals(1, payload.getInt("id"))
        assertEquals("RPC.Ping", payload.getString("method"))
        assertEquals("abc123", payload.getString("app_id"))
        assertTrue(payload.getJSONObject("params").length() == 0)
    }

    @Test
    fun statusFrameIsRecognizedAndPasswordLikeFieldsAreRedacted() {
        val message = PitBossCloudProtocol.inspectMessage(
            """{"status":["FE0B00"],"data":{"psw":"private-grill-value","probe":1}}""",
        )

        assertEquals(PitBossCloudMessageKind.CONTROLLER_STATUS, message.kind)
        assertTrue(message.safeJson.contains("[redacted]"))
        assertFalse(message.safeJson.contains("private-grill-value"))
    }

    @Test
    fun malformedFramesAreNotEchoedIntoTheDebugScreen() {
        val message = PitBossCloudProtocol.inspectMessage("secret-that-is-not-json")

        assertEquals(PitBossCloudMessageKind.MALFORMED, message.kind)
        assertFalse(message.safeJson.contains("secret-that-is-not-json"))
    }
}
