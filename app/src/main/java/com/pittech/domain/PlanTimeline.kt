package com.pittech.domain

import kotlin.math.roundToLong

/**
 * Plan-timeline derivation (Flowing spec §4.4, engine side): the serve plan
 * is the one timeline. From a [ServePlan] this derives per-dish lanes with
 * finish targets against the serve line, the alert set those lanes imply
 * (food-on / wrap / off — derived, never re-entered), and warn-and-stretch
 * resolution for one-pit conflicts. Slice 4 renders this; nothing here
 * touches the UI. The iOS PlanTimeline mirrors this file rule for rule.
 */
data class PlanLane(
    val dishIndex: Int,
    val dishName: String,
    val pitF: Double?,
    val prepAtUtcMillis: Long,
    val preheatAtUtcMillis: Long,
    val foodOnAtUtcMillis: Long,
    val wrapAtUtcMillis: Long?,
    val removeEarliestUtcMillis: Long,
    val removeLatestUtcMillis: Long,
    /** Finish targets: ready (post-rest) window edges against the serve line. */
    val readyEarliestUtcMillis: Long,
    val readyLatestUtcMillis: Long,
    val serveAtUtcMillis: Long,
    val stretch: StretchAdjustment? = null,
)

data class StretchAdjustment(
    val dishIndex: Int,
    /** Minutes added to the dish's longest cook duration by the stretch. */
    val addedMinutes: Long,
    /** The stated basis, shown wherever the stretch is shown. */
    val basis: String,
    val adjustedCookMinMinutes: Long,
    val adjustedCookMaxMinutes: Long,
)

data class DerivedAlert(
    val dishIndex: Int,
    val kind: Kind,
    /** Null when the alert is condition-based (a temperature) rather than timed. */
    val atUtcMillis: Long?,
    val temperatureF: Double? = null,
    val label: String,
) {
    enum class Kind { FOOD_ON, WRAP, OFF }
}

data class PlanConflict(val dishIndexA: Int, val dishIndexB: Int)

data class PlanTimelineResult(
    val lanes: List<PlanLane>,
    val alerts: List<DerivedAlert>,
    val conflicts: List<PlanConflict>,
    /** The pit temperature the cook chose, once a conflict is resolved. */
    val chosenPitF: Double? = null,
)

object PlanTimeline {
    /**
     * Warn-and-stretch rate: a dish planned for a hotter pit takes longer
     * at a cooler one — durations scale by this fraction per °F of
     * difference. At 0.5%/°F, ribs planned at 275° in a 250° pit stretch
     * by 12.5% (a 280-minute cook gains the book's +35 min).
     */
    const val STRETCH_RATE_PER_FAHRENHEIT = 0.005

    /** Pit temperatures within this many °F are one pit, not a conflict. */
    const val PIT_CONFLICT_THRESHOLD_F = 10.0

    fun derive(plan: ServePlan): PlanTimelineResult = build(plan, plan.schedules, chosenPitF = null, stretches = emptyMap())

    /**
     * Resolve one-pit conflicts by the cook's chosen pit temperature:
     * dishes planned hotter than the chosen pit stretch (never block);
     * dishes planned at or below it are unchanged. The returned timeline's
     * lanes and alerts reflect the stretched durations.
     */
    fun applyPitChoice(plan: ServePlan, chosenPitF: Double): PlanTimelineResult {
        val stretches = mutableMapOf<Int, StretchAdjustment>()
        val schedules = plan.schedules.map { schedule ->
            val pit = schedule.pitF
            if (pit == null || pit - chosenPitF <= PIT_CONFLICT_THRESHOLD_F) schedule
            else {
                val factor = 1.0 + STRETCH_RATE_PER_FAHRENHEIT * (pit - chosenPitF)
                val adjustedMin = (schedule.cookMinMinutes * factor).roundToLong()
                val adjustedMax = (schedule.cookMaxMinutes * factor).roundToLong()
                val added = adjustedMax - schedule.cookMaxMinutes
                stretches[schedule.dishIndex] = StretchAdjustment(
                    dishIndex = schedule.dishIndex,
                    addedMinutes = added,
                    basis = "stretched +$added min at ${formatTemp(chosenPitF)} (planned ${formatTemp(pit)}; " +
                        "durations scale +${trimRate(STRETCH_RATE_PER_FAHRENHEIT * 100)}% per °F the pit runs cooler)",
                    adjustedCookMinMinutes = adjustedMin,
                    adjustedCookMaxMinutes = adjustedMax,
                )
                schedule.copy(cookMinMinutes = adjustedMin, cookMaxMinutes = adjustedMax)
            }
        }
        return build(plan, schedules, chosenPitF, stretches)
    }

    private fun build(plan: ServePlan, schedules: List<DishSchedule>, chosenPitF: Double?, stretches: Map<Int, StretchAdjustment>): PlanTimelineResult {
        val adjusted = plan.copy(schedules = schedules)
        val windows = ServeTimePlanner.windows(adjusted).associateBy { it.dishIndex }
        val dishes = plan.book.draft.dishes
        val lanes = schedules.map { schedule ->
            val window = windows.getValue(schedule.dishIndex)
            PlanLane(
                dishIndex = schedule.dishIndex,
                dishName = dishes.getOrNull(schedule.dishIndex)?.name ?: "Dish ${schedule.dishIndex + 1}",
                pitF = schedule.pitF,
                prepAtUtcMillis = window.prepAt,
                preheatAtUtcMillis = window.preheatAt,
                foodOnAtUtcMillis = window.foodOnAt,
                wrapAtUtcMillis = wrapAt(plan, schedule.dishIndex, window.foodOnAt),
                removeEarliestUtcMillis = window.removeEarliest,
                removeLatestUtcMillis = window.removeLatest,
                readyEarliestUtcMillis = window.readyEarliest,
                readyLatestUtcMillis = window.readyLatest,
                serveAtUtcMillis = plan.serveAt,
                stretch = stretches[schedule.dishIndex],
            )
        }
        val alerts = mutableListOf<DerivedAlert>()
        for (lane in lanes) {
            alerts += DerivedAlert(lane.dishIndex, DerivedAlert.Kind.FOOD_ON, lane.foodOnAtUtcMillis,
                label = "${lane.dishName}: food on")
            wrapStep(plan, lane.dishIndex)?.let { step ->
                val at = wrapAt(plan, lane.dishIndex, lane.foodOnAtUtcMillis)
                alerts += DerivedAlert(lane.dishIndex, DerivedAlert.Kind.WRAP, at, step.temperatureF?.takeIf { at == null },
                    label = "${lane.dishName}: wrap")
            }
            alerts += DerivedAlert(lane.dishIndex, DerivedAlert.Kind.OFF, lane.removeEarliestUtcMillis,
                label = "${lane.dishName}: off window opens")
        }
        val conflicts = ServeTimePlanner.conflicts(adjusted).map { PlanConflict(it.first, it.second) }
        return PlanTimelineResult(lanes, alerts.sortedWith(compareBy<DerivedAlert> { it.atUtcMillis ?: Long.MAX_VALUE }.thenBy { it.dishIndex }), conflicts, chosenPitF)
    }

    private fun wrapStep(plan: ServePlan, dishIndex: Int): PlaybookStep? =
        plan.book.steps.firstOrNull { it.action == "wrap" && (it.dishIndex == dishIndex || it.dishIndex == null) }

    /** When the dish's wrap step falls, from its trigger; null when it is not time-based. */
    private fun wrapAt(plan: ServePlan, dishIndex: Int, foodOnAt: Long): Long? {
        val step = wrapStep(plan, dishIndex) ?: return null
        return when (step.trigger) {
            "elapsed" -> foodOnAt + step.minutes * 60_000L
            "after_action" -> if (step.anchor == "food_on") foodOnAt + step.minutes * 60_000L else null
            "clock" -> step.clockAtUtcMillis
            else -> null // temperature / manual: condition-based, no fixed time
        }
    }

    private fun formatTemp(f: Double): String =
        if (f == f.toLong().toDouble()) "${f.toLong()}°" else "$f°"

    private fun trimRate(percent: Double): String =
        if (percent == percent.toLong().toDouble()) percent.toLong().toString() else percent.toString()
}
