@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pittech.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pittech.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val planClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val planDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d")

/** A plan instant in the plan's own zone; the day rides along when it isn't the serve day. */
internal fun planClock(atUtcMillis: Long, zoneId: String, serveAtUtcMillis: Long): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))
    val instant = Instant.ofEpochMilli(atUtcMillis).atZone(zone)
    val serveDay = Instant.ofEpochMilli(serveAtUtcMillis).atZone(zone).toLocalDate()
    return if (instant.toLocalDate() == serveDay) instant.format(planClockFormat)
    else "${instant.format(planDayFormat)} · ${instant.format(planClockFormat)}"
}

private fun formatPit(f: Double): String =
    if (f == f.toLong().toDouble()) "${f.toLong()}°" else "$f°"

/**
 * Spread 03 — the plan day-board (Flowing Slice 4). The answer comes
 * first (3.1): "Start the brisket" in banner numerals, worked back from
 * the serve time, rest included. The stagger is the screen (3.2): every
 * dish a lane against the serve line at the board's right edge. One pit
 * warns, never blocks (3.3): a conflict shows as a marigold strip with
 * the resolution applied — pick a pit and the other lanes stretch, the
 * basis stated on the lane itself.
 *
 * [interactive] boards (the editor) offer the pit choice; read-only
 * boards (the saved plan card) show the same warning and resolution.
 */
@Composable
internal fun PlanDayBoard(
    plan: ServePlan,
    timeline: PlanTimelineResult,
    onChoosePit: ((Double) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = pitTechTokens()
    val headline = PlanBoard.headlineLane(timeline) ?: return
    val window = PlanBoard.boardWindow(timeline) ?: return
    Column(modifier.testTag("plan-board"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TurnInMetaLabel("Start the ${headline.dishName}", color = tokens.attentionText)
            Text(
                planClock(headline.foodOnAtUtcMillis, plan.zoneId, plan.serveAt),
                style = PitTechTextStyles.scoreboard,
                modifier = Modifier.testTag("plan-board-headline"),
            )
            Text(
                "Serve ${planClock(plan.serveAt, plan.zoneId, plan.serveAt)} · worked back from serving, rest included",
                style = PitTechTextStyles.monoMeta, color = tokens.inkMuted,
            )
        }
        timeline.lanes.forEach { lane -> PlanLaneRow(plan, lane, window) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(planClock(window.first, plan.zoneId, plan.serveAt), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted)
            Text("Serve ${planClock(window.second, plan.zoneId, plan.serveAt)}", style = PitTechTextStyles.monoMeta, color = tokens.attentionText)
        }
        if (timeline.conflicts.isNotEmpty()) {
            val names = timeline.conflicts.map { conflict ->
                val a = timeline.lanes.firstOrNull { it.dishIndex == conflict.dishIndexA }
                val b = timeline.lanes.firstOrNull { it.dishIndex == conflict.dishIndexB }
                "${a?.dishName} at ${a?.pitF?.let(::formatPit)}" to "${b?.dishName} at ${b?.pitF?.let(::formatPit)}"
            }
            val warning = names.joinToString("; ") { (a, b) -> "One pit: $a, $b" } +
                (timeline.chosenPitF?.let { " — running at ${formatPit(it)}, the rest stretch." } ?: ".")
            TurnInStatusStrip(warning, tokens.marigold, Modifier.testTag("plan-board-conflict"))
            if (onChoosePit != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanBoard.conflictPitChoices(timeline).forEach { pit ->
                        val chosen = timeline.chosenPitF == pit
                        if (chosen) Button(onClick = { onChoosePit(pit) }, modifier = Modifier.testTag("plan-board-pit-${pit.toInt()}")) { Text("Run at ${formatPit(pit)}") }
                        else OutlinedButton(onClick = { onChoosePit(pit) }, modifier = Modifier.testTag("plan-board-pit-${pit.toInt()}")) { Text("Run at ${formatPit(pit)}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanLaneRow(plan: ServePlan, lane: PlanLane, window: Pair<Long, Long>) {
    val tokens = pitTechTokens()
    val (start, end) = PlanBoard.laneSpan(lane, window)
    Column(Modifier.fillMaxWidth().testTag("plan-board-lane-${lane.dishIndex}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TurnInBannerText(lane.dishName, modifier = Modifier.weight(1f))
            lane.pitF?.let { Text(formatPit(it), style = PitTechTextStyles.monoMeta, color = tokens.inkMuted) }
        }
        Row(Modifier.fillMaxWidth().height(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (start > 0.0) Spacer(Modifier.weight(start.toFloat()))
            Box(
                Modifier.weight((end - start).toFloat().coerceAtLeast(0.01f)).fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (lane.stretch != null) tokens.marigold else tokens.ember),
            )
            if (end < 1.0) Spacer(Modifier.weight((1.0 - end).toFloat()))
        }
        Text(
            buildString {
                append("Food on ${planClock(lane.foodOnAtUtcMillis, plan.zoneId, plan.serveAt)}")
                lane.wrapAtUtcMillis?.let { append(" · Wrap ${planClock(it, plan.zoneId, plan.serveAt)}") }
                append(" · Off ${planClock(lane.removeEarliestUtcMillis, plan.zoneId, plan.serveAt)}–${planClock(lane.removeLatestUtcMillis, plan.zoneId, plan.serveAt)}")
                append(" · Ready ${planClock(lane.readyEarliestUtcMillis, plan.zoneId, plan.serveAt)}–${planClock(lane.readyLatestUtcMillis, plan.zoneId, plan.serveAt)}")
            },
            style = PitTechTextStyles.monoMeta, color = tokens.inkMuted,
        )
        lane.stretch?.let { stretch ->
            TurnInStatusStrip(
                "${lane.dishName} ${stretch.basis}",
                tokens.marigold,
                Modifier.testTag("plan-board-stretch-${lane.dishIndex}"),
            )
        }
    }
}

/**
 * E5 — the plan rides on Live: during a planned cook, the day-board's
 * lanes appear on the Live board as the cook's own schedule strip. The
 * states are the schedule read against the clock — on the pit once its
 * food-on time has passed, done once its ready window has closed —
 * labeled as plan times, never as recorded events.
 */
@Composable
internal fun PlanScheduleStrip(plan: ServePlan, timeline: PlanTimelineResult, nowUtcMillis: Long, modifier: Modifier = Modifier) {
    val tokens = pitTechTokens()
    TurnInCard(modifier.fillMaxWidth().testTag("live-plan-strip")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TurnInBannerText("Today's plan", modifier = Modifier.weight(1f))
                Text("Serve ${planClock(plan.serveAt, plan.zoneId, plan.serveAt)}", style = PitTechTextStyles.monoMeta, color = tokens.attentionText)
            }
            timeline.lanes.forEach { lane ->
                val state = when {
                    nowUtcMillis >= lane.readyLatestUtcMillis -> "Done"
                    nowUtcMillis >= lane.foodOnAtUtcMillis -> "On the pit"
                    else -> "Up next"
                }
                Row(
                    Modifier.fillMaxWidth().testTag("live-plan-lane-${lane.dishIndex}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(lane.dishName, style = PitTechTextStyles.bodyEmphasis, modifier = Modifier.weight(1f))
                    Text(
                        "On ${planClock(lane.foodOnAtUtcMillis, plan.zoneId, plan.serveAt)} · Ready ${planClock(lane.readyEarliestUtcMillis, plan.zoneId, plan.serveAt)}–${planClock(lane.readyLatestUtcMillis, plan.zoneId, plan.serveAt)}",
                        style = PitTechTextStyles.monoMeta, color = tokens.inkMuted,
                    )
                    TurnInStamp(state)
                }
            }
        }
    }
}
