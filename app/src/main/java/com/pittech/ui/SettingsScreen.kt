package com.pittech.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.BuildConfig
import com.pittech.CrashDiagnostics
import com.pittech.CooksViewModel
import com.pittech.FeedbackIssueLink
import com.pittech.FeedbackKind

@Composable
fun SettingsScreen(
    viewModel: CooksViewModel,
    temperatureUnit: String,
    weightUnit: String,
    onTemperatureUnitChange: (String) -> Unit,
    onWeightUnitChange: (String) -> Unit,
    privacyOptionsRequired: Boolean = false,
    onShowPrivacyOptions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    var savedCrashReport by remember(context) { mutableStateOf(CrashDiagnostics.savedReport(context)) }
    var feedbackKindName by rememberSaveable { mutableStateOf<String?>(null) }
    var feedbackTitle by rememberSaveable { mutableStateOf("") }
    var feedbackDescription by rememberSaveable { mutableStateOf("") }
    var includeCrashReport by rememberSaveable { mutableStateOf(false) }
    var showCrashPreview by rememberSaveable { mutableStateOf(false) }
    var feedbackError by rememberSaveable { mutableStateOf<String?>(null) }
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_XLSX)
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_ZIP)
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_CSV)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.previewImport(uri)
    }

    fun openFeedback(kind: FeedbackKind) {
        feedbackKindName = kind.name
        feedbackTitle = ""
        feedbackDescription = ""
        includeCrashReport = kind == FeedbackKind.BUG && savedCrashReport != null
        showCrashPreview = false
        feedbackError = null
    }

    val feedbackKind = feedbackKindName?.let { runCatching { FeedbackKind.valueOf(it) }.getOrNull() }
    if (feedbackKind != null) {
        val reportToInclude = savedCrashReport.takeIf { feedbackKind == FeedbackKind.BUG && includeCrashReport }
        AlertDialog(
            onDismissRequest = { feedbackKindName = null },
            title = { Text(if (feedbackKind == FeedbackKind.BUG) "Report a problem" else "Request a feature") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = feedbackTitle,
                        onValueChange = { feedbackTitle = it.take(120) },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("${feedbackTitle.length}/120") },
                    )
                    OutlinedTextField(
                        value = feedbackDescription,
                        onValueChange = { feedbackDescription = it.take(1_500) },
                        label = { Text(if (feedbackKind == FeedbackKind.BUG) "What happened?" else "What would you like PitTech to do?") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("${feedbackDescription.length}/1500") },
                    )
                    if (feedbackKind == FeedbackKind.BUG && savedCrashReport != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(
                                checked = includeCrashReport,
                                onCheckedChange = { includeCrashReport = it },
                            )
                            Column {
                                Text("Include the saved crash report", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Reference ${savedCrashReport!!.referenceCode}; includes the exception summary and stack trace.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (includeCrashReport) {
                            TextButton(onClick = { showCrashPreview = !showCrashPreview }) {
                                Text(if (showCrashPreview) "Hide crash report preview" else "Preview crash report")
                            }
                            if (showCrashPreview) {
                                Card {
                                    Text(
                                        savedCrashReport!!.toPlainText(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 180.dp)
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "PitTech will open a prefilled draft on GitHub. The report text is sent to GitHub to fill that draft; it becomes a public issue only if you submit it there. Review the contents first. You will need a GitHub account to submit.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    feedbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = feedbackTitle.isNotBlank(),
                    onClick = {
                        val device = listOf(Build.MANUFACTURER, Build.MODEL)
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" ")
                            .ifBlank { "Unknown device" }
                        val link = FeedbackIssueLink.create(
                            kind = feedbackKind,
                            title = feedbackTitle,
                            description = feedbackDescription,
                            appVersion = BuildConfig.VERSION_NAME,
                            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                            device = device,
                            diagnosticReport = reportToInclude,
                        )
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                            .onSuccess { feedbackKindName = null }
                            .onFailure { feedbackError = "Couldn't open GitHub. Check that a browser is installed and try again." }
                    },
                ) { Text("Continue to GitHub") }
            },
            dismissButton = { TextButton(onClick = { feedbackKindName = null }) { Text("Cancel") } },
        )
    }

    if (importPreview != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Review backup contents") },
            text = {
                Text("This archive contains ${importPreview!!.cookCount} cooks, ${importPreview!!.dishCount} dishes, and ${importPreview!!.photoCount} photos. PitTech will add cooks that are not already in this phone's library and skip duplicate cook IDs.")
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport, enabled = !busy) { Text("Restore cooks") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancel") } },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SectionCard("Display") {
            Text("Temperature", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("°F", "°C").forEach { unit ->
                    if (temperatureUnit == unit) Button(onClick = { onTemperatureUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                    else OutlinedButton(onClick = { onTemperatureUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                }
            }
            Text("Weight", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("lb", "kg").forEach { unit ->
                    if (weightUnit == unit) Button(onClick = { onWeightUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                    else OutlinedButton(onClick = { onWeightUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                }
            }
            Text("Changing a preferred unit affects new entries. Existing records keep the unit in which you entered them.", style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard("Your data") {
            Text("Cook records and photos stay on this phone. No account is needed. Save a backup somewhere you control so you can restore it after changing phones or reinstalling PitTech.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { zip.launch("PitTech_Backup.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export complete backup (ZIP)") }
            OutlinedButton(onClick = { xlsx.launch("PitTech_Cook_Log.xlsx") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export Excel workbook") }
            OutlinedButton(onClick = { csv.launch("PitTech_Readings.csv") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export temperature readings (CSV)") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Restore from ZIP backup") }
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text("Working with your cook data…", style = MaterialTheme.typography.bodyLarge) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyLarge) }
        }

        SectionCard("Feedback") {
            Text(
                "Tell us about a problem or suggest a feature. Bug reports can include the last saved crash report. Cook records and photos are never added to these drafts.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = { openFeedback(FeedbackKind.BUG) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Report a problem") }
            OutlinedButton(
                onClick = { openFeedback(FeedbackKind.FEATURE) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Request a feature") }
            savedCrashReport?.let { report ->
                Text(
                    "A crash report (${report.referenceCode}) is saved on this phone. It is not sent unless you choose to include it in a GitHub draft.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = {
                        CrashDiagnostics.clearPendingReport(context)
                        savedCrashReport = null
                        includeCrashReport = false
                    },
                ) { Text("Delete saved crash report") }
            }
        }

        SectionCard("Privacy and offline use") {
            Text("You can start, edit, review, and export cooks without internet access. PitTech has no sign-in screen. Photos are copied into the app's private storage so the cook log can use them offline.", style = MaterialTheme.typography.bodyLarge)
            Text("A backup file is your portable copy. Keep it in a place you can find again.", style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard("Ads and privacy") {
            Text(
                "PitTech may show a Google ad in cook history after you have at least four completed cooks and no cook is active. When an ad request is allowed by your privacy choices, Google's SDK may collect and share your IP address, ad interactions, diagnostics, and device or account identifiers for advertising, analytics, and fraud prevention. Google encrypts this data in transit. PitTech does not attach cook records or photos to ad requests.",
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(
                onClick = { uriHandler.openUri("https://pittech-privacy-production.up.railway.app/") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Read the privacy policy")
            }
            if (privacyOptionsRequired) {
                OutlinedButton(
                    onClick = onShowPrivacyOptions,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("Privacy options")
                }
            }
        }
    }
}
