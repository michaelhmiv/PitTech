@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun RecipeReferenceTools(data: CookDetailData, viewModel: CooksViewModel, initialDishId: String? = null) {
    val sources by viewModel.recipes.observeSources().collectAsStateWithLifecycle(emptyList())
    val revisions by viewModel.recipes.observeRevisions().collectAsStateWithLifecycle(emptyList())
    val headers by viewModel.recipes.observeRecipes().collectAsStateWithLifecycle(emptyList())
    val cooks by viewModel.cooks.collectAsStateWithLifecycle()
    var dishId by remember(data.cook.id) { mutableStateOf<String?>(null) }
    val source = sources.firstOrNull { it.dishId == dishId }
    var revisionId by remember(dishId, source?.recipeRevisionId) { mutableStateOf(source?.recipeRevisionId) }
    val loaded = data.dishes.filter { dish -> (initialDishId == null || dish.id == initialDishId) && sources.any { it.dishId == dish.id } }
    if (loaded.isEmpty()) return
    loaded.forEach { dish -> TextButton({ dishId = dish.id }, Modifier.testTag("recipe-history-${dish.id}")) { Text("Previous cook · ${dish.name}") } }
    if (dishId != null) ModalBottomSheet(onDismissRequest = { dishId = null }) {
        Column(Modifier.heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Previous cook and this cook", style = MaterialTheme.typography.titleLarge)
            Text(data.dishes.firstOrNull { it.id == dishId }?.name.orEmpty())
            val versions = revisions.filter { it.recipeId == source?.recipeId }.sortedByDescending { it.revisionNumber }
            val labels = versions.map { "Version ${it.revisionNumber}${if (it.id == source?.recipeRevisionId) " · used in this meal" else ""}${if (headers.firstOrNull { h -> h.id == it.recipeId }?.currentRevisionId == it.id) " · preferred" else ""}" }
            ChoiceField("Historical version", versions.indexOfFirst { it.id == revisionId }.takeIf { it >= 0 }?.let { labels[it] } ?: "Choose version", labels) { revisionId = versions[labels.indexOf(it)].id }
            val revision = versions.firstOrNull { it.id == revisionId }
            val snapshot = revision?.let { RecipeSnapshotCodec.decode(it.snapshotJson) }
            var assets by remember(revisionId) { mutableStateOf(emptyList<RecipeAsset>()) }
            LaunchedEffect(revisionId) { assets = revisionId?.let { viewModel.recipes.assets(it) }.orEmpty() }
            if (snapshot != null) {
                Text("Previous cook · saved observations", style = MaterialTheme.typography.titleMedium)
                Text("${if (snapshot.publicSource?.relativeTiming == true) "Original date not shared" else historyTime(snapshot.smokingAnchorUtcMillis ?: snapshot.observations.minOfOrNull { it.occurredAtUtcMillis }, snapshot.observations.firstOrNull()?.timeZoneId)} · ${revision.changeNotes}")
                snapshot.rating?.let { Text("Recorded rating: $it") }
                if (snapshot.resultNotes.isNotBlank()) Text(snapshot.resultNotes)
                snapshot.targets.forEach { Text("Previous target · ${it.type.replace('_', ' ')}: ${it.value} ${it.unit}") }
                RecipeReferenceTimeline.heating(snapshot).forEachIndexed { index, interval ->
                    Text("Heating interval ${index + 1} · ${if (snapshot.publicSource?.relativeTiming == true) RecipeReferenceTimeline.relativeLabel(snapshot.smokingAnchorUtcMillis?.let { interval.startedAt - it }) else historyTime(interval.startedAt, null)} → ${interval.endedAt?.let { if (snapshot.publicSource?.relativeTiming == true) RecipeReferenceTimeline.relativeLabel(snapshot.smokingAnchorUtcMillis?.let { anchor -> it - anchor }) else historyTime(it, null) } ?: "end not recorded"}")
                }
                var actionsOnly by remember { mutableStateOf(true) }
                FilterChip(actionsOnly, { actionsOnly = !actionsOnly }, { Text(if (actionsOnly) "Major actions" else "All saved notes") })
                val observations = RecipeReferenceTimeline.events(snapshot).filter { !actionsOnly || it.action != null }
                if (observations.isEmpty()) Text("No ${if (actionsOnly) "major actions" else "observations"} saved. Switch filters to view available history.")
                observations.forEach { event ->
                    var expanded by remember(event.sourceEventId) { mutableStateOf(false) }
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        TextButton({ expanded = !expanded }) { Text("${RecipeReferenceTimeline.relativeLabel(event.offsetMillis)} · ${event.title}") }
                        if (expanded) {
                            Text(if (snapshot.publicSource?.relativeTiming == true) "Original date not shared" else historyTime(event.occurredAtUtcMillis, event.timeZoneId))
                            if (event.details.isNotBlank()) Text(event.details)
                            CookNoteMetadata.decode(event.noteMetadataJson)?.let { metadata ->
                                metadata.method?.let { Text("Method · ${CookingMethods.labels[it] ?: it}") }
                                metadata.durationMillis?.let { Text("Recorded duration · ${it / 60_000.0} min") }
                                metadata.ingredients.forEach { Text("${it.name} · ${it.amountText} ${it.amountUnit}") }
                            }
                            event.recordedTemperatures.forEach { measurement -> Text("${measurement.probeName} · ${measurement.measurementType.replace('_', ' ')} · ${measurement.value} ${measurement.unit} · ${if (snapshot.publicSource?.relativeTiming == true) RecipeReferenceTimeline.relativeLabel(snapshot.smokingAnchorUtcMillis?.let { measurement.measuredAtUtcMillis - it }) else historyTime(measurement.measuredAtUtcMillis, measurement.timeZoneId)}") }
                            TemperatureContext.decode(event.temperatureContextJson).forEach { Text("Recorded context · ${it.name}: ${it.value} ${it.unit}") }
                        }
                    } }
                }
                assets.forEach { asset ->
                    RecipePicture(viewModel.recipes.photoUri(asset.relativeFilePath))
                    if (asset.caption.isNotBlank()) Text(asset.caption)
                }
                val original = cooks.firstOrNull { it.cook.id == revision.sourceCookId }
                if (original != null) TextButton({ dishId = null; viewModel.openCook(original.cook.id) }) { Text("Open original meal context") }
                else Text("Original meal unavailable. Saved reference content remains available.")
                Text("This cook · actual actions", style = MaterialTheme.typography.titleMedium)
                val current = notePlanEvents(data.events).filter { (it.dishId == dishId || it.dishId == null) && it.action in CookNoteMetadata.actions }.sortedBy { it.at }
                val currentAnchor = CookReferenceCodec.anchor(current, dishId.orEmpty(), "food_on")
                if (current.isEmpty()) Text("No actions recorded for this dish yet.")
                current.distinctBy { it.id }.forEach { Text("${PlaybookCodec.actions[it.action] ?: it.action} · ${RecipeReferenceTimeline.relativeLabel(currentAnchor?.let { anchor -> it.at - anchor })}") }
                data.targets.filter { it.dishId == dishId }.forEach { Text("This cook target · ${it.targetType.replace('_', ' ')}: ${it.value} ${it.unit}") }
                Text("Other attempts", style = MaterialTheme.typography.titleMedium)
                cooks.filter { meal -> meal.cook.id != data.cook.id && meal.dishes.any { dish -> sources.any { it.dishId == dish.id && it.recipeId == source?.recipeId } } }.forEach { meal ->
                    TextButton({ dishId = null; viewModel.openCook(meal.cook.id) }) { Text("${meal.cook.title} · ${historyTime(meal.cook.startedAtUtcMillis, meal.cook.startedTimeZoneId)}") }
                }
                Text("Charts → Compare with a previous cook shows compatible recorded channels. Historical observations stay separate from this meal's editable timeline.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
private fun historyTime(time: Long?, zone: String?): String = time?.let {
    DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z").withZone(runCatching { ZoneId.of(zone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())).format(Instant.ofEpochMilli(it))
} ?: "Date unavailable"
