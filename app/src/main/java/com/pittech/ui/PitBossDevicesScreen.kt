package com.pittech.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.devices.NearbyPitBossController
import com.pittech.devices.PitBossBleDiscovery
import com.pittech.devices.PitBossRelayClient
import com.pittech.devices.PitBossRelayStage

@Composable
fun PitBossDevicesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("pittech-controller-test", Context.MODE_PRIVATE)
    }
    val client = remember { PitBossRelayClient() }
    val discovery = remember(context) { PitBossBleDiscovery(context) }
    val uiState by client.uiState.collectAsStateWithLifecycle()

    var nearbyControllers by remember { mutableStateOf(emptyList<NearbyPitBossController>()) }
    var selectedIdentifier by rememberSaveable {
        mutableStateOf(preferences.getString("pitboss-grill-id", "") ?: "")
    }
    var isScanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        isScanning = true
        scanMessage = "Scanning nearby Bluetooth devices…"
        nearbyControllers = emptyList()
        discovery.startScan(
            onDevices = { found ->
                nearbyControllers = found
                if (selectedIdentifier.isBlank() && found.size == 1) {
                    selectedIdentifier = found.single().identifier
                } else if (selectedIdentifier !in found.map { it.identifier } && found.size == 1) {
                    selectedIdentifier = found.single().identifier
                }
            },
            onFinished = { message ->
                isScanning = false
                scanMessage = message ?: if (nearbyControllers.isEmpty()) {
                    "No controller found."
                } else {
                    "Select the controller to test."
                }
            },
        )
    }

    val scanPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (scanPermissions.all { results[it] == true }) {
            startScan()
        } else {
            isScanning = false
            scanMessage = "Bluetooth permission is needed to find a nearby controller."
        }
    }

    DisposableEffect(client, discovery) {
        onDispose {
            discovery.stopScan()
            client.disconnect()
        }
    }

    val canScan = scanPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val canConnect = selectedIdentifier.isNotBlank() &&
        uiState.stage != PitBossRelayStage.CONNECTING &&
        uiState.stage != PitBossRelayStage.RELAY_CONNECTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Controller connection test", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pit Boss · Bluetooth discovery + vendor relay",
            style = MaterialTheme.typography.titleMedium,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Find your controller", style = MaterialTheme.typography.titleSmall)
                Text("Turn on the controller and keep it nearby. PitTech reads its advertised device ID over Bluetooth, so you do not have to find or type the ID.")
                Text("After discovery, PitTech tests the existing Pit Boss relay using that ID. The controller must already be set up on Wi-Fi in the Pit Boss app for this relay test.")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (canScan) {
                        startScan()
                    } else {
                        permissionLauncher.launch(scanPermissions)
                    }
                },
                enabled = !isScanning,
                modifier = Modifier.testTag("pitboss-scan"),
            ) {
                Text(if (isScanning) "Scanning…" else "Scan nearby")
            }

            OutlinedButton(
                onClick = {
                    preferences.edit()
                        .putString("pitboss-grill-id", selectedIdentifier)
                        .apply()
                    client.connect(selectedIdentifier)
                },
                enabled = canConnect,
                modifier = Modifier.testTag("pitboss-connect"),
            ) {
                Text(if (uiState.stage == PitBossRelayStage.RPC_RESPONDED) "Reconnect relay" else "Test relay")
            }

            OutlinedButton(
                onClick = client::disconnect,
                enabled = uiState.stage != PitBossRelayStage.DISCONNECTED,
                modifier = Modifier.testTag("pitboss-disconnect"),
            ) {
                Text("Disconnect")
            }
        }

        if (scanMessage != null) {
            Text(scanMessage!!, style = MaterialTheme.typography.bodyMedium)
        } else if (nearbyControllers.isEmpty() && selectedIdentifier.isNotBlank()) {
            Text("Saved controller: $selectedIdentifier", style = MaterialTheme.typography.bodyMedium)
        }

        if (nearbyControllers.isNotEmpty()) {
            Text("Nearby controller IDs", style = MaterialTheme.typography.titleMedium)
            nearbyControllers.forEachIndexed { index, controller ->
                val selected = controller.identifier == selectedIdentifier
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedIdentifier = controller.identifier
                            scanMessage = "Selected controller."
                        }
                        .testTag(if (index == 0) "pitboss-candidate" else "pitboss-candidate-$index"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(controller.identifier, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (selected) "Selected · signal ${controller.rssi} dBm" else "Signal ${controller.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Connection status", style = MaterialTheme.typography.titleSmall)
                Text(
                    uiState.statusMessage,
                    modifier = Modifier.testTag("pitboss-connection-status"),
                )
                uiState.errorMessage?.let { errorMessage ->
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Read-only relay probe", style = MaterialTheme.typography.titleSmall)
                Text("This test sends only RPC.Ping through the Pit Boss vendor relay. It does not change grill settings or send control commands.")
                Text("PitTech does not upload the controller ID or status frames to a PitTech server. Incoming JSON is redacted before display.")
            }
        }

        if (uiState.recentMessages.isNotEmpty()) {
            Text("Relay messages", style = MaterialTheme.typography.titleMedium)
            uiState.recentMessages.forEachIndexed { index, message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(if (index == 0) "pitboss-latest-message" else "pitboss-message-$index"),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(message.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            message.safeJson,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
