package com.pittech.ads

import com.pittech.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPlacementPolicyTest {
    @Test
    fun cooksListAdIsShownWheneverConsentAllowsAdRequests() {
        assertTrue(shouldShowCookListNativeAd(adsEnabled = true))
        assertFalse(shouldShowCookListNativeAd(adsEnabled = false))
    }

    @Test
    fun cookListAdHasNoSlotWithoutCompletedCooks() {
        assertNull(cookListNativeAdInsertIndex(0))
    }

    @Test
    fun cookListAdSitsAfterFirstCookWhenListIsShort() {
        assertEquals(1, cookListNativeAdInsertIndex(1))
    }

    @Test
    fun cookListAdSitsAfterSecondCook() {
        assertEquals(2, cookListNativeAdInsertIndex(2))
    }

    @Test
    fun cookListAdNeverSitsDeeperThanSecondCook() {
        assertEquals(2, cookListNativeAdInsertIndex(5))
    }

    @Test
    fun debugBuildAlwaysUsesGoogleTestNativeAdUnit() {
        assertEquals("ca-app-pub-3940256099942544/2247696110", BuildConfig.PITTECH_NATIVE_AD_UNIT_ID)
    }
}
