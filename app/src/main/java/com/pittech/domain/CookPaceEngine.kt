package com.pittech.domain

import kotlin.math.max
import kotlin.math.min

/** One food-probe observation, already normalized to degrees Fahrenheit. */
data class PaceReading(val atUtcMillis: Long, val fahrenheit: Double)

/**
 * The cook's own history for one protein/cut, precomputed by
 * [CookPaceEngine.history] from past archives: how long those cooks took,
 * how long their stalls ran, and how fast the probe climbed after each stall.
 * Empty lists mean "no usable history" — the engine then narrows what it is
 * willing to say rather than inventing numbers.
 */
data class PaceHistory(
    val totalDurationsMinutes: List<Double> = emptyList(),
    val stallDurationsMinutes: List<Double> = emptyList(),
    val postStallSlopesFPerMinute: List<Double> = emptyList(),
) {
    companion object { val NONE = PaceHistory() }
}

/** Why a finish range reads the way it does. Every range carries one. */
enum class PaceBasis {
    /** Extrapolated from the trailing readings window. */
    PACE,
    /** Widened by the cook's own history envelope (the stall is still ahead). */
    HISTORY,
    /** Stated while in a stall: stall remainder plus post-stall climb, from history. */
    STALL,
    /** No range: the plan window is the only guidance. */
    PLAN,
    /** The probe has reached the target; there is nothing left to estimate. */
    FINISHED,
}

data class FinishRange(val earliestUtcMillis: Long, val latestUtcMillis: Long, val basis: PaceBasis) {
    val widthMinutes: Double get() = (latestUtcMillis - earliestUtcMillis) / 60_000.0
}

sealed interface FinishEstimate {
    data class Ranged(val range: FinishRange) : FinishEstimate
    /** No range is supportable yet; [reason] says why, for the basis line. */
    data class PlanOnly(val reason: String) : FinishEstimate
    data object Finished : FinishEstimate
}

sealed interface StallState {
    /** Not enough probe evidence to judge — never report this as "no stall". */
    data object Unknown : StallState
    data object NotInStall : StallState
    data class InStall(
        val sinceUtcMillis: Long,
        val elapsedMinutes: Double,
        /** The cook's average stall for this protein, when history knows it. */
        val historicalAverageStallMinutes: Double?,
    ) : StallState
}

data class StallSpan(val startUtcMillis: Long, val endUtcMillis: Long) {
    val durationMinutes: Double get() = (endUtcMillis - startUtcMillis) / 60_000.0
}

data class PaceAssessment(val finish: FinishEstimate, val stall: StallState)

/**
 * Named thresholds for the pace & stall engine (Flowing spec §4.1). Every
 * value here was tuned against `contracts/fixtures/pace-stall-corpus-v1.json`
 * (see docs/flowing/slice-1-backtest-report.md); change one and the corpus
 * backtest in CookPaceEngineTest must be re-run and the report updated.
 */
object PaceConstants {
    /** The stall band, from the UX critique: the probe's 150–170°F plateau zone. */
    const val STALL_BAND_LOW_F = 150.0
    const val STALL_BAND_HIGH_F = 170.0
    /** Trailing window over which the probe slope is measured. */
    const val SLOPE_WINDOW_MINUTES = 30L
    /** At or below this slope (°F/min, ≈2.4°F/hour) an in-band probe is "flat". */
    const val STALL_SLOPE_FLOOR_F_PER_MINUTE = 0.04
    /** Flat must persist this long before a stall is declared. */
    const val STALL_SUSTAIN_MINUTES = 30L
    /**
     * A trailing-window slope describes the window's span, so a flat run's
     * best start estimate is its midpoint: declared starts are backdated by
     * half the slope window.
     */
    const val STALL_START_BACKDATE_MINUTES = 15L
    /** A longer silence than this breaks both stall runs and pace windows. */
    const val MAX_READING_GAP_MINUTES = 20L
    /** Minimum probe evidence before any pace statement: readings in window… */
    const val PACE_MIN_READINGS = 4
    /** …spanning at least this many minutes inside the trailing window. */
    const val PACE_MIN_SPAN_MINUTES = 20L
    /** Below this slope the probe is not climbing fast enough to extrapolate. */
    const val PACE_MIN_SLOPE_F_PER_MINUTE = 0.05
    /** Range edges: earliest assumes the pace runs this much faster… */
    const val PACE_UNCERTAINTY_FAST = 1.6
    /** …latest this much slower. Asymmetric on purpose: finishes slip late. */
    const val PACE_UNCERTAINTY_SLOW = 0.55
    /** A stated range is never narrower than this. */
    const val PACE_MIN_WIDTH_MINUTES = 30L
    /** Padding applied to both ends of the history total-duration envelope. */
    const val HISTORY_ENVELOPE_PAD_MINUTES = 30.0
    /** Floor on the remaining-stall allowance in the in-stall range. */
    const val STALL_REMAINING_FLOOR_MINUTES = 30.0
    /** Anti-jitter: a published range updates at most this often… */
    const val SMOOTHER_CADENCE_MINUTES = 10L
    /** …and neither edge moves more than this per publication. */
    const val SMOOTHER_MAX_EDGE_STEP_MINUTES = 15L
}

/**
 * The pace & stall engine (Flowing spec §4.1). Pure functions over probe
 * readings — any source (controller, BLE, hand-logged) is just readings.
 * It states a finish RANGE with a basis, or declines to state one; it never
 * emits a point estimate and never fabricates pace from sparse data.
 * The iOS CookPaceEngine mirrors this file rule for rule.
 */
object CookPaceEngine {

    /** Least-squares slope (°F/min) of a time-ordered series; null when undefined. */
    fun slopePerMinute(series: List<PaceReading>): Double? {
        if (series.size < 2) return null
        val t0 = series.first().atUtcMillis
        val xs = series.map { (it.atUtcMillis - t0) / 60_000.0 }
        val ys = series.map { it.fahrenheit }
        val mx = xs.average(); val my = ys.average()
        var num = 0.0; var den = 0.0
        for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        return if (den == 0.0) null else num / den
    }

    private fun gapsOk(series: List<PaceReading>): Boolean =
        series.zipWithNext().all { (a, b) -> b.atUtcMillis - a.atUtcMillis <= PaceConstants.MAX_READING_GAP_MINUTES * 60_000L }

    /** Per-reading "flat now" flags: trailing-window slope at/below the floor, in band, no gap. */
    private fun flatFlags(series: List<PaceReading>): List<Boolean> = series.indices.map { i ->
        val t = series[i].atUtcMillis
        val window = series.subList(0, i + 1).filter { it.atUtcMillis >= t - PaceConstants.SLOPE_WINDOW_MINUTES * 60_000L }
        val slope = slopePerMinute(window)
        slope != null && gapsOk(window) && slope <= PaceConstants.STALL_SLOPE_FLOOR_F_PER_MINUTE &&
            series[i].fahrenheit in PaceConstants.STALL_BAND_LOW_F..PaceConstants.STALL_BAND_HIGH_F
    }

    /** All stall spans in a (complete or partial) probe series, in order. */
    fun stallSpans(series: List<PaceReading>): List<StallSpan> {
        if (series.size < 2) return emptyList()
        val flat = flatFlags(series)
        val spans = mutableListOf<StallSpan>()
        var i = 0
        while (i < series.size) {
            if (flat[i]) {
                var j = i
                while (j + 1 < series.size && flat[j + 1]) j++
                if ((series[j].atUtcMillis - series[i].atUtcMillis) / 60_000L >= PaceConstants.STALL_SUSTAIN_MINUTES) {
                    spans += StallSpan(series[i].atUtcMillis - PaceConstants.STALL_START_BACKDATE_MINUTES * 60_000L, series[j].atUtcMillis)
                }
                i = j + 1
            } else i++
        }
        return spans
    }

    /** Whether the series' tail is currently inside a declared stall, and since when. */
    private fun liveStallStart(series: List<PaceReading>, flat: List<Boolean>): Long? {
        if (series.isEmpty() || !flat.last()) return null
        var i = series.lastIndex
        while (i - 1 >= 0 && flat[i - 1]) i--
        if ((series.last().atUtcMillis - series[i].atUtcMillis) / 60_000L < PaceConstants.STALL_SUSTAIN_MINUTES) return null
        return series[i].atUtcMillis - PaceConstants.STALL_START_BACKDATE_MINUTES * 60_000L
    }

    private fun assessable(series: List<PaceReading>): Boolean =
        series.size >= PaceConstants.PACE_MIN_READINGS &&
            (series.last().atUtcMillis - series.first().atUtcMillis) / 60_000L >= PaceConstants.PACE_MIN_SPAN_MINUTES

    fun stallState(series: List<PaceReading>, nowUtcMillis: Long, history: PaceHistory): StallState {
        val flat = if (series.size >= 2) flatFlags(series) else emptyList()
        val since = if (flat.isNotEmpty()) liveStallStart(series, flat) else null
        if (since != null) {
            return StallState.InStall(
                sinceUtcMillis = since,
                elapsedMinutes = (nowUtcMillis - since) / 60_000.0,
                historicalAverageStallMinutes = history.stallDurationsMinutes.takeIf { it.isNotEmpty() }?.average(),
            )
        }
        return if (assessable(series)) StallState.NotInStall else StallState.Unknown
    }

    private fun historyEnvelope(foodOnUtcMillis: Long, history: PaceHistory): Pair<Long, Long>? {
        if (history.totalDurationsMinutes.isEmpty()) return null
        val lo = (history.totalDurationsMinutes.min() - PaceConstants.HISTORY_ENVELOPE_PAD_MINUTES) * 60_000.0
        val hi = (history.totalDurationsMinutes.max() + PaceConstants.HISTORY_ENVELOPE_PAD_MINUTES) * 60_000.0
        return (foodOnUtcMillis + lo.toLong()) to (foodOnUtcMillis + hi.toLong())
    }

    /**
     * Assess one dish's cook at [nowUtcMillis]. [readings] are that dish's
     * food-probe readings (any source); readings after [nowUtcMillis] are
     * ignored. [foodOnUtcMillis] anchors the history envelope.
     */
    fun assess(
        readings: List<PaceReading>,
        targetF: Double,
        nowUtcMillis: Long,
        foodOnUtcMillis: Long,
        history: PaceHistory = PaceHistory.NONE,
    ): PaceAssessment {
        val series = readings.filter { it.atUtcMillis <= nowUtcMillis }.sortedBy { it.atUtcMillis }
        val stall = stallState(series, nowUtcMillis, history)
        if (series.isEmpty()) return PaceAssessment(FinishEstimate.PlanOnly("no readings yet"), stall)
        if (series.last().fahrenheit >= targetF) return PaceAssessment(FinishEstimate.Finished, stall)

        val envelope = historyEnvelope(foodOnUtcMillis, history)

        if (stall is StallState.InStall) {
            // Never extrapolate the stall's own slope. The range is the
            // remaining-stall allowance plus the post-stall climb at the
            // cook's historical climb rates, unioned with the history
            // envelope so a stall can widen the finish, never slide it early.
            if (history.postStallSlopesFPerMinute.isEmpty() || history.stallDurationsMinutes.isEmpty() || envelope == null) {
                return PaceAssessment(FinishEstimate.PlanOnly("in the stall — not enough of your history to widen the range"), stall)
            }
            val delta = targetF - series.last().fahrenheit
            val remainingLatest = max(history.stallDurationsMinutes.max() - stall.elapsedMinutes, PaceConstants.STALL_REMAINING_FLOOR_MINUTES)
            val earliest = nowUtcMillis + (delta / history.postStallSlopesFPerMinute.max() * 60_000.0).toLong()
            val latest = nowUtcMillis + ((remainingLatest + delta / history.postStallSlopesFPerMinute.min()) * 60_000.0).toLong()
            return PaceAssessment(
                FinishEstimate.Ranged(FinishRange(min(earliest, envelope.first), max(latest, envelope.second), PaceBasis.STALL)),
                stall,
            )
        }

        val window = series.filter { it.atUtcMillis >= nowUtcMillis - PaceConstants.SLOPE_WINDOW_MINUTES * 60_000L }
        val spanMinutes = if (window.size >= 2) (window.last().atUtcMillis - window.first().atUtcMillis) / 60_000.0 else 0.0
        if (window.size < PaceConstants.PACE_MIN_READINGS || spanMinutes < PaceConstants.PACE_MIN_SPAN_MINUTES || !gapsOk(window)) {
            return PaceAssessment(FinishEstimate.PlanOnly("not enough readings yet"), stall)
        }
        val slope = slopePerMinute(window)
        if (slope == null || slope < PaceConstants.PACE_MIN_SLOPE_F_PER_MINUTE) {
            return PaceAssessment(FinishEstimate.PlanOnly("the probe is not climbing — no pace to state"), stall)
        }
        val delta = targetF - series.last().fahrenheit
        var earliest = nowUtcMillis + (delta / (slope * PaceConstants.PACE_UNCERTAINTY_FAST) * 60_000.0).toLong()
        var latest = nowUtcMillis + (delta / (slope * PaceConstants.PACE_UNCERTAINTY_SLOW) * 60_000.0).toLong()
        if (latest - earliest < PaceConstants.PACE_MIN_WIDTH_MINUTES * 60_000L) {
            val mid = (earliest + latest) / 2
            earliest = mid - PaceConstants.PACE_MIN_WIDTH_MINUTES * 30_000L
            latest = mid + PaceConstants.PACE_MIN_WIDTH_MINUTES * 30_000L
        }
        // Until this cook has cleared the stall band (or finished a stall),
        // the stall is still ahead of it: a pace-only range would pretend
        // otherwise, so the history envelope widens it.
        val spans = stallSpans(series)
        val cleared = series.last().fahrenheit > PaceConstants.STALL_BAND_HIGH_F ||
            (spans.isNotEmpty() && spans.last().endUtcMillis < series.last().atUtcMillis)
        if (envelope != null && !cleared) {
            val widened = FinishRange(min(earliest, envelope.first), max(latest, envelope.second),
                if (envelope.first < earliest || envelope.second > latest) PaceBasis.HISTORY else PaceBasis.PACE)
            return PaceAssessment(FinishEstimate.Ranged(widened), stall)
        }
        return PaceAssessment(FinishEstimate.Ranged(FinishRange(earliest, latest, PaceBasis.PACE)), stall)
    }

    /**
     * Derive [PaceHistory] from past archives (already filtered by the
     * caller to the same protein/cut). [targetFByDish] keys are
     * "cookId/dishId". A cook contributes its total duration when its finish
     * is observable — the recorded pull (remove event) when the
     * archive has one, else the first probe reading at/above target — and
     * its stall durations / post-stall slopes when the detector finds one.
     */
    fun history(archives: List<InsightArchive>, targetFByDish: Map<String, Double>): PaceHistory {
        val totals = mutableListOf<Double>()
        val stalls = mutableListOf<Double>()
        val postSlopes = mutableListOf<Double>()
        for (archive in archives) {
            for (dish in archive.dishes) {
                val target = targetFByDish["${dish.cookId}/${dish.id}"] ?: continue
                val series = archive.readings
                    .filter { it.cookId == dish.cookId && (it.dishId == dish.id || it.dishId == null) &&
                        InsightsCalculations.role(it.measurementType) == "food" &&
                        it.qualityStatus == "valid" && it.value != null && it.value.isFinite() }
                    .map { PaceReading(it.measuredAtUtcMillis, InsightsCalculations.fahrenheit(it.value!!, it.unit)) }
                    .sortedBy { it.atUtcMillis }
                if (series.size < 2) continue
                val foodOn = archive.events.filter { it.cookId == dish.cookId && it.action == "food_on" && (it.dishIds.isEmpty() || dish.id in it.dishIds) }
                    .minOfOrNull { it.at } ?: archive.cooks.firstOrNull { it.id == dish.cookId }?.startedAtUtcMillis ?: continue
                // The finish is the cook's recorded pull (the remove
                // event) when the archive has one: real probes routinely
                // peak a fraction below the target — the corpus briskets
                // top out at 202.8°F for a 203°F target — so demanding a
                // literal crossing would throw away exactly the cooks
                // history should learn from. dish_done is only the
                // fallback: in the app's phase order it means "ready to
                // serve", after the rest, so preferring it would fold
                // rest time into the cooking total. Without a recorded
                // pull, the first crossing of the target is the finish.
                val scopedToDish: (InsightEventInput) -> Boolean = { event ->
                    dish.id in event.dishIds ||
                        (event.dishIds.isEmpty() && archive.dishes.count { it.cookId == dish.cookId } == 1)
                }
                val finish = listOf("remove", "dish_done").firstNotNullOfOrNull { action ->
                    archive.events.filter { it.cookId == dish.cookId && it.action == action && scopedToDish(it) }.minOfOrNull { it.at }
                } ?: series.firstOrNull { it.fahrenheit >= target }?.atUtcMillis ?: continue
                totals += (finish - foodOn) / 60_000.0
                for (span in stallSpans(series.filter { it.atUtcMillis <= finish })) {
                    stalls += span.durationMinutes
                    val climb = series.filter { it.atUtcMillis >= span.endUtcMillis && it.atUtcMillis <= finish }
                    val slope = slopePerMinute(climb)
                    if (slope != null && slope > 0.0) postSlopes += slope
                }
            }
        }
        return PaceHistory(totals, stalls, postSlopes)
    }
}

/**
 * Anti-jitter publication for stated ranges (spec §4.1): a published range
 * updates at most once per [PaceConstants.SMOOTHER_CADENCE_MINUTES], and
 * neither edge moves more than [PaceConstants.SMOOTHER_MAX_EDGE_STEP_MINUTES]
 * per publication. One smoother per dish per cook.
 */
class PaceRangeSmoother {
    private var published: FinishRange? = null
    private var publishedAtUtcMillis: Long = Long.MIN_VALUE

    /** Returns the range to display now, or null when nothing is statable. */
    fun publish(candidate: FinishRange?, nowUtcMillis: Long): FinishRange? {
        if (candidate == null) return null
        val current = published
        if (current == null) {
            published = candidate; publishedAtUtcMillis = nowUtcMillis
            return candidate
        }
        if (nowUtcMillis - publishedAtUtcMillis < PaceConstants.SMOOTHER_CADENCE_MINUTES * 60_000L) return current
        val step = PaceConstants.SMOOTHER_MAX_EDGE_STEP_MINUTES * 60_000L
        fun clamp(next: Long, prev: Long) = prev + (next - prev).coerceIn(-step, step)
        val out = FinishRange(clamp(candidate.earliestUtcMillis, current.earliestUtcMillis), clamp(candidate.latestUtcMillis, current.latestUtcMillis), candidate.basis)
        published = out; publishedAtUtcMillis = nowUtcMillis
        return out
    }

    fun reset() { published = null; publishedAtUtcMillis = Long.MIN_VALUE }
}

/** The generated basis line for a stated (or withheld) finish estimate. */
fun PaceBasis.displayText(): String = when (this) {
    PaceBasis.PACE -> "from your last 30 min of readings"
    PaceBasis.HISTORY -> "from your past cooks — the stall is still ahead"
    PaceBasis.STALL -> "in the stall — widened from your past stalls"
    PaceBasis.PLAN -> "from your plan — not enough readings yet"
    PaceBasis.FINISHED -> "done"
}
