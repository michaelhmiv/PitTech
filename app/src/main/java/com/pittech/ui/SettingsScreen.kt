package com.pittech.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.BuildConfig
import com.pittech.CrashDiagnostics
import com.pittech.CooksViewModel
import com.pittech.FeedbackApi
import com.pittech.FeedbackKind
import com.pittech.FeedbackRequest
import com.pittech.FeedbackSubmitResult
import com.pittech.domain.EditorDraftKeys
import com.pittech.domain.FeedbackDraft
import com.pittech.domain.FeedbackDraftCodec

@Composable
fun SettingsScreen(
    viewModel: CooksViewModel,
    themeMode: PitTechThemeMode,
    onThemeModeChange: (PitTechThemeMode) -> Unit,
    temperatureUnit: String,
    weightUnit: String,
    onTemperatureUnitChange: (String) -> Unit,
    onWeightUnitChange: (String) -> Unit,
    privacyOptionsRequired: Boolean = false,
    onShowPrivacyOptions: () -> Unit = {},
    // PL-20 (#172): crash recovery's "Attach to a problem report" lands
    // here with the feedback form already open (the saved report stays
    // attached — acknowledging the recovery prompt does not delete it).
    openFeedbackOnStart: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var savedCrashReport by remember(context) { mutableStateOf(CrashDiagnostics.savedReport(context)) }
    var backupDetails by remember { mutableStateOf(false) }
    var noticesAsset by remember { mutableStateOf<String?>(null) }
    // PL-27 (#172): Feedback converges on the iOS shape — the kind is
    // chosen in the form, a review step precedes submit, and the draft
    // survives cancel in the device-local draft store (PL-24). Android
    // keeps its crash-report attach. The old openFeedback ZEROED the
    // form on every open; opening now restores the kept draft instead.
    val feedbackDraftStore = rememberEditorDraftStore()
    var feedbackOpen by rememberSaveable { mutableStateOf(false) }
    var feedbackKindName by rememberSaveable { mutableStateOf(FeedbackKind.BUG.name) }
    var feedbackTitle by rememberSaveable { mutableStateOf("") }
    var feedbackDescription by rememberSaveable { mutableStateOf("") }
    var includeCrashReport by rememberSaveable { mutableStateOf(false) }
    var showCrashPreview by rememberSaveable { mutableStateOf(false) }
    var feedbackReviewing by rememberSaveable { mutableStateOf(false) }
    var feedbackRestored by rememberSaveable { mutableStateOf(false) }
    var feedbackSubmitting by remember { mutableStateOf(false) }
    var feedbackError by rememberSaveable { mutableStateOf<String?>(null) }
    var feedbackNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var hasFeedbackDraft by remember {
        mutableStateOf(
            feedbackDraftStore.load(EditorDraftKeys.FEEDBACK)?.let(FeedbackDraftCodec::decode)
                ?.let { it.title.isNotBlank() || it.description.isNotBlank() } == true,
        )
    }
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

    fun openFeedback(preferredKind: FeedbackKind) {
        val draft = feedbackDraftStore.load(EditorDraftKeys.FEEDBACK)?.let(FeedbackDraftCodec::decode)
        if (draft != null && (draft.title.isNotBlank() || draft.description.isNotBlank())) {
            feedbackKindName = runCatching { FeedbackKind.valueOf(draft.kind) }.getOrDefault(preferredKind).name
            feedbackTitle = draft.title
            feedbackDescription = draft.description
            includeCrashReport = draft.includeCrashReport && feedbackKindName == FeedbackKind.BUG.name && savedCrashReport != null
            feedbackRestored = true
        } else {
            feedbackKindName = preferredKind.name
            feedbackTitle = ""
            feedbackDescription = ""
            includeCrashReport = preferredKind == FeedbackKind.BUG && savedCrashReport != null
            feedbackRestored = false
        }
        showCrashPreview = false
        feedbackReviewing = false
        feedbackSubmitting = false
        feedbackError = null
        feedbackOpen = true
    }

    // PL-20 (#172): crash recovery's attach action lands here with the
    // form open; the saved (acknowledged) report still attaches.
    LaunchedEffect(openFeedbackOnStart) {
        if (openFeedbackOnStart) openFeedback(FeedbackKind.BUG)
    }
    // PL-24: autosave the working draft while the form is open, so
    // leaving and returning restores it. A successful submit clears it.
    LaunchedEffect(feedbackOpen, feedbackKindName, feedbackTitle, feedbackDescription, includeCrashReport) {
        if (feedbackOpen) {
            feedbackDraftStore.save(
                EditorDraftKeys.FEEDBACK,
                FeedbackDraftCodec.encode(FeedbackDraft(feedbackKindName, feedbackTitle, feedbackDescription, includeCrashReport)),
            )
            hasFeedbackDraft = feedbackTitle.isNotBlank() || feedbackDescription.isNotBlank()
        }
    }

    val feedbackKind = FeedbackKind.valueOf(feedbackKindName)
    if (feedbackOpen) {
        val reportToInclude = savedCrashReport.takeIf { feedbackKind == FeedbackKind.BUG && includeCrashReport }
        val deviceLabel = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Unknown device" }
        // PL-22 (DD-6): the feedback form is a form — full screen.
        // PL-27 (#172): the flow inside is edit → review → submit, like iOS.
        FullScreenSurface(
            onDismiss = { if (!feedbackSubmitting) feedbackOpen = false },
            title = if (feedbackReviewing) "Review feedback" else "Send feedback",
            dismissEnabled = !feedbackSubmitting,
            actions = {
                if (feedbackReviewing) {
                    TextButton(
                        onClick = { feedbackReviewing = false },
                        enabled = !feedbackSubmitting,
                    ) { Text("Back") }
                    TextButton(
                        enabled = feedbackTitle.isNotBlank() && !feedbackSubmitting,
                        onClick = {
                            val request = FeedbackRequest(
                                kind = feedbackKind,
                                title = feedbackTitle,
                                description = feedbackDescription,
                                appVersion = BuildConfig.VERSION_NAME,
                                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                device = deviceLabel,
                                diagnosticReport = reportToInclude,
                            )
                            feedbackSubmitting = true
                            feedbackError = null
                            FeedbackApi.submitAsync(request) { result ->
                                feedbackSubmitting = false
                                when (result) {
                                    is FeedbackSubmitResult.Success -> {
                                        // PL-24/PL-27: only a successful
                                        // submit clears the kept draft.
                                        feedbackDraftStore.clear(EditorDraftKeys.FEEDBACK)
                                        hasFeedbackDraft = false
                                        feedbackOpen = false
                                        feedbackNotice = result.issueNumber
                                            ?.let { "Thanks — feedback submitted as GitHub issue #$it." }
                                            ?: "Thanks — your feedback was submitted."
                                    }
                                    is FeedbackSubmitResult.Failure -> feedbackError = result.message
                                }
                            }
                        },
                        modifier = Modifier.testTag("feedback-submit"),
                    ) { Text(if (feedbackSubmitting) "Submitting…" else "Submit feedback") }
                } else {
                    TextButton(
                        onClick = { feedbackOpen = false },
                        enabled = !feedbackSubmitting,
                    ) { Text("Cancel") }
                    TextButton(
                        enabled = feedbackTitle.isNotBlank() && !feedbackSubmitting,
                        onClick = { feedbackError = null; feedbackReviewing = true },
                        modifier = Modifier.testTag("feedback-review"),
                    ) { Text("Review feedback") }
                }
            },
        ) {
                if (feedbackReviewing) {
                    // PL-27: the review step — exactly what will be sent,
                    // shown before anything is submitted (mirrors iOS).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (feedbackKind == FeedbackKind.BUG) "Problem report" else "Feature idea",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(feedbackTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("feedback-review-title"))
                        Text(feedbackDescription, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("feedback-review-description"))
                        Text("Included app details", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "PitTech ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · $deviceLabel",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (reportToInclude != null) {
                            Text(
                                "The saved crash report (reference ${reportToInclude.referenceCode}) is included.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text("This text will be public on GitHub.", style = MaterialTheme.typography.bodySmall)
                        feedbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (feedbackRestored) {
                        ResumeDraftBanner(onDiscard = {
                            feedbackDraftStore.clear(EditorDraftKeys.FEEDBACK)
                            hasFeedbackDraft = false
                            feedbackRestored = false
                            feedbackKindName = FeedbackKind.BUG.name
                            feedbackTitle = ""
                            feedbackDescription = ""
                            includeCrashReport = savedCrashReport != null
                        })
                    }
                    // PL-27: the kind is chosen in the form, not before it.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (feedbackKind == FeedbackKind.BUG) {
                            Button(onClick = { feedbackKindName = FeedbackKind.BUG.name }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("feedback-kind-bug")) { Text("Problem") }
                        } else {
                            OutlinedButton(onClick = { feedbackKindName = FeedbackKind.BUG.name; includeCrashReport = savedCrashReport != null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("feedback-kind-bug")) { Text("Problem") }
                        }
                        if (feedbackKind == FeedbackKind.FEATURE) {
                            Button(onClick = { feedbackKindName = FeedbackKind.FEATURE.name }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("feedback-kind-feature")) { Text("Feature idea") }
                        } else {
                            OutlinedButton(onClick = { feedbackKindName = FeedbackKind.FEATURE.name }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("feedback-kind-feature")) { Text("Feature idea") }
                        }
                    }
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
                        "Submitting creates a public issue in the PitTech GitHub repository through PitTech's feedback relay. No GitHub account is required. Review what you entered before sending, and do not include personal information. A saved crash report is sent only when you choose to include it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    feedbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SectionCard("Appearance") {
            // TURN-IN (BUILD-SPEC §3.4): the working Dark / Light / System
            // control designed in Area 06 frame f01 replaces the #172
            // informational row. Dark is the fresh-install default.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    PitTechThemeMode.DARK to "Dark",
                    PitTechThemeMode.LIGHT to "Light",
                    PitTechThemeMode.SYSTEM to "System",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("theme-mode-${mode.name.lowercase()}"),
                    )
                }
            }
            Text(
                "Dark is the default — light is the same layout.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard("Units") {
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

        SectionCard("Cook notifications") {
            Text("Guidance reminders and monitoring alerts have separate sound settings. Turn on alerts from a cook when you want to use them.")
            Text(if (com.pittech.CookGuidanceNotifications.notificationsAvailable(context)) "Notifications allowed" else "Notifications are disabled", style = MaterialTheme.typography.bodyMedium)
            Text(if (com.pittech.CookGuidanceNotifications.preciseAvailable(context) && context.getSharedPreferences("pittech-preferences", 0).getBoolean("precise-cook-timers", false)) "Precise cook timers enabled" else "Cook timers may be delayed by Android", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Notification settings") }
            // PL-29 rider (#172): test feedback stays in-app — no real notification is posted for a test.
            TextButton(onClick = { viewModel.showNotice("Test alert — alert feedback appears here in the app. Real alerts post a notification when they fire.") }) { Text("Send test alert") }
        }

        SectionCard("Your data") {
            Text(SettingsCopy.DATA_STAYS_LINE, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { backupDetails = !backupDetails }) { Text(if (backupDetails) "Hide backup details" else "Backup details") }
            if (backupDetails) Text(SettingsCopy.FULL_BACKUP_SCOPE, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { zip.launch("PitTech_Backup.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save full backup (ZIP)") }
            OutlinedButton(onClick = { viewModel.createShareArchive() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("share-full-backup")) { Text("Share full backup (ZIP)") }
            if (backupDetails) Text("Share opens Android's app chooser. The ZIP is sent only after you choose an app; available destinations depend on what is installed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { xlsx.launch("PitTech_Cook_Log.xlsx") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export Excel workbook") }
            OutlinedButton(onClick = { csv.launch("PitTech_Readings.csv") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export temperature readings (CSV)") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Restore from ZIP backup") }
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text(LoadingCopy.WORKING_WITH_DATA, style = MaterialTheme.typography.bodyLarge) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyLarge) }
        }

        SectionCard("Feedback") {
            Text(
                "Tell us about a problem or suggest a feature without needing a GitHub account. Bug reports can include the last saved crash report when you opt in. Cook records and photos are never included.",
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
            if (hasFeedbackDraft) {
                // PL-24/PL-27: the visible Resume affordance for a kept
                // draft — opening restores it into the form.
                Text(
                    "You have an unsent feedback draft on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { openFeedback(FeedbackKind.BUG) },
                    modifier = Modifier.testTag("feedback-resume"),
                ) { Text("Resume draft") }
            }
            feedbackNotice?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            savedCrashReport?.let { report ->
                Text(
                    "A crash report (${report.referenceCode}) is saved on this phone. It is not sent unless you choose to include it in a problem report.",
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

        // PL-26: About closes the aligned section order on both platforms
        // (SettingsCopy.SECTION_ORDER) — Version, Contact support, and the
        // bundled third-party notices iOS already showed.
        SectionCard("About") {
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("settings-version"))
            TextButton(
                onClick = { uriHandler.openUri("mailto:sportfolioholdings@gmail.com") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Contact support") }
            TextButton(
                onClick = { noticesAsset = "third-party-telemetry-notices.txt" },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Third-party telemetry notices") }
            TextButton(
                onClick = { noticesAsset = "third-party-grill-notices.txt" },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Third-party grill notices") }
        }
    }

    noticesAsset?.let { asset ->
        val noticesText = remember(asset) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }
                .getOrElse { "No dependency notices in this build." }
        }
        FullScreenSurface(onDismiss = { noticesAsset = null }, title = "Third-party notices", actions = {
            TextButton(onClick = { noticesAsset = null }) { Text("Done") }
        }) {
            Text(noticesText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        }
    }
}
