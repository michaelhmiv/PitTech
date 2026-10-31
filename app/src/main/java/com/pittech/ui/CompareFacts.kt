package com.pittech.ui

import com.pittech.data.CookStatus
import com.pittech.data.CookWithDishes
import com.pittech.data.DishEntity
import com.pittech.data.InsightsSnapshot
import com.pittech.data.SensorReadingEntity
import com.pittech.data.insightArchive
import com.pittech.data.notePlanEvents
import com.pittech.domain.CookInputVocabulary
import com.pittech.domain.CookInputs
import com.pittech.domain.CookPaceEngine
import com.pittech.domain.CookPlanEngine
import com.pittech.domain.FinishEstimate
import com.pittech.domain.InsightArchive
import com.pittech.domain.InsightsCalculations
import com.pittech.domain.LiveGuidance
import com.pittech.domain.PaceBasis
import com.pittech.domain.PaceReading
import com.pittech.domain.SpritzState
import com.pittech.domain.WrapMaterial
import com.pittech.domain.displayText

/**
 * Flowing Slice 6 — Spread 05 "Compare that answers" (spec §5), Android.
 * Coverage ledger, quoted verbatim from the approved spec; an element is
 * built only when every clause below is satisfied:
 *
 * - 5.1 "Aligned at food-on with an offset stepper (FireBoard's pattern)."
 *   → [CompareCookFacts.foodOnUtcMillis] anchors every compared curve at
 *   its own food-on moment; the screen's per-cook stepper shifts a curve
 *   by ±5 min steps through the chart's `cookOffsetMillis` (display-only).
 * - 5.2 "Inputs recorded as data (§4.2) and diffed beside outcomes: wrap
 *   material, rub, setpoint — stall 2h 40m → 1h 35m as the canonical
 *   example." → the grid's Wrap / Rub / Spritz / Setpoint rows come from
 *   [CookInputVocabulary] + the cook's recorded initial setpoint, beside
 *   outcome rows whose Stall figure is the §4.1 detector's total over the
 *   lead dish's recorded readings. The canonical pair is a unit test.
 * - 5.3 "Projections wear a PROJECTED tag, never blended into fact; a
 *   projected total is computed by §4.1 from the in-progress cook."
 *   → [compareCookFacts] runs [CookPaceEngine.assess] for an in-progress
 *   cook and returns the range as [ProjectedCompareTotal], a type the
 *   factual rows never carry; the grid tags it PROJECTED and states its
 *   basis (§4.1: "Every surface showing the range shows the basis").
 * - 5.4 "One generated sentence from the cook's own history does the
 *   teaching ("Paper is winning: your last three paper wraps stalled ~1h
 *   shorter than foil." — generated only when the underlying recorded
 *   data supports it; otherwise absent, never templated filler)."
 *   → [compareTeachingSentence]; §9 restraint: "it appears only with
 *   recorded-data support (≥3 comparable cooks). Below that, silence."
 * - 5.5 "Honest history: cooks predating §4.2 show "inputs not recorded"
 *   in the diff; the overlay and outcome diff still work from readings."
 *   → input cells read [CookInputVocabulary.NOT_RECORDED_LABEL] when no
 *   §4.2 row exists, while stall / time / temperature outcomes are still
 *   computed from the cook's readings.
 */

/** A §4.1 projection for an in-progress cook: the total time from food-on
 * to the projected finish, as a range — never a point, never a fact. */
data class ProjectedCompareTotal(
    val earliestMinutes: Double,
    val latestMinutes: Double,
    val basis: PaceBasis,
) {
    /** §4.1: every surface showing the range shows the basis. */
    val basisText: String get() = basis.displayText()
}

/** Everything the compare grid renders for one cook, pre-computed from
 * recorded data only. Factual fields and the projection stay in separate
 * fields so a projection can never be blended into fact by the UI. */
data class CompareCookFacts(
    val cookId: String,
    val title: String,
    val inProgress: Boolean,
    /** 5.1 anchor: the cook's earliest recorded food-on moment. */
    val foodOnUtcMillis: Long,
    val inputs: CookInputs,
    /** True when at least one §4.2 field was recorded for this cook. */
    val inputsRecorded: Boolean,
    val wrapText: String,
    val rubText: String,
    val spritzText: String,
    val setpointText: String,
    /** Total stall minutes the §4.1 detector finds in the lead dish's
     * recorded readings; null when no stall was recorded. */
    val stallMinutes: Double?,
    /** 5.3: non-null only for an in-progress cook with a §4.1 range. */
    val projected: ProjectedCompareTotal?,
    /** Why §4.1 stated no range, when it didn't (its own words). */
    val projectionWithheldReason: String?,
    /** The in-progress cook's probe has reached its recorded target. */
    val reachedTarget: Boolean,
    /** Minutes from food-on to now, for an in-progress cook's "so far". */
    val elapsedMinutes: Double,
)

/** The dish compare reads first: the first dish with recorded food
 * readings, else the first dish — the Live board's lead-dish convention. */
internal fun compareLeadDish(item: CookWithDishes, data: InsightsSnapshot): DishEntity? =
    item.dishes.firstOrNull { dish ->
        data.readings.any { it.cookId == item.cook.id && it.dishId == dish.id && InsightsCalculations.role(it.measurementType) == "food" }
    } ?: item.dishes.firstOrNull()

/** 5.1 anchor: the earliest food-on plan event, else the session start. */
internal fun compareFoodOnUtcMillis(item: CookWithDishes): Long =
    notePlanEvents(item.events).filter { it.action == "food_on" }.minOfOrNull { it.at }
        ?: item.cook.startedAtUtcMillis

private fun compareInputs(item: CookWithDishes, archive: InsightArchive, leadDish: DishEntity?): CookInputs {
    val hasSpritzEvent = item.events.any { event ->
        event.eventType == "spritz" && (leadDish == null || event.dishId == null || event.dishId == leadDish.id)
    }
    return CookInputVocabulary.fromResults(archive.results, item.cook.id, leadDish?.id, hasSpritzEvent)
}

/**
 * The lead dish's food-probe series in °F, built exactly the way the
 * §4.1 engine's own history derivation builds it (dish-scoped or
 * cook-scoped readings, food role, valid, finite).
 */
internal fun compareFoodSeries(item: CookWithDishes, readings: List<SensorReadingEntity>, dishId: String?): List<PaceReading> =
    readings.filter {
        it.cookId == item.cook.id && (dishId == null || it.dishId == dishId || it.dishId == null) &&
            InsightsCalculations.role(it.measurementType) == "food" && it.qualityStatus == "valid" && it.value.isFinite()
    }.map { PaceReading(it.measuredAtUtcMillis, InsightsCalculations.fahrenheit(it.value, it.unit)) }
        .sortedBy { it.atUtcMillis }

/** The §4.1 detector's total stall for one cook's lead dish, from
 * readings alone — so it works for cooks that predate §4.2 (5.5). */
internal fun compareStallMinutes(item: CookWithDishes, readings: List<SensorReadingEntity>, dishId: String?): Double? {
    val spans = CookPaceEngine.stallSpans(compareFoodSeries(item, readings, dishId))
    return spans.takeIf { it.isNotEmpty() }?.sumOf { it.durationMinutes }
}

fun compareCookFacts(
    item: CookWithDishes,
    data: InsightsSnapshot,
    nowUtcMillis: Long,
    archive: InsightArchive = insightArchive(data.cooks, data.readings, data.results),
): CompareCookFacts {
    val cook = item.cook
    val leadDish = compareLeadDish(item, data)
    val inputs = compareInputs(item, archive, leadDish)
    val foodOn = compareFoodOnUtcMillis(item)
    val inProgress = cook.status != CookStatus.COMPLETED
    var projected: ProjectedCompareTotal? = null
    var withheld: String? = null
    var reachedTarget = false
    if (inProgress) {
        if (leadDish == null) {
            withheld = "no dish recorded for this cook"
        } else {
            // The same target the Live board reads: the dish's own
            // personal_finish target, else the cook-level one.
            val target = data.targets.firstOrNull { it.cookId == cook.id && it.dishId == leadDish.id && it.targetType == "personal_finish" }
                ?: data.targets.firstOrNull { it.cookId == cook.id && it.dishId == null && it.targetType == "personal_finish" }
            if (target == null) {
                withheld = "no finish target recorded for this cook"
            } else {
                val series = foodSeries(data.readings.filter { it.cookId == cook.id }, leadDish.id, 0L)
                val history = LiveGuidance.historyFor(archive, cook.id, leadDish.id, leadDish.cut)
                when (val finish = CookPaceEngine.assess(series, CookPlanEngine.fahrenheit(target.value, target.unit), nowUtcMillis, foodOn, history).finish) {
                    is FinishEstimate.Ranged -> projected = ProjectedCompareTotal(
                        earliestMinutes = (finish.range.earliestUtcMillis - foodOn) / 60_000.0,
                        latestMinutes = (finish.range.latestUtcMillis - foodOn) / 60_000.0,
                        basis = finish.range.basis,
                    )
                    is FinishEstimate.PlanOnly -> withheld = finish.reason
                    FinishEstimate.Finished -> reachedTarget = true
                }
            }
        }
    }
    return CompareCookFacts(
        cookId = cook.id,
        title = cook.title,
        inProgress = inProgress,
        foodOnUtcMillis = foodOn,
        inputs = inputs,
        inputsRecorded = inputs.wrapMaterial != null || inputs.rub != null || inputs.spritz != SpritzState.NOT_RECORDED,
        wrapText = inputs.wrapMaterial?.label ?: CookInputVocabulary.NOT_RECORDED_LABEL,
        rubText = inputs.rub ?: CookInputVocabulary.NOT_RECORDED_LABEL,
        spritzText = when (inputs.spritz) {
            SpritzState.YES -> inputs.spritzMedium?.let { "Yes · $it" } ?: "Yes"
            SpritzState.NO -> "No"
            SpritzState.NOT_RECORDED -> CookInputVocabulary.NOT_RECORDED_LABEL
        },
        setpointText = cook.initialSetpointValue?.let { value ->
            "${formatCompareNumber(value)}${cook.initialSetpointUnit?.replace("°F", "°")?.replace("°C", "°") ?: "°"}"
        } ?: CookInputVocabulary.NOT_RECORDED_LABEL,
        stallMinutes = compareStallMinutes(item, data.readings, leadDish?.id),
        projected = projected,
        projectionWithheldReason = withheld,
        reachedTarget = reachedTarget,
        elapsedMinutes = ((nowUtcMillis - foodOn).coerceAtLeast(0) / 60_000.0),
    )
}

/**
 * 5.4: the one generated teaching sentence, or null. A sentence exists
 * only when the recorded data carries it: finished cooks of one protein
 * (the lead dish's recorded cut) whose wrap material was recorded as
 * data (§4.2) and whose stall the §4.1 detector finds in their readings.
 * The winning material needs its last three recorded cooks (§9's ≥3
 * comparable-cooks restraint), the other material at least two, and the
 * average stall gap at least 20 minutes — the printed "~" figure is
 * rounded down, so the sentence never claims more than the data shows.
 */
fun compareTeachingSentence(data: InsightsSnapshot): String? {
    val archive = insightArchive(data.cooks, data.readings, data.results)
    data class WrapStall(val material: WrapMaterial, val stallMinutes: Double, val endedAtUtcMillis: Long)
    val byProtein = mutableMapOf<String, MutableList<WrapStall>>()
    for (item in data.cooks) {
        if (item.cook.status != CookStatus.COMPLETED) continue
        val leadDish = compareLeadDish(item, data) ?: continue
        val protein = leadDish.cut?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
        val material = compareInputs(item, archive, leadDish).wrapMaterial ?: continue
        if (material == WrapMaterial.NONE) continue
        val stall = compareStallMinutes(item, data.readings, leadDish.id) ?: continue
        byProtein.getOrPut(protein) { mutableListOf() } +=
            WrapStall(material, stall, item.cook.endedAtUtcMillis ?: item.cook.startedAtUtcMillis)
    }
    data class Verdict(val sentence: String, val support: Int, val marginMinutes: Double, val protein: String)
    val verdicts = mutableListOf<Verdict>()
    for ((protein, rows) in byProtein) {
        val paper = rows.filter { it.material == WrapMaterial.BUTCHER_PAPER }.sortedByDescending { it.endedAtUtcMillis }
        val foil = rows.filter { it.material == WrapMaterial.FOIL }.sortedByDescending { it.endedAtUtcMillis }
        fun verdict(winner: List<WrapStall>, loser: List<WrapStall>, winnerWord: String, loserWord: String): Verdict? {
            if (winner.size < 3 || loser.size < 2) return null
            val margin = loser.take(3).map { it.stallMinutes }.average() - winner.take(3).map { it.stallMinutes }.average()
            if (margin < 20.0) return null
            val claim = if (margin >= 50.0) "~${(margin / 60).toInt()}h" else "~${(margin / 5).toInt() * 5}m"
            return Verdict(
                "${winnerWord.replaceFirstChar { it.uppercase() }} is winning: your last three $winnerWord wraps stalled $claim shorter than $loserWord.",
                winner.size + loser.size, margin, protein,
            )
        }
        verdict(paper, foil, "paper", "foil")?.let { verdicts += it }
        verdict(foil, paper, "foil", "paper")?.let { verdicts += it }
    }
    return verdicts.sortedWith(compareByDescending<Verdict> { it.support }.thenByDescending { it.marginMinutes }.thenBy { it.protein }).firstOrNull()?.sentence
}

internal fun formatCompareNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
