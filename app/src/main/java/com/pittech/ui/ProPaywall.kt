package com.pittech.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.billing.ProDestination
import com.pittech.billing.ProPlacement
import com.pittech.billing.ProProductState
import com.pittech.billing.ProPurchaseState
import com.pittech.billing.SubscriptionManager
import com.pittech.billing.entryTag
import com.pittech.billing.preview
import com.pittech.billing.proBillingText
import com.pittech.billing.proDestination
import com.pittech.billing.proPreviewBullets
import com.pittech.billing.proShowsPurchaseOffer
import com.pittech.billing.title
import kotlinx.coroutines.launch

/** One dialog for every Pro entry point. The destination follows the verified
 * entitlement state; Live and Finish flows never open this dialog. */
@Composable
internal fun ProGateDialog(subscription: SubscriptionManager, previouslyEntitled: Boolean, onSignIn: () -> Unit) {
    val state by subscription.presentation.collectAsStateWithLifecycle()
    val entry = state.paywallEntry ?: return
    val destination = proDestination(state.signedIn, state.entitlement, state.serverState, previouslyEntitled)
    when (destination) {
        ProDestination.LOADING -> ProLoadingDialog(subscription, entry)
        ProDestination.PAYWALL -> ProPaywallDialog(subscription, entry, onSignIn)
        ProDestination.PRO -> ProPlaceholderDialog(subscription, entry)
        ProDestination.EXPIRED_FALLBACK -> ProExpiredFallbackDialog(subscription, entry, onSignIn)
    }
}

/** Contextual Pro entry rows for Insights. Rendered only when the caller
 * passes a handler; the free tools above are untouched. */
@Composable
internal fun ProInsightsEntries(onEntry: (ProPlacement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Deeper analysis", style = MaterialTheme.typography.titleMedium)
        ProEntryRow(ProPlacement.ANALYSIS, onEntry)
        ProEntryRow(ProPlacement.CURVES, onEntry)
    }
}

@Composable
internal fun ProEntryRow(entry: ProPlacement, onClick: (ProPlacement) -> Unit) {
    Card(
        onClick = { onClick(entry) },
        modifier = Modifier.fillMaxWidth().testTag(entry.entryTag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title + " · Pro", style = MaterialTheme.typography.titleMedium)
            Text(entry.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProLoadingDialog(subscription: SubscriptionManager, entry: ProPlacement) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(entry.title) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("pro-loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Checking PitTech Pro status…", style = MaterialTheme.typography.bodyLarge)
                Text("Your cooks, exports, and free tools stay available while this loads.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { scope.launch { subscription.reconcile() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

@Composable
private fun ProPaywallDialog(subscription: SubscriptionManager, entry: ProPlacement, onSignIn: () -> Unit) {
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text("PitTech Pro") },
        text = { ProPaywallContent(subscription, entry, onSignIn) },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-paywall-close")) { Text("Close") } },
    )
}

@Composable
private fun ProPaywallContent(subscription: SubscriptionManager, entry: ProPlacement, onSignIn: () -> Unit) {
    val state by subscription.presentation.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val activity = LocalContext.current as? Activity
    var confirmingRelease by remember { mutableStateOf(false) }
    val product = (state.productState as? ProProductState.Ready)?.product
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-paywall"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                proPreviewBullets(entry).forEach { Text("✓ $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (!proShowsPurchaseOffer(state.entitlement)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("pro-entitled-state")) {
                Text("You're covered by PitTech Pro on this account.", style = MaterialTheme.typography.titleMedium)
                Text("There's no second subscription to buy. Manage or cancel anytime in Google Play.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val billing = proBillingText(product)
            if (billing != null) {
                Text(billing, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pro-price"))
                Text(
                    "Payment is charged through Google Play after PitTech verifies the purchase. Subscriptions are managed in the store, not in PitTech.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "PitTech Pro isn't available on this build yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("pro-product-unavailable"),
                )
            }
        }
        if (!state.signedIn) {
            Text(
                if (subscription.accountsEnabled) "Sign in to subscribe with your PitTech account. Purchases are bound to the verified account."
                else "Accounts arrive with community recipes and private sync. Subscribing needs a PitTech account first.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("pro-signin-required"),
            )
            if (subscription.accountsEnabled) {
                OutlinedButton(onClick = onSignIn, modifier = Modifier.heightIn(min = 48.dp).testTag("pro-signin")) { Text("Sign in") }
            }
        }
        if (proShowsPurchaseOffer(state.entitlement) && proBillingText(product) != null && state.signedIn) {
            Button(
                onClick = { activity?.let { scope.launch { subscription.subscribe(it) } } },
                enabled = !state.busy && activity != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-subscribe"),
            ) { Text(if (state.busy) "Working…" else "Subscribe") }
        }
        if (state.isStorePending || state.purchaseState is ProPurchaseState.PendingStore) {
            Text(
                "Your purchase is pending with Google Play. Pro unlocks after the store confirms it; nothing is charged by PitTech.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("pro-pending"),
            )
        }
        (state.purchaseState as? ProPurchaseState.Conflict)?.let { conflict ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pro-conflict")) {
                Text(conflict.recovery, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { scope.launch { subscription.reconcile() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
            }
        }
        (state.purchaseState as? ProPurchaseState.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pro-error")) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pro-notice")) }
        OutlinedButton(
            onClick = { scope.launch { subscription.restore() } },
            enabled = !state.busy && state.signedIn,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-restore"),
        ) { Text("Restore purchases") }
        if (!state.signedIn) {
            Text("Restoring needs the PitTech account your purchase is bound to.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { uri.openUri("https://play.google.com/store/account/subscriptions?package=com.pittech") }, modifier = Modifier.testTag("pro-manage")) {
            Text("Manage subscription")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TextButton(onClick = { uri.openUri("https://pittech-privacy-production.up.railway.app/") }, modifier = Modifier.testTag("pro-privacy")) { Text("Privacy policy") }
            TextButton(onClick = { uri.openUri("https://play.google.com/about/play-terms/") }, modifier = Modifier.testTag("pro-terms")) { Text("Terms") }
        }
        if (state.hasRelease && state.signedIn) {
            TextButton(onClick = { confirmingRelease = true }, modifier = Modifier.testTag("pro-release")) { Text("Release purchase from this account") }
        }
    }
    if (confirmingRelease) {
        AlertDialog(
            onDismissRequest = { confirmingRelease = false },
            title = { Text("Release this purchase?") },
            text = { Text("Pro on this account ends immediately. Another account can claim the purchase through a fresh verification.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmingRelease = false; scope.launch { subscription.releasePurchase() } },
                    modifier = Modifier.testTag("pro-release-confirm"),
                ) { Text("Release purchase") }
            },
            dismissButton = { TextButton(onClick = { confirmingRelease = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProPlaceholderDialog(subscription: SubscriptionManager, entry: ProPlacement) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(entry.title) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("pro-placeholder"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "This Pro view arrives with the next PitTech update. Your recorded cooks, exports, and free tools are unchanged.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { uri.openUri("https://play.google.com/store/account/subscriptions?package=com.pittech") }, modifier = Modifier.testTag("pro-manage")) {
                    Text("Manage subscription")
                }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

@Composable
private fun ProExpiredFallbackDialog(subscription: SubscriptionManager, entry: ProPlacement, onSignIn: () -> Unit) {
    var resubscribing by remember { mutableStateOf(false) }
    if (resubscribing) {
        ProPaywallDialog(subscription, entry, onSignIn)
        return
    }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(entry.title) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("pro-expired-fallback"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                Text("PitTech Pro ended on this account.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your recorded cooks, exported files, and free tools keep working. Previously saved exports stay where you left them — nothing was deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { resubscribing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-resubscribe")) {
                    Text("See PitTech Pro")
                }
                OutlinedButton(onClick = { scope.launch { subscription.reconcile() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Check again") }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

