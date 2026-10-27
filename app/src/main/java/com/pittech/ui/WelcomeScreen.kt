package com.pittech.ui

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pittech.R

/**
 * First-run welcome (#187, #172 PL-31): one screen, first launch only.
 * The seen flag lives in the shared "pittech-preferences" file, following the
 * PitTechThemeMode.PREFERENCE_KEY pattern.
 */
object WelcomePreferences {
    const val PREFERENCE_KEY = "welcome-seen-v1"

    fun hasSeen(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(PREFERENCE_KEY, false)

    fun markSeen(preferences: SharedPreferences) {
        // commit(), not apply(): the flag must be durable before the surface
        // changes — an apply() still in flight can be lost if the process is
        // replaced (e.g. the live-upgrade flow) right after dismissal.
        preferences.edit().putBoolean(PREFERENCE_KEY, true).commit()
    }
}

enum class LaunchSurface { CRASH_RECOVERY, WELCOME, APP }

/**
 * Launch routing for the welcome (#187 acceptance):
 * - a crash report pending *right now* always wins (crash recovery first);
 * - if a crash was pending at this launch, the welcome stays unseen for the
 *   whole session — it shows on the next clean launch instead;
 * - otherwise an unseen welcome shows once; a seen one never does.
 */
fun resolveLaunchSurface(
    crashPendingNow: Boolean,
    crashPendingAtLaunch: Boolean,
    welcomeSeen: Boolean,
): LaunchSurface = when {
    crashPendingNow -> LaunchSurface.CRASH_RECOVERY
    crashPendingAtLaunch -> LaunchSurface.APP
    !welcomeSeen -> LaunchSurface.WELCOME
    else -> LaunchSurface.APP
}

@Composable
internal fun WelcomeScreen(
    onStartCooking: () -> Unit,
    onRestoreBackup: () -> Unit,
    onConnectThermometer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("welcome-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero mascot: DD-1 sanctions hero size in exactly two places — this
        // welcome and Settings → About. The Meals cap does not apply here.
        Image(
            painter = painterResource(R.drawable.pittech_smoker),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .testTag("welcome-mascot"),
        )
        // TURN-IN (`d03`): the wordmark and promise in the banner face —
        // the app's own words, unchanged (§1.1).
        TurnInBannerText(
            "Welcome to PitTech",
            style = PitTechTextStyles.bannerTitle,
        )
        Text(
            "Log your cooks with live temps, notes and photos — no account needed to start.",
            style = PitTechTextStyles.body,
            color = pitTechTokens().inkMuted,
        )
        WelcomeRow(
            icon = Icons.Filled.Thermostat,
            title = "Live cook tracking",
            subtitle = "Connect a thermometer or log temps by hand.",
        )
        WelcomeRow(
            icon = Icons.Filled.MenuBook,
            title = "Recipes, playbooks & reminders",
            subtitle = "Plan the cook and get nudged at the right moments.",
        )
        WelcomeRow(
            icon = Icons.Filled.Explore,
            title = "Share & Discover",
            subtitle = "Cook Packs, codes and community recipes.",
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onStartCooking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("welcome-start-cooking"),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Start cooking")
        }
        // Quiet secondaries: the existing first-run jobs, same destinations as
        // the empty-Meals entries.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onRestoreBackup, modifier = Modifier.testTag("welcome-restore-backup")) {
                Text("Restore from backup")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onConnectThermometer, modifier = Modifier.testTag("welcome-connect-thermometer")) {
                Text("Connect a thermometer")
            }
        }
    }
}

@Composable
private fun WelcomeRow(icon: ImageVector, title: String, subtitle: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = pitTechTokens().surface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, pitTechTokens().ruling),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = pitTechTokens().emberText)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TurnInBannerText(title)
                Text(
                    subtitle,
                    style = PitTechTextStyles.body,
                    color = pitTechTokens().inkMuted,
                )
            }
        }
    }
}
