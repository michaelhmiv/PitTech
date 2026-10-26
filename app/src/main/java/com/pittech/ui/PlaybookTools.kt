@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pittech.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val tokens = pitTechTokens()
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playbooks-toggle")) { Text("Playbooks · ${books.size}", style = PitTechTextStyles.bodyEmphasis) }
    if (expanded) {
        // TURN-IN (Slice 3, Area 03 `dark-13-playbooks` / `dark-15`): each
        // playbook is a plan card — banner name with its revision chip, the
        // setpoint / dishes / steps as score cells, and the plan's first
        // steps as a milestone rail. (The app has no standalone playbook
        // detail screen; the frame's detail composition lands here and in
        // the editor.) When the library is empty the toggle simply shows
        // the intro and the create path — the frame's empty state
        // (`dark-14`) in the app's own words.
        Text("Save a good cook here, then review its setup and steps before cooking again.", style = PitTechTextStyles.body, color = tokens.inkMuted)
        Button(onClick = viewModel::newPlaybook, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create playbook") }
        books.forEach { record ->
            val book = remember(record.payload) { PlaybookCodec.decode(record.payload) }
            TurnInCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TurnInBannerText(record.title, modifier = Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(8.dp), color = Color.Transparent, contentColor = tokens.emberText, border = BorderStroke(1.dp, tokens.ember)) {
                            Text("Revision ${book.revision}", style = PitTechTextStyles.monoMeta, color = tokens.emberText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    if (book.draft.smokerName.isNotBlank()) Text(book.draft.smokerName, style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (book.draft.setpointText.isNotBlank()) TurnInScoreCell("Setpoint", "${book.draft.setpointText}${book.draft.setpointUnit}", Modifier.weight(1f))
                        TurnInScoreCell("Dishes", "${book.draft.dishes.size}", Modifier.weight(1f))
                        TurnInScoreCell("Steps", "${book.steps.size}", Modifier.weight(1f))
                    }
                    if (book.steps.isNotEmpty()) TurnInMilestoneRail(book.steps.take(4).map { step ->
                        TurnInRailStep(
                            label = PlaybookCodec.actions[step.action] ?: step.action,
                            sublabel = when (step.trigger) {
                                "elapsed" -> "${step.minutes} min"
                                "after_action" -> "after ${PlaybookCodec.actions[step.anchor] ?: step.anchor}"
                                "temperature" -> step.temperatureF?.let { "${it.toInt()}°" }
                                "clock" -> step.clockAtUtcMillis?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
                                else -> null
                            },
                            state = TurnInRailState.UPCOMING,
                        )
                    })
                    val cookIds = records.filter { it.kind == "plan" && CookPlanEngine.decode(it.payload).playbookId == record.id }.mapNotNull { it.cookId }.toSet()
                    val outcomes = cooks.filter { it.cook.id in cookIds && it.cook.status == CookStatus.COMPLETED }
                    if (outcomes.isNotEmpty()) TextButton(onClick = { viewModel.openCook(outcomes.first().cook.id) }) { Text("${outcomes.size} cooks from this revision · open latest results") }
                    Button(onClick = { viewModel.previewPlaybook(record.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cook this") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.planServeTime(record.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Plan serving time") }
                        TextButton(onClick = { viewModel.editPlaybook(record.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Edit a new revision") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CookPlaybookTools(data: CookDetailData, viewModel: CooksViewModel, configuredOnly: Boolean? = null) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val plan = records.firstOrNull { it.kind == "plan" && it.cookId == data.cook.id }
    if (configuredOnly == true && (plan == null || data.cook.status == CookStatus.COMPLETED) || configuredOnly == false && plan != null && data.cook.status != CookStatus.COMPLETED) return
    if (data.cook.status == CookStatus.COMPLETED) {
        // A3 `dark-13`: the save-from-a-cook entry (F9) as a plan card.
        TurnInCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TurnInBannerText("Make the next cook easier")
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
    editingStep?.let { step -> StepEditor(step, book.draft.dishes, { editingStep = null }, otherSteps = book.steps, draftKey = step.id) { s ->
        book = book.copy(steps = if (book.steps.any { it.id == s.id }) book.steps.map { if (it.id == s.id) s else it } else book.steps + s)
        editingStep = null
    } }
    editingDish?.let { index -> DishEditorDialog(preferredWeightUnit = "lb", initial = book.draft.dishes.getOrNull(index), draftKey = "playbook", onDismiss = { editingDish = null }, onSave = { d ->
        val dishes = book.draft.dishes.toMutableList()
        if (index in dishes.indices) dishes[index] = d else dishes.add(d)
        book = book.copy(draft = book.draft.copy(dishes = dishes))
        editingDish = null
    }) }
    // PL-22 (DD-6): the playbook and step editors are multi-field — full screen.
    FullScreenSurface(onDismiss = onClose, title = "Save cook playbook", actions = {
        TextButton(onClick = onClose) { Text("Cancel") }
        TextButton(enabled = !busy, onClick = {
            try { PlaybookCodec.validate(book); require(CookEntryValidation.isOptionalPositiveNumberValid(book.draft.setpointText)) { "Enter a valid setpoint." }; onSave(book, includePhotos) }
            catch (failure: IllegalArgumentException) { localError = ErrorCopy.failureText(failure, "Check the playbook details and try again.") }
        }) { Text("Save playbook") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A3 `dark-16-playbook-editor`: boxed fields, the steps as
            // numbered cards with their trigger lines in mono.
            TurnInBoxedField("Playbook name") {
                OutlinedTextField(book.name, { book = book.copy(name = it) }, modifier = Modifier.fillMaxWidth().testTag("playbook-name"))
            }
            Text("Review suggested checks. Timing helps you inspect the food; you decide when to act.", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
            TurnInBoxedField("Smoker") {
                OutlinedTextField(book.draft.smokerName, { book = book.copy(draft = book.draft.copy(smokerName = it)) }, modifier = Modifier.fillMaxWidth())
            }
            TurnInBoxedField("Starting setpoint (${book.draft.setpointUnit})") {
                OutlinedTextField(book.draft.setpointText, { book = book.copy(draft = book.draft.copy(setpointText = it)) }, modifier = Modifier.fillMaxWidth())
            }
            book.draft.dishes.forEachIndexed { i, d -> OutlinedButton(onClick = { editingDish = i }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit ${d.name} and preparation") } }
            TextButton(onClick = { editingDish = book.draft.dishes.size }) { Text("Add dish") }
            book.steps.forEachIndexed { i, s ->
                TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${i + 1}. ${s.title}", style = PitTechTextStyles.bodyEmphasis)
                        Text(stepDescription(s), style = PitTechTextStyles.monoMeta, color = pitTechTokens().inkMuted)
                        if (s.instructions.isNotBlank()) Text(s.instructions, style = PitTechTextStyles.body)
                        FlowRow {
                            TextButton(onClick = { editingStep = s }) { Text("Edit") }
                            TextButton(onClick = { book = book.copy(steps = book.steps.filterNot { it.id == s.id }) }) { Text("Remove") }
                            if (i > 0) TextButton(onClick = { val steps = book.steps.toMutableList(); steps.removeAt(i); steps.add(i - 1, s); book = book.copy(steps = steps) }) { Text("Move up") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editingStep = PlaybookStep() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playbook-add-step")) { Text("Add step") }
            if (book.sourceCookId != null && book.previousRevisionId == null) {
                Row { Checkbox(includePhotos, { includePhotos = it }); Text("Include milestone photos (up to 12)", Modifier.padding(top = 12.dp)) }
            }
            Text("${book.targets.size} targets will be copied. Sensor history and connection messages stay in the original cook.", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun StepEditor(initial: PlaybookStep, dishes: List<DishDraft>, onClose: () -> Unit, probeNames: List<String> = emptyList(), otherSteps: List<PlaybookStep> = emptyList(), draftKey: String, onSave: (PlaybookStep) -> Unit) {
    // PL-24 (#172): autosave + resume, keyed by the step's own id (a new
    // step's id is minted before the editor opens, so its draft is its
    // own). Only divergence from the opened state is stored.
    val preferredUnit = preferredInputTemperatureUnit()
    val draftStore = rememberEditorDraftStore()
    val fullDraftKey = EditorDraftKeys.step(draftKey)
    val initialDraft = remember(initial) {
        StepEditorDraft(
            step = initial, minutes = initial.minutes.toString(),
            temp = initial.temperatureF?.let { temperatureText(it, preferredUnit) }.orEmpty(),
            tempUnit = preferredUnit, repeat = initial.repeatMinutes?.toString().orEmpty(),
            time = initial.clockAtUtcMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5) } ?: "18:00",
            date = initial.clockAtUtcMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().toString(),
        )
    }
    val storedDraft = remember(initial) { draftStore.load(fullDraftKey)?.let(StepEditorDraftCodec::decode)?.takeIf { it != initialDraft } }
    var openedDraft by remember(initial) { mutableStateOf(storedDraft ?: initialDraft) }
    var draftRestored by remember(initial) { mutableStateOf(storedDraft != null) }
    var step by remember(initial) { mutableStateOf(openedDraft.step) }
    var minutes by remember { mutableStateOf(openedDraft.minutes) }
    var tempUnit by remember { mutableStateOf(openedDraft.tempUnit) }
    var temp by remember { mutableStateOf(openedDraft.temp) }
    var repeat by remember { mutableStateOf(openedDraft.repeat) }
    var time by remember { mutableStateOf(openedDraft.time) }
    var date by remember { mutableStateOf(openedDraft.date) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(step, minutes, temp, tempUnit, repeat, time, date) {
        val current = StepEditorDraft(step, minutes, temp, tempUnit, repeat, time, date)
        if (current != openedDraft) draftStore.save(fullDraftKey, StepEditorDraftCodec.encode(current))
        else draftStore.clear(fullDraftKey)
    }
    FullScreenSurface(onDismiss = onClose, title = "Cook step", actions = {
        TextButton(onClick = onClose) { Text("Cancel") }
        TextButton(onClick = {
            runCatching {
                val clock = if (step.trigger == "clock") ServeTimePlanner.localGoal(date, time, ZoneId.systemDefault().id) else null
                require(minutes.toLongOrNull() != null) { "Enter whole minutes." }
                require(repeat.isBlank() || repeat.toLongOrNull() != null) { "Enter whole repeat minutes." }
                val s = step.copy(minutes = minutes.toLong(), temperatureF = temp.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, repeatMinutes = repeat.toLongOrNull(), clockAtUtcMillis = clock)
                PlaybookCodec.validate(CookPlaybook("Step", NewCookDraft("Step", dishes = dishes), otherSteps.filterNot { it.id == s.id } + s))
                draftStore.clear(fullDraftKey)
                onSave(s)
            }.onFailure { error = ErrorCopy.failureText(it, "That step couldn't be saved. The playbook is unchanged — check the step details and try again.") }
        }) { Text("Save step") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (draftRestored) {
                ResumeDraftBanner(onDiscard = {
                    draftStore.clear(fullDraftKey)
                    draftRestored = false
                    openedDraft = initialDraft
                    step = initialDraft.step; minutes = initialDraft.minutes
                    temp = initialDraft.temp; tempUnit = initialDraft.tempUnit
                    repeat = initialDraft.repeat; time = initialDraft.time; date = initialDraft.date
                })
            }
            // A3 `dark-17-step-editor` (and `dark-18` for the error): boxed
            // fields; a failed save flags at the field block's end and the
            // step's edits stay put.
            TurnInBoxedField("What to check or do") {
                OutlinedTextField(step.title, { step = step.copy(title = it) }, modifier = Modifier.fillMaxWidth())
            }
            ChoiceField("Action", PlaybookCodec.actions.getValue(step.action), PlaybookCodec.actions.values.toList()) { label -> step = step.copy(action = PlaybookCodec.actions.entries.first { it.value == label }.key) }
            val dishLabels = dishes.mapIndexed { i, d -> "${i + 1}. ${d.name}" }
            ChoiceField("Dish", step.dishIndex?.let { dishLabels.getOrNull(it) } ?: "Whole cook", listOf("Whole cook") + dishLabels) { label -> step = step.copy(dishIndex = dishLabels.indexOf(label).takeIf { it >= 0 }) }
            ChoiceField("When to remind", PlaybookCodec.triggers.getValue(step.trigger), PlaybookCodec.triggers.values.toList()) { label -> step = step.copy(trigger = PlaybookCodec.triggers.entries.first { it.value == label }.key) }
            if (step.trigger in setOf("elapsed", "after_action")) TurnInBoxedField("Minutes after") {
                OutlinedTextField(minutes, { minutes = it }, modifier = Modifier.fillMaxWidth())
            }
            if (step.trigger == "after_action") ChoiceField("After", PlaybookCodec.actions.getValue(step.anchor), PlaybookCodec.actions.values.toList()) { label -> step = step.copy(anchor = PlaybookCodec.actions.entries.first { it.value == label }.key) }
            if (initial.sourceObservations.isNotBlank()) Text("Source observations: ${initial.sourceObservations}. Choose a reminder trigger based on what you want to check.", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
            if (step.trigger == "temperature") {
                TurnInBoxedField("Temperature $tempUnit") {
                    OutlinedTextField(temp, { temp = it }, modifier = Modifier.fillMaxWidth())
                }
                ChoiceField("Unit", tempUnit, listOf("°F", "°C")) { unit -> temp = temp.toDoubleOrNull()?.let { temperatureText(CookPlanEngine.fahrenheit(it, tempUnit), unit) }.orEmpty(); tempUnit = unit }
                if (probeNames.isNotEmpty()) ChoiceField("Food probe", step.probeName.ifBlank { "Any food probe for this dish" }, listOf("Any food probe for this dish") + probeNames.distinct()) { label -> step = step.copy(probeName = label.takeUnless { it == "Any food probe for this dish" }.orEmpty()) }
                else TurnInBoxedField("Food probe name (optional)") {
                    OutlinedTextField(step.probeName, { step = step.copy(probeName = it) }, supportingText = { Text("Leave blank to use this dish's food probe. You can choose a recorded channel while cooking.") }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (step.trigger == "clock") {
                CookDateTimeControls(date, time, { date = it }, { time = it })
            }
            TurnInBoxedField("Repeat every minutes (optional)") {
                OutlinedTextField(repeat, { repeat = it }, modifier = Modifier.fillMaxWidth())
            }
            ChoiceField("While", step.stage.replaceFirstChar { it.uppercase() }, listOf("Prep", "Cooking", "Resting", "Holding")) { step = step.copy(stage = it.lowercase()) }
            TurnInBoxedField("Notes / what to look for") {
                OutlinedTextField(step.instructions, { step = step.copy(instructions = it) }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { TurnInStatusStrip(it, pitTechTokens().flag) }
        }
    }
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
