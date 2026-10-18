package com.pittech.billing

import com.pittech.ads.AdEntitlement
import com.pittech.ads.AdRequestMonitor
import com.pittech.ads.shouldAttemptAdRequest
import com.pittech.ads.shouldRequestAds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProGateTest {
    @Before
    fun resetMonitor() {
        AdRequestMonitor.reset()
        ProPaywallPresentation.reset()
    }

    @Test
    fun billingTextComesFromTheStoreProduct() {
        val product = ProStoreProduct("stub.pro.monthly", "A$4.99", isMonthly = true)
        val text = proBillingText(product)!!
        assertTrue(text.contains("A$4.99"))
        assertTrue(text.contains("per month"))
        assertNull(proBillingText(null))
        assertNull(proBillingText(product.copy(productId = "")))
        assertNull(proBillingText(product.copy(isMonthly = false)))
        assertNull(proBillingText(product.copy(displayPrice = "")))
    }

    @Test
    fun gateMatrixRoutesEveryState() {
        // Signed-out users see the paywall preview with a sign-in requirement.
        assertEquals(ProDestination.PAYWALL, proDestination(false, AdEntitlement.UNKNOWN, null, false))
        assertEquals(ProDestination.PAYWALL, proDestination(false, AdEntitlement.ENTITLED, "active", true))
        // Unknown/loading is neutral for previously entitled users too.
        assertEquals(ProDestination.LOADING, proDestination(true, AdEntitlement.UNKNOWN, "unknown", true))
        assertEquals(ProDestination.LOADING, proDestination(true, AdEntitlement.UNKNOWN, "unknown", false))
        // Verified entitled opens Pro; verified not-entitled sees the paywall.
        assertEquals(ProDestination.PRO, proDestination(true, AdEntitlement.ENTITLED, "active", true))
        assertEquals(ProDestination.PAYWALL, proDestination(true, AdEntitlement.NOT_ENTITLED, "none", false))
        assertEquals(ProDestination.PAYWALL, proDestination(true, AdEntitlement.NOT_ENTITLED, "pending", false))
        // Verified ended states fall back to free capabilities.
        for (state in listOf("expired", "cancelled", "refunded", "revoked")) {
            assertEquals(ProDestination.EXPIRED_FALLBACK, proDestination(true, AdEntitlement.NOT_ENTITLED, state, true))
        }
        // No second subscription offer when covered.
        assertFalse(proShowsPurchaseOffer(AdEntitlement.ENTITLED))
        assertTrue(proShowsPurchaseOffer(AdEntitlement.NOT_ENTITLED))
        assertTrue(proShowsPurchaseOffer(AdEntitlement.UNKNOWN))
    }

    @Test
    fun unknownNeverFlashesPaywallOrAdsForPreviouslyEntitledUsers() {
        assertEquals(ProDestination.LOADING, proDestination(true, AdEntitlement.UNKNOWN, "unknown", true))
        assertFalse(shouldRequestAds(AdEntitlement.UNKNOWN, previouslyEntitled = true))
        assertFalse(shouldAttemptAdRequest(AdEntitlement.UNKNOWN, previouslyEntitled = true))
        assertTrue(AdRequestMonitor.attempts.isEmpty())
    }

    @Test
    fun entitledStateTriggersZeroAdRequests() {
        assertFalse(shouldRequestAds(AdEntitlement.ENTITLED, previouslyEntitled = true))
        assertFalse(shouldAttemptAdRequest(AdEntitlement.ENTITLED, previouslyEntitled = true))
        assertTrue(AdRequestMonitor.attempts.isEmpty())
    }

    @Test
    fun cacheParsingMapsServerStatesAndHonorsTheOfflineWindow() {
        fun cache(state: String, verified: Boolean = true) = SubscriptionCache.parse(
            JSONObject(
                """{"version":"entitlement-cache-v1","environment":"production","verified":$verified,"state":"$state","verifiedUntil":"2026-11-17T12:00:00Z","lastVerifiedAt":"2026-10-18T12:00:00Z","offlineValidUntil":"2026-10-25T12:00:00Z","contractVersion":"store-verification-v1"}",
            ),
        )
        val now = 1_792_324_800_000L
        assertEquals(AdEntitlement.ENTITLED, cache("active").entitlement(now))
        assertEquals(AdEntitlement.ENTITLED, cache("grace").entitlement(now))
        assertEquals(AdEntitlement.NOT_ENTITLED, cache("expired").entitlement(now))
        assertEquals(AdEntitlement.NOT_ENTITLED, cache("none").entitlement(now))
        assertEquals(AdEntitlement.UNKNOWN, cache("unknown", verified = false).entitlement(now))
        assertEquals(AdEntitlement.UNKNOWN, cache("active").copy(version = "entitlement-cache-v0").entitlement(now))
        val active = cache("active")
        assertTrue(active.offlineUsable(now))
        assertFalse(active.offlineUsable(now + 604_801_000L))
        assertFalse(cache("expired").offlineUsable(now))
        assertEquals(active, SubscriptionCache.parse(active.json()))
    }

    @Test
    fun outcomeParsingCarriesConflictRecovery() {
        val cache = JSONObject(
            """{"version":"entitlement-cache-v1","environment":"production","verified":true,"state":"none","verifiedUntil":null,"lastVerifiedAt":"2026-10-18T12:00:00Z","offlineValidUntil":null,"contractVersion":"store-verification-v1"}",
        )
        val outcome = SubscriptionOutcome.parse(
            JSONObject()
                .put("cache", cache)
                .put("applied", false)
                .put("note", "binding-conflict")
                .put("transaction", JSONObject().put("store", "google").put("environment", "production").put("transactionKey", "stub-ref").put("evidenceRef", "a".repeat(64)))
                .put("conflict", JSONObject().put("id", "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa").put("state", "open").put("recovery", "Bound elsewhere.")),
        )
        assertEquals(false, outcome.applied)
        assertEquals("Bound elsewhere.", outcome.conflictRecovery)
        assertEquals("google", outcome.store)
        assertEquals("stub-ref", outcome.transactionKey)
        assertEquals(AdEntitlement.NOT_ENTITLED, outcome.cache.entitlement())
    }

    @Test
    fun openingAProEntryRecordsOnePresentation() {
        ProPaywallPresentation.record(ProPlacement.ANALYSIS)
        assertEquals(listOf(ProPlacement.ANALYSIS), ProPaywallPresentation.recorded)
    }
}
