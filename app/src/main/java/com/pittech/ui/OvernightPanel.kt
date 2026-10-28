package com.pittech.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Flowing Slice 2 (E3): the Overnight panel — the whole safety net
 * assembled in one place on the Live board, so at 11 PM the cook can see
 * in one glance whether the 3 AM wake-up is real: notifications can
 * reach the phone, something is watching the pit, the missing-readings
 * alert is on, and a test alert that ACTUALLY FIRES to rehearse it.
 */
@Composable
internal fun OvernightPanelContent(
    notificationsAllowed: Boolean,
    watchingLabel: String?,
    missingRecordId: String?,
    missingEnabled: Boolean,
    nextReminder: Pair<String, Long>?,
    onToggleMissing: (String) -> Unit,
    onTestAlert: () -> Unit,
) {
    val tokens = pitTechTokens()
    var testSent by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NetRow(
            ok = notificationsAllowed,
            label = "Notifications",
            detail = if (notificationsAllowed) "On — alerts can reach this phone" else "Off — no alert can wake you until notifications are on",
        )
        NetRow(
            ok = watchingLabel != null,
            label = "Watching the pit",
            detail = watchingLabel ?: "Nothing is recording or attached — readings you log by hand are all the net has",
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (missingEnabled) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, tint = if (missingEnabled) tokens.ember else tokens.marigold, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Missing readings alert", style = PitTechTextStyles.bodyEmphasis)
                Text(
                    when {
                        missingRecordId == null -> "Not set up yet — it switches on by itself when a temperature source attaches"
                        missingEnabled -> "On — fires when readings stop arriving"
                        else -> "Off — readings can stop in silence"
                    },
                    style = PitTechTextStyles.body, color = tokens.inkMuted,
                )
            }
            if (missingRecordId != null) {
                Switch(checked = missingEnabled, onCheckedChange = { onToggleMissing(missingRecordId) }, modifier = Modifier.testTag("overnight-missing-toggle"))
            }
        }
        NetRow(
            ok = nextReminder != null,
            label = "Next reminder",
            detail = nextReminder?.let { "${it.first} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.second))}" } ?: "None set — nothing is scheduled to check in",
        )
        Button(
            onClick = { onTestAlert(); testSent = true },
            modifier = Modifier.fillMaxWidth().testTag("overnight-test-alert"),
        ) { Text("Send test alert") }
        if (testSent) {
            Text(
                "Test alert sent. If it didn't buzz this phone, check notification settings before you trust the night to it.",
                style = PitTechTextStyles.body, color = tokens.inkMuted,
                modifier = Modifier.testTag("overnight-test-sent"),
            )
        }
    }
}

@Composable
private fun NetRow(ok: Boolean, label: String, detail: String) {
    val tokens = pitTechTokens()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, tint = if (ok) tokens.ember else tokens.marigold, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = PitTechTextStyles.bodyEmphasis)
            Text(detail, style = PitTechTextStyles.body, color = tokens.inkMuted)
        }
    }
}
