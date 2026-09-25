package com.pittech.ads

/** Keep ads out of live cooking and wait until history has enough content. */
internal fun shouldShowCookHistoryNativeAd(
    adsEnabled: Boolean,
    activeCookCount: Int,
    completedCookCount: Int,
): Boolean = adsEnabled && activeCookCount == 0 && completedCookCount >= 4
