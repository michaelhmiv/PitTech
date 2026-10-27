package com.pittech.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/** TURN-IN selectable row (`d21` attach sources, `d23` assign sources):
 * a laminated row whose border goes ember while it carries the selection.
 * The label text is the app's own, suffixes included. */
@Composable
private fun TurnInSelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = pitTechTokens()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface,
        contentColor = tokens.ink,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) tokens.ember else tokens.ruling),
    ) {
        Text(label, style = PitTechTextStyles.bodyEmphasis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

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
                    catch (e: Exception) { dialogError = "Home Assistant sign-in could not finish. Check the server address and try again. Server: ${p.endpoint}" }
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
    val tokens = pitTechTokens()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // TURN-IN (`d08`): the board head — banner title, the app's own
        // read-only intro, and the §1.1.3 contract line the frames carry.
        TurnInBannerText("Temperature connections · Beta", style = PitTechTextStyles.bannerTitle, modifier = Modifier.testTag("telemetry-connections"))
        Text("Read-only connections. Your accounts and temperatures stay on this phone. Manual cooking and logging work without a connection.", style = PitTechTextStyles.body, color = tokens.inkMuted)
        Text("PitTech watches — it never changes a setting.", style = PitTechTextStyles.body, color = tokens.inkMuted)
        // TURN-IN (`d21`/`d23`): the step rail — done steps carry a check,
        // the current step is the marigold segment, the rest stay quiet.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Connect", 2 to "Attach", 3 to "Assign").forEach { (number, label) ->
                val current = step == number
                Surface(
                    onClick = { step = number },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("telemetry-step-$number").semantics { this.selected = current },
                    shape = RoundedCornerShape(12.dp),
                    color = if (current) tokens.marigold else tokens.surface,
                    contentColor = if (current) tokens.onAccent else tokens.ink,
                    border = if (current) null else BorderStroke(1.dp, tokens.ruling),
                ) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (number < step) Text("✓", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                        else TurnInMetaLabel("Step $number", color = if (current) tokens.onAccent else tokens.inkMuted)
                        Text(label, style = PitTechTextStyles.bodyEmphasis, color = if (current) tokens.onAccent else tokens.ink)
                    }
                }
            }
        }
        error?.let { TurnInStatusStrip(it, tokens.flag) }
        if (step == 1) {
            profiles.forEach { profile ->
                // TURN-IN (`d08`): one laminated card per connection —
                // banner name, its state as a mono line, then Reconnect
                // and Forget in their homes.
                TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnInBannerText(profile.label + " · Beta")
                        Text(states[profile.id] ?: "Saved connection", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                        OutlinedButton(onClick = { scope.launch { busy = true; try { runtime.connect(profile, "", "") } catch (_: Exception) { dialogError = null; error = "Reconnect by entering your account credentials."; pending = profile; add = true } finally { busy = false } } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                        TextButton(onClick = { scope.launch { runtime.remove(profile) } }, enabled = !busy) { Text("Forget connection", color = tokens.flag) }
                    }
                }
            }
            if (profiles.isEmpty()) TurnInCard(Modifier.fillMaxWidth()) { Text(EmptyStateCopy.CONNECTIONS_EMPTY, style = PitTechTextStyles.body) }
        }
        if (step == 2) {
            if (profiles.isEmpty()) Text("Connect a connection first (step 1), then attach it here.")
            profiles.forEach { profile ->
                val draft = drafts[profile.id] ?: seedAssignmentDraft(profile, active.firstOrNull()?.cook?.id)
                // TURN-IN (`d21` + `d11`): the profile is a laminated card;
                // sources pick from selectable rows, meals stay chips, and
                // the watching switches group in a raised sub-card.
                TurnInCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TurnInBannerText(profile.label + " · Beta")
                        Text(states[profile.id] ?: "Saved connection", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                        profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { source ->
                            Text(source.label + " · " + (states[profile.id + ":" + source.deviceId] ?: if (profile.monitoring) "Waiting for readings" else "Paused"), style = PitTechTextStyles.body)
                            TextButton(onClick = { scope.launch { runtime.detachSource(profile, source) } }) { Text("Detach " + source.label) }
                        }
                        if (profile.sources.isEmpty()) TurnInDashedCard(Modifier.fillMaxWidth()) { Text(devicesEmptyStateText(profile.id in discoveryInFlight, profile.id in discoverySettled), style = PitTechTextStyles.body, color = tokens.inkMuted) }
                        profile.sources.forEach { source ->
                            TurnInSelectableRow(
                                label = source.label + (if (source.deviceId in profile.selectedDeviceIds) " · attached" else if (draft.sourceId == source.deviceId) " · selected" else ""),
                                selected = draft.sourceId == source.deviceId,
                                onClick = {
                                    runtime.updateAssignmentDraft(draft.copy(sourceId = source.deviceId, roles = source.channels.associate { it.id to it.kind }))
                                },
                            )
                        }
                        active.forEach { meal -> FilterChip(selected = draft.cookId == meal.cook.id, onClick = { runtime.updateAssignmentDraft(draft.copy(cookId = meal.cook.id, dishes = emptyMap())) }, label = { Text(meal.cook.title) }) }
                        if (active.isEmpty()) TurnInDashedCard(Modifier.fillMaxWidth()) { Text("Start a meal before attaching temperature probes.", style = PitTechTextStyles.body, color = tokens.inkMuted) }
                        Button(onClick = { attachDraft(profile, advance = true) }, enabled = !busy && draft.sourceId != null && draft.cookId != null, modifier = Modifier.fillMaxWidth()) { Text("Attach to meal") }
                        Surface(shape = RoundedCornerShape(14.dp), color = tokens.surfaceRaised, contentColor = tokens.ink, border = BorderStroke(1.dp, tokens.ruling), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Monitor temperatures", style = PitTechTextStyles.body, modifier = Modifier.weight(1f))
                                    Switch(checked = profile.monitoring, enabled = profile.cookId != null && profile.selectedDeviceIds.isNotEmpty(), onCheckedChange = { runtime.update(profile.copy(monitoring = it)) })
                                }
                                // PL-05: the dead toggle explains itself.
                                if (profile.cookId == null || profile.selectedDeviceIds.isEmpty()) Text("Attach this connection to the cook first", style = PitTechTextStyles.body, color = tokens.inkMuted)
                                HorizontalDivider(color = tokens.ruling)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Continue while phone is locked", style = PitTechTextStyles.body, modifier = Modifier.weight(1f))
                                    Switch(checked = profile.background, enabled = profile.monitoring && !profile.onLogOnly, onCheckedChange = { if (it) background(profile) else runtime.update(profile.copy(background = false)) })
                                }
                                HorizontalDivider(color = tokens.ruling)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Collect only when I log", style = PitTechTextStyles.body, modifier = Modifier.weight(1f))
                                    Switch(checked = profile.onLogOnly, onCheckedChange = { runtime.update(profile.copy(onLogOnly = it, background = if (it) false else profile.background)) })
                                }
                                if (!profile.onLogOnly) {
                                    HorizontalDivider(color = tokens.ruling)
                                    var intervals by remember(profile.id) { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { intervals = true }) { Text("Every " + GrillSamplingPolicy.intervalLabel(profile.intervalMillis)) }
                                        DropdownMenu(expanded = intervals, onDismissRequest = { intervals = false }) {
                                            listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L).forEach { interval ->
                                                DropdownMenuItem(text = { Text(GrillSamplingPolicy.intervalLabel(interval)) }, onClick = { runtime.update(profile.copy(intervalMillis = interval)); intervals = false })
                                            }
                                        }
                                    }
                                    if (profile.provider in setOf("meater", "fireboard")) Text("Cloud reads are limited to once per 30 seconds across this account.", style = PitTechTextStyles.body, color = tokens.inkMuted)
                                }
                            }
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
                TurnInBannerText(profile.label + " · " + (source?.label ?: ""))
                if (profile.sources.size > 1) profile.sources.forEach { candidate ->
                    TurnInSelectableRow(
                        label = candidate.label + if (draft.sourceId == candidate.deviceId) " · selected" else "",
                        selected = draft.sourceId == candidate.deviceId,
                        onClick = { runtime.updateAssignmentDraft(draft.copy(sourceId = candidate.deviceId, roles = candidate.channels.associate { it.id to it.kind })) },
                    )
                }
                source?.channels?.forEach { channel ->
                    val kind = draft.roles[channel.id] ?: channel.kind
                    // TURN-IN (`d23`): one laminated card per channel —
                    // banner name, its role control, and for a food probe
                    // the dish it cooks. The setpoint channel stays a
                    // locked read-only row: displayed, never editable.
                    TurnInCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TurnInBannerText(channel.name)
                            if (channel.kind != TelemetryKind.SETPOINT) {
                                var dropdown by remember(channel.id) { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { dropdown = true }) { Text(when(kind) { TelemetryKind.FOOD -> "Food probe"; TelemetryKind.CHAMBER -> "Grill chamber"; TelemetryKind.AMBIENT -> "Ambient"; TelemetryKind.OTHER -> "Other temperature"; else -> "Observed target" }) }
                                    DropdownMenu(dropdown, { dropdown = false }) {
                                        listOf(TelemetryKind.FOOD to "Food probe", TelemetryKind.CHAMBER to "Grill chamber", TelemetryKind.AMBIENT to "Ambient", TelemetryKind.OTHER to "Other temperature").forEach { (role, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { runtime.updateAssignmentDraft(draft.copy(roles = draft.roles + (channel.id to role))); dropdown = false }) }
                                    }
                                }
                            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("🔒", style = PitTechTextStyles.monoMeta)
                                Text("Observed target · read-only", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                            }
                            if (kind == TelemetryKind.FOOD) ProbeDishSelector("Assign to food", if (draft.dishes.containsKey(channel.id)) draft.dishes[channel.id] else dishes.singleOrNull()?.id, dishes.map { it.id to it.name }, "telemetry-food-" + channel.id) { runtime.updateAssignmentDraft(draft.copy(dishes = draft.dishes + (channel.id to it))) }
                        }
                    }
                }
                Text("Edits are kept as a draft if you leave — they apply when you attach.", style = PitTechTextStyles.body, color = tokens.inkMuted)
                Button(onClick = { attachDraft(profile, advance = false) }, enabled = !busy && draft.sourceId != null && draft.cookId != null, modifier = Modifier.fillMaxWidth()) { Text("Apply assignments") }
            }
        }
        Button(onClick = { pending = null; dialogError = null; add = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("telemetry-add")) { Text("Add temperature connection") }
        Text("Background monitoring uses a persistent notification and can increase battery use. Android may stop it; supported history is recovered after reconnecting, and other providers leave a gap.", style = PitTechTextStyles.body, color = tokens.inkMuted)
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
        // PL-22 (DD-6): connecting a provider is a multi-field form — full screen.
        FullScreenSurface(onDismiss = { if (!busy) { add = false; pending = null; secret = ""; dialogError = null } }, title = "Connect · Beta", dismissEnabled = !busy, actions = {
            TextButton(enabled = !busy, onClick = { add = false; pending = null; secret = ""; dialogError = null }) { Text("Cancel") }
            TextButton(enabled = !busy && (provider == "combustion" || secret.isNotBlank()), onClick = {
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
            }) { Text(if (busy) "Connecting…" else "Connect") }
        }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // TURN-IN (`d16`): a failure stays in place as a flag
                    // strip — the form, URL included, is untouched.
                    dialogError?.let { TurnInStatusStrip(it, tokens.flag) }
                    // PL-06: visible progress during the token exchange (`d15`).
                    if (connecting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("telemetry-ha-progress"), color = tokens.ember, trackColor = tokens.ruling)
                        Text("Connecting to Home Assistant…", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
                    }
                    // TURN-IN (`d13`): the provider picker is a card per
                    // maker — banner name over its one-line descriptor;
                    // the picked card carries the ember border.
                    listOf("home_assistant", "fireboard", "flame_boss", "meater", "combustion").forEach { id ->
                        val selected = provider == id
                        Surface(
                            onClick = { provider = id; secret = ""; user = "" },
                            enabled = !busy && pending == null,
                            modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
                            shape = RoundedCornerShape(14.dp),
                            color = tokens.surface,
                            contentColor = tokens.ink,
                            border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) tokens.ember else tokens.ruling),
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TurnInBannerText(providerNames.getValue(id))
                                    Text(when (id) {
                                        "home_assistant" -> "Server address + sign-in, or a token"
                                        "fireboard" -> "Your FireBoard account"
                                        "flame_boss" -> "Broker address + device ID"
                                        "meater" -> "Your MEATER account"
                                        else -> "Bluetooth — probes nearby"
                                    }, style = PitTechTextStyles.body, color = tokens.inkMuted)
                                }
                                Text("›", style = PitTechTextStyles.bannerCard, color = tokens.inkMuted)
                            }
                        }
                    }
                    if (provider == "home_assistant") {
                        Text("Use a Home Assistant server you already operate. PitTech does not host or install one.", style = PitTechTextStyles.body)
                        TurnInLiteralBoxedField("Server URL") {
                            OutlinedTextField(endpoint, { endpoint = it.take(350) }, singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth())
                        }
                        Button(modifier = Modifier.testTag("telemetry-ha-authorize"), enabled = !busy && endpoint.startsWith("https://"), onClick = {
                            val p = profile(); pending = p; dialogError = null
                            runCatching { oauth.launch(Intent().setClassName(context, "com.pittech.HomeAssistantAuthorizationActivity").putExtra("server", p.endpoint).putExtra("profile", p.id)) }
                                .onFailure { dialogError = "Authorization is unavailable in this build. Server: ${p.endpoint}" }
                        }) { Text("Sign in to Home Assistant") }
                        TextButton(onClick = { advanced = !advanced }) { Text("Advanced token setup") }
                        if (advanced) Text("Local HTTP is available only in development builds and exposes the token to your network. HTTPS is recommended.", style = PitTechTextStyles.body, color = tokens.inkMuted)
                    }
                    if (provider == "flame_boss") {
                        Text("Requires a manufacturer-confirmed TLS broker and a public cook topic. Private topics are awaiting vendor verification.", style = PitTechTextStyles.body)
                        TurnInLiteralBoxedField("TLS broker URL from Flame Boss") {
                            OutlinedTextField(endpoint, { endpoint = it.take(350) }, placeholder = { Text("mqtts://host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        TurnInLiteralBoxedField("Device ID") {
                            OutlinedTextField(device, { device = it.take(16) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (provider == "fireboard") {
                        Text("Cloud-connected FireBoards are discovered from your account. Choose probe roles after connecting.", style = PitTechTextStyles.body)
                        TextButton(onClick = { advanced = !advanced }) { Text("Optional session history") }
                        if (advanced) TurnInLiteralBoxedField("FireBoard session ID") {
                            OutlinedTextField(device, { device = it.take(18) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (provider == "meater") Text("The MEATER app or Block must stay connected to Bluetooth and MEATER Cloud. The public API cannot recover missing historical samples.", style = PitTechTextStyles.body)
                    if (provider == "combustion") {
                        Text("Turn on Bluetooth and keep your probe nearby. This Beta uses official passive advertisements; repeater and probe-log recovery are not validated.", style = PitTechTextStyles.body)
                        OutlinedButton(onClick = { ble.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text(if (bleGranted) "Bluetooth access allowed" else "Allow Bluetooth access") }
                    } else if (provider != "home_assistant") TurnInLiteralBoxedField("Account email or username") {
                        OutlinedTextField(user, { user = it.take(254) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (provider != "combustion" && (provider != "home_assistant" || advanced)) TurnInLiteralBoxedField(if (provider == "home_assistant") "Access token" else "Password (not saved)") {
                        OutlinedTextField(secret, { secret = it.take(16_384) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (provider != "combustion" && provider != "home_assistant" && provider != "flame_boss") Text("Advanced: leave the account field empty to use a provider access token.", style = PitTechTextStyles.body, color = tokens.inkMuted)
                }
        }
    }
}

@Composable
internal fun CookTelemetryPanel(data: CookDetailData, viewModel: CooksViewModel) {
    val profiles by viewModel.telemetry.store.profiles.collectAsStateWithLifecycle()
    val states by viewModel.telemetry.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val attached = profiles.filter { it.cookId == data.cook.id && it.selectedDeviceIds.isNotEmpty() }
    if (attached.isEmpty()) return
    // TURN-IN (`d03` footer strip): the cook's watching connections are a
    // laminated card — banner title, each source with its state in mono.
    TurnInCard(Modifier.fillMaxWidth().testTag("cook-telemetry")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TurnInBannerText("Connected temperatures · Beta")
            attached.forEach { profile -> profile.sources.filter { it.deviceId in profile.selectedDeviceIds }.forEach { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.label, style = PitTechTextStyles.body, modifier = Modifier.weight(1f))
                    Text(if (data.cook.status != CookStatus.ACTIVE || !profile.monitoring) "Paused" else states[profile.id + ":" + source.deviceId] ?: "Waiting for readings", style = PitTechTextStyles.monoMeta, color = pitTechTokens().inkMuted)
                }
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { viewModel.telemetry.onLog(data.cook.id, true) } }, enabled = data.cook.status == CookStatus.ACTIVE) { Text("Read now") }
                TextButton(onClick = { attached.forEach { viewModel.telemetry.update(it.copy(monitoring = !it.monitoring)) } }, enabled = data.cook.status == CookStatus.ACTIVE) { Text(if (attached.any { it.monitoring }) "Pause monitoring" else "Resume monitoring") }
            }
            Text("Probe assignments and connection settings are in Devices.", style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
        }
    }
}
