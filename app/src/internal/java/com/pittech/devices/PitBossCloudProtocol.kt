package com.pittech.devices

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Small, read-only Pit Boss vendor-cloud protocol adapter.
 *
 * This is an independent Kotlin implementation informed by public protocol
 * observations and the Apache-2.0 pytboss project. It intentionally exposes
 * only RPC.Ping; it does not provide grill-control commands.
 */
enum class PitBossCloudMessageKind(val title: String) {
    CONTROLLER_STATUS("Controller status"),
    RPC_RESPONSE("RPC response"),
    RPC_ERROR("RPC error"),
    OTHER("Relay message"),
    MALFORMED("Unparseable message"),
}

data class PitBossCloudMessage(
    val kind: PitBossCloudMessageKind,
    val safeJson: String,
)

object PitBossCloudProtocol {
    private const val HOST = "socket.dansonscorp.com"
    const val PING_REQUEST_ID = 1

    fun webSocketUrl(grillId: String): String {
        val normalizedId = grillId.trim()
        require(normalizedId.isNotEmpty()) { "A controller identifier is required." }

        val encodedPath = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegment("to")
            .addPathSegment(normalizedId)
            .build()
            .encodedPath

        return "wss://" + HOST + encodedPath
    }

    fun pingPayload(appId: String, requestId: Int = PING_REQUEST_ID): String {
        require(appId.isNotBlank()) { "A session ID is required." }
        return JSONObject()
            .put("id", requestId)
            .put("method", "RPC.Ping")
            .put("params", JSONObject())
            .put("app_id", appId)
            .toString()
    }

    fun inspectMessage(raw: String): PitBossCloudMessage {
        return try {
            val json = JSONObject(raw)
            val kind = when {
                json.has("status") -> PitBossCloudMessageKind.CONTROLLER_STATUS
                json.has("error") -> PitBossCloudMessageKind.RPC_ERROR
                json.has("id") && json.has("result") -> PitBossCloudMessageKind.RPC_RESPONSE
                else -> PitBossCloudMessageKind.OTHER
            }
            redactSensitiveValues(json)
            PitBossCloudMessage(kind, json.toString(2))
        } catch (_: JSONException) {
            // Never show an unparseable raw frame; it may contain sensitive data.
            PitBossCloudMessage(
                PitBossCloudMessageKind.MALFORMED,
                "Received an unparseable message (" + raw.length + " characters).",
            )
        }
    }

    private fun redactSensitiveValues(value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys += iterator.next()

                keys.forEach { key ->
                    if (SENSITIVE_KEY_FRAGMENTS.any { key.lowercase().contains(it) }) {
                        value.put(key, REDACTED)
                    } else {
                        redactSensitiveValues(value.opt(key))
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    redactSensitiveValues(value.opt(index))
                }
            }
        }
    }

    private val SENSITIVE_KEY_FRAGMENTS = listOf(
        "password",
        "passphrase",
        "psw",
        "token",
        "authorization",
        "secret",
        "ssid",
        "email",
    )
    private const val REDACTED = "[redacted]"
}
