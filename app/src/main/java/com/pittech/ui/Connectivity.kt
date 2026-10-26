package com.pittech.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Slice 10 (PL-23 / DD-8): the app-wide offline state. Before this slice
 * the app had NO offline UI at all — sync and publishing failures were
 * the only signal. [rememberOnlineState] tracks the default network;
 * [OfflineBanner] renders the one banner wording ([OfflineCopy.BANNER])
 * under the top bar of the primary surfaces while offline, and surfaces
 * that hold changes for sync show [OfflineCopy.CHANGES_WAIT] in place.
 */
@Composable
internal fun rememberOnlineState(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var online by remember(manager) {
        mutableStateOf(
            manager?.activeNetwork
                ?.let { manager.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true,
        )
    }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = false }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        manager?.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return online
}

@Composable
internal fun OfflineBanner() {
    if (rememberOnlineState()) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("offline-banner")) {
        Text(
            OfflineCopy.BANNER,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
