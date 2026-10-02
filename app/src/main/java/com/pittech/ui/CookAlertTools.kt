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
internal fun CookAlertTools(data: CookDetailData, viewModel: CooksViewModel) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val alerts = records.filter { it.kind == "alert" && it.cookId == data.cook.id }
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CookAlertRule?>(null) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Alerts · ${alerts.count { CookAlertEngine.decode(it.payload).first.enabled }} enabled") }
    if (expanded) {
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
            Row { TextButton(onClick = { editing = rule }) { Text("Edit") }; TextButton(onClick = { viewModel.changeCookAlert(record.id, "toggle") }) { Text(if (rule.enabled) "Disable" else "Enable") } }
            if (state.active) Row { TextButton(onClick = { viewModel.changeCookAlert(record.id, "acknowledge") }) { Text("Acknowledge") }; TextButton(onClick = { viewModel.changeCookAlert(record.id, "snooze") }) { Text("Snooze 10 min") } }
            if (rule.type == "pit_range") TextButton(onClick = { viewModel.changeCookAlert(record.id, "lid") }) { Text("Lid open · suppress pit alarm 10 min") }
        }
        if (data.cook.status != CookStatus.COMPLETED) {
            OutlinedButton(onClick = { editing = CookAlertRule(dishId = data.dishes.firstOrNull()?.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add alert") }
            data.targets.filter { it.targetType == "personal_finish" }.forEach { target -> TextButton(onClick = { editing = CookAlertRule(dishId = target.dishId, targetF = CookPlanEngine.fahrenheit(target.value, target.unit)) }) { Text("Monitor target · ${target.value} ${target.unit}") } }
        }
    }
    editing?.let { rule -> AlertEditor(rule, data, { editing = null }) { title, saved, timed -> viewModel.saveCookAlert(data.cook.id, title, saved, timed); editing = null } }
}

@Composable
private fun AlertEditor(initial: CookAlertRule, data: CookDetailData, onClose: () -> Unit, onSave: (String, CookAlertRule, Boolean) -> Unit) {
    var rule by remember(initial) { mutableStateOf(initial) }
    var title by remember { mutableStateOf(CookAlertEngine.types.getValue(initial.type)) }
    var target by remember { mutableStateOf(initial.targetF?.toString().orEmpty()) }
    var low by remember { mutableStateOf(initial.lowF?.toString() ?: "200") }
    var high by remember { mutableStateOf(initial.highF?.toString() ?: "300") }
    var dwell by remember { mutableStateOf((initial.dwellMillis / 60_000).toString()) }
    var missing by remember { mutableStateOf((initial.missingMillis / 60_000).toString()) }
    var timed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loggingOnly = data.recording?.samplingMode == "on_log"
    AlertDialog(onDismissRequest = onClose, title = { Text("Cook alert") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Alert name") }, modifier = Modifier.fillMaxWidth())
            ChoiceField("Watch", CookAlertEngine.types.getValue(rule.type), CookAlertEngine.types.values.toList()) { label -> rule = rule.copy(type = CookAlertEngine.types.entries.first { it.value == label }.key, dishId = if (label == "Food target") rule.dishId else null) }
            if (rule.type == "target") {
                ChoiceField("Dish", data.dishes.firstOrNull { it.id == rule.dishId }?.name ?: "Whole cook", listOf("Whole cook") + data.dishes.map { it.name }) { name -> rule = rule.copy(dishId = data.dishes.firstOrNull { it.name == name }?.id) }
                ChoiceField("During", rule.stage.replaceFirstChar { it.uppercase() }, listOf("Cooking", "Resting", "Holding")) { rule = rule.copy(stage = it.lowercase()) }
                OutlinedTextField(target, { target = it }, label = { Text("Target °F") }, modifier = Modifier.fillMaxWidth())
                Text("Reaching the target asks you to check. It does not mark the food done.")
            }
            if (rule.type == "pit_range") {
                OutlinedTextField(low, { low = it }, label = { Text("Low °F") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(high, { high = it }, label = { Text("High °F") }, modifier = Modifier.fillMaxWidth())
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            require(!loggingOnly || timed || rule.type == "target") { "Choose timed monitoring for this sensor alert." }
            val saved = rule.copy(targetF = target.toDoubleOrNull(), lowF = low.toDoubleOrNull(), highF = high.toDoubleOrNull(), dwellMillis = (dwell.toLongOrNull() ?: throw IllegalArgumentException("Enter whole dwell minutes.")) * 60_000, missingMillis = (missing.toLongOrNull() ?: throw IllegalArgumentException("Enter whole missing minutes.")) * 60_000)
            CookAlertEngine.validate(saved); require(title.isNotBlank()); onSave(title, saved, timed)
        }.onFailure { error = it.message }
    }) { Text("Save alert") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
