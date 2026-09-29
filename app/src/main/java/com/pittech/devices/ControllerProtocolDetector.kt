package com.pittech.devices

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal object ControllerProtocolDetector {
    const val MONGOOSE_RPC_SERVICE = "5f6d4f53-5f52-5043-5f53-56435f49445f"
    const val MONGOOSE_RPC_DATA = "5f6d4f53-5f52-5043-5f64-6174615f5f5f"
    const val MONGOOSE_RPC_TX_CTL = "5f6d4f53-5f52-5043-5f74-785f63746c5f"
    const val MONGOOSE_RPC_RX_CTL = "5f6d4f53-5f52-5043-5f72-785f63746c5f"

    const val MONGOOSE_DEBUG_SERVICE = "5f6d4f53-5f44-4247-5f53-56435f49445f"
    const val MONGOOSE_DEBUG_LOG = "306d4f53-5f44-4247-5f6c-6f675f5f5f30"

    const val MONGOOSE_CONFIG_SERVICE = "5f6d4f53-5f43-4647-5f53-56435f49445f"
    const val MONGOOSE_CONFIG_KEY = "306d4f53-5f43-4647-5f6b-65795f5f5f30"
    const val MONGOOSE_CONFIG_VALUE = "316d4f53-5f43-4647-5f76-616c75655f31"
    const val MONGOOSE_CONFIG_SAVE = "326d4f53-5f43-4647-5f73-6176655f5f32"

    /** Mongoose's value characteristic reads whichever config key is currently selected. */
    fun isSafeGattRead(serviceUuid: String, characteristicUuid: String): Boolean =
        !(serviceUuid.equals(MONGOOSE_CONFIG_SERVICE, ignoreCase = true) &&
            characteristicUuid.equals(MONGOOSE_CONFIG_VALUE, ignoreCase = true))

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
    ): String = fingerprintEvidence(device, inspection, probe).value

    fun fingerprintEvidence(
        device: NearbyBluetoothDevice,
        inspection: BluetoothGattInspectionReport?,
        probe: ControllerProbeReport?,
    ): ControllerFingerprint {
        val signals = buildList {
            ControllerDiagnosticSanitizer.advertisedNameFingerprintSignal(device.advertisedName)
                ?.let(::add)
            device.advertisements
                .flatMap { it.manufacturerData.keys }
                .distinct()
                .sorted()
                .forEach { add("manufacturer:${it.uppercase(Locale.ROOT)}") }
            device.advertisements
                .flatMap { it.serviceUuids }
                .map { it.lowercase(Locale.ROOT) }
                .distinct()
                .sorted()
                .forEach { add("advertised-service:$it") }
            inspection?.services
                ?.sortedBy { it.uuid.lowercase(Locale.ROOT) }
                ?.forEach { service ->
                    val serviceUuid = service.uuid.lowercase(Locale.ROOT)
                    add("gatt-service:$serviceUuid")
                    service.characteristics
                        .map { it.uuid.lowercase(Locale.ROOT) }
                        .distinct()
                        .sorted()
                        .forEach { add("gatt-characteristic:$serviceUuid/$it") }
                }
            probe?.protocols
                ?.map { it.name }
                ?.sorted()
                ?.forEach { add("protocol:$it") }
            probe?.rpcMethods
                ?.distinct()
                ?.sorted()
                ?.forEach { add("rpc:$it") }
            probe?.observations
                ?.filter { it.method in FINGERPRINT_EVIDENCE_METHODS && it.state == ProbeStepState.SUCCESS }
                ?.sortedBy { it.method }
                ?.forEach { observation ->
                    val response = observation.response.orEmpty()
                    val stableResponse = if (observation.method == "Sys.GetInfo") {
                        stableSystemInfo(response)
                    } else {
                        ControllerDiagnosticSanitizer.sanitizeJson(response)
                    }
                    if (!stableResponse.isNullOrBlank()) add("device-info:${observation.method}:$stableResponse")
                }
        }.distinct().sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signals.joinToString("\n").toByteArray(Charsets.UTF_8))
        return ControllerFingerprint(
            value = "pittech:sha256:" + digest.joinToString("") {
                "%02x".format(Locale.US, it.toInt() and 0xff)
            },
            stableSignals = signals,
        )
    }

    private val FINGERPRINT_EVIDENCE_METHODS = setOf(
        "Sys.GetInfo",
        "PB.GetFirmwareVersion",
        "PBL.GetLoaderVersion",
    )

    private fun stableSystemInfo(raw: String): String? = runCatching {
        val json = JSONObject(ControllerDiagnosticSanitizer.sanitizeJson(raw))
        val stable = JSONObject()
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.lowercase(Locale.ROOT) in STABLE_SYSTEM_INFO_KEYS) {
                stable.put(key, json.opt(key))
            }
        }
        stable.takeIf { it.length() > 0 }?.toString()
    }.getOrNull()

    private val STABLE_SYSTEM_INFO_KEYS = setOf(
        "arch", "architecture", "board", "build_id", "buildid", "fw_id", "fw_version",
        "firmware", "firmware_version", "hardware", "hw_version", "model", "platform",
        "product", "version",
    )
}
