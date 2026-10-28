package com.pittech.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.pittech.BuildConfig
import com.pittech.ads.AdEntitlement
import com.pittech.ads.shouldAttemptAdRequest

// Slim, banner-proportioned slot (hands-on feedback, 2026-10-27): the home
// board's ad used to render as a 408dp native card that dwarfed the cook
// listings. The same native unit now renders as a small template — one row,
// media/icon, headline, one secondary line, call to action. The slot height
// is fixed and reserved while the ad loads, so the list never shifts; the
// placement logic (finished-cooks list only, never the active-cook zone)
// is unchanged.
private val CookListNativeAdSlotHeight = 108.dp
private val CookListNativeAdContentHeight = 64.dp

@Composable
internal fun PitTechNativeAdPlacement(
    adsEnabled: Boolean,
    entitlement: AdEntitlement = AdEntitlement.UNKNOWN,
    previouslyEntitled: Boolean = false,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val adState = remember { mutableStateOf<NativeAd?>(null) }
    val loadFailed = remember { mutableStateOf(false) }
    val adUnitId = BuildConfig.PITTECH_NATIVE_AD_UNIT_ID

    DisposableEffect(adsEnabled, adUnitId, entitlement, previouslyEntitled) {
        var disposed = false
        var loadedAd: NativeAd? = null
        adState.value = null
        loadFailed.value = false

        if (adsEnabled && shouldAttemptAdRequest(entitlement, previouslyEntitled)) {
            val request = NativeAdRequest.Builder(
                adUnitId,
                listOf(NativeAd.NativeAdType.NATIVE),
            ).build()

            NativeAdLoader.load(
                request,
                object : NativeAdLoaderCallback {
                    override fun onNativeAdLoaded(nativeAd: NativeAd) {
                        mainHandler.post {
                            if (disposed) {
                                nativeAd.destroy()
                            } else {
                                loadedAd = nativeAd
                                adState.value = nativeAd
                            }
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        mainHandler.post {
                            if (!disposed) {
                                adState.value = null
                                loadFailed.value = true
                            }
                        }
                    }
                },
            )
        }

        onDispose {
            disposed = true
            loadedAd?.destroy()
            adState.value = null
            loadFailed.value = false
        }
    }

    val ad = adState.value
    if (ad == null) {
        if (loadFailed.value) return
        Card(
            modifier = Modifier.fillMaxWidth().height(CookListNativeAdSlotHeight).testTag("cook-list-native-ad-placeholder"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AdLabelChip()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CookListNativeAdContentHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        return
    }
    val adStyle = PitTechNativeAdStyle(
        headlineColor = MaterialTheme.colorScheme.onSurface.toArgb(),
        secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        actionColor = MaterialTheme.colorScheme.primary.toArgb(),
        actionTextColor = MaterialTheme.colorScheme.onPrimary.toArgb(),
    )
    Card(
        modifier = Modifier.fillMaxWidth().height(CookListNativeAdSlotHeight).testTag("cook-list-native-ad"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AdLabelChip()
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(CookListNativeAdContentHeight),
                factory = { viewContext -> createPitTechNativeAdView(viewContext, adStyle) },
                update = { view -> bindPitTechNativeAd(view, ad, adStyle) },
            )
        }
    }
}

@Composable
private fun AdLabelChip() {
    Text(
        "Ad",
        modifier = Modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

private data class PitTechNativeAdAssets(
    val icon: ImageView,
    val headline: TextView,
    val advertiser: TextView,
    val media: MediaView,
    val callToAction: TextView,
    var boundAd: NativeAd? = null,
)

private data class PitTechNativeAdStyle(
    val headlineColor: Int,
    val secondaryTextColor: Int,
    val actionColor: Int,
    val actionTextColor: Int,
)

private fun createPitTechNativeAdView(context: Context, style: PitTechNativeAdStyle): NativeAdView {
    val nativeAdView = NativeAdView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }
    // Small template: one horizontal row — media (or icon), the headline
    // over one secondary line, and the call to action at the end.
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val media = MediaView(context)
    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Advertiser icon"
    }
    val headline = TextView(context).apply {
        setTextColor(style.headlineColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val advertiser = TextView(context).apply {
        setTextColor(style.secondaryTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, context.dp(2), 0, 0)
    }
    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    texts.addView(headline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    texts.addView(advertiser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val callToAction = TextView(context).apply {
        setTextColor(style.actionTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
        background = GradientDrawable().apply {
            setColor(style.actionColor)
            cornerRadius = context.dp(8).toFloat()
        }
    }

    content.addView(
        media,
        LinearLayout.LayoutParams(context.dp(64), context.dp(64)).apply { marginEnd = context.dp(10) },
    )
    content.addView(
        icon,
        LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { marginEnd = context.dp(10) },
    )
    content.addView(
        texts,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    content.addView(
        callToAction,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(10)
        },
    )
    nativeAdView.addView(
        content,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    nativeAdView.headlineView = headline
    nativeAdView.advertiserView = advertiser
    nativeAdView.iconView = icon
    nativeAdView.callToActionView = callToAction
    nativeAdView.tag = PitTechNativeAdAssets(icon, headline, advertiser, media, callToAction)
    return nativeAdView
}

private fun bindPitTechNativeAd(nativeAdView: NativeAdView, ad: NativeAd, style: PitTechNativeAdStyle) {
    val assets = nativeAdView.tag as PitTechNativeAdAssets
    assets.headline.setTextColor(style.headlineColor)
    assets.advertiser.setTextColor(style.secondaryTextColor)
    assets.callToAction.setTextColor(style.actionTextColor)
    (assets.callToAction.background as? GradientDrawable)?.setColor(style.actionColor)
    if (assets.boundAd === ad) return
    assets.boundAd = ad

    assets.headline.text = ad.headline.orEmpty()
    // One secondary line: the advertiser when present, else the body text.
    assets.advertiser.text = ad.advertiser?.takeIf { it.isNotBlank() } ?: ad.body.orEmpty()
    assets.callToAction.text = ad.callToAction.orEmpty()
    assets.icon.setImageDrawable(ad.icon?.drawable)

    assets.callToAction.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
    val hasMedia = ad.mediaContent != null
    assets.media.visibility = if (hasMedia) View.VISIBLE else View.GONE
    assets.icon.visibility = if (!hasMedia && ad.icon?.drawable != null) View.VISIBLE else View.GONE

    nativeAdView.registerNativeAd(ad, assets.media.takeIf { hasMedia })
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
