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

/** Index within the completed-cooks list after which the single home-tab
 * native ad is inserted (in-feed), or null when no ad slot exists. The ad
 * needs at least one completed cook above it and never sits above the list. */
internal fun cookListNativeAdInsertIndex(completedCount: Int): Int? =
    if (completedCount <= 0) null else minOf(2, completedCount)
