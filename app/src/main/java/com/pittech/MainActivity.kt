package com.pittech

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pittech.ui.CrashRecoveryScreen
import com.pittech.ui.PitTechApp
import com.pittech.ui.PitTechTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CooksViewModel by viewModels {
        CooksViewModel.Factory((application as PitTechApplication).cookRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var crashReport by remember {
                mutableStateOf(CrashDiagnostics.pendingReport(this@MainActivity))
            }
            PitTechTheme {
                val report = crashReport
                if (report != null) {
                    CrashRecoveryScreen(
                        report = report,
                        onCopyReport = {
                            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("PitTech diagnostic report", report.toPlainText()),
                            )
                        },
                        onContinue = {
                            CrashDiagnostics.clearPendingReport(this@MainActivity)
                            crashReport = null
                        },
                    )
                } else {
                    PitTechApp(viewModel)
                }
            }
        }
    }
}
