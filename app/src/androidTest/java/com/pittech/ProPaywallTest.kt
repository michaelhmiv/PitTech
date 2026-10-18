package com.pittech

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.ads.AdRequestMonitor
import com.pittech.ads.AdEntitlement
import com.pittech.ads.shouldAttemptAdRequest
import com.pittech.billing.MemorySubscriptionCacheStore
import com.pittech.billing.ProBillingClient
import com.pittech.billing.ProPlacement
import com.pittech.billing.ProPurchaseOutcome
import com.pittech.billing.ProStoreProduct
import com.pittech.billing.ProStorePurchase
import com.pittech.billing.SubscriptionAPI
import com.pittech.billing.SubscriptionManager
import com.pittech.ui.PitTechTheme
import com.pittech.ui.ProGateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Slice 16 native lane: the contextual paywall on Android UI. The store
 * product and the backend are stubbed; no Google Play or server is contacted. */
@RunWith(AndroidJUnit4::class)
class ProPaywallTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeBilling(
        override var productId: String = "stub.pro.monthly",
        var owned: List<ProStorePurchase> = emptyList(),
    ) : ProBillingClient {
        override var externalPurchases: ((ProStorePurchase) -> Unit)? = null

        override suspend fun queryProduct(): ProStoreProduct? {
            if (productId.isEmpty()) return null
            return ProStoreProduct(productId, "$3.00", isMonthly = true, offerToken = "stub-offer")
        }

        override suspend fun launchPurchase(activity: android.app.Activity?): ProPurchaseOutcome = ProPurchaseOutcome.Cancelled
        override suspend fun queryOwnedPurchases(): List<ProStorePurchase> = owned
        override suspend fun acknowledge(purchaseToken: String): Boolean = true
        override fun close() {}
    }

    private class FakeAPI(var entitlementState: String = "none") : SubscriptionAPI {
        val calls = mutableListOf<String>()

        private fun cache(state: String, verified: Boolean): JSONObject = JSONObject(
            """{"version":"entitlement-cache-v1","environment":"production","verified":$verified,"state":"$state","verifiedUntil":"2026-11-17T12:00:00Z","lastVerifiedAt":"2026-10-18T12:00:00Z","offlineValidUntil":"2026-10-25T12:00:00Z","contractVersion":"store-verification-v1"}",
        )

        override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
            calls.add(path)
            require(access != null)
            return when (path) {
                "/v1/me/entitlement" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/entitlement/reconcile" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/purchases/restore" -> JSONObject().put("outcomes", JSONArray().put(JSONObject().put("cache", cache("active", true)).put("applied", true).put("note", "stub-verified")))
                else -> throw IllegalStateException("Unexpected local test path")
            }
        }
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun manager(billing: FakeBilling = FakeBilling(), api: FakeAPI = FakeAPI(), token: String? = "A".repeat(43), accountsEnabled: Boolean = true): SubscriptionManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scopes.add(scope)
        val manager = SubscriptionManager(billing, scope, api, { token }, accountsEnabled, MemorySubscriptionCacheStore())
        runBlocking { manager.loadProduct(); manager.refresh() }
        return manager
    }

    @Test fun paywallShowsStorePriceLinksAndCloseAtEveryEntryPoint() {
        val subscription = manager()
        try {
            compose.setContent { PitTechTheme { ProGateDialog(subscription, previouslyEntitled = false, onSignIn = {}) } }
            for (entry in listOf(ProPlacement.ANALYSIS, ProPlacement.CURVES, ProPlacement.THEMES, ProPlacement.MORE)) {
                subscription.openPro(entry)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("pro-price").performScrollTo().assertTextContains("$3.00", substring = true)
                compose.onNodeWithTag("pro-subscribe").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("pro-restore").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("pro-manage").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("pro-privacy").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("pro-terms").performScrollTo().assertIsDisplayed()
                if (entry == ProPlacement.ANALYSIS) screenshot("pro-paywall")
                compose.onNodeWithTag("pro-paywall-close").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            subscription.stop()
        }
    }

    @Test fun signedOutPaywallRequiresSignInAndOffersNoPurchase() {
        val subscription = manager(token = null, accountsEnabled = false)
        try {
            compose.setContent { PitTechTheme { ProGateDialog(subscription, previouslyEntitled = false, onSignIn = {}) } }
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-signin-required").performScrollTo().assertTextContains("Accounts arrive", substring = true)
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun entitledAccountOpensProWithNoSecondOffer() {
        val subscription = manager(api = FakeAPI(entitlementState = "active"))
        try {
            compose.setContent { PitTechTheme { ProGateDialog(subscription, previouslyEntitled = true, onSignIn = {}) } }
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-placeholder").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-placeholder").fetchSemanticsNodes().isEmpty() }
        } finally {
            subscription.stop()
        }
    }

    @Test fun expiredAccountKeepsFreeFallback() {
        val subscription = manager(api = FakeAPI(entitlementState = "expired"))
        try {
            compose.setContent { PitTechTheme { ProGateDialog(subscription, previouslyEntitled = true, onSignIn = {}) } }
            subscription.openPro(ProPlacement.CURVES)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-expired-fallback").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-placeholder").assertCountEquals(0)
            compose.onNodeWithTag("pro-resubscribe").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun unknownEntitlementShowsNeutralLoadingWithNoPaywallFlash() {
        AdRequestMonitor.reset()
        val subscription = manager(api = FakeAPI(entitlementState = "unknown"))
        try {
            compose.setContent { PitTechTheme { ProGateDialog(subscription, previouslyEntitled = true, onSignIn = {}) } }
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-loading").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            assertFalse(shouldAttemptAdRequest(AdEntitlement.UNKNOWN, previouslyEntitled = true))
            assertTrue(AdRequestMonitor.attempts.isEmpty())
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun unconfiguredProductShowsNoPriceAndNoPurchase() {
        val subscription = manager(billing = FakeBilling(productId = ""))
        try {
            compose.setContent { PitTechTheme { ProGateDialog(subscription, previouslyEntitled = false, onSignIn = {}) } }
            subscription.openPro(ProPlacement.ANALYSIS)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-product-unavailable").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("pro-price").assertCountEquals(0)
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.filesDir, "pittech-ui-test/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
