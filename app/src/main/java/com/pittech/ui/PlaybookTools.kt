@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.pittech.CooksViewModel
import com.pittech.data.*
import com.pittech.domain.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.*

@Composable
internal fun PlaybookDialogs(viewModel: CooksViewModel) {
    val book by viewModel.playbookEditor.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    book?.let { PlaybookEditor(it, busy, error, viewModel::closePlaybookEditor, viewModel::savePlaybook) }
}

@Composable
internal fun PlaybookLibrary(viewModel: CooksViewModel) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    val books = records.filter { it.kind == "playbook" }
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playbooks-toggle")) { Text("Playbooks · ${books.size}") }
    if (expanded) {
        Text("Save a good cook here, then review its setup and steps before cooking again.")
        OutlinedButton(onClick = viewModel::newPlaybook, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create playbook") }
        books.forEach { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(record.title, style = MaterialTheme.typography.titleMedium)
                    val book = remember(record.payload) { PlaybookCodec.decode(record.payload) }
                    Text("Revision ${book.revision} · ${book.draft.dishes.size} dishes · ${book.steps.size} steps")
                    val cookIds = records.filter { it.kind == "plan" && CookPlanEngine.decode(it.payload).playbookId == record.id }.mapNotNull { it.cookId }.toSet()
                    val outcomes = cooks.filter { it.cook.id in cookIds && it.cook.status == CookStatus.COMPLETED }
                    if (outcomes.isNotEmpty()) TextButton(onClick = { viewModel.openCook(outcomes.first().cook.id) }) { Text("${outcomes.size} cooks from this revision · open latest results") }
                    OutlinedButton(onClick = { viewModel.previewPlaybook(record.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cook this") }
                    TextButton(onClick = { viewModel.planServeTime(record.id) }) { Text("Plan serving time") }
                    TextButton(onClick = { viewModel.editPlaybook(record.id) }) { Text("Edit a new revision") }
                }
            }
        }
    }
}

@Composable
internal fun CookPlaybookTools(data: CookDetailData, viewModel: CooksViewModel) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val plan = records.firstOrNull { it.kind == "plan" && it.cookId == data.cook.id }
    if (data.cook.status == CookStatus.COMPLETED) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Make the next cook easier", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { viewModel.preparePlaybook(data.cook.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-playbook")) { Text("Save as playbook") }
                OutlinedButton(onClick = { viewModel.previewCookAgain(data.cook.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cook again") }
            }
        }
    } else {
        CookGuidanceCard(data, plan, viewModel)
    }
}

internal fun stepDescription(s: PlaybookStep): String = when (s.trigger) {
    "elapsed" -> "Check ${s.minutes} min after food goes on"
    "after_action" -> "Check ${s.minutes} min after ${PlaybookCodec.actions[s.anchor]}"
    "temperature" -> "Check at ${s.temperatureF} °F"
    "clock" -> "Check at ${s.clockAtUtcMillis?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it)) }}"
    else -> "When ready"
} + (s.repeatMinutes?.let { " · repeat every $it min" } ?: "")

@Composable
private fun PlaybookEditor(initial: CookPlaybook, busy: Boolean, error: String?, onClose: () -> Unit, onSave: (CookPlaybook, Boolean) -> Unit) {
    var book by remember(initial) { mutableStateOf(initial) }
    var includePhotos by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<PlaybookStep?>(null) }
    var editingDish by remember { mutableStateOf<Int?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    editingStep?.let { step -> StepEditor(step, book.draft.dishes, { editingStep = null }, otherSteps = book.steps) { s ->
        book = book.copy(steps = if (book.steps.any { it.id == s.id }) book.steps.map { if (it.id == s.id) s else it } else book.steps + s)
        editingStep = null
    } }
    editingDish?.let { index -> DishEditorDialog(preferredWeightUnit = "lb", initial = book.draft.dishes.getOrNull(index), onDismiss = { editingDish = null }, onSave = { d ->
        val dishes = book.draft.dishes.toMutableList()
        if (index in dishes.indices) dishes[index] = d else dishes.add(d)
        book = book.copy(draft = book.draft.copy(dishes = dishes))
        editingDish = null
    }) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Save cook playbook") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(book.name, { book = book.copy(name = it) }, label = { Text("Playbook name") }, modifier = Modifier.fillMaxWidth().testTag("playbook-name"))
            Text("Review suggested checks. Timing helps you inspect the food; you decide when to act.")
            OutlinedTextField(book.draft.smokerName, { book = book.copy(draft = book.draft.copy(smokerName = it)) }, label = { Text("Smoker") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(book.draft.setpointText, { book = book.copy(draft = book.draft.copy(setpointText = it)) }, label = { Text("Starting setpoint (${book.draft.setpointUnit})") }, modifier = Modifier.fillMaxWidth())
            book.draft.dishes.forEachIndexed { i, d -> OutlinedButton(onClick = { editingDish = i }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit ${d.name} and preparation") } }
            TextButton(onClick = { editingDish = book.draft.dishes.size }) { Text("Add dish") }
            book.steps.forEachIndexed { i, s ->
                Text("${i + 1}. ${s.title}", style = MaterialTheme.typography.titleSmall)
                Text(stepDescription(s))
                if (s.instructions.isNotBlank()) Text(s.instructions)
                FlowRow {
                    TextButton(onClick = { editingStep = s }) { Text("Edit") }
                    TextButton(onClick = { book = book.copy(steps = book.steps.filterNot { it.id == s.id }) }) { Text("Remove") }
                    if (i > 0) TextButton(onClick = { val steps = book.steps.toMutableList(); steps.removeAt(i); steps.add(i - 1, s); book = book.copy(steps = steps) }) { Text("Move up") }
                }
            }
            OutlinedButton(onClick = { editingStep = PlaybookStep() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playbook-add-step")) { Text("Add step") }
            if (book.sourceCookId != null && book.previousRevisionId == null) {
                Row { Checkbox(includePhotos, { includePhotos = it }); Text("Include milestone photos (up to 12)", Modifier.padding(top = 12.dp)) }
            }
            Text("${book.targets.size} targets will be copied. Sensor history and connection messages stay in the original cook.")
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try { PlaybookCodec.validate(book); require(CookEntryValidation.isOptionalPositiveNumberValid(book.draft.setpointText)) { "Enter a valid setpoint." }; onSave(book, includePhotos) }
        catch (failure: IllegalArgumentException) { localError = failure.message }
    }) { Text("Save playbook") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
internal fun StepEditor(initial: PlaybookStep, dishes: List<DishDraft>, onClose: () -> Unit, probeNames: List<String> = emptyList(), otherSteps: List<PlaybookStep> = emptyList(), onSave: (PlaybookStep) -> Unit) {
    var step by remember(initial) { mutableStateOf(initial) }
    var minutes by remember { mutableStateOf(initial.minutes.toString()) }
    val preferredUnit = preferredInputTemperatureUnit()
    var tempUnit by remember { mutableStateOf(preferredUnit) }
    var temp by remember { mutableStateOf(initial.temperatureF?.let { temperatureText(it, tempUnit) }.orEmpty()) }
    var repeat by remember { mutableStateOf(initial.repeatMinutes?.toString().orEmpty()) }
    var time by remember { mutableStateOf(initial.clockAtUtcMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) } ?: "18:00") }
    var date by remember { mutableStateOf(initial.clockAtUtcMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Cook step") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(step.title, { step = step.copy(title = it) }, label = { Text("What to check or do") }, modifier = Modifier.fillMaxWidth())
            ChoiceField("Action", PlaybookCodec.actions.getValue(step.action), PlaybookCodec.actions.values.toList()) { label -> step = step.copy(action = PlaybookCodec.actions.entries.first { it.value == label }.key) }
            val dishLabels = dishes.mapIndexed { i, d -> "${i + 1}. ${d.name}" }
            ChoiceField("Dish", step.dishIndex?.let { dishLabels.getOrNull(it) } ?: "Whole cook", listOf("Whole cook") + dishLabels) { label -> step = step.copy(dishIndex = dishLabels.indexOf(label).takeIf { it >= 0 }) }
            ChoiceField("When to remind", PlaybookCodec.triggers.getValue(step.trigger), PlaybookCodec.triggers.values.toList()) { label -> step = step.copy(trigger = PlaybookCodec.triggers.entries.first { it.value == label }.key) }
            if (step.trigger in setOf("elapsed", "after_action")) OutlinedTextField(minutes, { minutes = it }, label = { Text("Minutes after") }, modifier = Modifier.fillMaxWidth())
            if (step.trigger == "after_action") ChoiceField("After", PlaybookCodec.actions.getValue(step.anchor), PlaybookCodec.actions.values.toList()) { label -> step = step.copy(anchor = PlaybookCodec.actions.entries.first { it.value == label }.key) }
            if (initial.sourceObservations.isNotBlank()) Text("Source observations: ${initial.sourceObservations}. Choose a reminder trigger based on what you want to check.")
            if (step.trigger == "temperature") {
                OutlinedTextField(temp, { temp = it }, label = { Text("Temperature $tempUnit") }, modifier = Modifier.fillMaxWidth())
                ChoiceField("Unit", tempUnit, listOf("°F", "°C")) { unit -> temp = temp.toDoubleOrNull()?.let { temperatureText(CookPlanEngine.fahrenheit(it, tempUnit), unit) }.orEmpty(); tempUnit = unit }
                if (probeNames.isNotEmpty()) ChoiceField("Food probe", step.probeName.ifBlank { "Any food probe for this dish" }, listOf("Any food probe for this dish") + probeNames.distinct()) { label -> step = step.copy(probeName = label.takeUnless { it == "Any food probe for this dish" }.orEmpty()) }
                else OutlinedTextField(step.probeName, { step = step.copy(probeName = it) }, label = { Text("Food probe name (optional)") }, supportingText = { Text("Leave blank to use this dish's food probe. You can choose a recorded channel while cooking.") }, modifier = Modifier.fillMaxWidth())
            }
            if (step.trigger == "clock") {
                CookDateTimeControls(date, time, { date = it }, { time = it })
            }
            OutlinedTextField(repeat, { repeat = it }, label = { Text("Repeat every minutes (optional)") }, modifier = Modifier.fillMaxWidth())
            ChoiceField("While", step.stage.replaceFirstChar { it.uppercase() }, listOf("Prep", "Cooking", "Resting", "Holding")) { step = step.copy(stage = it.lowercase()) }
            OutlinedTextField(step.instructions, { step = step.copy(instructions = it) }, label = { Text("Notes / what to look for") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val clock = if (step.trigger == "clock") ServeTimePlanner.localGoal(date, time, ZoneId.systemDefault().id) else null
            require(minutes.toLongOrNull() != null) { "Enter whole minutes." }
            require(repeat.isBlank() || repeat.toLongOrNull() != null) { "Enter whole repeat minutes." }
            val s = step.copy(minutes = minutes.toLong(), temperatureF = temp.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, repeatMinutes = repeat.toLongOrNull(), clockAtUtcMillis = clock)
            PlaybookCodec.validate(CookPlaybook("Step", NewCookDraft("Step", dishes = dishes), otherSteps.filterNot { it.id == s.id } + s))
            onSave(s)
        }.onFailure { error = it.message ?: "Check the step details." }
    }) { Text("Save step") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
internal fun preferredInputTemperatureUnit(): String = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("pittech-preferences", 0).getString("temperature-unit", "°F")?.takeIf { it in setOf("°F", "°C") } ?: "°F"
internal fun temperatureText(fahrenheit: Double, unit: String): String = String.format(java.util.Locale.US, "%.1f", if (unit == "°C") (fahrenheit - 32) * 5 / 9 else fahrenheit)

@Composable
internal fun CookDateTimeControls(date: String, time: String, onDate: (String) -> Unit, onTime: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    val parsedTime = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(18, 0))
    OutlinedButton(onClick = {
        android.app.DatePickerDialog(context, { _, y, m, d -> onDate(LocalDate.of(y, m + 1, d).toString()) }, parsedDate.year, parsedDate.monthValue - 1, parsedDate.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Date · ${parsedDate.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))}") }
    OutlinedButton(onClick = {
        android.app.TimePickerDialog(context, { _, h, m -> onTime(LocalTime.of(h, m).toString()) }, parsedTime.hour, parsedTime.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Time · ${parsedTime.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))}") }
}

@Composable
internal fun ChoiceField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "$label: $value" }) { Text(value) }
            DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) } }
        }
    }
}
