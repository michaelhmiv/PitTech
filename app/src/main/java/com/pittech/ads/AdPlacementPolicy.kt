package com.pittech.ads

/** Show the cooks-list ad whenever consent allows ad requests. */
internal fun shouldShowCookListNativeAd(adsEnabled: Boolean): Boolean = adsEnabled

/** Entitlement-aware placement: the gate holds the placement for entitled users
 * and for unverified previously entitled accounts. */
internal fun shouldShowCookListNativeAd(
    adsEnabled: Boolean,
    entitlement: AdEntitlement,
    previouslyEntitled: Boolean,
): Boolean = adsEnabled && shouldRequestAds(entitlement, previouslyEntitled)
