package com.pittech.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pittech.billing.ProPlacement
import com.pittech.billing.SubscriptionManager
import com.pittech.billing.preview
import com.pittech.billing.title
import com.pittech.data.InsightsSnapshot
import com.pittech.data.PreparedShareCard
import com.pittech.data.insightArchive
import com.pittech.domain.InsightsCalculations
import com.pittech.domain.ProAdvancedCurves
import com.pittech.domain.ProCustomization
import com.pittech.domain.ProCustomizationStore
import com.pittech.domain.ProPersonalAnalysis
import com.pittech.domain.ProPersonalLibrary
import com.pittech.domain.ProRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** These dialogs render only when the Pro gate resolves to PRO. They read the
 * local library plus the cached verified entitlement — never the network — so
 * offline Pro access works. Every other gate destination (loading, paywall,
 * expired fallback) renders through the shared Pro host. */

/** Maps the live Room snapshot onto the versioned Pro input (titles joined
 * from the same snapshot; the v1 archive itself carries no titles). */
internal fun InsightsSnapshot.proPersonalLibrary(now: Long): ProPersonalLibrary =
    ProPersonalLibrary(
        insightArchive(cooks, readings, results),
        cooks.associate { it.cook.id to it.cook.title },
        now,
    )

@Composable
private fun ProAnalysisCard(title: String, lines: List<String>, notes: List<String>, coverage: String, testTag: String) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(coverage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProPersonalAnalysisDialog(subscription: SubscriptionManager, entry: ProPlacement, library: ProPersonalLibrary, onRecap: () -> Unit) {
    val report = remember(library) { InsightsCalculations.compute(library.archive, library.asOfUtcMillis) }
    val snapshot = remember(report) { ProPersonalAnalysis.snapshot(report, library.titles) }
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(entry.title) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-analysis"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                if (snapshot.state == ProPersonalAnalysis.LibraryState.EMPTY) {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("pro-analysis-empty"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(snapshot.emptyTitle ?: ProPersonalAnalysis.EMPTY_TITLE, style = MaterialTheme.typography.titleMedium)
                            Text(snapshot.emptyBody ?: ProPersonalAnalysis.EMPTY_BODY, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    snapshot.emptyBody?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pro-analysis-sparse-note"))
                    }
                    if (snapshot.comparisonNote != null) {
                        Text(
                            snapshot.comparisonNote,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("pro-analysis-comparison-note"),
                        )
                    } else {
                        Text("How your cooks of a cut compare", style = MaterialTheme.typography.titleMedium)
                        snapshot.cutGroups.forEach { group ->
                            ProAnalysisCard(group.header, group.lines, emptyList(), group.coverage, "pro-analysis-cut")
                        }
                    }
                    snapshot.best?.let { best ->
                        Text("Your best-rated recorded attempt", style = MaterialTheme.typography.titleMedium)
                        ProAnalysisCard("Your declared ratings decide", best.lines, best.notes, best.coverage, "pro-analysis-best")
                    }
                    snapshot.timing?.let { timing ->
                        Text("Timing across recorded cuts and weights", style = MaterialTheme.typography.titleMedium)
                        ProAnalysisCard("Recorded heating time by cut", timing.lines, emptyList(), timing.coverage, "pro-analysis-timing")
                    }
                    snapshot.methods?.let { methods ->
                        Text("Wrap and rest differences", style = MaterialTheme.typography.titleMedium)
                        ProAnalysisCard("What each cook recorded", methods.lines, emptyList(), methods.coverage, "pro-analysis-methods")
                    }
                    Card(
                        onClick = onRecap,
                        modifier = Modifier.fillMaxWidth().testTag("pro-recap-entry"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Year in Smoke", style = MaterialTheme.typography.titleMedium)
                            Text("Build a scoped, shareable recap from this library.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

@Composable
internal fun ProAdvancedCurvesDialog(subscription: SubscriptionManager, entry: ProPlacement, library: ProPersonalLibrary, customization: ProCustomizationStore, entitled: Boolean) {
    val report = remember(library) { InsightsCalculations.compute(library.archive, library.asOfUtcMillis) }
    val options = remember(report) { ProAdvancedCurves.channelOptions(report, library.archive, library.titles) }
    var anchor by remember { mutableStateOf(ProAdvancedCurves.Anchor.FOOD_ON) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val aligned = remember(report, anchor, selected) { ProAdvancedCurves.align(report, library.archive, library.titles, anchor, selected) }
    val palette = remember(customization.paletteId, entitled) {
        ProCustomization.effectivePalette(customization.paletteId, entitled).colors
    }
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(entry.title) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-curves"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                if (options.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("pro-curves-empty"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No measured channels yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Record temperatures with a cook to compare aligned curves here. Gaps stay visible; nothing is estimated.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    Text("Align at", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pro-curves-anchor")) {
                        ProAdvancedCurves.Anchor.values().forEach { option ->
                            FilterChip(
                                selected = anchor == option,
                                onClick = { anchor = option },
                                label = { Text(option.title) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text("Measured sources", style = MaterialTheme.typography.titleMedium)
                    Text("Only real measurements — gaps stay visible.", style = MaterialTheme.typography.bodySmall)
                    options.forEach { option ->
                        Card(
                            onClick = {
                                selected = if (option.channelId in selected) selected - option.channelId else selected + option.channelId
                            },
                            modifier = Modifier.fillMaxWidth().testTag(option.testTag),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = option.channelId in selected, onCheckedChange = null)
                                Text(option.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (selected.isEmpty()) {
                        Text(
                            "Choose at least one measured source to see aligned readings.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("pro-curves-unselected"),
                        )
                    } else {
                        aligned.series.forEachIndexed { index, series ->
                            Card(
                                modifier = Modifier.fillMaxWidth().testTag(series.testTag),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(series.label, style = MaterialTheme.typography.titleMedium, color = proHex(palette[index % palette.size]))
                                    series.points.take(40).forEach { Text(it.line, style = MaterialTheme.typography.bodyMedium) }
                                    if (series.points.size > 40) Text("+${series.points.size - 40} more recorded points", style = MaterialTheme.typography.bodySmall)
                                    Text(series.coverage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        aligned.excluded.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro-curves-excluded"))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

internal fun proHex(hex: String): Color {
    val clean = hex.removePrefix("#")
    val rgb = clean.toIntOrNull(16) ?: 0
    return Color(red = (rgb shr 16) and 0xFF, green = (rgb shr 8) and 0xFF, blue = rgb and 0xFF)
}

@Composable
internal fun ProRecapDialog(subscription: SubscriptionManager, library: ProPersonalLibrary, customization: ProCustomizationStore, entitled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report = remember(library) { InsightsCalculations.compute(library.archive, library.asOfUtcMillis) }
    var scopeState by remember(report) { mutableStateOf(ProRecap.Scope.all(report.cooks.map { it.cookId }, ProRecap.ALL_PERIODS)) }
    var previewing by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf<ByteArray?>(null) }
    var renderFailed by remember { mutableStateOf(false) }
    val periods = remember(report) {
        listOf(ProRecap.ALL_PERIODS) + ProRecap.availablePeriods(library.archive.cooks.associate { it.id to it.startedAtUtcMillis })
    }
    val periodCooks = remember(report, scopeState.period) { ProRecap.cooksInPeriod(report, library.archive, scopeState.period) }
    val card = remember(report, scopeState) { ProRecap.build(report, library.titles, scopeState, report.cooks.size) }
    val themeId = remember(customization.themeId, entitled) { ProCustomization.effectiveTheme(customization.themeId, entitled).id }
    val paletteId = remember(customization.paletteId, entitled) { ProCustomization.effectivePalette(customization.paletteId, entitled).id }
    val image = remember(rendered) { rendered?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }

    fun render() {
        val current = card ?: return
        scope.launch {
            renderFailed = false
            rendered = null
            try {
                val content = ProRecap.shareCardContent(current, themeId, paletteId).toString()
                rendered = withContext(Dispatchers.Default) { ShareCardRenderer.render(PreparedShareCard(content, null)) }
            } catch (_: Exception) {
                renderFailed = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(if (previewing) "Recap preview" else "Year in Smoke") },
        text = {
            if (!previewing) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-recap-scope"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Choose the period, cooks, and sections. Nothing is shared until you say so.", style = MaterialTheme.typography.bodyLarge)
                    Text("Period", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pro-recap-period")) {
                        periods.forEach { period ->
                            FilterChip(
                                selected = scopeState.period == period,
                                onClick = {
                                    scopeState = scopeState.copy(
                                        period = period,
                                        includedCookIds = ProRecap.cooksInPeriod(report, library.archive, period),
                                    )
                                },
                                label = { Text(if (period == ProRecap.ALL_PERIODS) "All recorded cooks" else period) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text("Cooks in this recap", style = MaterialTheme.typography.titleMedium)
                    if (periodCooks.isEmpty()) Text("No recorded cooks in this period.", style = MaterialTheme.typography.bodyMedium)
                    periodCooks.forEach { cookId ->
                        Card(
                            onClick = {
                                scopeState = scopeState.copy(
                                    includedCookIds = if (cookId in scopeState.includedCookIds) scopeState.includedCookIds - cookId else scopeState.includedCookIds + cookId,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().testTag("pro-recap-cook"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = cookId in scopeState.includedCookIds, onCheckedChange = null)
                                Text(library.titles[cookId] ?: cookId, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Text("Sections", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(scopeState.showStats, { scopeState = scopeState.copy(showStats = it) }, modifier = Modifier.testTag("pro-recap-section-stats"))
                        Text("Recorded totals", modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(scopeState.showMilestones, { scopeState = scopeState.copy(showMilestones = it) }, modifier = Modifier.testTag("pro-recap-section-milestones"))
                        Text("Recorded milestones", modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(scopeState.showBest, { scopeState = scopeState.copy(showBest = it) }, modifier = Modifier.testTag("pro-recap-section-best"))
                        Text("Best recorded rating", modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = scopeState.note,
                        onValueChange = { input ->
                            val count = input.codePointCount(0, input.length)
                            scopeState = scopeState.copy(note = if (count > 2000) input.substring(0, input.offsetByCodePoints(0, 2000)) else input)
                        },
                        label = { Text("Recap note (optional)") },
                        modifier = Modifier.fillMaxWidth().testTag("pro-recap-note"),
                        maxLines = 4,
                    )
                    if (card == null) {
                        Text(
                            "Select at least one cook to preview this recap.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("pro-recap-needs-selection"),
                        )
                    }
                    Button(
                        onClick = { previewing = true; render() },
                        enabled = card != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-recap-preview-button"),
                    ) { Text("Preview recap") }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Back") }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-recap-preview"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("This is exactly what sharing sends. Review it before anything leaves this device.", style = MaterialTheme.typography.bodyLarge)
                    if (card != null) {
                        val current = card
                        val bitmap = image
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = "Recap card preview",
                                modifier = Modifier.fillMaxWidth().aspectRatio(1080f / 1350f).testTag("pro-recap-image"),
                            )
                        } else if (renderFailed) {
                            Text("The recap image couldn't be rendered.", style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { render() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                        } else {
                            CircularProgressIndicator()
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth().testTag("pro-recap-card"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(current.title, style = MaterialTheme.typography.titleMedium)
                                current.dishes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                current.stats.forEach { Text("${it.label}: ${it.value}", style = MaterialTheme.typography.bodyMedium) }
                                current.milestones.forEach { Text("${it.dish} · ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                                if (current.note.isNotEmpty()) Text("“${current.note}”", style = MaterialTheme.typography.bodyMedium)
                                Text(current.coverageNote, style = MaterialTheme.typography.bodySmall)
                                Text(current.privacyNote, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (rendered != null) {
                            Button(
                                onClick = {
                                    rendered?.let { value ->
                                        scope.launch {
                                            runCatching {
                                                val file = withContext(Dispatchers.IO) { ShareCardFiles.create(context, value) }
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "image/png"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    clipData = ClipData.newUri(context.contentResolver, "PitTech recap", uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share PitTech recap"))
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-recap-share"),
                            ) { Text("Share recap") }
                        }
                    }
                    OutlinedButton(
                        onClick = { previewing = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-recap-back"),
                    ) { Text("Back to scope") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

@Composable
internal fun ProCustomizationDialog(subscription: SubscriptionManager, entry: ProPlacement, customization: ProCustomizationStore) {
    var tick by remember { mutableIntStateOf(0) }
    var iconNotice by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        tick++
    }
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text(entry.title) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-customization"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Preview every look before applying. Pro looks apply while PitTech Pro is active; without Pro, PitTech renders the readable Classic treatment and keeps your stored selections.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Share-card themes", style = MaterialTheme.typography.titleMedium)
                ProCustomization.cardThemes.forEach { theme ->
                    @Suppress("UNUSED_EXPRESSION") tick
                    val previewed = customization.previewThemeId ?: customization.themeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { customization.previewThemeId = theme.id; refresh() }, modifier = Modifier.fillMaxWidth().testTag("pro-theme-${theme.id}")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(8.dp))
                                            .background(proHex(theme.background)),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(theme.name, style = MaterialTheme.typography.bodyMedium)
                                        if (theme.isPro) Text("Pro", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (customization.themeId == theme.id) Text("Applied", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro-theme-applied-${theme.id}"))
                                }
                            }
                            if (previewed == theme.id) {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(proHex(theme.background)).padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Ink", style = MaterialTheme.typography.bodySmall, color = proHex(theme.ink))
                                    Text("Supporting", style = MaterialTheme.typography.bodySmall, color = proHex(theme.supporting))
                                    Text("Accent", style = MaterialTheme.typography.bodySmall, color = proHex(theme.accent))
                                }
                                Button(
                                    onClick = { customization.applyTheme(theme.id); refresh() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-theme-apply-${theme.id}"),
                                ) { Text("Apply ${theme.name}") }
                            }
                        }
                    }
                }
                Text("Chart palettes", style = MaterialTheme.typography.titleMedium)
                Text("Every palette is contrast-verified against its background.", style = MaterialTheme.typography.bodySmall)
                ProCustomization.chartPalettes.forEach { palette ->
                    val previewed = customization.previewPaletteId ?: customization.paletteId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { customization.previewPaletteId = palette.id; refresh() }, modifier = Modifier.fillMaxWidth().testTag("pro-palette-${palette.id}")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        palette.colors.forEach { hex -> Box(Modifier.size(20.dp).clip(CircleShape).background(proHex(hex))) }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(palette.name, style = MaterialTheme.typography.bodyMedium)
                                        if (palette.isPro) Text("Pro", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (customization.paletteId == palette.id) Text("Applied", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro-palette-applied-${palette.id}"))
                                }
                            }
                            if (previewed == palette.id) {
                                Button(
                                    onClick = { customization.applyPalette(palette.id); refresh() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-palette-apply-${palette.id}"),
                                ) { Text("Apply ${palette.name}") }
                            }
                        }
                    }
                }
                Text("Public profile presentation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Restrained styling for named public cookbooks only. Anonymous publications never carry Pro styling or identity.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ProCustomization.profileAccents.forEach { accent ->
                    val previewed = customization.previewProfileAccentId ?: customization.profileAccentId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { customization.previewProfileAccentId = accent.id; refresh() }, modifier = Modifier.fillMaxWidth().testTag("pro-profile-${accent.id}")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(proHex(accent.color)))
                                    Text(accent.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    if (customization.profileAccentId == accent.id) Text("Applied", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro-profile-applied-${accent.id}"))
                                }
                            }
                            if (previewed == accent.id) {
                                Button(
                                    onClick = {
                                        customization.applyProfile(accent.id, customization.previewProfileLayout ?: customization.profileLayout)
                                        refresh()
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-profile-apply-${accent.id}"),
                                ) { Text("Apply ${accent.name}") }
                            }
                        }
                    }
                }
                Text("Cookbook layout", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pro-profile-layout")) {
                    listOf("standard" to "Standard", "compact" to "Compact").forEach { (id, label) ->
                        FilterChip(
                            selected = (customization.previewProfileLayout ?: customization.profileLayout) == id,
                            onClick = { customization.previewProfileLayout = id; refresh() },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        customization.applyProfile(
                            customization.previewProfileAccentId ?: customization.profileAccentId,
                            customization.previewProfileLayout ?: customization.profileLayout,
                        )
                        refresh()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-profile-apply-layout"),
                ) { Text("Apply layout") }
                Text("App icons", style = MaterialTheme.typography.titleMedium)
                ProCustomization.iconVariants.forEach { variant ->
                    val previewed = customization.previewIconId ?: customization.iconId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { customization.previewIconId = variant.id; refresh() }, modifier = Modifier.fillMaxWidth().testTag("pro-icon-${variant.id}")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(variant.name, style = MaterialTheme.typography.bodyMedium)
                                        if (variant.isPro) Text("Pro", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (customization.iconId == variant.id) Text("Applied", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pro-icon-applied-${variant.id}"))
                                }
                            }
                            if (previewed == variant.id) {
                                Button(
                                    onClick = { iconNotice = customization.applyIcon(variant.id); refresh() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pro-icon-apply-${variant.id}"),
                                ) { Text("Apply ${variant.name}") }
                            }
                        }
                    }
                }
                iconNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pro-icon-notice")) }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}

@Composable
internal fun ProMoreHubDialog(subscription: SubscriptionManager, entry: ProPlacement, onRecap: () -> Unit) {
    AlertDialog(
        onDismissRequest = { subscription.closePaywall() },
        title = { Text("PitTech Pro") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("pro-more-hub"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(entry.preview, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Your Pro library: personal analysis, advanced curves, recaps, and customization — all from cooks recorded on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                listOf(ProPlacement.ANALYSIS, ProPlacement.CURVES, ProPlacement.BENCHMARKS, ProPlacement.THEMES).forEach { target ->
                    Card(
                        onClick = { subscription.openPro(target) },
                        modifier = Modifier.fillMaxWidth().testTag("pro-hub-" + target.name.lowercase()),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(target.title, style = MaterialTheme.typography.titleMedium)
                            Text(target.preview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Card(
                    onClick = onRecap,
                    modifier = Modifier.fillMaxWidth().testTag("pro-hub-recap"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Year in Smoke", style = MaterialTheme.typography.titleMedium)
                        Text("Build a scoped, shareable recap from this library.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { subscription.closePaywall() }, modifier = Modifier.testTag("pro-close")) { Text("Close") } },
    )
}
