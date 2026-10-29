package com.pittech.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Live board's merged guidance (Flowing spec §5, Spread 01). Pure
 * presentation logic over the pace engine's outputs and the board's
 * existing sources — alerts, reminders, the serve plan — so the verdict
 * line (1.1) and the living ETA (1.2) are unit-testable without Compose.
 * The iOS LiveGuidance mirrors this file rule for rule.
 *
 * The philosophy of §1 binds here too: a range is stated only with its
 * generated basis; where the engine withholds a range, the surface shows
 * the plan window or the engine's reason — never a fabricated time.
 */

/** One merged answer for the top of the Live board. */
data class LiveVerdict(val headline: String, val detail: String?)

object LiveGuidance {

    /**
     * Priority is the spouse test in order: something that needs the cook
     * beats everything; the stall (the moment every new cook panics) next;
     * then the next dated obligation; then plain pace. [formatTime] and
     * [formatRange] are injected so the strings carry the cook's locale
     * and time zone without this file knowing either.
     */
    fun verdict(
        needsAttention: Int,
        stall: StallState,
        finish: FinishEstimate,
        nextReminder: Pair<String, Long>?,
        nowUtcMillis: Long,
        eta: LiveEta,
        formatTime: (Long) -> String,
        formatDuration: (Double) -> String,
    ): LiveVerdict {
        if (needsAttention > 0) {
            return LiveVerdict(
                if (needsAttention == 1) "An alert needs attention." else "$needsAttention alerts need attention.",
                "See the Alerts panel below — acknowledging it there quiets it here.",
            )
        }
        if (stall is StallState.InStall) {
            val average = stall.historicalAverageStallMinutes?.let { " · your average stall for this cut is ${formatDuration(it)}" } ?: ""
            return LiveVerdict(
                "In the stall — normal. Nothing needs doing.",
                "In the stall since ${formatTime(stall.sinceUtcMillis)} · ${formatDuration(stall.elapsedMinutes)} in$average.",
            )
        }
        if (finish is FinishEstimate.Finished) {
            return LiveVerdict("At target — ready when you are.", null)
        }
        if (nextReminder != null && nextReminder.second - nowUtcMillis in 0..45 * 60_000L) {
            return LiveVerdict("Next: ${nextReminder.first} at ${formatTime(nextReminder.second)}.", eta.detail)
        }
        return when (eta) {
            is LiveEta.RangedLine -> LiveVerdict("On pace — nothing needs doing.", eta.detail)
            is LiveEta.PlanLine -> LiveVerdict("Nothing needs doing.", eta.detail)
            is LiveEta.Withheld -> LiveVerdict("Nothing needs doing.", eta.detail)
            LiveEta.None -> LiveVerdict("Nothing needs doing.", null)
        }
    }

    /** The ETA row's content: a stated range, a plan window, or the honest reason there is neither. */
    fun eta(
        finish: FinishEstimate,
        planWindow: Pair<Long, Long>?,
        formatRange: (Long, Long) -> String,
    ): LiveEta = when (finish) {
        is FinishEstimate.Ranged -> LiveEta.RangedLine(
            text = "Done ${formatRange(finish.range.earliestUtcMillis, finish.range.latestUtcMillis)}",
            basis = finish.range.basis.displayText(),
        )
        is FinishEstimate.PlanOnly -> when {
            planWindow != null -> LiveEta.PlanLine(
                text = "Planning window ${formatRange(planWindow.first, planWindow.second)}",
                basis = PaceBasis.PLAN.displayText(),
            )
            else -> LiveEta.Withheld(reason = finish.reason)
        }
        FinishEstimate.Finished -> LiveEta.RangedLine(text = "At target", basis = PaceBasis.FINISHED.displayText())
    }

    /**
     * Same-protein history for one live dish, drawn from the whole-library
     * insights archive: only finished cooks, only dishes whose cut matches
     * the live dish's cut (normalized). A past dish contributes a target
     * only from its own recorded final temperature — without one it is
     * skipped rather than assigned a plausible number (the engine's
     * history anchors finishes at the recorded pull, but its crossing
     * fallback must not run on an invented target).
     */
    fun historyFor(archive: InsightArchive, currentCookId: String, currentDishId: String, cut: String?): PaceHistory {
        val wanted = cut?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return PaceHistory.NONE
        val pastCooks = archive.cooks.filter { it.id != currentCookId && it.endedAtUtcMillis != null }
        if (pastCooks.isEmpty()) return PaceHistory.NONE
        val pastIds = pastCooks.map { it.id }.toSet()
        val dishes = archive.dishes.filter { it.cookId in pastIds && it.id != currentDishId &&
            it.cut?.trim()?.lowercase() == wanted }
        if (dishes.isEmpty()) return PaceHistory.NONE
        val targets = mutableMapOf<String, Double>()
        for (dish in dishes) {
            val recorded = archive.results.firstOrNull { it.cookId == dish.cookId && it.dishId == dish.id && it.resultType == "final_temperature" }?.numericValue
                ?: archive.results.firstOrNull { it.cookId == dish.cookId && it.dishId == null && it.resultType == "final_temperature" }?.numericValue
            if (recorded != null && recorded.isFinite() && recorded > 0) targets["${dish.cookId}/${dish.id}"] = recorded
        }
        val kept = dishes.filter { "${it.cookId}/${it.id}" in targets }
        if (kept.isEmpty()) return PaceHistory.NONE
        val keptCookIds = kept.map { it.cookId }.toSet()
        val filtered = InsightArchive(
            cooks = pastCooks.filter { it.id in keptCookIds },
            dishes = kept,
            events = archive.events.filter { it.cookId in keptCookIds },
            readings = archive.readings.filter { it.cookId in keptCookIds },
            results = archive.results.filter { it.cookId in keptCookIds },
        )
        return CookPaceEngine.history(listOf(filtered), targets)
    }

    /** The most recent finished cook of the same cut — the chart's ghost (element 2.4). */
    fun ghostCook(archive: InsightArchive, currentCookId: String, cut: String?): InsightCookInput? {
        val wanted = cut?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val dishCookIds = archive.dishes.filter { it.cut?.trim()?.lowercase() == wanted }.map { it.cookId }.toSet()
        return archive.cooks
            .filter { it.id != currentCookId && it.endedAtUtcMillis != null && it.id in dishCookIds }
            .maxByOrNull { it.endedAtUtcMillis!! }
    }

    /**
     * Minutes the cook is ahead of the ghost at the cursor, both series
     * elapsed-aligned to their own food-on (element 2.4's "+38 min vs last
     * brisket at this point"). Positive: the ghost needed longer to reach
     * the temperature the current cook shows at the cursor. Null whenever
     * either series can't answer — the delta is stated only when real.
     */
    fun ghostDeltaMinutes(current: List<PaceReading>, ghost: List<PaceReading>, cursorElapsedMinutes: Double): Int? {
        val temp = interpolate(current, cursorElapsedMinutes) ?: return null
        val ghostAt = elapsedAtTemp(ghost, temp) ?: return null
        val delta = ghostAt - cursorElapsedMinutes
        if (abs(delta) < 1.0) return 0
        return delta.roundToInt()
    }

    /** Piecewise-linear value of an elapsed-minutes series at [elapsedMinutes]; null outside its span. */
    internal fun interpolate(series: List<PaceReading>, elapsedMinutes: Double): Double? {
        if (series.size < 2) return null
        val sorted = series.sortedBy { it.atUtcMillis }
        val at = elapsedMinutes * 60_000.0
        if (at < sorted.first().atUtcMillis || at > sorted.last().atUtcMillis) return null
        val upper = sorted.firstOrNull { it.atUtcMillis.toDouble() >= at } ?: return null
        val lower = sorted.lastOrNull { it.atUtcMillis.toDouble() <= at } ?: return null
        if (upper.atUtcMillis == lower.atUtcMillis) return upper.fahrenheit
        val fraction = (at - lower.atUtcMillis) / (upper.atUtcMillis - lower.atUtcMillis)
        return lower.fahrenheit + fraction * (upper.fahrenheit - lower.fahrenheit)
    }

    /** First elapsed time (minutes) at which a climbing series reaches [tempF]; null when it never does. */
    internal fun elapsedAtTemp(series: List<PaceReading>, tempF: Double): Double? {
        val sorted = series.sortedBy { it.atUtcMillis }
        var previous: PaceReading? = null
        for (point in sorted) {
            val prev = previous
            if (prev != null && prev.fahrenheit <= tempF && point.fahrenheit >= tempF && point.atUtcMillis > prev.atUtcMillis) {
                val fraction = (tempF - prev.fahrenheit) / (point.fahrenheit - prev.fahrenheit)
                return (prev.atUtcMillis + fraction * (point.atUtcMillis - prev.atUtcMillis)) / 60_000.0
            }
            if (point.fahrenheit == tempF) return point.atUtcMillis / 60_000.0
            previous = point
        }
        return null
    }
}

/** The ETA row's three honest shapes (plus silence). */
sealed interface LiveEta {
    val detail: String?
    data class RangedLine(val text: String, val basis: String) : LiveEta {
        override val detail: String get() = "$text · $basis"
    }
    data class PlanLine(val text: String, val basis: String) : LiveEta {
        override val detail: String get() = "$text · $basis"
    }
    data class Withheld(val reason: String) : LiveEta {
        override val detail: String get() = "No finish range yet — $reason"
    }
    data object None : LiveEta {
        override val detail: String? get() = null
    }
}
