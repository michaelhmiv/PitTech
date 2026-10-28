import Foundation

/// Plan-timeline derivation (Flowing spec §4.4, engine side): the serve
/// plan is the one timeline. This derives per-dish lanes with finish
/// targets against the serve line, the alert set those lanes imply
/// (food-on / wrap / off — derived, never re-entered), and warn-and-stretch
/// resolution for one-pit conflicts.
///
/// Platform note: Android binds this derivation to its ServePlan /
/// ServeTimePlanner types. iOS has no serve-plan domain types yet (the
/// critique's structural mismatch #6; Slice 4 builds the iOS editor), so
/// this file carries the derivation's own input vocabulary and the exact
/// window math of Android's ServeTimePlanner — the rules are identical,
/// and Slice 4 binds these inputs to its UI. Mirrors Android's
/// PlanTimeline + the windows/conflicts half of ServeTimePlanner.
struct PlanDishSchedule: Equatable {
    var dishIndex: Int
    var dishName: String
    var cookMinMinutes: Int64 = 480
    var cookMaxMinutes: Int64 = 600
    var prepMinutes: Int64 = 30
    var preheatMinutes: Int64 = 30
    var restMinutes: Int64 = 60
    var holdMinutes: Int64 = 0
    var pitF: Double?
    var method: String = ""
}

struct PlanWrapStep: Equatable {
    var dishIndex: Int?
    var trigger: String = "manual" // elapsed | after_action | clock | temperature | manual
    var minutes: Int64 = 0
    var temperatureF: Double?
    var anchor: String = "food_on"
    var clockAtUtcMillis: Int64?
}

struct PlanInput: Equatable {
    var serveAtUtcMillis: Int64
    var bufferMinutes: Int64 = 60
    var smokerName: String = ""
    var schedules: [PlanDishSchedule]
    var wrapSteps: [PlanWrapStep] = []
}

struct PlanLane: Equatable {
    let dishIndex: Int
    let dishName: String
    let pitF: Double?
    let prepAtUtcMillis: Int64
    let preheatAtUtcMillis: Int64
    let foodOnAtUtcMillis: Int64
    let wrapAtUtcMillis: Int64?
    let removeEarliestUtcMillis: Int64
    let removeLatestUtcMillis: Int64
    let readyEarliestUtcMillis: Int64
    let readyLatestUtcMillis: Int64
    let serveAtUtcMillis: Int64
    let stretch: StretchAdjustment?
}

struct StretchAdjustment: Equatable {
    let dishIndex: Int
    /// Minutes added to the dish's longest cook duration by the stretch.
    let addedMinutes: Int64
    /// The stated basis, shown wherever the stretch is shown.
    let basis: String
    let adjustedCookMinMinutes: Int64
    let adjustedCookMaxMinutes: Int64
}

struct DerivedAlert: Equatable {
    enum Kind: Equatable { case foodOn, wrap, off }
    let dishIndex: Int
    let kind: Kind
    /// Nil when the alert is condition-based (a temperature) rather than timed.
    let atUtcMillis: Int64?
    let temperatureF: Double?
    let label: String
}

struct PlanConflict: Equatable {
    let dishIndexA: Int
    let dishIndexB: Int
}

struct PlanTimelineResult: Equatable {
    let lanes: [PlanLane]
    let alerts: [DerivedAlert]
    let conflicts: [PlanConflict]
    /// The pit temperature the cook chose, once a conflict is resolved.
    let chosenPitF: Double?
}

enum PlanTimeline {
    /// Warn-and-stretch rate: durations scale by this fraction per °F the
    /// pit runs cooler than the dish's plan. At 0.5%/°F, ribs planned at
    /// 275° in a 250° pit stretch by 12.5% (a 280-minute cook gains the
    /// book's +35 min).
    static let stretchRatePerFahrenheit = 0.005
    /// Pit temperatures within this many °F are one pit, not a conflict.
    static let pitConflictThresholdF = 10.0

    private struct Window {
        let prepAt: Int64
        let preheatAt: Int64
        let foodOnAt: Int64
        let removeEarliest: Int64
        let removeLatest: Int64
        let readyEarliest: Int64
        let readyLatest: Int64
    }

    /// ServeTimePlanner.windows, ported: the latest finish works back from
    /// the serve line; food-on is a full longest-cook before it.
    private static func window(_ input: PlanInput, _ s: PlanDishSchedule) -> Window {
        let latest = input.serveAtUtcMillis - (s.restMinutes + s.holdMinutes + input.bufferMinutes) * 60_000
        let foodOn = latest - s.cookMaxMinutes * 60_000
        let earliest = foodOn + s.cookMinMinutes * 60_000
        return Window(
            prepAt: foodOn - (s.prepMinutes + s.preheatMinutes) * 60_000,
            preheatAt: foodOn - s.preheatMinutes * 60_000,
            foodOnAt: foodOn,
            removeEarliest: earliest,
            removeLatest: latest,
            readyEarliest: earliest + s.restMinutes * 60_000,
            readyLatest: latest + s.restMinutes * 60_000)
    }

    static func derive(_ input: PlanInput) -> PlanTimelineResult {
        build(input, schedules: input.schedules, chosenPitF: nil, stretches: [:])
    }

    /// Resolve one-pit conflicts by the cook's chosen pit temperature:
    /// dishes planned hotter than the chosen pit stretch (never block);
    /// dishes planned at or below it are unchanged.
    static func applyPitChoice(_ input: PlanInput, chosenPitF: Double) -> PlanTimelineResult {
        var stretches: [Int: StretchAdjustment] = [:]
        let schedules = input.schedules.map { schedule -> PlanDishSchedule in
            guard let pit = schedule.pitF, pit - chosenPitF > pitConflictThresholdF else { return schedule }
            let factor = 1.0 + stretchRatePerFahrenheit * (pit - chosenPitF)
            let adjustedMin = Int64((Double(schedule.cookMinMinutes) * factor).rounded())
            let adjustedMax = Int64((Double(schedule.cookMaxMinutes) * factor).rounded())
            let added = adjustedMax - schedule.cookMaxMinutes
            stretches[schedule.dishIndex] = StretchAdjustment(
                dishIndex: schedule.dishIndex,
                addedMinutes: added,
                basis: "stretched +\(added) min at \(formatTemp(chosenPitF)) (planned \(formatTemp(pit)); " +
                    "durations scale +\(trimRate(stretchRatePerFahrenheit * 100))% per °F the pit runs cooler)",
                adjustedCookMinMinutes: adjustedMin,
                adjustedCookMaxMinutes: adjustedMax)
            var adjusted = schedule
            adjusted.cookMinMinutes = adjustedMin
            adjusted.cookMaxMinutes = adjustedMax
            return adjusted
        }
        return build(input, schedules: schedules, chosenPitF: chosenPitF, stretches: stretches)
    }

    private static func build(_ input: PlanInput, schedules: [PlanDishSchedule], chosenPitF: Double?, stretches: [Int: StretchAdjustment]) -> PlanTimelineResult {
        let windows = Dictionary(uniqueKeysWithValues: schedules.map { ($0.dishIndex, window(input, $0)) })
        let lanes = schedules.map { schedule -> PlanLane in
            let w = windows[schedule.dishIndex]!
            return PlanLane(
                dishIndex: schedule.dishIndex,
                dishName: schedule.dishName,
                pitF: schedule.pitF,
                prepAtUtcMillis: w.prepAt,
                preheatAtUtcMillis: w.preheatAt,
                foodOnAtUtcMillis: w.foodOnAt,
                wrapAtUtcMillis: wrapAt(input, dishIndex: schedule.dishIndex, foodOnAt: w.foodOnAt),
                removeEarliestUtcMillis: w.removeEarliest,
                removeLatestUtcMillis: w.removeLatest,
                readyEarliestUtcMillis: w.readyEarliest,
                readyLatestUtcMillis: w.readyLatest,
                serveAtUtcMillis: input.serveAtUtcMillis,
                stretch: stretches[schedule.dishIndex])
        }
        var alerts: [DerivedAlert] = []
        for lane in lanes {
            alerts.append(DerivedAlert(dishIndex: lane.dishIndex, kind: .foodOn, atUtcMillis: lane.foodOnAtUtcMillis, temperatureF: nil, label: "\(lane.dishName): food on"))
            if let step = wrapStep(input, dishIndex: lane.dishIndex) {
                let at = wrapAt(input, dishIndex: lane.dishIndex, foodOnAt: lane.foodOnAtUtcMillis)
                alerts.append(DerivedAlert(dishIndex: lane.dishIndex, kind: .wrap, atUtcMillis: at, temperatureF: at == nil ? step.temperatureF : nil, label: "\(lane.dishName): wrap"))
            }
            alerts.append(DerivedAlert(dishIndex: lane.dishIndex, kind: .off, atUtcMillis: lane.removeEarliestUtcMillis, temperatureF: nil, label: "\(lane.dishName): off window opens"))
        }
        alerts.sort { ($0.atUtcMillis ?? .max, $0.dishIndex) < ($1.atUtcMillis ?? .max, $1.dishIndex) }
        var conflicts: [PlanConflict] = []
        for (i, a) in schedules.enumerated() {
            for b in schedules.dropFirst(i + 1) {
                let wa = windows[a.dishIndex]!
                let wb = windows[b.dishIndex]!
                let equipmentA = a.method.trimmingCharacters(in: .whitespaces).isEmpty ? input.smokerName : a.method
                let equipmentB = b.method.trimmingCharacters(in: .whitespaces).isEmpty ? input.smokerName : b.method
                if equipmentA.lowercased() == equipmentB.lowercased(),
                   let pa = a.pitF, let pb = b.pitF, abs(pa - pb) > pitConflictThresholdF,
                   wa.foodOnAt < wb.removeLatest && wb.foodOnAt < wa.removeLatest {
                    conflicts.append(PlanConflict(dishIndexA: a.dishIndex, dishIndexB: b.dishIndex))
                }
            }
        }
        return PlanTimelineResult(lanes: lanes, alerts: alerts, conflicts: conflicts, chosenPitF: chosenPitF)
    }

    private static func wrapStep(_ input: PlanInput, dishIndex: Int) -> PlanWrapStep? {
        input.wrapSteps.first { $0.dishIndex == dishIndex || $0.dishIndex == nil }
    }

    /// When the dish's wrap step falls, from its trigger; nil when it is not time-based.
    private static func wrapAt(_ input: PlanInput, dishIndex: Int, foodOnAt: Int64) -> Int64? {
        guard let step = wrapStep(input, dishIndex: dishIndex) else { return nil }
        switch step.trigger {
        case "elapsed": return foodOnAt + step.minutes * 60_000
        case "after_action": return step.anchor == "food_on" ? foodOnAt + step.minutes * 60_000 : nil
        case "clock": return step.clockAtUtcMillis
        default: return nil // temperature / manual: condition-based, no fixed time
        }
    }

    private static func formatTemp(_ f: Double) -> String {
        f == f.rounded() ? "\(Int64(f))°" : "\(f)°"
    }

    private static func trimRate(_ percent: Double) -> String {
        percent == percent.rounded() ? "\(Int64(percent))" : "\(percent)"
    }
}
