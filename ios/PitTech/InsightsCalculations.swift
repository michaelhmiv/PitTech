import Foundation

struct InsightCookInput: Decodable {
    let id: String
    let startedAtUtcMillis: Double
    let endedAtUtcMillis: Double?
    let timingMode: String?
}

struct InsightDishInput: Decodable {
    let id: String
    let cookId: String
    let cut: String?
    let weightValue: Double?
    let weightUnit: String?
}

struct InsightEventInput: Decodable {
    let id: String
    let cookId: String
    let dishIds: [String]
    let action: String
    let at: Double
}

struct InsightReadingInput: Decodable {
    let id: String
    let cookId: String
    let dishId: String?
    let probeId: String?
    let probeName: String
    let measurementType: String
    let value: Double?
    let unit: String
    let measuredAtUtcMillis: Double
    let source: String
    let sourceDeviceId: String?
    let qualityStatus: String
}

struct InsightResultInput: Decodable {
    let id: String
    let cookId: String
    let dishId: String?
    let resultType: String
    let numericValue: Double?
    let textValue: String?
    let recordedAtUtcMillis: Double
}

struct InsightLifecycleInput: Decodable {
    let cookId: String
    let timingMode: String
}

/// One recorded target row, carried the way Android's
/// `InsightsSnapshot.targets` now carries it (Flowing Slice 6): compare
/// runs the §4.1 pace engine for an in-progress cook from its own
/// recorded `personal_finish` target.
struct InsightTargetInput: Decodable, Equatable {
    let id: String
    let cookId: String
    let dishId: String?
    let targetType: String
    let value: Double
    let unit: String
}

struct InsightArchiveInput: Decodable {
    let cooks: [InsightCookInput]
    let dishes: [InsightDishInput]
    let events: [InsightEventInput]
    let readings: [InsightReadingInput]
    let results: [InsightResultInput]
    let lifecycles: [InsightLifecycleInput]
    /// Recorded targets (Slice 6); archives decoded from older fixtures
    /// simply carry none.
    let targets: [InsightTargetInput] = []
}

extension InsightArchiveInput {
    /// Hand-written so `targets` stays optional on the wire: golden
    /// fixtures written before Slice 6 have no targets key and must keep
    /// decoding to an empty list rather than failing.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        cooks = try container.decode([InsightCookInput].self, forKey: .cooks)
        dishes = try container.decode([InsightDishInput].self, forKey: .dishes)
        events = try container.decode([InsightEventInput].self, forKey: .events)
        readings = try container.decode([InsightReadingInput].self, forKey: .readings)
        results = try container.decode([InsightResultInput].self, forKey: .results)
        lifecycles = try container.decode([InsightLifecycleInput].self, forKey: .lifecycles)
        targets = try container.decodeIfPresent([InsightTargetInput].self, forKey: .targets) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case cooks, dishes, events, readings, results, lifecycles, targets
    }
}

struct InsightChannelMetrics: Decodable, Equatable {
    let channelId: String
    let dishId: String?
    let role: String
    let readingCount: Int
    let manualCount: Int
    let minF: Double?
    let maxF: Double?
    let units: [String]
}

struct InsightDishMetrics: Decodable, Equatable {
    let dishId: String
    let cut: String?
    let weightValue: Double?
    let weightUnit: String?
    let methods: [String]
    let foodOnAt: Double?
    let wrapAt: Double?
    let removeAt: Double?
    let restStartAt: Double?
    let holdStartAt: Double?
    let dishDoneAt: Double?
    let heatingMillis: Double?
    let restMillis: Double?
    let holdMillis: Double?
    let heatingIntervalCount: Int?
    let validReadings: Int
    let manualReadings: Int
    let excludedReadings: Int
    let rating: Double?
    let cookAgain: Bool?
    let hasResultNotes: Bool
}

struct InsightCookMetrics: Decodable, Equatable {
    let cookId: String
    let equipmentHeatingMillis: Double?
    let channels: [InsightChannelMetrics]
    let dishes: [InsightDishMetrics]
}

struct InsightReport: Decodable, Equatable {
    let calculationVersion: Int
    let cooks: [InsightCookMetrics]
}

struct InsightDishSummary {
    let dishId: String
    let cut: String?
    let weightValue: Double?
    let weightUnit: String?
    let methods: [String]
}

struct InsightReadingSummary {
    let units: [String]
    let validCount: Int
    let manualCount: Int
    let excludedCount: Int
    let roles: [String]
}

/// Deterministic dish-level calculations pinned by contracts/insights-calculations-v1.json.
/// Mirrors the Android InsightsCalculations engine rule for rule over the same golden fixtures.
enum InsightsCalculations {
    static let calculationVersion = 1
    static let epsilon = 1e-9
    private static let heatingPhases = ["cooking", "wrapped"]
    private static let temperatureUnits = ["°F", "°C"]

    static func role(_ raw: String) -> String { RecipeGuideProjection.role(raw) }

    static func channelId(cookId: String, probeId: String?, probeName: String, sourceDeviceId: String?, dishId: String?, measurementType: String, source: String) -> String {
        [cookId, probeId ?? probeName, sourceDeviceId ?? "", dishId ?? "", measurementType, source].joined(separator: ":")
    }

    static func fahrenheit(_ value: Double, unit: String) -> Double { unit == "°C" ? value * 9 / 5 + 32 : value }

    private static func isMeasured(_ row: InsightReadingInput) -> Bool {
        row.qualityStatus == "valid" && row.value?.isFinite == true && temperatureUnits.contains(row.unit)
    }

    /// Equipment-hours union ported from the shared phase rules: simultaneous dishes never double-count.
    static func unionHeatingMillis(_ phases: [NativeDishPhase]) -> Double? {
        let spans = phases.flatMap { $0.spans.filter { heatingPhases.contains($0.phase) } }.sorted { $0.start < $1.start }
        guard !spans.isEmpty else { return nil }
        var start = spans[0].start, end = spans[0].end, sum = 0.0
        for span in spans.dropFirst() {
            if span.start <= end { end = max(end, span.end) } else { sum += end - start; start = span.start; end = span.end }
        }
        return sum + end - start
    }

    static func compute(_ archive: InsightArchiveInput, asOf asOfUtcMillis: Double) -> InsightReport {
        let modes = Dictionary(uniqueKeysWithValues: archive.lifecycles.map { ($0.cookId, $0.timingMode) })
        let cooks = archive.cooks.sorted { $0.id < $1.id }.map { cook -> InsightCookMetrics in
            let end = cook.endedAtUtcMillis ?? asOfUtcMillis
            let dishes = archive.dishes.filter { $0.cookId == cook.id }.sorted { $0.id < $1.id }
            let actions = archive.events.filter { $0.cookId == cook.id }.map { CookAction(id: $0.id, action: $0.action, dishIds: $0.dishIds, at: $0.at) }
            let explicit = (modes[cook.id] ?? cook.timingMode ?? "legacy_preserved") == "explicit_phases"
            let phases = explicit && !dishes.isEmpty ? dishes.map { CookPhaseEngine.dish(id: $0.id, name: "", actions: actions, start: cook.startedAtUtcMillis, end: end) } : []
            let dishMetrics = dishes.enumerated().map { index, dish -> InsightDishMetrics in
                let scoped = actions.filter { CookPhaseEngine.actions.contains($0.action) && ($0.dishIds.isEmpty || $0.dishIds.contains(dish.id)) && $0.at <= end }
                    .sorted { $0.at == $1.at ? $0.id < $1.id : $0.at < $1.at }
                func anchor(_ action: String) -> Double? { scoped.first { $0.action == action }?.at }
                let hasDurations = explicit && index < phases.count && scoped.contains { $0.action == "food_on" }
                let rows = archive.results.filter { $0.cookId == cook.id && ($0.dishId == nil || $0.dishId == dish.id) }
                let dishRows = archive.readings.filter { $0.cookId == cook.id && $0.dishId == dish.id }
                return InsightDishMetrics(
                    dishId: dish.id, cut: dish.cut, weightValue: dish.weightValue, weightUnit: dish.weightUnit,
                    methods: rows.filter { $0.resultType == "method" }.compactMap { $0.textValue?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.sortedRemovingDuplicates(),
                    foodOnAt: anchor("food_on"), wrapAt: anchor("wrap"), removeAt: anchor("remove"),
                    restStartAt: anchor("rest_start"), holdStartAt: anchor("hold_start"), dishDoneAt: anchor("dish_done"),
                    heatingMillis: hasDurations ? phases[index].cooking : nil,
                    restMillis: hasDurations ? phases[index].rest : nil,
                    holdMillis: hasDurations ? phases[index].hold : nil,
                    heatingIntervalCount: hasDurations ? phases[index].spans.filter({ heatingPhases.contains($0.phase) }).count : nil,
                    validReadings: dishRows.filter { isMeasured($0) }.count,
                    manualReadings: dishRows.filter { isMeasured($0) && $0.source == "manual" }.count,
                    excludedReadings: dishRows.filter { !isMeasured($0) }.count,
                    rating: pickRating(rows), cookAgain: pickCookAgain(rows),
                    hasResultNotes: rows.contains { $0.resultType == "result_notes" }
                )
            }
            let channels = Dictionary(grouping: archive.readings.filter { $0.cookId == cook.id && isMeasured($0) }) {
                channelId(cookId: $0.cookId, probeId: $0.probeId, probeName: $0.probeName, sourceDeviceId: $0.sourceDeviceId, dishId: $0.dishId, measurementType: $0.measurementType, source: $0.source)
            }.map { id, rows in
                let stats = rows.compactMap { row in row.value.map { fahrenheit($0, unit: row.unit) } }
                return InsightChannelMetrics(channelId: id, dishId: rows[0].dishId, role: role(rows[0].measurementType), readingCount: rows.count, manualCount: rows.filter { $0.source == "manual" }.count, minF: stats.min(), maxF: stats.max(), units: rows.map(\.unit).sortedRemovingDuplicates())
            }.sorted { $0.channelId < $1.channelId }
            return InsightCookMetrics(cookId: cook.id, equipmentHeatingMillis: unionHeatingMillis(phases), channels: channels, dishes: dishMetrics)
        }
        return InsightReport(calculationVersion: calculationVersion, cooks: cooks)
    }

    private static func pickRating(_ rows: [InsightResultInput]) -> Double? {
        rows.filter { ["overall_rating", "overall"].contains($0.resultType) && $0.numericValue != nil }
            .sorted { a, b in
                let typeRank = { (row: InsightResultInput) in row.resultType == "overall_rating" ? 0 : 1 }
                if typeRank(a) != typeRank(b) { return typeRank(a) < typeRank(b) }
                if (a.dishId == nil) != (b.dishId == nil) { return b.dishId == nil }
                if a.recordedAtUtcMillis != b.recordedAtUtcMillis { return a.recordedAtUtcMillis > b.recordedAtUtcMillis }
                return a.id < b.id
            }.first?.numericValue
    }

    private static func pickCookAgain(_ rows: [InsightResultInput]) -> Bool? {
        let value = rows.filter { $0.resultType == "cook_again" && $0.numericValue != nil }
            .sorted { a, b in
                if (a.dishId == nil) != (b.dishId == nil) { return b.dishId == nil }
                if a.recordedAtUtcMillis != b.recordedAtUtcMillis { return a.recordedAtUtcMillis > b.recordedAtUtcMillis }
                return a.id < b.id
            }.first?.numericValue
        if value == 1.0 { return true }
        if value == 0.0 { return false }
        return nil
    }

    /// Factual current/reference difference lines for the chart comparison. No causal or doneness claims.
    static func comparisonContext(currentDish: InsightDishSummary, sourceDish: InsightDishSummary, current: InsightReadingSummary, source: InsightReadingSummary) -> [String] {
        func weight(_ dish: InsightDishSummary) -> String {
            guard let value = dish.weightValue else { return "not recorded" }
            return "\(value) \(dish.weightUnit ?? "")".trimmingCharacters(in: .whitespaces)
        }
        func readings(_ summary: InsightReadingSummary) -> String {
            let base: String
            if summary.validCount == 0 { base = "none" }
            else { base = "\(summary.validCount)" + (summary.manualCount > 0 ? " (\(summary.manualCount) manual)" : "") }
            return base + (summary.excludedCount > 0 ? ", \(summary.excludedCount) unavailable" : "")
        }
        return [
            "Methods: " + [currentDish.methods.joined(separator: ", "), sourceDish.methods.joined(separator: ", ")].map { $0.isEmpty ? "not recorded" : $0 }.joined(separator: " · "),
            "Weights: " + [weight(currentDish), weight(sourceDish)].joined(separator: " · "),
            "Reading units: " + [current.units.joined(separator: ", "), source.units.joined(separator: ", ")].map { $0.isEmpty ? "no readings" : $0 }.joined(separator: " · "),
            "Recorded readings: " + [readings(current), readings(source)].joined(separator: " · "),
            "Channels: " + [current.roles.joined(separator: ", "), source.roles.joined(separator: ", ")].map { $0.isEmpty ? "no readings" : $0 }.joined(separator: " · "),
        ]
    }
}

private extension Array where Element == String {
    func sortedRemovingDuplicates() -> [String] { Array(Set(self)).sorted() }
}

extension CookStore {
    /// Maps the local library onto the versioned insights input. Later engines consume this; slice 15 proves it with golden tests.
    func insightsArchive() -> InsightArchiveInput {
        let cooks = library.cooks.map { cook in
            InsightCookInput(id: cook.id, startedAtUtcMillis: Double(cook.startedAtUtcMillis), endedAtUtcMillis: cook.endedAtUtcMillis.map { Double($0) }, timingMode: timingMode(cook.id))
        }
        let dishes = library.dishes.compactMap { row -> InsightDishInput? in
            guard let o = row.objectValue, let id = o["id"]?.stringValue, let cookId = o["cookId"]?.stringValue else { return nil }
            return InsightDishInput(id: id, cookId: cookId, cut: o["cut"]?.stringValue?.nilIfBlank, weightValue: o["weightValue"]?.numberValue, weightUnit: o["weightUnit"]?.stringValue)
        }
        let events = library.cooks.flatMap { cook in
            library.actions(for: cook.id).map { InsightEventInput(id: $0.id, cookId: cook.id, dishIds: $0.dishIds, action: $0.action, at: $0.at) }
        }
        let readings = library.readings.compactMap(TemperatureSample.init(json:)).map {
            InsightReadingInput(id: $0.id, cookId: $0.cookId, dishId: $0.dishId, probeId: $0.probeId, probeName: $0.probeName, measurementType: $0.measurementType, value: $0.value, unit: $0.unit, measuredAtUtcMillis: $0.measuredAt.timeIntervalSince1970 * 1_000, source: $0.source, sourceDeviceId: $0.sourceDeviceId, qualityStatus: $0.qualityStatus)
        }
        let results = library.results.compactMap { row -> InsightResultInput? in
            guard let o = row.objectValue, let id = o["id"]?.stringValue, let cookId = o["cookId"]?.stringValue,
                  let type = o["resultType"]?.stringValue, let recorded = o["recordedAtUtcMillis"]?.numberValue else { return nil }
            return InsightResultInput(id: id, cookId: cookId, dishId: o["dishId"]?.stringValue, resultType: type, numericValue: o["numericValue"]?.numberValue, textValue: o["textValue"]?.stringValue, recordedAtUtcMillis: recorded)
        }
        let lifecycles = library.cooks.map { InsightLifecycleInput(cookId: $0.id, timingMode: timingMode($0.id)) }
        let targets = library.targets.compactMap { row -> InsightTargetInput? in
            guard let o = row.objectValue, let id = o["id"]?.stringValue, let cookId = o["cookId"]?.stringValue,
                  let type = o["targetType"]?.stringValue, let value = o["value"]?.numberValue, let unit = o["unit"]?.stringValue else { return nil }
            return InsightTargetInput(id: id, cookId: cookId, dishId: o["dishId"]?.stringValue, targetType: type, value: value, unit: unit)
        }
        return InsightArchiveInput(cooks: cooks, dishes: dishes, events: events, readings: readings, results: results, lifecycles: lifecycles, targets: targets)
    }
}

private extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
