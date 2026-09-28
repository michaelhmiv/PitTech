package com.pittech.devices

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object ControllerDiagnosticSanitizer {
    private val macAddress = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")

    private val sensitiveFragments = listOf(
        "password", "passphrase", "psw", "secret", "token", "authorization",
        "ssid", "bssid", "username", "email", "certificate", "private_key", "privatekey",
    )

    fun sanitizeJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        return try {
            when {
                trimmed.startsWith("{") -> sanitizeText(JSONObject(trimmed).also(::redact).toString())
                trimmed.startsWith("[") -> sanitizeText(JSONArray(trimmed).also(::redact).toString())
                else -> sanitizeText(trimmed)
            }
        } catch (_: JSONException) {
            sanitizeText(trimmed)
        }
    }

    fun sanitizeText(raw: String): String {
        var value = raw
        value = macAddress.replace(value, "[bluetooth-address-redacted]")
        sensitiveFragments.forEach { key ->
            val quoted = Regex("(?i)([\"']?$key[\"']?\\s*[:=]\\s*)([\"'][^\"']*[\"']|[^,;\\s}]+)")
            value = value.replace(quoted) { match -> match.groupValues[1] + "[redacted]" }
        }
        return value.take(MAX_TEXT_CHARS)
    }

    private fun redact(value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys += iterator.next()
                keys.forEach { key ->
                    if (sensitiveFragments.any { key.lowercase().contains(it) }) {
                        value.put(key, "[redacted]")
                    } else {
                        redact(value.opt(key))
                    }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) redact(value.opt(index))
        }
    }

    private const val MAX_TEXT_CHARS = 2_000
}
