package com.pittech.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.pittech.BuildConfig
import com.pittech.FeedbackApi
import com.pittech.FeedbackDiagnosticReport
import com.pittech.FeedbackKind
import com.pittech.FeedbackRequest
import com.pittech.FeedbackSubmitResult
import com.pittech.devices.BluetoothScanDiagnostics
import com.pittech.devices.BluetoothScanSummary
import com.pittech.devices.BluetoothGattInspectionReport
import com.pittech.devices.BluetoothGattInspector
import com.pittech.devices.ControllerSupportRegistry
import com.pittech.devices.NearbyBluetoothDevice
import com.pittech.devices.PitBossBleDiscovery
import com.pittech.devices.PitBossRelayClient
import com.pittech.devices.PitBossRelayStage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Composable
fun PitBossDevicesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("pittech-controller-test", Context.MODE_PRIVATE)
    }
    val client = remember { PitBossRelayClient() }
    val discovery = remember(context) { PitBossBleDiscovery(context) }
    val gattInspector = remember(context) { BluetoothGattInspector(context) }
    val uiState by client.uiState.collectAsStateWithLifecycle()

    var nearbyDevices by remember { mutableStateOf(emptyList<NearbyBluetoothDevice>()) }
    var selectedDeviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIdentifier by rememberSaveable {
        mutableStateOf(preferences.getString("pitboss-grill-id", "") ?: "")
    }
    var scanSummary by remember { mutableStateOf<BluetoothScanSummary?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var bluetoothConnectPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var pendingBluetoothAction by rememberSaveable { mutableStateOf("SCAN") }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var isInspectingGatt by remember { mutableStateOf(false) }
    var gattStatusMessage by remember { mutableStateOf<String?>(null) }
    var gattInspection by remember { mutableStateOf<BluetoothGattInspectionReport?>(null) }
    var sessionEvents by remember { mutableStateOf(emptyList<String>()) }
    var sessionEventRevision by remember { mutableStateOf(0) }
    var omittedSessionEventCount by remember { mutableStateOf(0) }
    val eventHandler = remember { Handler(Looper.getMainLooper()) }

    var showReportDialog by remember { mutableStateOf(false) }
    var reportDevice by remember { mutableStateOf<NearbyBluetoothDevice?>(null) }
    var reportDetailsExpanded by remember { mutableStateOf(true) }
    var reportSubmitting by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    var reportNotice by remember { mutableStateOf<String?>(null) }

    fun recordSessionEvent(message: String) {
        val addEvent = {
            val entry = formatReportUtc(System.currentTimeMillis()) + " " + message.take(350)
            if (sessionEvents.size >= 80) omittedSessionEventCount += 1
            sessionEvents = (sessionEvents + entry).takeLast(80)
            sessionEventRevision += 1
        }
        if (Looper.myLooper() == eventHandler.looper) addEvent() else eventHandler.post { addEvent() }
    }

    fun startScan() {
        recordSessionEvent("BLE scan started; foreground scan duration is capped at 12 seconds.")
        isScanning = true
        scanMessage = "Scanning nearby Bluetooth devices…"
        nearbyDevices = emptyList()
        scanSummary = null
        selectedDeviceKey = null
        gattInspection = null
        gattStatusMessage = null
        discovery.startScan(
            onDevices = { found ->
                nearbyDevices = found
                if (found.size == 1) {
                    selectedDeviceKey = found.single().key
                    found.single().relayIdentifier?.let { selectedIdentifier = it }
                }
            },
            onFinished = { summary, message ->
                recordSessionEvent(
                    "BLE scan finished; results=" + summary.totalResults +
                        ", retainedDevices=" + summary.capturedDeviceCount +
                        ", omittedDevices=" + summary.omittedDeviceCount +
                        ", error=" + (summary.error ?: "none"),
                )
                scanSummary = summary
                isScanning = false
                scanMessage = message ?: if (nearbyDevices.isEmpty()) {
                    "No Bluetooth devices were detected."
                } else {
                    "Found ${nearbyDevices.size} Bluetooth device(s). Choose your controller; reports include only that device."
                }
            },
        )
    }

    val requiredScanPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val hasScanPermission = ContextCompat.checkSelfPermission(
        context,
        requiredScanPermission,
    ) == PackageManager.PERMISSION_GRANTED
    val hasConnectPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    val selectedDevice = nearbyDevices.firstOrNull { it.key == selectedDeviceKey }

    fun startGattInspection(device: NearbyBluetoothDevice) {
        val address = device.address
        if (address.isNullOrBlank()) {
            val message = "The scan did not expose a Bluetooth address. Allow Bluetooth connection access and scan again."
            gattStatusMessage = message
            recordSessionEvent("GATT inspection could not start because the selected scan record had no address.")
            return
        }
        selectedDeviceKey = device.key
        gattInspection = null
        isInspectingGatt = true
        gattStatusMessage = "Connecting to the selected controller and inspecting readable BLE services…"
        recordSessionEvent("User started read-only GATT inspection for the selected BLE controller.")
        gattInspector.inspect(
            address = address,
            onProgress = { message -> gattStatusMessage = message },
            onFinished = { result ->
                gattInspection = result
                isInspectingGatt = false
                gattStatusMessage = result.outcome
                recordSessionEvent(
                    "GATT inspection finished; outcome=" + result.outcome +
                        ", services=" + result.services.size +
                        ", readableCharacteristics=" + result.readableCharacteristicCount +
                        ", readsCaptured=" + result.reads.size + ".",
                )
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (pendingBluetoothAction == "GATT") {
            val connectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                results[Manifest.permission.BLUETOOTH_CONNECT] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            val device = nearbyDevices.firstOrNull { it.key == selectedDeviceKey }
            if (connectGranted && device != null) {
                recordSessionEvent("Bluetooth connection permission granted for GATT inspection.")
                startGattInspection(device)
            } else {
                isInspectingGatt = false
                gattStatusMessage = "Bluetooth connection permission is needed to inspect this controller."
                recordSessionEvent("Bluetooth connection permission was denied for GATT inspection.")
            }
        } else if (results[requiredScanPermission] == true) {
            recordSessionEvent("Bluetooth scan permission granted.")
            startScan()
        } else {
            isScanning = false
            scanMessage = "Bluetooth permission is needed to find a nearby controller."
            recordSessionEvent("Bluetooth scan permission was denied.")
        }
        pendingBluetoothAction = "SCAN"
    }

    DisposableEffect(client, discovery, gattInspector) {
        onDispose {
            discovery.stopScan()
            client.disconnect()
            gattInspector.closeSilently()
        }
    }

    LaunchedEffect(hasScanPermission, hasConnectPermission, Build.VERSION.SDK_INT) {
        recordSessionEvent(
            "Bluetooth permission state; scan=" + hasScanPermission +
                ", connect=" + hasConnectPermission +
                ", Android API=" + Build.VERSION.SDK_INT + ".",
        )
    }

    LaunchedEffect(uiState.stage, uiState.statusMessage, uiState.errorMessage) {
        val stateText = "Relay stage=" + uiState.stage.name +
            "; status=" + uiState.statusMessage +
            (uiState.errorMessage?.let { "; error=" + it } ?: "")
        recordSessionEvent(stateText)
    }

    val relayIdentifier = if (selectedDevice != null) {
        selectedDevice.relayIdentifier.orEmpty()
    } else {
        selectedIdentifier
    }
    val canConnect = relayIdentifier.isNotBlank() &&
        uiState.stage != PitBossRelayStage.CONNECTING &&
        uiState.stage != PitBossRelayStage.RELAY_CONNECTED
    val reportDetails = remember(
        reportDevice,
        scanSummary,
        uiState,
        gattInspection,
        sessionEventRevision,
    ) {
        BluetoothScanDiagnostics.details(
            summary = scanSummary ?: BluetoothScanSummary(
                startedAtUtc = "not available",
                finishedAtUtc = "not available",
                durationMillis = 0,
                scanMode = "LOW_LATENCY",
                totalResults = 0,
                capturedDeviceCount = 0,
                omittedDeviceCount = 0,
                error = "No completed scan is available.",
            ),
            selectedDevice = reportDevice,
            relayStage = uiState.stage.name,
            relayStatus = uiState.statusMessage,
            relayMessages = uiState.recentMessages.map { it.title to it.safeJson },
            inspection = gattInspection?.takeIf { reportDevice?.address == it.address },
            sessionEvents = sessionEvents,
            omittedSessionEventCount = omittedSessionEventCount,
        )
    }

    fun openReport(device: NearbyBluetoothDevice?) {
        recordSessionEvent("User opened the public Bluetooth diagnostic report preview.")
        reportDevice = device
        reportDetailsExpanded = true
        reportError = null
        reportNotice = null
        showReportDialog = true
    }

    fun submitReport(device: NearbyBluetoothDevice?) {
        val summary = scanSummary ?: return
        val deviceLabel = device?.displayName ?: "No controller detected"
        recordSessionEvent("User confirmed submission of the public Bluetooth diagnostic issue.")
        val detailText = BluetoothScanDiagnostics.details(
            summary = summary,
            selectedDevice = device,
            relayStage = uiState.stage.name,
            relayStatus = uiState.statusMessage,
            relayMessages = uiState.recentMessages.map { it.title to it.safeJson },
            inspection = gattInspection?.takeIf { device?.address == it.address },
            sessionEvents = sessionEvents,
            omittedSessionEventCount = omittedSessionEventCount,
        )
        val androidDevice = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Unknown Android device" }
        val report = FeedbackDiagnosticReport(
            referenceCode = "BLE-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.ROOT),
            occurredAtUtc = formatReportUtc(System.currentTimeMillis()),
            source = "Bluetooth controller discovery",
            summary = if (device == null) {
                "No controller was identified during a BLE scan; ${summary.totalResults} BLE result(s) were observed."
            } else {
                "Unverified Bluetooth device '${deviceLabel}'; ${device.observationCount} observation(s) captured."
            },
            details = detailText,
        )
        val request = FeedbackRequest(
            kind = FeedbackKind.DEVICE_DIAGNOSTIC,
            title = "Bluetooth discovery: $deviceLabel",
            description = "User-submitted Bluetooth discovery diagnostics for a controller not yet verified by PitTech.",
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = androidDevice,
            diagnosticReport = report,
        )
        reportSubmitting = true
        reportError = null
        FeedbackApi.submitAsync(request) { result ->
            reportSubmitting = false
            when (result) {
                is FeedbackSubmitResult.Success -> {
                    showReportDialog = false
                    reportNotice = result.issueNumber
                        ?.let { "Diagnostics submitted as GitHub issue #$it. Share the issue number with me so I can inspect the capture." }
                        ?: "Diagnostics submitted to GitHub. Share the issue number with me so I can inspect the capture."
                }
                is FeedbackSubmitResult.Failure -> reportError = result.message
            }
        }
    }

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
                Text("Turn on the controller and keep it nearby. Scan, select its Bluetooth entry, then inspect BLE services. That opens a read-only GATT session and attempts limited reads from characteristics marked readable; it never writes to the controller.")
                Text("The report preview contains the selected controller's advertisements, GATT services and readable values, plus timestamped PitTech test events. Nothing uploads until you review and submit the preview; GitHub issues are public.")
                Text("The optional Pit Boss relay test requires the controller to be online through the Pit Boss app's Wi-Fi setup. Send me the issue number after submission so I can examine the raw report.")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    pendingBluetoothAction = "SCAN"
                    if (!hasScanPermission || (!hasConnectPermission && !bluetoothConnectPermissionRequested)) {
                        bluetoothConnectPermissionRequested = true
                        permissionLauncher.launch(permissionsToRequest)
                    } else {
                        startScan()
                    }
                },
                enabled = !isScanning && !isInspectingGatt,
                modifier = Modifier.testTag("pitboss-scan"),
            ) {
                Text(if (isScanning) "Scanning…" else "Scan nearby")
            }

            OutlinedButton(
                onClick = {
                    if (relayIdentifier.isNotBlank()) {
                        preferences.edit().putString("pitboss-grill-id", relayIdentifier).apply()
                        selectedIdentifier = relayIdentifier
                        client.connect(relayIdentifier)
                    }
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
        } else if (nearbyDevices.isEmpty() && selectedIdentifier.isNotBlank()) {
            Text("Saved Pit Boss relay ID: $selectedIdentifier", style = MaterialTheme.typography.bodyMedium)
        }

        if (nearbyDevices.isNotEmpty()) {
            Text("Nearby Bluetooth devices (${nearbyDevices.size})", style = MaterialTheme.typography.titleMedium)
            nearbyDevices.forEachIndexed { index, device ->
                val selected = device.key == selectedDeviceKey
                val approved = ControllerSupportRegistry.isApproved(device)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedDeviceKey = device.key
                            device.relayIdentifier?.let { selectedIdentifier = it }
                            scanMessage = "Selected ${device.displayName}."
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
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${ControllerSupportRegistry.label(device)} · ${device.observationCount} observations · ${device.advertisements.size} distinct advertisements · ${device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (device.relayIdentifier != null) {
                            Text("Pit Boss relay ID candidate: ${device.relayIdentifier}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selected && scanSummary != null && !isScanning) {
                                OutlinedButton(
                                    onClick = {
                                        if (hasConnectPermission) {
                                            startGattInspection(device)
                                        } else {
                                            pendingBluetoothAction = "GATT"
                                            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                                        }
                                    },
                                    enabled = !isInspectingGatt,
                                    modifier = Modifier.testTag("ble-gatt-inspect-$index"),
                                ) {
                                    Text(if (isInspectingGatt) "Inspecting…" else "Inspect BLE services")
                                }
                            }
                            if (!approved && scanSummary != null && !isScanning) {
                                OutlinedButton(
                                    onClick = { openReport(device) },
                                    enabled = !isInspectingGatt,
                                    modifier = Modifier.testTag("ble-report-$index"),
                                ) {
                                    Text("Review diagnostics")
                                }
                            }
                            if (selected) {
                                Text("Selected", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        } else if (!isScanning && scanSummary != null) {
            OutlinedButton(
                onClick = { openReport(null) },
                modifier = Modifier.testTag("ble-empty-scan-report"),
            ) {
                Text("Report empty scan")
            }
        }

        selectedDevice?.let { device ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Read-only BLE inspection", style = MaterialTheme.typography.titleSmall)
                    Text("PitTech discovers the selected controller's GATT services and characteristic properties, then reads up to 20 characteristics marked READ. It does not explicitly bond, write values, or subscribe to notifications.")
                    gattStatusMessage?.let { Text("Inspection status: " + it) }
                    gattInspection?.takeIf { it.address == device.address }?.let { result ->
                        Text(
                            "Result: " + result.outcome +
                                " · " + result.services.size + " services · " +
                                result.reads.size + " read result(s)",
                            modifier = Modifier.testTag("ble-gatt-result"),
                        )
                    }
                    if (isInspectingGatt) {
                        TextButton(
                            onClick = { gattInspector.cancel() },
                            modifier = Modifier.testTag("ble-gatt-cancel"),
                        ) { Text("Stop inspection") }
                    }
                }
            }
        }

        reportNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        reportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

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
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
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
                Text("Relay messages are redacted before display. They are included in a device report only if you review and submit that report.")
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

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { if (!reportSubmitting) showReportDialog = false },
            title = { Text("Review Bluetooth diagnostics") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Submitting creates a public GitHub issue. The report includes the selected controller's Bluetooth address when available, advertisements, GATT service/characteristic/descriptor details, and values from attempted read-only characteristic reads, plus timestamped PitTech scan/connection/relay events and app/device details. It does not include other nearby devices, cook records, or photos. Review any raw values before submitting.")
                    Text("Review the data below before sending. Do not submit if it contains information you do not want public.")
                    TextButton(
                        onClick = { reportDetailsExpanded = !reportDetailsExpanded },
                        modifier = Modifier.testTag("ble-report-preview-toggle"),
                    ) {
                        Text(if (reportDetailsExpanded) "Hide full report" else "Preview full report")
                    }
                    if (reportDetailsExpanded) {
                        Card {
                            Text(
                                reportDetails,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                                    .testTag("ble-report-preview"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    reportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !reportSubmitting,
                    onClick = { submitReport(reportDevice) },
                    modifier = Modifier.testTag("ble-report-submit"),
                ) {
                    if (reportSubmitting) {
                        CircularProgressIndicator()
                    } else {
                        Text("Submit public report")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !reportSubmitting,
                    onClick = { showReportDialog = false },
                ) { Text("Cancel") }
            },
        )
    }
}

private fun formatReportUtc(timestampMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(timestampMillis))
