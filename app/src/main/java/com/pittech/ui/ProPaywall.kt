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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.ads.AdEntitlement
import com.pittech.billing.CommunityBenchmarksLoader
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
import com.pittech.domain.MemoryCustomizationBacking
import com.pittech.domain.ProCustomizationStore
import com.pittech.domain.ProPersonalLibrary
import kotlinx.coroutines.launch

/** One dialog for every Pro entry point. The destination follows the verified
 * entitlement state; Live and Finish flows never open this dialog. */
@Composable
internal fun ProGateDialog(
    subscription: SubscriptionManager,
    previouslyEntitled: Boolean,
    onSignIn: () -> Unit,
    benchmarks: CommunityBenchmarksLoader = CommunityBenchmarksLoader(session = { null }),
    personal: ProPersonalLibrary = ProPersonalLibrary.EMPTY,
    customization: ProCustomizationStore = ProCustomizationStore(MemoryCustomizationBacking()),
) {
    val state by subscription.presentation.collectAsStateWithLifecycle()
    val entry = state.paywallEntry ?: return
    // Resolved here in the host screen's composition: inside the AlertDialog
    // below, LocalContext is the dialog's ContextThemeWrapper, not the
    // Activity, so a lookup there would silently null out and disable the
    // Subscribe button.
    val activity = LocalContext.current as? Activity
    val destination = proDestination(state.signedIn, state.entitlement, state.serverState, previouslyEntitled)
    var showingRecap by remember(entry) { mutableStateOf(false) }
    val entitled = state.entitlement == AdEntitlement.ENTITLED
    // Sign-in from the gate is a detour, not an exit (Slice 8, PL-15 /
    // DD-10): park the entry and close the gate so the account surface is
    // reachable; when the account surface closes, the host resumes the
    // detour and this gate reopens on the same destination.
    val detourSignIn = { subscription.beginSignInDetour(); onSignIn() }
    when (destination) {
        ProDestination.LOADING -> ProLoadingDialog(subscription, entry)
        ProDestination.PAYWALL -> ProPaywallDialog(subscription, entry, detourSignIn, activity)
        ProDestination.PRO -> if (showingRecap && (entry == ProPlacement.ANALYSIS || entry == ProPlacement.MORE)) {
            ProRecapDialog(subscription, personal, customization, entitled, onBack = { showingRecap = false })
        } else {
            when (entry) {
                ProPlacement.BENCHMARKS -> CommunityBenchmarksDialog(subscription, entry, benchmarks, detourSignIn, activity)
                ProPlacement.ANALYSIS -> ProPersonalAnalysisDialog(subscription, entry, personal, onRecap = { showingRecap = true })
                ProPlacement.CURVES -> ProAdvancedCurvesDialog(subscription, entry, personal, customization, entitled)
                ProPlacement.THEMES -> ProCustomizationDialog(subscription, entry, customization)
                ProPlacement.MORE -> ProMoreHubDialog(subscription, entry, onRecap = { showingRecap = true })
            }
        }
        ProDestination.EXPIRED_FALLBACK -> ProExpiredFallbackDialog(subscription, entry, detourSignIn, activity)
    }
}

/** Contextual Pro entry rows for Insights. Rendered only when the caller
 * passes a handler; the free tools above are untouched.
 *
 * TURN-IN (Slice 2, Area 02 `history-populated` "Go deeper" lane; the same
 * rows are Area 07's canonical entries and Slice 7 reconciles their
 * destinations): each row is a laminated card with the marigold ◆ marker,
 * the destination's name in the banner face, a PRO tag chip, and its
 * factual preview line. */
@Composable
internal fun ProInsightsEntries(onEntry: (ProPlacement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TurnInMetaLabel("Deeper analysis")
        ProEntryRow(ProPlacement.ANALYSIS, onEntry)
        ProEntryRow(ProPlacement.CURVES, onEntry)
        ProEntryRow(ProPlacement.BENCHMARKS, onEntry)
    }
}

@Composable
internal fun ProEntryRow(entry: ProPlacement, onClick: (ProPlacement) -> Unit) {
    TurnInCard(
        modifier = Modifier.fillMaxWidth().testTag(entry.entryTag),
        onClick = { onClick(entry) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text("◆", style = PitTechTextStyles.body, color = pitTechTokens().marigold)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TurnInBannerText(entry.title)
                    ProTagChip()
                }
                Text(entry.preview, style = PitTechTextStyles.body, color = pitTechTokens().inkMuted)
            }
        }
    }
}

/** The marigold PRO tag (§3.1): a fill, so its text is navy on-accent,
 * never white. Shared by the entry rows here and the Pro hub (Slice 2);
 * Slice 7 reconciles the remaining Pro surfaces to it. */
@Composable
internal fun ProTagChip() {
    val tokens = pitTechTokens()
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tokens.marigold,
        contentColor = tokens.onAccent,
    ) {
        Text(
            "PRO",
            style = PitTechTextStyles.monoMeta,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProLoadingDialog(subscription: SubscriptionManager, entry: ProPlacement) {
    val scope = rememberCoroutineScope()
    // PL-22 (DD-6): the Pro status wait is a status surface — full screen.
    FullScreenSurface(
        onDismiss = { subscription.closePaywall() },
        title = entry.title,
        actions = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    ) {
            Column(Modifier.fillMaxWidth().testTag("pro-loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(LoadingCopy.CHECKING_PRO, style = MaterialTheme.typography.bodyLarge)
                }
                Text("Your cooks, exports, and free tools stay available while this loads.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { scope.launch { subscription.reconcile() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
            }
    }
}

@Composable
internal fun ProPaywallDialog(subscription: SubscriptionManager, entry: ProPlacement, onSignIn: () -> Unit, activity: Activity?) {
    // PL-22 (DD-6): the paywall is a full offer surface — full screen.
    FullScreenSurface(
        onDismiss = { subscription.closePaywall() },
        title = "PitTech Pro",
        actions = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-paywall-close")) { Text("Close") } },
    ) { ProPaywallContent(subscription, entry, onSignIn, activity) }
}

@Composable
private fun ProPaywallContent(subscription: SubscriptionManager, entry: ProPlacement, onSignIn: () -> Unit, activity: Activity?) {
    val state by subscription.presentation.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var confirmingRelease by remember { mutableStateOf(false) }
    val product = (state.productState as? ProProductState.Ready)?.product
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-paywall"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
        // TURN-IN (Slice 2, Area 02 `pro-gate`): the opens-with-Pro card —
        // the frame's eyebrow over the destination's own preview bullets.
        TurnInCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TurnInMetaLabel("With Pro, this opens")
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
                Text(billing, style = PitTechTextStyles.bannerTitle, modifier = Modifier.testTag("pro-price"))
                Text(
                    "Payment is charged through Google Play after PitTech verifies the purchase. Subscriptions are managed in the store, not in PitTech.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PitTech Pro isn't available on this build yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("pro-product-unavailable"),
                    )
                    // PL-17: the unavailable state is not a dead end — the
                    // user can re-check the store product and their
                    // entitlement without leaving the gate.
                    OutlinedButton(
                        onClick = { scope.launch { subscription.loadProduct(); subscription.reconcile() } },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("pro-check-again"),
                    ) { Text("Check again") }
                }
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
private fun ProExpiredFallbackDialog(subscription: SubscriptionManager, entry: ProPlacement, onSignIn: () -> Unit, activity: Activity?) {
    var resubscribing by remember { mutableStateOf(false) }
    if (resubscribing) {
        ProPaywallDialog(subscription, entry, onSignIn, activity)
        return
    }
    val scope = rememberCoroutineScope()
    // PL-22 (DD-6): the expired-Pro fallback is a status surface — a sheet.
    SheetSurface(
        onDismiss = { subscription.closePaywall() },
        title = entry.title,
        actions = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    ) {
            Column(Modifier.fillMaxWidth().testTag("pro-expired-fallback"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                TurnInBannerText("PitTech Pro ended on this account.")
                Text(
                    "Your recorded cooks, exported files, and free tools keep working. Previously saved exports stay where you left them — nothing was deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { resubscribing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-resubscribe")) {
                    Text("See PitTech Pro")
                }
                OutlinedButton(onClick = { scope.launch { subscription.reconcile() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Check again") }
            }
    }
}

