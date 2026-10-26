package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.data.DishEntity
import com.pittech.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MealActivityTimers(cookId: String, dishes: List<DishEntity>, active: Boolean, viewModel: CooksViewModel) {
    val timers by remember(cookId) { viewModel.activities.observe(cookId) }.collectAsStateWithLifecycle(emptyList())
    var expanded by rememberSaveable(cookId) { mutableStateOf(false) }
    var title by rememberSaveable(cookId) { mutableStateOf("") }
    var dishId by rememberSaveable(cookId) { mutableStateOf<String?>(null) }
    var method by rememberSaveable(cookId) { mutableStateOf<String?>(null) }
    var target by rememberSaveable(cookId) { mutableStateOf("") }
    var draft by remember { mutableStateOf<CookNoteDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(timers) { while (timers.any { it.status == "running" }) { now = System.currentTimeMillis(); delay(1000) } }
    fun act(block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { block(); error = null } catch (e: Exception) { error = ErrorCopy.failureText(e, "That timer change couldn't be saved. The timer is unchanged — try again.") } finally { busy = false } } }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("task-timers")) {
        Text("Task timers" + timers.count { it.status == "running" }.takeIf { it > 0 }?.let { " ($it running)" }.orEmpty())
    }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        timers.filter { it.status in setOf("running", "stopped") }.forEach { timer ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(timer.title)
                val elapsed = ((timer.stoppedAtUtcMillis ?: now) - timer.startedAtUtcMillis).coerceAtLeast(0) / 1000
                Text("${elapsed / 60}m ${elapsed % 60}s" + timer.desiredDurationMillis?.let { " · target ${it / 60_000}m" }.orEmpty())
                Row {
                    TextButton(onClick = { act { draft = viewModel.activities.stop(timer.id) } }, enabled = !busy) { Text(if (timer.status == "running") "Stop & save duration" else "Save duration") }
                    TextButton(onClick = { act { viewModel.activities.cancel(timer.id) } }, enabled = !busy) { Text("Dismiss") }
                }
            } }
        }
        if (active) {
            OutlinedTextField(title, { title = it }, label = { Text("Task name") }, modifier = Modifier.fillMaxWidth().testTag("task-timer-title"))
            SimpleDropdownField("For", dishes.firstOrNull { it.id == dishId }?.name ?: "Whole meal", listOf("Whole meal") + dishes.map { it.name }, { label -> dishId = dishes.firstOrNull { it.name == label }?.id })
            SimpleDropdownField("Method", CookingMethods.labels[method] ?: "Not set", listOf("Not set") + CookingMethods.labels.values, { label -> method = CookingMethods.labels.entries.firstOrNull { it.value == label }?.key })
            OutlinedTextField(target, { target = it }, label = { Text("Target minutes (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { act { viewModel.activities.start(cookId, dishId, title, method, target.toLongOrNull()?.times(60_000)); title = ""; target = "" } },
                enabled = !busy && title.isNotBlank() && (target.isBlank() || target.toLongOrNull()?.let { it in 1..43200 } == true), modifier = Modifier.testTag("task-timer-start")) { Text("Start timer") }
            Text("Start and stop times stay saved when you leave. Saving creates one note.", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    draft?.let { entry -> CookNoteComposer(entry, dishes, "°F", viewModel, false, false,
        onChange = { draft = it }, onClose = { draft = null }, onSaved = { draft = null }) }
}
