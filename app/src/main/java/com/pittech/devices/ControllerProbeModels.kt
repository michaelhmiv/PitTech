package com.pittech.devices

enum class ControllerProtocolFamily(val displayName: String) {
    MONGOOSE_RPC("Mongoose OS RPC"),
    MONGOOSE_CONFIG_GATT("Mongoose OS configuration GATT"),
    MONGOOSE_DEBUG_GATT("Mongoose OS debug GATT"),
    UNKNOWN("Unknown"),
}

enum class ProbeStepState {
    NOT_ATTEMPTED,
    RUNNING,
    SUCCESS,
    UNSUPPORTED,
    TIMEOUT,
    AUTH_REQUIRED,
    PERMISSION_DENIED,
    TRANSPORT_ERROR,
    PROTOCOL_ERROR,
    SKIPPED_SAFETY,
}

enum class RpcSafetyClass {
    SAFE_AUTOPROBE,
    KNOWN_MUTATING,
    SENSITIVE_READ,
    UNKNOWN,
}

enum class ControllerTransportType(val displayName: String) {
    BLE("Direct Bluetooth"),
    LOCAL_HTTP("Local network"),
    VENDOR_RELAY("Vendor cloud relay"),
}

enum class CapabilityState(val displayName: String) {
    SUPPORTED("Available"),
    POSSIBLE("Possible"),
    UNAVAILABLE("Unavailable"),
    UNKNOWN("Unknown"),
    FAILED("Failed"),
}

data class ControllerTransportCapability(
    val transport: ControllerTransportType,
    val state: CapabilityState,
    val details: String,
)

/** Generic identity selected by the user for one interrogation session. */
data class ControllerTarget(
    val deviceKey: String,
    val advertisedName: String?,
    val bluetoothAddress: String?,
)

/** Stable hash plus the non-secret signals used to create it. */
data class ControllerFingerprint(
    val value: String,
    val stableSignals: List<String>,
)

data class BluetoothGattNotificationObservation(
    val serviceUuid: String,
    val characteristicUuid: String,
    val eventCount: Int,
    val payloadChangeCount: Int,
    val totalPayloadBytes: Int,
    val observationDurationMillis: Long,
    val error: String? = null,
) {
    val frequencyHz: Double
        get() = if (observationDurationMillis > 0) eventCount * 1_000.0 / observationDurationMillis else 0.0
}

data class RpcProbeObservation(
    val label: String,
    val method: String,
    val state: ProbeStepState,
    val response: String? = null,
    val error: String? = null,
)

data class ControllerProbeReport(
    val address: String,
    val startedAtUtc: String,
    val finishedAtUtc: String,
    val outcome: String,
    val protocols: Set<ControllerProtocolFamily>,
    val rpcMethods: List<String>,
    val rpcDescriptions: Map<String, String>,
    val observations: List<RpcProbeObservation>,
    val debugMessages: List<String>,
    val debugNotificationCount: Int = 0,
    val debugPayloadChangeCount: Int = 0,
    val debugRetainedBytes: Int = 0,
    val omittedDebugMessageCount: Int = 0,
    val transportCapabilities: List<ControllerTransportCapability>,
    val events: List<String>,
    val omittedEventCount: Int,
)
