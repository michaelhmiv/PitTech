package com.pittech.billing

import com.pittech.ads.AdEntitlement
import com.pittech.ads.AdRequestMonitor
import com.pittech.ads.shouldAttemptAdRequest
import com.pittech.ads.shouldRequestAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Slice 16 native lane: paywall gating, server-verified purchases, and the
 * Live/Finish no-interrupt rule. The store and the backend are faked; every
 * entitlement decision under test traces to a scripted verified cache record.
 * Mirrors the iOS ProPaywallTests matrix. */
class ProPaywallTest {
    private class FakeBilling(override var productId: String = "stub.pro.monthly") : ProBillingClient {
        var product = ProStoreProduct("stub.pro.monthly", "$3.00", true, offerToken = "stub-offer")
        var launchResult: ProPurchaseOutcome = ProPurchaseOutcome.Cancelled
        var launchCalls = 0
        var owned: List<ProStorePurchase> = emptyList()
        val acknowledged = mutableListOf<String>()
        var failQuery = false
        override var externalPurchases: ((ProStorePurchase) -> Unit)? = null
        override suspend fun queryProduct(): ProStoreProduct? {
            if (failQuery || productId.isEmpty()) return null
            return product
        }
        override suspend fun launchPurchase(activity: android.app.Activity?): ProPurchaseOutcome {
            launchCalls++
            return launchResult
        }
        override suspend fun queryOwnedPurchases(): List<ProStorePurchase> = owned
        override suspend fun acknowledge(purchaseToken: String): Boolean {
            acknowledged.add(purchaseToken)
            return true
        }
        override fun close() {}
    }

    private class FakeAPI : SubscriptionAPI {
        val calls = mutableListOf<String>()
        var entitlementState = "unknown"
        var throwOnVerify = false
        var throwOnEntitlement = false
        var conflictRecovery: String? = null

        private fun cache(state: String, verified: Boolean) = JSONObject(
            """{"version":"entitlement-cache-v1","environment":"production","verified":$verified,"state":"$state",""" +
                """"verifiedUntil":"2026-11-17T12:00:00.000Z","lastVerifiedAt":"2026-10-18T12:00:00.000Z",""" +
                """"offlineValidUntil":"2026-10-25T12:00:00.000Z","contractVersion":"store-verification-v1"}""",
        )

        private fun outcome(state: String): JSONObject {
            val tx = JSONObject().put("store", "google").put("environment", "production")
                .put("transactionKey", "stub-token-ref").put("evidenceRef", "a".repeat(64))
            val result = JSONObject().put("cache", cache(state, true))
                .put("applied", conflictRecovery == null).put("note", "stub-verified").put("transaction", tx)
            conflictRecovery?.let {
                result.put("conflict", JSONObject().put("id", "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa").put("state", "open").put("recovery", it))
            }
            return result
        }

        override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
            calls.add(path)
            if (access == null) throw SubscriptionFailure(401)
            return when (path) {
                "/v1/me/entitlement" -> run {
                    if (throwOnEntitlement) throw SubscriptionFailure()
                    cache(entitlementState, entitlementState != "unknown")
                }
                "/v1/me/entitlement/reconcile" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/purchases/verify" -> run {
                    if (throwOnVerify) throw SubscriptionFailure()
                    assertEquals("google", body!!.getString("store"))
                    assertTrue(body.getString("purchaseToken").isNotEmpty())
                    outcome(if (conflictRecovery == null) "active" else "none")
                }
                "/v1/me/purchases/restore" -> run {
                    assertTrue(body!!.getJSONArray("proofs").length() > 0)
                    JSONObject().put("outcomes", org.json.JSONArray().put(outcome("active")))
                }
                "/v1/me/purchases/release" -> JSONObject().put("released", true)
                else -> throw SubscriptionFailure()
            }
        }
    }

    private class SessionBox(var token: String?)

    private val token = "A".repeat(43)

    private fun manager(
        scope: CoroutineScope,
        billing: FakeBilling = FakeBilling(),
        api: FakeAPI = FakeAPI(),
        token: String? = this.token,
    ): Triple<SubscriptionManager, FakeBilling, FakeAPI> {
        val manager = SubscriptionManager(
            billing = billing,
            scope = scope,
            api = api,
            session = { token },
            accountsEnabled = true,
            cacheStore = MemorySubscriptionCacheStore(),
        )
        return Triple(manager, billing, api)
    }

    @Before
    fun resetMonitor() {
        AdRequestMonitor.reset()
        ProPaywallPresentation.reset()
    }

    @Test
    fun billingTextComesFromTheStoreProduct() {
        val product = ProStoreProduct("stub.pro.monthly", "A$4.99", true)
        val text = proBillingText(product)
        assertNotNull(text)
        assertTrue(text!!.contains("A$4.99"))
        assertTrue(text.contains("per month"))
        assertNull(proBillingText(null))
        assertNull(proBillingText(ProStoreProduct("", "$3.00", true)))
        assertNull(proBillingText(ProStoreProduct("stub.pro.monthly", "$3.00", false)))
        assertNull(proBillingText(ProStoreProduct("stub.pro.monthly", "", true)))
    }

    @Test
    fun gateMatrixRoutesEveryState() {
        assertEquals(ProDestination.PAYWALL, proDestination(false, AdEntitlement.UNKNOWN, null, false))
        assertEquals(ProDestination.PAYWALL, proDestination(false, AdEntitlement.ENTITLED, "active", true))
        assertEquals(ProDestination.LOADING, proDestination(true, AdEntitlement.UNKNOWN, "unknown", true))
        assertEquals(ProDestination.LOADING, proDestination(true, AdEntitlement.UNKNOWN, "unknown", false))
        assertEquals(ProDestination.PRO, proDestination(true, AdEntitlement.ENTITLED, "active", true))
        assertEquals(ProDestination.PAYWALL, proDestination(true, AdEntitlement.NOT_ENTITLED, "none", false))
        assertEquals(ProDestination.PAYWALL, proDestination(true, AdEntitlement.NOT_ENTITLED, "pending", false))
        for (state in listOf("expired", "cancelled", "refunded", "revoked")) {
            assertEquals(ProDestination.EXPIRED_FALLBACK, proDestination(true, AdEntitlement.NOT_ENTITLED, state, true))
        }
        assertFalse(proShowsPurchaseOffer(AdEntitlement.ENTITLED))
        assertTrue(proShowsPurchaseOffer(AdEntitlement.NOT_ENTITLED))
        assertTrue(proShowsPurchaseOffer(AdEntitlement.UNKNOWN))
    }

    @Test
    fun unknownNeverFlashesPaywallOrAdsForPreviouslyEntitledUsers() = runBlocking {
        val (manager, _, api) = manager(this)
        api.entitlementState = "unknown"
        manager.refresh()
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        assertEquals(
            ProDestination.LOADING,
            proDestination(true, manager.presentation.value.entitlement, manager.presentation.value.serverState, true),
        )
        assertFalse(shouldRequestAds(manager.presentation.value.entitlement, true))
        assertFalse(shouldAttemptAdRequest(manager.presentation.value.entitlement, true))
        assertTrue(AdRequestMonitor.attempts.isEmpty())
    }

    @Test
    fun entitledStateTriggersZeroAdRequestsThroughTheSharedGate() = runBlocking {
        val (manager, _, api) = manager(this)
        api.entitlementState = "active"
        manager.refresh()
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        assertFalse(shouldAttemptAdRequest(manager.presentation.value.entitlement, true))
        assertTrue(AdRequestMonitor.attempts.isEmpty())
    }

    @Test
    fun externalPurchaseBecomesVerifiedActiveThroughTheBackend() = runBlocking {
        val billing = FakeBilling()
        val api = FakeAPI()
        val (manager, _, _) = manager(this, billing, api)
        manager.loadProduct()
        manager.refresh()
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        // The plain cache read stays failing so only the verify path can unlock.
        api.throwOnEntitlement = true
        manager.start()
        billing.externalPurchases!!.invoke(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.presentation.value.entitlement != AdEntitlement.ENTITLED && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        assertEquals(ProPurchaseState.Idle, manager.presentation.value.purchaseState)
        assertTrue(api.calls.contains("/v1/me/purchases/verify"))
        assertEquals(listOf("stub-token"), billing.acknowledged)
        manager.stop()
    }

    @Test
    fun localPurchaseCallbackAloneNeverUnlocks() = runBlocking {
        val billing = FakeBilling()
        val api = FakeAPI()
        api.throwOnVerify = true
        val (manager, _, _) = manager(this, billing, api)
        manager.loadProduct()
        manager.refresh()
        manager.start()
        billing.externalPurchases!!.invoke(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.presentation.value.purchaseState !is ProPurchaseState.Failed && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        assertTrue(billing.acknowledged.isEmpty())
        assertTrue(manager.presentation.value.purchaseState is ProPurchaseState.Failed)
        manager.stop()
    }

    @Test
    fun restoreReestablishesEntitlementThroughTheBackend() = runBlocking {
        val billing = FakeBilling()
        val api = FakeAPI()
        billing.owned = listOf(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        val (manager, _, _) = manager(this, billing, api)
        manager.refresh()
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
        manager.restore()
        assertTrue(api.calls.contains("/v1/me/purchases/restore"))
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        assertEquals(listOf("stub-token"), billing.acknowledged)
        assertEquals("PitTech Pro is active on this account.", manager.presentation.value.notice)
    }

    @Test
    fun restoreWithoutPurchasesExplainsItself() = runBlocking {
        val (manager, _, api) = manager(this)
        manager.refresh()
        manager.restore()
        assertFalse(api.calls.contains("/v1/me/purchases/restore"))
        assertEquals("No Google Play purchases were found for PitTech Pro.", manager.presentation.value.notice)
    }

    @Test
    fun restoreWithOnlyPendingPurchasesWaitsForTheStore() = runBlocking {
        val billing = FakeBilling()
        val api = FakeAPI()
        billing.owned = listOf(ProStorePurchase("stub-token", acknowledged = false, pending = true))
        val (manager, _, _) = manager(this, billing, api)
        manager.refresh()
        manager.restore()
        assertFalse(api.calls.contains("/v1/me/purchases/restore"))
        assertEquals(ProPurchaseState.PendingStore, manager.presentation.value.purchaseState)
    }

    @Test
    fun bindingConflictShowsTheServersRecoveryText() = runBlocking {
        val billing = FakeBilling()
        val api = FakeAPI()
        api.conflictRecovery = "This store purchase is already bound to a different PitTech account."
        val (manager, _, _) = manager(this, billing, api)
        manager.refresh()
        manager.start()
        billing.externalPurchases!!.invoke(ProStorePurchase("stub-token", acknowledged = false, pending = false))
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.presentation.value.purchaseState !is ProPurchaseState.Conflict && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        assertEquals(
            ProPurchaseState.Conflict("This store purchase is already bound to a different PitTech account."),
            manager.presentation.value.purchaseState,
        )
        assertNotEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        manager.stop()
    }

    @Test
    fun expiredCacheFallsBackWithoutUnlocking() = runBlocking {
        val (manager, _, api) = manager(this)
        api.entitlementState = "expired"
        manager.refresh()
        assertEquals(AdEntitlement.NOT_ENTITLED, manager.presentation.value.entitlement)
        assertEquals(
            ProDestination.EXPIRED_FALLBACK,
            proDestination(true, manager.presentation.value.entitlement, manager.presentation.value.serverState, true),
        )
    }

    @Test
    fun signOutClearsTheCacheSoAccountsNeverShareEntitlement() = runBlocking {
        val api = FakeAPI()
        api.entitlementState = "active"
        val box = SessionBox(token)
        val manager = SubscriptionManager(
            billing = FakeBilling(),
            scope = this,
            api = api,
            session = { box.token },
            accountsEnabled = true,
            cacheStore = MemorySubscriptionCacheStore(),
        )
        manager.refresh()
        assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        box.token = null
        manager.refresh()
        assertFalse(manager.presentation.value.signedIn)
        assertNull(manager.presentation.value.cache)
        assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
    }

    @Test
    fun productNotConfiguredIsGraceful() = runBlocking {
        val billing = FakeBilling()
        billing.productId = ""
        billing.failQuery = true
        val (manager, _, _) = manager(this, billing)
        manager.loadProduct()
        assertEquals(ProProductState.Unavailable, manager.presentation.value.productState)
        assertNull(proBillingText(null))
        manager.refresh()
        assertEquals(ProProductState.Unavailable, manager.presentation.value.productState)
    }

    @Test
    fun cacheStoreRoundTripPreservesTheOfflineWindow() {
        val store = MemorySubscriptionCacheStore()
        val cache = SubscriptionCache.parse(
            JSONObject(
                """{"version":"entitlement-cache-v1","environment":"production","verified":true,"state":"active",""" +
                    """"verifiedUntil":"2026-11-17T12:00:00.000Z","lastVerifiedAt":"2026-10-18T12:00:00.000Z",""" +
                    """"offlineValidUntil":"2026-10-25T12:00:00.000Z","contractVersion":"store-verification-v1"}""",
            ),
        )
        store.save(cache)
        val loaded = store.load()!!
        assertEquals(cache, loaded)
        assertTrue(loaded.offlineUsable(1_792_324_800_000L))
        assertFalse(loaded.offlineUsable(1_792_324_800_000L + 604_801_000L))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun sharedEntitlementCacheFixtureParses() {
        val json = javaClass.classLoader!!.getResourceAsStream("entitlement-cache-v1.json")!!.bufferedReader().use { it.readText() }
        val cache = SubscriptionCache.parse(JSONObject(json))
        assertEquals("entitlement-cache-v1", cache.version)
        assertEquals("store-verification-v1", cache.contractVersion)
        assertEquals(AdEntitlement.ENTITLED, cache.entitlement(1_792_324_800_000L))
        assertTrue(cache.offlineUsable(1_792_324_800_000L))
    }

    @Test
    fun openingAProEntryRecordsOnePresentation() = runBlocking {
        val (manager, _, _) = manager(this, token = null)
        manager.openPro(ProPlacement.ANALYSIS)
        assertEquals(listOf(ProPlacement.ANALYSIS), ProPaywallPresentation.recorded)
        assertEquals(ProPlacement.ANALYSIS, manager.presentation.value.paywallEntry)
        manager.closePaywall()
        assertNull(manager.presentation.value.paywallEntry)
    }
}
