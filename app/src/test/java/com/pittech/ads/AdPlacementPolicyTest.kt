package com.pittech.ads

import com.pittech.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPlacementPolicyTest {
    @Test
    fun historyAdRequiresFourCompletedCooksAndNoActiveCook() {
        assertTrue(shouldShowCookHistoryNativeAd(adsEnabled = true, activeCookCount = 0, completedCookCount = 4))
        assertTrue(shouldShowCookHistoryNativeAd(adsEnabled = true, activeCookCount = 0, completedCookCount = 8))
        assertFalse(shouldShowCookHistoryNativeAd(adsEnabled = true, activeCookCount = 0, completedCookCount = 3))
        assertFalse(shouldShowCookHistoryNativeAd(adsEnabled = true, activeCookCount = 1, completedCookCount = 4))
        assertFalse(shouldShowCookHistoryNativeAd(adsEnabled = false, activeCookCount = 0, completedCookCount = 4))
    }

    @Test
    fun debugBuildAlwaysUsesGoogleTestNativeAdUnit() {
        assertEquals("ca-app-pub-3940256099942544/2247696110", BuildConfig.PITTECH_NATIVE_AD_UNIT_ID)
    }
}
