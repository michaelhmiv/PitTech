package com.pittech.devices

import java.security.MessageDigest
import java.util.Locale

data class BluetoothGattDescriptorInfo(
    val uuid: String,
    val permissions: List<String>,
)

data class BluetoothGattCharacteristicInfo(
    val uuid: String,
    val properties: List<String>,
    val permissions: List<String>,
    val descriptors: List<BluetoothGattDescriptorInfo>,
)

data class BluetoothGattServiceInfo(
    val uuid: String,
    val kind: String,
    val characteristics: List<BluetoothGattCharacteristicInfo>,
)

data class BluetoothGattReadResult(
    val serviceUuid: String,
    val characteristicUuid: String,
    val initiated: Boolean,
    val statusCode: Int?,
    val valueLengthBytes: Int,
    val valueHex: String,
    val valueText: String?,
    val truncatedValueBytes: Int,
    val note: String?,
)

data class BluetoothGattInspectionReport(
    val address: String,
    val startedAtUtc: String,
    val finishedAtUtc: String,
    val outcome: String,
    val connected: Boolean?,
    val connectionStatusCode: Int?,
    val serviceDiscoveryStatusCode: Int?,
    val services: List<BluetoothGattServiceInfo>,
    val totalCharacteristicCount: Int,
    val readableCharacteristicCount: Int,
    val omittedReadableCharacteristicCount: Int,
    val skippedReadableCharacteristicCount: Int = 0,
    val notifications: List<BluetoothGattNotificationObservation> = emptyList(),
    val omittedNotificationCharacteristicCount: Int = 0,
    val omittedNotificationEventCount: Int = 0,
    val reads: List<BluetoothGattReadResult>,
    val events: List<String>,
    val omittedEventCount: Int,
)

/** Formats a bounded, user-reviewed report from an explicitly requested BLE inspection. */
internal object BluetoothGattDiagnostics {
    const val MAX_DETAILS_CHARS = 14_000

    /** Split GATT evidence so the report builder can protect identity and failures from verbose data. */
    fun sections(report: BluetoothGattInspectionReport): List<RawDiagnosticSection> {
        val identity = buildString {
            appendLine("Outcome: ${quoted(report.outcome)}")
            appendLine("Started (UTC): ${report.startedAtUtc}")
            appendLine("Finished (UTC): ${report.finishedAtUtc}")
            appendLine("Connected: ${report.connected?.toString() ?: "(no callback)"}")
            appendLine("Last connection status: ${status(report.connectionStatusCode)}")
            appendLine("Service discovery status: ${status(report.serviceDiscoveryStatusCode)}")
            appendLine("Services discovered: ${report.services.size}")
            appendLine("Characteristics discovered: ${report.totalCharacteristicCount}")
            appendLine("Readable characteristics found: ${report.readableCharacteristicCount}")
            appendLine("Readable characteristics skipped by safety policy: ${report.skippedReadableCharacteristicCount}")
            appendLine("Readable characteristic reads omitted by safety cap: ${report.omittedReadableCharacteristicCount}")
            appendLine("Notification-capable characteristics omitted by observation cap: ${report.omittedNotificationCharacteristicCount}")
            appendLine("Notification events omitted by event/byte caps: ${report.omittedNotificationEventCount}")
            appendLine("Services, characteristics, and descriptors:")
            if (report.services.isEmpty()) {
                appendLine("  (none discovered)")
            } else {
                report.services.forEachIndexed { serviceIndex, service ->
                    appendLine("  Service ${serviceIndex + 1}: ${service.uuid} (${service.kind})")
                    if (service.characteristics.isEmpty()) appendLine("    (no characteristics)")
                    service.characteristics.forEach { characteristic ->
                        appendLine(
                            "    Characteristic ${characteristic.uuid} properties=[${characteristic.properties.joinToString().ifBlank { "none" }}] " +
                                "permissions=[${characteristic.permissions.joinToString().ifBlank { "none reported" }}]",
                        )
                        characteristic.descriptors.forEach { descriptor ->
                            appendLine(
                                "      Descriptor ${descriptor.uuid} permissions=[${descriptor.permissions.joinToString().ifBlank { "none reported" }}]",
                            )
                        }
                    }
                }
            }
        }.trimEnd()

        val failures = report.reads.filter { it.statusCode != 0 || it.note != null }
        val failureDetails = buildString {
            appendLine("GATT reads: ${report.reads.size}; failed, timed out, or safety-skipped: ${failures.size}")
            failures.forEach { read ->
                appendLine(
                    "- service=${read.serviceUuid} characteristic=${read.characteristicUuid} " +
                        "accepted=${read.initiated} status=${status(read.statusCode)} note=${quoted(read.note ?: "read failed")}",
                )
            }
        }.trimEnd()

        val values = buildString {
            appendLine("GATT read values (transport operations do not imply controller-state mutation):")
            val successful = report.reads.filter { it.statusCode == 0 && it.note == null }
            if (successful.isEmpty()) {
                appendLine("  (no successful readable-characteristic responses)")
            } else {
                successful.forEach { read ->
                    appendLine(
                        "- service=${read.serviceUuid} characteristic=${read.characteristicUuid} " +
                            "bytes=${read.valueLengthBytes} sha256=${fingerprint(read.valueHex)} " +
                            "preview=${quoted(read.valueText?.let { ControllerDiagnosticSanitizer.sanitizeText(it, MAX_TEXT_PREVIEW_CHARS) } ?: "(binary or empty)")}" +
                            (if (read.truncatedValueBytes > 0) " truncatedBytes=${read.truncatedValueBytes}" else ""),
                    )
                }
            }
        }.trimEnd()

        val notifications = buildString {
            appendLine("Bounded passive notification observations:")
            if (report.notifications.isEmpty()) {
                appendLine("  (no passive notification observations were collected)")
            } else {
                report.notifications.forEach { observation ->
                    appendLine(
                        "- service=${observation.serviceUuid} characteristic=${observation.characteristicUuid} " +
                            "events=${observation.eventCount} payloadChanges=${observation.payloadChangeCount} " +
                            "bytes=${observation.totalPayloadBytes} durationMs=${observation.observationDurationMillis} " +
                            "frequencyHz=${"%.2f".format(Locale.US, observation.frequencyHz)}" +
                            (observation.error?.let { " error=${ControllerDiagnosticSanitizer.sanitizeText(it, 180)}" } ?: ""),
                    )
                }
            }
        }.trimEnd()

        val events = buildString {
            appendLine("Connection and GATT event log (UTC; elapsed milliseconds from inspection start):")
            report.events.takeLast(MAX_EVENTS_IN_REPORT).forEach { appendLine("- ${it.take(MAX_EVENT_CHARS)}") }
            val omitted = report.omittedEventCount + (report.events.size - MAX_EVENTS_IN_REPORT).coerceAtLeast(0)
            if (omitted > 0) appendLine("Earlier event entries omitted: $omitted")
            if (report.events.isEmpty()) appendLine("(no callback events recorded)")
        }.trimEnd()

        return listOf(
            RawDiagnosticSection("GATT IDENTITY AND SERVICE TREE", 94, identity),
            RawDiagnosticSection("GATT READ FAILURES AND TIMEOUTS", 97, failureDetails),
            RawDiagnosticSection("GATT READ VALUE FINGERPRINTS", 70, values),
            RawDiagnosticSection("GATT NOTIFICATION OBSERVATIONS", 65, notifications),
            RawDiagnosticSection("GATT SESSION EVENTS", 15, events),
        )
    }

    fun format(report: BluetoothGattInspectionReport): String {
        val full = sections(report).sortedByDescending { it.priority }
            .joinToString("\n\n") { "${it.title}\n${it.text}" }
        if (full.length <= MAX_DETAILS_CHARS) return full
        val marker = "\n\n[GATT details shortened at $MAX_DETAILS_CHARS characters; failure counts and service identity are prioritized.]"
        return full.take(MAX_DETAILS_CHARS - marker.length) + marker
    }

    private fun status(code: Int?): String = when (code) {
        null -> "(not reported)"
        0 -> "GATT_SUCCESS (0)"
        133 -> "GATT_ERROR_133 (133)"
        else -> "GATT_STATUS_" + code
    }

    private fun fingerprint(hex: String): String {
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
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
                char.isISOControl() -> append("\\u" + char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }

    private const val MAX_EVENTS_IN_REPORT = 32
    private const val MAX_EVENT_CHARS = 180
    private const val MAX_TEXT_PREVIEW_CHARS = 256
}
