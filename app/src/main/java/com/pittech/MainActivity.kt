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
import com.pittech.ui.PitTechThemeMode

class MainActivity : ComponentActivity() {
    private val adsConsentManager by lazy { PitTechAdsConsentManager(this) }

    private val viewModel: CooksViewModel by viewModels {
        val app = application as PitTechApplication
        CooksViewModel.Factory(app.cookRepository, app.dataTransfer, app)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleReminderIntent(intent)
        if (savedInstanceState == null) handleArchiveIntent(intent)
        setContent {
            val preferences = remember(this@MainActivity) {
                this@MainActivity.getSharedPreferences("pittech-preferences", MODE_PRIVATE)
            }
            var themeMode by remember(preferences) {
                mutableStateOf(PitTechThemeMode.fromPreference(preferences.getString(PitTechThemeMode.PREFERENCE_KEY, null)))
            }
            var crashReport by remember {
                mutableStateOf(CrashDiagnostics.pendingReport(this@MainActivity))
            }
            val canRequestAds by adsConsentManager.canRequestAds.collectAsStateWithLifecycle()
            val adsSdkReady by adsConsentManager.sdkReady.collectAsStateWithLifecycle()
            val privacyOptionsRequired by adsConsentManager.privacyOptionsRequired.collectAsStateWithLifecycle()
            PitTechTheme(themeMode = themeMode) {
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
                        themeMode = themeMode,
                        onThemeModeChange = { newMode ->
                            themeMode = newMode
                            preferences.edit().putString(PitTechThemeMode.PREFERENCE_KEY, newMode.name).apply()
                        },
                        adsEnabled = canRequestAds && adsSdkReady,
                        privacyOptionsRequired = privacyOptionsRequired,
                        onShowPrivacyOptions = adsConsentManager::showPrivacyOptions,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
        handleArchiveIntent(intent)
    }

    private fun handleArchiveIntent(intent: android.content.Intent?) {
        val uri = when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java) else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri?.scheme == "content") viewModel.previewImport(uri)
        else if (uri != null && intent?.action == android.content.Intent.ACTION_VIEW) {
            if (viewModel.publicDiscovery.receivePublic(uri.toString())) return
            if (uri.path == "/auth") { viewModel.account.receive(uri.toString()); return }
            runCatching { com.pittech.data.RelayProtocol.code(uri.toString()) }.onSuccess { viewModel.relay.openImport(it) }
        }
    }

    private fun handleReminderIntent(intent: android.content.Intent?) {
        val cookId = intent?.getStringExtra(CookReminderNotifications.EXTRA_COOK_ID) ?: return
        val reminderId = intent.getStringExtra(CookReminderNotifications.EXTRA_REMINDER_ID)
        viewModel.openCook(cookId)
        reminderId?.let(viewModel::requestReminderCheckIn)
        intent.removeExtra(CookReminderNotifications.EXTRA_REMINDER_ID)
    }
}
