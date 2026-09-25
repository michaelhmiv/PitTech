package com.pittech.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun FeedbackDialog(
    onDismiss: () -> Unit,
    initialKind: FeedbackKind = FeedbackKind.BUG,
    initialTitle: String = "",
    initialDetails: String = "",
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val diagnosticBundle = remember(context) { FeedbackDiagnosticBundle.create(context) }
    var selectedKindName by rememberSaveable(initialKind) { mutableStateOf(initialKind.name) }
    val selectedKind = FeedbackKind.valueOf(selectedKindName)
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var details by rememberSaveable(initialDetails) { mutableStateOf(initialDetails) }
    var includeDiagnostics by rememberSaveable { mutableStateOf(true) }
    var showDiagnosticsPreview by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Feedback for PitTech") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Choose what you want to send.")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FeedbackKind.entries.forEach { kind ->
                        FilterChip(
                            selected = selectedKind == kind,
                            onClick = {
                                selectedKindName = kind.name
                                showDiagnosticsPreview = false
                            },
                            label = {
                                Text(if (kind == FeedbackKind.BUG) "Report a problem" else "Request a feature")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("feedback-kind-${kind.name.lowercase()}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    modifier = Modifier.fillMaxWidth().testTag("feedback-title"),
                    label = { Text("Short title") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(1_800) },
                    modifier = Modifier.fillMaxWidth().testTag("feedback-details"),
                    label = {
                        Text(
                            if (selectedKind == FeedbackKind.BUG) {
                                "What happened? How can we reproduce it?"
                            } else {
                                "What would you like PitTech to do?"
                            },
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                )

                if (selectedKind == FeedbackKind.BUG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeDiagnostics,
                            onCheckedChange = { includeDiagnostics = it },
                            modifier = Modifier.testTag("feedback-include-diagnostics"),
                        )
                        Text("Include diagnostic details")
                    }
                    Text(
                        "This includes the PitTech version, Android version, device model, recent screen/action names, and the latest saved crash report if one exists. PitTech does not automatically attach cook records, notes, or photos. A GitHub account is required to post. When you continue, GitHub receives the report to prefill its draft form. Issues are public after you submit them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (includeDiagnostics) {
                        TextButton(
                            onClick = { showDiagnosticsPreview = !showDiagnosticsPreview },
                            modifier = Modifier.testTag("feedback-diagnostics-preview-toggle"),
                        ) {
                            Text(if (showDiagnosticsPreview) "Hide diagnostics preview" else "Preview included diagnostics")
                        }
                        if (showDiagnosticsPreview) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .testTag("feedback-diagnostics-preview"),
                            ) {
                                Text(
                                    diagnosticBundle,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "Opening this sends the title and description to GitHub to prefill an unsubmitted issue form. A GitHub account is required to submit it, and issues are public.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val diagnostics = if (selectedKind == FeedbackKind.BUG && includeDiagnostics) {
                        diagnosticBundle
                    } else {
                        ""
                    }
                    val issueUrl = FeedbackIssueUrl.build(
                        kind = selectedKind,
                        title = title,
                        details = details,
                        diagnostics = diagnostics,
                    )
                    runCatching { uriHandler.openUri(issueUrl) }
                        .onSuccess { onDismiss() }
                        .onFailure {
                            Toast.makeText(
                                context,
                                "Could not open GitHub. Please try again.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                },
                enabled = title.isNotBlank() && details.isNotBlank(),
                modifier = Modifier.testTag("feedback-continue"),
            ) {
                Text("Continue to GitHub")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
