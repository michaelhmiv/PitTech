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
import java.time.*
import java.time.format.DateTimeFormatter

internal fun serveTime(at: Long, zone: String): String = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))

@Composable
internal fun UpcomingCooks(viewModel: CooksViewModel) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val upcoming = records.filter { it.kind == "upcoming" && ServeTimePlanner.decode(it.payload).startedCookId == null }
    OutlinedButton(onClick = { viewModel.planServeTime() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plan-serving-time")) { Text("Plan a serving time") }
    upcoming.forEach { r ->
        val plan = remember(r.payload) { ServeTimePlanner.decode(r.payload) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Upcoming · ${r.title}", style = MaterialTheme.typography.titleMedium)
                Text("Serve ${serveTime(plan.serveAt, plan.zoneId)} · ${plan.zoneId}")
                Text("Food on from ${serveTime(ServeTimePlanner.windows(plan).minOf { it.foodOnAt }, plan.zoneId)}")
                Button(onClick = { viewModel.previewScheduledCook(r.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review and start") }
                Row { TextButton(onClick = { viewModel.planServeTime(scheduledId = r.id) }) { Text("Adjust schedule") }; TextButton(onClick = { viewModel.deleteScheduledCook(r.id) }) { Text("Delete plan") } }
            }
        }
    }
}

@Composable
internal fun ServePlanDialogs(viewModel: CooksViewModel) {
    val plan by viewModel.servePlanEditor.collectAsStateWithLifecycle()
    val evidence by viewModel.durationEvidence.collectAsStateWithLifecycle()
    plan?.let { ServePlanEditor(it, evidence, viewModel::closeServePlan, viewModel::saveServePlan) }
}

@Composable
private fun ServePlanEditor(initial: ServePlan, evidence: List<DurationEvidence>, onClose: () -> Unit, onSave: (ServePlan) -> Unit) {
    var plan by remember(initial) { mutableStateOf(initial) }
    val local = Instant.ofEpochMilli(initial.serveAt).atZone(ZoneId.of(initial.zoneId))
    var date by remember { mutableStateOf(local.toLocalDate().toString()) }
    var time by remember { mutableStateOf(local.toLocalTime().withSecond(0).withNano(0).toString()) }
    var zone by remember { mutableStateOf(initial.zoneId) }
    var laterOffset by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf(initial.bufferMinutes.toString()) }
    var editingSchedule by remember { mutableStateOf<Int?>(null) }
    var editingDish by remember { mutableStateOf<Int?>(null) }
    var editingStep by remember { mutableStateOf<PlaybookStep?>(null) }
    editingStep?.let { s -> StepEditor(s, plan.book.draft.dishes, { editingStep = null }) { step -> plan = plan.copy(book = plan.book.copy(steps = if (plan.book.steps.any { it.id == step.id }) plan.book.steps.map { if (it.id == step.id) step else it } else plan.book.steps + step)); editingStep = null } }
    var error by remember { mutableStateOf<String?>(null) }
    editingDish?.let { i -> DishEditorDialog(initial = plan.book.draft.dishes.getOrNull(i), onDismiss = { editingDish = null }, onSave = { dish ->
        val dishes = plan.book.draft.dishes.toMutableList(); if (i in dishes.indices) dishes[i] = dish else dishes.add(dish)
        val schedules = if (i in plan.schedules.indices) plan.schedules else plan.schedules + DishSchedule(i, evidenceNote = "Enter your duration range")
        plan = plan.copy(book = plan.book.copy(draft = plan.book.draft.copy(dishes = dishes)), schedules = schedules); editingDish = null
    }) }
    editingSchedule?.let { i -> DishScheduleEditor(plan.schedules[i], plan.book.draft.dishes[i], plan.book, evidence, { editingSchedule = null }) { s -> plan = plan.copy(schedules = plan.schedules.map { if (it.dishIndex == i) s else it }); editingSchedule = null } }
    val preview = runCatching { plan.copy(serveAt = ServeTimePlanner.localGoal(date, time, zone, laterOffset), zoneId = zone, bufferMinutes = buffer.toLong()).also(ServeTimePlanner::validate) }.getOrNull()
    val offsets = runCatching { ZoneId.of(zone).rules.getValidOffsets(LocalDate.parse(date).atTime(LocalTime.parse(time))).size }.getOrDefault(1)
    AlertDialog(onDismissRequest = onClose, title = { Text("Plan serving time") }, text = {
        Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(plan.book.name, { plan = plan.copy(book = plan.book.copy(name = it, draft = plan.book.draft.copy(title = it))) }, label = { Text("Cook name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, label = { Text("Serve date YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(time, { time = it }, label = { Text("Serve time HH:mm") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(zone, { zone = it }, label = { Text("Time zone") }, modifier = Modifier.fillMaxWidth())
            if (offsets == 2) ChoiceField("This time occurs twice", if (laterOffset) "Later occurrence" else "Earlier occurrence", listOf("Earlier occurrence", "Later occurrence")) { laterOffset = it == "Later occurrence" }
            OutlinedTextField(buffer, { buffer = it }, label = { Text("Extra buffer minutes") }, modifier = Modifier.fillMaxWidth())
            Text("Work backward from serving. Cooking stays a range, and resting is separate. Review each dish's durations before saving.")
            plan.schedules.forEach { s ->
                val dish = plan.book.draft.dishes[s.dishIndex]
                Text(dish.name, style = MaterialTheme.typography.titleMedium)
                Text("${s.cookMinMinutes}–${s.cookMaxMinutes} min cooking · ${s.restMinutes} min rest")
                Text(s.evidenceNote)
                Row { TextButton(onClick = { editingDish = s.dishIndex }) { Text("Dish and prep") }; TextButton(onClick = { editingSchedule = s.dishIndex }) { Text("Durations") } }
                preview?.let { p -> val w = ServeTimePlanner.windows(p).first { it.dishIndex == s.dishIndex }; Text("Prep ${serveTime(w.prepAt, p.zoneId)}\nPreheat ${serveTime(w.preheatAt, p.zoneId)}\nFood on ${serveTime(w.foodOnAt, p.zoneId)}\nReady ${serveTime(w.readyEarliest, p.zoneId)}–${serveTime(w.readyLatest, p.zoneId)}") }
            }
            TextButton(onClick = { editingDish = plan.book.draft.dishes.size }) { Text("Add dish or side") }
            plan.book.steps.forEach { s -> TextButton(onClick = { editingStep = s }) { Text("Edit step · ${s.title}") } }
            TextButton(onClick = { editingStep = PlaybookStep() }) { Text("Add guided step") }
            preview?.let { p -> ServeTimePlanner.conflicts(p).forEach { (a, b) -> Text("Pit-temperature conflict: ${p.book.draft.dishes[a].name} and ${p.book.draft.dishes[b].name}. Adjust the method, durations or pit temperature before sharing one smoker.", color = MaterialTheme.colorScheme.error) } }
            Text("Saved plans do not start a cook or recording. Start when you're ready.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching {
        val saved = preview ?: throw IllegalArgumentException("Check the serving date, time zone and buffer.")
        require(saved.schedules.none { it.evidenceNote == "Enter your duration range" }) { "Review each dish's duration range." }
        require(ServeTimePlanner.conflicts(saved).isEmpty()) { "Resolve the overlapping pit-temperature conflict before saving." }
        onSave(saved)
    }.onFailure { error = it.message } }) { Text("Save for later") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun DishScheduleEditor(initial: DishSchedule, dish: DishDraft, book: CookPlaybook, evidence: List<DurationEvidence>, onClose: () -> Unit, onSave: (DishSchedule) -> Unit) {
    var min by remember { mutableStateOf((initial.cookMinMinutes / 60.0).toString()) }
    var max by remember { mutableStateOf((initial.cookMaxMinutes / 60.0).toString()) }
    var prep by remember { mutableStateOf(initial.prepMinutes.toString()) }
    var preheat by remember { mutableStateOf(initial.preheatMinutes.toString()) }
    var rest by remember { mutableStateOf(initial.restMinutes.toString()) }
    var hold by remember { mutableStateOf(initial.holdMinutes.toString()) }
    var pit by remember { mutableStateOf(initial.pitF?.toString().orEmpty()) }
    var method by remember { mutableStateOf(initial.method) }
    var count by remember { mutableIntStateOf(initial.evidenceCount) }
    var note by remember { mutableStateOf(initial.evidenceNote) }
    var error by remember { mutableStateOf<String?>(null) }
    val matches = if (method.isNotBlank() && !method.equals(book.draft.smokerName, true)) emptyList() else ServeTimePlanner.comparable(dish, book.draft, evidence, book.steps.any { it.dishIndex == initial.dishIndex && it.action == "wrap" })
    AlertDialog(onDismissRequest = onClose, title = { Text("${dish.name} schedule") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (matches.isNotEmpty()) {
                Text("${matches.size} comparable recorded cooks · ${matches.minOf { it.durationMinutes }}–${matches.maxOf { it.durationMinutes }} min from Food on to Remove. Rest is separate.")
                if (matches.size == 1) Text("One cook is a reference. Enter your own planning range.")
                if (matches.size >= 2) TextButton(onClick = { min = (matches.minOf { it.durationMinutes } / 60.0).toString(); max = (matches.maxOf { it.durationMinutes } / 60.0).toString(); count = matches.size; note = "${matches.size} recorded cooks with similar cut, smoker, size, start, setpoint and wrap" }) { Text("Use recorded range") }
            } else Text("No comparable history. Enter a range you are comfortable planning around.")
            OutlinedTextField(min, { min = it; count = 0; note = "Your duration range" }, label = { Text("Cooking minimum hours") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(max, { max = it; count = 0; note = "Your duration range" }, label = { Text("Cooking maximum hours") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prep, { prep = it }, label = { Text("Prep minutes") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(preheat, { preheat = it }, label = { Text("Preheat minutes") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(rest, { rest = it }, label = { Text("Rest minutes") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(hold, { hold = it }, label = { Text("Planned hold minutes") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pit, { pit = it }, label = { Text("Pit °F (optional, for conflict checks)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(method, { method = it }, label = { Text("Cooking equipment (blank uses this smoker)") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching {
        val lo = min.toDouble(); val hi = max.toDouble(); require(lo.isFinite() && hi.isFinite()) { "Enter valid cooking durations." }
        val s = initial.copy(cookMinMinutes = (lo * 60).toLong(), cookMaxMinutes = (hi * 60).toLong(), prepMinutes = prep.toLong(), preheatMinutes = preheat.toLong(), restMinutes = rest.toLong(), holdMinutes = hold.toLong(), pitF = pit.toDoubleOrNull(), method = method, evidenceCount = count, evidenceNote = if (note == "Enter your duration range") "Your duration range" else note)
        ServeTimePlanner.validate(ServePlan(book, System.currentTimeMillis(), ZoneId.systemDefault().id, schedules = book.draft.dishes.indices.map { if (it == initial.dishIndex) s else DishSchedule(it) }))
        onSave(s)
    }.onFailure { error = it.message ?: "Check durations." } }) { Text("Save durations") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
internal fun ActiveServeGoal(data: CookDetailData, records: List<CompanionRecord>) {
    val record = records.firstOrNull { it.kind == "serve_goal" && it.cookId == data.cook.id } ?: return
    val plan = remember(record.payload) { ServeTimePlanner.decode(record.payload) }
    val events = data.events.map { PlanEvent(it.id, PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) }
    Text("Serve ${serveTime(plan.serveAt, plan.zoneId)} · ${plan.zoneId}", style = MaterialTheme.typography.titleMedium)
    val dishIds = records.firstOrNull { it.kind == "plan" && it.cookId == data.cook.id }?.let { CookPlanEngine.decode(it.payload).dishIds }
    plan.schedules.forEach { s -> (dishIds?.getOrNull(s.dishIndex)?.let { id -> data.dishes.firstOrNull { it.id == id } } ?: data.dishes.getOrNull(s.dishIndex))?.let { dish ->
        val (low, high) = ServeTimePlanner.updatedReadyWindow(plan, s, events, dish.id)
        Text("${dish.name} · planning window ${serveTime(low, plan.zoneId)}–${serveTime(high, plan.zoneId)}")
        if (high > plan.serveAt) Text("The planned ready window is after serving. Adjust the meal or method.", color = MaterialTheme.colorScheme.error)
        Text(s.evidenceNote, style = MaterialTheme.typography.bodySmall)
    } }
}
