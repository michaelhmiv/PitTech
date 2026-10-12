@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class ReminderEdit(val rule: ReminderRule? = null, val dishId: String? = null, val title: String = "", val trigger: ReminderTrigger? = null, val revisionId: String? = null, val milestoneId: String? = null)

@Composable internal fun UnifiedReminderTools(data: CookDetailData, viewModel: CooksViewModel, initialDishId: String? = null) {
    val allRules by viewModel.reminders.observeRules().collectAsStateWithLifecycle(emptyList())
    val occurrences by viewModel.reminders.observeOccurrences().collectAsStateWithLifecycle(emptyList())
    val sources by viewModel.recipes.observeSources().collectAsStateWithLifecycle(emptyList())
    val revisions by viewModel.recipes.observeRevisions().collectAsStateWithLifecycle(emptyList())
    val rules = allRules.filter { it.cookId == data.cook.id && (initialDishId == null || it.dishId == initialDishId) }
    var show by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<ReminderEdit?>(null) }
    var confirm by remember { mutableStateOf<ReminderRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = e.message } finally { busy = false } } }
    OutlinedButton({ show = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (initialDishId == null) "meal-reminders" else "dish-reminders-$initialDishId")) { Text("Reminders${rules.count { it.enabled }.takeIf { it > 0 }?.let { " · $it enabled" }.orEmpty()}") }
    if (show) ModalBottomSheet(onDismissRequest = { show = false }) {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reminders", style = MaterialTheme.typography.titleLarge)
            Text("Set a check for this meal or one dish. Dismiss, skip and snooze leave the cooking timeline unchanged.")
            if (data.cook.status != CookStatus.COMPLETED) Button({ editor = ReminderEdit(dishId = initialDishId) }, Modifier.testTag("reminder-rule-add")) { Text("Add reminder") }
            rules.forEach { rule ->
                val receipt = occurrences.firstOrNull { it.ruleId == rule.id && it.sequence == 0 }
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(rule.title, style = MaterialTheme.typography.titleMedium)
                    Text("${data.dishes.firstOrNull { it.id == rule.dishId }?.name ?: "Meal"} · ${if (!rule.enabled) rule.status else rule.status.replace('_', ' ')}")
                    receipt?.dueAtUtcMillis?.let { Text("Due ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}") }
                    if (rule.kind == "probe") Text(if (data.recording?.status == CookRecordingEntity.RECORDING && data.recording.samplingMode != "on_log") "Uses accepted readings from the selected channel" else "Monitoring unavailable · manual or on-log readings are evaluated only when recorded")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ act { viewModel.changeReminderRule(rule.id, "toggle") } }, enabled = !busy && data.cook.status != CookStatus.COMPLETED) { Text(if (rule.enabled) "Disable" else "Enable") }
                        TextButton({ editor = ReminderEdit(rule = rule, trigger = ReminderTriggerCodec.decode(rule.triggerConfig)) }, enabled = !busy && data.cook.status != CookStatus.COMPLETED) { Text("Edit") }
                        if (rule.enabled) {
                            TextButton({ act { viewModel.changeReminderRule(rule.id, "snooze") } }, enabled = !busy) { Text("Snooze 10 min") }
                            TextButton({ act { viewModel.changeReminderRule(rule.id, "skip") } }, enabled = !busy) { Text("Skip") }
                            TextButton({ act { viewModel.changeReminderRule(rule.id, "complete") } }, enabled = !busy) { Text("Dismiss") }
                            TextButton({ confirm = rule }, enabled = !busy) { Text("Record an action…") }
                        }
                        TextButton({ act { viewModel.changeReminderRule(rule.id, "remove") } }, enabled = !busy) { Text("Remove") }
                    }
                } }
            }
            if (data.cook.status != CookStatus.COMPLETED) {
                Text("Previous cook suggestions", style = MaterialTheme.typography.titleMedium)
                data.dishes.filter { initialDishId == null || it.id == initialDishId }.forEach { dish ->
                    val source = sources.firstOrNull { it.dishId == dish.id }
                    val revision = revisions.firstOrNull { it.id == source?.recipeRevisionId }
                    val snapshot = revision?.let { RecipeSnapshotCodec.decode(it.snapshotJson) }
                    snapshot?.observations?.filter { it.action in CookPhases.phaseActions && it.action != "food_on" && it.offsetMillis != null }?.forEach { observation ->
                        TextButton({ editor = ReminderEdit(dishId = dish.id, title = "Last time: ${observation.title}. Check ${dish.name}.", trigger = ReminderTrigger.Historical("food_on", observation.offsetMillis!!), revisionId = revision?.id, milestoneId = observation.sourceEventId) }) { Text("${dish.name} · last time ${observation.title} at ${RecipeReferenceTimeline.relativeLabel(observation.offsetMillis)} · enable…") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
    editor?.let { request -> ReminderRuleEditor(data, request, busy, error, { editor = null }, { title, dishId, trigger -> act {
        viewModel.saveReminderRule(data.cook.id, dishId, title, trigger, request.rule?.id, request.revisionId ?: request.rule?.sourceRecipeRevisionId, request.milestoneId ?: request.rule?.sourceMilestoneId); editor = null
    } }) }
    confirm?.let { rule ->
        var action by remember(rule.id) { mutableStateOf("wrap") }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Record what actually happened") }, text = { Column {
            Text("Only confirm an action you performed. This creates one current-cook entry.")
            ChoiceField("Actual action", PlaybookCodec.actions[action] ?: action, CookPhases.phaseActions.map { PlaybookCodec.actions[it] ?: it }) { label -> action = PlaybookCodec.actions.entries.first { it.value == label }.key }
        } }, confirmButton = { TextButton({ act { viewModel.confirmReminderAction(rule.id, action); confirm = null } }, enabled = !busy) { Text("Record action") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

@Composable private fun ReminderRuleEditor(data: CookDetailData, request: ReminderEdit, busy: Boolean, error: String?, onClose: () -> Unit, onSave: (String, String?, ReminderTrigger) -> Unit) {
    val initial = request.trigger
    var title by remember(request) { mutableStateOf(request.rule?.title ?: request.title.ifBlank { "Check the food" }) }
    var dishId by remember(request) { mutableStateOf(request.rule?.dishId ?: request.dishId) }
    var kind by remember(request) { mutableStateOf(initial?.let(ReminderTriggerCodec::kind) ?: "duration") }
    var minutes by remember(request) { mutableStateOf(when (initial) { is ReminderTrigger.Duration -> (initial.delayMillis / 60_000.0).toString(); is ReminderTrigger.Action -> (initial.offsetMillis / 60_000.0).toString(); is ReminderTrigger.Historical -> (initial.offsetMillis / 60_000.0).toString(); else -> "10" }) }
    var clock by remember(request) { mutableStateOf(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli((initial as? ReminderTrigger.Clock)?.at ?: System.currentTimeMillis() + 600_000), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) }
    var action by remember(request) { mutableStateOf((initial as? ReminderTrigger.Action)?.action ?: (initial as? ReminderTrigger.Historical)?.anchorAction ?: "food_on") }
    var eventId by remember(request) { mutableStateOf((initial as? ReminderTrigger.Action)?.eventId) }
    val channels = data.readings.filter { it.measurementType in setOf("food", "food_probe", "internal_temperature", "pit", "pit_ambient", "ambient_temperature") && (dishId == null || it.dishId == dishId) }.groupBy { it.channelIdentity() }.mapValues { it.value.maxBy { r -> r.measuredAtUtcMillis } }
    var seriesId by remember(request) { mutableStateOf((initial as? ReminderTrigger.Probe)?.seriesId ?: channels.keys.firstOrNull().orEmpty()) }
    var direction by remember(request) { mutableStateOf((initial as? ReminderTrigger.Probe)?.direction ?: "above") }
    var threshold by remember(request) { mutableStateOf(((initial as? ReminderTrigger.Probe)?.thresholdF ?: 165.0).toString()) }
    var unit by remember(request) { mutableStateOf("°F") }
    var sustain by remember(request) { mutableStateOf(((initial as? ReminderTrigger.Probe)?.sustainMillis?.div(60_000.0) ?: 0.0).toString()) }
    var repeat by remember(request) { mutableStateOf((initial as? ReminderTrigger.Probe)?.repeatMillis != null) }
    fun trigger(): ReminderTrigger? = runCatching {
        fun duration() = (minutes.toDouble() * 60_000).also { require(it.isFinite()) }.toLong()
        when (kind) {
            "duration" -> ReminderTrigger.Duration((initial as? ReminderTrigger.Duration)?.startedAt ?: System.currentTimeMillis(), duration())
            "clock" -> ReminderTrigger.Clock(LocalDateTime.parse(clock, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            "action" -> ReminderTrigger.Action(action, duration(), eventId)
            "historical" -> ReminderTrigger.Historical(action, duration())
            "probe" -> channels[seriesId]?.let { reading -> ReminderTrigger.Probe(seriesId, reading.probeId, reading.measurementType, direction, CookPlanEngine.fahrenheit(threshold.toDouble(), unit), (sustain.toDouble() * 60_000).also { require(it.isFinite()) }.toLong(), if (repeat) 900_000 else null) } ?: error("Choose a channel")
            else -> error("Choose a type")
        }.also { ReminderTriggerCodec.encode(it) }
    }.getOrNull()
    val labels = linkedMapOf("duration" to "After a duration", "clock" to "At a clock time", "probe" to "Probe temperature", "action" to "After an actual action", "historical" to "Previous cook milestone")
    AlertDialog(onDismissRequest = onClose, title = { Text("Reminder") }, text = { Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("reminder-rule-title"))
        ChoiceField("Scope", data.dishes.firstOrNull { it.id == dishId }?.name ?: "Meal", listOf("Meal") + data.dishes.mapIndexed { i, d -> "${i + 1}. ${d.name}" }) { label -> dishId = if (label == "Meal") null else data.dishes[label.substringBefore('.').toInt() - 1].id }
        ChoiceField("Type", labels[kind] ?: "Choose", labels.filterKeys { it != "historical" || initial is ReminderTrigger.Historical }.values.toList()) { kind = labels.entries.first { e -> e.value == it }.key }
        when (kind) {
            "clock" -> OutlinedTextField(clock, { clock = it }, label = { Text("Local date and time · yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
            "probe" -> {
                val channelLabels = channels.mapValues { (id, r) -> "${r.probeName} · ${r.measurementType.replace('_', ' ')} · ${id.hashCode().toUInt().toString(16).takeLast(4)}" }
                ChoiceField("Channel", channelLabels[seriesId] ?: "Choose channel", channelLabels.values.toList()) { label -> seriesId = channelLabels.entries.first { it.value == label }.key }
                ChoiceField("Crossing", direction, listOf("above", "below")) { direction = it }
                OutlinedTextField(threshold, { threshold = it }, label = { Text("Threshold") })
                ChoiceField("Unit", unit, listOf("°F", "°C")) { new -> threshold.toDoubleOrNull()?.let { threshold = com.pittech.devices.CookTelemetryPolicy.convert(it, unit, new).toString() }; unit = new }
                OutlinedTextField(sustain, { sustain = it }, label = { Text("Sustained minutes (0 = immediate)") })
                Row { Checkbox(repeat, { repeat = it }); Text("Repeat every 15 min while still crossed") }
                Text("Manual/on-log readings do not provide sustained monitoring.", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                OutlinedTextField(minutes, { minutes = it }, label = { Text("Minutes${if (kind in setOf("action", "historical")) " after anchor (negative = earlier)" else ""}") }, modifier = Modifier.testTag("reminder-rule-minutes"))
                if (kind in setOf("action", "historical")) {
                    val actions = if (kind == "historical") CookPhases.phaseActions else CookNoteMetadata.actions
                    ChoiceField("Actual anchor", PlaybookCodec.actions[action] ?: action, actions.map { PlaybookCodec.actions[it] ?: it }) { label -> action = PlaybookCodec.actions.entries.first { it.value == label }.key; eventId = null }
                    if (kind == "action") {
                        val choices = notePlanEvents(data.events).filter { (it.dishId == dishId || it.dishId == null) && it.action == action }.distinctBy { it.id }
                        val names = choices.map { "${PlaybookCodec.actions[it.action]} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.at))} · ${it.id.takeLast(5)}" }
                        ChoiceField("Action occurrence", choices.indexOfFirst { it.id == eventId }.takeIf { it >= 0 }?.let { names[it] } ?: "First occurrence (wait if unrecorded)", listOf("First occurrence (wait if unrecorded)") + names) { label -> eventId = if (label in names) choices[names.indexOf(label)].id else null }
                    }
                    Text("Waits for the new attempt's actual action; correcting that timestamp updates future reminders.")
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton({ trigger()?.let { onSave(title, dishId, it) } }, enabled = !busy && title.isNotBlank() && trigger() != null, modifier = Modifier.testTag("reminder-rule-save")) { Text("Save reminder") } }, dismissButton = { TextButton(onClose, enabled = !busy) { Text("Cancel") } })
}
