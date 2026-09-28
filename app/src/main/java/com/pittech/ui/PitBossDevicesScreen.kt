package com.pittech.ui

import android.Manifest
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
import com.pittech.BuildConfig
import com.pittech.FeedbackApi
import com.pittech.FeedbackDiagnosticReport
import com.pittech.FeedbackKind
import com.pittech.FeedbackRequest
import com.pittech.FeedbackSubmitResult
import com.pittech.devices.BluetoothGattInspectionReport
import com.pittech.devices.BluetoothGattInspector
import com.pittech.devices.BluetoothScanSummary
import com.pittech.devices.ControllerProbeDiagnostics
import com.pittech.devices.ControllerProbeReport
import com.pittech.devices.ControllerProtocolDetector
import com.pittech.devices.ControllerSupportRegistry
import com.pittech.devices.MongooseBleRpcProbe
import com.pittech.devices.NearbyBluetoothDevice
import com.pittech.devices.ControllerBleDiscovery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The experimental controller entry point. The historical function name is
 * retained so navigation does not churn, but the workflow is intentionally
 * manufacturer-neutral: discovery first, protocol identification second.
 */
@Composable
fun PitBossDevicesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val discovery = remember(context) { ControllerBleDiscovery(context) }
    val gattInspector = remember(context) { BluetoothGattInspector(context) }
    val mongooseProbe = remember(context) { MongooseBleRpcProbe(context) }

    var nearbyDevices by remember { mutableStateOf(emptyList<NearbyBluetoothDevice>()) }
    var selectedDeviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var scanSummary by remember { mutableStateOf<BluetoothScanSummary?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var isProbing by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    var gattInspection by remember { mutableStateOf<BluetoothGattInspectionReport?>(null) }
    var probeReport by remember { mutableStateOf<ControllerProbeReport?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf("SCAN") }

    var sessionEvents by remember { mutableStateOf(emptyList<String>()) }
    var omittedSessionEventCount by remember { mutableStateOf(0) }
    var sessionRevision by remember { mutableStateOf(0) }

    var showReportDialog by remember { mutableStateOf(false) }
    var reportExpanded by remember { mutableStateOf(true) }
    var reportSubmitting by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    var reportNotice by remember { mutableStateOf<String?>(null) }

    fun recordSessionEvent(message: String) {
        val entry = formatReportUtc(System.currentTimeMillis()) + " " + message.take(350)
        if (sessionEvents.size >= 100) omittedSessionEventCount += 1
        sessionEvents = (sessionEvents + entry).takeLast(100)
        sessionRevision += 1
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

    fun startAutomaticProbe(device: NearbyBluetoothDevice) {
        val address = device.address
        if (address.isNullOrBlank()) {
            probeStatus = "Android did not expose a Bluetooth address. Grant Bluetooth connection permission and scan again."
            recordSessionEvent("Automatic controller interrogation could not start: Bluetooth address unavailable.")
            return
        }

        selectedDeviceKey = device.key
        gattInspection = null
        probeReport = null
        isProbing = true
        probeStatus = "Connecting and inventorying GATT services…"
        recordSessionEvent("Automatic interrogation started for selected controller '${device.displayName}'.")

        gattInspector.inspect(
            address = address,
            onProgress = { message -> probeStatus = message },
            onFinished = { inspection ->
                gattInspection = inspection
                recordSessionEvent(
                    "GATT inventory finished; services=${inspection.services.size}, " +
                        "characteristics=${inspection.totalCharacteristicCount}, " +
                        "readable=${inspection.readableCharacteristicCount}, reads=${inspection.reads.size}.",
                )

                probeStatus = "Identifying protocol family and probing safe capabilities…"
                mongooseProbe.probe(
                    address = address,
                    inspection = inspection,
                    onProgress = { message -> probeStatus = message },
                    onFinished = { report ->
                        probeReport = report
                        isProbing = false
                        probeStatus = report.outcome
                        recordSessionEvent(
                            "Protocol interrogation finished; protocols=" +
                                report.protocols.joinToString { it.name } +
                                ", rpcMethods=${report.rpcMethods.size}.",
                        )
                    },
                )
            },
        )
    }

    fun startScan() {
        mongooseProbe.closeSilently()
        gattInspector.closeSilently()
        isScanning = true
        isProbing = false
        nearbyDevices = emptyList()
        selectedDeviceKey = null
        scanSummary = null
        gattInspection = null
        probeReport = null
        probeStatus = null
        scanMessage = "Scanning nearby Bluetooth devices…"
        recordSessionEvent("Generic BLE scan started.")

        discovery.startScan(
            onDevices = { nearbyDevices = it },
            onFinished = { summary, message ->
                scanSummary = summary
                isScanning = false
                scanMessage = message ?: if (nearbyDevices.isEmpty()) {
                    "No Bluetooth devices were detected."
                } else {
                    "Found ${nearbyDevices.size} Bluetooth device(s). Select the controller to interrogate it automatically."
                }
                recordSessionEvent(
                    "BLE scan finished; results=${summary.totalResults}, " +
                        "retained=${summary.capturedDeviceCount}, omitted=${summary.omittedDeviceCount}.",
                )

            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val connectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            results[Manifest.permission.BLUETOOTH_CONNECT] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

        if (pendingAction == "PROBE") {
            val device = nearbyDevices.firstOrNull { it.key == selectedDeviceKey }
            if (connectGranted && device != null) {
                startAutomaticProbe(device)
            } else {
                isProbing = false
                probeStatus = "Bluetooth connection permission is required to interrogate this controller."
            }
        } else if (results[requiredScanPermission] == true ||
            ContextCompat.checkSelfPermission(context, requiredScanPermission) == PackageManager.PERMISSION_GRANTED
        ) {
            startScan()
        } else {
            isScanning = false
            scanMessage = "Bluetooth permission is required to discover nearby controllers."
        }
        pendingAction = "SCAN"
    }

    fun selectAndProbe(device: NearbyBluetoothDevice) {
        selectedDeviceKey = device.key
        if (hasConnectPermission) {
            startAutomaticProbe(device)
        } else {
            pendingAction = "PROBE"
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    val selectedDevice = nearbyDevices.firstOrNull { it.key == selectedDeviceKey }
    val reportDetails = remember(
        selectedDevice,
        scanSummary,
        gattInspection,
        probeReport,
        sessionRevision,
    ) {
        ControllerProbeDiagnostics.details(
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
            selectedDevice = selectedDevice,
            inspection = gattInspection,
            probe = probeReport,
            sessionEvents = sessionEvents,
            omittedSessionEventCount = omittedSessionEventCount,
        )
    }

    fun submitReport() {
        val summary = scanSummary ?: return
        val device = selectedDevice ?: return
        val details = ControllerProbeDiagnostics.details(
            summary = summary,
            selectedDevice = device,
            inspection = gattInspection,
            probe = probeReport,
            sessionEvents = sessionEvents,
            omittedSessionEventCount = omittedSessionEventCount,
        )
        val androidDevice = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Unknown Android device" }
        val report = FeedbackDiagnosticReport(
            referenceCode = "CTRL-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.ROOT),
            occurredAtUtc = formatReportUtc(System.currentTimeMillis()),
            source = "Automatic controller interrogation",
            summary = "Unverified controller '${device.displayName}'; automatic BLE/GATT/protocol fingerprint captured.",
            details = details,
        )
        val request = FeedbackRequest(
            kind = FeedbackKind.DEVICE_DIAGNOSTIC,
            title = "Controller interrogation: ${device.displayName}",
            description = "User-reviewed diagnostics for a controller not yet officially verified by PitTech.",
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
                        ?.let { "Controller diagnostics submitted as GitHub issue #$it." }
                        ?: "Controller diagnostics submitted to GitHub."
                }
                is FeedbackSubmitResult.Failure -> reportError = result.message
            }
        }
    }

    DisposableEffect(discovery, gattInspector, mongooseProbe) {
        onDispose {
            discovery.stopScan()
            gattInspector.closeSilently()
            mongooseProbe.closeSilently()
        }
    }

    LaunchedEffect(hasScanPermission, hasConnectPermission) {
        recordSessionEvent(
            "Bluetooth permission state: scan=$hasScanPermission, connect=$hasConnectPermission, API=${Build.VERSION.SDK_INT}.",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Controller diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Automatic BLE fingerprinting + safe capability probing",
            style = MaterialTheme.typography.titleMedium,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("How this works", style = MaterialTheme.typography.titleSmall)
                Text(
                    "PitTech first treats every controller as unknown. After you select a nearby device it automatically inventories its BLE/GATT surface, identifies known protocol families, and exercises only explicitly allowlisted observational RPCs.",
                )
                Text(
                    "Transport writes required to carry read-only RPC requests or enable notifications are allowed. Grill settings, ignition, temperature targets, motors, credentials, firmware, reboot, OTA, file writes, and unknown RPCs are never executed by the automatic probe.",
                )
                Text(
                    "If the controller is not officially supported, review the sanitized capture and submit it through the existing GitHub feedback relay so its fingerprint can become a verified PitTech profile.",
                )
            }
        }

        Button(
            onClick = {
                pendingAction = "SCAN"
                if (hasScanPermission && hasConnectPermission) {
                    startScan()
                } else {
                    permissionLauncher.launch(permissionsToRequest)
                }
            },
            enabled = !isScanning && !isProbing,
            modifier = Modifier.testTag("pitboss-scan"),
        ) {
            Text(if (isScanning) "Scanning…" else "Scan for controller")
        }

        scanMessage?.let { Text(it) }

        if (nearbyDevices.isNotEmpty()) {
            Text("Nearby Bluetooth devices", style = MaterialTheme.typography.titleMedium)
            nearbyDevices.forEachIndexed { index, device ->
                val selected = device.key == selectedDeviceKey
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isScanning && !isProbing) { selectAndProbe(device) }
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
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${device.observationCount} observations · ${device.advertisements.size} advertisement variants · ${device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            ControllerSupportRegistry.label(device),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (selected) Text("Selected · automatic interrogation " + if (isProbing) "running" else "ready")
                    }
                }
            }
        }

        selectedDevice?.let { device ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("Automatic controller inspection", style = MaterialTheme.typography.titleSmall)
                    if (isProbing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text(probeStatus ?: "Inspecting controller…")
                        }
                    } else {
                        Text(probeStatus ?: "Select this controller to begin.")
                    }
                    Text((if (gattInspection != null) "✓" else "○") + " GATT service/characteristic inventory")
                    Text((if (probeReport != null) "✓" else "○") + " Protocol-family detection")
                    Text((if (probeReport?.rpcMethods?.isNotEmpty() == true) "✓" else "○") + " RPC method inventory")
                    Text((if (probeReport?.observations?.isNotEmpty() == true) "✓" else "○") + " Safe observational capability probes")
                    Text(
                        if (probeReport != null) {
                            "✓ Fingerprint: " + ControllerProtocolDetector.fingerprint(
                                device,
                                gattInspection,
                                probeReport,
                            )
                        } else {
                            "○ Stable controller fingerprint"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        probeReport?.let { report ->
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("Inspection result", style = MaterialTheme.typography.titleMedium)
                    Text("Protocol: " + report.protocols.joinToString { it.displayName })
                    Text("RPC methods discovered: ${report.rpcMethods.size}")
                    report.transportCapabilities.forEach { (name, value) ->
                        Text("$name: $value", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("PitTech support: " + selectedDevice?.let(ControllerSupportRegistry::label).orEmpty())
                    TextButton(
                        onClick = { reportExpanded = !reportExpanded },
                        modifier = Modifier.testTag("controller-technical-details"),
                    ) {
                        Text(if (reportExpanded) "Hide technical details" else "Show technical details")
                    }
                    if (reportExpanded) {
                        Text(
                            report.rpcMethods.joinToString("\n").ifBlank { "No RPC inventory was returned." },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (selectedDevice != null && !ControllerSupportRegistry.isApproved(selectedDevice!!)) {
                        OutlinedButton(
                            onClick = {
                                reportExpanded = true
                                reportError = null
                                showReportDialog = true
                            },
                            modifier = Modifier.testTag("controller-submit-diagnostics"),
                        ) {
                            Text("Submit controller for support")
                        }
                    }
                }
            }
        }

        reportNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { if (!reportSubmitting) showReportDialog = false },
            title = { Text("Review controller diagnostics") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Submitting creates a public GitHub issue. PitTech removes the permanent Bluetooth address and redacts password-like fields, Wi-Fi SSIDs/BSSIDs, tokens, credentials, and similar values before building this report. Review the complete report below before sending.",
                    )
                    TextButton(
                        onClick = { reportExpanded = !reportExpanded },
                        modifier = Modifier.testTag("ble-report-preview-toggle"),
                    ) {
                        Text(if (reportExpanded) "Hide full report" else "Preview full report")
                    }
                    if (reportExpanded) {
                        Card {
                            Text(
                                reportDetails,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
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
                    onClick = ::submitReport,
                    modifier = Modifier.testTag("ble-report-submit"),
                ) {
                    if (reportSubmitting) CircularProgressIndicator() else Text("Submit public report")
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
