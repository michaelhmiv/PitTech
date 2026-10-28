import Foundation

/// One food-probe observation, already normalized to degrees Fahrenheit.
struct PaceReading: Equatable {
    let atUtcMillis: Int64
    let fahrenheit: Double
}

/// The cook's own history for one protein/cut, precomputed by
/// `CookPaceEngine.history` from past archives. Empty lists mean "no usable
/// history" — the engine narrows what it is willing to say rather than
/// inventing numbers.
struct PaceHistory: Equatable {
    var totalDurationsMinutes: [Double] = []
    var stallDurationsMinutes: [Double] = []
    var postStallSlopesFPerMinute: [Double] = []
    static let none = PaceHistory()
}

/// Why a finish range reads the way it does. Every range carries one.
enum PaceBasis: String, Equatable {
    case pace
    case history
    case stall
    case plan
    case finished

    var displayText: String {
        switch self {
        case .pace: return "from your last 30 min of readings"
        case .history: return "from your past cooks — the stall is still ahead"
        case .stall: return "in the stall — widened from your past stalls"
        case .plan: return "from your plan — not enough readings yet"
        case .finished: return "done"
        }
    }
}

struct FinishRange: Equatable {
    let earliestUtcMillis: Int64
    let latestUtcMillis: Int64
    let basis: PaceBasis
    var widthMinutes: Double { Double(latestUtcMillis - earliestUtcMillis) / 60_000.0 }
}

enum FinishEstimate: Equatable {
    case ranged(FinishRange)
    case planOnly(String)
    case finished
}

enum StallState: Equatable {
    /// Not enough probe evidence to judge — never report this as "no stall".
    case unknown
    case notInStall
    case inStall(sinceUtcMillis: Int64, elapsedMinutes: Double, historicalAverageStallMinutes: Double?)
}

struct StallSpan: Equatable {
    let startUtcMillis: Int64
    let endUtcMillis: Int64
    var durationMinutes: Double { Double(endUtcMillis - startUtcMillis) / 60_000.0 }
}

struct PaceAssessment: Equatable {
    let finish: FinishEstimate
    let stall: StallState
}

/// Named thresholds for the pace & stall engine (Flowing spec §4.1), tuned
/// against `contracts/fixtures/pace-stall-corpus-v1.json` — see
/// docs/flowing/slice-1-backtest-report.md.
enum PaceConstants {
    static let stallBandLowF = 150.0
    static let stallBandHighF = 170.0
    static let slopeWindowMinutes: Int64 = 30
    static let stallSlopeFloorFPerMinute = 0.04
    static let stallSustainMinutes: Int64 = 30
    static let stallStartBackdateMinutes: Int64 = 15
    static let maxReadingGapMinutes: Int64 = 20
    static let paceMinReadings = 4
    static let paceMinSpanMinutes = 20.0
    static let paceMinSlopeFPerMinute = 0.05
    static let paceUncertaintyFast = 1.6
    static let paceUncertaintySlow = 0.55
    static let paceMinWidthMinutes: Int64 = 30
    static let historyEnvelopePadMinutes = 30.0
    static let stallRemainingFloorMinutes = 30.0
    static let smootherCadenceMinutes: Int64 = 10
    static let smootherMaxEdgeStepMinutes: Int64 = 15
}

/// The pace & stall engine (Flowing spec §4.1). Pure functions over probe
/// readings — any source (controller, BLE, hand-logged) is just readings.
/// It states a finish RANGE with a basis, or declines to state one; it never
/// emits a point estimate and never fabricates pace from sparse data.
/// Mirrors Android's CookPaceEngine rule for rule.
enum CookPaceEngine {

    /// Least-squares slope (°F/min) of a time-ordered series; nil when undefined.
    static func slopePerMinute(_ series: [PaceReading]) -> Double? {
        guard series.count >= 2 else { return nil }
        let t0 = series[0].atUtcMillis
        let xs = series.map { Double($0.atUtcMillis - t0) / 60_000.0 }
        let ys = series.map { $0.fahrenheit }
        let mx = xs.reduce(0, +) / Double(xs.count)
        let my = ys.reduce(0, +) / Double(ys.count)
        var num = 0.0
        var den = 0.0
        for i in xs.indices { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        return den == 0 ? nil : num / den
    }

    private static func gapsOk(_ series: [PaceReading]) -> Bool {
        zip(series, series.dropFirst()).allSatisfy { $0.1.atUtcMillis - $0.0.atUtcMillis <= PaceConstants.maxReadingGapMinutes * 60_000 }
    }

    /// Per-reading "flat now" flags: trailing-window slope at/below the floor, in band, no gap.
    private static func flatFlags(_ series: [PaceReading]) -> [Bool] {
        series.indices.map { i in
            let t = series[i].atUtcMillis
            let window = series.prefix(i + 1).filter { $0.atUtcMillis >= t - PaceConstants.slopeWindowMinutes * 60_000 }
            guard let slope = slopePerMinute(window) else { return false }
            return gapsOk(window) && slope <= PaceConstants.stallSlopeFloorFPerMinute &&
                (PaceConstants.stallBandLowF...PaceConstants.stallBandHighF).contains(series[i].fahrenheit)
        }
    }

    /// All stall spans in a (complete or partial) probe series, in order.
    static func stallSpans(_ series: [PaceReading]) -> [StallSpan] {
        guard series.count >= 2 else { return [] }
        let flat = flatFlags(series)
        var spans: [StallSpan] = []
        var i = 0
        while i < series.count {
            if flat[i] {
                var j = i
                while j + 1 < series.count && flat[j + 1] { j += 1 }
                if (series[j].atUtcMillis - series[i].atUtcMillis) / 60_000 >= PaceConstants.stallSustainMinutes {
                    spans.append(StallSpan(
                        startUtcMillis: series[i].atUtcMillis - PaceConstants.stallStartBackdateMinutes * 60_000,
                        endUtcMillis: series[j].atUtcMillis))
                }
                i = j + 1
            } else { i += 1 }
        }
        return spans
    }

    /// Whether the series' tail is currently inside a declared stall, and since when.
    private static func liveStallStart(_ series: [PaceReading], flat: [Bool]) -> Int64? {
        guard let last = series.last, flat.last == true else { return nil }
        var i = series.count - 1
        while i - 1 >= 0 && flat[i - 1] { i -= 1 }
        if (last.atUtcMillis - series[i].atUtcMillis) / 60_000 < PaceConstants.stallSustainMinutes { return nil }
        return series[i].atUtcMillis - PaceConstants.stallStartBackdateMinutes * 60_000
    }

    private static func assessable(_ series: [PaceReading]) -> Bool {
        guard let first = series.first, let last = series.last else { return false }
        return series.count >= PaceConstants.paceMinReadings &&
            Double(last.atUtcMillis - first.atUtcMillis) / 60_000.0 >= PaceConstants.paceMinSpanMinutes
    }

    static func stallState(_ series: [PaceReading], nowUtcMillis: Int64, history: PaceHistory) -> StallState {
        let flat = series.count >= 2 ? flatFlags(series) : []
        if !flat.isEmpty, let since = liveStallStart(series, flat: flat) {
            return .inStall(
                sinceUtcMillis: since,
                elapsedMinutes: Double(nowUtcMillis - since) / 60_000.0,
                historicalAverageStallMinutes: history.stallDurationsMinutes.isEmpty
                    ? nil : history.stallDurationsMinutes.reduce(0, +) / Double(history.stallDurationsMinutes.count))
        }
        return assessable(series) ? .notInStall : .unknown
    }

    private static func historyEnvelope(foodOnUtcMillis: Int64, history: PaceHistory) -> (Int64, Int64)? {
        guard let lo = history.totalDurationsMinutes.min(), let hi = history.totalDurationsMinutes.max() else { return nil }
        return (foodOnUtcMillis + Int64((lo - PaceConstants.historyEnvelopePadMinutes) * 60_000.0),
                foodOnUtcMillis + Int64((hi + PaceConstants.historyEnvelopePadMinutes) * 60_000.0))
    }

    /// Assess one dish's cook at `nowUtcMillis`. `readings` are that dish's
    /// food-probe readings (any source); later readings are ignored.
    static func assess(
        readings: [PaceReading],
        targetF: Double,
        nowUtcMillis: Int64,
        foodOnUtcMillis: Int64,
        history: PaceHistory = .none
    ) -> PaceAssessment {
        let series = readings.filter { $0.atUtcMillis <= nowUtcMillis }.sorted { $0.atUtcMillis < $1.atUtcMillis }
        let stall = stallState(series, nowUtcMillis: nowUtcMillis, history: history)
        guard let last = series.last else {
            return PaceAssessment(finish: .planOnly("no readings yet"), stall: stall)
        }
        if last.fahrenheit >= targetF { return PaceAssessment(finish: .finished, stall: stall) }

        let envelope = historyEnvelope(foodOnUtcMillis: foodOnUtcMillis, history: history)

        if case .inStall(_, let elapsed, _) = stall {
            // Never extrapolate the stall's own slope: remaining-stall
            // allowance plus the post-stall climb at historical rates,
            // unioned with the history envelope so a stall can widen the
            // finish, never slide it early.
            guard let fastest = history.postStallSlopesFPerMinute.max(),
                  let slowest = history.postStallSlopesFPerMinute.min(),
                  let longest = history.stallDurationsMinutes.max(),
                  let envelope else {
                return PaceAssessment(finish: .planOnly("in the stall — not enough of your history to widen the range"), stall: stall)
            }
            let delta = targetF - last.fahrenheit
            let remainingLatest = max(longest - elapsed, PaceConstants.stallRemainingFloorMinutes)
            let earliest = nowUtcMillis + Int64(delta / fastest * 60_000.0)
            let latest = nowUtcMillis + Int64((remainingLatest + delta / slowest) * 60_000.0)
            return PaceAssessment(
                finish: .ranged(FinishRange(earliestUtcMillis: min(earliest, envelope.0), latestUtcMillis: max(latest, envelope.1), basis: .stall)),
                stall: stall)
        }

        let window = series.filter { $0.atUtcMillis >= nowUtcMillis - PaceConstants.slopeWindowMinutes * 60_000 }
        let spanMinutes = window.count >= 2 ? Double(window.last!.atUtcMillis - window.first!.atUtcMillis) / 60_000.0 : 0.0
        guard window.count >= PaceConstants.paceMinReadings, spanMinutes >= PaceConstants.paceMinSpanMinutes, gapsOk(window) else {
            return PaceAssessment(finish: .planOnly("not enough readings yet"), stall: stall)
        }
        guard let slope = slopePerMinute(window), slope >= PaceConstants.paceMinSlopeFPerMinute else {
            return PaceAssessment(finish: .planOnly("the probe is not climbing — no pace to state"), stall: stall)
        }
        let delta = targetF - last.fahrenheit
        var earliest = nowUtcMillis + Int64(delta / (slope * PaceConstants.paceUncertaintyFast) * 60_000.0)
        var latest = nowUtcMillis + Int64(delta / (slope * PaceConstants.paceUncertaintySlow) * 60_000.0)
        if latest - earliest < PaceConstants.paceMinWidthMinutes * 60_000 {
            let mid = (earliest + latest) / 2
            earliest = mid - PaceConstants.paceMinWidthMinutes * 30_000
            latest = mid + PaceConstants.paceMinWidthMinutes * 30_000
        }
        // Until this cook has cleared the stall band (or finished a stall),
        // the stall is still ahead: the history envelope widens the range.
        let spans = stallSpans(series)
        let cleared = last.fahrenheit > PaceConstants.stallBandHighF ||
            (spans.last.map { $0.endUtcMillis < last.atUtcMillis } ?? false)
        if let envelope, !cleared {
            let widened = FinishRange(
                earliestUtcMillis: min(earliest, envelope.0),
                latestUtcMillis: max(latest, envelope.1),
                basis: (envelope.0 < earliest || envelope.1 > latest) ? .history : .pace)
            return PaceAssessment(finish: .ranged(widened), stall: stall)
        }
        return PaceAssessment(finish: .ranged(FinishRange(earliestUtcMillis: earliest, latestUtcMillis: latest, basis: .pace)), stall: stall)
    }

    /// Derive `PaceHistory` from past archives (already filtered by the
    /// caller to the same protein/cut). `targetFByDish` keys are
    /// "cookId/dishId". Mirrors the Android builder rule for rule.
    static func history(archives: [InsightArchiveInput], targetFByDish: [String: Double]) -> PaceHistory {
        var totals: [Double] = []
        var stalls: [Double] = []
        var postSlopes: [Double] = []
        for archive in archives {
            for dish in archive.dishes {
                guard let target = targetFByDish["\(dish.cookId)/\(dish.id)"] else { continue }
                let series = archive.readings
                    .filter { $0.cookId == dish.cookId && ($0.dishId == dish.id || $0.dishId == nil) &&
                        InsightsCalculations.role($0.measurementType) == "food" &&
                        $0.qualityStatus == "valid" && $0.value != nil && $0.value!.isFinite }
                    .map { PaceReading(atUtcMillis: Int64($0.measuredAtUtcMillis), fahrenheit: InsightsCalculations.fahrenheit($0.value!, unit: $0.unit)) }
                    .sorted { $0.atUtcMillis < $1.atUtcMillis }
                guard series.count >= 2 else { continue }
                let foodOn = archive.events
                    .filter { $0.cookId == dish.cookId && $0.action == "food_on" && ($0.dishIds.isEmpty || $0.dishIds.contains(dish.id)) }
                    .map { Int64($0.at) }.min() ?? archive.cooks.first { $0.id == dish.cookId }.map { Int64($0.startedAtUtcMillis) }
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
                let scopedToDish: (InsightEventInput) -> Bool = { event in
                    event.dishIds.contains(dish.id) ||
                        (event.dishIds.isEmpty && archive.dishes.filter { $0.cookId == dish.cookId }.count == 1)
                }
                let finishEvent = ["remove", "dish_done"].lazy.compactMap { action in
                    archive.events.filter { $0.cookId == dish.cookId && $0.action == action && scopedToDish($0) }.map { Int64($0.at) }.min()
                }.first
                guard let foodOn, let finish = finishEvent ?? series.first(where: { $0.fahrenheit >= target })?.atUtcMillis else { continue }
                totals.append(Double(finish - foodOn) / 60_000.0)
                for span in stallSpans(series.filter { $0.atUtcMillis <= finish }) {
                    stalls.append(span.durationMinutes)
                    let climb = series.filter { $0.atUtcMillis >= span.endUtcMillis && $0.atUtcMillis <= finish }
                    if let slope = slopePerMinute(climb), slope > 0 { postSlopes.append(slope) }
                }
            }
        }
        return PaceHistory(totalDurationsMinutes: totals, stallDurationsMinutes: stalls, postStallSlopesFPerMinute: postSlopes)
    }
}

/// Anti-jitter publication for stated ranges (spec §4.1): a published range
/// updates at most once per cadence, and neither edge moves more than the
/// max step per publication. One smoother per dish per cook.
final class PaceRangeSmoother {
    private var published: FinishRange?
    private var publishedAtUtcMillis: Int64 = .min

    func publish(_ candidate: FinishRange?, nowUtcMillis: Int64) -> FinishRange? {
        guard let candidate else { return nil }
        guard let current = published else {
            published = candidate
            publishedAtUtcMillis = nowUtcMillis
            return candidate
        }
        if nowUtcMillis - publishedAtUtcMillis < PaceConstants.smootherCadenceMinutes * 60_000 { return current }
        let step = PaceConstants.smootherMaxEdgeStepMinutes * 60_000
        func clamp(_ next: Int64, _ prev: Int64) -> Int64 { prev + max(-step, min(step, next - prev)) }
        let out = FinishRange(
            earliestUtcMillis: clamp(candidate.earliestUtcMillis, current.earliestUtcMillis),
            latestUtcMillis: clamp(candidate.latestUtcMillis, current.latestUtcMillis),
            basis: candidate.basis)
        published = out
        publishedAtUtcMillis = nowUtcMillis
        return out
    }

    func reset() {
        published = nil
        publishedAtUtcMillis = .min
    }
}
