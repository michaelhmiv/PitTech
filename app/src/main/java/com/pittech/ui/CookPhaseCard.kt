package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.data.*
import com.pittech.domain.CookPhases
import kotlinx.coroutines.delay

@Composable internal fun CookPhaseCard(data: CookDetailData, onFoodOn: (String?) -> Unit, onPreheat: () -> Unit, onAdopt: (Long?) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(data.cook.status) { while (data.cook.status != CookStatus.COMPLETED) { delay(1000); now = System.currentTimeMillis() } }
    val events = notePlanEvents(data.events)
    val phases = CookPhases.all(events, data.dishes.map { it.id }, data.cook.startedAtUtcMillis, data.cook.endedAtUtcMillis ?: now)
    val legacy = data.lifecycle?.timingMode != "explicit_phases"
    var showAdopt by rememberSaveable(data.cook.id) { mutableStateOf(false) }
    var adoptionTime by rememberSaveable(data.cook.id) { mutableLongStateOf(data.cook.startedAtUtcMillis) }
    Card(Modifier.fillMaxWidth().testTag("cook-phase-card")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (data.cook.status == CookStatus.COMPLETED) "Finished" else CookPhases.summary(phases), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("cook-physical-phase"))
            if (legacy) {
                Text("Legacy timer · saved start and pause history retained.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showAdopt = true }, modifier = Modifier.testTag("cook-adopt-food-on")) { Text("Use actual Food on timing") }
            }
            phases.forEach { phase ->
                val name = data.dishes.firstOrNull { it.id == phase.dishId }?.name ?: "Whole cook"
                Text("$name · ${CookPhases.label(phase.phase)}", style = MaterialTheme.typography.titleMedium)
                Text("Cooking ${duration(phase.cookingMillis)} · Rest ${duration(phase.restMillis)} · Hold ${duration(phase.holdMillis)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("dish-clocks-${phase.dishId ?: "whole"}"))
                if (phase.phase == "prep" && data.cook.status != CookStatus.COMPLETED) {
                    Button(onClick = { onFoodOn(phase.dishId) }, modifier = Modifier.testTag("dish-food-on-${phase.dishId ?: "whole"}")) { Text("Food on · $name") }
                }
            }
            if (data.cook.status != CookStatus.COMPLETED) {
                TextButton(onClick = onPreheat, modifier = Modifier.testTag("cook-preheat")) { Text("Log preheat started") }
                Text("Preheat can overlap preparation. Pause controls affect recording and guidance; food stays in its current phase.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showAdopt) AlertDialog(onDismissRequest = { showAdopt = false }, title = { Text("Use actual Food on timing?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This deliberately replaces the displayed legacy timer with time on heat. Original start and pause records remain saved.")
            if (events.none { it.action == "food_on" }) DateTimePickerField("Actual Food on", adoptionTime, { adoptionTime = it }, "cook-adopt-time")
            else Text("The saved dish milestones will be used. Correct their times in the timeline first if needed.")
        } }, confirmButton = { TextButton(onClick = { onAdopt(if (events.any { it.action == "food_on" }) null else adoptionTime); showAdopt = false }) { Text("Use Food on timing") } },
        dismissButton = { TextButton(onClick = { showAdopt = false }) { Text("Keep legacy timer") } })
}
private fun duration(millis: Long) = "${millis / 3_600_000}h ${(millis / 60_000) % 60}m"
