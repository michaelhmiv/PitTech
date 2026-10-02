@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pittech.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.CookAlertNotifications
import com.pittech.data.*
import com.pittech.domain.*

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CookAlertTools(data: CookDetailData, viewModel: CooksViewModel, configuredOnly: Boolean? = null) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val alerts = records.filter { it.kind == "alert" && it.cookId == data.cook.id }
    var expanded by remember { mutableStateOf(false) }
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
        Text("Cook alerts", style = MaterialTheme.typography.titleLarge)
        Text("Manual temperatures trigger checks when logged. Background sensor alerts need an attached grill with timed recording.")
        if (!CookAlertNotifications.canNotify(context)) OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Enable alert notifications") }
        TextButton(onClick = { CookAlertNotifications.test(context) }) { Text("Send test alert") }
        TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
        alerts.forEach { record ->
            val (rule, state) = CookAlertEngine.decode(record.payload)
            Text("${record.title} · ${if (!rule.enabled) "off" else if (state.active) "needs attention" else "watching"}", style = MaterialTheme.typography.titleSmall)
            if (state.acknowledged && state.active) Text("Acknowledged · condition has not recovered")
            FlowRow { TextButton(onClick = { editing = rule }) { Text("Edit") }; TextButton(onClick = { viewModel.changeCookAlert(record.id, "toggle") }) { Text(if (rule.enabled) "Disable" else "Enable") } }
            if (state.active) FlowRow { TextButton(onClick = { viewModel.changeCookAlert(record.id, "acknowledge") }) { Text("Acknowledge") }; TextButton(onClick = { viewModel.changeCookAlert(record.id, "snooze") }) { Text("Snooze 10 min") } }
            if (rule.type == "pit_range") TextButton(onClick = { viewModel.changeCookAlert(record.id, "lid") }) { Text("Lid open · suppress pit alarm 10 min") }
        }
        if (data.cook.status != CookStatus.COMPLETED) {
            OutlinedButton(onClick = { editing = CookAlertRule(dishId = data.dishes.firstOrNull()?.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add alert") }
            data.targets.filter { it.targetType == "personal_finish" }.forEach { target -> TextButton(onClick = { editing = CookAlertRule(dishId = target.dishId, targetF = CookPlanEngine.fahrenheit(target.value, target.unit)) }) { Text("Monitor target · ${target.value} ${target.unit}") } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
    }
    }
    editing?.let { rule -> AlertEditor(rule, data, busy, error, { editing = null }) { title, saved, timed -> viewModel.saveCookAlert(data.cook.id, title, saved, timed) { if (it) editing = null } } }
}

@Composable
private fun AlertEditor(initial: CookAlertRule, data: CookDetailData, busy: Boolean, saveError: String?, onClose: () -> Unit, onSave: (String, CookAlertRule, Boolean) -> Unit) {
    var rule by remember(initial) { mutableStateOf(initial) }
    var title by remember { mutableStateOf(CookAlertEngine.types.getValue(initial.type)) }
    val preferredUnit = preferredInputTemperatureUnit()
    var tempUnit by remember { mutableStateOf(preferredUnit) }
    var target by remember { mutableStateOf(initial.targetF?.let { temperatureText(it, tempUnit) }.orEmpty()) }
    var low by remember { mutableStateOf(temperatureText(initial.lowF ?: 200.0, tempUnit)) }
    var high by remember { mutableStateOf(temperatureText(initial.highF ?: 300.0, tempUnit)) }
    var dwell by remember { mutableStateOf((initial.dwellMillis / 60_000).toString()) }
    var missing by remember { mutableStateOf((initial.missingMillis / 60_000).toString()) }
    var timed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loggingOnly = data.recording?.samplingMode == "on_log"
    AlertDialog(onDismissRequest = onClose, title = { Text("Cook alert") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        runCatching {
            require(!loggingOnly || timed || rule.type == "target") { "Choose timed monitoring for this sensor alert." }
            val saved = rule.copy(targetF = target.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, lowF = low.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, highF = high.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, dwellMillis = (dwell.toLongOrNull() ?: throw IllegalArgumentException("Enter whole dwell minutes.")) * 60_000, missingMillis = (missing.toLongOrNull() ?: throw IllegalArgumentException("Enter whole missing minutes.")) * 60_000)
            CookAlertEngine.validate(saved); require(title.isNotBlank()); onSave(title, saved, timed)
        }.onFailure { error = it.message }
    }) { Text("Save alert") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
