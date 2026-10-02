@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pittech.ui

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
import com.pittech.PitTechApplication
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun CookLearningTools(data: CookDetailData, viewModel: CooksViewModel) {
    val favorite = data.results.any { it.resultType == "favorite" && it.numericValue == 1.0 }
    var editTags by remember { mutableStateOf(false) }
    FlowRow {
        TextButton(onClick = { viewModel.toggleCookFavorite(data.cook.id, !favorite) }) { Text(if (favorite) "★ Favorite" else "☆ Favorite") }
        TextButton(onClick = { editTags = true }) { Text("Method and tags") }
    }
    if (data.cook.status == CookStatus.COMPLETED) {
        data.results.filter { it.resultType in setOf("keep_doing", "change_next_time") }.forEach { result -> Text("${if (result.resultType == "keep_doing") "Keep doing" else "Change next time"}: ${result.textValue.orEmpty()}") }
    }
    if (editTags) {
        var method by remember { mutableStateOf(data.results.firstOrNull { it.resultType == "method" }?.textValue.orEmpty()) }
        var tags by remember { mutableStateOf(data.results.firstOrNull { it.resultType == "tags" }?.textValue.orEmpty()) }
        AlertDialog(onDismissRequest = { editTags = false }, title = { Text("Find this cook again") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(method, { method = it }, label = { Text("Method (optional)") }, placeholder = { Text("No wrap, hot and fast, foil boat") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tags, { tags = it }, label = { Text("Tags (optional)") }, modifier = Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(onClick = { viewModel.saveCookTags(data.cook.id, method, tags); editTags = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editTags = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceComparisonTools(data: CookDetailData, viewModel: CooksViewModel) {
    val records by viewModel.companionRecords.collectAsStateWithLifecycle()
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as PitTechApplication
    var show by remember { mutableStateOf(false) }
    var dishId by remember(data.cook.id) { mutableStateOf(data.dishes.firstOrNull()?.id.orEmpty()) }
    val saved = records.firstOrNull { it.kind == "reference" && it.cookId == data.cook.id && CookReferenceCodec.decode(it.payload).dishId == dishId }?.let { CookReferenceCodec.decode(it.payload) }
    var sourceCookId by remember(saved?.sourceCookId, dishId) { mutableStateOf(saved?.sourceCookId ?: records.firstOrNull { it.kind == "plan" && it.cookId == data.cook.id }?.let { CookPlanEngine.decode(it.payload).book.sourceCookId }.orEmpty()) }
    var source by remember { mutableStateOf<CookDetailData?>(null) }
    LaunchedEffect(sourceCookId) { source = null; if (sourceCookId.isNotBlank()) app.cookRepository.observeCookDetail(sourceCookId).collectLatest { source = it } }
    var sourceDishId by remember(saved?.sourceDishId, sourceCookId) { mutableStateOf(saved?.sourceDishId.orEmpty()) }
    var anchor by remember(saved?.anchor) { mutableStateOf(saved?.anchor ?: "food_on") }
    var currentProbe by remember(saved?.currentProbe, dishId) { mutableStateOf(saved?.currentProbe ?: data.readings.firstOrNull { it.dishId == dishId && it.measurementType == "food_probe" }?.probeName.orEmpty()) }
    var sourceProbe by remember(saved?.sourceProbe, sourceCookId) { mutableStateOf(saved?.sourceProbe.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    if (data.dishes.isEmpty()) return
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Compare with a previous cook") }
    if (show) ModalBottomSheet(onDismissRequest = { show = false }) {
        Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reference cook", style = MaterialTheme.typography.titleLarge)
            Text("Choose one dish and one recorded channel from each cook. Curves show what was recorded, not a promise about when this cook will finish.")
            val dishLabels = data.dishes.mapIndexed { i, d -> "${i + 1}. ${d.name}" }
            ChoiceField("Current dish", data.dishes.indexOfFirst { it.id == dishId }.takeIf { it >= 0 }?.let { dishLabels[it] } ?: "Choose dish", dishLabels) { label -> dishId = data.dishes[dishLabels.indexOf(label)].id }
            val candidates = cooks.filter { it.cook.id != data.cook.id && it.cook.status == CookStatus.COMPLETED }
            val cookLabels = candidates.map { "${it.cook.title} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.cook.startedAtUtcMillis))}" }.let { names -> names.mapIndexed { i, label -> if (names.count { it == label } > 1) "$label (${i + 1})" else label } }
            ChoiceField("Previous cook", candidates.indexOfFirst { it.cook.id == sourceCookId }.takeIf { it >= 0 }?.let { cookLabels[it] } ?: "Choose cook", cookLabels) { label -> sourceCookId = candidates[cookLabels.indexOf(label)].cook.id }
            val history = source
            if (history != null) {
                val sourceLabels = history.dishes.mapIndexed { i, d -> "${i + 1}. ${d.name}" }
                ChoiceField("Reference dish", history.dishes.indexOfFirst { it.id == sourceDishId }.takeIf { it >= 0 }?.let { sourceLabels[it] } ?: "Choose dish", sourceLabels) { label -> sourceDishId = history.dishes[sourceLabels.indexOf(label)].id }
                ChoiceField("Current channel", currentProbe.ifBlank { "Choose channel" }, data.readings.filter { it.dishId == dishId || it.measurementType == "pit_ambient" }.map { it.probeName }.distinct()) { currentProbe = it }
                ChoiceField("Reference channel", sourceProbe.ifBlank { "Choose channel" }, history.readings.filter { it.dishId == sourceDishId || it.measurementType == "pit_ambient" }.map { it.probeName }.distinct()) { sourceProbe = it }
                ChoiceField("Align from", if (anchor == "food_on") "Food on" else "Wrap", listOf("Food on", "Wrap")) { anchor = if (it == "Food on") "food_on" else "wrap" }
                val currentEvents = data.events.map { PlanEvent(it.id, PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) }
                val sourceEvents = history.events.map { PlanEvent(it.id, PlaybookCodec.action(it.eventType), it.dishId, it.occurredAtUtcMillis) }
                val currentAnchor = CookReferenceCodec.anchor(currentEvents, dishId, anchor)
                val sourceAnchor = CookReferenceCodec.anchor(sourceEvents, sourceDishId, anchor)
                if (currentAnchor == null || sourceAnchor == null) Text("Log ${PlaybookCodec.actions[anchor]} for both dishes to align their curves.")
                else if (currentProbe.isBlank() || sourceProbe.isBlank()) Text("Choose both channels to compare temperatures.")
                else if ((data.readings.firstOrNull { it.probeName == currentProbe && (it.dishId == dishId || it.measurementType == "pit_ambient") }?.measurementType == "pit_ambient") != (history.readings.firstOrNull { it.probeName == sourceProbe && (it.dishId == sourceDishId || it.measurementType == "pit_ambient") }?.measurementType == "pit_ambient")) Text("Choose two food channels or two pit channels for a like-for-like comparison.")
                else {
                    val currentReadings = data.readings.filter { it.probeName == currentProbe && (it.dishId == dishId || it.measurementType == "pit_ambient") && it.measuredAtUtcMillis >= currentAnchor }
                    val referenceReadings = history.readings.filter { it.probeName == sourceProbe && (it.dishId == sourceDishId || it.measurementType == "pit_ambient") && it.measuredAtUtcMillis >= sourceAnchor }
                    TemperatureChart(currentReadings + referenceReadings, cookNames = mapOf(data.cook.id to "Current", history.cook.id to "Reference"), cookStartTimes = mapOf(data.cook.id to currentAnchor, history.cook.id to sourceAnchor), connectionGaps = data.events.filter { it.eventType == "connection_gap" } + history.events.filter { it.eventType == "connection_gap" })
                    Text("Minutes since ${PlaybookCodec.actions[anchor]} · units normalized · gaps remain empty")
                    PlaybookCodec.actions.keys.forEach { action ->
                        val current = currentEvents.firstOrNull { (it.dishId == dishId || it.dishId == null) && it.action == action }
                        val reference = sourceEvents.firstOrNull { (it.dishId == sourceDishId || it.dishId == null) && it.action == action }
                        if (current != null || reference != null) Text("${PlaybookCodec.actions[action]} · current ${current?.let { "${(it.at - currentAnchor) / 60_000}m" } ?: "—"} · reference ${reference?.let { "${(it.at - sourceAnchor) / 60_000}m" } ?: "—"}")
                    }
                    history.results.filter { it.dishId == sourceDishId && it.resultType in setOf("keep_doing", "change_next_time", "result_notes") }.forEach { Text(it.textValue.orEmpty()) }
                    Button(onClick = { viewModel.saveCookReference(data.cook.id, CookReference(dishId, sourceCookId, sourceDishId, currentProbe, sourceProbe, anchor)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use this reference") }
                }
                TextButton(onClick = { show = false; viewModel.openCook(history.cook.id) }) { Text("Open reference timeline, notes and photos") }
            } else if (sourceCookId.isNotBlank()) Text("The reference cook is unavailable on this phone. Choose another saved cook.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
