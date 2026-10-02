package com.pittech.devices

import java.time.Instant

/** Only authenticated observations and explicit sign-in operations are exposed. */
internal enum class PolarisOperation(val path: String) {
    REQUEST_CODE("/email/m2s/send/verify/code"),
    SIGN_IN("/auth/m2s/email/login"),
    DEVICES("/dms/queryDeviceListByUserId"),
    STATUS("/dms/queryDeviceStatus"),
    READINGS("/dms/queryDeviceRealTimeData"),
    REFRESH_SESSION(""),
}

internal class PolarisSession(
    val token: String,
    val expiresAtMillis: Long? = null,
    val selectedDeviceId: String? = null,
    val refreshToken: String? = null,
) {
    override fun toString() = "PolarisSession(redacted)"
    fun expired(now: Long) = expiresAtMillis?.let { now >= it - 30_000L } ?: false
    fun select(id: String?) = PolarisSession(token, expiresAtMillis, id, refreshToken)
}

internal data class PolarisDevice(
    val id: String,
    val name: String,
    val brand: String?,
    val grillModel: String?,
    val controllerModel: String?,
    val firmware: String?,
    val probeCount: Int = 2,
)

internal data class PolarisPayload(
    val values: Map<String, Double>,
    val recognizedFields: List<String>,
    val unknownFieldCount: Int,
    val alarmCount: Int?,
    val reportedAtMillis: Long? = null,
    val zeroProbeMeansUnavailable: Boolean = true,
    val probeCount: Int = 2,
)

internal data class PolarisResult<T>(val value: T, val httpStatus: Int? = 200, val apiCode: Int? = 10000, val observedRemote: Boolean = true)

internal enum class PolarisFailureKind { NETWORK, HTTP, RATE_LIMIT, AUTH, SCHEMA, API, STORAGE }

internal class PolarisFailure(
    val kind: PolarisFailureKind,
    val httpStatus: Int? = null,
    val apiCode: Int? = null,
    val retryAfterMillis: Long? = null,
) : Exception(kind.name) {
    val userMessage: String get() = when (kind) {
        PolarisFailureKind.NETWORK -> "Could not reach GrillirG. Check the phone's internet connection."
        PolarisFailureKind.AUTH -> if (apiCode == -10108) {
            "This account was signed in elsewhere. Sign in again, or use an account shared to your grill."
        } else "GrillirG needs you to sign in again. Request a new email code."
        PolarisFailureKind.RATE_LIMIT -> "GrillirG is limiting requests. Monitoring will retry after a pause."
        PolarisFailureKind.SCHEMA -> "GrillirG returned a response PitTech could not interpret. See connection details."
        PolarisFailureKind.STORAGE -> "PitTech could not open the saved sign-in. Sign in again."
        PolarisFailureKind.HTTP -> "GrillirG returned HTTP ${httpStatus ?: "error"}. Monitoring will retry."
        PolarisFailureKind.API -> "GrillirG rejected this request (code ${apiCode ?: "unknown"})."
    }
    fun message(provider: GrillProvider): String = when {
        provider == GrillProvider.GRILLIRG -> userMessage
        kind == PolarisFailureKind.AUTH && provider == GrillProvider.PIT_BOSS -> "The controller rejected the connection. Check its ID and controller password in Devices."
        kind == PolarisFailureKind.AUTH -> "${provider.label} needs you to sign in again in Devices."
        else -> userMessage.replace("GrillirG", provider.label)
    }
}

internal data class PolarisExchange(
    val timeMillis: Long,
    val operation: PolarisOperation,
    val durationMillis: Long,
    val httpStatus: Int?,
    val apiCode: Int?,
    val failure: PolarisFailureKind? = null,
)

internal data class PolarisSample(val fetchedAtMillis: Long, val payload: PolarisPayload, val samplingIntervalMillis: Long = 15_000L)

internal enum class PolarisPhase { RESTORING, SIGNED_OUT, CODE_SENT, DISCOVERING, READY, MONITORING, PAUSED, SIGN_IN_REQUIRED }

internal data class PolarisMonitorState(
    val phase: PolarisPhase = PolarisPhase.RESTORING,
    val authenticated: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Opening the grill monitor…",
    val devices: List<PolarisDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val latest: PolarisSample? = null,
    val samples: List<PolarisSample> = emptyList(),
    val onlineStatus: Int? = null,
    val statusFetchedAtMillis: Long? = null,
    val lastApiSuccessMillis: Long? = null,
    val nextPollAtMillis: Long? = null,
    val consecutiveFailures: Int = 0,
    val readingRequestFailed: Boolean = false,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val exchanges: List<PolarisExchange> = emptyList(),
    val sessionSaved: Boolean = true,
    val lockedDeviceId: String? = null,
    val provider: GrillProvider = GrillProvider.GRILLIRG,
    val sampling: GrillSamplingPolicy = GrillSamplingPolicy(),
) {
    val selectedDevice get() = devices.firstOrNull { it.id == selectedDeviceId }
    fun readingsAreOld(now: Long) = latest == null || now - latest.fetchedAtMillis > GrillSamplingPolicy.receiptWindow(sampling.sampleIntervalMillis) || readingRequestFailed || onlineStatus?.let { it != 0 } == true || latest.payload.reportedAtMillis?.let { latest.fetchedAtMillis - it > 45_000L || it - latest.fetchedAtMillis > 300_000L } == true
    fun onlineLabel(now: Long): String = when {
        statusFetchedAtMillis == null -> "Grill connection not reported"
        now - statusFetchedAtMillis > GrillSamplingPolicy.receiptWindow(sampling.sampleIntervalMillis) -> "Grill connection status is old"
        onlineStatus == 0 -> "Grill online"
        onlineStatus != null -> "Grill offline (status $onlineStatus)"
        else -> "Grill connection not reported"
    }
}

internal object PolarisMonitorPolicy {
    fun nextDelay(failures: Int, retryAfterMillis: Long? = null, intervalMillis: Long = 60_000L): Long =
        maxOf((intervalMillis * (1L shl failures.coerceIn(0, 3))).coerceAtMost(300_000L), retryAfterMillis ?: 0L)

    fun temperature(sample: PolarisSample?, key: String): String {
        val value = sample?.payload?.values?.get(key) ?: return "Not reported"
        val number = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)
        return number + when (sample.payload.values["tempUnit"]) {
            0.0 -> " °F"
            1.0 -> " °C"
            else -> " (unit unknown)"
        }
    }

    fun runningLabel(code: Double?): String = when (code?.toInt()) {
        1 -> "Starting"
        2 -> "Cooking"
        3 -> "Off"
        4 -> "Shutting down"
        5 -> "Controller reports an error"
        null -> "Not reported"
        else -> "Status ${code.toInt()} (unmapped)"
    }

    /** Build from typed, allowlisted facts; never include account names, IDs or raw JSON. */
    fun report(state: PolarisMonitorState, now: Long): String = buildString {
        appendLine("PitTech ${state.provider.label} read-only monitor")
        appendLine("Captured (UTC): ${Instant.ofEpochMilli(now)}")
        appendLine("Phase: ${state.phase}; successful requests=${state.successfulRequests}; failed requests=${state.failedRequests}")
        appendLine("Collection: ${state.sampling.label}; requested interval=${state.sampling.sampleIntervalMillis}ms")
        appendLine("Cloud last reachable (UTC): ${state.lastApiSuccessMillis?.let { Instant.ofEpochMilli(it) } ?: "not reached"}")
        appendLine("Grill: ${state.onlineLabel(now)}; consecutive incomplete polls=${state.consecutiveFailures}")
        appendLine("Session stored encrypted: ${if (state.authenticated) state.sessionSaved else "not in use"}")
        // User-defined names, device IDs and arbitrary metadata are intentionally omitted.
        appendLine("Account device count: ${state.devices.size}")
        appendLine("Latest fields: ${state.latest?.payload?.recognizedFields?.joinToString() ?: "none"}")
        appendLine("Unknown top-level field count: ${state.latest?.payload?.unknownFieldCount ?: 0}")
        appendLine("Alarm entries: ${state.latest?.payload?.alarmCount ?: "not reported"}; alarm contents omitted")
        appendLine("Fetch times show receipt from the backend. Device report timestamps, when present, are checked for freshness.")
        appendLine("Recent exchanges (max 80):")
        state.exchanges.asReversed().forEach {
            appendLine("${Instant.ofEpochMilli(it.timeMillis)} ${it.operation.name} duration=${it.durationMillis}ms HTTP=${it.httpStatus ?: "none"} API=${it.apiCode ?: "none"} result=${it.failure?.name ?: "success"}")
        }
        appendLine("Recent observations (max 120):")
        state.samples.asReversed().forEach {
            appendLine("${Instant.ofEpochMilli(it.fetchedAtMillis)} " + it.payload.values.toSortedMap().entries.joinToString { field -> "${field.key}=${field.value}" })
        }
        appendLine("Email, login codes, tokens, device IDs/names, network identities, headers and raw responses are excluded.")
    }.take(40_000)
}
