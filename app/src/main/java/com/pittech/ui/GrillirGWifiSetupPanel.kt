package com.pittech.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pittech.devices.AndroidGrillirGSetupEngine
import com.pittech.devices.GrillirGProtocol
import com.pittech.devices.GrillirGSetupEngine
import com.pittech.devices.GrillirGSetupSnapshot
import com.pittech.devices.GrillirGSetupStage

@Composable
internal fun GrillirGWifiSetupPanel(
    address: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    engineOverride: GrillirGSetupEngine? = null,
) {
    val context = LocalContext.current
    val engine = remember(context, engineOverride) {
        engineOverride ?: AndroidGrillirGSetupEngine(context)
    }
    var snapshot by remember { mutableStateOf(GrillirGSetupSnapshot()) }
    var selectedNetwork by remember { mutableStateOf<GrillirGProtocol.WifiNetwork?>(null) }
    var password by remember { mutableStateOf("") }
    var showSendConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(engine) { onDispose { engine.close() } }

    val hasBluetoothConnectPermission = remember(context, engine) {
        !engine.requiresBluetoothPermissions || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val isWorking = snapshot.stage in setOf(
        GrillirGSetupStage.CONNECTING,
        GrillirGSetupStage.NEGOTIATING_MTU,
        GrillirGSetupStage.DISCOVERING_SERVICES,
        GrillirGSetupStage.CHECKING_CONNECTION,
        GrillirGSetupStage.ENABLING_NOTIFICATIONS,
        GrillirGSetupStage.REQUESTING_NETWORKS,
        GrillirGSetupStage.SENDING_CREDENTIALS,
        GrillirGSetupStage.WAITING_FOR_WIFI,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("GrillirG Wi-Fi setup · experimental", style = MaterialTheme.typography.titleMedium)
                Text(
                    "PitTech will connect directly to the selected iFireTech controller, ask it to scan for Wi-Fi, and display the networks it reports. Scanning sends only the vendor app's connection-check and Wi-Fi-scan commands.",
                )
                Text(
                    "Wi-Fi details are sent only after you choose a network and confirm. The controller's password format uses the same key bundled in the vendor app; this is compatibility encryption, not end-to-end protection.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    selectedNetwork = null
                    password = ""
                    if (!hasBluetoothConnectPermission) {
                        snapshot = GrillirGSetupSnapshot(
                            stage = GrillirGSetupStage.FAILED,
                            message = "Grant PitTech Bluetooth connection permission, then try setup again.",
                        )
                    } else {
                        engine.start(address) { snapshot = it }
                    }
                },
                enabled = !isWorking,
                modifier = Modifier.testTag("grillirg-scan-wifi"),
            ) {
                Text(if (snapshot.stage in setOf(GrillirGSetupStage.IDLE, GrillirGSetupStage.FAILED)) {
                    "Connect & scan Wi-Fi"
                } else {
                    "Scan again"
                })
            }
            TextButton(onClick = onClose, enabled = !isWorking) { Text("Close") }
        }

        if (isWorking) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text(snapshot.message, modifier = Modifier.testTag("grillirg-setup-status"))
            }
        } else {
            Text(snapshot.message, modifier = Modifier.testTag("grillirg-setup-status"))
        }

        if (snapshot.networks.isNotEmpty()) {
            Text("Networks reported by the controller", style = MaterialTheme.typography.titleSmall)
            snapshot.networks.forEach { network ->
                val isSelected = selectedNetwork?.key == network.key
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = snapshot.stage == GrillirGSetupStage.NETWORKS_READY,
                        ) {
                            selectedNetwork = network
                            password = ""
                        }
                        .testTag("grillirg-network-${network.key}"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(network.ssid, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${wifiSecurityLabel(network.authMode)} · signal ${network.signalIndicator} · RSSI field ${network.rssiRaw} · channel ${network.primaryChannel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        selectedNetwork?.let { network ->
            if (!network.isOpen) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Wi-Fi password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = snapshot.stage == GrillirGSetupStage.NETWORKS_READY,
                    modifier = Modifier.fillMaxWidth().testTag("grillirg-password"),
                )
            } else {
                Text("This network is open; no password is required.")
            }
            Button(
                onClick = { showSendConfirmation = true },
                enabled = snapshot.stage == GrillirGSetupStage.NETWORKS_READY && (network.isOpen || password.isNotEmpty()),
                modifier = Modifier.testTag("grillirg-configure-network"),
            ) {
                Text("Configure this Wi-Fi network")
            }
        }
    }

    if (showSendConfirmation) {
        val network = selectedNetwork
        AlertDialog(
            onDismissRequest = { showSendConfirmation = false },
            title = { Text("Send Wi-Fi details to the controller?") },
            text = {
                Text(
                    "PitTech will send the network name and password for \"${network?.ssid.orEmpty()}\" to the nearby controller over Bluetooth. This changes the controller's Wi-Fi configuration. The password is encrypted using the vendor app's bundled compatibility key. PitTech will not upload these Wi-Fi details.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSendConfirmation = false
                        network?.let { engine.provision(it, password) }
                        password = ""
                    },
                    modifier = Modifier.testTag("grillirg-confirm-provision"),
                ) { Text("Send to controller") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSendConfirmation = false },
                    modifier = Modifier.testTag("grillirg-cancel-provision"),
                ) { Text("Cancel") }
            },
        )
    }
}

private fun wifiSecurityLabel(authMode: Int): String = when (authMode) {
    0 -> "Open"
    1 -> "WEP"
    2 -> "WPA"
    3 -> "WPA2"
    4 -> "WPA/WPA2"
    5 -> "Enterprise"
    6 -> "WPA3"
    7 -> "WPA2/WPA3"
    else -> "Security mode $authMode"
}
