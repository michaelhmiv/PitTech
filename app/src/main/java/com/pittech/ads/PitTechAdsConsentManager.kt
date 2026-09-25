package com.pittech.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.pittech.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class PitTechAdsConsentManager(private val activity: Activity) {
    private val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
    private val requestStarted = AtomicBoolean(false)

    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds = _canRequestAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired = _privacyOptionsRequired.asStateFlow()

    val sdkReady = PitTechMobileAdsInitializer.ready

    fun requestConsent() {
        if (!requestStarted.compareAndSet(false, true)) return

        val parameters = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                refreshPrivacyOptionsRequirement()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "UMP consent form error: ${formError.message}")
                    }
                    refreshConsentAndInitializeIfAllowed()
                }
            },
            { requestError ->
                Log.w(TAG, "UMP consent information update failed: ${requestError.message}")
                // UMP can still permit requests using consent saved from a prior session.
                refreshConsentAndInitializeIfAllowed()
            },
        )
    }

    fun showPrivacyOptions() {
        if (!_privacyOptionsRequired.value) return

        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "UMP privacy options form error: ${formError.message}")
            }
            refreshConsentAndInitializeIfAllowed()
        }
    }

    private fun refreshPrivacyOptionsRequirement() {
        _privacyOptionsRequired.value = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun refreshConsentAndInitializeIfAllowed() {
        refreshPrivacyOptionsRequirement()
        val allowed = consentInformation.canRequestAds()
        _canRequestAds.value = allowed
        if (allowed) {
            PitTechMobileAdsInitializer.initialize(
                activity.applicationContext,
                activity.getString(R.string.admob_app_id),
            )
        }
    }

    private companion object {
        const val TAG = "PitTechAdsConsent"
    }
}

private object PitTechMobileAdsInitializer {
    private val initializationStarted = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    fun initialize(context: Context, appId: String) {
        if (!initializationStarted.compareAndSet(false, true)) return

        Thread(
            {
                MobileAds.initialize(
                    context,
                    InitializationConfig.Builder(appId).build(),
                ) {
                    _ready.value = true
                }
            },
            "PitTech-GMA-init",
        ).start()
    }
}
