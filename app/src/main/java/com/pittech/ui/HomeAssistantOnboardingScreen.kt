package com.pittech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pittech.PitTechApplication
import com.pittech.data.CookEntity
import com.pittech.data.CookStatus
import com.pittech.data.DishEntity
import com.pittech.data.TelemetryRecordingRepository
import com.pittech.devices.HomeAssistantConnector
import com.pittech.devices.TelemetryChannel
import com.pittech.devices.TelemetryKind
import com.pittech.devices.TelemetrySource
import com.pittech.devices.TelemetryTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Developer-preview flow: connects to an existing user-owned HA installation.
 * No HA installation/third-party plugin is bundled; no controller control methods.
 * Public Beta onboarding must add HA's normal OAuth authorization flow.
 */
@Composable
internal fun HomeAssistantOnboardingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PitTechApplication
    val preferences = remember(context) {
        context.getSharedPreferences("telemetry-homeassistant", android.content.Context.MODE_PRIVATE)
    }
    val vault = remember(context) { TelemetryTokenStore(context) }
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(preferences.getString("server", "") ?: "") }
    // Do not use rememberSaveable for credentials: they must not enter saved UI state.
    var enteredToken by remember { mutableStateOf("") }
    var discovered by remember { mutableStateOf(emptyList<TelemetrySource>()) }
    var selected by remember { mutableStateOf<TelemetrySource?>(null) }
    var cooks by remember { mutableStateOf(emptyList<CookEntity>()) }
    var dishes by remember { mutableStateOf(emptyList<DishEntity>()) }
    var cookId by remember { mutableStateOf<String?>(null) }
    var dishId by remember { mutableStateOf<String?>(null) }
    var foodProbe by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var connector by remember { mutableStateOf<HomeAssistantConnector?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back to Devices") }
        Text("Connect Home Assistant · Beta", style = MaterialTheme.typography.titleLarge)
        Text("Connects to a Home Assistant server that you already operate. The Home Assistant phone app alone is not a server. PitTech only reads temperature sensors; it cannot control your grill.",
            style = MaterialTheme.typography.bodySmall)
        Text("Use HTTPS whenever possible. Local HTTP is unencrypted and can expose your Home Assistant token to anyone intercepting your network traffic.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = server, onValueChange = { server = it.take(350) },
            label = { Text("Home Assistant server URL") },
            placeholder = { Text("https://home.example.com") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = enteredToken, onValueChange = { enteredToken = it.take(16384) },
            label = { Text("Access token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            supportingText = { Text("Advanced Beta setup: generate a long-lived access token in your Home Assistant profile. Stored only with Android Keystore.") },
        )
        Button(
            enabled = !busy && server.isNotBlank(),
            onClick = {
                busy = true
                status = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val actual = enteredToken.ifBlank { vault.load("home_assistant", "primary") ?: "" }
                            require(actual.isNotBlank()) { "Enter your Home Assistant access token." }
                            val session = HomeAssistantConnector(server, actual)
                            val devices = session.discover()
                            vault.save("home_assistant", "primary", actual)
                            preferences.edit().putString("server", server).apply()
                            Triple(session, devices, app.database.cookDao().getAllCooks().filter { it.status == CookStatus.ACTIVE })
                        }
                    }.onSuccess { result ->
                        connector = result.first
                        discovered = result.second
                        cooks = result.third
                        enteredToken = ""
                        status = if (discovered.isEmpty()) "Connected. No compatible temperature sensors are available." else
                            "Connected. Choose a temperature sensor below."
                    }.onFailure { status = "Connection failed: " + (it.message ?: "Check server URL and token") }
                    busy = false
                }
            },
        ) { Text(if (busy) "Connecting…" else "Connect and find sensors") }

        status?.let {
            Text(it, color = if (it.startsWith("Connection failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }

        if (discovered.isNotEmpty()) {
            Text("Temperature sensors", style = MaterialTheme.typography.titleMedium)
            discovered.forEach { source ->
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selected = source; cookId = null; dishId = null; dishes = emptyList() },
                ) { Text(source.label + if (source == selected) " · selected" else "") }
            }
        }

        selected?.let { source ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Record " + source.label, style = MaterialTheme.typography.titleMedium)
                    Text("Choose how to use this reading and which active cook should receive it.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = foodProbe, onClick = { foodProbe = true }, label = { Text("Food probe") })
                        FilterChip(selected = !foodProbe, onClick = { foodProbe = false }, label = { Text("Grill chamber") })
                    }
                    Text("Active cook", style = MaterialTheme.typography.bodyMedium)
                    if (cooks.isEmpty()) Text("Start a cook first to record temperatures.")
                    cooks.forEach { cook ->
                        OutlinedButton(onClick = {
                            cookId = cook.id
                            scope.launch {
                                dishes = withContext(Dispatchers.IO) { app.database.cookDao().getDishesForCook(cook.id) }
                                dishId = dishes.firstOrNull()?.id
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(cook.title + if (cookId == cook.id) " · selected" else "")
                        }
                    }
                    if (foodProbe && cookId != null) {
                        Text("Assign probe to food")
                        dishes.forEach { dish ->
                            FilterChip(selected = dishId == dish.id, onClick = { dishId = dish.id }, label = { Text(dish.name) })
                        }
                    }
                    Button(
                        enabled = !busy && cookId != null && (!foodProbe || dishId != null),
                        onClick = {
                            val chosenCook = cookId ?: return@Button
                            val chosenDish = dishId
                            val session = connector ?: return@Button
                            busy = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val role = if (foodProbe) TelemetryKind.FOOD else TelemetryKind.CHAMBER
                                        val mapped = source.copy(channels = listOf(
                                            TelemetryChannel("temperature", if (foodProbe) "Food probe" else "Grill chamber", role),
                                        ))
                                        val recorder = TelemetryRecordingRepository(app.database)
                                        recorder.attach(chosenCook, mapped,
                                            if (foodProbe) mapOf("temperature" to chosenDish) else emptyMap())
                                        recorder.ingest(chosenCook, mapped, session.snapshot(source.deviceId))
                                    }
                                }.onSuccess { count -> status = "Attached. Collected " + count + " available observations." }
                                 .onFailure { status = "Unable to attach: " + (it.message ?: "Unknown error") }
                                busy = false
                            }
                        },
                    ) { Text("Attach and record current reading") }
                }
            }
        }

        OutlinedButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    vault.clear("home_assistant", "primary")
                    preferences.edit().remove("server").apply()
                    connector?.disconnect()
                }
                connector = null
                discovered = emptyList()
                selected = null
                enteredToken = ""
                status = "Home Assistant connection removed from PitTech."
            }
        }) { Text("Forget Home Assistant connection") }
        Text("Developer preview. Automatic background recording and OAuth onboarding are in separate integration PRs; this screen only attaches a source and takes one live reading.",
            style = MaterialTheme.typography.bodySmall)
    }
}
