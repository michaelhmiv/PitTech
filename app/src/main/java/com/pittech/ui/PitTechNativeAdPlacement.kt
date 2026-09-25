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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
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
                        Handler(Looper.getMainLooper()).post {
                            if (disposed) {
                                nativeAd.destroy()
                            } else {
                                loadedAd = nativeAd
                                adState.value = nativeAd
                            }
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        Handler(Looper.getMainLooper()).post {
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
                factory = { PitTechNativeAdView(context) },
                update = { it.bind(ad) },
            )
        }
    }
}

private class PitTechNativeAdView(context: Context) : NativeAdView(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(4))
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Advertiser icon"
    }
    private val headline = TextView(context).apply {
        setTextColor(Color.rgb(36, 35, 31))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
        setPadding(0, 0, dp(40), 0)
    }
    private val advertiser = TextView(context).apply {
        setTextColor(Color.rgb(81, 76, 69))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, 0)
    }
    private val media = MediaView(context).apply {
        setImageScaleType(ImageView.ScaleType.CENTER_CROP)
    }
    private val body = TextView(context).apply {
        setTextColor(Color.rgb(36, 35, 31))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(8), 0, dp(8))
    }
    private val callToAction = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = GradientDrawable().apply {
            setColor(Color.rgb(123, 64, 36))
            cornerRadius = dp(8).toFloat()
        }
    }
    private var boundAd: NativeAd? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(
            content,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        header.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(
            headline,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            },
        )
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(advertiser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(
            media,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(152)).apply {
                topMargin = dp(10)
            },
        )
        content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(
            callToAction,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            },
        )

        headlineView = headline
        advertiserView = advertiser
        iconView = icon
        bodyView = body
        callToActionView = callToAction
    }

    fun bind(ad: NativeAd) {
        if (boundAd === ad) return
        boundAd = ad

        headline.text = ad.headline.orEmpty()
        advertiser.text = ad.advertiser.orEmpty()
        body.text = ad.body.orEmpty()
        callToAction.text = ad.callToAction.orEmpty()
        icon.setImageDrawable(ad.icon?.drawable)

        advertiser.visibility = if (ad.advertiser.isNullOrBlank()) View.GONE else View.VISIBLE
        body.visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
        callToAction.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        icon.visibility = if (ad.icon?.drawable == null) View.GONE else View.VISIBLE
        media.mediaContent = ad.mediaContent
        media.visibility = if (ad.mediaContent == null) View.GONE else View.VISIBLE

        registerNativeAd(ad, ad.mediaContent?.let { media })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
