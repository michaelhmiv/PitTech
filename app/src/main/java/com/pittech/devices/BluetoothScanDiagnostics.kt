package com.pittech.devices

/**
 * Turns the selected device's BLE scan observations and current relay state
 * into a bounded report for the anonymous GitHub feedback relay.
 */
internal object BluetoothScanDiagnostics {
    const val MAX_DETAILS_CHARS = 45_000

    fun details(
        summary: BluetoothScanSummary,
        selectedDevice: NearbyBluetoothDevice?,
        relayStage: String,
        relayStatus: String,
        relayMessages: List<Pair<String, String>>,
    ): String {
        val full = buildString {
            appendLine("PitTech Bluetooth controller discovery diagnostics")
            appendLine("Scan started (UTC): ${summary.startedAtUtc}")
            appendLine("Scan finished (UTC): ${summary.finishedAtUtc}")
            appendLine("Scan duration (ms): ${summary.durationMillis}")
            appendLine("Scan mode: ${summary.scanMode}")
            appendLine("Total BLE results: ${summary.totalResults}")
            appendLine("Distinct devices retained locally: ${summary.capturedDeviceCount}")
            appendLine("Devices omitted because the local capture cap was reached: ${summary.omittedDeviceCount}")
            appendLine("Scan error: ${quoted(summary.error ?: "none")}")
            appendLine()

            if (selectedDevice == null) {
                appendLine("Selected device: none")
                appendLine("No device-specific Bluetooth address or advertisement payload is included.")
            } else {
                appendLine("Selected device")
                appendLine("Name: ${quoted(selectedDevice.advertisedName ?: "(not advertised)")}")
                appendLine("Bluetooth address: ${selectedDevice.address ?: "(unavailable)"}")
                appendLine("Pit Boss relay ID candidate: ${selectedDevice.relayIdentifier ?: "(not recognized)"}")
                appendLine("PitTech support status: ${ControllerSupportRegistry.label(selectedDevice)}")
                appendLine("Latest RSSI (dBm): ${selectedDevice.rssi}")
                appendLine("Captured observations: ${selectedDevice.observationCount}")
                appendLine("Distinct advertisement variants omitted: ${selectedDevice.omittedAdvertisementVariants}")
                appendLine()

                selectedDevice.advertisements.forEachIndexed { index, item ->
                    appendLine("Advertisement variant ${index + 1}")
                    appendLine("  First seen after scan start (ms): ${item.firstSeenOffsetMillis}")
                    appendLine("  Last seen after scan start (ms): ${item.lastSeenOffsetMillis}")
                    appendLine("  Repeated observations: ${item.observationCount}")
                    appendLine("  RSSI range (dBm): ${item.weakestRssi} to ${item.strongestRssi}")
                    appendLine("  Advertised name: ${quoted(item.advertisedName ?: "(not present in this packet)")}")
                    appendLine("  TX power (dBm): ${item.txPower ?: "(not present)"}")
                    appendLine("  Connectable: ${item.connectable ?: "(not reported)"}")
                    appendLine("  Advertising flags: ${item.advertiseFlags ?: "(not present)"}")
                    appendLine("  Primary PHY: ${item.primaryPhy ?: "(not reported)"}")
                    appendLine("  Secondary PHY: ${item.secondaryPhy ?: "(not reported)"}")
                    appendLine("  Advertising SID: ${item.advertisingSid ?: "(not reported)"}")
                    appendLine("  Periodic advertising interval: ${item.periodicAdvertisingInterval ?: "(not reported)"}")
                    appendLine("  Data status: ${item.dataStatus ?: "(not reported)"}")
                    appendLine("  Service UUIDs: ${item.serviceUuids.joinToString().ifBlank { "(none)" }}")
                    appendLine("  Service solicitation UUIDs: ${item.serviceSolicitationUuids.joinToString().ifBlank { "(none)" }}")
                    appendLine("  Manufacturer data (company ID -> hex):")
                    if (item.manufacturerData.isEmpty()) appendLine("    (none)")
                    item.manufacturerData.forEach { (id, bytes) -> appendLine("    $id -> $bytes") }
                    appendLine("  Service data (UUID -> hex):")
                    if (item.serviceData.isEmpty()) appendLine("    (none)")
                    item.serviceData.forEach { (uuid, bytes) -> appendLine("    $uuid -> $bytes") }
                    appendLine("  Raw advertisement record (hex): ${item.rawRecordHex.ifBlank { "(empty)" }}")
                    appendLine()
                }
            }

            appendLine("Pit Boss relay probe stage: ${quoted(relayStage)}")
            appendLine("Pit Boss relay status: ${quoted(relayStatus)}")
            if (relayMessages.isEmpty()) {
                appendLine("Recent redacted relay messages: none")
            } else {
                appendLine("Recent redacted relay messages:")
                relayMessages.take(20).forEachIndexed { index, (title, body) ->
                    appendLine("Message ${index + 1}: ${quoted(title)}")
                    appendLine(body)
                }
                if (relayMessages.size > 20) {
                    appendLine("Additional relay messages omitted: ${relayMessages.size - 20}")
                }
            }
        }.trimEnd()

        if (full.length <= MAX_DETAILS_CHARS) return full
        val marker = "\n\n[Report truncated to fit the feedback service limit. BLE capture caps and omitted counts are listed above.]"
        return full.take(MAX_DETAILS_CHARS - marker.length) + marker
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
