package com.pittech.devices

import java.security.MessageDigest
import java.util.Locale

/** Converts local, raw probe objects into sanitized sections for a public report. */
internal object ControllerProbeDiagnostics {
    const val MAX_DETAILS_CHARS = 45_000

    fun details(
        summary: BluetoothScanSummary,
        selectedDevice: NearbyBluetoothDevice?,
        inspection: BluetoothGattInspectionReport?,
        probe: ControllerProbeReport?,
        sessionEvents: List<String> = emptyList(),
        omittedSessionEventCount: Int = 0,
        pitTechVersion: String? = null,
        androidVersion: String? = null,
        phoneModel: String? = null,
        referenceCode: String? = null,
    ): String {
        val sections = mutableListOf<RawDiagnosticSection>()
        sections += RawDiagnosticSection(
            "SESSION",
            100,
            buildString {
                appendLine("Scan started (UTC): ${summary.startedAtUtc}")
                appendLine("Scan finished (UTC): ${summary.finishedAtUtc}")
                appendLine("Scan duration (ms): ${summary.durationMillis}")
                appendLine("PitTech version: ${pitTechVersion ?: "(not reported)"}")
                appendLine("Android version: ${androidVersion ?: "(not reported)"}")
                appendLine("Phone model: ${phoneModel ?: "(not reported)"}")
                appendLine("Probe-engine version: ${ControllerSupportRegistry.CURRENT_PROBE_VERSION}")
                appendLine("Session reference: ${referenceCode ?: "(not assigned)"}")
                appendLine("Total BLE results: ${summary.totalResults}")
                appendLine("Distinct nearby devices retained locally: ${summary.capturedDeviceCount}")
                appendLine("Scan error: ${summary.error ?: "none"}")
            }.trimEnd(),
        )

        if (selectedDevice == null) {
            sections += RawDiagnosticSection(
                "CONTROLLER",
                100,
                "Selected controller: none\nNo controller-specific advertisement, GATT, or protocol data is included.",
            )
        } else {
            val fingerprint = ControllerProtocolDetector.fingerprintEvidence(selectedDevice, inspection, probe)
            sections += RawDiagnosticSection(
                "CONTROLLER FINGERPRINT",
                100,
                buildString {
                    appendLine("Advertised name: ${ControllerDiagnosticSanitizer.sanitizeAdvertisedName(selectedDevice.advertisedName)}")
                    appendLine("Session device ID: ${sessionDeviceId(selectedDevice.address, summary.startedAtUtc)}")
                    appendLine("Stable capability fingerprint: ${fingerprint.value}")
                    appendLine("Stable evidence signals: ${fingerprint.stableSignals.size}")
                    appendLine(
                        "PitTech support status: " +
                            ControllerSupportRegistry.label(selectedDevice, inspection, probe),
                    )
                    appendLine("Latest RSSI (dBm): ${selectedDevice.rssi}")
                    appendLine("Captured advertisement observations: ${selectedDevice.observationCount}")
                }.trimEnd(),
            )

            if (inspection != null &&
                selectedDevice.address?.equals(inspection.address, ignoreCase = true) == true
            ) {
                sections += BluetoothGattDiagnostics.sections(inspection)
            }

            if (probe != null) {
                sections += RawDiagnosticSection(
                    "PROTOCOL DETECTION",
                    100,
                    buildString {
                        appendLine("Detected protocol families: ${probe.protocols.joinToString { it.displayName }}")
                        appendLine("Outcome: ${probe.outcome}")
                        appendLine("RPC methods discovered: ${probe.rpcMethods.size}")
                        appendLine("Debug notifications retained: ${probe.debugNotificationCount}")
                        appendLine("Debug payload changes observed: ${probe.debugPayloadChangeCount}")
                        appendLine("Debug bytes retained: ${probe.debugRetainedBytes}")
                        appendLine("Debug notifications omitted: ${probe.omittedDebugMessageCount}")
                    }.trimEnd(),
                )
                sections += RawDiagnosticSection(
                    "TRANSPORT CAPABILITIES",
                    93,
                    probe.transportCapabilities.joinToString("\n") { capability ->
                        "${capability.transport.displayName}: ${capability.state.displayName} · ${capability.details}"
                    }.ifBlank { "(none assessed)" },
                )
                sections += RawDiagnosticSection(
                    "RPC ERRORS AND OUTCOMES",
                    96,
                    probe.observations.joinToString("\n") { observation ->
                        buildString {
                            append("- ${observation.method} | state=${observation.state}")
                            observation.error?.let { append(" | error=$it") }
                        }
                    }.ifBlank { "(no RPC methods were attempted)" },
                )
                sections += RawDiagnosticSection(
                    "RPC INVENTORY",
                    99,
                    buildString {
                        appendLine("RPC methods captured: ${probe.rpcMethods.size}")
                        probe.rpcMethods.forEach { method ->
                            appendLine("- $method [${ControllerProbePolicy.classify(method).name}]")
                        }
                    }.trimEnd(),
                )
                if (probe.observations.any { !it.response.isNullOrBlank() }) {
                    sections += RawDiagnosticSection(
                        "SAFE RPC RESPONSE EVIDENCE",
                        80,
                        probe.observations.filter { !it.response.isNullOrBlank() }.joinToString("\n") { observation ->
                            "- ${observation.method}: ${observation.response}"
                        },
                    )
                }
                if (probe.rpcDescriptions.isNotEmpty()) {
                    sections += RawDiagnosticSection(
                        "RPC INTROSPECTION",
                        70,
                        probe.rpcDescriptions.entries.joinToString("\n") { (method, description) ->
                            "- $method: $description"
                        },
                    )
                }
                if (probe.debugMessages.isNotEmpty()) {
                    sections += RawDiagnosticSection(
                        "SANITIZED MONGOOSE DEBUG OBSERVATIONS",
                        35,
                        probe.debugMessages.take(MAX_DEBUG_MESSAGES_IN_REPORT)
                            .mapIndexed { index, message -> "- ${index + 1}: $message" }
                            .joinToString("\n") +
                            "\nDebug messages omitted: ${probe.omittedDebugMessageCount}",
                    )
                }
                if (probe.events.isNotEmpty()) {
                    sections += RawDiagnosticSection(
                        "PROTOCOL PROBE EVENTS",
                        25,
                        probe.events.takeLast(MAX_PROTOCOL_EVENTS_IN_REPORT).joinToString("\n") +
                            "\nEarlier/overflow events omitted: ${probe.omittedEventCount}",
                    )
                }
            }

            sections += RawDiagnosticSection(
                "ADVERTISEMENT EVIDENCE",
                20,
                buildString {
                    appendLine("Repeated advertisements are lower-priority diagnostic evidence.")
                    selectedDevice.advertisements
                        .take(MAX_ADVERTISEMENT_VARIANTS_IN_REPORT)
                        .forEachIndexed { index, item ->
                            appendLine("Advertisement variant ${index + 1}")
                            appendLine("  Observations: ${item.observationCount}")
                            appendLine("  RSSI range: ${item.weakestRssi} to ${item.strongestRssi} dBm")
                            appendLine("  Name: ${ControllerDiagnosticSanitizer.sanitizeAdvertisedName(item.advertisedName)}")
                            appendLine("  Connectable: ${item.connectable ?: "(not reported)"}")
                            appendLine("  Service UUIDs: ${item.serviceUuids.joinToString().ifBlank { "(none)" }}")
                            appendLine("  Solicitation UUIDs: ${item.serviceSolicitationUuids.joinToString().ifBlank { "(none)" }}")
                            appendLine("  Manufacturer IDs: ${item.manufacturerData.keys.joinToString().ifBlank { "(none)" }}")
                            appendLine(
                                "  Manufacturer payload fingerprints: " + item.manufacturerData.entries.joinToString { (id, hex) ->
                                    "$id bytes=${hex.length / 2} sha256=${fingerprintHex(hex)}"
                                }.ifBlank { "(none)" },
                            )
                            appendLine(
                                "  Service-data payload fingerprints: " + item.serviceData.entries.joinToString { (uuid, hex) ->
                                    "$uuid bytes=${hex.length / 2} sha256=${fingerprintHex(hex)}"
                                }.ifBlank { "(none)" },
                            )
                            val raw = item.rawRecordHex
                            appendLine("  Advertisement payload fingerprint: bytes=${raw.length / 2} sha256=${fingerprintHex(raw)}")
                        }
                    val omittedAds =
                        (selectedDevice.advertisements.size - MAX_ADVERTISEMENT_VARIANTS_IN_REPORT).coerceAtLeast(0) +
                            selectedDevice.omittedAdvertisementVariants
                    if (omittedAds > 0) appendLine("Advertisement variants omitted: $omittedAds")
                }.trimEnd(),
            )
        }

        if (sessionEvents.isNotEmpty() || omittedSessionEventCount > 0) {
            sections += RawDiagnosticSection(
                "APP SESSION EVENTS",
                15,
                sessionEvents.takeLast(MAX_SESSION_EVENTS_IN_REPORT).joinToString("\n") +
                    "\nEarlier app events omitted: ${omittedSessionEventCount + (sessionEvents.size - MAX_SESSION_EVENTS_IN_REPORT).coerceAtLeast(0)}",
            )
        }
        sections += RawDiagnosticSection(
            "SAFETY AND PRIVACY",
            100,
            "Automatic protocol requests are restricted to an explicit observational allowlist. " +
                "Unknown and mutating RPC methods are inventoried but are not automatically executed. " +
                "Wi-Fi identities, credentials, tokens, serial-like IDs, and Bluetooth addresses are redacted or omitted. " +
                "The user reviews and submits this report; PitTech does not upload it automatically.",
        )

        val sanitized = ControllerDiagnosticSanitizer.sanitizeSections(sections)
        return ControllerDiagnosticReportBuilder.build(sanitized, MAX_DETAILS_CHARS)
    }

    private fun sessionDeviceId(address: String?, seed: String): String {
        if (address.isNullOrBlank()) return "(unavailable)"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$seed|${address.uppercase(Locale.ROOT)}".toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun fingerprintHex(hex: String): String {
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private const val MAX_ADVERTISEMENT_VARIANTS_IN_REPORT = 24
    private const val MAX_SESSION_EVENTS_IN_REPORT = 80
    private const val MAX_PROTOCOL_EVENTS_IN_REPORT = 100
    private const val MAX_DEBUG_MESSAGES_IN_REPORT = 40
}

internal data class RawDiagnosticSection(val title: String, val priority: Int, val text: String)

internal data class SanitizedDiagnosticSection(
    val title: String,
    val priority: Int,
    val text: String,
)

internal class SanitizedDiagnosticEvidence internal constructor(
    val sections: List<SanitizedDiagnosticSection>,
)

/** Produces a bounded report by section priority and records omissions explicitly. */
internal object ControllerDiagnosticReportBuilder {
    fun build(evidence: SanitizedDiagnosticEvidence, maxChars: Int): String {
        require(maxChars > 0)
        val output = StringBuilder(minOf(maxChars, 4_000))
        output.appendLine("PitTech controller interrogation diagnostics")
        val omitted = mutableListOf<String>()
        val sections = evidence.sections.withIndex()
            .sortedWith(compareByDescending<IndexedValue<SanitizedDiagnosticSection>> { it.value.priority }.thenBy { it.index })

        for ((index, section) in sections.withIndex()) {
            val value = section.value
            val heading = "\n${value.title}\n"
            val remaining = maxChars - output.length
            if (remaining <= heading.length + MIN_TRUNCATION_MARKER_CHARS) {
                omitted += value.title
                continue
            }
            val fullText = heading + value.text
            if (fullText.length <= remaining) {
                output.append(fullText)
                continue
            }

            val omittedChars = fullText.length - remaining + MIN_TRUNCATION_MARKER_CHARS
            val marker = "\n[Section shortened; approximately $omittedChars characters omitted.]"
            val bodyChars = (remaining - heading.length - marker.length).coerceAtLeast(0)
            output.append(heading)
            output.append(value.text.take(bodyChars).trimEnd())
            output.append(marker.take(maxChars - output.length))
            sections.drop(index + 1).forEach { omitted += it.value.title }
            break
        }

        if (omitted.isNotEmpty() && output.length < maxChars) {
            val marker = "\n\n[Lower-priority sections omitted: ${omitted.distinct().joinToString(", ")} ]"
            output.append(marker.take(maxChars - output.length))
        }
        return output.toString().take(maxChars)
    }

    private const val MIN_TRUNCATION_MARKER_CHARS = 48
}
