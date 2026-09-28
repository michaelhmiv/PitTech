package com.pittech.devices

import java.security.MessageDigest
import java.util.Locale

internal object ControllerProtocolDetector {
    const val MONGOOSE_RPC_SERVICE = "5f6d4f53-5f52-5043-5f53-56435f49445f"
    const val MONGOOSE_RPC_DATA = "5f6d4f53-5f52-5043-5f64-6174615f5f5f"
    const val MONGOOSE_RPC_TX_CTL = "5f6d4f53-5f52-5043-5f74-785f63746c5f"
    const val MONGOOSE_RPC_RX_CTL = "5f6d4f53-5f52-5043-5f72-785f63746c5f"

    const val MONGOOSE_DEBUG_SERVICE = "5f6d4f53-5f44-4247-5f53-56435f49445f"
    const val MONGOOSE_DEBUG_LOG = "306d4f53-5f44-4247-5f6c-6f675f5f5f30"

    const val MONGOOSE_CONFIG_SERVICE = "5f6d4f53-5f43-4647-5f53-56435f49445f"

    fun detect(report: BluetoothGattInspectionReport): Set<ControllerProtocolFamily> {
        val services = report.services.map { it.uuid.lowercase(Locale.ROOT) }.toSet()
        return buildSet {
            if (MONGOOSE_RPC_SERVICE in services) add(ControllerProtocolFamily.MONGOOSE_RPC)
            if (MONGOOSE_CONFIG_SERVICE in services) add(ControllerProtocolFamily.MONGOOSE_CONFIG_GATT)
            if (MONGOOSE_DEBUG_SERVICE in services) add(ControllerProtocolFamily.MONGOOSE_DEBUG_GATT)
            if (isEmpty()) add(ControllerProtocolFamily.UNKNOWN)
        }
    }

    fun fingerprint(
        device: NearbyBluetoothDevice,
        inspection: BluetoothGattInspectionReport?,
        probe: ControllerProbeReport?,
    ): String {
        val stable = buildString {
            appendLine(device.advertisedName?.trim()?.uppercase(Locale.ROOT).orEmpty())
            device.advertisements
                .flatMap { it.manufacturerData.keys }
                .distinct()
                .sorted()
                .forEach { appendLine("m:$it") }
            device.advertisements
                .flatMap { it.serviceUuids }
                .map { it.lowercase(Locale.ROOT) }
                .distinct()
                .sorted()
                .forEach { appendLine("a:$it") }
            inspection?.services
                ?.map { it.uuid.lowercase(Locale.ROOT) }
                ?.distinct()
                ?.sorted()
                ?.forEach { appendLine("g:$it") }
            probe?.rpcMethods
                ?.distinct()
                ?.sorted()
                ?.forEach { appendLine("r:$it") }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
        return "pittech:sha256:" + digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}
