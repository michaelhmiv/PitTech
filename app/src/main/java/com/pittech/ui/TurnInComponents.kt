package com.pittech.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * TURN-IN shared components (BUILD-SPEC §4), built in Slice 1 — the first
 * slice that uses them — and reused by every later slice. Each component is
 * a direct transcription of its canonical Area-01 frame: the scoreboard
 * numeral block and milestone rail from `d12-live-board`, the action
 * placards from `d12`/`d16`, the boxed score cells from
 * `d31-finish-results`, the lane row from `d01-meals-home`, sheet chrome
 * (grab handle + mono kicker) from the sheet frames, and the segmented
 * control from `d12`/`d17`/`d19`. All colors and type come from the
 * Slice 0 tokens (`pitTechTokens()`, `PitTechTextStyles`) so both modes
 * stay correct by construction.
 */

/** Tracked section label — the §3.2 floor lives in the token. The string
 * renders exactly as passed: like the frames (whose caps are a CSS
 * transform over literal DOM text), the tracked meta type carries the
 * look and the words stay the app's own, so data-derived labels (food
 * group headings, dish names) are never rewritten. */
@Composable
internal fun TurnInMetaLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = pitTechTokens().inkMuted,
) {
    Text(text, style = PitTechTextStyles.metaLabel, color = color, modifier = modifier)
}

/** A surface name set in the banner face (cook names, card titles). The
 * frames render these in caps via text-transform; the app's copy stays
 * literal here — the condensed banner face carries the look, and the
 * words remain the app's own (§1.1: no silent copy adoption). */
@Composable
internal fun TurnInBannerText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = PitTechTextStyles.bannerCard,
    color: Color = pitTechTokens().ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text, style = style, color = color, modifier = modifier, maxLines = maxLines)
}

/** The laminated TURN-IN card: token surface, one ruling border, 16dp. */
@Composable
internal fun TurnInCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = pitTechTokens()
    val colors = CardDefaults.cardColors(containerColor = tokens.surface, contentColor = tokens.ink)
    val border = BorderStroke(1.dp, tokens.ruling)
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border) {
            Box(Modifier.padding(16.dp)) { content() }
        }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border) {
            Box(Modifier.padding(16.dp)) { content() }
        }
    }
}

/** Segmented control (§4): a raised pill track; the selected segment is a
 * surface pill. In-content switching, not chrome. */
@Composable
internal fun TurnInSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<String?> = emptyList(),
) {
    val tokens = pitTechTokens()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = tokens.surfaceRaised,
        border = BorderStroke(1.dp, tokens.ruling),
    ) {
        Row(Modifier.fillMaxWidth().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) tokens.surface else Color.Transparent)
                        .clickable { onSelect(index) }
                        .then(if (tags.getOrNull(index) != null) Modifier.testTag(tags[index]!!) else Modifier)
                        .semantics { this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = PitTechTextStyles.bodyEmphasis,
                        color = if (selected) tokens.ink else tokens.inkMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Filled trend chip on the scoreboard (HOLD / CLIMB / DROP): accent fill,
 * on-accent mono text, never white on marigold (§3.1). */
@Composable
internal fun TurnInTrendChip(text: String, fill: Color, modifier: Modifier = Modifier) {
    val tokens = pitTechTokens()
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = fill, contentColor = tokens.onAccent) {
        Text(
            text,
            style = PitTechTextStyles.monoMeta,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

internal enum class TurnInRailState { DONE, CURRENT, UPCOMING }

internal data class TurnInRailStep(val label: String, val sublabel: String?, val state: TurnInRailState)

/** Milestone rail (§4): the cook in order — done segments in ember, the
 * next segment loudest in marigold, the rest quiet on the ruling. */
@Composable
internal fun TurnInMilestoneRail(steps: List<TurnInRailStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val tokens = pitTechTokens()
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(top = 5.dp)
                        .height(2.dp)
                        .background(if (steps[index - 1].state == TurnInRailState.DONE) tokens.ember else tokens.ruling),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when (step.state) {
                                TurnInRailState.DONE -> tokens.ember
                                TurnInRailState.CURRENT -> tokens.marigold
                                TurnInRailState.UPCOMING -> tokens.ruling
                            },
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    step.label,
                    style = PitTechTextStyles.metaLabel,
                    color = when (step.state) {
                        TurnInRailState.CURRENT -> tokens.attentionText
                        TurnInRailState.DONE -> tokens.ink
                        TurnInRailState.UPCOMING -> tokens.inkMuted
                    },
                    textAlign = TextAlign.Center,
                )
                step.sublabel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = PitTechTextStyles.monoMeta,
                        color = tokens.inkMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Action placard (§4): a large tappable action card (icon + label). The
 * primary placard takes the mode's primary fill (Slice 0 §3.1: ember in
 * dark, marigold in light, on-accent text in both). */
@Composable
internal fun TurnInPlacard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    testTag: String? = null,
    enabled: Boolean = true,
) {
    val tokens = pitTechTokens()
    val background = if (primary) MaterialTheme.colorScheme.primary else tokens.surface
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else tokens.ink
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp).then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = background,
        contentColor = content,
        border = if (primary) null else BorderStroke(1.dp, tokens.ruling),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (primary) content else tokens.emberText)
            Text(label, style = PitTechTextStyles.bodyEmphasis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Boxed score cells (§4, `d31`): five tappable 1–5 boxes; the selected box
 * is marigold fill with an on-accent banner numeral. Tapping the selected
 * box clears back to unrecorded — the same contract the retired
 * `VerdictChipRow` chips carried, tags included (`$tagPrefix-$n`).
 */
@Composable
internal fun TurnInScoreCells(
    value: Int?,
    onSelect: (Int?) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = pitTechTokens()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..5).forEach { n ->
            val selected = value == n
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) tokens.marigold else tokens.surface)
                    .border(1.dp, if (selected) tokens.marigold else tokens.ruling, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onSelect(if (selected) null else n) }
                    .testTag("$tagPrefix-$n")
                    .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n",
                    style = PitTechTextStyles.bannerCard,
                    color = if (selected) tokens.onAccent else tokens.ink,
                )
            }
        }
    }
}

/** Verdict stars for lane rows (`d01`): filled stars in marigold, the
 * remainder quiet on the ruling. */
@Composable
internal fun TurnInStars(rating: Double, modifier: Modifier = Modifier) {
    val tokens = pitTechTokens()
    val filled = rating.toInt().coerceIn(0, 5)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = tokens.marigold)) { append("★".repeat(filled)) }
            if (filled < 5) withStyle(SpanStyle(color = tokens.ruling)) { append("★".repeat(5 - filled)) }
        },
        style = PitTechTextStyles.body,
        modifier = modifier,
    )
}

/** A state strip above the board (paused / stale / offline, `d13`/`d14`):
 * raised surface with an accent edge and a plain factual line. */
@Composable
internal fun TurnInStatusStrip(text: String, accent: Color, modifier: Modifier = Modifier) {
    val tokens = pitTechTokens()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surfaceRaised,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.ruling),
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Text(
                text,
                style = PitTechTextStyles.body,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Boxed field (§4, `d28`): the label rides above the box as a caps meta
 * label instead of floating inside it; the box itself is the theme's
 * outlined field (token surface, ruling border, accent focus). */
@Composable
internal fun TurnInBoxedField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TurnInMetaLabel(label)
        content()
    }
}

/** Boxed score cell (§4): one rated/scored/status value in a raised box —
 * a caps meta label over a mono readout. Canonical: area-02 `stats`,
 * area-03 `dark-15-playbook-detail` (setpoint / smoker / dishes / steps)
 * and `dark-04-recipe-detail` (pit / cook / serves / target). */
@Composable
internal fun TurnInScoreCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val tokens = pitTechTokens()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = tokens.surfaceRaised,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.ruling),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TurnInMetaLabel(label)
            Text(value, style = PitTechTextStyles.monoReadout, color = tokens.ink)
        }
    }
}

/** The "Not started" stamp (area-03 `dark-27-upcoming`): a marigold
 * outline with attention-text wording — a saved plan is visibly a plan,
 * never a cook in progress (PL-10). */
@Composable
internal fun TurnInStamp(text: String, modifier: Modifier = Modifier) {
    val tokens = pitTechTokens()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        contentColor = tokens.attentionText,
        border = BorderStroke(1.5.dp, tokens.marigold),
    ) {
        Text(
            text,
            style = PitTechTextStyles.monoMeta,
            color = tokens.attentionText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Sheet chrome (§4): a small mono context kicker above a sheet's title —
 * the content name stays the title; the kicker names where you are. */
@Composable
internal fun TurnInSheetKicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = PitTechTextStyles.monoMeta,
        color = pitTechTokens().inkMuted,
        modifier = modifier,
    )
}
