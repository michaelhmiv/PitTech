package com.pittech.devices

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
    val reads: List<BluetoothGattReadResult>,
    val events: List<String>,
    val omittedEventCount: Int,
)

/** Formats a bounded, user-reviewed report from an explicitly requested BLE inspection. */
internal object BluetoothGattDiagnostics {
    const val MAX_DETAILS_CHARS = 14_000

    fun format(report: BluetoothGattInspectionReport): String {
        val full = buildString {
            appendLine("Bluetooth GATT inspection")
            appendLine("Selected controller Bluetooth address: " + report.address)
            appendLine("Started (UTC): " + report.startedAtUtc)
            appendLine("Finished (UTC): " + report.finishedAtUtc)
            appendLine("Outcome: " + quoted(report.outcome))
            appendLine("Connected: " + (report.connected?.toString() ?: "(no callback)"))
            appendLine("Last connection status: " + status(report.connectionStatusCode))
            appendLine("Service discovery status: " + status(report.serviceDiscoveryStatusCode))
            appendLine("Services discovered: " + report.services.size)
            appendLine("Characteristics discovered: " + report.totalCharacteristicCount)
            appendLine("Readable characteristics found: " + report.readableCharacteristicCount)
            appendLine("Readable characteristic reads omitted by safety cap: " + report.omittedReadableCharacteristicCount)
            appendLine()

            appendLine("Connection and GATT event log (UTC; elapsed milliseconds from inspection start):")
            if (report.events.isEmpty()) {
                appendLine("  (no callback events recorded)")
            } else {
                report.events.takeLast(MAX_EVENTS_IN_REPORT).forEach { appendLine("  " + it.take(MAX_EVENT_CHARS)) }
                val omitted = report.omittedEventCount + (report.events.size - MAX_EVENTS_IN_REPORT).coerceAtLeast(0)
                if (omitted > 0) appendLine("  Earlier event entries omitted: " + omitted)
            }
            appendLine()

            appendLine("Discovered GATT services, characteristics, and descriptors:")
            if (report.services.isEmpty()) {
                appendLine("  (none discovered)")
            } else {
                report.services.forEachIndexed { serviceIndex, service ->
                    appendLine("  Service " + (serviceIndex + 1) + ": " + service.uuid + " (" + service.kind + ")")
                    if (service.characteristics.isEmpty()) appendLine("    (no characteristics)")
                    service.characteristics.forEach { characteristic ->
                        appendLine(
                            "    Characteristic " + characteristic.uuid +
                                " properties=[" + characteristic.properties.joinToString().ifBlank { "none" } +
                                "] permissions=[" + characteristic.permissions.joinToString().ifBlank { "none reported" } + "]",
                        )
                        if (characteristic.descriptors.isEmpty()) {
                            appendLine("      Descriptors: none")
                        } else {
                            characteristic.descriptors.forEach { descriptor ->
                                appendLine(
                                    "      Descriptor " + descriptor.uuid +
                                        " permissions=[" + descriptor.permissions.joinToString().ifBlank { "none reported" } + "]",
                                )
                            }
                        }
                    }
                }
            }
            appendLine()

            appendLine("GATT inventory read results (this inventory phase does not write characteristics or subscribe to notifications; protocol-specific probing may separately use transport framing writes and notifications):")
            if (report.reads.isEmpty()) {
                appendLine("  (no readable characteristics were read)")
            } else {
                report.reads.forEachIndexed { index, read ->
                    appendLine(
                        "  Read " + (index + 1) + ": service=" + read.serviceUuid +
                            " characteristic=" + read.characteristicUuid,
                    )
                    appendLine("    Request accepted by Android: " + read.initiated)
                    appendLine("    GATT status: " + status(read.statusCode))
                    appendLine("    Value length (bytes): " + read.valueLengthBytes)
                    appendLine("    Value (hex): " + read.valueHex.ifBlank { "(empty)" })
                    read.valueText?.let { appendLine("    Printable UTF-8 preview: " + quoted(it)) }
                    if (read.truncatedValueBytes > 0) {
                        appendLine("    Value bytes omitted from report preview: " + read.truncatedValueBytes)
                    }
                    read.note?.let { appendLine("    Read note: " + quoted(it)) }
                }
            }
        }.trimEnd()

        if (full.length <= MAX_DETAILS_CHARS) return full
        val marker = "\n\n[GATT section truncated at " + MAX_DETAILS_CHARS + " characters; service/read omission counts are shown above.]"
        return full.take(MAX_DETAILS_CHARS - marker.length) + marker
    }

    private fun status(code: Int?): String = when (code) {
        null -> "(not reported)"
        0 -> "GATT_SUCCESS (0)"
        133 -> "GATT_ERROR_133 (133)"
        else -> "GATT_STATUS_" + code
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
}
