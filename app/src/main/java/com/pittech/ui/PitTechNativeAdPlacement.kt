package com.pittech.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
internal fun PitTechNativeAdPlacement(adsEnabled: Boolean) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val adState = remember { mutableStateOf<NativeAd?>(null) }
    val adUnitId = BuildConfig.PITTECH_NATIVE_AD_UNIT_ID

    DisposableEffect(adsEnabled, adUnitId) {
        var disposed = false
        var loadedAd: NativeAd? = null
        adState.value = null

        if (adsEnabled) {
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
                            if (!disposed) adState.value = null
                        }
                    }
                },
            )
        }

        onDispose {
            disposed = true
            loadedAd?.destroy()
            adState.value = null
        }
    }

    val ad = adState.value ?: return
    val adStyle = PitTechNativeAdStyle(
        headlineColor = MaterialTheme.colorScheme.onSurface.toArgb(),
        secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        actionColor = MaterialTheme.colorScheme.primary.toArgb(),
        actionTextColor = MaterialTheme.colorScheme.onPrimary.toArgb(),
    )
    Card(
        modifier = Modifier.fillMaxWidth().testTag("cook-history-native-ad"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Ad",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { viewContext -> createPitTechNativeAdView(viewContext, adStyle) },
                update = { view -> bindPitTechNativeAd(view, ad, adStyle) },
            )
        }
    }
}

private data class PitTechNativeAdAssets(
    val icon: ImageView,
    val headline: TextView,
    val advertiser: TextView,
    val media: MediaView,
    val body: TextView,
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
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(4))
    }
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Advertiser icon"
    }
    val headline = TextView(context).apply {
        setTextColor(style.headlineColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
        setPadding(0, 0, context.dp(40), 0)
    }
    val advertiser = TextView(context).apply {
        setTextColor(style.secondaryTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, context.dp(4), 0, 0)
    }
    val media = MediaView(context)
    val body = TextView(context).apply {
        setTextColor(style.headlineColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, context.dp(8), 0, context.dp(8))
    }
    val callToAction = TextView(context).apply {
        setTextColor(style.actionTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(context.dp(16), context.dp(11), context.dp(16), context.dp(11))
        background = GradientDrawable().apply {
            setColor(style.actionColor)
            cornerRadius = context.dp(8).toFloat()
        }
    }

    header.addView(icon, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
    header.addView(
        headline,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(10)
        },
    )
    content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(advertiser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(
        media,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(152)).apply {
            topMargin = context.dp(10)
        },
    )
    content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    content.addView(
        callToAction,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(4)
        },
    )
    nativeAdView.addView(
        content,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    nativeAdView.headlineView = headline
    nativeAdView.advertiserView = advertiser
    nativeAdView.iconView = icon
    nativeAdView.bodyView = body
    nativeAdView.callToActionView = callToAction
    nativeAdView.tag = PitTechNativeAdAssets(icon, headline, advertiser, media, body, callToAction)
    return nativeAdView
}

private fun bindPitTechNativeAd(nativeAdView: NativeAdView, ad: NativeAd, style: PitTechNativeAdStyle) {
    val assets = nativeAdView.tag as PitTechNativeAdAssets
    assets.headline.setTextColor(style.headlineColor)
    assets.advertiser.setTextColor(style.secondaryTextColor)
    assets.body.setTextColor(style.headlineColor)
    assets.callToAction.setTextColor(style.actionTextColor)
    (assets.callToAction.background as? GradientDrawable)?.setColor(style.actionColor)
    if (assets.boundAd === ad) return
    assets.boundAd = ad

    assets.headline.text = ad.headline.orEmpty()
    assets.advertiser.text = ad.advertiser.orEmpty()
    assets.body.text = ad.body.orEmpty()
    assets.callToAction.text = ad.callToAction.orEmpty()
    assets.icon.setImageDrawable(ad.icon?.drawable)

    assets.advertiser.visibility = if (ad.advertiser.isNullOrBlank()) View.GONE else View.VISIBLE
    assets.body.visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
    assets.callToAction.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
    assets.icon.visibility = if (ad.icon?.drawable == null) View.GONE else View.VISIBLE
    assets.media.visibility = if (ad.mediaContent == null) View.GONE else View.VISIBLE

    nativeAdView.registerNativeAd(ad, assets.media.takeIf { ad.mediaContent != null })
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
