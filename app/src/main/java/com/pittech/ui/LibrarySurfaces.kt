package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * PL-30 (#172): the one shared "not available yet" pattern — icon tile,
 * title, message, an optional note about what works instead, and one
 * route onward. Every not-available surface on both platforms renders
 * through this shape with [NotAvailableCopy] strings.
 */
@Composable internal fun NotAvailablePlaceholder(
    icon: ImageVector,
    title: String,
    message: String,
    note: String? = null,
    onwardLabel: String,
    onOnward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
            Icon(icon, null, Modifier.padding(18.dp).size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (note != null) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOnward, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(onwardLabel) }
    }
}

/** Native, honest interim surface while the community release is built. */
@Composable internal fun DiscoveryWelcome(modifier: Modifier = Modifier, onRecipes: () -> Unit) {
    NotAvailablePlaceholder(
        icon = Icons.Filled.Explore,
        title = NotAvailableCopy.DISCOVER_TITLE,
        message = NotAvailableCopy.DISCOVER_MESSAGE,
        onwardLabel = NotAvailableCopy.DISCOVER_ONWARD,
        onOnward = onRecipes,
        modifier = modifier,
    )
}

@Composable internal fun MoreHome(modifier: Modifier = Modifier, onDevices: () -> Unit, onSettings: () -> Unit, onImportCode: () -> Unit = {}, onSharedCodes: () -> Unit = {}, onAccount: (() -> Unit)? = null, onCookbook: (() -> Unit)? = null, onCommunity: (() -> Unit)? = null, onPro: (() -> Unit)? = null, accountSubtitle: String = "Optional email sign-in and this device’s session") {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your cookbook stays yours", style = MaterialTheme.typography.titleLarge)
                Text("Cook, save recipes and share files without an account. Optional accounts and private sync arrive with community recipes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MoreDestination("Devices & connections", "Read-only monitoring and saved equipment", Icons.Filled.Thermostat, "more-devices", onDevices)
        // PL-14 (#172): once signed in, the Account row carries the private
        // sync status so it is visible without opening the sheet.
        if (onAccount != null) MoreDestination("Account", accountSubtitle, Icons.Filled.Person, "more-account", onAccount)
        if (onCommunity != null) MoreDestination("Community controls", "Reports, saved requests and blocked publishers", Icons.Filled.Person, "more-community-controls", onCommunity)
        if (onCookbook != null) MoreDestination("My cookbook", "Public identity, publications and interrupted changes", Icons.Filled.Person, "more-cookbook", onCookbook)
        MoreDestination("Settings & support", "Appearance, units, data and feedback", Icons.Filled.Settings, "more-settings", onSettings)
        MoreDestination("Import by code", "Preview a shared Cook Pack without an account", Icons.Filled.Explore, "more-relay-import", onImportCode)
        MoreDestination("My shared codes", "Renew or revoke codes created on this device", Icons.Filled.Explore, "more-relay-owned", onSharedCodes)
        if (onPro != null) MoreDestination("PitTech Pro", "Ad-free cooking with deeper analysis", Icons.Filled.Star, "more-pro", onPro)
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
