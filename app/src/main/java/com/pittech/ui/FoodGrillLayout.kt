package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.*
import com.pittech.domain.*
import com.pittech.devices.CookTelemetryPolicy
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FoodGrillLayout(data: CookDetailData, selectedFood: String?, displayUnit: String,
    onSelect: (String?) -> Unit, onLog: (String, String, String?) -> Unit, onRecordAction: (DishEntity, String) -> Unit = { dish, action -> onLog(dish.id, "action", action) },
    onAdd: () -> Unit, onEdit: (DishEntity) -> Unit, onIngredients: (DishEntity) -> Unit, onProbe: (String) -> Unit, reminders: List<ReminderRule> = emptyList(), occurrences: List<ReminderOccurrence> = emptyList(), selectedTools: @Composable (DishEntity) -> Unit = {}) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.pittech.PitTechApplication
    val telemetryProfiles by app.telemetryRuntime.store.profiles.collectAsStateWithLifecycle()
    val telemetryStates by app.telemetryRuntime.states.collectAsStateWithLifecycle()
    val liveTelemetryProbes = telemetryProfiles.filter { it.monitoring && it.cookId == data.cook.id && data.cook.status == CookStatus.ACTIVE }
        .flatMap { profile -> profile.sources.filter { it.deviceId in profile.selectedDeviceIds &&
            telemetryStates[profile.id + ":" + it.deviceId]?.startsWith("Connected") == true }
            .flatMap { source -> source.channels.filter { telemetryStates[profile.id + ":" + source.deviceId + ":" + it.id] == "Connected" }.map { TelemetryRecordingRepository(app.database).channelKey(data.cook.id, source, it.id) } } }.toSet()
    LaunchedEffect(data.cook.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val end = data.cook.endedAtUtcMillis ?: now
    val phases = FoodLivePresentation.phases(data, end)
    val phaseByFood = phases.associateBy { it.dishId }
    val legacy = data.lifecycle?.timingMode != "explicit_phases"
    val groups = listOf(
        (if (legacy) "Foods in this cook" else "Heating") to data.dishes.filter {
            legacy || phaseByFood[it.id]?.phase in setOf("cooking", "wrapped")
        },
        "Preparing" to data.dishes.filter { !legacy && phaseByFood[it.id]?.phase == "prep" },
        "Resting and finished" to data.dishes.filter { !legacy && phaseByFood[it.id]?.phase in setOf("removed", "resting", "holding", "done") },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Foods in this meal", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd, modifier = Modifier.testTag("live-add-dish")) { Text("+ Add food") }
    }
    if (data.dishes.isEmpty()) Text("Add the food you want to track.", style = MaterialTheme.typography.bodyMedium)
    val grate = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    groups.filter { it.second.isNotEmpty() }.forEach { (label, foods) ->
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().drawBehind {
                if (label == "Heating" || label == "Foods in this cook") {
                    var y = 12.dp.toPx()
                    while (y < size.height) { drawLine(grate, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()); y += 24.dp.toPx() }
                }
            }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                foods.forEach { dish ->
                    val phase = phaseByFood[dish.id]
                    val temperatures = FoodLivePresentation.temperatures(dish.id, data.probes, data.assignments, data.readings, data.recording, now, liveTelemetryProbes)
                    val last = data.events.filter { CookFeedPresentation.matchesFood(it, dish.id) &&
                        (it.dishId == dish.id || dish.id in CookNoteMetadata.decode(it.noteMetadataJson)?.dishIds.orEmpty()) &&
                        it.source == "manual" }.maxByOrNull { it.occurredAtUtcMillis }
                    Card(onClick = { onSelect(if (selectedFood == dish.id) null else dish.id) },
                        modifier = Modifier.fillMaxWidth().testTag("food-card-" + dish.id),
                        colors = CardDefaults.cardColors(containerColor = if (selectedFood == dish.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (dish.foodType.equals("Vegetables", true)) Icons.Filled.Eco else Icons.Filled.Restaurant, contentDescription = null)
                                Text(dish.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            }
                            if (phase != null) {
                                val duration = FoodLivePresentation.phaseElapsed(phase)
                                Text((if (legacy && phase.phase == "prep") "Status not recorded" else (if (legacy || data.cook.status == CookStatus.COMPLETED) "Last recorded: " else "") + CookPhases.label(phase.phase)) +
                                    (if (!legacy && duration > 0) " · " + foodDuration(duration) else ""), style = MaterialTheme.typography.bodySmall)
                            }
                            val displayedProbes = temperatures.mapNotNull { it.probe?.id }.toSet()
                            val assigned = data.probes.filter { it.assignedDishId == dish.id && it.measurementType == "food_probe" &&
                                (data.recording == null || it.deviceId == data.recording.deviceId) && it.id !in displayedProbes }
                            if (assigned.isNotEmpty()) Text("Assigned: " + assigned.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("food-probe-assignment-" + dish.id))
                            temperatures.forEach { value ->
                                val reading = value.reading
                                if (reading != null) {
                                    val number = CookTelemetryPolicy.convert(reading.value, reading.unit, displayUnit)
                                    Text((if (value.live) "" else "Last check · ") + String.format(Locale.getDefault(), "%.0f", number) + " " + displayUnit,
                                        style = if (value.live) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.testTag("food-temperature-" + dish.id + "-" + (value.probe?.id ?: "manual")))
                                    Text(((value.probe?.name ?: if (reading.source == "manual") "Manual" else reading.probeName) + " · ") + (if (value.live) "Received " else "") + foodClock(reading.measuredAtUtcMillis),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else Text(when {
                                    value.waiting -> (value.probe?.name ?: "Probe") + " · waiting for a reading"
                                    value.probe != null -> value.probe.name + " · reading unavailable"
                                    else -> "No probe"
                                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val method = methodEvents(data.events).filter { it.dishId == dish.id || it.dishId == null }.maxByOrNull { it.at }?.method
                            method?.let { Text("Method · ${CookingMethods.labels[it] ?: it}", style = MaterialTheme.typography.bodySmall) }
                            val nextReminder = reminders.filter { it.dishId == dish.id && it.enabled && it.status !in setOf("completed", "cancelled", "inactive") }.minByOrNull { rule -> occurrences.firstOrNull { it.ruleId == rule.id && it.sequence == 0 }?.dueAtUtcMillis ?: Long.MAX_VALUE }
                            nextReminder?.let { Text("Reminder · ${it.title} · ${it.status}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("food-next-reminder-${dish.id}")) }
                            last?.let { Text((PlaybookCodec.actions[PlaybookCodec.action(it.eventType, it.noteMetadataJson)] ?: CookFeedPresentation.title(it)) + " · " + foodClock(it.occurredAtUtcMillis),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    if (selectedFood == dish.id) {
                        FoodActions(dish, if (legacy && phase?.phase == "prep") "unknown" else phase?.phase ?: "prep", data.cook.status != CookStatus.COMPLETED,
                            onLog, onRecordAction, onEdit, onIngredients, onProbe, data.probes.any { it.measurementType == "food_probe" })
                        selectedTools(dish)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FoodActions(dish: DishEntity, phase: String, active: Boolean,
    onLog: (String, String, String?) -> Unit, onRecordAction: (DishEntity, String) -> Unit, onEdit: (DishEntity) -> Unit, onIngredients: (DishEntity) -> Unit, onProbe: (String) -> Unit, hasProbes: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("general" to "Note", "photo" to "Photo", "temperature" to "Temp").forEach { (category, label) ->
                OutlinedButton(onClick = { onLog(dish.id, category, null) }, modifier = Modifier.weight(1f).testTag("food-" + category + "-" + dish.id),
                    contentPadding = PaddingValues(8.dp)) { Text(label) }
            }
        }
        if (active) {
            val actions = when (phase) {
                "unknown" -> listOf("food_on", "spritz", "wrap", "remove", "rest_start", "hold_start", "dish_done")
                "cooking", "wrapped" -> listOf("spritz", "wrap", "remove", "rest_start")
                "removed" -> listOf("rest_start", "hold_start", "dish_done", "food_on")
                "resting" -> listOf("hold_start", "dish_done", "food_on")
                "holding" -> listOf("dish_done", "food_on")
                "done" -> emptyList()
                else -> listOf("food_on")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.forEach { action ->
                    // PL-01 (issue #172): a phase action records in one tap —
                    // Wrap included. It no longer merely opens a composer
                    // that records nothing until a second Save; a note or
                    // photo can be added to the recorded entry afterwards.
                    TextButton(onClick = { onRecordAction(dish, action) }, modifier = Modifier.testTag("food-action-" + action + "-" + dish.id)) {
                        Text(PlaybookCodec.actions[action] ?: action)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onIngredients(dish) }, modifier = Modifier.testTag("food-ingredients-" + dish.id)) { Text("Ingredients") }
            if (active && hasProbes) TextButton(onClick = { onProbe(dish.id) }, modifier = Modifier.testTag("food-probes-" + dish.id)) { Text("Assign / move probe") }
            TextButton(onClick = { onEdit(dish) }, modifier = Modifier.testTag("food-details-" + dish.id)) { Text("Food details") }
        }
    }
}

@Composable internal fun ProbeMoveDialog(data: CookDetailData, foodId: String?, viewModel: CooksViewModel, onClose: () -> Unit) {
    val probes = data.probes.filter { it.measurementType == "food_probe" && (data.recording == null || it.deviceId == data.recording.deviceId) }
    var probeId by rememberSaveable(data.cook.id, foodId) { mutableStateOf((probes.firstOrNull { it.assignedDishId == foodId } ?: probes.firstOrNull())?.id) }
    var dishId by rememberSaveable(data.cook.id, foodId) { mutableStateOf(foodId) }
    var changeTime by rememberSaveable { mutableStateOf(false) }
    var movedAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    // PL-22 (DD-6): probe reassignment is contextual capture — a sheet.
    SheetSurface(onDismiss = { if (!busy) onClose() }, title = "Move a probe", dismissEnabled = !busy,
        actions = {
            TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") }
            TextButton(onClick = { probeId?.let { id ->
                viewModel.assignGrillProbe(data.cook.id, id, dishId, movedAt.takeIf { changeTime }) { saved -> if (saved) onClose() }
            } }, enabled = !busy && probeId != null, modifier = Modifier.testTag("probe-move-save")) { Text(if (busy) "Saving…" else "Move probe") }
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val probeChoices = probes.mapIndexed { index, probe -> probe.id to (probe.name + if (probes.count { it.name == probe.name } > 1) " (" + (index + 1) + ")" else "") }
            val foods = foodChoices(data.dishes)
            SimpleDropdownField("Probe", probeChoices.firstOrNull { it.first == probeId }?.second ?: "No probe", probeChoices.map { it.second },
                { name -> probeId = probeChoices.firstOrNull { it.second == name }?.first }, testTag = "probe-move-probe")
            SimpleDropdownField("Food", foods.firstOrNull { it.first == dishId }?.second ?: "Unassigned", listOf("Unassigned") + foods.map { it.second },
                { name -> dishId = foods.firstOrNull { it.second == name }?.first }, testTag = "probe-move-food")
            Text("Earlier readings stay with their food.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { changeTime = !changeTime }, modifier = Modifier.testTag("probe-move-time-toggle")) { Text(if (changeTime) "Use the current time" else "I moved it earlier") }
            if (changeTime) DateTimePickerField("Actual move time", movedAt, { movedAt = it }, "probe-move-time")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun foodClock(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
private fun foodDuration(duration: Long): String {
    val hours = duration / 3_600_000
    val minutes = (duration / 60_000) % 60
    return if (hours > 0) hours.toString() + "h " + minutes + "m" else minutes.toString() + "m"
}
