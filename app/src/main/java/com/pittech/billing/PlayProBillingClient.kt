package com.pittech.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The one monthly product behind an interface so tests use a fake with
 * localized product data instead of touching Google Play. */
internal interface ProBillingClient {
    val productId: String

    /** Localized monthly offer, or null when the product is not configured or
     * the store is unreachable. Never a fake price. */
    suspend fun queryProduct(): ProStoreProduct?

    /** Null activity (tests) never reaches Google Play. */
    suspend fun launchPurchase(activity: Activity?): ProPurchaseOutcome
    suspend fun queryOwnedPurchases(): List<ProStorePurchase>

    /** Acknowledge after the backend confirms verification, within Play's
     * required lifecycle. */
    suspend fun acknowledge(purchaseToken: String): Boolean

    /** Purchases the store delivers outside a launch (a pending purchase
     * completing). The manager verifies each server-side. */
    var externalPurchases: ((ProStorePurchase) -> Unit)?

    fun close()
}

internal class PlayProBillingClient(context: Context, override val productId: String) : ProBillingClient {
    private val appContext = context.applicationContext
    private var launchHandler: ((BillingResult, List<Purchase>?) -> Unit)? = null
    private var cachedDetails: ProductDetails? = null

    override var externalPurchases: ((ProStorePurchase) -> Unit)? = null

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(appContext)
            .setListener { result, purchases ->
                val handler = launchHandler
                if (handler != null) {
                    launchHandler = null
                    handler(result, purchases)
                } else if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases.orEmpty().filter { productId in it.products }.forEach {
                        externalPurchases?.invoke(ProStorePurchase(it.purchaseToken, it.isAcknowledged, it.purchaseState == Purchase.PurchaseState.PENDING))
                    }
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    override fun close() {
        runCatching { client.endConnection() }
    }

    private suspend fun ensureConnected(): Boolean {
        if (productId.isEmpty()) return false
        if (client.isReady) return true
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    // Reconnect lazily on the next use; never cache a dead client as ready.
                }
            })
        }
    }

    override suspend fun queryProduct(): ProStoreProduct? {
        if (!ensureConnected()) return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, details ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(null)
                    return@queryProductDetailsAsync
                }
                // billing-ktx 9.x: onProductDetailsResponse delivers a QueryProductDetailsResult;
                // the product list lives on its productDetailsList (verified against billing-9.1.0 bytecode)
                val match = details.productDetailsList.firstOrNull { it.productId == productId && it.productType == BillingClient.ProductType.SUBS }
                val offer = match?.subscriptionOfferDetails?.firstOrNull { offer ->
                    offer.pricingPhases.pricingPhaseList.any { it.billingPeriod == "P1M" }
                }
                if (match == null || offer == null) {
                    continuation.resume(null)
                    return@queryProductDetailsAsync
                }
                cachedDetails = match
                val phase = offer.pricingPhases.pricingPhaseList.first { it.billingPeriod == "P1M" }
                continuation.resume(ProStoreProduct(productId, phase.formattedPrice, isMonthly = true, offerToken = offer.offerToken))
            }
        }
    }

    override suspend fun launchPurchase(activity: Activity?): ProPurchaseOutcome {
        if (activity == null) return ProPurchaseOutcome.Failed("Google Play couldn't start that purchase. Nothing was charged.")
        if (productId.isEmpty()) return ProPurchaseOutcome.Failed("PitTech Pro isn't available on this build yet.")
        val product = queryProduct()
        val details = cachedDetails
        val offerToken = product?.offerToken
        if (product == null || details == null || offerToken == null) {
            return ProPurchaseOutcome.Failed("Couldn't reach Google Play. Try again when you're online.")
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            launchHandler = { result, purchases ->
                if (!continuation.isActive) return
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        val purchase = purchases?.firstOrNull { productId in it.products }
                        if (purchase == null) {
                            continuation.resume(ProPurchaseOutcome.Failed("Google Play returned no purchase. Nothing was charged."))
                        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                            continuation.resume(ProPurchaseOutcome.Pending)
                        } else {
                            continuation.resume(ProPurchaseOutcome.Purchased(ProStorePurchase(purchase.purchaseToken, purchase.isAcknowledged, pending = false)))
                        }
                    }
                    BillingClient.BillingResponseCode.USER_CANCELED -> continuation.resume(ProPurchaseOutcome.Cancelled)
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> continuation.resume(ProPurchaseOutcome.AlreadyOwned(null))
                    else -> continuation.resume(ProPurchaseOutcome.Failed("Google Play couldn't complete that purchase. Nothing was charged."))
                }
            }
            continuation.invokeOnCancellation { launchHandler = null }
            val launch = client.launchBillingFlow(activity, params)
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                launchHandler = null
                if (continuation.isActive) continuation.resume(ProPurchaseOutcome.Failed("Google Play couldn't start that purchase. Nothing was charged."))
            }
        }
    }

    override suspend fun queryOwnedPurchases(): List<ProStorePurchase> {
        if (!ensureConnected()) return emptyList()
        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
            ) { result, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(emptyList())
                    return@queryPurchasesAsync
                }
                continuation.resume(
                    purchases.filter { productId in it.products }.map {
                        ProStorePurchase(it.purchaseToken, it.isAcknowledged, it.purchaseState == Purchase.PurchaseState.PENDING)
                    },
                )
            }
        }
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (!ensureConnected()) return false
        return suspendCancellableCoroutine { continuation ->
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
            ) { result ->
                if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }
    }
}
