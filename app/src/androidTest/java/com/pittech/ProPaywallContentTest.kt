package com.pittech

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pittech.ads.AdEntitlement
import com.pittech.ads.AdRequestMonitor
import com.pittech.ads.shouldAttemptAdRequest
import com.pittech.billing.MemorySubscriptionCacheStore
import com.pittech.billing.ProBillingClient
import com.pittech.billing.ProPlacement
import com.pittech.billing.ProProductState
import com.pittech.billing.ProPurchaseOutcome
import com.pittech.billing.ProPurchaseState
import com.pittech.billing.ProStoreProduct
import com.pittech.billing.ProStorePurchase
import com.pittech.billing.SubscriptionAPI
import com.pittech.billing.SubscriptionFailure
import com.pittech.billing.SubscriptionManager
import com.pittech.ui.PitTechTheme
import com.pittech.ui.ProGateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 16 native lane on device UI with a stubbed store product and a
 * scripted verification backend. No Google Play or server is contacted. */
@RunWith(AndroidJUnit4::class)
class ProPaywallContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeBilling(override var productId: String = "stub.pro.monthly") : ProBillingClient {
        var product = ProStoreProduct("stub.pro.monthly", "A$4.99", true, offerToken = "stub-offer")
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

        private fun cache(state: String, verified: Boolean) = JSONObject(
            """{"version":"entitlement-cache-v1","environment":"production","verified":$verified,"state":"$state",""" +
                """"verifiedUntil":"2026-11-17T12:00:00.000Z","lastVerifiedAt":"2026-10-18T12:00:00.000Z",""" +
                """"offlineValidUntil":"2026-10-25T12:00:00.000Z","contractVersion":"store-verification-v1"}""",
        )

        private fun outcome(state: String): JSONObject {
            val tx = JSONObject().put("store", "google").put("environment", "production")
                .put("transactionKey", "stub-token-ref").put("evidenceRef", "a".repeat(64))
            return JSONObject().put("cache", cache(state, true))
                .put("applied", true).put("note", "stub-verified").put("transaction", tx)
        }

        override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
            calls.add(path)
            if (access == null) throw SubscriptionFailure(401)
            return when (path) {
                "/v1/me/entitlement" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/entitlement/reconcile" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/purchases/verify" -> run {
                    if (throwOnVerify) throw SubscriptionFailure()
                    outcome("active")
                }
                "/v1/me/purchases/restore" -> run {
                    JSONObject().put("outcomes", org.json.JSONArray().put(outcome("active")))
                }
                "/v1/me/purchases/release" -> JSONObject().put("released", true)
                else -> throw SubscriptionFailure()
            }
        }
    }

    private val token = "A".repeat(43)

    @Before
    fun resetInstrumentation() {
        AdRequestMonitor.reset()
    }

    private fun manager(scope: CoroutineScope, billing: FakeBilling, api: FakeAPI, token: String? = this.token) =
        SubscriptionManager(billing, scope, api, { token }, true, MemorySubscriptionCacheStore())

    private fun show(manager: SubscriptionManager, previouslyEntitled: Boolean = false) {
        compose.setContent { PitTechTheme { ProGateDialog(manager, previouslyEntitled) {} } }
    }

    @Test
    fun billingTextUsesStubProductLocalizedPrice() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            api.entitlementState = "none"
            val manager = manager(scope, billing, api)
            runBlocking { manager.loadProduct(); manager.refresh() }
            show(manager)
            manager.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-price").performScrollTo().assertIsDisplayed()
                .assertTextContains("A$4.99", substring = true)
            compose.onNodeWithTag("pro-subscribe").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pro-restore").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pro-manage").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pro-privacy").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pro-terms").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pro-paywall-close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isEmpty() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun entitledShowsProContentWithoutPurchaseOffer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            api.entitlementState = "active"
            val manager = manager(scope, billing, api)
            runBlocking { manager.loadProduct(); manager.refresh() }
            show(manager, previouslyEntitled = true)
            manager.openPro(ProPlacement.CURVES)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-placeholder").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
            runBlocking { manager.subscribe(compose.activity) }
            assertEquals(0, billing.launchCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unknownShowsNeutralLoadingWithoutPaywallOrAdRequest() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            api.entitlementState = "unknown"
            val manager = manager(scope, billing, api)
            runBlocking { manager.refresh() }
            show(manager, previouslyEntitled = true)
            manager.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-loading").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
            assertFalse(shouldAttemptAdRequest(manager.presentation.value.entitlement, true))
            assertTrue(AdRequestMonitor.attempts.isEmpty())
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun expiredShowsFreeFallbackWithRecordsIntact() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            api.entitlementState = "expired"
            val manager = manager(scope, billing, api)
            runBlocking { manager.loadProduct(); manager.refresh() }
            show(manager, previouslyEntitled = true)
            manager.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-expired-fallback").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("PitTech Pro ended on this account.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("nothing was deleted", substring = true, ignoreCase = true).assertIsDisplayed()
            compose.onNodeWithTag("pro-resubscribe").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledPurchaseLeavesEntitlementAlone() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            api.entitlementState = "none"
            billing.launchResult = ProPurchaseOutcome.Cancelled
            val manager = manager(scope, billing, api)
            runBlocking { manager.loadProduct(); manager.refresh() }
            show(manager)
            manager.openPro(ProPlacement.MORE)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-subscribe").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-notice").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Purchase cancelled. Nothing was charged.").performScrollTo().assertIsDisplayed()
            assertEquals(AdEntitlement.UNKNOWN, manager.presentation.value.entitlement)
            assertFalse(api.calls.contains("/v1/me/purchases/verify"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pendingPurchaseTransitionsToVerifiedActive() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            api.entitlementState = "none"
            billing.launchResult = ProPurchaseOutcome.Pending
            val manager = manager(scope, billing, api)
            runBlocking { manager.loadProduct(); manager.refresh() }
            manager.start()
            show(manager)
            manager.openPro(ProPlacement.MORE)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-subscribe").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-pending").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-pending").performScrollTo().assertIsDisplayed()
            assertFalse(api.calls.contains("/v1/me/purchases/verify"))
            billing.externalPurchases!!.invoke(ProStorePurchase("stub-token", acknowledged = false, pending = false))
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-placeholder").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(api.calls.contains("/v1/me/purchases/verify"))
            assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
            assertEquals(ProPurchaseState.Idle, manager.presentation.value.purchaseState)
            manager.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun restoreReestablishesEntitlement() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            api.entitlementState = "none"
            billing.owned = listOf(ProStorePurchase("stub-token", acknowledged = false, pending = false))
            val manager = manager(scope, billing, api)
            runBlocking { manager.loadProduct(); manager.refresh() }
            show(manager)
            manager.openPro(ProPlacement.MORE)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-restore").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-placeholder").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(api.calls.contains("/v1/me/purchases/restore"))
            assertEquals(AdEntitlement.ENTITLED, manager.presentation.value.entitlement)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun signedOutPaywallRequiresSignInAndOffersNoPurchase() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val billing = FakeBilling()
            val api = FakeAPI()
            val manager = manager(scope, billing, api, token = null)
            runBlocking { manager.loadProduct(); manager.refresh() }
            show(manager)
            manager.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-signin-required").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            scope.cancel()
        }
    }
}
