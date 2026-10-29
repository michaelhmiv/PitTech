package com.pittech.domain

/**
 * The plan day-board's derivation (Flowing spec Spread 03, §4.4 surfaces):
 * Slice 1's [PlanTimeline] derives the lanes; this file decides what the
 * board *shows* — the answer first (3.1), the stagger against the serve
 * line (3.2), and warn-and-stretch resolution (3.3) — as pure functions
 * the UI renders and the tests pin. The iOS plan board mirrors this file
 * rule for rule.
 */
object PlanBoard {
    /**
     * 3.1: the dish that starts the day — the lane with the earliest
     * food-on (ties break to the lowest dish index, the plan's own order).
     * Null only for a lane-less timeline, which a validated plan never is.
     */
    fun headlineLane(timeline: PlanTimelineResult): PlanLane? =
        timeline.lanes.minWithOrNull(compareBy<PlanLane> { it.foodOnAtUtcMillis }.thenBy { it.dishIndex })

    /**
     * 3.2: the board's time window — earliest food-on to the serve line.
     * Every lane is drawn inside it; the serve line is its right edge.
     */
    fun boardWindow(timeline: PlanTimelineResult): Pair<Long, Long>? {
        val headline = headlineLane(timeline) ?: return null
        val serveAt = timeline.lanes.first().serveAtUtcMillis
        return headline.foodOnAtUtcMillis to serveAt
    }

    /** A lane's bar inside the board window: food-on → ready (latest). */
    fun laneSpan(lane: PlanLane, window: Pair<Long, Long>): Pair<Double, Double> {
        val span = (window.second - window.first).toDouble()
        if (span <= 0) return 0.0 to 1.0
        val start = ((lane.foodOnAtUtcMillis - window.first) / span).coerceIn(0.0, 1.0)
        val end = ((lane.readyLatestUtcMillis - window.first) / span).coerceIn(start, 1.0)
        return start to end
    }

    /**
     * 3.3's default resolution when the cook hasn't picked: the pit is set
     * by the earliest-starting dish in the conflict — physically, the
     * first dish on the pit sets its temperature. Null when there is no
     * conflict to resolve.
     */
    fun defaultPitF(timeline: PlanTimelineResult): Double? {
        if (timeline.conflicts.isEmpty()) return null
        val conflicted = timeline.conflicts.flatMap { listOf(it.dishIndexA, it.dishIndexB) }.toSet()
        return timeline.lanes.filter { it.dishIndex in conflicted }
            .minWithOrNull(compareBy<PlanLane> { it.foodOnAtUtcMillis }.thenBy { it.dishIndex })?.pitF
    }

    /** The distinct pit temperatures the cook can pick between, in lane order. */
    fun conflictPitChoices(timeline: PlanTimelineResult): List<Double> {
        val conflicted = timeline.conflicts.flatMap { listOf(it.dishIndexA, it.dishIndexB) }.toSet()
        return timeline.lanes.filter { it.dishIndex in conflicted }.mapNotNull { it.pitF }.distinct()
    }

    /**
     * The timeline the board renders: unconflicted plans derive plainly;
     * conflicted plans resolve by the cook's chosen pit, or by
     * [defaultPitF] until they pick — warn-and-stretch either way, never
     * a block (3.3).
     */
    fun resolvedTimeline(plan: ServePlan, chosenPitF: Double?): PlanTimelineResult {
        val plain = PlanTimeline.derive(plan)
        if (plain.conflicts.isEmpty()) return plain
        val pit = chosenPitF ?: defaultPitF(plain) ?: return plain
        return PlanTimeline.applyPitChoice(plan, pit)
    }

    /**
     * The plan as it is *saved* once a resolution is applied: stretched
     * lanes carry their stretched durations and the pit they will
     * actually cook at, with the stretch recorded in the schedule's
     * evidence note — so a saved plan re-derives to the same lanes with
     * no conflict left, and the resolution stays visible on the dish
     * card instead of evaporating at save.
     */
    fun stretchedPlan(plan: ServePlan, timeline: PlanTimelineResult): ServePlan {
        if (timeline.lanes.none { it.stretch != null }) return plan
        val chosen = timeline.chosenPitF ?: return plan
        val schedules = plan.schedules.map { schedule ->
            val lane = timeline.lanes.firstOrNull { it.dishIndex == schedule.dishIndex }
            val stretch = lane?.stretch ?: return@map schedule
            schedule.copy(
                cookMinMinutes = stretch.adjustedCookMinMinutes,
                cookMaxMinutes = stretch.adjustedCookMaxMinutes,
                pitF = chosen,
                evidenceNote = schedule.evidenceNote + " · Stretched +${stretch.addedMinutes} min at ${formatTemp(chosen)} (planned ${formatTemp(schedule.pitF)})",
            )
        }
        return plan.copy(schedules = schedules)
    }

    private fun formatTemp(f: Double?): String =
        if (f == null) "the chosen pit" else if (f == f.toLong().toDouble()) "${f.toLong()}°" else "$f°"
}
