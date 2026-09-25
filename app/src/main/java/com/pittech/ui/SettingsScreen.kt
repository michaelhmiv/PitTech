package com.pittech.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.CooksViewModel

@Composable
fun SettingsScreen(
    viewModel: CooksViewModel,
    themeMode: PitTechThemeMode,
    onThemeModeChange: (PitTechThemeMode) -> Unit,
    temperatureUnit: String,
    weightUnit: String,
    onTemperatureUnitChange: (String) -> Unit,
    onWeightUnitChange: (String) -> Unit,
    privacyOptionsRequired: Boolean = false,
    onShowPrivacyOptions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_XLSX)
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_ZIP)
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri, CooksViewModel.FORMAT_CSV)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.previewImport(uri)
    }

    if (importPreview != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Review backup contents") },
            text = {
                Text("This archive contains ${importPreview!!.cookCount} cooks, ${importPreview!!.dishCount} dishes, and ${importPreview!!.photoCount} photos. PitTech will add cooks that are not already in this phone's library and skip duplicate cook IDs.")
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport, enabled = !busy) { Text("Restore cooks") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancel") } },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SectionCard("Appearance") {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PitTechThemeMode.values().forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        modifier = Modifier.weight(1f).testTag("theme-mode-${mode.name.lowercase()}"),
                    )
                }
            }
            Text(
                when (themeMode) {
                    PitTechThemeMode.SYSTEM -> "PitTech follows your device appearance."
                    PitTechThemeMode.LIGHT -> "Light appearance is selected."
                    PitTechThemeMode.DARK -> "Dark appearance is selected."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionCard("Units") {
            Text("Temperature", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("°F", "°C").forEach { unit ->
                    if (temperatureUnit == unit) Button(onClick = { onTemperatureUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                    else OutlinedButton(onClick = { onTemperatureUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                }
            }
            Text("Weight", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("lb", "kg").forEach { unit ->
                    if (weightUnit == unit) Button(onClick = { onWeightUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                    else OutlinedButton(onClick = { onWeightUnitChange(unit) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(unit) }
                }
            }
            Text("Changing a preferred unit affects new entries. Existing records keep the unit in which you entered them.", style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard("Your data") {
            Text("Cook records and photos stay on this phone. No account is needed. Save a backup somewhere you control so you can restore it after changing phones or reinstalling PitTech.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { zip.launch("PitTech_Backup.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export complete backup (ZIP)") }
            OutlinedButton(onClick = { xlsx.launch("PitTech_Cook_Log.xlsx") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export Excel workbook") }
            OutlinedButton(onClick = { csv.launch("PitTech_Readings.csv") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Export temperature readings (CSV)") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Restore from ZIP backup") }
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text("Working with your cook data…", style = MaterialTheme.typography.bodyLarge) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyLarge) }
        }

        SectionCard("Privacy and offline use") {
            Text("You can start, edit, review, and export cooks without internet access. PitTech has no sign-in screen. Photos are copied into the app's private storage so the cook log can use them offline.", style = MaterialTheme.typography.bodyLarge)
            Text("A backup file is your portable copy. Keep it in a place you can find again.", style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard("Ads and privacy") {
            Text(
                "PitTech may show a Google ad in cook history after you have at least four completed cooks and no cook is active. When an ad request is allowed by your privacy choices, Google's SDK may collect and share your IP address, ad interactions, diagnostics, and device or account identifiers for advertising, analytics, and fraud prevention. Google encrypts this data in transit. PitTech does not attach cook records or photos to ad requests.",
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(
                onClick = { uriHandler.openUri("https://pittech-privacy-production.up.railway.app/") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Read the privacy policy")
            }
            if (privacyOptionsRequired) {
                OutlinedButton(
                    onClick = onShowPrivacyOptions,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("Privacy options")
                }
            }
        }
    }
}
