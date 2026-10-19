package com.pittech

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pittech.billing.CommunityBenchmarksAPI
import com.pittech.billing.CommunityBenchmarksFailure
import com.pittech.billing.CommunityBenchmarksLoader
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slice 17 native lane: benchmark cards on Android UI. Entitlement and
 * figures are scripted (the same fixture file as iOS); no store or server is
 * contacted. */
@RunWith(AndroidJUnit4::class)
class CommunityBenchmarksTest {
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

    private class FakeSubscriptionAPI(var entitlementState: String = "none") : SubscriptionAPI {
        private fun cache(state: String, verified: Boolean): JSONObject = JSONObject(
            """{"version":"entitlement-cache-v1","environment":"production","verified":$verified,"state":"$state","verifiedUntil":"2026-11-17T12:00:00Z","lastVerifiedAt":"2026-10-18T12:00:00Z","offlineValidUntil":"2026-10-25T12:00:00Z","contractVersion":"store-verification-v1"}""",
        )

        override suspend fun call(path: String, body: JSONObject?, access: String?): JSONObject {
            require(access != null)
            return when (path) {
                "/v1/me/entitlement" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/entitlement/reconcile" -> cache(entitlementState, entitlementState != "unknown")
                "/v1/me/purchases/restore" -> JSONObject().put("outcomes", JSONArray().put(JSONObject().put("cache", cache("active", true)).put("applied", true).put("note", "stub-verified")))
                else -> throw IllegalStateException("Unexpected local test path")
            }
        }
    }

    private class FakeBenchmarksAPI(var payloadName: String = "mixed", var failure: CommunityBenchmarksFailure? = null) : CommunityBenchmarksAPI {
        val calls = mutableListOf<String>()
        var mismatches = 0

        private fun fixture(name: String): JSONObject {
            val text = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("community-benchmarks-v1.json").bufferedReader().use { it.readText() }
            return JSONObject(text).getJSONObject("payloads").getJSONObject(name)
        }

        override suspend fun aggregates(bucket: String?, metric: String?, runId: String?, access: String?): JSONObject {
            calls.add("aggregates")
            if (access == null) throw CommunityBenchmarksFailure(401)
            failure?.let { throw it }
            return fixture(payloadName)
        }

        override suspend fun jobRuns(limit: Int?, cursor: String?, access: String?): JSONObject {
            if (access == null) throw CommunityBenchmarksFailure(401)
            return JSONObject().put("runs", JSONArray()).put("nextCursor", JSONObject.NULL)
        }
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun manager(api: FakeSubscriptionAPI = FakeSubscriptionAPI(), token: String? = "A".repeat(43)): SubscriptionManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scopes.add(scope)
        val manager = SubscriptionManager(FakeBilling(), scope, api, { token }, true, MemorySubscriptionCacheStore())
        runBlocking { manager.loadProduct(); manager.refresh() }
        return manager
    }

    private fun benchmarks(api: FakeBenchmarksAPI, token: String? = "A".repeat(43)) =
        CommunityBenchmarksLoader(api = api, session = { token }, onAuthMismatch = { api.mismatches++ })

    private fun show(subscription: SubscriptionManager, benchmarks: CommunityBenchmarksLoader, previouslyEntitled: Boolean = false) {
        compose.setContent { PitTechTheme { ProGateDialog(subscription, previouslyEntitled, {}, benchmarks) } }
        subscription.openPro(ProPlacement.BENCHMARKS)
    }

    private fun assertNoBenchmarkTag() {
        for (tag in listOf("community-benchmarks", "community-empty", "community-unavailable", "community-loading")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    @Test fun entitledBenchmarksShowFigureCardsWithNAndDate() {
        val subscription = manager(FakeSubscriptionAPI("active"))
        val api = FakeBenchmarksAPI("mixed")
        try {
            show(subscription, benchmarks(api), previouslyEntitled = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-benchmarks").fetchSemanticsNodes().isNotEmpty() }
            for (tag in listOf(
                "community-card-median-heating-time-v1-pork-butt-shoulder",
                "community-insufficient-median-heating-time-v1-pork-loin",
                "community-card-pit-food-curve-percentiles-v1-pork-butt-shoulder",
                "community-card-stall-summary-v1-pork-butt-shoulder",
                "community-card-wrap-spritz-prevalence-v1-pork-butt-shoulder",
            )) {
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }
            for (line in listOf(
                "Typical (median): 5h 30m",
                "Middle half: 3h 15m – 7h 45m",
                "Recorded range: 1h – 10h",
                "Food band hidden: 3 of 10 cooks in this interval (30%). Bands need 10 cooks.",
                "Percentile bands summarize many cooks. They are not one cook's readings.",
                "Measured stall windows only; sparse attempts are excluded from n, never interpolated.",
                "Wrapped: 6 of 10 (60%)",
                "Spritzed or mopped: 3 of 10 (30%)",
                "Not enough cooks yet",
                "9 of 10 eligible cooks recorded. Figures appear when 10 distinct eligible cooks are recorded.",
                "Pork loin · Medium · Method: smoke",
            )) {
                compose.onNodeWithText(line).performScrollTo().assertIsDisplayed()
            }
            compose.onAllNodesWithText("10 eligible cooks · Computed 2026-10-18").onFirst().performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("9 eligible cooks · Computed 2026-10-18").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Pork butt / shoulder · Medium · Method: smoke").onFirst().performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-benchmarks").fetchSemanticsNodes().isEmpty() }
        } finally {
            subscription.stop()
        }
    }

    @Test fun insufficientDataCardExplainsThreshold() {
        val subscription = manager(FakeSubscriptionAPI("active"))
        try {
            show(subscription, benchmarks(FakeBenchmarksAPI("suppressed")), previouslyEntitled = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-benchmarks").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("community-insufficient-median-heating-time-v1-pork-loin").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("9 of 10 eligible cooks recorded. Figures appear when 10 distinct eligible cooks are recorded.").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("community-card-median-heating-time-v1-pork-butt-shoulder").assertCountEquals(0)
            compose.onAllNodesWithText("Typical (median): 5h 30m").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun emptyStateShowsNoFigures() {
        val subscription = manager(FakeSubscriptionAPI("active"))
        try {
            show(subscription, benchmarks(FakeBenchmarksAPI("empty")), previouslyEntitled = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-empty").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("No community figures yet").assertIsDisplayed()
            compose.onAllNodesWithTag("community-benchmarks").assertCountEquals(0)
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun signedOutShowsPaywallWithSignInAndNoNetworkCall() {
        val subscription = manager(token = null)
        val api = FakeBenchmarksAPI("mixed")
        try {
            show(subscription, benchmarks(api, token = null))
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-signin-required").performScrollTo().assertTextContains("Sign in to subscribe", substring = true)
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            assertNoBenchmarkTag()
            assertTrue("a signed-out gate must not load figures", api.calls.isEmpty())
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun nonProShowsPaywallWithoutFigures() {
        val subscription = manager()
        val api = FakeBenchmarksAPI("mixed")
        try {
            show(subscription, benchmarks(api))
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            assertNoBenchmarkTag()
            assertTrue("a non-Pro gate must not load figures", api.calls.isEmpty())
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun unknownEntitlementShowsNeutralLoadingWithNoPaywallFlash() {
        val subscription = manager(FakeSubscriptionAPI("unknown"))
        val api = FakeBenchmarksAPI("mixed")
        try {
            show(subscription, benchmarks(api), previouslyEntitled = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-loading").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("pro-paywall").assertCountEquals(0)
            assertNoBenchmarkTag()
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun unavailableShowsRetryAndPreservesGate() {
        val subscription = manager(FakeSubscriptionAPI("active"))
        try {
            show(subscription, benchmarks(FakeBenchmarksAPI("mixed", CommunityBenchmarksFailure())), previouslyEntitled = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-unavailable").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Community benchmarks are unavailable right now.").assertIsDisplayed()
            compose.onNodeWithTag("community-retry").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-unavailable").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pro-close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-unavailable").fetchSemanticsNodes().isEmpty() }
        } finally {
            subscription.stop()
        }
    }

    @Test fun forbiddenMapsToPaywallWithoutFigures() {
        val subscription = manager(FakeSubscriptionAPI("active"))
        val api = FakeBenchmarksAPI("mixed", CommunityBenchmarksFailure(403))
        try {
            show(subscription, benchmarks(api), previouslyEntitled = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-paywall").fetchSemanticsNodes().isNotEmpty() }
            assertNoBenchmarkTag()
            compose.onAllNodesWithTag("pro-subscribe").assertCountEquals(0)
            assertTrue("one convergence refresh per view lifetime", api.mismatches == 1)
            compose.onNodeWithTag("pro-paywall-close").performClick()
        } finally {
            subscription.stop()
        }
    }

    @Test fun expiredShowsFreeFallback() {
        val subscription = manager(FakeSubscriptionAPI("expired"))
        val api = FakeBenchmarksAPI("mixed")
        try {
            show(subscription, benchmarks(api), previouslyEntitled = true)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("pro-expired-fallback").fetchSemanticsNodes().isNotEmpty() }
            assertNoBenchmarkTag()
            assertTrue("an expired gate must not load figures", api.calls.isEmpty())
            compose.onNodeWithTag("pro-close").performClick()
        } finally {
            subscription.stop()
        }
    }
}
