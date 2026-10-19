package com.pittech.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pittech.billing.CommunityBenchmarksLoader
import com.pittech.billing.CommunityBenchmarksState
import com.pittech.billing.CommunityCardContent
import com.pittech.billing.CommunityOmittedFigure
import com.pittech.billing.ProPlacement
import com.pittech.billing.SubscriptionManager
import com.pittech.billing.preview
import com.pittech.billing.title
import kotlinx.coroutines.launch

/** The benchmarks entry's entitled destination. Every other gate destination
 * (loading, paywall, expired fallback) renders through the shared Pro gate, so
 * this dialog only handles recorded figures and their honest states. */
@Composable
internal fun CommunityBenchmarksDialog(
    subscription: SubscriptionManager,
    entry: ProPlacement,
    benchmarks: CommunityBenchmarksLoader,
    onSignIn: () -> Unit,
    activity: Activity?,
) {
    val state by benchmarks.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(entry) { benchmarks.load() }
    DisposableEffect(entry) { onDispose { benchmarks.reset() } }
    // A stale entitled cache the server rejects converges through the loader's
    // one refresh, then lands on the same contextual paywall the gate shows —
    // with no figures decoded or rendered.
    if (state is CommunityBenchmarksState.SignedOut || state is CommunityBenchmarksState.Forbidden) {
        ProPaywallDialog(subscription, entry, onSignIn, activity)
        return
    }
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(entry.title) },
        text = {
            when (state) {
                is CommunityBenchmarksState.Loading -> Column(
                    Modifier.fillMaxWidth().testTag("community-loading"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Loading community benchmarks…", style = MaterialTheme.typography.bodyLarge)
                }
                is CommunityBenchmarksState.Loaded -> {
                    val snapshot = (state as CommunityBenchmarksState.Loaded).snapshot
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("community-benchmarks"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                        snapshot.cards.forEach { CommunityBenchmarkCard(it) }
                        snapshot.omitted.forEach { CommunityOmittedNote(it) }
                    }
                }
                is CommunityBenchmarksState.Empty -> Column(
                    Modifier.fillMaxWidth().testTag("community-empty"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("No community figures yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Benchmarks appear here when eligible published cooks are recorded. Your personal tools and Pro entries are unchanged.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is CommunityBenchmarksState.Unavailable -> {
                    Column(
                        Modifier.fillMaxWidth().testTag("community-unavailable"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Community benchmarks are unavailable right now.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Your cooks, exports, and free tools are unchanged.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = { scope.launch { benchmarks.load() } },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("community-retry"),
                        ) { Text("Try again") }
                    }
                }
                is CommunityBenchmarksState.SignedOut, is CommunityBenchmarksState.Forbidden -> Unit
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

@Composable
private fun CommunityBenchmarkCard(card: CommunityCardContent) {
    Column(Modifier.fillMaxWidth().testTag(card.testTag)) {
        SectionCard(card.title) {
            Text(card.bucketLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            card.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(card.coverageLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommunityOmittedNote(omitted: CommunityOmittedFigure) {
    Column(Modifier.fillMaxWidth().testTag(omitted.testTag)) {
        SectionCard(omitted.title) {
            Text(omitted.bucketLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(omitted.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
