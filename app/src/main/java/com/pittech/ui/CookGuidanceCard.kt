package com.pittech.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.CooksViewModel
import com.pittech.CookGuidanceNotifications
import com.pittech.PitTechApplication
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookGuidanceCard(data: CookDetailData, record: CompanionRecord?, viewModel: CooksViewModel) {
    var show by remember(data.cook.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaybookStep?>(null) }
    var skipping by remember { mutableStateOf<EvaluatedStep?>(null) }
    var removing by remember { mutableStateOf<EvaluatedStep?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(data.cook.id) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val plan = remember(record?.payload) { record?.let { CookPlanEngine.decode(it.payload) } }
    val events = remember(data.events) { data.events.map { PlanEvent(it.id, PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) } }
    val readings = remember(data.readings) { data.readings.map { PlanReading(it.dishId, CookPlanEngine.fahrenheit(it.value, it.unit), it.measuredAtUtcMillis, it.qualityStatus == "valid", it.measurementType) } }
    val evaluated = plan?.let { CookPlanEngine.evaluate(it, events, readings, now) }.orEmpty()
    val next = evaluated.firstOrNull { it.ready } ?: evaluated.filter { it.progress.status == "pending" && it.waitingFor == null }.minByOrNull { it.dueAt ?: Long.MAX_VALUE }
    if (record == null) {
        TextButton(onClick = { viewModel.beginGuidance(data.cook.id); show = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add cook guidance") }
    } else Card(Modifier.fillMaxWidth().testTag("next-action-card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (plan?.paused == true) "Guidance paused" else "Next action", style = MaterialTheme.typography.labelLarge)
            Text(next?.step?.title ?: "${record.title} · view your plan", style = MaterialTheme.typography.titleMedium)
            next?.let { e ->
                Text(if (e.ready) "Ready to check" else e.dueAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: e.waitingFor ?: "When ready")
                if (e.step.instructions.isNotBlank()) Text(e.step.instructions)
                if (e.ready) Button(onClick = { if (e.step.action == "remove" && e.dishId != null) removing = e else viewModel.completeGuidanceStep(data.cook.id, e) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("guidance-done")) { Text("Done") }
            }
            TextButton(onClick = { show = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("view-plan")) { Text("View plan") }
        }
    }
    editing?.let { s -> StepEditor(s, plan?.book?.draft?.dishes ?: data.dishes.map { DishDraft(it.name, it.foodType) }, { editing = null }) { viewModel.updateGuidanceStep(data.cook.id, it); editing = null } }
    removing?.let { e -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${data.dishes.firstOrNull { it.id == e.dishId }?.name.orEmpty()}?") }, text = { Text("Confirm when the food leaves the smoker. You can start its rest now.") }, confirmButton = { TextButton(onClick = { viewModel.completeGuidanceStep(data.cook.id, e, rest = true); removing = null }) { Text("Removed · start rest") } }, dismissButton = { TextButton(onClick = { viewModel.completeGuidanceStep(data.cook.id, e); removing = null }) { Text("Removed only") } }) }
    skipping?.let { e -> AlertDialog(onDismissRequest = { skipping = null }, title = { Text("Skip ${e.step.title}?") }, text = { Text("Skipping records no physical action. If later steps depend on this action, choose whether to use the current time as their planning anchor.") }, confirmButton = { TextButton(onClick = { viewModel.skipGuidance(data.cook.id, e.step.id, false); skipping = null }) { Text("Skip") } }, dismissButton = { TextButton(onClick = { viewModel.skipGuidance(data.cook.id, e.step.id, true); skipping = null }) { Text("Skip · use now for later steps") } }) }
    if (show && plan != null) ModalBottomSheet(onDismissRequest = { show = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(plan.book.name, style = MaterialTheme.typography.titleLarge)
            Text("Checks use actual action times. Only Done or a logged action confirms what happened.")
            OutlinedButton(onClick = { viewModel.pauseGuidance(data.cook.id, !plan.paused) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (plan.paused) "Resume guidance" else "Pause guidance") }
            Text("Guidance pause leaves recording and physical rest/hold timers running.", style = MaterialTheme.typography.bodySmall)
            if (!CookGuidanceNotifications.canNotify(context)) OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Enable cook reminders") }
            TextButton(onClick = {
                context.getSharedPreferences("pittech-preferences", 0).edit().putBoolean("precise-cook-timers", true).apply()
                if (Build.VERSION.SDK_INT >= 31 && !CookGuidanceNotifications.preciseAvailable(context)) runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
            }) { Text(if (CookGuidanceNotifications.preciseAvailable(context)) "Use precise timers" else "Allow precise timers (optional)") }
            if (!CookGuidanceNotifications.preciseAvailable(context)) Text("Android may delay reminders. Your plan stays available here.", style = MaterialTheme.typography.bodySmall)
            data.dishes.forEach { dish ->
                val stage = CookPlanEngine.stage(events, dish.id)
                Text("${dish.name} · $stage", style = MaterialTheme.typography.titleMedium)
                ChoiceField("Change stage after acting", "Choose an action", when (stage) { "prep" -> listOf("Food on"); "removed", "resting" -> listOf("Start rest", "Start hold", "Ready to serve"); "holding" -> listOf("Ready to serve"); "done" -> emptyList(); else -> listOf("Start rest", "Start hold", "Ready to serve") }) { label -> viewModel.changeDishStage(data.cook.id, dish.id, PlaybookCodec.actions.entries.first { it.value == label }.key) }
            }
            evaluated.forEach { e ->
                HorizontalDivider()
                Text(e.step.title, style = MaterialTheme.typography.titleMedium)
                e.dishId?.let { id -> Text(data.dishes.firstOrNull { it.id == id }?.name.orEmpty()) }
                Text(if (e.progress.status != "pending") e.progress.status else e.waitingFor ?: stepDescription(e.step))
                if (e.step.instructions.isNotBlank()) Text(e.step.instructions)
                if (e.progress.status == "pending") {
                    OutlinedButton(onClick = { if (e.step.action == "remove" && e.dishId != null) removing = e else viewModel.completeGuidanceStep(data.cook.id, e) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done · ${e.step.title}") }
                    Row { TextButton(onClick = { viewModel.snoozeGuidance(data.cook.id, e) }) { Text("Snooze 10 min") }; TextButton(onClick = { skipping = e }) { Text("Skip") }; TextButton(onClick = { editing = e.step }) { Text("Edit") } }
                }
            }
            OutlinedButton(onClick = { editing = PlaybookStep() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add step") }
            ReferencePhotos(plan.playbookId, context.applicationContext as PitTechApplication)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReferencePhotos(id: String?, app: PitTechApplication) {
    var photos by remember(id) { mutableStateOf(emptyList<CompanionPhoto>()) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(id) { photos = id?.let { app.companionRepository.photos(it) }.orEmpty() }
    if (photos.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }) { Text("Reference photos · ${photos.size}") }
        if (expanded) photos.forEach { photo ->
            val bitmap = remember(photo.relativePath) { android.graphics.BitmapFactory.decodeFile(java.io.File(app.filesDir, photo.relativePath).absolutePath)?.asImageBitmap() }
            bitmap?.let { Image(it, contentDescription = photo.caption, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
            Text(photo.caption)
        }
    }
}
