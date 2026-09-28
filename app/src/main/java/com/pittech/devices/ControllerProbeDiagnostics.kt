package com.pittech.devices

import java.security.MessageDigest
import java.util.Locale

internal object ControllerProbeDiagnostics {
    const val MAX_DETAILS_CHARS = 45_000

    fun details(
        summary: BluetoothScanSummary,
        selectedDevice: NearbyBluetoothDevice?,
        inspection: BluetoothGattInspectionReport?,
        probe: ControllerProbeReport?,
        sessionEvents: List<String> = emptyList(),
        omittedSessionEventCount: Int = 0,
    ): String {
        val full = buildString {
            appendLine("PitTech controller interrogation diagnostics")
            appendLine("Probe engine: generic BLE fingerprint + safe protocol interrogation")
            appendLine("Scan started (UTC): ${summary.startedAtUtc}")
            appendLine("Scan finished (UTC): ${summary.finishedAtUtc}")
            appendLine("Scan duration (ms): ${summary.durationMillis}")
            appendLine("Total BLE results: ${summary.totalResults}")
            appendLine("Distinct nearby devices retained locally: ${summary.capturedDeviceCount}")
            appendLine("Scan error: ${quoted(summary.error ?: "none")}")
            appendLine()

            if (selectedDevice == null) {
                appendLine("Selected controller: none")
                appendLine("No controller-specific advertisement, GATT, or protocol data is included.")
            } else {
                appendLine("CONTROLLER FINGERPRINT")
                appendLine("Display name: ${quoted(selectedDevice.advertisedName ?: "(not advertised)")}")
                appendLine("Session device ID: ${sessionDeviceId(selectedDevice.address, summary.startedAtUtc)}")
                appendLine(
                    "Stable capability fingerprint: " +
                        ControllerProtocolDetector.fingerprint(selectedDevice, inspection, probe),
                )
                appendLine(
                    "PitTech support status: " +
                        ControllerSupportRegistry.label(selectedDevice, inspection, probe),
                )
                appendLine("Latest RSSI (dBm): ${selectedDevice.rssi}")
                appendLine("Captured advertisement observations: ${selectedDevice.observationCount}")
                appendLine()

                appendLine("ADVERTISEMENTS")
                selectedDevice.advertisements.forEachIndexed { index, item ->
                    appendLine("Advertisement variant ${index + 1}")
                    appendLine("  Observations: ${item.observationCount}")
                    appendLine("  RSSI range: ${item.weakestRssi} to ${item.strongestRssi} dBm")
                    appendLine("  Name: ${quoted(item.advertisedName ?: "(none)")}")
                    appendLine("  Connectable: ${item.connectable ?: "(not reported)"}")
                    appendLine("  Service UUIDs: ${item.serviceUuids.joinToString().ifBlank { "(none)" }}")
                    appendLine("  Solicitation UUIDs: ${item.serviceSolicitationUuids.joinToString().ifBlank { "(none)" }}")
                    appendLine("  Manufacturer IDs: ${item.manufacturerData.keys.joinToString().ifBlank { "(none)" }}")
                    appendLine("  Manufacturer data: ${item.manufacturerData.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "(none)" }}")
                    appendLine("  Service data: ${item.serviceData.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "(none)" }}")
                    appendLine("  Raw advertisement hex: ${item.rawRecordHex.ifBlank { "(empty)" }}")
                }
                if (selectedDevice.omittedAdvertisementVariants > 0) {
                    appendLine("Advertisement variants omitted locally: ${selectedDevice.omittedAdvertisementVariants}")
                }
                appendLine()

                if (inspection != null &&
                    selectedDevice.address?.equals(inspection.address, ignoreCase = true) == true
                ) {
                    appendLine("GATT INVENTORY")
                    appendLine(
                        BluetoothGattDiagnostics.format(inspection)
                            .replace(inspection.address, "[device-address-redacted]"),
                    )
                    appendLine()
                }

                if (probe != null) {
                    appendLine("PROTOCOL DETECTION")
                    appendLine(
                        "Detected protocol families: " +
                            probe.protocols.joinToString { it.displayName },
                    )
                    appendLine("Outcome: ${quoted(probe.outcome)}")
                    appendLine()

                    appendLine("TRANSPORT CAPABILITIES")
                    probe.transportCapabilities.forEach { (name, status) ->
                        appendLine("- $name: ${ControllerDiagnosticSanitizer.sanitizeText(status)}")
                    }
                    appendLine()

                    appendLine("RPC INVENTORY")
                    appendLine("RPC methods discovered: ${probe.rpcMethods.size}")
                    probe.rpcMethods.forEach { method ->
                        appendLine("- $method [${ControllerProbePolicy.classify(method).name}]")
                    }
                    appendLine()

                    if (probe.rpcDescriptions.isNotEmpty()) {
                        appendLine("RPC INTROSPECTION")
                        probe.rpcDescriptions.forEach { (method, description) ->
                            appendLine("- $method: " + ControllerDiagnosticSanitizer.sanitizeJson(description))
                        }
                        appendLine()
                    }

                    appendLine("SAFE AUTOMATIC PROBE RESULTS")
                    if (probe.observations.isEmpty()) appendLine("(none)")
                    probe.observations.forEach { observation ->
                        appendLine("- ${observation.label} | method=${observation.method} | state=${observation.state}")
                        observation.response?.let {
                            appendLine("  response=" + ControllerDiagnosticSanitizer.sanitizeJson(it))
                        }
                        observation.error?.let {
                            appendLine("  error=" + ControllerDiagnosticSanitizer.sanitizeText(it))
                        }
                    }
                    appendLine()

                    if (probe.debugMessages.isNotEmpty()) {
                        appendLine("SANITIZED MONGOOSE DEBUG OBSERVATIONS")
                        probe.debugMessages.take(40).forEach { raw ->
                            appendLine("- " + ControllerDiagnosticSanitizer.sanitizeText(raw))
                        }
                        appendLine()
                    }

                    appendLine("PROTOCOL PROBE EVENTS")
                    probe.events.takeLast(100).forEach { event ->
                        appendLine(ControllerDiagnosticSanitizer.sanitizeText(event))
                    }
                    if (probe.omittedEventCount > 0) {
                        appendLine("Earlier/overflow protocol events omitted: ${probe.omittedEventCount}")
                    }
                    appendLine()
                }
            }

            appendLine("APP SESSION EVENTS")
            sessionEvents.takeLast(80).forEach {
                appendLine(ControllerDiagnosticSanitizer.sanitizeText(it))
            }
            val omitted = omittedSessionEventCount + (sessionEvents.size - 80).coerceAtLeast(0)
            if (omitted > 0) appendLine("Earlier app events omitted: $omitted")
            appendLine()
            appendLine("SAFETY")
            appendLine("Automatic protocol requests are restricted to an explicit observational allowlist.")
            appendLine("Unknown and mutating RPC methods are inventoried but are not automatically executed.")
            appendLine("Wi-Fi SSIDs/BSSIDs, password-like fields, tokens, credentials, and permanent Bluetooth addresses are excluded or redacted from this public-report representation.")
        }.trimEnd()

        if (full.length <= MAX_DETAILS_CHARS) return full
        val marker = "\n\n[Report truncated to fit the feedback relay. Fingerprint, service inventory, RPC inventory, probe results, and errors are prioritized above repeated/raw details.]"
        return full.take(MAX_DETAILS_CHARS - marker.length) + marker
    }

    private fun sessionDeviceId(address: String?, seed: String): String {
        if (address.isNullOrBlank()) return "(unavailable)"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$seed|${address.uppercase(Locale.ROOT)}".toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun quoted(value: String): String = buildString(value.length + 8) {
        append('"')
        value.forEach { char ->
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char.isISOControl() -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }
}
