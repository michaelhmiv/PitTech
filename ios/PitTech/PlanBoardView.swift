import SwiftUI

/// Clock forms for the plan surfaces. Instants render in the reader's
/// local time, matching how the rest of the app shows cook times.
enum PlanBoardFormat {
    static func clock(_ millis: Int64) -> String {
        Date(timeIntervalSince1970: Double(millis) / 1000).formatted(date: .omitted, time: .shortened)
    }

    static func dateTime(_ millis: Int64) -> String {
        Date(timeIntervalSince1970: Double(millis) / 1000).formatted(date: .abbreviated, time: .shortened)
    }

    static func serveLine(_ plan: ServePlan) -> String {
        "Serve \(dateTime(plan.serveAtUtcMillis)) · \(plan.zoneId)"
    }
}

/// The day board (Flowing Slice 4 §3): the headline is the first dish's
/// food-on — the one number the whole day hangs off — over one lane per
/// dish, with the one-pit conflict resolved by choice and stretch, never
/// by blocking. Used by the editor, the saved-plan cards and (through
/// PlanScheduleStripView) the Live list.
struct PlanDayBoardView: View {
    let plan: ServePlan
    let timeline: PlanTimelineResult
    var chosenPitF: Double? = nil
    var onChoosePit: ((Double) -> Void)? = nil

    private var headline: PlanLane? { PlanBoard.headlineLane(timeline) }
    private var window: (startUtcMillis: Int64, endUtcMillis: Int64)? { PlanBoard.boardWindow(timeline) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let headline, let window {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Food on \(PlanBoardFormat.clock(headline.foodOnAtUtcMillis))")
                        .font(PitTechFont.bannerCard)
                        .foregroundStyle(PitTechStyle.ink)
                        .accessibilityIdentifier("plan-board-headline")
                    Text("\(headline.dishName) goes on first")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(timeline.lanes, id: \.dishIndex) { lane in
                    laneRow(lane, window: window)
                }
                HStack {
                    Text(PlanBoardFormat.clock(window.startUtcMillis))
                    Spacer()
                    Text("Serve \(PlanBoardFormat.clock(window.endUtcMillis))")
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            if !timeline.conflicts.isEmpty {
                conflictStrip
            }
        }
        .accessibilityIdentifier("plan-board")
    }

    private func laneRow(_ lane: PlanLane, window: (startUtcMillis: Int64, endUtcMillis: Int64)) -> some View {
        let span = PlanBoard.laneSpan(lane, window: window)
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(lane.dishName).font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.ink)
                if let pit = lane.pitF {
                    Text(PlanBoard.formatTemp(pit)).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                if let stretch = lane.stretch {
                    Text("+\(stretch.addedMinutes) min stretch")
                        .font(.caption).foregroundStyle(PitTechStyle.emberText)
                        .accessibilityIdentifier("plan-board-stretch-\(lane.dishIndex)")
                }
            }
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 5)
                        .fill(PitTechStyle.surfaceRaised)
                    RoundedRectangle(cornerRadius: 5)
                        .fill(PitTechStyle.ember)
                        .frame(width: max(6, geometry.size.width * (span.end - span.start)))
                        .offset(x: geometry.size.width * span.start)
                }
            }
            .frame(height: 10)
            Text(laneCaption(lane))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityIdentifier("plan-board-lane-\(lane.dishIndex)")
    }

    private func laneCaption(_ lane: PlanLane) -> String {
        var parts = ["Food on \(PlanBoardFormat.clock(lane.foodOnAtUtcMillis))"]
        if let wrap = lane.wrapAtUtcMillis {
            parts.append("Wrap \(PlanBoardFormat.clock(wrap))")
        }
        parts.append("Off \(PlanBoardFormat.clock(lane.removeEarliestUtcMillis))–\(PlanBoardFormat.clock(lane.removeLatestUtcMillis))")
        parts.append("Ready \(PlanBoardFormat.clock(lane.readyEarliestUtcMillis))–\(PlanBoardFormat.clock(lane.readyLatestUtcMillis))")
        return parts.joined(separator: " · ")
    }

    private var conflictStrip: some View {
        let choices = PlanBoard.conflictPitChoices(timeline)
        let running = timeline.chosenPitF ?? PlanBoard.defaultPitF(timeline)
        let pairs = timeline.conflicts.map { conflict -> String in
            let a = timeline.lanes.first { $0.dishIndex == conflict.dishIndexA }
            let b = timeline.lanes.first { $0.dishIndex == conflict.dishIndexB }
            return "\(a?.dishName ?? "Dish") at \(PlanBoard.formatTemp(a?.pitF)), \(b?.dishName ?? "Dish") at \(PlanBoard.formatTemp(b?.pitF))"
        }
        return VStack(alignment: .leading, spacing: 8) {
            Text("One pit: \(pairs.joined(separator: "; ")) — running at \(PlanBoard.formatTemp(running)), the rest stretch.")
                .font(.footnote)
                .foregroundStyle(PitTechStyle.ink)
            if choices.count > 1 {
                HStack(spacing: 8) {
                    ForEach(choices, id: \.self) { pit in
                        Button(PlanBoard.formatTemp(pit)) { onChoosePit?(pit) }
                            .font(PitTechFont.bodyEmphasis)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(
                                (running == pit ? PitTechStyle.primaryFill : PitTechStyle.surfaceRaised),
                                in: Capsule()
                            )
                            .foregroundStyle(running == pit ? PitTechStyle.onAccent : PitTechStyle.ink)
                            .accessibilityIdentifier("plan-board-pit-\(Int(pit))")
                    }
                }
            }
        }
        .padding(10)
        .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier("plan-board-conflict")
    }
}

/// The Live strip: a started plan's lanes against what the cook has
/// actually logged — Done, On the pit, Up next. Shown on the Meals list
/// under the active cooks (Android's PlanScheduleStrip).
struct PlanScheduleStripView: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    let plan: ServePlan

    private struct LaneState {
        let name: String
        let text: String
    }

    private func laneStates() -> [LaneState] {
        let dishIds = store.dishRecords(cookId).map(\.id)
        return plan.book.draft.dishes.enumerated().map { index, dish in
            let dishId = dishIds.indices.contains(index) ? dishIds[index] : nil
            let events = store.planEvents(cookId: cookId, dishId: dishId)
            if events.contains(where: { $0.action == "dish_done" }) {
                return LaneState(name: dish.name, text: "Done")
            }
            if events.contains(where: { $0.action == "food_on" }) {
                return LaneState(name: dish.name, text: "On the pit")
            }
            if let schedule = plan.schedules.first(where: { $0.dishIndex == index }) {
                let foodOn = ServeTimePlanner.foodOn(plan: plan, schedule: schedule)
                return LaneState(name: dish.name, text: "Up next · food on \(PlanBoardFormat.clock(foodOn))")
            }
            return LaneState(name: dish.name, text: "Up next")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TurnInMetaLabel("Today's plan")
                Spacer()
                Text("Serve \(PlanBoardFormat.clock(plan.serveAtUtcMillis))")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(Array(laneStates().enumerated()), id: \.offset) { index, lane in
                HStack {
                    Text(lane.name).font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.ink)
                    Spacer()
                    Text(lane.text).font(.footnote).foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("live-plan-lane-\(index)")
            }
        }
        .padding(14)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(PitTechStyle.ruling, lineWidth: 1))
        .accessibilityIdentifier("live-plan-strip")
    }
}

/// The serve goal on a started plan's Live board (Android's
/// ActiveServeGoalView): the serve line, each dish's planning window as
/// its own events update it, and the honest flag when a window now ends
/// after the serve time.
struct ActiveServeGoalView: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    let plan: ServePlan

    var body: some View {
        let dishIds = store.dishRecords(cookId).map(\.id)
        VStack(alignment: .leading, spacing: 8) {
            TurnInMetaLabel("Serve goal")
            Text("Serve \(PlanBoardFormat.clock(plan.serveAtUtcMillis)) · \(plan.zoneId)")
                .font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.ink)
            ForEach(plan.schedules, id: \.dishIndex) { schedule in
                let name = plan.book.draft.dishes.indices.contains(schedule.dishIndex)
                    ? plan.book.draft.dishes[schedule.dishIndex].name
                    : "Dish \(schedule.dishIndex + 1)"
                let dishId = dishIds.indices.contains(schedule.dishIndex) ? dishIds[schedule.dishIndex] : nil
                let window = ServeTimePlanner.updatedReadyWindow(
                    plan: plan, schedule: schedule,
                    events: store.planEvents(cookId: cookId, dishId: dishId)
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(name) · planning window \(PlanBoardFormat.clock(window.earliest))–\(PlanBoardFormat.clock(window.latest))")
                        .font(.footnote).foregroundStyle(PitTechStyle.ink)
                    if window.latest > plan.serveAtUtcMillis {
                        Text("Planning window ends after the \(PlanBoardFormat.clock(plan.serveAtUtcMillis)) serve time.")
                            .font(.caption).foregroundStyle(PitTechStyle.emberText)
                    }
                    if !schedule.evidenceNote.isEmpty {
                        Text(schedule.evidenceNote).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
