package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Native, honest interim surface while the community release is built. */
@Composable internal fun DiscoveryWelcome(modifier: Modifier = Modifier, onRecipes: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Filled.Explore, null, Modifier.padding(18.dp).size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text("Find your next cook", style = MaterialTheme.typography.headlineMedium)
        Text("Community recipes are being prepared for the next PitTech release. You'll be able to browse, save and cook them without an account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onRecipes, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open your recipes") }
    }
}

@Composable internal fun MoreHome(modifier: Modifier = Modifier, onDevices: () -> Unit, onSettings: () -> Unit, onImportCode: () -> Unit = {}, onSharedCodes: () -> Unit = {}) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your cookbook stays yours", style = MaterialTheme.typography.titleLarge)
                Text("Cook, save recipes and share files without an account. Optional accounts and private sync arrive with community recipes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MoreDestination("Devices & connections", "Read-only monitoring and saved equipment", Icons.Filled.Thermostat, "more-devices", onDevices)
        MoreDestination("Settings & support", "Appearance, units, data and feedback", Icons.Filled.Settings, "more-settings", onSettings)
        MoreDestination("Import by code", "Preview a shared Cook Pack without an account", Icons.Filled.Explore, "more-relay-import", onImportCode)
        MoreDestination("My shared codes", "Renew or revoke codes created on this device", Icons.Filled.Explore, "more-relay-owned", onSharedCodes)
    }
}

@Composable private fun MoreDestination(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(18.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, null)
        }
    }
}
