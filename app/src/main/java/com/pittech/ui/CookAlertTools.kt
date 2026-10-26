@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pittech.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.CookAlertNotifications
import com.pittech.data.*
import com.pittech.domain.*

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CookAlertTools(data: CookDetailData, viewModel: CooksViewModel, configuredOnly: Boolean? = null, focusedAlertId: String? = null) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val alerts = records.filter { it.kind == "alert" && it.cookId == data.cook.id }
    var expanded by remember { mutableStateOf(false) }
    // PL-03 (#172): a notification tap opens this sheet at the firing rule.
    LaunchedEffect(focusedAlertId) { if (focusedAlertId != null) expanded = true }
    var editing by remember { mutableStateOf<CookAlertRule?>(null) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    if (configuredOnly == true && alerts.isEmpty() || configuredOnly == false && alerts.isNotEmpty()) return
    val activeCount = alerts.count { CookAlertEngine.decode(it.payload).second.active }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (alerts.isEmpty()) "Add cook alerts" else "Alerts · ${alerts.count { CookAlertEngine.decode(it.payload).first.enabled }} enabled" + if (activeCount > 0) " · $activeCount need attention" else "") }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }) {
    Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TurnInBannerText("Cook alerts", style = PitTechTextStyles.bannerTitle)
        Text("Manual temperatures trigger checks when logged. Background sensor alerts need an attached grill with timed recording.")
        if (!CookAlertNotifications.canNotify(context)) OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Enable alert notifications") }
        // PL-29 rider (#172): test feedback stays in-app — no real
        // HIGH-importance notification is posted for a test.
        TextButton(onClick = { viewModel.showNotice("Test alert — alert feedback appears here in the app. Real alerts post a notification when they fire.") }) { Text("Send test alert") }
        TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
        // PL-03: the rule a notification tap named renders first, highlighted.
        val orderedAlerts = if (focusedAlertId == null) alerts else alerts.sortedByDescending { it.id == focusedAlertId }
        orderedAlerts.forEach { record ->
            val (rule, state) = CookAlertEngine.decode(record.payload)
            val focused = record.id == focusedAlertId
            Column(if (focused) Modifier.fillMaxWidth().background(pitTechTokens().surfaceRaised, RoundedCornerShape(12.dp)).padding(10.dp).testTag("alert-focus-${record.id}") else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // `d26` status cell language: the rule's name in the banner
                // face, its state as a colored status word beneath.
                TurnInBannerText(record.title)
                Text(
                    (if (!rule.enabled) "Off" else if (state.active) "Needs attention" else "Watching").uppercase(),
                    style = PitTechTextStyles.monoMeta,
                    color = if (!rule.enabled) pitTechTokens().inkMuted else if (state.active) pitTechTokens().attentionText else pitTechTokens().inkMuted,
                )
                if (state.acknowledged) Text(if (state.active) "Acknowledged · condition has not recovered" else "Acknowledged")
                FlowRow { TextButton(onClick = { editing = rule }) { Text("Edit") }; TextButton(onClick = { viewModel.changeCookAlert(record.id, "toggle") }) { Text(if (rule.enabled) "Disable" else "Enable") } }
                if (state.active) FlowRow { TextButton(onClick = { viewModel.changeCookAlert(record.id, "acknowledge") }) { Text("Acknowledge") }; TextButton(onClick = { viewModel.changeCookAlert(record.id, "snooze") }) { Text("Snooze 10 min") } }
                if (rule.type == "pit_range") TextButton(onClick = { viewModel.changeCookAlert(record.id, "lid") }) { Text("Lid open · suppress pit alarm 10 min") }
            }
        }
        if (data.cook.status != CookStatus.COMPLETED) {
            OutlinedButton(onClick = { editing = CookAlertRule(dishId = data.dishes.firstOrNull()?.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add alert") }
            data.targets.filter { it.targetType == "personal_finish" }.forEach { target -> TextButton(onClick = { editing = CookAlertRule(dishId = target.dishId, targetF = CookPlanEngine.fahrenheit(target.value, target.unit)) }) { Text("Monitor target · ${target.value} ${target.unit}") } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
    }
    }
    editing?.let { rule ->
        // PL-24 (#172): an existing rule drafts under its own id; a new
        // rule (a fresh id is minted at every open) drafts under the
        // cook's single "new" slot.
        val isExisting = alerts.any { record -> runCatching { CookAlertEngine.decode(record.payload).first.id }.getOrNull() == rule.id }
        AlertEditor(rule, data, busy, error, { editing = null }, draftKey = if (isExisting) "${data.cook.id}:${rule.id}" else "${data.cook.id}:new") { title, saved, timed -> viewModel.saveCookAlert(data.cook.id, title, saved, timed) { if (it) editing = null } }
    }
}

@Composable
private fun AlertEditor(initial: CookAlertRule, data: CookDetailData, busy: Boolean, saveError: String?, onClose: () -> Unit, draftKey: String, onSave: (String, CookAlertRule, Boolean) -> Unit) {
    // PL-24 (#172): autosave + resume. The draft codec does not validate
    // (CookAlertEngine.encode would reject a half-typed rule).
    val preferredUnit = preferredInputTemperatureUnit()
    val draftStore = rememberEditorDraftStore()
    val fullDraftKey = EditorDraftKeys.alert(draftKey)
    val initialDraft = remember(initial) {
        AlertEditorDraft(
            rule = initial, title = CookAlertEngine.types.getValue(initial.type), tempUnit = preferredUnit,
            target = initial.targetF?.let { temperatureText(it, preferredUnit) }.orEmpty(),
            low = temperatureText(initial.lowF ?: 200.0, preferredUnit),
            high = temperatureText(initial.highF ?: 300.0, preferredUnit),
            dwell = (initial.dwellMillis / 60_000).toString(),
            missing = (initial.missingMillis / 60_000).toString(),
            timed = false,
        )
    }
    val storedDraft = remember(initial) { draftStore.load(fullDraftKey)?.let(AlertEditorDraftCodec::decode)?.takeIf { it != initialDraft } }
    var openedDraft by remember(initial) { mutableStateOf(storedDraft ?: initialDraft) }
    var draftRestored by remember(initial) { mutableStateOf(storedDraft != null) }
    var rule by remember(initial) { mutableStateOf(openedDraft.rule) }
    var title by remember { mutableStateOf(openedDraft.title) }
    var tempUnit by remember { mutableStateOf(openedDraft.tempUnit) }
    var target by remember { mutableStateOf(openedDraft.target) }
    var low by remember { mutableStateOf(openedDraft.low) }
    var high by remember { mutableStateOf(openedDraft.high) }
    var dwell by remember { mutableStateOf(openedDraft.dwell) }
    var missing by remember { mutableStateOf(openedDraft.missing) }
    var timed by remember { mutableStateOf(openedDraft.timed) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rule, title, tempUnit, target, low, high, dwell, missing, timed) {
        val current = AlertEditorDraft(rule, title, tempUnit, target, low, high, dwell, missing, timed)
        if (current != openedDraft) draftStore.save(fullDraftKey, AlertEditorDraftCodec.encode(current))
        else draftStore.clear(fullDraftKey)
    }
    val loggingOnly = data.recording?.samplingMode == "on_log"
    // PL-22 (DD-6): the alert editor is a multi-field editor — full screen.
    FullScreenSurface(onDismiss = onClose, title = "Cook alert", actions = {
        TextButton(onClick = onClose) { Text("Cancel") }
        TextButton(enabled = !busy, onClick = {
            runCatching {
                require(!loggingOnly || timed || rule.type == "target") { "Choose timed monitoring for this sensor alert." }
                val saved = rule.copy(targetF = target.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, lowF = low.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, highF = high.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, dwellMillis = (dwell.toLongOrNull() ?: throw IllegalArgumentException("Enter whole dwell minutes.")) * 60_000, missingMillis = (missing.toLongOrNull() ?: throw IllegalArgumentException("Enter whole missing minutes.")) * 60_000)
                CookAlertEngine.validate(saved); require(title.isNotBlank()); draftStore.clear(fullDraftKey); onSave(title, saved, timed)
            }.onFailure { error = ErrorCopy.failureText(it, "That alert couldn't be saved. Your other alerts are unchanged — check the values and try again.") }
        }) { Text("Save alert") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (draftRestored) {
                ResumeDraftBanner(onDiscard = {
                    draftStore.clear(fullDraftKey)
                    draftRestored = false
                    openedDraft = initialDraft
                    rule = initialDraft.rule; title = initialDraft.title; tempUnit = initialDraft.tempUnit
                    target = initialDraft.target; low = initialDraft.low; high = initialDraft.high
                    dwell = initialDraft.dwell; missing = initialDraft.missing; timed = initialDraft.timed
                })
            }
            OutlinedTextField(title, { title = it }, label = { Text("Alert name") }, modifier = Modifier.fillMaxWidth())
            ChoiceField("Watch", CookAlertEngine.types.getValue(rule.type), CookAlertEngine.types.values.toList()) { label -> rule = rule.copy(type = CookAlertEngine.types.entries.first { it.value == label }.key, dishId = if (label == "Food target") rule.dishId else null) }
            if (rule.type in setOf("target", "pit_range")) ChoiceField("Temperature unit", tempUnit, listOf("°F", "°C")) { unit ->
                target = target.toDoubleOrNull()?.let { temperatureText(CookPlanEngine.fahrenheit(it, tempUnit), unit) }.orEmpty()
                low = low.toDoubleOrNull()?.let { temperatureText(CookPlanEngine.fahrenheit(it, tempUnit), unit) }.orEmpty()
                high = high.toDoubleOrNull()?.let { temperatureText(CookPlanEngine.fahrenheit(it, tempUnit), unit) }.orEmpty()
                tempUnit = unit
            }
            if (rule.type == "target") {
                val dishLabels = data.dishes.mapIndexed { i, d -> "${i + 1}. ${d.name}" }
                ChoiceField("Dish", data.dishes.indexOfFirst { it.id == rule.dishId }.takeIf { it >= 0 }?.let { dishLabels[it] } ?: "Whole cook", listOf("Whole cook") + dishLabels) { label -> rule = rule.copy(dishId = dishLabels.indexOf(label).takeIf { it >= 0 }?.let { data.dishes[it].id }) }
                ChoiceField("During", rule.stage.replaceFirstChar { it.uppercase() }, listOf("Cooking", "Resting", "Holding")) { rule = rule.copy(stage = it.lowercase()) }
                OutlinedTextField(target, { target = it }, label = { Text("Target $tempUnit") }, modifier = Modifier.fillMaxWidth())
                Text("Reaching the target asks you to check. It does not mark the food done.")
            }
            if (rule.type == "pit_range") {
                OutlinedTextField(low, { low = it }, label = { Text("Low $tempUnit") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(high, { high = it }, label = { Text("High $tempUnit") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dwell, { dwell = it }, label = { Text("Sustained for minutes") }, modifier = Modifier.fillMaxWidth())
            }
            if (rule.type in setOf("target", "pit_range")) ChoiceField("Probe", rule.probeName.ifBlank { "Any matching probe" }, listOf("Any matching probe") + (data.probes.map { it.name } + data.readings.map { it.probeName }).distinct()) { rule = rule.copy(probeName = it.takeUnless { it == "Any matching probe" }.orEmpty()) }
            if (rule.type == "missing") OutlinedTextField(missing, { missing = it }, label = { Text("No readings for minutes") }, modifier = Modifier.fillMaxWidth())
            if (rule.type in setOf("missing", "offline") && data.recording == null) Text("Attach a grill from Live before this connection alert can run.")
            if (loggingOnly) {
                Text("Logging-only collection cannot watch the grill between entries.")
                Row { Checkbox(timed, { timed = it }); Text("Switch this cook to timed monitoring", Modifier.padding(top = 10.dp)) }
            }
            Text("Up to 3 alerts, at least 15 minutes apart. Acknowledge stops repeats until recovery; snooze leaves the condition visible.", style = MaterialTheme.typography.bodySmall)
            (error ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
