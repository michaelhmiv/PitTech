package com.pittech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pittech.CrashDiagnosticReport

@Composable
internal fun CrashRecoveryScreen(
    report: CrashDiagnosticReport,
    onCopyReport: () -> Unit,
    onContinue: () -> Unit,
    // PL-20 (#172): attach the saved report to a problem report directly,
    // instead of the copy-and-ferry clipboard step.
    onAttachToProblemReport: () -> Unit = {},
) {
    var copied by rememberSaveable(report.referenceCode) { mutableStateOf(false) }
    var showTechnicalDetails by rememberSaveable(report.referenceCode) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // TURN-IN (`d06`, A1 canonical): banner headline, the report as
            // a bordered card, the reference code in the mono face — the
            // app's own words, unchanged (§1.1).
            TurnInMetaLabel("PitTech")
            TurnInBannerText(
                "PitTech stopped unexpectedly",
                style = PitTechTextStyles.bannerTitle,
            )
            Text(
                "We saved a diagnostic report on this phone. Nothing was sent automatically.",
                style = PitTechTextStyles.body,
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = pitTechTokens().surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, pitTechTokens().ruling),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TurnInMetaLabel("Reference code")
                    Text(
                        report.referenceCode,
                        style = PitTechTextStyles.monoReadout,
                    )
                    TurnInMetaLabel("What happened")
                    Text(report.summary, style = PitTechTextStyles.body)
                    Text("Time: ${report.occurredAtUtc}", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
                }
            }

            // The primary action leads: Continue sits directly under the
            // report card so it is always on screen (PL-20 also returns to
            // the crashed cook from here); the share actions follow, and
            // the technical-details expander comes last.
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("crash-report-continue"),
            ) {
                Text("Continue to PitTech")
            }

            OutlinedButton(
                onClick = onAttachToProblemReport,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("crash-report-attach"),
            ) {
                Text("Attach to a problem report")
            }

            OutlinedButton(
                onClick = {
                    onCopyReport()
                    copied = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("crash-report-copy"),
            ) {
                Text(
                    if (copied) {
                        "Report copied. Include it in Settings → Report a problem if you'd like to share it."
                    } else {
                        "Copy diagnostic report"
                    },
                )
            }

            TextButton(
                onClick = { showTechnicalDetails = !showTechnicalDetails },
                modifier = Modifier.testTag("crash-report-details"),
            ) {
                Text(if (showTechnicalDetails) "Hide technical details" else "Show technical details")
            }

            if (showTechnicalDetails) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        report.details,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
