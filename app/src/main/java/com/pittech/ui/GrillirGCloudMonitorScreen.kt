package com.pittech.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.pittech.devices.GrillProvider
import com.pittech.devices.PitBossTelemetry
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pittech.BuildConfig
import com.pittech.FeedbackApi
import com.pittech.FeedbackDiagnosticReport
import com.pittech.FeedbackKind
import com.pittech.FeedbackRequest
import com.pittech.FeedbackSubmitResult
import com.pittech.devices.AndroidPolarisSessionStore
import com.pittech.devices.PolarisMonitorEngine
import com.pittech.devices.PolarisMonitorPolicy
import com.pittech.devices.PolarisPhase
import com.pittech.devices.PolarisSample
import com.pittech.devices.PrimePolarisApi
import com.pittech.devices.PrimePolarisMonitor
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID

@Composable
internal fun ControllerDevicesScreen(modifier: Modifier = Modifier) {
    var bluetooth by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { bluetooth = false }, modifier = Modifier.weight(1f).testTag("devices-cloud")) { Text("Wi-Fi monitor") }
            OutlinedButton(onClick = { bluetooth = true }, modifier = Modifier.weight(1f).testTag("devices-bluetooth")) { Text("Bluetooth discovery") }
        }
        if (bluetooth) ControllerDiagnosticsScreen(modifier = Modifier.weight(1f))
        else GrillirGCloudMonitorScreen(modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun GrillirGCloudMonitorScreen(
    modifier: Modifier = Modifier,
    engineOverride: PolarisMonitorEngine? = null,
    submitter: ControllerReportSubmitter = ControllerReportSubmitter(FeedbackApi::submitAsync),
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val engine = remember(context, engineOverride) { engineOverride ?: (context.applicationContext as com.pittech.PitTechApplication).grillMonitor }
    val state by engine.state.collectAsStateWithLifecycle()
    var email by remember(state.provider) { mutableStateOf("") }
    var code by remember(state.provider) { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var requirementsExpanded by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<FeedbackDiagnosticReport?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var reportMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(engine, engineOverride) { onDispose { if (engineOverride != null) engine.close() } }
    LaunchedEffect(engine, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (engineOverride != null) engine.setForeground(true)
            try { while (true) { now = System.currentTimeMillis(); delay(1000) } }
            finally { if (engineOverride != null) engine.setForeground(false) }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wi-Fi grill monitor", style = MaterialTheme.typography.headlineSmall)
        SimpleDropdownField("Connection", state.provider.label,
            if (state.lockedDeviceId != null) listOf(state.provider.label) else GrillProvider.entries.map { it.label },
            { label -> engine.selectProvider(GrillProvider.entries.first { it.label == label }) }, testTag = "cloud-provider")
        Text("Read-only temperatures. Pair your grill with Wi-Fi in its supported app first.")
        if (state.provider != GrillProvider.GRILLIRG) Text("Experimental · hardware confirmation pending", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { requirementsExpanded = !requirementsExpanded }) { Text(if (requirementsExpanded) "Hide connection requirements" else "Connection requirements") }
        if (requirementsExpanded) Text(state.provider.setup, style = MaterialTheme.typography.bodySmall)
        Text(state.message, modifier = Modifier.testTag("cloud-message"))
        if (state.busy || state.phase == PolarisPhase.RESTORING) CircularProgressIndicator()

        if (!state.authenticated) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val emailCode = state.provider.emailCode
                    val pitBoss = state.provider == GrillProvider.PIT_BOSS
                    Text(if (emailCode) "Sign in with an email code" else if (pitBoss) "Connect your controller" else "Sign in to Traeger", style = MaterialTheme.typography.titleMedium)
                    Text(if (emailCode) "Use the email used in GrillirG. If that app displaces this sign-in, you can use a separate account your grill is shared to."
                        else if (pitBoss) "Use the complete controller ID, not a grill model or serial number. The optional password is the controller password, not your Pit Boss account password."
                        else "Use your Traeger email and password. Account passwords are used only for sign-in; saved tokens are encrypted on this phone. Federated sign-in and MFA challenges are not supported.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.take(254) }, label = { Text(if (pitBoss) "Controller ID" else state.provider.label + " account email") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (pitBoss) KeyboardType.Ascii else KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("cloud-email"), enabled = !state.busy,
                    )
                    if (emailCode) OutlinedButton(
                        onClick = { engine.requestCode(email.trim()) },
                        enabled = !state.busy && state.phase != PolarisPhase.RESTORING && PrimePolarisApi.validEmail(email),
                        modifier = Modifier.testTag("cloud-request-code"),
                    ) { Text("Send sign-in code") }
                    OutlinedTextField(
                        value = code, onValueChange = { code = if (emailCode) it.filter(Char::isDigit).take(6) else it.take(if (pitBoss) 256 else 1024) },
                        label = { Text(if (emailCode) "Six-digit email code" else if (pitBoss) "Controller password (optional)" else "Traeger password") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (emailCode) KeyboardType.Number else KeyboardType.Password),
                        visualTransformation = if (emailCode) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("cloud-code"), enabled = !state.busy,
                    )
                    val valid = if (pitBoss) runCatching { PitBossTelemetry.controllerId(email) }.isSuccess else
                        PrimePolarisApi.validEmail(email) && if (emailCode) Regex("[0-9]{6}").matches(code) else code.isNotEmpty()
                    Button(
                        onClick = { val secret = code; code = ""; engine.signIn(email.trim(), secret) },
                        enabled = !state.busy && state.phase != PolarisPhase.RESTORING && valid,
                        modifier = Modifier.testTag("cloud-sign-in"),
                    ) { Text(if (pitBoss) "Connect controller" else "Sign in & find my grill") }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = engine::reloadDevices, enabled = !state.busy, modifier = Modifier.testTag("cloud-reload-devices")) { Text("Find grills") }
                TextButton(onClick = engine::signOut, modifier = Modifier.testTag("cloud-sign-out")) { Text("Sign out") }
            }
            if (!state.sessionSaved) Text("This sign-in could not be saved. You will need to sign in again next visit.")
            state.devices.forEachIndexed { index, device ->
                OutlinedButton(
                    onClick = { engine.selectDevice(device.id) }, enabled = !state.busy && (state.lockedDeviceId == null || state.lockedDeviceId == device.id),
                    modifier = Modifier.fillMaxWidth().testTag("cloud-device-$index"),
                ) { Text((if (state.selectedDeviceId == device.id) "✓ " else "") + device.name) }
            }
            state.selectedDevice?.let { device ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleLarge)
                        Text(state.onlineLabel(now), modifier = Modifier.testTag("cloud-grill-status"))
                        Text("Backend: " + (state.lastApiSuccessMillis?.let { "last response ${age(now, it)}" } ?: "no response yet"))
                        Text("Grill status fetched: " + (state.statusFetchedAtMillis?.let { age(now, it) } ?: "not reported"))
                        Text("Readings fetched: " + (state.latest?.fetchedAtMillis?.let { age(now, it) } ?: "not reported"))
                        if (state.readingsAreOld(now)) Text("Readings are unavailable, older, or may be cached.", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cloud-stale"))
                        state.nextPollAtMillis?.let { Text("Next check in ${((it - now).coerceAtLeast(0) + 999) / 1000}s") }
                        Button(onClick = engine::refresh, enabled = !state.busy, modifier = Modifier.testTag("cloud-refresh")) { Text("Refresh now") }
                    }
                }

                val sample = state.latest
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingCard("Chamber", PolarisMonitorPolicy.temperature(sample, "furnaceTempMeasured"), Modifier.weight(1f).testTag("cloud-chamber"))
                    ReadingCard("Target", PolarisMonitorPolicy.temperature(sample, "furnaceTempSetting"), Modifier.weight(1f))
                }
                (1..device.probeCount.coerceIn(1, 4)).toList().chunked(2).forEach { channels ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        channels.forEach { channel -> ReadingCard("Probe " + channel, PolarisMonitorPolicy.temperature(sample, "probeP" + channel + "Measured"), Modifier.weight(1f)) }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Controller status", style = MaterialTheme.typography.titleMedium)
                        Text("Operation: " + PolarisMonitorPolicy.runningLabel(sample?.payload?.values?.get("runningStatus")))
                        sample?.payload?.values?.get("vendorSystemStatus")?.let { Text("Vendor status code: ${it.toInt()}") }
                        sample?.payload?.values?.get("vendorPowerState")?.let { Text("Controller power: " + switchLabel(it)) }
                        sample?.payload?.values?.get("pelletLevel")?.let { Text("Pellet level: ${it.toInt()}%") }
                        Text("Smoke mode: " + switchLabel(sample?.payload?.values?.get("smokeMode")))
                        sample?.payload?.values?.get("smokeLevel")?.let { Text("Smoke level: ${it.toInt()}") }
                        Text("Probe 1 target: " + PolarisMonitorPolicy.temperature(sample, "probeP1Setting"))
                        Text("Probe 2 target: " + PolarisMonitorPolicy.temperature(sample, "probeP2Setting"))
                        Text("Alarm entries returned: ${sample?.payload?.alarmCount ?: "not reported"}")
                        if (sample?.payload?.alarmCount?.let { it > 0 } == true) Text("Check the controller or its supported app for alarm details.", color = MaterialTheme.colorScheme.error)
                        Text("Fetch time is when PitTech received the data. " + (sample?.payload?.reportedAtMillis?.let { "Device report: ${age(now, it)}." } ?: "The backend's device sample age is unknown."), style = MaterialTheme.typography.bodySmall)
                    }
                }
                ChamberHistory(state.samples)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Controller information", style = MaterialTheme.typography.titleMedium)
                        Text("Brand: ${device.brand ?: "not reported"}")
                        Text("Grill: ${device.grillModel ?: "not reported"}")
                        Text("Controller: ${device.controllerModel ?: "not reported"}")
                        Text("Firmware: ${device.firmware ?: "not reported"}")
                    }
                }
            }
        }

        if (state.exchanges.isNotEmpty()) {
            TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.testTag("cloud-details")) { Text(if (detailsExpanded) "Hide connection details" else "Show connection details") }
            if (detailsExpanded) Text(PolarisMonitorPolicy.report(state, now), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    reportMessage = null
                    report = FeedbackDiagnosticReport(
                        referenceCode = "CLOUD-" + UUID.randomUUID().toString().take(8).uppercase(),
                        occurredAtUtc = Instant.ofEpochMilli(now).toString(), source = state.provider.label + " cloud monitor",
                        summary = state.provider.label + " connection and controller observations", details = PolarisMonitorPolicy.report(state, now),
                    )
                }, modifier = Modifier.testTag("cloud-review-report"),
            ) { Text("Review connection report") }
        }
        reportMessage?.let { Text(it, modifier = Modifier.testTag("cloud-report-result")) }
    }

    report?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!submitting) report = null },
            title = { Text("Review public support report") },
            text = {
                Column(Modifier.height(350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Submitting creates a public GitHub issue. Email, passwords, codes, tokens, device identities and raw responses are excluded.")
                    Text(preview.details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cloud-report-preview"))
                }
            },
            confirmButton = {
                Button(onClick = {
                    submitting = true
                    submitter.submit(
                        FeedbackRequest(
                            kind = FeedbackKind.DEVICE_DIAGNOSTIC, title = state.provider.label + " cloud connection report",
                            description = "User-reviewed read-only backend monitoring diagnostics.", appVersion = BuildConfig.VERSION_NAME,
                            androidVersion = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                            device = android.os.Build.MODEL, diagnosticReport = preview,
                        ),
                    ) { result ->
                        submitting = false
                        reportMessage = when (result) {
                            is FeedbackSubmitResult.Success -> "Report submitted" + (result.issueNumber?.let { " as GitHub issue #$it." } ?: ".")
                            is FeedbackSubmitResult.Failure -> result.message
                        }
                        if (result is FeedbackSubmitResult.Success) report = null
                    }
                }, enabled = !submitting, modifier = Modifier.testTag("cloud-submit-report")) { Text(if (submitting) "Submitting…" else "Submit report") }
            },
            dismissButton = { TextButton(onClick = { report = null }, enabled = !submitting, modifier = Modifier.testTag("cloud-cancel-report")) { Text("Cancel") } },
        )
    }
}

@Composable private fun ReadingCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable private fun ChamberHistory(samples: List<PolarisSample>) {
    val unit = samples.lastOrNull()?.payload?.values?.get("tempUnit")
    val points = samples.filter { it.payload.values["tempUnit"] == unit && it.payload.values["furnaceTempMeasured"] != null }
    if (points.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent chamber readings", style = MaterialTheme.typography.titleMedium)
            Text("${points.size} observations retained for this visit", style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                val values = points.map { it.payload.values.getValue("furnaceTempMeasured") }
                val minimum = values.minOrNull()!! - 5
                val range = (values.maxOrNull()!! + 5 - minimum).coerceAtLeast(10.0)
                val first = points.first().fetchedAtMillis
                val duration = (points.last().fetchedAtMillis - first).coerceAtLeast(1)
                fun point(index: Int) = Offset(((points[index].fetchedAtMillis - first).toFloat() / duration) * size.width, size.height - ((values[index] - minimum) / range).toFloat() * size.height)
                for (index in 1 until points.size) {
                    if (points[index].fetchedAtMillis - points[index - 1].fetchedAtMillis <= 45_000 &&
                        points[index].payload.values["onlineStatus"] != 1.0 && points[index - 1].payload.values["onlineStatus"] != 1.0)
                        drawLine(color, point(index - 1), point(index), strokeWidth = 3.dp.toPx())
                }
            }
            Text("Min: ${PolarisMonitorPolicy.temperature(points.minByOrNull { it.payload.values.getValue("furnaceTempMeasured") }, "furnaceTempMeasured")} · Max: ${PolarisMonitorPolicy.temperature(points.maxByOrNull { it.payload.values.getValue("furnaceTempMeasured") }, "furnaceTempMeasured")}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun age(now: Long, then: Long) = "${((now - then).coerceAtLeast(0)) / 1000}s ago"
private fun switchLabel(value: Double?) = when (value?.toInt()) { 0 -> "Off"; 1 -> "On"; null -> "Not reported"; else -> "Status ${value.toInt()} (unmapped)" }
