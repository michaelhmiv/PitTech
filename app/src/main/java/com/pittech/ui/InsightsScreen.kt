package com.pittech.ui

import androidx.compose.foundation.clickable
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
fun InsightsScreen(
    data: InsightsSnapshot,
    onOpenCook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var comparing by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val completed = data.cooks.filter { it.cook.status == CookStatus.COMPLETED }
    val durationValues = completed.mapNotNull { cook ->
        cook.cook.endedAtUtcMillis?.let { (it - cook.cook.startedAtUtcMillis).coerceAtLeast(0) / 60_000.0 }
    }
    val ratings = data.results.filter { it.resultType == "overall_rating" }.mapNotNull { it.numericValue }
    val filtered = data.cooks.filter { item ->
        query.isBlank() || item.cook.title.contains(query, ignoreCase = true) ||
            item.dishes.any { it.name.contains(query, true) || it.cut.orEmpty().contains(query, true) }
    }
    val selected = completed.filter { it.cook.id in selectedIds }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Learn from your cooks", style = MaterialTheme.typography.headlineMedium)
        Text("Compare the records you have saved. A comparison describes the cooks; it does not prove what caused a result.", style = MaterialTheme.typography.bodyLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Completed", completed.size.toString(), Modifier.weight(1f))
            MetricCard("Avg. time", durationValues.takeIf { it.isNotEmpty() }?.average()?.let(::formatMinutes) ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Avg. rating", ratings.takeIf { it.isNotEmpty() }?.average()?.let { "%.1f / 5".format(it) } ?: "—", Modifier.weight(1f))
            MetricCard("Readings", data.readings.size.toString(), Modifier.weight(1f))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Find a cook or dish") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Compare completed cooks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { comparing = !comparing; selectedIds.clear() }) { Text(if (comparing) "Done" else "Select cooks") }
        }

        if (selected.size >= 2) {
            SectionCard(title = "Selected comparison") {
                selected.forEach { item ->
                    val duration = item.cook.endedAtUtcMillis?.let { (it - item.cook.startedAtUtcMillis) / 60_000.0 }
                    val score = data.results.firstOrNull { it.cookId == item.cook.id && it.resultType == "overall_rating" }?.numericValue
                    Text(item.cook.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${duration?.let(::formatMinutes) ?: "Duration unavailable"} · rating ${score?.let { "%.1f / 5".format(it) } ?: "not recorded"}", style = MaterialTheme.typography.bodyLarge)
                    Text(item.dishes.joinToString { it.cut?.takeIf(String::isNotBlank) ?: it.name }.ifBlank { "No dishes recorded" }, style = MaterialTheme.typography.bodyMedium)
                }
                val ids = selected.map { it.cook.id }.toSet()
                val startTimes = selected.associate { it.cook.id to it.cook.startedAtUtcMillis }
                val names = selected.associate { it.cook.id to it.cook.title }
                TemperatureChart(data.readings.filter { it.cookId in ids }, cookNames = names, cookStartTimes = startTimes)
                Text("Only recorded readings are shown. Empty periods remain empty.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (filtered.isEmpty()) {
            Text("No cooks match that search.", style = MaterialTheme.typography.bodyLarge)
        } else {
            filtered.forEach { item ->
                val rating = data.results.firstOrNull { it.cookId == item.cook.id && it.resultType == "overall_rating" }?.numericValue
                val selectedForCompare = item.cook.id in selectedIds
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (comparing && item.cook.status == CookStatus.COMPLETED) {
                            if (selectedForCompare) selectedIds.remove(item.cook.id)
                            else if (selectedIds.size < 4) selectedIds.add(item.cook.id)
                        } else onOpenCook(item.cook.id)
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.cook.title, style = MaterialTheme.typography.titleLarge)
                            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.cook.startedAtUtcMillis)), style = MaterialTheme.typography.bodyMedium)
                            Text(item.dishes.joinToString { it.cut?.takeIf(String::isNotBlank) ?: it.name }.ifBlank { "No dishes recorded" }, style = MaterialTheme.typography.bodyLarge)
                            Text("${item.cook.status.replaceFirstChar { it.uppercase() }} · ${data.readings.count { it.cookId == item.cook.id }} readings · ${rating?.let { "rating %.1f/5".format(it) } ?: "no rating"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

private fun formatMinutes(minutes: Double): String {
    val hours = (minutes / 60).toInt()
    val remaining = minutes.toInt() % 60
    return if (hours > 0) "${hours}h ${remaining}m" else "${remaining}m"
}
