package com.pittech.devices

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Central redaction boundary for text that may enter a public GitHub issue. */
internal object ControllerDiagnosticSanitizer {
    private val macAddress = Regex(
        "(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f])|(?<![0-9a-f])[0-9a-f]{12}(?![0-9a-f])",
    )
    private val ipv4Address = Regex(
        """(?<![0-9.])(?:(?:10|127)\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})|(?:169\.254|192\.168)\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})|172\.(?:1[6-9]|2[0-9]|3[01])\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2}))(?![0-9.])""",
    )
    private val ipv6Address = Regex(
        """(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){1,7}:[0-9a-f:]{0,20}(?![0-9a-f:])|(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![0-9a-f:])""",
    )
    private val sensitiveKeyPattern =
        "password|passwd|passphrase|wifi\\.sta\\.pass|psw|secret|token|authorization|credential|ssid|bssid|username|email|certificate|private[ _-]?key|auth|serial|device[_.-]?id|grill[_.-]?id|relay[_.-]?id|unit[_.-]?id|unique[_.-]?id|eui(?:48|64)?"
    private val sensitiveFragments = listOf(
        "password", "passwd", "passphrase", "wifi.sta.pass", "psw", "secret", "token",
        "authorization", "credential", "ssid", "bssid", "username", "email", "certificate",
        "private key", "private_key", "privatekey", "private-key", "auth", "serial", "device_id", "deviceid", "device.id",
        "grill_id", "grillid", "grill.id", "relay_id", "relayid", "relay.id", "unit_id", "unitid", "unique_id", "uniqueid", "eui",
    )
    private val keyValueSecret = Regex(
        """(?i)(["']?[a-z0-9_.-]*(?:$sensitiveKeyPattern)[a-z0-9_.-]*["']?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'[^']*'|(?:(?!\s+[a-z0-9_.-]+\s*[:=])[^,;\r\n}\]])+)""",
    )
    private val labelOnlySecret = Regex(
        """(?im)(\b(?:$sensitiveKeyPattern)\b\s+is\s+)(?:"[^"]*"|'[^']*'|(?:(?!\s+[a-z0-9_.-]+\s*[:=])[^,;\r\n])+)""",
    )
    private val namedMacValue = Regex(
        """(?i)([\"']?(?:bluetooth[_-]?address|device[_-]?address|mac(?:[_-]?address)?|address)[\"']?\s*[:=]\s*)([\"']?)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\2""",
    )
    private val recognizedNamePrefix = Regex("(?i)^(pit\\s?boss|pitboss|pbl|mongoose|dansons)(?:$|[-_\\s].*)")

    /** Sanitizes JSON keys and string values recursively without changing its structure. */
    fun sanitizeJson(raw: String, maxChars: Int = MAX_TEXT_CHARS): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).also { redact(it) }.toString()
                trimmed.startsWith("[") -> JSONArray(trimmed).also { redact(it) }.toString()
                else -> sanitizeText(trimmed, maxChars)
            }
                .let {
                    ipv6Address.replace(
                        ipv4Address.replace(macAddress.replace(it, "[bluetooth-address-redacted]"), "[ip-address-redacted]"),
                        "[ip-address-redacted]",
                    )
                        .take(maxChars.coerceAtLeast(0))
                }
        } catch (_: JSONException) {
            sanitizeText(trimmed, maxChars)
        }
    }

    /** Handles JSON fragments and ordinary debug/log text such as `SSID: Home WiFi`. */
    fun sanitizeText(raw: String, maxChars: Int = MAX_TEXT_CHARS): String {
        var value = raw
        value = namedMacValue.replace(value) { match -> match.groupValues[1] + "[bluetooth-address-redacted]" }
        value = keyValueSecret.replace(value) { match -> match.groupValues[1] + "[redacted]" }
        value = labelOnlySecret.replace(value) { match -> match.groupValues[1] + "[redacted]" }
        value = macAddress.replace(value, "[bluetooth-address-redacted]")
        value = ipv4Address.replace(value, "[ip-address-redacted]")
        value = ipv6Address.replace(value, "[ip-address-redacted]")
        return value.take(maxChars.coerceAtLeast(0))
    }

    /** Keep useful family labels while withholding arbitrary user-assigned BLE names. */
    fun sanitizeAdvertisedName(name: String?): String {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() } ?: return "(not advertised)"
        val match = recognizedNamePrefix.find(normalized)
            ?: return "present (value withheld for privacy)"
        val family = match.groupValues[1].replace(Regex("\\s+"), " ")
        return if (normalized.equals(family, ignoreCase = true)) family else "$family (device suffix withheld)"
    }

    /** Only recognized product-family tokens enter a stable public support fingerprint. */
    fun advertisedNameFingerprintSignal(name: String?): String? {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = recognizedNamePrefix.find(normalized)
        return if (match == null) "name-present" else "name-family:${match.groupValues[1].uppercase()}"
    }

    fun sanitizeSections(sections: List<RawDiagnosticSection>): SanitizedDiagnosticEvidence =
        SanitizedDiagnosticEvidence(
            sections.map { section ->
                SanitizedDiagnosticSection(
                    title = sanitizeText(section.title, MAX_SECTION_TITLE_CHARS),
                    priority = section.priority,
                    text = sanitizeText(section.text, Int.MAX_VALUE),
                )
            },
        )

    private fun redact(value: Any?, parentKey: String? = null) {
        when (value) {
            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys += iterator.next()
                keys.forEach { key ->
                    val deviceScopedId = key.equals("id", ignoreCase = true) &&
                        parentKey.orEmpty().let { parent ->
                            parent.equals("device", ignoreCase = true) ||
                                parent.equals("grill", ignoreCase = true) ||
                                parent.equals("controller", ignoreCase = true) ||
                                parent.equals("bluetooth", ignoreCase = true)
                        }
                    if (deviceScopedId || sensitiveFragments.any { key.contains(it, ignoreCase = true) }) {
                        value.put(key, "[redacted]")
                    } else {
                        when (val child = value.opt(key)) {
                            is String -> value.put(key, sanitizeText(child, Int.MAX_VALUE))
                            else -> redact(child, key)
                        }
                    }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                when (val child = value.opt(index)) {
                    is String -> value.put(index, sanitizeText(child, Int.MAX_VALUE))
                    else -> redact(child, parentKey)
                }
            }
        }
    }

    private const val MAX_TEXT_CHARS = 2_000
    private const val MAX_SECTION_TITLE_CHARS = 120
}
