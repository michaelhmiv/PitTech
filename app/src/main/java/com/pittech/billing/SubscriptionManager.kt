package com.pittech.billing

import android.app.Activity
import android.content.Context
import com.pittech.ads.AdEntitlement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface ProPurchaseState {
    data object Idle : ProPurchaseState
    data object Purchasing : ProPurchaseState
    data object Verifying : ProPurchaseState
    data object PendingStore : ProPurchaseState
    data class Conflict(val recovery: String) : ProPurchaseState
    data class Failed(val message: String) : ProPurchaseState
}

internal sealed interface ProProductState {
    data object Idle : ProProductState
    data object Loading : ProProductState
    data class Ready(val product: ProStoreProduct) : ProProductState
    data object Unavailable : ProProductState
}

internal data class SubscriptionPresentation(
    val signedIn: Boolean = false,
    val cache: SubscriptionCache? = null,
    val productState: ProProductState = ProProductState.Idle,
    val purchaseState: ProPurchaseState = ProPurchaseState.Idle,
    val notice: String? = null,
    val paywallEntry: ProPlacement? = null,
    val hasRelease: Boolean = false,
) {
    val entitlement: AdEntitlement get() = cache?.entitlement() ?: AdEntitlement.UNKNOWN
    val serverState: String? get() = cache?.state
    val isStorePending: Boolean get() = cache?.state == "pending"
    val busy: Boolean get() = purchaseState is ProPurchaseState.Purchasing || purchaseState is ProPurchaseState.Verifying
}

internal interface SubscriptionCacheStore {
    fun load(): SubscriptionCache?
    fun save(cache: SubscriptionCache)
    fun clear()
}

internal class SharedPrefsSubscriptionCacheStore(context: Context) : SubscriptionCacheStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SubscriptionCache? =
        prefs.getString(KEY_CACHE, null)?.let { runCatching { SubscriptionCache.parse(JSONObject(it)) }.getOrNull() }

    override fun save(cache: SubscriptionCache) {
        prefs.edit().putString(KEY_CACHE, cache.json().toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_CACHE).apply()
    }

    private companion object {
        const val PREFS_NAME = "pittech-subscriptions"
        const val KEY_CACHE = "entitlement_cache_v1"
    }
}

internal class MemorySubscriptionCacheStore : SubscriptionCacheStore {
    var value: SubscriptionCache? = null
    override fun load(): SubscriptionCache? = value
    override fun save(cache: SubscriptionCache) { value = cache }
    override fun clear() { value = null }
}

/** The subscription source of truth. Entitlement changes only from verified
 * server cache records; a local purchase callback alone never unlocks.
 * [entitlement] feeds the ad gate so entitled users trigger zero ad requests
 * from the same state the paywall reads. */
internal class SubscriptionManager(
    private val billing: ProBillingClient,
    private val scope: CoroutineScope,
    private val api: SubscriptionAPI = SubscriptionClient(),
    private val session: suspend () -> String?,
    val accountsEnabled: Boolean,
    private val cacheStore: SubscriptionCacheStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var lastTransaction: Triple<String, String, String>? = null
    private var started = false

    private val _presentation = MutableStateFlow(SubscriptionPresentation())
    val presentation = _presentation.asStateFlow()

    private val _entitlement = MutableStateFlow(AdEntitlement.UNKNOWN)
    val entitlement = _entitlement.asStateFlow()

    /** Fetch the store product, restore the bounded offline cache, refresh a
     * signed-in account, and verify store deliveries. Silent: an active cook
     * is never interrupted by an entitlement transition. */
    fun start() {
        if (started) return
        started = true
        cacheStore.load()?.takeIf { it.offlineUsable(now()) }?.let { applyOffline(it) }
        billing.externalPurchases = { purchase ->
            // A pending store delivery has no charge to verify yet; the store
            // redelivers when it completes.
            if (!purchase.pending) scope.launch { mutex.withLock { verifyLocked(purchase, acknowledgeAfterResponse = true) } }
        }
        scope.launch { mutex.withLock { loadProductLocked() } }
        scope.launch { mutex.withLock { refreshLocked() } }
    }

    fun stop() {
        billing.externalPurchases = null
        billing.close()
    }

    /** Opens the Pro gate for one entry point. Live and Finish flows never
     * call this; no purchase popup interrupts them. Ever. */
    fun openPro(entry: ProPlacement) {
        if (_presentation.value.busy) return
        _presentation.value = _presentation.value.copy(notice = null, paywallEntry = entry)
        ProPaywallPresentation.record(entry)
        scope.launch { mutex.withLock { refreshLocked() } }
    }

    fun closePaywall() {
        val current = _presentation.value
        _presentation.value = current.copy(
            paywallEntry = null,
            notice = null,
            purchaseState = if (current.purchaseState is ProPurchaseState.Failed) ProPurchaseState.Idle else current.purchaseState,
        )
    }

    suspend fun loadProduct() = mutex.withLock { loadProductLocked() }
    suspend fun refresh() = mutex.withLock { refreshLocked() }
    suspend fun reconcile() = mutex.withLock { reconcileLocked() }
    suspend fun subscribe(activity: Activity?) = mutex.withLock { subscribeLocked(activity) }
    suspend fun restore() = mutex.withLock { restoreLocked() }
    suspend fun releasePurchase() = mutex.withLock { releaseLocked() }

    private suspend fun loadProductLocked() {
        if (_presentation.value.productState is ProProductState.Loading) return
        if (billing.productId.isEmpty()) {
            _presentation.value = _presentation.value.copy(productState = ProProductState.Unavailable)
            return
        }
        _presentation.value = _presentation.value.copy(productState = ProProductState.Loading)
        val product = runCatching { billing.queryProduct() }.getOrNull()
        _presentation.value = _presentation.value.copy(
            productState = if (product == null) ProProductState.Unavailable else ProProductState.Ready(product),
        )
    }

    /** Silent refresh. A sign-out clears the cache so one account's
     * entitlement never leaks into another session. */
    private suspend fun refreshLocked() {
        val token = runCatching { session() }.getOrNull()
        if (token == null) {
            signOutLocked()
            return
        }
        _presentation.value = _presentation.value.copy(signedIn = true)
        try {
            applyLocked(SubscriptionCache.parse(api.call("/v1/me/entitlement", null, token)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unverified keeps the provisional offline cache (or unknown);
            // the UI stays in its neutral loading state, never flashing the
            // paywall or ads at a previously entitled user.
        }
    }

    /** Authoritative store re-verification with a converged cache. */
    private suspend fun reconcileLocked() {
        val token = runCatching { session() }.getOrNull()
        if (token == null) {
            signOutLocked()
            return
        }
        _presentation.value = _presentation.value.copy(signedIn = true, purchaseState = ProPurchaseState.Verifying)
        try {
            applyLocked(SubscriptionCache.parse(api.call("/v1/me/entitlement/reconcile", JSONObject(), token)))
            if (_presentation.value.entitlement == AdEntitlement.ENTITLED) {
                _presentation.value = _presentation.value.copy(notice = "PitTech Pro is active on this account.")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("Couldn't check your subscription status. Your local cookbook is unchanged."))
        } finally {
            if (_presentation.value.purchaseState is ProPurchaseState.Verifying) {
                _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
            }
        }
    }

    private fun applyOffline(cache: SubscriptionCache) {
        _presentation.value = _presentation.value.copy(cache = cache)
        _entitlement.value = cache.entitlement(now())
    }

    private fun applyLocked(cache: SubscriptionCache) {
        val resetPending = cache.entitlement(now()) == AdEntitlement.ENTITLED && _presentation.value.purchaseState is ProPurchaseState.PendingStore
        _presentation.value = _presentation.value.copy(
            cache = cache,
            purchaseState = if (resetPending) ProPurchaseState.Idle else _presentation.value.purchaseState,
        )
        _entitlement.value = cache.entitlement(now())
        cacheStore.save(cache)
    }

    private fun signOutLocked() {
        _presentation.value = _presentation.value.copy(signedIn = false, cache = null)
        _entitlement.value = AdEntitlement.UNKNOWN
        cacheStore.clear()
    }

    /** Play Billing purchase bound to the verified PitTech account. The store
     * purchase is sent to the backend for server-side verification; the
     * client never sets entitlement from the local callback alone. */
    private suspend fun subscribeLocked(activity: Activity?) {
        if (_presentation.value.busy) return
        _presentation.value = _presentation.value.copy(notice = null)
        if (_presentation.value.entitlement == AdEntitlement.ENTITLED) return
        val token = runCatching { session() }.getOrNull()
        if (!_presentation.value.signedIn || token == null) {
            _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.Failed(
                    if (accountsEnabled) "Sign in to subscribe with your PitTech account."
                    else "Accounts arrive with community recipes and private sync. Subscribing needs a PitTech account first.",
                ),
            )
            return
        }
        val product = (_presentation.value.productState as? ProProductState.Ready)?.product
        if (product == null || !product.isMonthly) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("PitTech Pro isn't available on this build yet."))
            return
        }
        _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Purchasing)
        when (val outcome = runCatching { billing.launchPurchase(activity) }.getOrElse { ProPurchaseOutcome.Failed("Couldn't reach Google Play. Try again when you're online.") }) {
            is ProPurchaseOutcome.Cancelled -> _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.Idle,
                notice = "Purchase cancelled. Nothing was charged.",
            )
            is ProPurchaseOutcome.Pending -> _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.PendingStore,
                notice = "Your purchase is pending with Google Play. Pro unlocks after the store confirms it; nothing is charged by PitTech.",
            )
            is ProPurchaseOutcome.Purchased -> verifyLocked(outcome.purchase, acknowledgeAfterResponse = true)
            is ProPurchaseOutcome.AlreadyOwned -> {
                // The purchase attempt is over; pivot to the restore flow.
                // restoreLocked() manages its own Verifying state, and its
                // busy guard would otherwise see this Purchasing state and
                // bail immediately, silently dropping the restore.
                _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
                restoreLocked()
            }
            is ProPurchaseOutcome.Failed -> _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed(outcome.message))
        }
    }

    /** Restore queries owned Play purchases, re-sends each for server
     * verification, and re-establishes the same account entitlement. */
    private suspend fun restoreLocked() {
        if (_presentation.value.busy) return
        _presentation.value = _presentation.value.copy(
            notice = null,
            purchaseState = if (_presentation.value.purchaseState is ProPurchaseState.Failed) ProPurchaseState.Idle else _presentation.value.purchaseState,
        )
        val token = runCatching { session() }.getOrNull()
        if (!_presentation.value.signedIn || token == null) {
            _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.Failed(
                    if (accountsEnabled) "Sign in to restore purchases on your PitTech account."
                    else "Accounts arrive with community recipes and private sync. Restoring needs a PitTech account first.",
                ),
            )
            return
        }
        _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Verifying)
        val owned = runCatching { billing.queryOwnedPurchases() }.getOrDefault(emptyList())
        val verifiable = owned.filter { !it.pending }
        if (owned.isEmpty()) {
            _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.Idle,
                notice = "No Google Play purchases were found for PitTech Pro.",
            )
            return
        }
        if (verifiable.isEmpty()) {
            _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.PendingStore,
                notice = "Your purchase is pending with Google Play. Pro unlocks after the store confirms it; nothing is charged by PitTech.",
            )
            return
        }
        try {
            val proofs = JSONArray()
            verifiable.take(10).forEach { proofs.put(JSONObject().put("store", "google").put("purchaseToken", it.purchaseToken)) }
            val response = api.call("/v1/me/purchases/restore", JSONObject().put("proofs", proofs), token)
            val outcomes = response.getJSONArray("outcomes")
            require(outcomes.length() > 0)
            val outcome = SubscriptionOutcome.parse(outcomes.getJSONObject(outcomes.length() - 1))
            applyLocked(outcome.cache)
            remember(outcome)
            if (outcome.conflictRecovery != null) {
                _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Conflict(outcome.conflictRecovery))
            } else {
                _presentation.value = _presentation.value.copy(
                    purchaseState = ProPurchaseState.Idle,
                    notice = if (_presentation.value.entitlement == AdEntitlement.ENTITLED) "PitTech Pro is active on this account."
                    else "Those purchases don't cover PitTech Pro on this account.",
                )
            }
            verifiable.filter { !it.acknowledged }.forEach { runCatching { billing.acknowledge(it.purchaseToken) } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
            throw cancelled
        } catch (_: Exception) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("Restore couldn't reach PitTech. Retry the same request when you're online."))
        }
    }

    private suspend fun verifyLocked(purchase: ProStorePurchase, acknowledgeAfterResponse: Boolean) {
        val token = runCatching { session() }.getOrNull()
        if (token == null) {
            signOutLocked()
            return
        }
        _presentation.value = _presentation.value.copy(signedIn = true, purchaseState = ProPurchaseState.Verifying)
        try {
            val outcome = SubscriptionOutcome.parse(
                api.call("/v1/me/purchases/verify", JSONObject().put("store", "google").put("purchaseToken", purchase.purchaseToken), token),
            )
            applyLocked(outcome.cache)
            remember(outcome)
            if (outcome.conflictRecovery != null) {
                _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Conflict(outcome.conflictRecovery))
            } else {
                _presentation.value = _presentation.value.copy(
                    purchaseState = ProPurchaseState.Idle,
                    notice = if (_presentation.value.entitlement == AdEntitlement.ENTITLED) "PitTech Pro is active on this account." else null,
                )
            }
            if (acknowledgeAfterResponse && !purchase.acknowledged && !purchase.pending) {
                runCatching { billing.acknowledge(purchase.purchaseToken) }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
            throw cancelled
        } catch (_: Exception) {
            // No server response: leave the purchase unacknowledged so Play
            // redelivers it, and never unlock from the local callback.
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("PitTech couldn't verify that purchase yet. It will retry automatically; your local cookbook is unchanged."))
        }
    }

    private fun remember(outcome: SubscriptionOutcome) {
        val store = outcome.store
        val environment = outcome.environment
        val key = outcome.transactionKey
        if (store != null && environment != null && key != null) {
            lastTransaction = Triple(store, environment, key)
            _presentation.value = _presentation.value.copy(hasRelease = true)
        }
    }

    /** The signed-in holder releases its store binding (for example before
     * handing the purchase to another account). The holder's live
     * entitlement ends; the release itself is verified server-side. */
    private suspend fun releaseLocked() {
        if (_presentation.value.busy) return
        _presentation.value = _presentation.value.copy(notice = null)
        val token = runCatching { session() }.getOrNull()
        if (!_presentation.value.signedIn || token == null) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("Sign in to manage this purchase."))
            return
        }
        val ref = lastTransaction
        if (ref == null) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("There's no verified purchase on this account to release."))
            return
        }
        _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Verifying)
        try {
            api.call("/v1/me/purchases/release", JSONObject().put("store", ref.first).put("environment", ref.second).put("transactionKey", ref.third), token)
            _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.Idle,
                notice = "That purchase was released from this account.",
            )
            refreshLocked()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
            throw cancelled
        } catch (_: Exception) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("That purchase couldn't be released. Retry when you're online."))
        }
    }
}
