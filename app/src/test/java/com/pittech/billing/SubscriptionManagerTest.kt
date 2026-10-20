package com.pittech.billing

import com.pittech.ads.AdEntitlement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 16 native lane: server-verified purchases on Android. The store and
 * the backend are faked; every entitlement decision under test traces to a
 * scripted verified cache record. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionManagerTest {
    private class FakeBilling(
        override var productId: String = "stub.pro.monthly",
        var launch: ProPurchaseOutcome = ProPurchaseOutcome.Cancelled,
        var owned: List<ProStorePurchase> = emptyList(),
    ) : ProBillingClient {
        var launchCalls = 0
        val acknowledged = mutableListOf<String>()
        override var externalPurchases: ((ProStorePurchase) -> Unit)? = null

        override suspend fun queryProduct(): ProStoreProduct? {
            if (productId.isEmpty()) return null
            return ProStoreProduct(productId, "$3.00", isMonthly = true, offerToken = "stub-offer")
        }

        override suspend fun launchPurchase(activity: android.app.Activity?): ProPurchaseOutcome {
            launchCalls += 1
            return launch
        }

        override suspend fun queryOwnedPurchases(): List<ProStorePurchase> = owned

        override suspend fun acknowledge(purchaseToken: String): Boolean {
            acknowledged.add(purchaseToken)
            return true
        }

        override fun close() {}
    }

    private class FakeAPI(
        var entitlementState: String = "unknown",
        var throwOnVerify: Boolean = false,
        var throwOnEntitlement: Boolean = false,
        var conflictRecovery: String? = null,
        var verifiedEnvironment: String = "production",
    ) : SubscriptionAPI {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject?>()
        var onCall: ((String) -> Unit)? = null

        private fun cache(state: String, verified: Boolean, environment: String = "production"): JSONObject = JSONObject(
            """{"version":"entitlement-cache-v1","environment":"production","verified":$verified,"state":"$state","verifiedUntil":"2026-11-17T12:00:00Z","lastVerifiedAt":"2026-10-18T12:00:00Z","offlineValidUntil":"2026-10-25T12:00:00Z","contractVersion":"store-verification-v1"}""",
        ).put("environment", environment)

        private fun outcome(state: String): JSONObject {
            val outcome = JSONObject()
                .put("cache", cache(state, true, verifiedEnvironment))
                .put("applied", conflictRecovery == null)
                .put("note", "stub-verified")
                .put("transaction", JSONObject().put("store", "google").put("environment", verifiedEnvironment).put("transactionKey", "stub-ref").put("evidenceRef", "a".repeat(64)))
            if (conflictRecovery != null) {
                outcome.put("conflict", JSONObject().put("id", "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa").put("state", "open").put("recovery", conflictRecovery))
            }
            return outcome
        }

        override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
            calls.add(path)
            bodies.add(body)
            onCall?.invoke(path)
            require(access != null)
            return when (path) {
                "/v1/me/entitlement", "/v1/me/entitlement?environment=sandbox" -> run {
                    if (throwOnEntitlement) throw SubscriptionFailure()
                    cache(entitlementState, entitlementState != "unknown", if (path.endsWith("=sandbox")) "sandbox" else "production")
                }
                "/v1/me/entitlement/reconcile" -> cache(entitlementState, entitlementState != "unknown", body!!.getString("environment"))
                "/v1/me/purchases/verify" -> run {
                    if (throwOnVerify) throw SubscriptionFailure()
                    require(body!!.getString("store") == "google")
                    require(body.getString("purchaseToken").isNotEmpty())
                    outcome(if (conflictRecovery == null) "active" else "none")
                }
                "/v1/me/purchases/restore" -> run {
                    require(body!!.getJSONArray("proofs").length() > 0)
                    JSONObject().put("outcomes", JSONArray().put(outcome("active")))
                }
                "/v1/me/purchases/release" -> JSONObject().put("released", true)
                else -> throw SubscriptionFailure()
            }
        }
    }

    private fun manager(
        billing: FakeBilling = FakeBilling(),
        api: FakeAPI = FakeAPI(),
        token: String? = "A".repeat(43),
    ) = Triple(
        SubscriptionManager(billing, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), api, { token }, true, MemorySubscriptionCacheStore(), ownerId = { token?.let { "stub-account" } }),
        billing,
        api,
    )

    @Test
    fun pendingPurchaseBecomesVerifiedActiveThroughTheBackend() = runTest {
        val (manager, billing, api) = manager()
        billing.launch = ProPurchaseOutcome.Pending
        manager.loadProduct()
        manager.refresh()
        manager.subscribe(null)
        assertEquals(ProPurchaseState.PendingStore, manager.presentation.value.purchaseState)
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        assertFalse(api.calls.contains("/v1/me/purchases/verify"))
        // The store later delivers the completed purchase; the backend verifies it.
        manager.start()
        billing.externalPurchases!!.invoke(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        advanceUntilIdle()
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        assertEquals(ProPurchaseState.Idle, manager.presentation.value.purchaseState)
        assertTrue(api.calls.contains("/v1/me/purchases/verify"))
        assertEquals(listOf("stub-token"), billing.acknowledged)
        assertEquals(AdEntitlement.ENTITLED, manager.entitlement.value)
        manager.stop()
    }

    @Test
    fun externalPendingPurchaseDoesNotUnlock() = runTest {
        val (manager, billing, api) = manager()
        manager.refresh()
        manager.start()
        billing.externalPurchases!!.invoke(ProStorePurchase("stub-token", acknowledged = false, pending = true))
        advanceUntilIdle()
        assertFalse(api.calls.contains("/v1/me/purchases/verify"))
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        manager.stop()
    }

    @Test
    fun localCallbackAloneNeverUnlocks() = runTest {
        val (manager, billing, api) = manager(api = FakeAPI(throwOnVerify = true))
        billing.launch = ProPurchaseOutcome.Purchased(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        manager.loadProduct()
        manager.refresh()
        manager.subscribe(null)
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        assertTrue(billing.acknowledged.isEmpty())
        assertTrue(manager.presentation.value.purchaseState is ProPurchaseState.Failed)
    }

    @Test
    fun cancelledPurchaseLeavesEntitlementAlone() = runTest {
        val (manager, billing, api) = manager()
        billing.launch = ProPurchaseOutcome.Cancelled
        manager.loadProduct()
        manager.refresh()
        manager.subscribe(null)
        assertEquals(ProPurchaseState.Idle, manager.presentation.value.purchaseState)
        assertEquals("Purchase cancelled. Nothing was charged.", manager.presentation.value.notice)
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        assertFalse(api.calls.contains("/v1/me/purchases/verify"))
    }

    @Test
    fun alreadyEntitledShowsNoSecondPurchaseOffer() = runTest {
        val (manager, billing, _) = manager(api = FakeAPI(entitlementState = "active"))
        manager.loadProduct()
        manager.refresh()
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        manager.subscribe(null)
        assertEquals(0, billing.launchCalls)
        assertFalse(proShowsPurchaseOffer(manager.presentation.value.entitlement))
    }

    @Test
    fun alreadyOwnedRunsTheRestorePath() = runTest {
        val (manager, billing, api) = manager()
        billing.launch = ProPurchaseOutcome.AlreadyOwned(null)
        billing.owned = listOf(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        manager.loadProduct()
        manager.refresh()
        manager.subscribe(null)
        assertTrue(api.calls.contains("/v1/me/purchases/restore"))
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        assertEquals(listOf("stub-token"), billing.acknowledged)
    }

    @Test
    fun restoreReestablishesEntitlementThroughTheBackend() = runTest {
        val (manager, billing, api) = manager()
        billing.owned = listOf(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        manager.refresh()
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        manager.restore()
        assertTrue(api.calls.contains("/v1/me/purchases/restore"))
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        assertEquals(listOf("stub-token"), billing.acknowledged)
        assertEquals("PitTech Pro is active on this account.", manager.presentation.value.notice)
    }

    @Test
    fun restoreWithoutPurchasesExplainsItself() = runTest {
        val (manager, _, api) = manager()
        manager.refresh()
        manager.restore()
        assertFalse(api.calls.contains("/v1/me/purchases/restore"))
        assertEquals("No Google Play purchases were found for PitTech Pro.", manager.presentation.value.notice)
    }

    @Test
    fun bindingConflictShowsTheServersRecoveryText() = runTest {
        val (manager, billing, _) = manager(api = FakeAPI(conflictRecovery = "This store purchase is already bound to a different PitTech account."))
        billing.launch = ProPurchaseOutcome.Purchased(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        manager.loadProduct()
        manager.refresh()
        manager.subscribe(null)
        assertEquals(ProPurchaseState.Conflict("This store purchase is already bound to a different PitTech account."), manager.presentation.value.purchaseState)
        assertEquals(AdEntitlement.NOT_ENTITLED, manager.presentation.value.entitlement)
        assertEquals(listOf("stub-token"), billing.acknowledged)
    }

    @Test
    fun expiredCacheFallsBackWithoutUnlocking() = runTest {
        val (manager, _, _) = manager(api = FakeAPI(entitlementState = "expired"))
        manager.refresh()
        assertEquals(AdEntitlement.NOT_ENTITLED, manager.presentation.value.entitlement)
        assertEquals(
            ProDestination.EXPIRED_FALLBACK,
            proDestination(true, manager.presentation.value.entitlement, manager.presentation.value.serverState, true),
        )
    }

    @Test
    fun signOutClearsTheCacheSoAccountsNeverShareEntitlement() = runTest {
        var token: String? = "A".repeat(43)
        val api = FakeAPI(entitlementState = "active")
        val manager = SubscriptionManager(FakeBilling(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), api, { token }, true, MemorySubscriptionCacheStore(), ownerId = { token?.let { "stub-account" } })
        manager.refresh()
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        token = null
        manager.refresh()
        assertFalse(manager.presentation.value.signedIn)
        assertNull(manager.presentation.value.cache)
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        assertEquals(AdEntitlement.UNKNOWN, manager.entitlement.value)
    }

    @Test
    fun productNotConfiguredIsGraceful() = runTest {
        val (manager, billing, _) = manager(billing = FakeBilling(productId = ""))
        manager.loadProduct()
        assertEquals(ProProductState.Unavailable, manager.presentation.value.productState)
        assertNull(proBillingText(null))
        manager.refresh()
        manager.subscribe(null)
        assertEquals(0, billing.launchCalls)
        assertEquals(ProPurchaseState.Failed("PitTech Pro isn't available on this build yet."), manager.presentation.value.purchaseState)
    }

    @Test
    fun releasePurchaseReleasesTheHolderBinding() = runTest {
        val (manager, billing, api) = manager()
        billing.launch = ProPurchaseOutcome.Purchased(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        manager.loadProduct()
        manager.refresh()
        manager.subscribe(null)
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        assertTrue(manager.presentation.value.hasRelease)
        manager.releasePurchase()
        assertTrue(api.calls.contains("/v1/me/purchases/release"))
        assertEquals("That purchase was released from this account.", manager.presentation.value.notice)
    }

    @Test
    fun openingAProEntryRecordsOnePresentation() = runTest {
        ProPaywallPresentation.reset()
        val (manager, _, _) = manager(token = null)
        manager.openPro(ProPlacement.ANALYSIS)
        advanceUntilIdle()
        assertEquals(listOf(ProPlacement.ANALYSIS), ProPaywallPresentation.recorded)
        assertEquals(ProPlacement.ANALYSIS, manager.presentation.value.paywallEntry)
        manager.closePaywall()
        assertNull(manager.presentation.value.paywallEntry)
    }

    @Test
    fun sandboxVerificationKeepsRefreshReconcileAndRestartInTheSandboxLane() = runTest {
        val store = MemorySubscriptionCacheStore()
        val api = FakeAPI(verifiedEnvironment = "sandbox")
        val billing = FakeBilling(launch = ProPurchaseOutcome.Purchased(ProStorePurchase("stub-token", false, false)))
        fun create() = SubscriptionManager(billing, this, api, { "A".repeat(43) }, true, store, ownerId = { "account-a" })
        val manager = create()
        manager.loadProduct(); manager.refresh(); manager.subscribe(null)
        assertEquals("sandbox", manager.presentation.value.cache?.environment)
        api.entitlementState = "active"
        manager.refresh()
        assertEquals("/v1/me/entitlement?environment=sandbox", api.calls.last())
        manager.reconcile()
        assertEquals("sandbox", api.bodies.last()!!.getString("environment"))
        val restarted = create()
        restarted.refresh()
        assertEquals("/v1/me/entitlement?environment=sandbox", api.calls.last())
        assertEquals(AdEntitlement.ENTITLED, restarted.entitlement.value)
    }

    @Test
    fun approvedSandboxReadContextDoesNotChangeTheProductionDefault() = runTest {
        val api = FakeAPI(entitlementState = "none")
        val manager = SubscriptionManager(FakeBilling(), this, api, { "A".repeat(43) }, true,
            MemorySubscriptionCacheStore(), ownerId = { "account-a" }, initialEnvironment = SubscriptionEnvironment.SANDBOX)
        manager.refresh(); manager.reconcile()
        assertEquals(listOf("/v1/me/entitlement?environment=sandbox", "/v1/me/entitlement/reconcile"), api.calls)
        assertEquals("sandbox", api.bodies.last()!!.getString("environment"))
        assertEquals(AdEntitlement.NOT_ENTITLED, manager.entitlement.value)
        val (production, _, productionApi) = this@SubscriptionManagerTest.manager()
        production.refresh()
        assertEquals("/v1/me/entitlement", productionApi.calls.last())
    }

    @Test
    fun switchingAccountsClearsPaidCacheAndReleaseReferenceEvenWhenTheServerIsOffline() = runTest {
        var owner = "account-a"
        val api = FakeAPI()
        val billing = FakeBilling(launch = ProPurchaseOutcome.Purchased(ProStorePurchase("stub-token", false, false)))
        val store = MemorySubscriptionCacheStore()
        val manager = SubscriptionManager(billing, this, api, { "A".repeat(43) }, true, store, ownerId = { owner })
        manager.loadProduct(); manager.refresh(); manager.subscribe(null)
        assertEquals(AdEntitlement.ENTITLED, manager.entitlement.value)
        assertTrue(manager.presentation.value.hasRelease)
        owner = "account-b"; api.throwOnEntitlement = true
        manager.refresh()
        assertTrue(manager.presentation.value.signedIn)
        assertNull(manager.presentation.value.cache)
        assertFalse(manager.presentation.value.hasRelease)
        assertEquals(AdEntitlement.UNKNOWN, manager.entitlement.value)
        manager.releasePurchase()
        assertFalse(api.calls.contains("/v1/me/purchases/release"))
        assertNull(store.load("account-b"))
    }

    @Test
    fun offlineAccessTokenRefreshKeepsOnlyTheSameOwnersBoundedCache() = runTest {
        var token: String? = "A".repeat(43)
        var clock = java.time.Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()
        val api = FakeAPI(entitlementState = "active")
        val store = MemorySubscriptionCacheStore()
        fun create(owner: String) = SubscriptionManager(FakeBilling(), this, api, { token }, true, store,
            ownerId = { owner }, now = { clock })
        val manager = create("account-a")
        manager.refresh()
        token = null
        val offline = create("account-a")
        offline.refresh()
        assertTrue(offline.presentation.value.signedIn)
        assertEquals(AdEntitlement.ENTITLED, offline.entitlement.value)
        clock = java.time.Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
        offline.refresh()
        assertEquals(AdEntitlement.UNKNOWN, offline.entitlement.value)
        assertNull(offline.presentation.value.cache)
        val otherAccount = create("account-b")
        otherAccount.refresh()
        assertEquals(AdEntitlement.UNKNOWN, otherAccount.entitlement.value)
        assertNull(store.load("account-b"))
        assertEquals(1, api.calls.size)
    }

    @Test
    fun purchaseResponseAfterAnAccountSwitchCannotUnlockOrAcknowledgeForTheNewOwner() = runTest {
        var owner = "account-a"
        val api = FakeAPI()
        val billing = FakeBilling(launch = ProPurchaseOutcome.Purchased(ProStorePurchase("stub-token", false, false)))
        val manager = SubscriptionManager(billing, this, api, { "A".repeat(43) }, true,
            MemorySubscriptionCacheStore(), ownerId = { owner })
        manager.loadProduct(); manager.refresh()
        api.onCall = { if (it == "/v1/me/purchases/verify") owner = "account-b" }
        manager.subscribe(null)
        assertEquals(AdEntitlement.UNKNOWN, manager.entitlement.value)
        assertNull(manager.presentation.value.cache)
        assertFalse(manager.presentation.value.hasRelease)
        assertTrue(billing.acknowledged.isEmpty())
    }
}
