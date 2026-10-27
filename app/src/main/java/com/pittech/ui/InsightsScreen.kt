package com.pittech.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pittech.data.CookStatus
import com.pittech.data.InsightsSnapshot
import java.text.DateFormat
import java.util.Date

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
    val selected = completed.filter { it.cook.id in selectedIds }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Learn from your cooks", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (data.cooks.isEmpty()) "Your cook history and readings will appear here."
            else "Find a cook, revisit the results, or compare what you tried.",
            style = MaterialTheme.typography.bodyLarge,
        )

        if (data.cooks.isEmpty()) {
            // PL-25: the Insights empty is the shared StateCopy wording.
            SectionCard(EmptyStateCopy.INSIGHTS_TITLE) {
                Text(EmptyStateCopy.INSIGHTS_BODY, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onStartCook, modifier = Modifier.fillMaxWidth()) { Text("Start a cook") }
            }
        } else {
            var showStatistics by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a cook or dish") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("insights-search"),
            )
            Text("Search cut, smoker, weight, rub, method or tags.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("Favorites") })
                FilterChip(highlyRated, { highlyRated = !highlyRated }, label = { Text("Rated 4+") })
            }
            ChoiceField("Smoker", smoker, listOf("All smokers") + data.cooks.mapNotNull { it.cook.smokerName }.distinct()) { smoker = it }
            if (completed.size >= 2) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Compare completed cooks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { comparing = !comparing; selectedIds.clear() }) { Text(if (comparing) "Done" else "Select cooks") }
                }
            }

            if (selected.size >= 2) {
                SectionCard(title = "Selected comparison") {
                    selected.forEach { item ->
                        val duration = item.cook.endedAtUtcMillis?.let { cookHeatingMinutes(item) }
                        val score = data.results.firstOrNull { it.cookId == item.cook.id && it.resultType == "overall_rating" }?.numericValue
                        Text(item.cook.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${duration?.let(::formatMinutes) ?: "Duration unavailable"} · rating ${score?.let { "%.1f / 5".format(it) } ?: "not recorded"}", style = MaterialTheme.typography.bodyLarge)
                        Text(item.dishes.joinToString { it.cut?.takeIf(String::isNotBlank) ?: it.name }.ifBlank { "No dishes recorded" }, style = MaterialTheme.typography.bodyMedium)
                    }
                    val ids = selected.map { it.cook.id }.toSet()
                    val startTimes = selected.associate { it.cook.id to it.cook.startedAtUtcMillis }
                    val names = selected.associate { it.cook.id to it.cook.title }
                    comparisonDifferenceLines(selected, data.readings.filter { it.cookId in ids }, data.results.filter { it.cookId in ids }).forEachIndexed { index, line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("comparison-line-$index"))
                    }
                    TemperatureChart(data.readings.filter { it.cookId in ids }, cookNames = names, cookStartTimes = startTimes, connectionGaps = data.connectionGaps.filter { it.cookId in ids })
                    Text("Only recorded readings are shown. Empty periods remain empty.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (filtered.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No cooks or dishes match those filters.", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { query = ""; favoritesOnly = false; highlyRated = false; smoker = "All smokers" }) {
                        Text("Clear filters")
                    }
                }
            } else {
                filtered.forEach { item ->
                    val rating = data.results.firstOrNull { it.cookId == item.cook.id && it.resultType == "overall_rating" }?.numericValue
                    val selectedForCompare = item.cook.id in selectedIds
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("insights-cook-" + item.cook.id).clickable {
                            if (comparing && item.cook.status == CookStatus.COMPLETED) {
                                if (selectedForCompare) selectedIds.remove(item.cook.id)
                                else if (selectedIds.size < 4) selectedIds.add(item.cook.id)
                            } else onOpenCook(item.cook.id)
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text((if (item.cook.id in favoriteIds) "★ " else "") + item.cook.title, style = MaterialTheme.typography.titleLarge)
                                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.cook.startedAtUtcMillis)), style = MaterialTheme.typography.bodyMedium)
                                Text(item.dishes.joinToString { it.cut?.takeIf(String::isNotBlank) ?: it.name }.ifBlank { "No dishes recorded" }, style = MaterialTheme.typography.bodyLarge)
                                Text(item.cook.status.replaceFirstChar { it.uppercase() } + (rating?.let { " · " + "%.1f/5".format(it) } ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                data.results.firstOrNull { it.cookId == item.cook.id && !it.textValue.isNullOrBlank() }?.textValue?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                                }
                            }
                            if (comparing && item.cook.status == CookStatus.COMPLETED) {
                                Spacer(Modifier.width(8.dp))
                                Checkbox(checked = selectedForCompare, onCheckedChange = {
                                    if (selectedForCompare) selectedIds.remove(item.cook.id)
                                    else if (selectedIds.size < 4) selectedIds.add(item.cook.id)
                                })
                            } else {
                                TextButton(onClick = { onOpenCook(item.cook.id) }) { Text("Open") }
                            }
                        }
                    }
                }
            }
            if (comparing) Text("Choose two to four completed cooks to compare.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showStatistics = !showStatistics }, modifier = Modifier.testTag("insights-statistics")) { Text(if (showStatistics) "Hide statistics" else "Cook statistics") }
            if (showStatistics) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Completed", completed.size.toString(), Modifier.weight(1f))
                MetricCard("Avg. time", durationValues.takeIf { it.isNotEmpty() }?.average()?.let(::formatMinutes) ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Avg. rating", ratings.takeIf { it.isNotEmpty() }?.average()?.let { "%.1f / 5".format(it) } ?: "—", Modifier.weight(1f))
                MetricCard("Readings", data.readings.size.toString(), Modifier.weight(1f))
            }

            }

        }
        if (onProEntry != null) {
            ProInsightsEntries(onEntry = onProEntry)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
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

private fun cookHeatingMinutes(cook: com.pittech.data.CookWithDishes): Double = if (cook.lifecycles.firstOrNull()?.timingMode == "explicit_phases") {
    com.pittech.domain.CookPhases.heatingMillis(com.pittech.domain.CookPhases.all(com.pittech.data.notePlanEvents(cook.events), cook.dishes.map { it.id }, cook.cook.startedAtUtcMillis, cook.cook.endedAtUtcMillis ?: System.currentTimeMillis())) / 60_000.0
} else ((cook.cook.endedAtUtcMillis ?: System.currentTimeMillis()) - cook.cook.startedAtUtcMillis).coerceAtLeast(0) / 60_000.0
