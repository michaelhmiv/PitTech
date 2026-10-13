package com.pittech.devices

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Response

internal enum class TelemetryFailureKind { AUTHORIZATION, RATE_LIMIT, NETWORK, CONFIGURATION }
internal class TelemetryRequestException(
    val kind: TelemetryFailureKind,
    val retryAtMillis: Long? = null,
) : IOException(when (kind) {
    TelemetryFailureKind.AUTHORIZATION -> "Sign in again to reconnect this account."
    TelemetryFailureKind.RATE_LIMIT -> "The provider has asked PitTech to wait before reading again."
    TelemetryFailureKind.NETWORK -> "The provider is unavailable. PitTech will try again."
    TelemetryFailureKind.CONFIGURATION -> "Check the connection settings."
})

internal object TelemetryHttpPolicy {
    fun check(response: Response, now: Long = System.currentTimeMillis(), defaultCooldown: Long = 300_000L) {
        if (response.isSuccessful) return
        throw when (response.code) {
            401, 403 -> TelemetryRequestException(TelemetryFailureKind.AUTHORIZATION)
            429 -> TelemetryRequestException(TelemetryFailureKind.RATE_LIMIT,
                retryAt(response.header("Retry-After"), now, defaultCooldown))
            in 300..399, 400, 404 -> TelemetryRequestException(TelemetryFailureKind.CONFIGURATION)
            else -> TelemetryRequestException(TelemetryFailureKind.NETWORK)
        }
    }
    fun retryAt(header: String?, now: Long, fallback: Long): Long {
        val seconds = header?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }
        val dated = header?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        return (seconds?.let { now + it * 1000L } ?: dated ?: (now + fallback))
            .coerceIn(now + 1000L, now + 86_400_000L)
    }
}

/** Charge failed requests too. Call while holding the provider's shared account mutex. */
internal class TelemetryAccountBudget(private val spacing: Long) {
    private var nextAttempt = Long.MIN_VALUE
    fun reserve(now: Long) {
        if (now < nextAttempt) throw TelemetryRequestException(TelemetryFailureKind.RATE_LIMIT, nextAttempt)
        nextAttempt = now + spacing
    }
    fun failed(error: Exception, now: Long) {
        if (error is TelemetryRequestException && error.kind == TelemetryFailureKind.RATE_LIMIT)
            nextAttempt = maxOf(nextAttempt, error.retryAtMillis ?: (now + 300_000L))
    }
}
