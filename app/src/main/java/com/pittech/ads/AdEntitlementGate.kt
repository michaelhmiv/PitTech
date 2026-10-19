package com.pittech.ads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Versioned entitlement cache contract shared with the server (`entitlement-cache-v1`). */
internal const val ENTITLEMENT_CACHE_VERSION = "entitlement-cache-v1"
internal const val ENTITLEMENT_CONTRACT_VERSION = "store-verification-v1"
internal const val ENTITLEMENT_OFFLINE_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

/** Verified entitlement behind the ad-request gate. UNKNOWN means the server has
 * not verified this account yet; it is distinct from NOT_ENTITLED. */
internal enum class AdEntitlement {
    UNKNOWN,
    NOT_ENTITLED,
    ENTITLED,
}

private val AD_FREE_SERVER_STATES = setOf("active", "grace", "retry", "paused")

/** Map one versioned server cache record to the gate input. Unverified records,
 * version mismatches and unrecognized states all map to UNKNOWN so the gate
 * defers to the previously-entitled flag instead of guessing. */
internal fun adEntitlementFromServerCache(
    verified: Boolean,
    state: String,
    verifiedUntilMillis: Long?,
    cacheVersion: String = ENTITLEMENT_CACHE_VERSION,
    contractVersion: String = ENTITLEMENT_CONTRACT_VERSION,
    nowMillis: Long = System.currentTimeMillis(),
): AdEntitlement {
    if (!verified || cacheVersion != ENTITLEMENT_CACHE_VERSION || contractVersion != ENTITLEMENT_CONTRACT_VERSION) {
        return AdEntitlement.UNKNOWN
    }
    if (state in AD_FREE_SERVER_STATES) return AdEntitlement.ENTITLED
    if (state == "cancelled" && verifiedUntilMillis != null && verifiedUntilMillis > nowMillis) {
        return AdEntitlement.ENTITLED
    }
    if (state == "none" || state == "pending" || state == "expired" || state == "cancelled" || state == "refunded" || state == "revoked") {
        return AdEntitlement.NOT_ENTITLED
    }
    return AdEntitlement.UNKNOWN
}

/** The ad-request gate. Entitled users trigger zero ad requests. While
 * entitlement is unverified, the gate holds requests for a previously entitled
 * account (fail closed) rather than flashing ads at a subscriber. */
internal fun shouldRequestAds(entitlement: AdEntitlement, previouslyEntitled: Boolean): Boolean =
    when (entitlement) {
        AdEntitlement.ENTITLED -> false
        AdEntitlement.NOT_ENTITLED -> true
        AdEntitlement.UNKNOWN -> !previouslyEntitled
    }

/** CI instrumentation: records every ad-request attempt the gate allows so the
 * suite can assert zero requests under entitlement. */
internal object AdRequestMonitor {
    private val lock = Any()
    private val attemptsMutable = mutableListOf<Long>()

    val attempts: List<Long>
        get() = synchronized(lock) { attemptsMutable.toList() }

    fun recordAttempt(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) { attemptsMutable.add(nowMillis) }
    }

    fun reset() {
        synchronized(lock) { attemptsMutable.clear() }
    }
}

/** Gate a single ad request: record the attempt when allowed, stay silent when
 * held. Call sites check this immediately before every ad SDK request. */
internal fun shouldAttemptAdRequest(
    entitlement: AdEntitlement,
    previouslyEntitled: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (!shouldRequestAds(entitlement, previouslyEntitled)) return false
    AdRequestMonitor.recordAttempt(nowMillis)
    return true
}

/** Durable previously-entitled flag. A subscriber who is merely unverified
 * (offline, fresh install, slow backend) must not see ads flash. */
internal interface AdEntitlementPrefs {
    fun getPreviouslyEntitled(): Boolean
    fun setPreviouslyEntitled()
}

internal class SharedPrefsAdEntitlementPrefs(context: Context) : AdEntitlementPrefs {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getPreviouslyEntitled(): Boolean = prefs.getBoolean(KEY_PREVIOUSLY_ENTITLED, false)

    override fun setPreviouslyEntitled() {
        prefs.edit().putBoolean(KEY_PREVIOUSLY_ENTITLED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "pittech-ads"
        const val KEY_PREVIOUSLY_ENTITLED = "previously_entitled"
    }
}

/** Holds the current verified entitlement for the ad gate. Until the account
 * backend verifies a subscription this stays UNKNOWN and ads behave as today. */
internal class AdEntitlementStore(prefs: AdEntitlementPrefs) {
    private val entitlementPrefs = prefs

    private val entitlementMutable = MutableStateFlow(AdEntitlement.UNKNOWN)
    val entitlement: StateFlow<AdEntitlement> = entitlementMutable.asStateFlow()

    private val previouslyEntitledMutable = MutableStateFlow(prefs.getPreviouslyEntitled())
    val previouslyEntitled: StateFlow<Boolean> = previouslyEntitledMutable.asStateFlow()

    fun update(entitlement: AdEntitlement) {
        entitlementMutable.value = entitlement
        if (entitlement == AdEntitlement.ENTITLED && !previouslyEntitledMutable.value) {
            entitlementPrefs.setPreviouslyEntitled()
            previouslyEntitledMutable.value = true
        }
    }
}
