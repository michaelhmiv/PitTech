package com.pittech.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdEntitlementGateTest {
    private class MemoryPrefs(var entitled: Boolean = false) : AdEntitlementPrefs {
        override fun getPreviouslyEntitled(): Boolean = entitled
        override fun setPreviouslyEntitled() {
            entitled = true
        }
    }

    @Before
    fun resetMonitor() {
        AdRequestMonitor.reset()
    }

    @Test
    fun entitledUsersTriggerZeroAdRequests() {
        assertFalse(shouldRequestAds(AdEntitlement.ENTITLED, previouslyEntitled = true))
        assertFalse(shouldRequestAds(AdEntitlement.ENTITLED, previouslyEntitled = false))
        assertFalse(shouldAttemptAdRequest(AdEntitlement.ENTITLED, previouslyEntitled = true))
        assertTrue(AdRequestMonitor.attempts.isEmpty())
    }

    @Test
    fun verifiedNotEntitledAllowsRequests() {
        assertTrue(shouldRequestAds(AdEntitlement.NOT_ENTITLED, previouslyEntitled = false))
        assertTrue(shouldAttemptAdRequest(AdEntitlement.NOT_ENTITLED, previouslyEntitled = false))
        assertEquals(1, AdRequestMonitor.attempts.size)
    }

    @Test
    fun unknownEntitlementFailsClosedForPreviouslyEntitledAccounts() {
        assertFalse(shouldRequestAds(AdEntitlement.UNKNOWN, previouslyEntitled = true))
        assertFalse(shouldAttemptAdRequest(AdEntitlement.UNKNOWN, previouslyEntitled = true))
        assertTrue(AdRequestMonitor.attempts.isEmpty())
        assertTrue(shouldRequestAds(AdEntitlement.UNKNOWN, previouslyEntitled = false))
    }

    @Test
    fun placementHoldsEntitledAndUnverifiedPreviouslyEntitledUsers() {
        assertTrue(shouldShowCookListNativeAd(adsEnabled = true, AdEntitlement.NOT_ENTITLED, previouslyEntitled = false))
        assertFalse(shouldShowCookListNativeAd(adsEnabled = true, AdEntitlement.ENTITLED, previouslyEntitled = true))
        assertFalse(shouldShowCookListNativeAd(adsEnabled = true, AdEntitlement.UNKNOWN, previouslyEntitled = true))
        assertTrue(shouldShowCookListNativeAd(adsEnabled = true, AdEntitlement.UNKNOWN, previouslyEntitled = false))
        assertFalse(shouldShowCookListNativeAd(adsEnabled = false, AdEntitlement.NOT_ENTITLED, previouslyEntitled = false))
    }

    @Test
    fun serverCacheMapsLiveBillingStatesToEntitledAndEndedStatesToNotEntitled() {
        val now = 1_790_000_000_000L
        for (state in listOf("active", "grace", "retry", "paused")) {
            assertEquals(AdEntitlement.ENTITLED, adEntitlementFromServerCache(verified = true, state = state, verifiedUntilMillis = now + 1, nowMillis = now))
        }
        assertEquals(AdEntitlement.ENTITLED, adEntitlementFromServerCache(verified = true, state = "cancelled", verifiedUntilMillis = now + 1, nowMillis = now))
        for (state in listOf("none", "pending", "expired", "refunded", "revoked")) {
            assertEquals(AdEntitlement.NOT_ENTITLED, adEntitlementFromServerCache(verified = true, state = state, verifiedUntilMillis = null, nowMillis = now))
        }
        assertEquals(AdEntitlement.NOT_ENTITLED, adEntitlementFromServerCache(verified = true, state = "cancelled", verifiedUntilMillis = now - 1, nowMillis = now))
        assertEquals(AdEntitlement.UNKNOWN, adEntitlementFromServerCache(verified = false, state = "active", verifiedUntilMillis = now + 1, nowMillis = now))
        assertEquals(AdEntitlement.UNKNOWN, adEntitlementFromServerCache(verified = true, state = "active", verifiedUntilMillis = now + 1, cacheVersion = "entitlement-cache-v0", nowMillis = now))
        assertEquals(AdEntitlement.UNKNOWN, adEntitlementFromServerCache(verified = true, state = "future-state", verifiedUntilMillis = now + 1, nowMillis = now))
    }

    @Test
    fun storePersistsPreviouslyEntitledAcrossRestarts() {
        val prefs = MemoryPrefs()
        val store = AdEntitlementStore(prefs)
        assertFalse(store.previouslyEntitled.value)
        store.update(AdEntitlement.ENTITLED)
        assertTrue(store.previouslyEntitled.value)
        assertTrue(prefs.entitled)
        val restarted = AdEntitlementStore(prefs)
        assertEquals(AdEntitlement.UNKNOWN, restarted.entitlement.value)
        assertTrue(restarted.previouslyEntitled.value)
        assertFalse(shouldRequestAds(restarted.entitlement.value, restarted.previouslyEntitled.value))
    }
}
