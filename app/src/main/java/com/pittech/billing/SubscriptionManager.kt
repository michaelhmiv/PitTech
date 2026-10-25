package com.pittech.billing

import com.pittech.online.OnlineConfiguration

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
    fun load(ownerId: String): SubscriptionCache?
    fun save(ownerId: String, cache: SubscriptionCache)
    fun clear()
}

internal class SharedPrefsSubscriptionCacheStore(context: Context) : SubscriptionCacheStore {
    private val prefs = context.getSharedPreferences(OnlineConfiguration.current.scopedName(PREFS_NAME), Context.MODE_PRIVATE)

    override fun load(ownerId: String): SubscriptionCache? {
        // The old cache has no account identity and cannot safely be adopted.
        prefs.edit().remove(LEGACY_KEY_CACHE).apply()
        val saved = prefs.getString(KEY_CACHE, null) ?: return null
        return runCatching {
            val envelope = JSONObject(saved)
            require(envelope.getString("ownerId") == ownerId)
            SubscriptionCache.parse(envelope.getJSONObject("cache"))
        }.getOrElse { clear(); null }
    }

    override fun save(ownerId: String, cache: SubscriptionCache) {
        require(ownerId.isNotEmpty())
        prefs.edit().remove(LEGACY_KEY_CACHE).putString(KEY_CACHE,
            JSONObject().put("ownerId", ownerId).put("cache", cache.json()).toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_CACHE).remove(LEGACY_KEY_CACHE).apply()
    }

    private companion object {
        const val PREFS_NAME = "pittech-subscriptions"
        const val KEY_CACHE = "entitlement_cache_v2"
        const val LEGACY_KEY_CACHE = "entitlement_cache_v1"
    }
}

internal class MemorySubscriptionCacheStore : SubscriptionCacheStore {
    private var ownerId: String? = null
    var value: SubscriptionCache? = null
    override fun load(ownerId: String): SubscriptionCache? = if (this.ownerId == ownerId) value else { clear(); null }
    override fun save(ownerId: String, cache: SubscriptionCache) { this.ownerId = ownerId; value = cache }
    override fun clear() { ownerId = null; value = null }
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
    private val ownerId: suspend () -> String?,
    private val initialEnvironment: SubscriptionEnvironment = if (OnlineConfiguration.current.sandbox) SubscriptionEnvironment.SANDBOX else SubscriptionEnvironment.PRODUCTION,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var lastTransaction: Triple<String, String, String>? = null
    private var started = false
    private var currentOwner: String? = null
    private var readEnvironment = initialEnvironment
    private data class Account(val ownerId: String, val token: String)

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

    /** The destination a paywall sign-in detour must return to (Slice 8,
     * PL-15 / DD-10). [beginSignInDetour] parks the open entry and closes
     * the gate so the account surface is reachable; when that surface
     * closes — sign-in completed OR cancelled — [resumeAfterSignInDetour]
     * reopens the gate on the same entry and the destination is recomputed
     * from the now-current account state. Without a detour both calls are
     * no-ops, so the account surface closing for any other reason never
     * conjures a paywall. */
    private var signInResumeEntry: ProPlacement? = null

    fun beginSignInDetour() {
        val entry = _presentation.value.paywallEntry ?: return
        signInResumeEntry = entry
        _presentation.value = _presentation.value.copy(paywallEntry = null, notice = null)
    }

    fun resumeAfterSignInDetour() {
        val entry = signInResumeEntry ?: return
        signInResumeEntry = null
        openPro(entry)
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

    /** Identity is independent of access-token refresh: an offline refresh
     * failure may keep this account's bounded cache, but never another's. */
    private suspend fun synchronizeOwner(): String? {
        val owner = ownerId()
        if (owner == null) {
            signOutLocked()
        } else if (owner != currentOwner) {
            if (currentOwner != null) cacheStore.clear()
            currentOwner = owner
            readEnvironment = initialEnvironment
            lastTransaction = null
            _presentation.value = _presentation.value.copy(signedIn = true, cache = null,
                purchaseState = ProPurchaseState.Idle, notice = null, hasRelease = false)
            _entitlement.value = AdEntitlement.UNKNOWN
            cacheStore.load(owner)?.let { saved ->
                readEnvironment = SubscriptionEnvironment.from(saved.environment)
                if (saved.offlineUsable(now())) applyOffline(saved)
            }
        }
        return owner
    }

    private fun keepBoundedOfflineCache() {
        if (_presentation.value.cache?.offlineUsable(now()) != true) {
            _presentation.value = _presentation.value.copy(cache = null)
            _entitlement.value = AdEntitlement.UNKNOWN
        }
    }

    private suspend fun account(): Account? {
        val owner = synchronizeOwner() ?: return null
        val token = try { session() } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        if (synchronizeOwner() != owner) return null
        if (token == null) { keepBoundedOfflineCache(); return null }
        return Account(owner, token)
    }

    private suspend fun isCurrent(account: Account): Boolean = synchronizeOwner() == account.ownerId

    /** Silent refresh reads only the current account and selected store lane. */
    private suspend fun refreshLocked() {
        val account = account() ?: return
        val environment = readEnvironment
        try {
            val cache = SubscriptionCache.parse(api.call(environment.entitlementPath, null, account.token))
            if (!isCurrent(account)) return
            require(cache.environment == environment.wireValue)
            applyLocked(cache, account.ownerId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (isCurrent(account)) keepBoundedOfflineCache()
        }
    }

    /** Authoritative store re-verification with a converged cache. */
    private suspend fun reconcileLocked() {
        val account = account() ?: return
        val environment = readEnvironment
        _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Verifying)
        try {
            val cache = SubscriptionCache.parse(api.call("/v1/me/entitlement/reconcile",
                JSONObject().put("environment", environment.wireValue), account.token))
            if (!isCurrent(account)) return
            require(cache.environment == environment.wireValue)
            applyLocked(cache, account.ownerId)
            if (_presentation.value.entitlement == AdEntitlement.ENTITLED) {
                _presentation.value = _presentation.value.copy(notice = "PitTech Pro is active on this account.")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!isCurrent(account)) return
            keepBoundedOfflineCache()
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

    private fun applyLocked(cache: SubscriptionCache, owner: String) {
        check(owner == currentOwner)
        readEnvironment = SubscriptionEnvironment.from(cache.environment)
        val resetPending = cache.entitlement(now()) == AdEntitlement.ENTITLED && _presentation.value.purchaseState is ProPurchaseState.PendingStore
        _presentation.value = _presentation.value.copy(
            cache = cache,
            purchaseState = if (resetPending) ProPurchaseState.Idle else _presentation.value.purchaseState,
        )
        _entitlement.value = cache.entitlement(now())
        cacheStore.save(owner, cache)
    }

    private fun signOutLocked() {
        currentOwner = null
        readEnvironment = initialEnvironment
        lastTransaction = null
        _presentation.value = _presentation.value.copy(signedIn = false, cache = null,
            purchaseState = ProPurchaseState.Idle, notice = null, hasRelease = false)
        _entitlement.value = AdEntitlement.UNKNOWN
        cacheStore.clear()
    }

    /** Play Billing purchase bound to the verified PitTech account. The store
     * purchase is sent to the backend for server-side verification; the
     * client never sets entitlement from the local callback alone. */
    private suspend fun subscribeLocked(activity: Activity?) {
        if (_presentation.value.busy) return
        _presentation.value = _presentation.value.copy(notice = null)
        val account = account()
        if (_presentation.value.entitlement == AdEntitlement.ENTITLED) return
        if (account == null) {
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
        val outcome = try { billing.launchPurchase(activity) } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (isCurrent(account)) _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
            throw cancelled
        } catch (_: Exception) { ProPurchaseOutcome.Failed("Couldn't reach Google Play. Try again when you're online.") }
        if (!isCurrent(account)) return
        when (outcome) {
            is ProPurchaseOutcome.Cancelled -> _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.Idle,
                notice = "Purchase cancelled. Nothing was charged.",
            )
            is ProPurchaseOutcome.Pending -> _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.PendingStore,
                notice = "Your purchase is pending with Google Play. Pro unlocks after the store confirms it; nothing is charged by PitTech.",
            )
            is ProPurchaseOutcome.Purchased -> verifyLocked(outcome.purchase, acknowledgeAfterResponse = true, expectedOwner = account.ownerId)
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
        val account = account()
        if (account == null) {
            _presentation.value = _presentation.value.copy(
                purchaseState = ProPurchaseState.Failed(
                    if (accountsEnabled) "Sign in to restore purchases on your PitTech account."
                    else "Accounts arrive with community recipes and private sync. Restoring needs a PitTech account first.",
                ),
            )
            return
        }
        _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Verifying)
        val owned = try { billing.queryOwnedPurchases() } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (isCurrent(account)) _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
            throw cancelled
        } catch (_: Exception) {
            if (isCurrent(account)) _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("Couldn't reach Google Play. Try again when you're online."))
            return
        }
        if (!isCurrent(account)) return
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
            val response = api.call("/v1/me/purchases/restore", JSONObject().put("proofs", proofs), account.token)
            if (!isCurrent(account)) return
            val outcomes = response.getJSONArray("outcomes")
            require(outcomes.length() > 0)
            val outcome = SubscriptionOutcome.parse(outcomes.getJSONObject(outcomes.length() - 1))
            applyLocked(outcome.cache, account.ownerId)
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
            if (!isCurrent(account)) return
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("Restore couldn't reach PitTech. Retry the same request when you're online."))
        }
    }

    private suspend fun verifyLocked(purchase: ProStorePurchase, acknowledgeAfterResponse: Boolean, expectedOwner: String? = null) {
        val account = account() ?: return
        if (expectedOwner != null && account.ownerId != expectedOwner) return
        _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Verifying)
        try {
            val outcome = SubscriptionOutcome.parse(
                api.call("/v1/me/purchases/verify", JSONObject().put("store", "google").put("purchaseToken", purchase.purchaseToken), account.token),
            )
            if (!isCurrent(account)) return
            applyLocked(outcome.cache, account.ownerId)
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
            if (!isCurrent(account)) return
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
        val account = account()
        if (account == null) {
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
            api.call("/v1/me/purchases/release", JSONObject().put("store", ref.first).put("environment", ref.second).put("transactionKey", ref.third), account.token)
            if (!isCurrent(account)) return
            lastTransaction = null
            cacheStore.clear()
            _entitlement.value = AdEntitlement.UNKNOWN
            _presentation.value = _presentation.value.copy(
                cache = null,
                hasRelease = false,
                purchaseState = ProPurchaseState.Idle,
                notice = "That purchase was released from this account.",
            )
            refreshLocked()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Idle)
            throw cancelled
        } catch (_: Exception) {
            if (!isCurrent(account)) return
            _presentation.value = _presentation.value.copy(purchaseState = ProPurchaseState.Failed("That purchase couldn't be released. Retry when you're online."))
        }
    }
}
