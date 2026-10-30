import Foundation

/// The serve-plan day board (Flowing Slice 4 §3): which dish leads, where
/// each lane sits inside the board's window, and how a one-pit conflict
/// resolves — warn, let the cook pick the pit, stretch the rest. The port
/// of Android's PlanBoard over the shared PlanTimeline engine: ServePlan
/// adapts to the engine's PlanInput, and the board reads the result.
enum PlanBoard {
    static func planInput(_ plan: ServePlan) -> PlanInput {
        PlanInput(
            serveAtUtcMillis: plan.serveAtUtcMillis,
            bufferMinutes: plan.bufferMinutes,
            smokerName: plan.book.draft.smokerName,
            schedules: plan.schedules.map { schedule in
                PlanDishSchedule(
                    dishIndex: schedule.dishIndex,
                    dishName: plan.book.draft.dishes.indices.contains(schedule.dishIndex)
                        ? plan.book.draft.dishes[schedule.dishIndex].name
                        : "Dish \(schedule.dishIndex + 1)",
                    cookMinMinutes: schedule.cookMinMinutes,
                    cookMaxMinutes: schedule.cookMaxMinutes,
                    prepMinutes: schedule.prepMinutes,
                    preheatMinutes: schedule.preheatMinutes,
                    restMinutes: schedule.restMinutes,
                    holdMinutes: schedule.holdMinutes,
                    pitF: schedule.pitTemperatureF,
                    method: schedule.method
                )
            },
            wrapSteps: plan.book.steps.filter { $0.action == "wrap" }.map {
                PlanWrapStep(
                    dishIndex: $0.dishIndex,
                    trigger: $0.trigger,
                    minutes: $0.minutes,
                    temperatureF: $0.temperatureF,
                    anchor: $0.anchor,
                    clockAtUtcMillis: $0.clockAtUtcMillis
                )
            }
        )
    }

    /// The lane that starts first; ties go to the lowest dish index so the
    /// headline is stable.
    static func headlineLane(_ timeline: PlanTimelineResult) -> PlanLane? {
        timeline.lanes.min { ($0.foodOnAtUtcMillis, $0.dishIndex) < ($1.foodOnAtUtcMillis, $1.dishIndex) }
    }

    /// The board spans from the headline's food-on to the serve line.
    static func boardWindow(_ timeline: PlanTimelineResult) -> (startUtcMillis: Int64, endUtcMillis: Int64)? {
        guard let headline = headlineLane(timeline) else { return nil }
        return (headline.foodOnAtUtcMillis, headline.serveAtUtcMillis)
    }

    /// A lane's food-on → ready-latest span as fractions of the window.
    static func laneSpan(_ lane: PlanLane, window: (startUtcMillis: Int64, endUtcMillis: Int64)) -> (start: Double, end: Double) {
        let span = Double(max(1, window.endUtcMillis - window.startUtcMillis))
        func fraction(_ at: Int64) -> Double {
            min(1, max(0, Double(at - window.startUtcMillis) / span))
        }
        return (fraction(lane.foodOnAtUtcMillis), fraction(lane.readyLatestUtcMillis))
    }

    private static func conflictedIndexes(_ timeline: PlanTimelineResult) -> Set<Int> {
        Set(timeline.conflicts.flatMap { [$0.dishIndexA, $0.dishIndexB] })
    }

    /// Absent a choice, the board runs at the earliest-starting conflicted
    /// lane's pit — the dish that goes on first sets the temperature.
    static func defaultPitF(_ timeline: PlanTimelineResult) -> Double? {
        let conflicted = conflictedIndexes(timeline)
        return timeline.lanes
            .filter { conflicted.contains($0.dishIndex) }
            .min { ($0.foodOnAtUtcMillis, $0.dishIndex) < ($1.foodOnAtUtcMillis, $1.dishIndex) }?
            .pitF
    }

    /// The pits the cook can choose between: the conflicted lanes' own, in
    /// lane order.
    static func conflictPitChoices(_ timeline: PlanTimelineResult) -> [Double] {
        let conflicted = conflictedIndexes(timeline)
        var choices: [Double] = []
        for lane in timeline.lanes where conflicted.contains(lane.dishIndex) {
            if let pit = lane.pitF, !choices.contains(pit) { choices.append(pit) }
        }
        return choices
    }

    /// The timeline the board shows and a save persists: plain when there
    /// is no conflict, pit-resolved when there is.
    static func resolvedTimeline(plan: ServePlan, chosenPitF: Double?) -> PlanTimelineResult {
        let input = planInput(plan)
        let plain = PlanTimeline.derive(input)
        if plain.conflicts.isEmpty { return plain }
        guard let pit = chosenPitF ?? defaultPitF(plain) else { return plain }
        return PlanTimeline.applyPitChoice(input, chosenPitF: pit)
    }

    /// The plan a save persists: stretched lanes keep their stretched
    /// durations at the chosen pit, with the stretch stated in the lane's
    /// evidence note — a stretched plan never silently differs from what
    /// the board showed.
    static func stretchedPlan(_ plan: ServePlan, timeline: PlanTimelineResult) -> ServePlan {
        guard let chosen = timeline.chosenPitF else { return plan }
        var result = plan
        result.schedules = plan.schedules.map { schedule in
            guard let lane = timeline.lanes.first(where: { $0.dishIndex == schedule.dishIndex }),
                  let stretch = lane.stretch else { return schedule }
            var adjusted = schedule
            adjusted.cookMinMinutes = stretch.adjustedCookMinMinutes
            adjusted.cookMaxMinutes = stretch.adjustedCookMaxMinutes
            adjusted.pitTemperatureF = chosen
            adjusted.evidenceNote = schedule.evidenceNote
                + " · Stretched +\(stretch.addedMinutes) min at \(formatTemp(chosen)) (planned \(formatTemp(schedule.pitTemperatureF)))"
            return adjusted
        }
        return result
    }

    static func formatTemp(_ fahrenheit: Double?) -> String {
        guard let fahrenheit else { return "the chosen pit" }
        return fahrenheit == fahrenheit.rounded() ? "\(Int64(fahrenheit))°" : "\(fahrenheit)°"
    }
}
