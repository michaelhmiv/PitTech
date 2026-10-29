package com.pittech.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.data.CookDetailData
import com.pittech.data.SensorReadingEntity
import com.pittech.data.notePlanEvents
import com.pittech.domain.CookPaceEngine
import com.pittech.domain.CookPlanEngine
import com.pittech.domain.LiveGuidance
import com.pittech.domain.PaceReading
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Flowing Slice 2, Spread 02: the full-screen chart. It is the SAME
 * TemperatureChart engine the Charts tab runs — the live surface gets
 * that engine with the live extras switched on (stall band, the shaded
 * hours ahead, the same-cut ghost), never a second chart.
 */

/** The previous same-cut cook overlaid on the chart, food-on aligned. */
internal data class GhostCookData(
    val cookId: String,
    val label: String,
    val dishId: String,
    val anchorUtcMillis: Long,
    val readings: List<SensorReadingEntity>,
)

/** °F food-probe series for one dish, elapsed-aligned to [anchorUtcMillis] — the pace engine's input shape. */
internal fun foodSeries(readings: List<SensorReadingEntity>, dishId: String?, anchorUtcMillis: Long): List<PaceReading> =
    readings.filter { it.dishId == dishId && FoodLivePresentation.accepted(it) }
        .map { PaceReading((it.measuredAtUtcMillis - anchorUtcMillis), CookPlanEngine.fahrenheit(it.value, it.unit)) }
        .sortedBy { it.atUtcMillis }

internal fun pitSeries(readings: List<SensorReadingEntity>, anchorUtcMillis: Long): List<PaceReading> =
    readings.filter { it.measurementType in setOf("pit", "pit_ambient", "ambient_temperature") }
        .map { PaceReading((it.measuredAtUtcMillis - anchorUtcMillis), CookPlanEngine.fahrenheit(it.value, it.unit)) }
        .sortedBy { it.atUtcMillis }

private fun clockTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun elapsedLabel(elapsedMillis: Long): String {
    val minutes = elapsedMillis.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m in" else "${minutes}m in"
}

@Composable
internal fun LiveChartDialog(
    data: CookDetailData,
    displayUnit: String,
    leadDishId: String?,
    ghost: GhostCookData?,
    etaLatestUtcMillis: Long?,
    onDismiss: () -> Unit,
) {
    val tokens = pitTechTokens()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(data.cook.id) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val planEvents = remember(data.events) { notePlanEvents(data.events) }
    val anchor = remember(planEvents, leadDishId, data.cook.startedAtUtcMillis) {
        planEvents.filter { it.action == "food_on" && (it.dishId == leadDishId || it.dishId == null) }.minOfOrNull { it.at }
            ?: data.cook.startedAtUtcMillis
    }
    val probe = remember(data.readings, leadDishId, anchor) { foodSeries(data.readings, leadDishId, anchor) }
    val pit = remember(data.readings, anchor) { pitSeries(data.readings, anchor) }
    val ghostProbe = remember(ghost) { ghost?.let { foodSeries(it.readings, it.dishId, it.anchorUtcMillis) } ?: emptyList() }
    val stallSpans = remember(probe) { CookPaceEngine.stallSpans(probe) }
    var scrubElapsed by remember { mutableStateOf<Double?>(null) }
    var presetKey by remember { mutableIntStateOf(0) }
    var presetSpan by remember { mutableStateOf<Double?>(null) }
    var presetStart by remember { mutableStateOf<Double?>(null) }
    var presetFollow by remember { mutableStateOf(true) }
    fun applyPreset(span: Double?, start: Double?, follow: Boolean) {
        presetSpan = span; presetStart = start; presetFollow = follow; presetKey++
        if (follow) scrubElapsed = null
    }
    val nowElapsed = (now - anchor).toDouble()
    val latestElapsed = maxOf(probe.lastOrNull()?.atUtcMillis ?: 0L, pit.lastOrNull()?.atUtcMillis ?: 0L).toDouble()
    val cursorElapsed = scrubElapsed ?: latestElapsed
    val cursorMinutes = cursorElapsed / 60_000.0
    val probeAt = LiveGuidance.interpolate(probe, cursorMinutes)
    val pitAt = LiveGuidance.interpolate(pit, cursorMinutes)
    val inStall = stallSpans.any { span -> cursorElapsed >= span.startUtcMillis && cursorElapsed <= span.endUtcMillis }
    val ghostDelta = ghost?.let { LiveGuidance.ghostDeltaMinutes(probe, ghostProbe, cursorMinutes) }
    val stripEvents = remember(planEvents, data.events, leadDishId, anchor) {
        val actions = planEvents.filter { (it.dishId == leadDishId || it.dishId == null) && it.action in setOf("food_on", "spritz", "wrap", "remove", "rest_start", "hold_start") }
            .map { event -> when (event.action) { "food_on" -> "Food on"; "spritz" -> "Spritz"; "wrap" -> "Wrap"; "remove" -> "Off"; "rest_start" -> "Rest"; else -> "Hold" } to event.at }
        val conditions = data.events.filter { it.eventType == "note" && it.title == "Conditions changed" }.map { "Conditions changed" to it.occurredAtUtcMillis }
        (actions + conditions).sortedBy { it.second }
    }
    FullScreenSurface(onDismiss = onDismiss, title = "Chart · ${data.cook.title}", actions = {
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("live-chart-close")) { Text("Done") }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 2.2: the readout is pinned at the top and recomputes at the cursor.
            TurnInCard(Modifier.fillMaxWidth().testTag("live-chart-readout")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        clockTime(anchor + cursorElapsed.toLong()) + " · " + elapsedLabel(cursorElapsed.toLong()) +
                            (if (scrubElapsed == null) " · latest" else ""),
                        style = PitTechTextStyles.monoMeta, color = tokens.inkMuted,
                    )
                    Text(
                        listOfNotNull(
                            probeAt?.let { "Probe " + String.format(Locale.getDefault(), "%.0f", com.pittech.devices.CookTelemetryPolicy.convert(it, "°F", displayUnit)) + "°" },
                            pitAt?.let { "Pit " + String.format(Locale.getDefault(), "%.0f", com.pittech.devices.CookTelemetryPolicy.convert(it, "°F", displayUnit)) + "°" },
                            if (inStall) "In the stall band" else null,
                        ).joinToString(" · ").ifEmpty { "No readings at this point yet" },
                        style = PitTechTextStyles.bodyEmphasis,
                    )
                    if (ghost != null) {
                        Text(
                            when {
                                ghostDelta == null -> "No comparison with ${ghost.label} at this point"
                                ghostDelta == 0 -> "Even with ${ghost.label} at this point"
                                ghostDelta > 0 -> "+$ghostDelta min vs ${ghost.label} at this point"
                                else -> "$ghostDelta min vs ${ghost.label} at this point"
                            },
                            style = PitTechTextStyles.body, color = tokens.inkMuted,
                            modifier = Modifier.testTag("live-chart-ghost-delta"),
                        )
                    }
                }
            }
            // 2.5: range presets over the same axis.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = presetFollow, onClick = { applyPreset(null, null, true) }, label = { Text("Whole cook") }, modifier = Modifier.testTag("live-chart-preset-whole"))
                FilterChip(selected = !presetFollow && presetSpan == 2 * 3600_000.0, onClick = { applyPreset(2 * 3600_000.0, (nowElapsed - 2 * 3600_000).coerceAtLeast(0.0), false) }, label = { Text("Last 2h") }, modifier = Modifier.testTag("live-chart-preset-last2h"))
                val firstStall = stallSpans.firstOrNull()
                FilterChip(selected = false, enabled = firstStall != null, onClick = {
                    firstStall?.let { span ->
                        applyPreset(maxOf((span.endUtcMillis - span.startUtcMillis).toDouble(), 1800_000.0), span.startUtcMillis.toDouble(), false)
                    }
                }, label = { Text("Stall") }, modifier = Modifier.testTag("live-chart-preset-stall"))
                val wrapAt = stripEvents.firstOrNull { it.first == "Wrap" }?.second
                FilterChip(selected = false, enabled = wrapAt != null, onClick = {
                    wrapAt?.let { at -> val start = (at - anchor).toDouble(); applyPreset(maxOf(nowElapsed - start, 1800_000.0), start, false) }
                }, label = { Text("Since wrap") }, modifier = Modifier.testTag("live-chart-preset-wrap"))
            }
            // 2.5: the event strip shares the axis — tapping an event moves the window to it.
            if (stripEvents.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("live-chart-events"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stripEvents.forEach { (label, at) ->
                        AssistChip(onClick = {
                            val start = (at - anchor).toDouble().coerceAtLeast(0.0)
                            applyPreset(2 * 3600_000.0, start, false)
                        }, label = { Text(label + " · " + elapsedLabel(at - anchor).removeSuffix(" in")) })
                    }
                }
            }
            TemperatureChart(
                readings = data.readings + (ghost?.readings ?: emptyList()),
                cookNames = mapOf(data.cook.id to data.cook.title) + (ghost?.let { mapOf(it.cookId to it.label) } ?: emptyMap()),
                cookStartTimes = mapOf(data.cook.id to anchor) + (ghost?.let { mapOf(it.cookId to it.anchorUtcMillis) } ?: emptyMap()),
                dishNames = data.dishes.associate { it.id to it.name },
                markers = data.events,
                targets = data.targets,
                targetHistory = data.targetHistory,
                displayUnit = displayUnit,
                chartHeight = 340.dp,
                timingMode = data.lifecycle?.timingMode ?: "legacy_preserved",
                stallBandF = com.pittech.domain.PaceConstants.STALL_BAND_LOW_F..com.pittech.domain.PaceConstants.STALL_BAND_HIGH_F,
                nowX = nowElapsed,
                futureUntilX = etaLatestUtcMillis?.let { (it - anchor).toDouble() }?.takeIf { it > nowElapsed },
                onScrubChange = { scrubElapsed = it },
                presetSpanMillis = presetSpan,
                presetStartMillis = presetStart,
                presetFollowLatest = presetFollow,
                presetKey = presetKey,
            )
            if (ghost != null) Text("The paler second cook is ${ghost.label}, aligned at food-on. The grey hours ahead run to the end of the stated finish range.", style = PitTechTextStyles.body, color = tokens.inkMuted)
            else Text("The grey hours ahead run to the end of the stated finish range, when a range is stated.", style = PitTechTextStyles.body, color = tokens.inkMuted)
            Spacer(Modifier.height(8.dp))
            Text("Elapsed " + elapsedLabel(latestElapsed.toLong()) + " of recorded time · " + data.readings.size + " readings" + if (abs(nowElapsed - latestElapsed) > 30_000) " · last reading " + clockTime(anchor + latestElapsed.toLong()) else "", style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
        }
    }
}
