@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

/** PL-10 (#172): a saved serve plan is explicitly badged "Not started" so
 * it can never read as a cook already in progress. TURN-IN (Slice 3): the
 * badge is the frame's marigold stamp (`dark-27-upcoming`). */
@Composable
internal fun NotStartedBadge(modifier: Modifier = Modifier) {
    TurnInStamp("Not started", modifier)
}

@Composable
internal fun UpcomingCooks(viewModel: CooksViewModel) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val upcoming = records.filter { it.kind == "upcoming" && ServeTimePlanner.decode(it.payload).startedCookId == null }
    val tokens = pitTechTokens()
    OutlinedButton(onClick = { viewModel.planServeTime() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plan-serving-time")) { Text("Plan a serving time") }
    upcoming.forEach { r ->
        val plan = remember(r.payload) { ServeTimePlanner.decode(r.payload) }
        val windows = ServeTimePlanner.windows(plan)
        // A3 `dark-27-upcoming`: the plan as a lane — banner name with the
        // Not started stamp, the serve line in mono, and the backwards
        // rail (preheat → food on → ready → serve, serve loudest).
        TurnInCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurnInBannerText("Upcoming · ${r.title}", modifier = Modifier.weight(1f))
                    NotStartedBadge(Modifier.testTag("upcoming-not-started"))
                }
                Text("Serve ${serveTime(plan.serveAt, plan.zoneId)} · ${plan.zoneId}", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                if (windows.isNotEmpty()) {
                    val clock = DateTimeFormatter.ofPattern("HH:mm")
                    fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.of(plan.zoneId)).format(clock)
                    TurnInMilestoneRail(listOf(
                        TurnInRailStep("Preheat", at(windows.minOf { it.preheatAt }), TurnInRailState.UPCOMING),
                        TurnInRailStep("Food on", at(windows.minOf { it.foodOnAt }), TurnInRailState.UPCOMING),
                        TurnInRailStep("Ready", at(windows.maxOf { it.readyLatest }), TurnInRailState.UPCOMING),
                        TurnInRailStep("Serve", at(plan.serveAt), TurnInRailState.CURRENT),
                    ))
                }
                Button(onClick = { viewModel.previewScheduledCook(r.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review and start") }
                FlowRow { TextButton(onClick = { viewModel.planServeTime(scheduledId = r.id) }) { Text("Adjust schedule") }; TextButton(onClick = { viewModel.deleteScheduledCook(r.id) }) { Text("Delete plan") } }
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
    editingStep?.let { s -> StepEditor(s, plan.book.draft.dishes, { editingStep = null }, otherSteps = plan.book.steps, draftKey = s.id) { step -> plan = plan.copy(book = plan.book.copy(steps = if (plan.book.steps.any { it.id == step.id }) plan.book.steps.map { if (it.id == step.id) step else it } else plan.book.steps + step)); editingStep = null } }
    var error by remember { mutableStateOf<String?>(null) }
    editingDish?.let { i -> DishEditorDialog(initial = plan.book.draft.dishes.getOrNull(i), draftKey = "serveplan", onDismiss = { editingDish = null }, onSave = { dish ->
        val dishes = plan.book.draft.dishes.toMutableList(); if (i in dishes.indices) dishes[i] = dish else dishes.add(dish)
        val schedules = if (i in plan.schedules.indices) plan.schedules else plan.schedules + DishSchedule(i, evidenceNote = "Enter your duration range")
        plan = plan.copy(book = plan.book.copy(draft = plan.book.draft.copy(dishes = dishes)), schedules = schedules); editingDish = null
    }) }
    editingSchedule?.let { i -> DishScheduleEditor(plan.schedules[i], plan.book.draft.dishes[i], plan.book, evidence, { editingSchedule = null }) { s -> plan = plan.copy(schedules = plan.schedules.map { if (it.dishIndex == i) s else it }); editingSchedule = null } }
    val preview = runCatching { plan.copy(serveAt = ServeTimePlanner.localGoal(date, time, zone, laterOffset), zoneId = zone, bufferMinutes = buffer.toLong()).also(ServeTimePlanner::validate) }.getOrNull()
    val offsets = runCatching { ZoneId.of(zone).rules.getValidOffsets(LocalDate.parse(date).atTime(LocalTime.parse(time))).size }.getOrDefault(1)
    val tokens = pitTechTokens()
    // The serve goal the hero reads from: the validated preview when the
    // fields parse, the parsed goal when they do, the plan's own serve
    // time while the fields are mid-edit.
    val goal = preview?.serveAt ?: runCatching { ServeTimePlanner.localGoal(date, time, zone, laterOffset) }.getOrDefault(initial.serveAt)
    // The hero formats in the field's zone while it parses, and in the
    // plan's own zone while the field is mid-edit garbage.
    val heroZone = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of(initial.zoneId))
    // PL-22 (DD-6): the serve-time planner is a multi-field editor — full screen.
    FullScreenSurface(onDismiss = onClose, title = "Plan serving time", actions = {
        TextButton(onClick = onClose) { Text("Cancel") }
        TextButton(onClick = { runCatching {
            val saved = preview ?: throw IllegalArgumentException("Check the serving date, time zone and buffer.")
            require(saved.schedules.none { it.evidenceNote == "Enter your duration range" }) { "Review each dish's duration range." }
            require(ServeTimePlanner.conflicts(saved).isEmpty()) { "Resolve the overlapping pit-temperature conflict before saving." }
            onSave(saved)
        }.onFailure { error = ErrorCopy.failureText(it, "That plan couldn't be saved. Nothing was changed — check the serving date, time zone and durations, then try again.") } }) { Text("Save plan") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A3 `dark-19-serve-plan`: the serve time is the hero — a
            // scoreboard numeral under a serve kicker, the zone beneath in
            // mono, and the backwards rail once the plan computes.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TurnInMetaLabel("Serve — ${Instant.ofEpochMilli(goal).atZone(heroZone).format(DateTimeFormatter.ofPattern("EEEE, MMM d"))}", color = tokens.attentionText)
                Text(Instant.ofEpochMilli(goal).atZone(heroZone).format(DateTimeFormatter.ofPattern("h:mm a")), style = PitTechTextStyles.scoreboard)
                Text(zone, style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
            }
            preview?.let { p ->
                val windows = ServeTimePlanner.windows(p)
                if (windows.isNotEmpty()) {
                    val clock = DateTimeFormatter.ofPattern("HH:mm")
                    fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.of(p.zoneId)).format(clock)
                    TurnInMilestoneRail(listOf(
                        TurnInRailStep("Preheat", at(windows.minOf { it.preheatAt }), TurnInRailState.UPCOMING),
                        TurnInRailStep("Food on", at(windows.minOf { it.foodOnAt }), TurnInRailState.UPCOMING),
                        TurnInRailStep("Ready", at(windows.maxOf { it.readyLatest }), TurnInRailState.UPCOMING),
                        TurnInRailStep("Serve", at(p.serveAt), TurnInRailState.CURRENT),
                    ))
                }
            }
            TurnInBoxedField("Cook name") {
                OutlinedTextField(plan.book.name, { plan = plan.copy(book = plan.book.copy(name = it, draft = plan.book.draft.copy(title = it))) }, modifier = Modifier.fillMaxWidth())
            }
            NotStartedBadge(Modifier.testTag("serve-plan-not-started"))
            Text("Serving date and time", style = PitTechTextStyles.metaLabel, color = tokens.inkMuted)
            CookDateTimeControls(date, time, { date = it }, { time = it })
            TurnInBoxedField("Time zone") {
                OutlinedTextField(zone, { zone = it }, modifier = Modifier.fillMaxWidth())
            }
            if (offsets == 2) ChoiceField("This time occurs twice", if (laterOffset) "Later occurrence" else "Earlier occurrence", listOf("Earlier occurrence", "Later occurrence")) { laterOffset = it == "Later occurrence" }
            TurnInBoxedField("Extra buffer minutes") {
                OutlinedTextField(buffer, { buffer = it }, modifier = Modifier.fillMaxWidth())
            }
            Text("Work backward from serving. Cooking stays a range, and resting is separate. Review each dish's durations before saving.", style = PitTechTextStyles.body)
            plan.schedules.forEach { s ->
                val dish = plan.book.draft.dishes[s.dishIndex]
                TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TurnInBannerText(dish.name)
                        Text("${s.cookMinMinutes}–${s.cookMaxMinutes} min cooking · ${s.restMinutes} min rest", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                        Text(s.evidenceNote, style = PitTechTextStyles.body)
                        FlowRow { TextButton(onClick = { editingDish = s.dishIndex }) { Text("Dish and prep") }; TextButton(onClick = { editingSchedule = s.dishIndex }) { Text("Durations") } }
                        preview?.let { p -> val w = ServeTimePlanner.windows(p).first { it.dishIndex == s.dishIndex }; Text("Prep ${serveTime(w.prepAt, p.zoneId)}\nPreheat ${serveTime(w.preheatAt, p.zoneId)}\nFood on ${serveTime(w.foodOnAt, p.zoneId)}\nReady ${serveTime(w.readyEarliest, p.zoneId)}–${serveTime(w.readyLatest, p.zoneId)}", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted) }
                    }
                }
            }
            TextButton(onClick = { editingDish = plan.book.draft.dishes.size }) { Text("Add dish or side") }
            plan.book.steps.forEach { s -> TextButton(onClick = { editingStep = s }) { Text("Edit step · ${s.title}") } }
            TextButton(onClick = { editingStep = PlaybookStep() }) { Text("Add guided step") }
            // A3 `dark-19`: the pit-temperature conflict is a flag strip,
            // not a red line of body text (frame 20's error state keeps the
            // same treatment for the save failure below).
            preview?.let { p -> ServeTimePlanner.conflicts(p).forEach { (a, b) -> TurnInStatusStrip("Pit-temperature conflict: ${p.book.draft.dishes[a].name} and ${p.book.draft.dishes[b].name}. Adjust the method, durations or pit temperature before sharing one smoker.", tokens.flag) } }
            Text("Saved plans do not start a cook or recording. Start when you're ready.", style = PitTechTextStyles.body, color = tokens.inkMuted)
            error?.let { TurnInStatusStrip(it, tokens.flag) }
        }
    }
}

@Composable
private fun DishScheduleEditor(initial: DishSchedule, dish: DishDraft, book: CookPlaybook, evidence: List<DurationEvidence>, onClose: () -> Unit, onSave: (DishSchedule) -> Unit) {
    var min by remember { mutableStateOf((initial.cookMinMinutes / 60.0).toString()) }
    var max by remember { mutableStateOf((initial.cookMaxMinutes / 60.0).toString()) }
    var prep by remember { mutableStateOf(initial.prepMinutes.toString()) }
    var preheat by remember { mutableStateOf(initial.preheatMinutes.toString()) }
    var rest by remember { mutableStateOf(initial.restMinutes.toString()) }
    var hold by remember { mutableStateOf(initial.holdMinutes.toString()) }
    val preferredUnit = preferredInputTemperatureUnit()
    var tempUnit by remember { mutableStateOf(preferredUnit) }
    var pit by remember { mutableStateOf(initial.pitF?.let { temperatureText(it, tempUnit) }.orEmpty()) }
    var method by remember { mutableStateOf(initial.method) }
    var count by remember { mutableIntStateOf(initial.evidenceCount) }
    var note by remember { mutableStateOf(initial.evidenceNote) }
    var error by remember { mutableStateOf<String?>(null) }
    val matches = if (method.isNotBlank() && !method.equals(book.draft.smokerName, true)) emptyList() else ServeTimePlanner.comparable(dish, book.draft, evidence, book.steps.any { it.dishIndex == initial.dishIndex && it.action == "wrap" })
    FullScreenSurface(onDismiss = onClose, title = "${dish.name} schedule", actions = {
        TextButton(onClick = onClose) { Text("Cancel") }
        TextButton(onClick = { runCatching {
            val lo = min.toDouble(); val hi = max.toDouble(); require(lo.isFinite() && hi.isFinite()) { "Enter valid cooking durations." }
            require(pit.isBlank() || pit.toDoubleOrNull() != null) { "Enter a valid pit temperature or leave it blank." }
            val s = initial.copy(cookMinMinutes = (lo * 60).toLong(), cookMaxMinutes = (hi * 60).toLong(), prepMinutes = prep.toLong(), preheatMinutes = preheat.toLong(), restMinutes = rest.toLong(), holdMinutes = hold.toLong(), pitF = pit.toDoubleOrNull()?.let { CookPlanEngine.fahrenheit(it, tempUnit) }, method = method, evidenceCount = count, evidenceNote = if (note == "Enter your duration range") "Your duration range" else note)
            ServeTimePlanner.validate(ServePlan(book, System.currentTimeMillis(), ZoneId.systemDefault().id, schedules = book.draft.dishes.indices.map { if (it == initial.dishIndex) s else DishSchedule(it) }))
            onSave(s)
        }.onFailure { error = ErrorCopy.failureText(it, "Those durations couldn't be saved. The plan is unchanged — check the durations and try again.") } }) { Text("Save durations") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A3 `dark-21-dish-schedule` (and `dark-22` for the empty
            // evidence state): the recorded range rides in a card on top;
            // the durations are boxed fields below it.
            if (matches.isNotEmpty()) {
                TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TurnInMetaLabel("Recorded range")
                        Text("${matches.size} comparable recorded cooks · ${matches.minOf { it.durationMinutes }}–${matches.maxOf { it.durationMinutes }} min from Food on to Remove. Rest is separate.", style = PitTechTextStyles.body)
                        if (matches.size == 1) Text("One cook is a reference. Enter your own planning range.", style = PitTechTextStyles.body)
                        if (matches.size >= 2) TextButton(onClick = { min = (matches.minOf { it.durationMinutes } / 60.0).toString(); max = (matches.maxOf { it.durationMinutes } / 60.0).toString(); count = matches.size; note = "${matches.size} recorded cooks with similar cut, smoker, size, start, setpoint and wrap" }) { Text("Use recorded range") }
                    }
                }
            } else Text("No comparable history. Enter a range you are comfortable planning around.", style = PitTechTextStyles.body)
            TurnInBoxedField("Cooking minimum hours") {
                OutlinedTextField(min, { min = it; count = 0; note = "Your duration range" }, modifier = Modifier.fillMaxWidth())
            }
            TurnInBoxedField("Cooking maximum hours") {
                OutlinedTextField(max, { max = it; count = 0; note = "Your duration range" }, modifier = Modifier.fillMaxWidth())
            }
            TurnInBoxedField("Prep minutes") {
                OutlinedTextField(prep, { prep = it }, modifier = Modifier.fillMaxWidth())
            }
            TurnInBoxedField("Preheat minutes") {
                OutlinedTextField(preheat, { preheat = it }, modifier = Modifier.fillMaxWidth())
            }
            TurnInBoxedField("Rest minutes") {
                OutlinedTextField(rest, { rest = it }, modifier = Modifier.fillMaxWidth())
            }
            TurnInBoxedField("Planned hold minutes") {
                OutlinedTextField(hold, { hold = it }, modifier = Modifier.fillMaxWidth())
            }
            TurnInBoxedField("Pit $tempUnit (optional, for conflict checks)") {
                OutlinedTextField(pit, { pit = it }, modifier = Modifier.fillMaxWidth())
            }
            ChoiceField("Temperature unit", tempUnit, listOf("°F", "°C")) { unit -> pit = pit.toDoubleOrNull()?.let { temperatureText(CookPlanEngine.fahrenheit(it, tempUnit), unit) }.orEmpty(); tempUnit = unit }
            TurnInBoxedField("Cooking equipment (blank uses this smoker)") {
                OutlinedTextField(method, { method = it }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { TurnInStatusStrip(it, pitTechTokens().flag) }
        }
    }
}

@Composable
internal fun ActiveServeGoal(data: CookDetailData, records: List<CompanionRecord>) {
    val record = records.firstOrNull { it.kind == "serve_goal" && it.cookId == data.cook.id } ?: return
    val plan = remember(record.payload) { ServeTimePlanner.decode(record.payload) }
    val events = data.events.let { com.pittech.data.notePlanEvents(it) }
    val tokens = pitTechTokens()
    // A3 `dark-23-guidance`: the serve goal rides the guidance sheet as a
    // mono readout with the countdown's facts; a window that slips past
    // serving is a flag strip, not a red line.
    Text("Serve ${serveTime(plan.serveAt, plan.zoneId)} · ${plan.zoneId}", style = PitTechTextStyles.monoReadout, color = tokens.attentionText)
    val dishIds = records.firstOrNull { it.kind == "plan" && it.cookId == data.cook.id }?.let { CookPlanEngine.decode(it.payload).dishIds }
    plan.schedules.forEach { s -> (dishIds?.getOrNull(s.dishIndex)?.let { id -> data.dishes.firstOrNull { it.id == id } } ?: data.dishes.getOrNull(s.dishIndex))?.let { dish ->
        val (low, high) = ServeTimePlanner.updatedReadyWindow(plan, s, events, dish.id)
        Text("${dish.name} · planning window ${serveTime(low, plan.zoneId)}–${serveTime(high, plan.zoneId)}", style = PitTechTextStyles.body)
        if (high > plan.serveAt) TurnInStatusStrip("The planned ready window is after serving. Adjust the meal or method.", tokens.flag)
        Text(s.evidenceNote, style = PitTechTextStyles.body, color = tokens.inkMuted)
    } }
}
