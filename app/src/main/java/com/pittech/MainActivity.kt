package com.pittech

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.ads.PitTechAdsConsentManager
import com.pittech.ui.CrashRecoveryScreen
import com.pittech.ui.PitTechApp
import com.pittech.ui.PitTechTheme

class MainActivity : ComponentActivity() {
    private val adsConsentManager by lazy { PitTechAdsConsentManager(this) }

    private val viewModel: CooksViewModel by viewModels {
        val app = application as PitTechApplication
        CooksViewModel.Factory(app.cookRepository, app.dataTransfer, app)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var crashReport by remember {
                mutableStateOf(CrashDiagnostics.pendingReport(this@MainActivity))
            }
            val canRequestAds by adsConsentManager.canRequestAds.collectAsStateWithLifecycle()
            val adsSdkReady by adsConsentManager.sdkReady.collectAsStateWithLifecycle()
            val privacyOptionsRequired by adsConsentManager.privacyOptionsRequired.collectAsStateWithLifecycle()
            PitTechTheme {
                val report = crashReport
                LaunchedEffect(report) {
                    if (report == null) adsConsentManager.requestConsent()
                }
                if (report != null) {
                    CrashRecoveryScreen(
                        report = report,
                        onCopyReport = {
                            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("PitTech diagnostic report", report.toPlainText()),
                            )
                        },
                        onContinue = {
                            CrashDiagnostics.acknowledgeReport(this@MainActivity, report)
                            crashReport = null
                        },
                    )
                } else {
                    PitTechApp(
                        viewModel = viewModel,
                        adsEnabled = canRequestAds && adsSdkReady,
                        privacyOptionsRequired = privacyOptionsRequired,
                        onShowPrivacyOptions = adsConsentManager::showPrivacyOptions,
                    )
                }
            }
        }
    }
}
