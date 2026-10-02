package com.pittech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.data.CookDetailData
import com.pittech.data.CookRecordingEntity
import com.pittech.data.CookStatus
import com.pittech.devices.CookTelemetryPolicy
import com.pittech.devices.PolarisMonitorState
import com.pittech.devices.GrillSamplingMode
import com.pittech.devices.GrillSamplingPolicy
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun GrillSamplingSelector(policy: GrillSamplingPolicy, enabled: Boolean = true, onSelect: (GrillSamplingPolicy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("grill-sampling")) { Text("Collect temperatures · ${policy.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GrillSamplingPolicy.INTERVALS.forEach { interval ->
                DropdownMenuItem(text = { Text("Every " + GrillSamplingPolicy.intervalLabel(interval)) }, onClick = {
                    onSelect(GrillSamplingPolicy(intervalMillis = interval)); open = false
                }, modifier = Modifier.testTag("grill-sampling-$interval"))
            }
            DropdownMenuItem(text = { Text("When I log something") }, onClick = {
                onSelect(policy.copy(mode = GrillSamplingMode.ON_LOG)); open = false
            }, modifier = Modifier.testTag("grill-sampling-on-log"))
        }
    }
}

@Composable
internal fun ProbeDishSelector(label: String, selected: String?, dishes: List<Pair<String, String>>, tag: String, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text("$label · ${dishes.firstOrNull { it.first == selected }?.second ?: "Unassigned"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { onSelect(null); open = false }, modifier = Modifier.testTag("$tag-none"))
            dishes.forEachIndexed { index, dish ->
                DropdownMenuItem(text = { Text(dish.second) }, onClick = { onSelect(dish.first); open = false }, modifier = Modifier.testTag("$tag-dish-$index"))
            }
        }
    }
}

@Composable
internal fun StartGrillRecordingPanel(state: PolarisMonitorState, enabled: Boolean, onEnabled: (Boolean) -> Unit, dishes: List<Pair<String, String>>, probe1: String?, probe2: String?, onProbe1: (String?) -> Unit, onProbe2: (String?) -> Unit, onGrill: (String) -> Unit, probe3: String? = null, probe4: String? = null, onProbe3: (String?) -> Unit = {}, onProbe4: (String?) -> Unit = {}, onSampling: (GrillSamplingPolicy) -> Unit = {}) {
    Card(Modifier.fillMaxWidth().testTag("cook-grill-setup")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Grill temperature recording", style = MaterialTheme.typography.titleMedium)
            if (!state.authenticated || state.devices.isEmpty()) Text("Connect your grill in Devices to record temperatures automatically. You can attach it after starting.", style = MaterialTheme.typography.bodySmall)
            else {
                state.devices.forEachIndexed { index, device ->
                    OutlinedButton(onClick = { onGrill(device.id) }, enabled = !state.busy && (state.lockedDeviceId == null || state.lockedDeviceId == device.id), modifier = Modifier.fillMaxWidth().testTag("cook-grill-$index")) {
                        Text((if (state.selectedDeviceId == device.id) "✓ " else "") + device.name)
                    }
                }
                Text(state.onlineLabel(System.currentTimeMillis()), style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(checked = enabled, onCheckedChange = onEnabled, enabled = state.selectedDevice != null && state.sessionSaved, modifier = Modifier.testTag("cook-record-grill"))
                    Text("Save grill temperatures with this cook", modifier = Modifier.padding(top = 12.dp))
                }
                if (enabled) {
                    GrillSamplingSelector(state.sampling, !state.busy && state.lockedDeviceId == null, onSampling)
                    RecordingPowerOptions(state.sampling.mode)
                    ProbeDishSelector("Probe 1", probe1, dishes, "cook-probe1", onProbe1)
                    if ((state.selectedDevice?.probeCount ?: 2) >= 2) ProbeDishSelector("Probe 2", probe2, dishes, "cook-probe2", onProbe2)
                    if ((state.selectedDevice?.probeCount ?: 2) >= 3) ProbeDishSelector("Probe 3", probe3, dishes, "cook-probe3", onProbe3)
                    if ((state.selectedDevice?.probeCount ?: 2) >= 4) ProbeDishSelector("Probe 4", probe4, dishes, "cook-probe4", onProbe4)
                }
            }
        }
    }
}

@Composable
internal fun CookGrillPanel(data: CookDetailData, state: PolarisMonitorState, running: Boolean, busy: Boolean, onAttach: () -> Unit, onResume: () -> Unit, onPause: () -> Unit, onStop: () -> Unit, onAssign: (String, String?) -> Unit, onSampling: (GrillSamplingPolicy) -> Unit = {}, onReadNow: () -> Unit = {}) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var assignmentsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L) } }
    val recording = data.recording
    if (recording == null) {
        if (data.cook.status != CookStatus.COMPLETED) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Automatic temperature recording", style = MaterialTheme.typography.titleMedium)
                Text(if (state.selectedDevice != null) "Attach ${state.selectedDevice!!.name} to this cook." else "Connect and select a grill in Devices, then attach it here.", style = MaterialTheme.typography.bodySmall)
                if (com.pittech.BuildConfig.CONTROLLER_TESTING_ENABLED && state.selectedDevice != null && state.authenticated) {
                    GrillSamplingSelector(state.sampling, !busy, onSampling)
                    RecordingPowerOptions(state.sampling.mode)
                }
                Button(onClick = onAttach, enabled = !busy && state.selectedDevice != null && state.authenticated && data.cook.status == CookStatus.ACTIVE, modifier = Modifier.testTag("cook-attach-grill")) { Text("Attach grill & record") }
            }
        }
        return
    }
    val matches = state.selectedDeviceId?.let(CookTelemetryPolicy::deviceKey) == recording.controllerKey
    val needsAttachment = recording.controllerKey.isBlank() || !matches
    val sampling = GrillSamplingPolicy.stored(recording.samplingMode, recording.samplingIntervalMillis)
    val loggingOnly = sampling.mode == GrillSamplingMode.ON_LOG
    val active = recording.status == CookRecordingEntity.RECORDING && (running || loggingOnly)
    val old = recording.lastReceivedAtUtcMillis == null || now - recording.lastReceivedAtUtcMillis > GrillSamplingPolicy.receiptWindow(sampling.intervalMillis) || recording.gapStartedAtUtcMillis != null || (matches && state.readingsAreOld(now))
    val sourceProbes = data.probes.filter { it.deviceId == recording.deviceId }.sortedBy { listOf("Chamber", "Setpoint", "Probe 1", "Probe 2", "Probe 3", "Probe 4").indexOf(it.name) }
    val latest = data.readings.filter { it.sourceDeviceId == recording.deviceId }.groupBy { it.probeId }.mapValues { it.value.maxByOrNull { row -> row.measuredAtUtcMillis } }
    Card(Modifier.fillMaxWidth().testTag("cook-grill-live")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (active && loggingOnly) "Collecting temperatures when you log" else if (active) "Recording grill temperatures" else if (recording.status == CookRecordingEntity.STOPPED) "Recorded grill temperatures" else "Temperature recording paused", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("cook-recording-status"))
            if (matches && data.cook.status != CookStatus.COMPLETED) Text(state.onlineLabel(now), style = MaterialTheme.typography.bodySmall)
            Text(recording.lastReceivedAtUtcMillis?.let { "Last cloud reading ${(now - it).coerceAtLeast(0L) / 1000}s ago" } ?: "Waiting for first reading", style = MaterialTheme.typography.bodySmall)
            if (old && !loggingOnly && data.cook.status != CookStatus.COMPLETED) Text("Older or unavailable readings · recording gaps remain visible", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            sourceProbes.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { probe ->
                        val reading = latest[probe.id]
                        Column(Modifier.weight(1f)) {
                            Text(probe.name, style = MaterialTheme.typography.labelLarge)
                            Text(if (reading?.qualityStatus == "valid") "${String.format(Locale.US, "%.0f", reading.value)} ${reading.unit}" else "Unavailable", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("cook-reading-${probe.name.replace(' ', '-').lowercase(Locale.ROOT)}"))
                            probe.assignedDishId?.let { id -> data.dishes.firstOrNull { it.id == id }?.let { Text(it.name, style = MaterialTheme.typography.bodySmall) } }
                        }
                    }
                }
            }
            Text("Times show cloud receipt. Available device timestamps are checked for freshness.", style = MaterialTheme.typography.bodySmall)
            if (com.pittech.BuildConfig.CONTROLLER_TESTING_ENABLED && data.cook.status != CookStatus.COMPLETED) {
                GrillSamplingSelector(sampling, !busy, onSampling)
                if (loggingOnly && active) OutlinedButton(onClick = onReadNow, enabled = !busy && matches && state.authenticated, modifier = Modifier.testTag("cook-grill-read-now")) { Text("Read grill now") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = if (active) onPause else if (needsAttachment) onAttach else onResume,
                        enabled = !busy && data.cook.status == CookStatus.ACTIVE && (active || !needsAttachment || (state.authenticated && state.sessionSaved && state.selectedDevice != null)),
                        modifier = Modifier.testTag("cook-recording-toggle")) { Text(if (active) "Pause recording" else if (needsAttachment) "Attach grill & record" else "Resume recording") }
                    TextButton(onClick = onStop, enabled = !busy && recording.status != CookRecordingEntity.STOPPED, modifier = Modifier.testTag("cook-recording-stop")) { Text("Stop recording") }
                }
                TextButton(onClick = { assignmentsOpen = !assignmentsOpen }, modifier = Modifier.testTag("cook-probe-assignments")) { Text(if (assignmentsOpen) "Hide probe assignments" else "Assign probes to dishes") }
                if (assignmentsOpen) sourceProbes.filter { it.measurementType == "food_probe" }.forEach { probe ->
                    ProbeDishSelector(probe.name, probe.assignedDishId, data.dishes.map { it.id to it.name }, "assign-${probe.name.replace(' ', '-').lowercase(Locale.ROOT)}") { onAssign(probe.id, it) }
                }
                if (!active || loggingOnly) Text(recording.message, style = MaterialTheme.typography.bodySmall)
                if (needsAttachment && !active) Text("Select a grill in Devices, then attach it here. Earlier readings keep their original grill and dish assignments.", style = MaterialTheme.typography.bodySmall)
                RecordingPowerOptions(sampling.mode)
            }
            val end = data.readings.maxOfOrNull { it.measuredAtUtcMillis } ?: now
            val recent = data.readings.filter { it.sourceDeviceId == recording.deviceId && it.measuredAtUtcMillis >= end - 3_600_000L }
            if (recent.any { it.qualityStatus == "valid" }) TemperatureChart(recent, cookStartTimes = mapOf(data.cook.id to data.cook.startedAtUtcMillis), dishNames = data.dishes.associate { it.id to it.name }, chartHeight = 140.dp)
        }
    }
}
