@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.CooksViewModel
import com.pittech.data.*
import com.pittech.domain.*
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun guideSources(records: List<CompanionRecord>) = records.filter { it.kind == "recipe_attempt" }.mapNotNull { record ->
    val raw = JSONObject(record.payload)
    raw.optJSONObject("guide")?.let { raw.getString("dishId") to PinnedRecipeGuide.decode(it) }
}.toMap()
internal fun guideState(records: List<CompanionRecord>) = records.firstOrNull { it.kind == "guide_presentation" }?.let { GuidePresentation.decode(it.payload) } ?: GuidePresentation()

@Composable internal fun RecipeGuideControl(data: CookDetailData, records: List<CompanionRecord>, viewModel: CooksViewModel, remindersActive: Boolean) {
    val sources = remember(records) { guideSources(records) }; val state = remember(records) { guideState(records) }; val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(data.cook.id) { runCatching { viewModel.guide.ensure(data.cook.id) }.onFailure { error = ErrorCopy.failureText(it, "The guide couldn't be updated. Your cook is unchanged — try again.") } }
    if (sources.isEmpty() && records.none { it.kind == "recipe_attempt" }) return
    fun save(value: GuidePresentation) { scope.launch { runCatching { viewModel.guide.save(data.cook.id, value) }.onFailure { error = ErrorCopy.failureText(it, "The guide couldn't be updated. Your cook is unchanged — try again.") } } }
    val tokens = pitTechTokens()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Show guide", style = PitTechTextStyles.bodyEmphasis, modifier = Modifier.weight(1f))
            Switch(state.visible, { save(state.copy(visible = it)) }, Modifier.testTag("show-guide"))
        }
        if (!state.visible && remindersActive) Text("Reminders active · use Reminders to change them", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted, modifier = Modifier.testTag("guide-reminders-active"))
        if (state.visible) TextButton({ choosing = true }, Modifier.testTag("guide-scope")) { Text("Guide dishes & channels") }
        // A3 `dark-26-guide-channels-error`: a failed guide refresh is a
        // flag strip; the last saved setup stays in place underneath it.
        error?.let { TurnInStatusStrip(it, tokens.flag) }
    }
    if (choosing) ModalBottomSheet(onDismissRequest = { choosing = false }) {
        Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TurnInSheetKicker("Recipe guide")
            TurnInBannerText("Guide dishes & channels", style = PitTechTextStyles.bannerTitle)
            Text("Recorded facts and instructions stay separate from your actual log. Different weights or methods are not used to predict finish time.", style = PitTechTextStyles.body)
            data.dishes.filter { it.id in sources }.forEach { dish ->
                val selected = state.dishIds ?: sources.keys
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dish.id in selected, { checked -> save(state.copy(dishIds = if (checked) selected + dish.id else selected - dish.id)) }, Modifier.testTag("guide-dish-${dish.id}"))
                    Text(dish.name, style = PitTechTextStyles.bodyEmphasis)
                }
                val rows = RecipeGuideProjection.rows(sources.getValue(dish.id)).distinctBy { RecipeGuideProjection.channel(dish.id, it) }
                rows.forEach { row ->
                    val key = RecipeGuideProjection.channel(dish.id, row)
                    val role = RecipeGuideProjection.role(row.getString("measurementType"))
                    val eligible = data.readings.filter { RecipeGuideProjection.role(it.measurementType) == role && (it.dishId == dish.id || it.dishId == null && role != "food") }.distinctBy(TemperaturePlot::identity)
                    val labels = listOf("Separate reference channel") + eligible.map { "${it.probeName} · ${it.measurementType} · ${(it.probeId ?: it.id).takeLast(6)}" }
                    val chosen = eligible.indexOfFirst { TemperaturePlot.identity(it) == state.channels[key] }
                    ChoiceField("Author · ${row.getString("probeName")} · $role · ${(if (row.isNull("probeId")) row.getString("id") else row.getString("probeId")).takeLast(6)}", labels[chosen + 1], labels) { label ->
                        val mapped = labels.indexOf(label) - 1
                        save(state.copy(channels = if (mapped < 0) state.channels - key else state.channels + (key to TemperaturePlot.identity(eligible[mapped]))))
                    }
                }
            }
            TextButton({ choosing = false }) { Text("Done") }
        }
    }
}

@Composable internal fun RecipeGuideMilestones(data: CookDetailData, records: List<CompanionRecord>, compact: Boolean = false, onFullGuide: () -> Unit = {}) {
    val state = guideState(records); if (!state.visible) return
    val sources = remember(records) { guideSources(records) }
    data.dishes.filter { it.id in sources && (state.dishIds == null || it.id in state.dishIds) }.forEach { dish ->
        val guide = sources.getValue(dish.id)
        val working = records.first { it.kind == "recipe_attempt" && JSONObject(it.payload).getString("dishId") == dish.id }.let { RecipeAttemptCodec.snapshot(it.payload) }
        val anchor = RecipeGuideProjection.currentAnchor(data, dish.id)
        val current = notePlanEvents(data.events).filter { it.dishId == null || it.dishId == dish.id }.sortedBy { it.at }
        var expanded by remember(dish.id) { mutableStateOf(!compact) }; var count by remember(dish.id) { mutableIntStateOf(50) }
        val tokens = pitTechTokens()
        TurnInCard(Modifier.fillMaxWidth().testTag("guide-reference-${dish.id}")) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TurnInBannerText("Guide · ${dish.name}")
            if (working.dish.cut != guide.snapshot.dish.cut || working.dish.weightText != guide.snapshot.dish.weightText || working.dish.weightUnit != guide.snapshot.dish.weightUnit) TurnInStatusStrip("Your cut or weight differs from the source. Recorded timing is context, not a finish prediction.", tokens.cobalt)
            TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide instructions" else "Instructions · ${working.instructions.size}") }
            if (expanded) working.instructions.forEach { step -> Text("Instruction · ${step.title}${if (step.details.isBlank()) "" else ": ${step.details}"}", style = PitTechTextStyles.body) }
            if (anchor == null) Text("Record actual Food on for this dish to align the guide. Preparation offsets are retained.", style = PitTechTextStyles.body)
            val observations = RecipeReferenceTimeline.events(guide.recordedSnapshot)
            val displayed = if (compact) observations.take(3) else observations.take(count)
            displayed.forEach { source ->
                val ordinal = observations.takeWhile { it.sourceEventId != source.sourceEventId }.count { source.action != null && it.action == source.action }
                val actual = source.action?.let { action -> current.filter { it.action == action }.getOrNull(ordinal) }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Author · ${source.title} · ${RecipeReferenceTimeline.relativeLabel(source.offsetMillis)}", style = PitTechTextStyles.bodyEmphasis)
                    Text(actual?.let { "Your cook · ${PlaybookCodec.actions[it.action] ?: it.action} · ${RecipeReferenceTimeline.relativeLabel(anchor?.let { a -> it.at - a })}" } ?: "Your cook · no matching action recorded", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                }
            }
            if (compact && observations.size > displayed.size) TextButton(onFullGuide) { Text("View all ${observations.size} milestones in Timeline") }
            if (!compact && observations.size > count) TextButton({ count += 50 }) { Text("Show more recorded milestones (${observations.size - count} remaining)") }
            if (observations.isEmpty()) Text("No recorded milestones. Available instructions still work.", style = PitTechTextStyles.body)
            if (RecipeGuideProjection.rows(guide).isEmpty()) Text("No source temperatures recorded. No reference curve is drawn.", style = PitTechTextStyles.body, color = tokens.inkMuted)
        } }
    }
}

internal fun alignedGuide(data: CookDetailData, records: List<CompanionRecord>): AlignedGuide {
    val state = guideState(records); if (!state.visible) return AlignedGuide()
    val sources = guideSources(records)
    val aligned = data.dishes.filter { it.id in sources && (state.dishIds == null || it.id in state.dishIds) }.map { dish ->
        val guide = sources.getValue(dish.id)
        val projected = RecipeGuideProjection.aligned(guide, dish.id, dish.name, RecipeGuideProjection.currentAnchor(data, dish.id))
        val mappings = RecipeGuideProjection.rows(guide).mapNotNull { row ->
            val mapped = state.channels[RecipeGuideProjection.channel(dish.id, row)]
            val role = RecipeGuideProjection.role(row.getString("measurementType"))
            data.readings.firstOrNull { TemperaturePlot.identity(it) == mapped && RecipeGuideProjection.role(it.measurementType) == role && (it.dishId == dish.id || it.dishId == null && role != "food") }
                ?.let { "guide:${dish.id}:${row.getString("id")}" to TemperaturePlot.identity(it) }
        }.toMap()
        projected.copy(matches = mappings)
    }
    return AlignedGuide(aligned.flatMap { it.readings }, aligned.flatMap { it.gaps }, aligned.flatMap { it.originalTimes.entries }.associate { it.toPair() }, aligned.flatMap { it.matches.entries }.associate { it.toPair() })
}
