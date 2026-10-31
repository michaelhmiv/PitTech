package com.pittech.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pittech.data.CookStatus
import com.pittech.data.CookWithDishes
import com.pittech.data.InsightsSnapshot
import com.pittech.data.insightArchive
import com.pittech.data.notePlanEvents
import java.text.DateFormat
import java.util.Date

/**
 * TURN-IN (Slice 2, Area 02): the Record Book. The history lane rows, the
 * always-on totals band, the statistics composition (cells, cooks-by-smoker
 * bars, rating buckets, recently finished), and the side-by-side comparison
 * grid are composed from the A2 frames (`history-populated`, `stats`,
 * `compare-select`, `compare-view`); every control, filter, and test tag
 * keeps its existing home and behavior — search, favorites / rated filters,
 * the smoker choice, compare selection (2–4 cooks, in-progress included
 * since Slice 6), the factual difference lines, and the aligned chart are
 * the app's own.
 */
@Composable
internal fun InsightsScreen(
    data: InsightsSnapshot,
    onOpenCook: (String) -> Unit,
    onStartCook: () -> Unit,
    modifier: Modifier = Modifier,
    onProEntry: ((com.pittech.billing.ProPlacement) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var highlyRated by remember { mutableStateOf(false) }
    var smoker by remember { mutableStateOf("All smokers") }
    var comparing by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    // Flowing Slice 6 (5.1): the compare alignment stepper's per-cook
    // display offsets, in elapsed milliseconds. Display-only state.
    val compareOffsets = remember { mutableStateMapOf<String, Long>() }
    val completed = data.cooks.filter { it.cook.status == CookStatus.COMPLETED }
    val durationValues = completed.mapNotNull { cook ->
        cook.cook.endedAtUtcMillis?.let { cookHeatingMinutes(cook) }
    }
    val ratings = data.results.filter { it.resultType == "overall_rating" }.mapNotNull { it.numericValue }
    val favoriteIds = data.results.filter { it.resultType == "favorite" && it.numericValue == 1.0 }.map { it.cookId }.toSet()
    val filtered = data.cooks.filter { item ->
        val searchable = buildString {
            append(item.cook.title).append(' ').append(item.cook.smokerName.orEmpty())
            item.dishes.forEach { append(" ${it.name} ${it.cut.orEmpty()} ${it.weightValue ?: ""} ${it.weightUnit.orEmpty()} ${it.prepNotes.orEmpty()}") }
            data.ingredients.filter { it.cookId == item.cook.id }.forEach { append(" ${it.name} ${it.brand.orEmpty()}") }
            data.results.filter { it.cookId == item.cook.id && it.resultType in setOf("method", "tags") }.forEach { append(" ${it.textValue.orEmpty()}") }
        }
        (!favoritesOnly || item.cook.id in favoriteIds) && (smoker == "All smokers" || item.cook.smokerName == smoker) &&
            (!highlyRated || data.results.any { it.cookId == item.cook.id && it.resultType == "overall_rating" && (it.numericValue ?: 0.0) >= 4.0 }) &&
            query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.all { searchable.contains(it, true) }
    }
    // Slice 6 (5.3): an in-progress cook can be compared too — its total
    // is a §4.1 projection and wears the PROJECTED tag, never fact.
    val selected = data.cooks.filter { it.cook.id in selectedIds }
    val tokens = pitTechTokens()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TurnInBannerText("Learn from your cooks", style = PitTechTextStyles.bannerTitle)
        Text(
            if (data.cooks.isEmpty()) "Your cook history and readings will appear here."
            else "Find a cook, revisit the results, or compare what you tried.",
            style = PitTechTextStyles.body,
            color = tokens.inkMuted,
        )

        if (data.cooks.isEmpty()) {
            // PL-25: the Insights empty is the shared StateCopy wording.
            TurnInCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurnInBannerText(EmptyStateCopy.INSIGHTS_TITLE)
                    Text(EmptyStateCopy.INSIGHTS_BODY, style = PitTechTextStyles.body)
                    Button(onClick = onStartCook, modifier = Modifier.fillMaxWidth()) { Text("Start a cook") }
                }
            }
        } else {
            var showStatistics by rememberSaveable { mutableStateOf(false) }
            // The totals band (A2 `history-populated`): the book at a
            // glance — finished cooks, recorded readings, timeline notes,
            // and the average cook — always on, above the search.
            val noteCount = data.cooks.sumOf { item -> item.events.count { it.eventType == "note" } }
            TurnInCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TotalsCell("Finished", completed.size.toString(), Modifier.weight(1f))
                    TotalsCell("Readings", data.readings.size.toString(), Modifier.weight(1f))
                    TotalsCell("Notes", noteCount.toString(), Modifier.weight(1f))
                    TotalsCell("Avg. cook", durationValues.takeIf { it.isNotEmpty() }?.average()?.let(::formatMinutes) ?: "—", Modifier.weight(1f))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a cook or dish") },
                placeholder = { Text("Search cooks & dishes…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("insights-search"),
            )
            Text("Search cut, smoker, weight, rub, method or tags.", style = MaterialTheme.typography.bodySmall, color = tokens.inkMuted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("Favorites") })
                FilterChip(highlyRated, { highlyRated = !highlyRated }, label = { Text("Rated 4+") })
                SmokerFilterChip(
                    value = smoker,
                    options = listOf("All smokers") + data.cooks.mapNotNull { it.cook.smokerName }.distinct(),
                    onSelect = { smoker = it },
                )
                if (data.cooks.size >= 2) {
                    FilterChip(comparing, { comparing = !comparing; selectedIds.clear() }, label = { Text(if (comparing) "Done" else "Select cooks") })
                }
            }
            if (data.cooks.size >= 2) {
                Text("Compare cooks", style = PitTechTextStyles.bodyEmphasis)
            }

            if (selected.size >= 2) {
                SectionCard(title = "Selected comparison") {
                    val archive = remember(data) { insightArchive(data.cooks, data.readings, data.results) }
                    val compareFacts = selected.map { compareCookFacts(it, data, System.currentTimeMillis(), archive) }
                    // 5.4: one generated sentence, only when the recorded
                    // history supports it; otherwise it is simply absent.
                    remember(data) { compareTeachingSentence(data) }?.let { sentence ->
                        Text(sentence, style = PitTechTextStyles.bodyEmphasis, modifier = Modifier.testTag("compare-teaching-sentence"))
                    }
                    ComparisonGrid(selected, data, compareFacts)
                    val ids = selected.map { it.cook.id }.toSet()
                    // 5.1: curves align at each cook's own food-on moment.
                    val startTimes = compareFacts.associate { it.cookId to it.foodOnUtcMillis }
                    val names = selected.associate { it.cook.id to it.cook.title }
                    comparisonDifferenceLines(selected, data.readings.filter { it.cookId in ids }, data.results.filter { it.cookId in ids }).forEachIndexed { index, line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("comparison-line-$index"))
                    }
                    TemperatureChart(data.readings.filter { it.cookId in ids }, cookNames = names, cookStartTimes = startTimes, cookOffsetMillis = compareOffsets.filterKeys { it in ids }, connectionGaps = data.connectionGaps.filter { it.cookId in ids })
                    Text("Only recorded readings are shown. Empty periods remain empty.", style = MaterialTheme.typography.bodySmall)
                    // 5.1: the offset stepper — nudge a cook whose food-on
                    // mark sits off the others. Display-only; the recorded
                    // times and every figure above never change.
                    TurnInMetaLabel("Align at food-on · nudge a curve")
                    compareFacts.forEach { facts ->
                        val offset = compareOffsets[facts.cookId] ?: 0L
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(facts.title, style = PitTechTextStyles.body, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { compareOffsets[facts.cookId] = (offset - 300_000L).coerceAtLeast(-7_200_000L) },
                                modifier = Modifier.testTag("compare-offset-minus-${facts.cookId}"),
                            ) { Text("−5m") }
                            Text(compareOffsetLabel(offset), style = PitTechTextStyles.monoReadout, modifier = Modifier.testTag("compare-offset-value-${facts.cookId}"))
                            TextButton(
                                onClick = { compareOffsets[facts.cookId] = (offset + 300_000L).coerceAtMost(7_200_000L) },
                                modifier = Modifier.testTag("compare-offset-plus-${facts.cookId}"),
                            ) { Text("+5m") }
                        }
                    }
                    // 5.3 + §4.1: a projected total always states its basis;
                    // a withheld projection says why, in the engine's words.
                    compareFacts.filter { it.inProgress }.forEach { facts ->
                        when {
                            facts.projected != null -> Text(
                                "${facts.title} total is projected ${facts.projected.basisText}.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("compare-projection-basis-${facts.cookId}"),
                            )
                            facts.reachedTarget -> Text(
                                "${facts.title} has reached its recorded target.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("compare-projection-basis-${facts.cookId}"),
                            )
                            facts.projectionWithheldReason != null -> Text(
                                "No projection for ${facts.title} yet — ${facts.projectionWithheldReason}.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("compare-projection-note-${facts.cookId}"),
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnInBannerText("No matches")
                        Text("No cooks or dishes match those filters.", style = PitTechTextStyles.body)
                        OutlinedButton(onClick = { query = ""; favoritesOnly = false; highlyRated = false; smoker = "All smokers" }) {
                            Text("Clear filters")
                        }
                    }
                }
            } else {
                TurnInMetaLabel("Record book · newest first")
                filtered.forEach { item ->
                    HistoryCookRow(
                        item = item,
                        data = data,
                        isFavorite = item.cook.id in favoriteIds,
                        comparing = comparing,
                        selectedForCompare = item.cook.id in selectedIds,
                        onClick = {
                            if (comparing) {
                                if (item.cook.id in selectedIds) selectedIds.remove(item.cook.id)
                                else if (selectedIds.size < 4) selectedIds.add(item.cook.id)
                            } else onOpenCook(item.cook.id)
                        },
                        onToggleCompare = {
                            if (item.cook.id in selectedIds) selectedIds.remove(item.cook.id)
                            else if (selectedIds.size < 4) selectedIds.add(item.cook.id)
                        },
                    )
                }
            }
            if (comparing) Text("Choose two to four cooks to compare — a cook still on the pit shows its projected total, tagged as such.", style = MaterialTheme.typography.bodyMedium, color = tokens.inkMuted)
            TextButton(onClick = { showStatistics = !showStatistics }, modifier = Modifier.testTag("insights-statistics")) { Text(if (showStatistics) "Hide statistics" else "Cook statistics") }
            if (showStatistics) {
                InsightsStatistics(data, completed, durationValues, ratings, favoriteIds, onOpenCook)
            }

        }
        if (onProEntry != null) {
            ProInsightsEntries(onEntry = onProEntry)
        }
    }
}

/** One figure of the totals band / statistics grid: mono value over a
 * tracked-caps label (A2 `history-populated` band, `stats` cells). */
@Composable
private fun TotalsCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = PitTechTextStyles.monoReadout, color = pitTechTokens().ink)
        TurnInMetaLabel(label)
    }
}

/** The smoker choice as a filter-row chip (A2 `history-populated`): the
 * current choice rides the chip; the menu keeps the app's option list. */
@Composable
private fun SmokerFilterChip(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != "All smokers",
            onClick = { expanded = true },
            label = { Text(if (value == "All smokers") "Smoker" else value) },
            modifier = Modifier.semantics { contentDescription = "Smoker: $value" },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/**
 * One Record Book lane row (A2 `history-populated`): banner cook name,
 * verdict stars, the favorite heart on the mono meta line, dishes, the
 * smoker · method line, and the recorded final temperature. In compare
 * mode the row carries the selection checkbox instead of the figures.
 */
@Composable
private fun HistoryCookRow(
    item: CookWithDishes,
    data: InsightsSnapshot,
    isFavorite: Boolean,
    comparing: Boolean,
    selectedForCompare: Boolean,
    onClick: () -> Unit,
    onToggleCompare: () -> Unit,
) {
    val tokens = pitTechTokens()
    val rating = data.results.firstOrNull { it.cookId == item.cook.id && it.dishId == null && it.resultType == "overall_rating" }?.numericValue
        ?: data.results.firstOrNull { it.cookId == item.cook.id && it.resultType == "overall_rating" }?.numericValue
    val finalTemp = data.results.firstOrNull { it.cookId == item.cook.id && it.resultType == "final_temperature" }
    val method = data.results.firstOrNull { it.cookId == item.cook.id && it.resultType == "method" }?.textValue?.takeIf { it.isNotBlank() }
    val duration = item.cook.endedAtUtcMillis?.let { cookHeatingMinutes(item) }
    TurnInCard(
        modifier = Modifier.fillMaxWidth().testTag("insights-cook-" + item.cook.id),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TurnInBannerText(item.cook.title)
                Text(
                    buildString {
                        if (isFavorite) append("♥ ")
                        append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.cook.startedAtUtcMillis)))
                        duration?.let { append(" · ").append(formatMinutes(it)) }
                        append(" · ").append(item.cook.status.replaceFirstChar { it.uppercase() })
                    },
                    style = PitTechTextStyles.monoMeta,
                    color = tokens.inkMuted,
                )
                Text(
                    item.dishes.joinToString { it.cut?.takeIf(String::isNotBlank) ?: it.name }.ifBlank { "No dishes recorded" },
                    style = PitTechTextStyles.body,
                )
                val smokerMethod = listOfNotNull(item.cook.smokerName?.takeIf { it.isNotBlank() }, method).joinToString(" · ")
                if (smokerMethod.isNotBlank()) {
                    Text(smokerMethod, style = PitTechTextStyles.body, color = tokens.inkMuted)
                }
                data.results.firstOrNull { it.cookId == item.cook.id && !it.textValue.isNullOrBlank() }?.textValue?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                }
            }
            if (comparing) {
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = selectedForCompare, onCheckedChange = { onToggleCompare() })
            } else {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (rating != null) TurnInStars(rating)
                    finalTemp?.numericValue?.let { value ->
                        Text(
                            "${formatResultNumber(value)}${finalTemp.unit?.replace("°F", "°")?.replace("°C", "°") ?: "°"} FINAL",
                            style = PitTechTextStyles.monoMeta,
                            color = tokens.inkMuted,
                        )
                    }
                }
            }
        }
    }
}

/** The 5.1 stepper's readout: how far a curve sits from food-on alignment. */
private fun compareOffsetLabel(offsetMillis: Long): String {
    val minutes = offsetMillis / 60_000
    return when {
        minutes > 0 -> "+${minutes}m"
        minutes < 0 -> "−${-minutes}m"
        else -> "Food-on"
    }
}

/**
 * The side-by-side comparison grid (A2 `compare-view`): one column per
 * cook. Flowing Slice 6 (Spread 05) puts the recorded inputs (§4.2 —
 * wrap, rub, spritz, setpoint) beside the outcomes (time on, the §4.1
 * stall, final temperature, time to wrap, rest, verdict, detail ratings).
 * A cook predating §4.2 reads "Not recorded" in the input rows (5.5)
 * while its outcomes still come from its readings; an in-progress cook's
 * time on is its §4.1 projection and wears the PROJECTED tag (5.3).
 * Missing facts stay an honest dash; nothing is estimated.
 */
@Composable
private fun ComparisonGrid(selected: List<CookWithDishes>, data: InsightsSnapshot, facts: List<CompareCookFacts>) {
    val tokens = pitTechTokens()
    fun result(cookId: String, type: String) = data.results.firstOrNull { it.cookId == cookId && it.dishId == null && it.resultType == type }
        ?: data.results.firstOrNull { it.cookId == cookId && it.resultType == type }
    fun wrapOffset(item: CookWithDishes): String {
        val plan = notePlanEvents(item.events)
        val anchor = plan.firstOrNull { it.action == "food_on" }?.at ?: item.cook.startedAtUtcMillis
        val wrap = plan.firstOrNull { it.action == "wrap" }?.at ?: return "—"
        return formatMinutes(((wrap - anchor).coerceAtLeast(0) / 60_000).toDouble())
    }
    fun timeOn(item: CookWithDishes, cookFacts: CompareCookFacts): String = when {
        !cookFacts.inProgress -> item.cook.endedAtUtcMillis?.let { formatMinutes(cookHeatingMinutes(item)) } ?: "—"
        cookFacts.projected != null -> "${formatMinutes(cookFacts.projected.earliestMinutes)} – ${formatMinutes(cookFacts.projected.latestMinutes)}"
        cookFacts.reachedTarget -> "${formatMinutes(cookFacts.elapsedMinutes)} · at target"
        else -> "${formatMinutes(cookFacts.elapsedMinutes)} so far"
    }
    val detailRows = listOf(
        "Bark" to "bark", "Tenderness" to "tenderness", "Juiciness" to "juiciness",
        "Smoke" to "smoke", "Seasoning" to "seasoning",
    ).filter { (_, type) -> selected.any { result(it.cook.id, type)?.numericValue != null } }
    val rows: List<Pair<String, List<String>>> = buildList {
        add("Cook" to selected.map { it.cook.title })
        add("Wrap" to facts.map { it.wrapText })
        add("Rub" to facts.map { it.rubText })
        add("Spritz" to facts.map { it.spritzText })
        add("Setpoint" to facts.map { it.setpointText })
        add("Time on" to selected.mapIndexed { index, item -> timeOn(item, facts[index]) })
        add("Stall" to facts.map { cookFacts -> cookFacts.stallMinutes?.let { formatMinutes(it) } ?: "—" })
        add("Final temp" to selected.map { item -> result(item.cook.id, "final_temperature")?.numericValue?.let { "${formatResultNumber(it)}°" } ?: "—" })
        add("To wrap" to selected.map { wrapOffset(it) })
        add("Rest" to selected.map { item -> result(item.cook.id, "rest_minutes")?.numericValue?.let { formatMinutes(it) } ?: "—" })
        add("Overall" to selected.map { item -> result(item.cook.id, "overall_rating")?.numericValue?.let { "${formatResultNumber(it)} ★" } ?: "—" })
        detailRows.forEach { (label, type) ->
            add(label to selected.map { item -> result(item.cook.id, type)?.numericValue?.let { formatResultNumber(it) } ?: "—" })
        }
    }
    val headerColors = listOf(tokens.emberText, tokens.cobalt, tokens.attentionText, tokens.ink)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(86.dp))
            selected.forEachIndexed { index, item ->
                Text(
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.cook.startedAtUtcMillis)).uppercase(),
                    style = PitTechTextStyles.bannerCard,
                    color = headerColors[index % headerColors.size],
                    modifier = Modifier.weight(1f),
                )
            }
        }
        rows.forEach { (label, values) ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.ruling))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = PitTechTextStyles.body, color = tokens.inkMuted, modifier = Modifier.width(86.dp))
                values.forEachIndexed { index, value ->
                    // 5.3: the projected total wears its tag in the cell —
                    // it is never rendered as if it were a recorded fact.
                    if (label == "Time on" && facts.getOrNull(index)?.projected != null) {
                        Column(Modifier.weight(1f)) {
                            Text(value, style = PitTechTextStyles.monoReadout)
                            Text(
                                "PROJECTED",
                                style = PitTechTextStyles.metaLabel,
                                color = tokens.attentionText,
                                modifier = Modifier.testTag("compare-projected-tag-${facts[index].cookId}"),
                            )
                        }
                    } else {
                        Text(
                            value,
                            style = if (label == "Cook") PitTechTextStyles.bodyEmphasis else PitTechTextStyles.monoReadout,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The statistics composition (A2 `stats`): the figure cells, cooks by
 * smoker as ranked bars, the rating buckets, and the recently finished
 * lane. Every figure is computed from the same snapshot the free tools
 * already read.
 */
@Composable
private fun InsightsStatistics(
    data: InsightsSnapshot,
    completed: List<CookWithDishes>,
    durationValues: List<Double>,
    ratings: List<Double>,
    favoriteIds: Set<String>,
    onOpenCook: (String) -> Unit,
) {
    val tokens = pitTechTokens()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCell("Completed", completed.size.toString(), Modifier.weight(1f))
            StatCell("Active", (data.cooks.size - completed.size).toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCell("Readings", data.readings.size.toString(), Modifier.weight(1f))
            StatCell("Notes", data.cooks.sumOf { item -> item.events.count { it.eventType == "note" } }.toString(), Modifier.weight(1f))
            StatCell("Avg. time", durationValues.takeIf { it.isNotEmpty() }?.average()?.let(::formatMinutes) ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCell("Avg. rating", ratings.takeIf { it.isNotEmpty() }?.average()?.let { "%.1f / 5".format(it) } ?: "—", Modifier.weight(1f))
        }
        val bySmoker = data.cooks.groupBy { it.cook.smokerName?.takeIf { name -> name.isNotBlank() } ?: "Unnamed smoker" }
            .entries.sortedByDescending { it.value.size }
        if (bySmoker.isNotEmpty()) {
            TurnInCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurnInMetaLabel("Cooks by smoker")
                    val barColors = listOf(tokens.ember, tokens.cobalt, tokens.marigold)
                    bySmoker.forEachIndexed { index, (smoker, cooks) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(smoker, style = PitTechTextStyles.body, modifier = Modifier.weight(1f))
                                Text(cooks.size.toString(), style = PitTechTextStyles.monoReadout)
                            }
                            Box(
                                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(tokens.surfaceRaised),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(cooks.size.toFloat() / bySmoker.maxOf { it.value.size }.toFloat())
                                        .height(8.dp)
                                        .background(barColors[index % barColors.size]),
                                )
                            }
                        }
                    }
                }
            }
        }
        val cookRatings = data.results.filter { it.dishId == null && it.resultType == "overall_rating" }.mapNotNull { it.numericValue }
        if (cookRatings.isNotEmpty()) {
            TurnInCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurnInMetaLabel("Ratings")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        StatCell("★ 1–2", cookRatings.count { it < 3 }.toString(), Modifier.weight(1f), boxed = false)
                        StatCell("★ 3", cookRatings.count { it in 3.0..<4.0 }.toString(), Modifier.weight(1f), boxed = false)
                        StatCell("★ 4", cookRatings.count { it in 4.0..<5.0 }.toString(), Modifier.weight(1f), boxed = false)
                        StatCell("★ 5", cookRatings.count { it >= 5.0 }.toString(), Modifier.weight(1f), boxed = false)
                    }
                }
            }
        }
        val recent = completed.sortedByDescending { it.cook.endedAtUtcMillis ?: it.cook.startedAtUtcMillis }.take(3)
        if (recent.isNotEmpty()) {
            TurnInMetaLabel("Recently finished")
            recent.forEach { item ->
                HistoryCookRow(
                    item = item,
                    data = data,
                    isFavorite = item.cook.id in favoriteIds,
                    comparing = false,
                    selectedForCompare = false,
                    onClick = { onOpenCook(item.cook.id) },
                    onToggleCompare = {},
                )
            }
        }
    }
}

/** A boxed statistic cell (A2 `stats`): scoreboard-style value over its
 * label, on the raised surface with the ruling border. The label keeps the
 * app's literal wording (the statistics figures are asserted by name);
 * the meta-label style carries the tracked-caps look. */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier, boxed: Boolean = true) {
    val tokens = pitTechTokens()
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = PitTechTextStyles.bannerCard, color = tokens.ink)
            Text(label, style = PitTechTextStyles.metaLabel, color = tokens.inkMuted)
        }
    }
    if (boxed) {
        Box(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.surfaceRaised)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) { content() }
    } else {
        Box(modifier) { content() }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    // TURN-IN card (§4): token surface, ruling border, banner-face title.
    Card(
        colors = CardDefaults.cardColors(containerColor = pitTechTokens().surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, pitTechTokens().ruling),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TurnInBannerText(title)
            content()
        }
    }
}

private fun formatMinutes(minutes: Double): String {
    val hours = (minutes / 60).toInt()
    val remaining = minutes.toInt() % 60
    return if (hours > 0) "${hours}h ${remaining}m" else "${remaining}m"
}

private fun formatResultNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun cookHeatingMinutes(cook: com.pittech.data.CookWithDishes): Double = if (cook.lifecycles.firstOrNull()?.timingMode == "explicit_phases") {
    com.pittech.domain.CookPhases.heatingMillis(com.pittech.domain.CookPhases.all(com.pittech.data.notePlanEvents(cook.events), cook.dishes.map { it.id }, cook.cook.startedAtUtcMillis, cook.cook.endedAtUtcMillis ?: System.currentTimeMillis())) / 60_000.0
} else ((cook.cook.endedAtUtcMillis ?: System.currentTimeMillis()) - cook.cook.startedAtUtcMillis).coerceAtLeast(0) / 60_000.0
