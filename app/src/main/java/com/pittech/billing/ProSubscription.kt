package com.pittech.billing

import com.pittech.BuildConfig
import com.pittech.ads.AdEntitlement
import com.pittech.ads.ENTITLEMENT_CACHE_VERSION
import com.pittech.ads.ENTITLEMENT_CONTRACT_VERSION
import com.pittech.ads.adEntitlementFromServerCache
import org.json.JSONObject
import java.time.Instant

/** The one monthly PitTech Pro product ID from the approved build configuration
 * (`-PpittechProProductId`, owner-gated). Empty until the owner creates the
 * store product; empty is a graceful not-configured state, never a crash and
 * never a fake price. */
internal object ProProductConfiguration {
    val productId: String get() = BuildConfig.PITTECH_PRO_PRODUCT_ID.trim()
    val isConfigured: Boolean get() = productId.isNotEmpty()
}

/** Paywall placement map. The slice-15 free-feature inventory is the
 * do-not-touch list: these entries only add paid views or loading states and
 * never remove or paywall an existing free tool. */
internal enum class ProPlacement {
    ANALYSIS,
    CURVES,
    BENCHMARKS,
    THEMES,
    MORE,
}

internal val ProPlacement.title: String
    get() = when (this) {
        ProPlacement.ANALYSIS -> "Personal analysis"
        ProPlacement.CURVES -> "Advanced curves"
        ProPlacement.BENCHMARKS -> "Community benchmarks"
        ProPlacement.THEMES -> "Pro themes"
        ProPlacement.MORE -> "PitTech Pro"
    }

internal val ProPlacement.preview: String
    get() = when (this) {
        ProPlacement.ANALYSIS -> "How your pork cooks compare, which recorded attempt you rated best, and how timing shifts across your recorded cuts and weights."
        ProPlacement.CURVES -> "Align recorded cooks at Food on and Wrap, and choose which measured sources to compare."
        ProPlacement.BENCHMARKS -> "Typical heating times and temperature bands from eligible published cooks."
        ProPlacement.THEMES -> "Pro card themes, chart palettes, and app icons. Preview before applying."
        ProPlacement.MORE -> "An ad-free PitTech with deeper personal analysis, advanced curves, and Pro themes."
    }

internal val ProPlacement.entryTag: String get() = "pro-entry-" + name.lowercase()

internal fun proPreviewBullets(entry: ProPlacement): List<String> = when (entry) {
    ProPlacement.ANALYSIS -> listOf(
        "Compare your recorded attempts side by side",
        "Find the cooks you rated best",
        "See timing shifts across your cuts and weights",
    )
    ProPlacement.CURVES -> listOf(
        "Align recorded cooks at Food on and Wrap",
        "Choose which measured sources to compare",
        "Only real measurements — gaps stay visible",
    )
    ProPlacement.BENCHMARKS -> listOf(
        "Typical heating times with sample counts",
        "Temperature percentile bands from many cooks",
        "Wrap and spritz shares, prevalence only",
    )
    ProPlacement.THEMES -> listOf(
        "Pro share-card themes",
        "Accessible chart palettes",
        "App icon variants",
    )
    ProPlacement.MORE -> listOf(
        "No ads, ever",
        "Personal analysis across your recorded cooks",
        "Advanced curves with Food on and Wrap alignment",
        "Pro themes and app icons",
    )
}

/** Localized monthly product data from the store. The paywall renders
 * [displayPrice] verbatim; no price string is hardcoded anywhere. */
internal data class ProStoreProduct(
    val productId: String,
    val displayPrice: String,
    val isMonthly: Boolean,
    val offerToken: String? = null,
)

internal data class ProStorePurchase(
    val purchaseToken: String,
    val acknowledged: Boolean,
    val pending: Boolean,
)

internal sealed interface ProPurchaseOutcome {
    data class Purchased(val purchase: ProStorePurchase) : ProPurchaseOutcome
    data object Pending : ProPurchaseOutcome
    data object Cancelled : ProPurchaseOutcome
    data class AlreadyOwned(val purchase: ProStorePurchase?) : ProPurchaseOutcome
    data class Failed(val message: String) : ProPurchaseOutcome
}

/** Precise monthly billing text from actual localized store product data.
 * Returns null when the product is not configured, so the paywall shows its
 * honest unavailable state instead of inventing a price. */
internal fun proBillingText(product: ProStoreProduct?): String? {
    if (product == null || product.productId.isEmpty() || !product.isMonthly || product.displayPrice.isEmpty()) return null
    return "${product.displayPrice} per month, billed monthly. Cancel anytime in Google Play."
}

/** Where a Pro entry point leads. Computed from the backend lane's verified
 * cache state; the client never derives this from a local purchase callback.
 * - Entitled → the Pro view opens.
 * - Not entitled → the contextual paywall with its preview.
 * - Unknown/loading → a neutral loading state. Never a flash of the paywall
 *   for a previously entitled user (and never a flash of ads: the ad gate
 *   holds for previously entitled accounts while unknown).
 * - Expired (verified ended states) → free fallback: local records, exported
 *   files, and all free capabilities keep working; nothing is destroyed.
 * Signed-out users see the paywall preview with a sign-in requirement; no
 * store purchase is ever started without a verified PitTech account. */
internal enum class ProDestination {
    LOADING,
    PAYWALL,
    PRO,
    EXPIRED_FALLBACK,
}

// A cancelled record that still maps to not-entitled has an exhausted
// paid-through date, so it is an ended subscription like the rest.
private val EXPIRED_SERVER_STATES = setOf("expired", "cancelled", "refunded", "revoked")

internal fun proDestination(
    signedIn: Boolean,
    entitlement: AdEntitlement,
    serverState: String?,
    previouslyEntitled: Boolean,
): ProDestination {
    // previouslyEntitled intentionally does not change the destination: unknown
    // is always neutral loading, so a previously entitled user never sees the
    // paywall flash while verification is in flight. The parameter stays so call
    // sites pass the real flag and tests pin both values to loading.
    if (!signedIn) return ProDestination.PAYWALL
    return when (entitlement) {
        AdEntitlement.ENTITLED -> ProDestination.PRO
        AdEntitlement.UNKNOWN -> ProDestination.LOADING
        AdEntitlement.NOT_ENTITLED ->
            if (serverState in EXPIRED_SERVER_STATES) ProDestination.EXPIRED_FALLBACK else ProDestination.PAYWALL
    }
}

/** The paywall offers a purchase only when the account is verifiably not
 * covered. An entitled account sees its covered state, never a second
 * subscription button. */
internal fun proShowsPurchaseOffer(entitlement: AdEntitlement): Boolean = entitlement != AdEntitlement.ENTITLED

/** Versioned entitlement cache record served by `/v1/me/entitlement`. */
internal data class SubscriptionCache(
    val version: String,
    val environment: String,
    val verified: Boolean,
    val state: String,
    val verifiedUntilMillis: Long?,
    val lastVerifiedAtMillis: Long?,
    val offlineValidUntilMillis: Long?,
    val contractVersion: String,
) {
    fun entitlement(nowMillis: Long = System.currentTimeMillis()): AdEntitlement =
        adEntitlementFromServerCache(verified, state, verifiedUntilMillis, cacheVersion = version, contractVersion = contractVersion, nowMillis = nowMillis)

    /** A recently verified active/grace record stays usable offline for a
     * bounded window; everything else requires fresh verification. */
    fun offlineUsable(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!verified || (state != "active" && state != "grace")) return false
        val last = lastVerifiedAtMillis ?: return false
        val until = offlineValidUntilMillis ?: return false
        if (version != ENTITLEMENT_CACHE_VERSION || contractVersion != ENTITLEMENT_CONTRACT_VERSION) return false
        return nowMillis in last..until
    }

    fun json(): JSONObject = JSONObject()
        .put("version", version)
        .put("environment", environment)
        .put("verified", verified)
        .put("state", state)
        .put("verifiedUntil", verifiedUntilMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("lastVerifiedAt", lastVerifiedAtMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("offlineValidUntil", offlineValidUntilMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("contractVersion", contractVersion)

    companion object {
        fun parse(o: JSONObject): SubscriptionCache {
            fun instantOrNull(key: String): Long? =
                if (o.isNull(key)) null else Instant.parse(o.getString(key)).toEpochMilli()
            return SubscriptionCache(
                version = o.getString("version"),
                environment = o.getString("environment"),
                verified = o.getBoolean("verified"),
                state = o.getString("state"),
                verifiedUntilMillis = instantOrNull("verifiedUntil"),
                lastVerifiedAtMillis = instantOrNull("lastVerifiedAt"),
                offlineValidUntilMillis = instantOrNull("offlineValidUntil"),
                contractVersion = o.getString("contractVersion"),
            )
        }
    }
}

/** One verified purchase outcome. [cache] is the converged
 * `entitlement-cache-v1` record; [conflictRecovery] carries the server's
 * binding-conflict recovery text when the purchase belongs elsewhere. */
internal data class SubscriptionOutcome(
    val cache: SubscriptionCache,
    val applied: Boolean,
    val note: String,
    val store: String?,
    val environment: String?,
    val transactionKey: String?,
    val conflictRecovery: String?,
) {
    companion object {
        fun parse(o: JSONObject): SubscriptionOutcome {
            val tx = o.optJSONObject("transaction")
            val conflict = o.optJSONObject("conflict")
            return SubscriptionOutcome(
                cache = SubscriptionCache.parse(o.getJSONObject("cache")),
                applied = o.getBoolean("applied"),
                note = o.getString("note"),
                store = tx?.optString("store", null),
                environment = tx?.optString("environment", null),
                transactionKey = tx?.optString("transactionKey", null),
                conflictRecovery = conflict?.optString("recovery", null),
            )
        }
    }
}

/** Records every paywall presentation so tests prove the paywall appears at
 * Pro entry points and never from Live or Finish. */
internal object ProPaywallPresentation {
    private val lock = Any()
    private val entriesMutable = mutableListOf<ProPlacement>()

    val recorded: List<ProPlacement>
        get() = synchronized(lock) { entriesMutable.toList() }

    fun record(entry: ProPlacement) {
        synchronized(lock) { entriesMutable.add(entry) }
    }

    fun reset() {
        synchronized(lock) { entriesMutable.clear() }
    }
}
