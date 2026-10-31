import Foundation

/// The Live board's merged guidance (Flowing spec §5, Spread 01). Pure
/// presentation logic over the pace engine's outputs — the mirror of
/// Android's LiveGuidance, rule for rule. A range is stated only with its
/// generated basis; where the engine withholds a range, the surface shows
/// the engine's reason — never a fabricated time. Slice 5 wires the
/// serve plan in: a started plan's window reaches PlanOnly cooks through
/// `planWindow` (the reason alone remains the no-plan case).

struct LiveVerdict: Equatable {
    let headline: String
    let detail: String?
}

/// The ETA row's honest shapes (plus silence).
enum LiveEta: Equatable {
    case ranged(text: String, basis: String)
    case plan(text: String, basis: String)
    case withheld(reason: String)
    case none

    var detail: String? {
        switch self {
        case .ranged(let text, let basis), .plan(let text, let basis): return "\(text) · \(basis)"
        case .withheld(let reason): return "No finish range yet — \(reason)"
        case .none: return nil
        }
    }
}

enum LiveGuidance {

    /// Priority is the spouse test in order: something that needs the cook
    /// beats everything; the stall next; then the next dated obligation;
    /// then plain pace. iOS passes needsAttention 0 until its own alert
    /// system exists (#183) — the ordering is ready for it.
    static func verdict(
        needsAttention: Int,
        stall: StallState,
        finish: FinishEstimate,
        nextReminder: (title: String, dueUtcMillis: Int64)?,
        nowUtcMillis: Int64,
        eta: LiveEta,
        formatTime: (Int64) -> String,
        formatDuration: (Double) -> String
    ) -> LiveVerdict {
        if needsAttention > 0 {
            return LiveVerdict(
                headline: needsAttention == 1 ? "An alert needs attention." : "\(needsAttention) alerts need attention.",
                detail: "See the Alerts panel below — acknowledging it there quiets it here.")
        }
        if case .inStall(let since, let elapsed, let average) = stall {
            let averageText = average.map { " · your average stall for this cut is \(formatDuration($0))" } ?? ""
            return LiveVerdict(
                headline: "In the stall — normal. Nothing needs doing.",
                detail: "In the stall since \(formatTime(since)) · \(formatDuration(elapsed)) in\(averageText).")
        }
        if finish == .finished {
            return LiveVerdict(headline: "At target — ready when you are.", detail: nil)
        }
        if let nextReminder, (0...(45 * 60_000)).contains(nextReminder.dueUtcMillis - nowUtcMillis) {
            return LiveVerdict(headline: "Next: \(nextReminder.title) at \(formatTime(nextReminder.dueUtcMillis)).", detail: eta.detail)
        }
        switch eta {
        case .ranged: return LiveVerdict(headline: "On pace — nothing needs doing.", detail: eta.detail)
        case .plan, .withheld: return LiveVerdict(headline: "Nothing needs doing.", detail: eta.detail)
        case .none: return LiveVerdict(headline: "Nothing needs doing.", detail: nil)
        }
    }

    /// The ETA row's content: a stated range, a plan window, or the honest reason there is neither.
    static func eta(finish: FinishEstimate, planWindow: (Int64, Int64)?, formatRange: (Int64, Int64) -> String) -> LiveEta {
        switch finish {
        case .ranged(let range):
            return .ranged(text: "Done \(formatRange(range.earliestUtcMillis, range.latestUtcMillis))", basis: range.basis.displayText)
        case .planOnly(let reason):
            if let planWindow {
                return .plan(text: "Planning window \(formatRange(planWindow.0, planWindow.1))", basis: PaceBasis.plan.displayText)
            }
            return .withheld(reason: reason)
        case .finished:
            return .ranged(text: "At target", basis: PaceBasis.finished.displayText)
        }
    }

    /// Same-protein history for one live dish: only finished cooks, only
    /// dishes whose cut matches (normalized), and a past dish contributes
    /// a target only from its own recorded final temperature — the same
    /// rule as Android.
    static func historyFor(archive: InsightArchiveInput, currentCookId: String, currentDishId: String, cut: String?) -> PaceHistory {
        guard let wanted = cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !wanted.isEmpty else { return .none }
        let pastCooks = archive.cooks.filter { $0.id != currentCookId && $0.endedAtUtcMillis != nil }
        guard !pastCooks.isEmpty else { return .none }
        let pastIds = Set(pastCooks.map(\.id))
        let dishes = archive.dishes.filter { pastIds.contains($0.cookId) && $0.id != currentDishId &&
            $0.cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == wanted }
        guard !dishes.isEmpty else { return .none }
        var targets: [String: Double] = [:]
        for dish in dishes {
            let recorded = archive.results.first { $0.cookId == dish.cookId && $0.dishId == dish.id && $0.resultType == "final_temperature" }?.numericValue
                ?? archive.results.first { $0.cookId == dish.cookId && $0.dishId == nil && $0.resultType == "final_temperature" }?.numericValue
            if let recorded, recorded.isFinite, recorded > 0 { targets["\(dish.cookId)/\(dish.id)"] = recorded }
        }
        let kept = dishes.filter { targets["\($0.cookId)/\($0.id)"] != nil }
        guard !kept.isEmpty else { return .none }
        let keptCookIds = Set(kept.map(\.cookId))
        let filtered = InsightArchiveInput(
            cooks: pastCooks.filter { keptCookIds.contains($0.id) },
            dishes: kept,
            events: archive.events.filter { keptCookIds.contains($0.cookId) },
            readings: archive.readings.filter { keptCookIds.contains($0.cookId) },
            results: archive.results.filter { keptCookIds.contains($0.cookId) },
            lifecycles: archive.lifecycles.filter { keptCookIds.contains($0.cookId) },
            targets: archive.targets.filter { keptCookIds.contains($0.cookId) })
        return CookPaceEngine.history(archives: [filtered], targetFByDish: targets)
    }

    /// The most recent finished cook of the same cut — the chart's ghost.
    static func ghostCook(archive: InsightArchiveInput, currentCookId: String, cut: String?) -> InsightCookInput? {
        guard let wanted = cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !wanted.isEmpty else { return nil }
        let dishCookIds = Set(archive.dishes.filter { $0.cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == wanted }.map(\.cookId))
        return archive.cooks
            .filter { $0.id != currentCookId && $0.endedAtUtcMillis != nil && dishCookIds.contains($0.id) }
            .max { ($0.endedAtUtcMillis ?? 0) < ($1.endedAtUtcMillis ?? 0) }
    }

    /// Minutes the cook is ahead of the ghost at the cursor, both series
    /// elapsed-aligned to their own food-on. Positive: the ghost needed
    /// longer to reach the temperature the current cook shows at the
    /// cursor. Nil whenever either series can't answer.
    static func ghostDeltaMinutes(current: [PaceReading], ghost: [PaceReading], cursorElapsedMinutes: Double) -> Int? {
        guard let temp = interpolate(current, elapsedMinutes: cursorElapsedMinutes),
              let ghostAt = elapsedAtTemp(ghost, tempF: temp) else { return nil }
        let delta = ghostAt - cursorElapsedMinutes
        if abs(delta) < 1.0 { return 0 }
        return Int(delta.rounded())
    }

    /// Piecewise-linear value of an elapsed-minutes series; nil outside its span.
    static func interpolate(_ series: [PaceReading], elapsedMinutes: Double) -> Double? {
        guard series.count >= 2 else { return nil }
        let sorted = series.sorted { $0.atUtcMillis < $1.atUtcMillis }
        let at = elapsedMinutes * 60_000.0
        guard at >= Double(sorted.first!.atUtcMillis), at <= Double(sorted.last!.atUtcMillis) else { return nil }
        guard let upper = sorted.first(where: { Double($0.atUtcMillis) >= at }),
              let lower = sorted.last(where: { Double($0.atUtcMillis) <= at }) else { return nil }
        if upper.atUtcMillis == lower.atUtcMillis { return upper.fahrenheit }
        let fraction = (at - Double(lower.atUtcMillis)) / Double(upper.atUtcMillis - lower.atUtcMillis)
        return lower.fahrenheit + fraction * (upper.fahrenheit - lower.fahrenheit)
    }

    /// First elapsed time (minutes) at which a climbing series reaches [tempF]; nil when it never does.
    static func elapsedAtTemp(_ series: [PaceReading], tempF: Double) -> Double? {
        let sorted = series.sorted { $0.atUtcMillis < $1.atUtcMillis }
        var previous: PaceReading?
        for point in sorted {
            if let prev = previous, prev.fahrenheit <= tempF, point.fahrenheit >= tempF, point.atUtcMillis > prev.atUtcMillis {
                let fraction = (tempF - prev.fahrenheit) / (point.fahrenheit - prev.fahrenheit)
                return (Double(prev.atUtcMillis) + fraction * Double(point.atUtcMillis - prev.atUtcMillis)) / 60_000.0
            }
            if point.fahrenheit == tempF { return Double(point.atUtcMillis) / 60_000.0 }
            previous = point
        }
        return nil
    }
}
