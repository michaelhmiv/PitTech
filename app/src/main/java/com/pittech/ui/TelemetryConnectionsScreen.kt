package com.pittech.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel
import com.pittech.HomeAssistantAuthorizationActivity
import com.pittech.PitTechApplication
import com.pittech.data.CookDetailData
import com.pittech.data.CookStatus
import com.pittech.devices.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private val providerNames = linkedMapOf("combustion" to "Combustion", "home_assistant" to "Home Assistant", "fireboard" to "FireBoard", "flame_boss" to "Flame Boss", "meater" to "MEATER")

/**
 * PL-05 (#172): Devices mid-cook is ONE sheet with a visible 3-step stepper —
 * Connect → Attach → Assign — identical from Cook Detail and from More.
 * Attach/assign edits live in the runtime as drafts, so dismissing the sheet
 * never throws them away; a successful attach consumes the draft.
 */
@Composable
internal fun TelemetryConnectionsScreen(viewModel: CooksViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runtime = viewModel.telemetry
    val profiles by runtime.store.profiles.collectAsStateWithLifecycle()
    val states by runtime.states.collectAsStateWithLifecycle()
    val drafts by runtime.assignmentDrafts.collectAsStateWithLifecycle()
    val discoveryInFlight by runtime.discoveryInFlight.collectAsStateWithLifecycle()
    val discoverySettled by runtime.discoverySettled.collectAsStateWithLifecycle()
    val meals by viewModel.cooks.collectAsStateWithLifecycle()
    val active = meals.filter { it.cook.status == CookStatus.ACTIVE }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(initialDevicesStep(profiles.isNotEmpty())) }
    var assignProfileId by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<TelemetryProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // PL-06: OAuth/connect failures render inside the Connect sheet with the
    // server URL, the form intact — never as a silent or whole-flow failure.
    var dialogError by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var enableBackground by remember { mutableStateOf<TelemetryProfile?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enableBackground?.let { if (granted) runtime.update(it.copy(background = true, monitoring = true)) else error = "Allow notifications to keep background monitoring visible." }
        enableBackground = null
    }
    fun background(profile: TelemetryProfile) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            enableBackground = profile; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else runtime.update(profile.copy(background = true, monitoring = true))
    }
    fun attachDraft(profile: TelemetryProfile, advance: Boolean) {
        val draft = drafts[profile.id] ?: seedAssignmentDraft(profile, active.firstOrNull()?.cook?.id)
        val source = profile.sources.firstOrNull { it.deviceId == draft.sourceId } ?: return
        val cook = draft.cookId ?: return
        val dishes = active.firstOrNull { it.cook.id == cook }?.dishes.orEmpty()
        val mapped = source.copy(channels = source.channels.map { it.copy(kind = draft.roles[it.id] ?: it.kind) })
        val assignments = mapped.channels.filter { it.kind == TelemetryKind.FOOD }.associate { it.id to (if (draft.dishes.containsKey(it.id)) draft.dishes[it.id] else dishes.singleOrNull()?.id) }
        scope.launch {
            busy = true
            try {
                runtime.attach(profile, cook, mapped, assignments)
                if (advance) { assignProfileId = profile.id; step = 3 }
            } catch (_: Exception) { error = "Unable to attach this device. Check that the meal is active." }
            finally { busy = false }
        }
    }
    // PL-06: the authorization activity returns ONLY the code (or an error,
    // or a plain cancel). The single token exchange happens in the runtime.
    val oauth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pending
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val code = result.data?.getStringExtra(HomeAssistantAuthorizationActivity.EXTRA_CODE)
                if (p != null && code != null) scope.launch {
                    connecting = true; busy = true
                    try { runtime.connectHomeAssistant(p, code); add = false; pending = null; dialogError = null; step = 2 }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { dialogError = "Home Assistant sign-in could not finish (${e.message ?: "connection failed"}). Server: ${p.endpoint}" }
                    finally { connecting = false; busy = false }
                } else if (p != null) dialogError = "Home Assistant did not return an authorization code. Server: ${p.endpoint}"
            }
            HomeAssistantAuthorizationActivity.RESULT_ERROR -> {
                dialogError = result.data?.getStringExtra(HomeAssistantAuthorizationActivity.EXTRA_ERROR)
                    ?: p?.let { "Home Assistant sign-in failed. Server: ${it.endpoint}" }
                    ?: "Home Assistant sign-in failed."
            }
            else -> pending = null // Plain cancel: no error, the form simply stays as it was.
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Temperature connections · Beta", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("telemetry-connections"))
        Text("Read-only connections. Your accounts and temperatures stay on this phone. Manual cooking and logging work without a connection.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Connect", 2 to "Attach", 3 to "Assign").forEach { (number, label) ->
                if (step == number) Button(onClick = { step = number }, modifier = Modifier.weight(1f).testTag("telemetry-step-$number")) { Text("$number · $label") }
                else OutlinedButton(onClick = { step = number }, modifier = Modifier.weight(1f).testTag("telemetry-step-$number")) { Text("$number · $label") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (step == 1) {
            profiles.forEach { profile ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(profile.label + " · Beta", style = MaterialTheme.typography.titleMedium)
                        Text(states[profile.id] ?: "Saved connection", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { scope.launch { busy = true; try { runtime.connect(profile, "", "") } catch (_: Exception) { dialogError = null; error = "Reconnect by entering your account credentials."; pending = profile; add = true } finally { busy = false } } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                        TextButton(onClick = { scope.launch { runtime.remove(profile) } }, enabled = !busy) { Text("Forget connection") }
                    }
                }
            }
            if (profiles.isEmpty()) Text("No connections yet. Add one below, then attach it to a cook on the next step.")
        }
        if (step == 2) {
            if (profiles.isEmpty()) Text("Connect a connection first (step 1), then attach it here.")
            profiles.forEach { profile ->
                val draft = drafts[profile.id] ?: seedAssignmentDraft(profile, active.firstOrNull()?.cook?.id)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(profile.label + " · Beta", style = MaterialTheme.typography.titleMedium)
                        Text(states[profile.id] ?: "Saved connection", style = MaterialTheme.typography.bodySmall)
                        profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { source ->
                            Text(source.label + " · " + (states[profile.id + ":" + source.deviceId] ?: if (profile.monitoring) "Waiting for readings" else "Paused"))
                            TextButton(onClick = { scope.launch { runtime.detachSource(profile, source) } }) { Text("Detach " + source.label) }
                        }
                        if (profile.sources.isEmpty()) Text(devicesEmptyStateText(profile.id in discoveryInFlight, profile.id in discoverySettled))
                        profile.sources.forEach { source -> OutlinedButton(onClick = { runtime.updateAssignmentDraft(draft.copy(sourceId = source.deviceId, roles = source.channels.associate { it.id to it.kind }))) }, modifier = Modifier.fillMaxWidth()) { Text(source.label + if (source.deviceId in profile.selectedDeviceIds) " · attached" else if (draft.sourceId == source.deviceId) " · selected" else "") } }
                        active.forEach { meal -> FilterChip(selected = draft.cookId == meal.cook.id, onClick = { runtime.updateAssignmentDraft(draft.copy(cookId = meal.cook.id, dishes = emptyMap())) }, label = { Text(meal.cook.title) }) }
                        if (active.isEmpty()) Text("Start a meal before attaching temperature probes.")
                        Button(onClick = { attachDraft(profile, advance = true) }, enabled = !busy && draft.sourceId != null && draft.cookId != null, modifier = Modifier.fillMaxWidth()) { Text("Attach to meal") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Monitor temperatures", modifier = Modifier.weight(1f))
                            Switch(checked = profile.monitoring, enabled = profile.cookId != null && profile.selectedDeviceIds.isNotEmpty(), onCheckedChange = { runtime.update(profile.copy(monitoring = it)) })
                        }
                        // PL-05: the dead toggle explains itself.
                        if (profile.cookId == null || profile.selectedDeviceIds.isEmpty()) Text("Attach this connection to the cook first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Continue while phone is locked", modifier = Modifier.weight(1f))
                            Switch(checked = profile.background, enabled = profile.monitoring && !profile.onLogOnly, onCheckedChange = { if (it) background(profile) else runtime.update(profile.copy(background = false)) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Collect only when I log", modifier = Modifier.weight(1f))
                            Switch(checked = profile.onLogOnly, onCheckedChange = { runtime.update(profile.copy(onLogOnly = it, background = if (it) false else profile.background)) })
                        }
                        if (!profile.onLogOnly) {
                            var intervals by remember(profile.id) { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { intervals = true }) { Text("Every " + GrillSamplingPolicy.intervalLabel(profile.intervalMillis)) }
                                DropdownMenu(expanded = intervals, onDismissRequest = { intervals = false }) {
                                    listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L).forEach { interval ->
                                        DropdownMenuItem(text = { Text(GrillSamplingPolicy.intervalLabel(interval)) }, onClick = { runtime.update(profile.copy(intervalMillis = interval)); intervals = false })
                                    }
                                }
                            }
                            if (profile.provider in setOf("meater", "fireboard")) Text("Cloud reads are limited to once per 30 seconds across this account.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (step == 3) {
            val assignable = profiles.filter { it.sources.isNotEmpty() }
            if (assignable.isEmpty()) Text("Nothing to assign yet. Connect a connection (step 1) and attach it to a cook (step 2) first.")
            if (assignable.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assignable.forEach { profile -> FilterChip(selected = (assignProfileId ?: assignable.first().id) == profile.id, onClick = { assignProfileId = profile.id }, label = { Text(profile.label) }) }
            }
            assignable.firstOrNull { it.id == (assignProfileId ?: assignable.firstOrNull()?.id) }?.let { profile ->
                val draft = drafts[profile.id] ?: seedAssignmentDraft(profile, active.firstOrNull()?.cook?.id)
                val source = profile.sources.firstOrNull { it.deviceId == draft.sourceId } ?: profile.sources.firstOrNull()
                val dishes = active.firstOrNull { it.cook.id == draft.cookId }?.dishes.orEmpty()
                Text(profile.label + " · " + (source?.label ?: ""), style = MaterialTheme.typography.titleMedium)
                if (profile.sources.size > 1) profile.sources.forEach { candidate ->
                    OutlinedButton(onClick = { runtime.updateAssignmentDraft(draft.copy(sourceId = candidate.deviceId, roles = candidate.channels.associate { it.id to it.kind })) }, modifier = Modifier.fillMaxWidth()) { Text(candidate.label + if (draft.sourceId == candidate.deviceId) " · selected" else "") }
                }
                source?.channels?.forEach { channel ->
                    val kind = draft.roles[channel.id] ?: channel.kind
                    Text(channel.name, style = MaterialTheme.typography.titleSmall)
                    if (channel.kind != TelemetryKind.SETPOINT) {
                        var dropdown by remember(channel.id) { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { dropdown = true }) { Text(when(kind) { TelemetryKind.FOOD -> "Food probe"; TelemetryKind.CHAMBER -> "Grill chamber"; TelemetryKind.AMBIENT -> "Ambient"; TelemetryKind.OTHER -> "Other temperature"; else -> "Observed target" }) }
                            DropdownMenu(dropdown, { dropdown = false }) {
                                listOf(TelemetryKind.FOOD to "Food probe", TelemetryKind.CHAMBER to "Grill chamber", TelemetryKind.AMBIENT to "Ambient", TelemetryKind.OTHER to "Other temperature").forEach { (role, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { runtime.updateAssignmentDraft(draft.copy(roles = draft.roles + (channel.id to role))); dropdown = false }) }
                            }
                        }
                    } else Text("Observed target · read-only")
                    if (kind == TelemetryKind.FOOD) ProbeDishSelector("Assign to food", if (draft.dishes.containsKey(channel.id)) draft.dishes[channel.id] else dishes.singleOrNull()?.id, dishes.map { it.id to it.name }, "telemetry-food-" + channel.id) { runtime.updateAssignmentDraft(draft.copy(dishes = draft.dishes + (channel.id to it))) }
                }
                Text("Edits are kept as a draft if you leave — they apply when you attach.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { attachDraft(profile, advance = false) }, enabled = !busy && draft.sourceId != null && draft.cookId != null, modifier = Modifier.fillMaxWidth()) { Text("Apply assignments") }
            }
        }
        Button(onClick = { pending = null; dialogError = null; add = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("telemetry-add")) { Text("Add temperature connection") }
        Text("Background monitoring uses a persistent notification and can increase battery use. Android may stop it; supported history is recovered after reconnecting, and other providers leave a gap.", style = MaterialTheme.typography.bodySmall)
    }
    if (add) {
        var provider by remember { mutableStateOf(pending?.provider ?: "home_assistant") }
        var endpoint by remember { mutableStateOf(pending?.endpoint ?: "") }
        var device by remember { mutableStateOf(pending?.deviceHint ?: "") }
        var user by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        var advanced by remember { mutableStateOf(false) }
        var bleGranted by remember { mutableStateOf(false) }
        val ble = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted -> bleGranted = granted.values.all { it } }
        fun profile() = (pending ?: TelemetryProfile(UUID.randomUUID().toString(), provider, providerNames.getValue(provider))).copy(provider = provider, endpoint = endpoint.trim(), deviceHint = device.trim())
        AlertDialog(onDismissRequest = { if (!busy) { add = false; pending = null; secret = ""; dialogError = null } }, title = { Text("Connect · Beta") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    // PL-06: visible progress during the token exchange.
                    if (connecting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("telemetry-ha-progress"))
                        Text("Connecting to Home Assistant…")
                    }
                    providerNames.forEach { (id, name) ->
                        FilterChip(selected = provider == id, onClick = { provider = id; secret = ""; user = "" }, label = { Text(name) }, enabled = !busy && pending == null)
                    }
                    if (provider == "home_assistant") {
                        Text("Use a Home Assistant server you already operate. PitTech does not host or install one.")
                        OutlinedTextField(endpoint, { endpoint = it.take(350) }, label = { Text("Server URL") }, singleLine = true, enabled = !connecting)
                        Button(modifier = Modifier.testTag("telemetry-ha-authorize"), enabled = !busy && endpoint.startsWith("https://"), onClick = {
                            val p = profile(); pending = p; dialogError = null
                            runCatching { oauth.launch(Intent().setClassName(context, "com.pittech.HomeAssistantAuthorizationActivity").putExtra("server", p.endpoint).putExtra("profile", p.id)) }
                                .onFailure { dialogError = "Authorization is unavailable in this build. Server: ${p.endpoint}" }
                        }) { Text("Sign in to Home Assistant") }
                        TextButton(onClick = { advanced = !advanced }) { Text("Advanced token setup") }
                        if (advanced) Text("Local HTTP is available only in development builds and exposes the token to your network. HTTPS is recommended.")
                    }
                    if (provider == "flame_boss") {
                        Text("Requires a manufacturer-confirmed TLS broker and a public cook topic. Private topics are awaiting vendor verification.")
                        OutlinedTextField(endpoint, { endpoint = it.take(350) }, label = { Text("TLS broker URL from Flame Boss") }, placeholder = { Text("mqtts://host:port") }, singleLine = true)
                        OutlinedTextField(device, { device = it.take(16) }, label = { Text("Device ID") }, singleLine = true)
                    }
                    if (provider == "fireboard") {
                        Text("Cloud-connected FireBoards are discovered from your account. Choose probe roles after connecting.")
                        TextButton(onClick = { advanced = !advanced }) { Text("Optional session history") }
                        if (advanced) OutlinedTextField(device, { device = it.take(18) }, label = { Text("FireBoard session ID") }, singleLine = true)
                    }
                    if (provider == "meater") Text("The MEATER app or Block must stay connected to Bluetooth and MEATER Cloud. The public API cannot recover missing historical samples.")
                    if (provider == "combustion") {
                        Text("Turn on Bluetooth and keep your probe nearby. This Beta uses official passive advertisements; repeater and probe-log recovery are not validated.")
                        OutlinedButton(onClick = { ble.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text(if (bleGranted) "Bluetooth access allowed" else "Allow Bluetooth access") }
                    } else if (provider != "home_assistant") OutlinedTextField(user, { user = it.take(254) }, label = { Text("Account email or username") }, singleLine = true)
                    if (provider != "combustion" && (provider != "home_assistant" || advanced)) OutlinedTextField(secret, { secret = it.take(16_384) }, label = { Text(if (provider == "home_assistant") "Access token" else "Password (not saved)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (provider != "combustion" && provider != "home_assistant" && provider != "flame_boss") Text("Advanced: leave the account field empty to use a provider access token.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = !busy && (provider == "combustion" || secret.isNotBlank()), onClick = {
                val p = profile(); val u = user; val entered = secret; secret = ""; busy = true
                scope.launch {
                    try { runtime.connect(p, u, entered); add = false; pending = null; dialogError = null; error = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        val message = if (e is TelemetryRequestException || e is IllegalArgumentException) e.message else "Connection failed. Check your account and network."
                        // PL-06: HA failures stay in this sheet, server URL attached, form intact.
                        if (provider == "home_assistant") dialogError = "$message Server: ${p.endpoint}" else error = message
                    }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Connecting…" else "Connect") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { add = false; pending = null; secret = ""; dialogError = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun CookTelemetryPanel(data: CookDetailData, viewModel: CooksViewModel) {
    val profiles by viewModel.telemetry.store.profiles.collectAsStateWithLifecycle()
    val states by viewModel.telemetry.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val attached = profiles.filter { it.cookId == data.cook.id && it.selectedDeviceIds.isNotEmpty() }
    if (attached.isEmpty()) return
    Card(Modifier.fillMaxWidth().testTag("cook-telemetry")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connected temperatures · Beta", style = MaterialTheme.typography.titleMedium)
            attached.forEach { profile -> profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { source ->
                Text(source.label + " · " + (if (data.cook.status != CookStatus.ACTIVE || !profile.monitoring) "Paused" else states[profile.id + ":" + source.deviceId] ?: "Waiting for readings"))
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { viewModel.telemetry.onLog(data.cook.id, true) } }, enabled = data.cook.status == CookStatus.ACTIVE) { Text("Read now") }
                TextButton(onClick = { attached.forEach { viewModel.telemetry.update(it.copy(monitoring = !it.monitoring)) } }, enabled = data.cook.status == CookStatus.ACTIVE) { Text(if (attached.any { it.monitoring }) "Pause monitoring" else "Resume monitoring") }
            }
            Text("Probe assignments and connection settings are in Devices.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
