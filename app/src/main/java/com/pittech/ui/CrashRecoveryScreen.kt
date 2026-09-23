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
fun CrashRecoveryScreen(
    report: CrashDiagnosticReport,
    onCopyReport: () -> Unit,
    onContinue: () -> Unit,
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
            Text("PitTech", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "PitTech stopped unexpectedly",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "We saved a diagnostic report on this phone. Nothing was sent automatically.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Reference code", style = MaterialTheme.typography.labelLarge)
                    Text(
                        report.referenceCode,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("What happened", style = MaterialTheme.typography.labelLarge)
                    Text(report.summary, style = MaterialTheme.typography.bodyLarge)
                    Text("Time: ${report.occurredAtUtc}", style = MaterialTheme.typography.bodyMedium)
                }
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
                Text(if (copied) "Report copied — paste it into our chat" else "Copy diagnostic report")
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

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("crash-report-continue"),
            ) {
                Text("Continue to PitTech")
            }
        }
    }
}
