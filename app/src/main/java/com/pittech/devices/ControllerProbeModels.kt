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
    val transportCapabilities: Map<String, String>,
    val events: List<String>,
    val omittedEventCount: Int,
)
