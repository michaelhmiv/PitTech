package com.pittech.devices

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Small, read-only Pit Boss relay protocol probe.
 *
 * This is an independent Kotlin implementation informed by public protocol
 * observations and the Apache-2.0 pytboss project. It intentionally exposes
 * only RPC.Ping; it does not provide grill-control commands.
 */
enum class PitBossRelayMessageKind(val title: String) {
    CONTROLLER_STATUS("Controller status"),
    RPC_RESPONSE("RPC response"),
    RPC_ERROR("RPC error"),
    OTHER("Relay message"),
    MALFORMED("Unparseable message"),
}

data class PitBossRelayMessage(
    val kind: PitBossRelayMessageKind,
    val safeJson: String,
)

object PitBossRelayProtocol {
    private const val HOST = "socket.dansonscorp.com"
    const val PING_REQUEST_ID = 1

    /**
     * Pit Boss BLE advertisements use the controller's device ID as their
     * local name. Keep the advertised name as the relay ID, normalized to the
     * uppercase form used by the vendor app.
     */
    fun identifierFromBluetoothName(advertisedName: String): String? {
        val normalized = advertisedName.trim().uppercase()
        if (normalized.isBlank() || normalized.startsWith("-") || normalized.endsWith("-")) {
            return null
        }
        return normalized.takeIf { it.contains('-') }
    }

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

    fun inspectMessage(raw: String): PitBossRelayMessage {
        return try {
            val json = JSONObject(raw)
            val kind = when {
                json.has("status") -> PitBossRelayMessageKind.CONTROLLER_STATUS
                json.has("error") -> PitBossRelayMessageKind.RPC_ERROR
                json.has("id") && json.has("result") -> PitBossRelayMessageKind.RPC_RESPONSE
                else -> PitBossRelayMessageKind.OTHER
            }
            redactSensitiveValues(json)
            PitBossRelayMessage(kind, json.toString(2))
        } catch (_: JSONException) {
            // Never show an unparseable raw frame; it may contain sensitive data.
            PitBossRelayMessage(
                PitBossRelayMessageKind.MALFORMED,
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
