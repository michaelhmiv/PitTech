import Foundation

// MARK: - Slice 18 personal analysis: local questions over v1 calculations

/// Pure content builders for the Personal analysis Pro view. Every figure comes
/// from the PR 15 v1 engine (`InsightReport`, `calculationVersion` 1) over the
/// user's LOCAL library plus the slice-16 cached verified entitlement: no
/// publishing, no account session beyond entitlement, no network. Missing
/// anchors, weights, and ratings stay missing — never invented, never
/// interpolated. Bests follow the user's declared rating, never an opaque
/// score. Android `domain/ProPersonalAnalysis.kt` pins the same strings.
enum ProPersonalAnalysis {
    // MARK: - Shared formatting (mirrors the benchmark card rules)

    /// "8h", "7h 30m", "50m". Whole minutes, rounded.
    static func formatDuration(_ millis: Double) -> String {
        let totalMinutes = Int((millis / 60000).rounded())
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        if hours > 0, minutes > 0 { return "\(hours)h \(minutes)m" }
        if hours > 0 { return "\(hours)h" }
        return "\(minutes)m"
    }

    /// Elapsed anchor offsets: "+3:20", "+1:00", "-2:20".
    static func formatElapsed(_ millis: Double) -> String {
        let sign = millis < 0 ? "-" : "+"
        let totalMinutes = Int((abs(millis) / 60000).rounded())
        return String(format: "%@%d:%02d", sign, totalMinutes / 60, totalMinutes % 60)
    }

    /// Declared ratings only: "5 / 5", "4.5 / 5". Never a computed score.
    static func formatRating(_ value: Double) -> String {
        let whole = value.rounded()
        let text = abs(value - whole) < 1e-9 ? String(Int(whole)) : String(value)
        return "\(text) / 5"
    }

    /// Normalized °F values: integral when whole, one decimal otherwise.
    static func formatTempF(_ value: Double) -> String {
        let whole = value.rounded()
        if abs(value - whole) < 1e-9 { return "\(Int(whole)) °F" }
        return String(format: "%.1f °F", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    static func formatWeight(value: Double, unit: String?) -> String {
        let whole = value.rounded()
        let text = abs(value - whole) < 1e-9 ? String(Int(whole)) : String(value)
        guard let unit, !unit.isEmpty else { return text }
        return "\(text) \(unit)"
    }

    // MARK: - Snapshot

    enum LibraryState: Equatable, Sendable {
        case empty
        case sparse
        case full
    }

    struct CutGroup: Equatable, Sendable {
        let cut: String
        let header: String
        let lines: [String]
        let coverage: String
    }

    struct Best: Equatable, Sendable {
        let lines: [String]
        let notes: [String]
        let coverage: String
    }

    struct Timing: Equatable, Sendable {
        let lines: [String]
        let coverage: String
    }

    struct Methods: Equatable, Sendable {
        let lines: [String]
        let coverage: String
    }

    struct Snapshot: Equatable, Sendable {
        let state: LibraryState
        let cookCount: Int
        let dishCount: Int
        let cutGroups: [CutGroup]
        let best: Best?
        let timing: Timing?
        let methods: Methods?
        let emptyTitle: String?
        let emptyBody: String?
        /// Shown in place of cut comparisons for a one-cook library.
        let comparisonNote: String?
    }

    struct DishRow: Equatable, Sendable {
        let cookId: String
        let title: String
        let cut: String
        let metrics: InsightDishMetrics
        let cookUnits: [String]
    }

    static let emptyTitle = "No recorded cooks yet"
    static let emptyBody = "Personal analysis appears here as you record cooks on this device. Your first finished cook unlocks bests, timing groups, and wrap notes."
    static let sparseComparisonNote = "Record a second cook to compare cuts side by side."
    static let sparseBody = "Comparisons need at least two recorded cooks. What you recorded so far is below; timing groups and wrap notes grow as you cook."

    static func snapshot(report: InsightReport, titles: [String: String]) -> Snapshot {
        precondition(report.calculationVersion == InsightsCalculations.calculationVersion)
        let rows = dishRows(report: report, titles: titles)
        let cookCount = report.cooks.count
        guard !rows.isEmpty else {
            return Snapshot(
                state: .empty, cookCount: cookCount, dishCount: 0,
                cutGroups: [], best: nil, timing: nil, methods: nil,
                emptyTitle: emptyTitle, emptyBody: emptyBody, comparisonNote: nil
            )
        }
        let groups = cutGroups(rows: rows)
        return Snapshot(
            state: cookCount < 2 ? .sparse : .full,
            cookCount: cookCount,
            dishCount: rows.count,
            cutGroups: groups,
            best: best(rows: rows),
            timing: timing(rows: rows),
            methods: methods(rows: rows),
            emptyTitle: nil,
            emptyBody: cookCount < 2 ? sparseBody : nil,
            comparisonNote: cookCount < 2 ? sparseComparisonNote : nil
        )
    }

    static func dishRows(report: InsightReport, titles: [String: String]) -> [DishRow] {
        report.cooks.flatMap { cook in
            cook.dishes.map { dish in
                let cut = dish.cut?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let units = cook.channels.flatMap(\.units).sortedRemovingDuplicates()
                return DishRow(
                    cookId: cook.cookId,
                    title: titles[cook.cookId] ?? cook.cookId,
                    cut: cut.isEmpty ? "Cut not recorded" : cut,
                    metrics: dish,
                    cookUnits: units
                )
            }
        }
    }

    // MARK: - Cut comparisons

    static func cutGroups(rows: [DishRow]) -> [CutGroup] {
        let ordered = rows.stableGrouped(by: \.cut).sorted { $0.key < $1.key }
        return ordered.map { cut, group in
            let lines = group.map { row -> String in
                let heating = positive(row.metrics.heatingMillis).map { "heating \(formatDuration($0))" } ?? "heating not recorded"
                let weight = row.metrics.weightValue.map { formatWeight(value: $0, unit: row.metrics.weightUnit) } ?? "weight not recorded"
                let method = row.metrics.methods.isEmpty ? "method not recorded" : row.metrics.methods.joined(separator: ", ")
                let readings = row.cookUnits.isEmpty ? "no recorded readings" : "\(row.cookUnits.joined(separator: ", ")) readings"
                return "\(row.title): \(heating) · \(weight) · \(method) · \(readings)"
            }
            let heated = group.filter { positive($0.metrics.heatingMillis) != nil }.count
            let weighed = group.filter { $0.metrics.weightValue != nil }.count
            return CutGroup(
                cut: cut,
                header: "\(cut) · \(group.count) recorded \(group.count == 1 ? "cook" : "cooks")",
                lines: lines,
                coverage: "Heating time recorded for \(heated) of \(group.count) dishes · weights recorded for \(weighed) of \(group.count) dishes"
            )
        }
    }

    // MARK: - Personal best (declared rating only)

    static func best(rows: [DishRow]) -> Best {
        let rated = rows.filter { $0.metrics.rating != nil }
        let coverage = "\(rated.count) of \(rows.count) dishes have a recorded rating"
        guard let winner = rated.sorted(by: { ($0.metrics.rating ?? 0) > ($1.metrics.rating ?? 0) }).first,
              let rating = winner.metrics.rating
        else {
            return Best(
                lines: ["No recorded ratings yet"],
                notes: ["Rate a finished cook to see your best-rated attempt here."],
                coverage: coverage
            )
        }
        var lines = [
            "\(winner.title) — \(winner.cut)",
            "You rated this \(formatRating(rating))",
        ]
        switch winner.metrics.cookAgain {
        case .some(true): lines.append("Cook again: Yes")
        case .some(false): lines.append("Cook again: No")
        case .none: lines.append("Cook-again choice not recorded")
        }
        if winner.metrics.hasResultNotes { lines.append("Includes your result note") }
        return Best(lines: lines, notes: [], coverage: coverage)
    }

    // MARK: - Timing across cuts and weights

    static func timing(rows: [DishRow]) -> Timing {
        let ordered = rows.stableGrouped(by: \.cut).sorted { $0.key < $1.key }
        let lines = ordered.map { cut, group -> String in
            let values = group.compactMap { positive($0.metrics.heatingMillis) }.sorted()
            if group.count == 1, let single = values.first {
                return "\(cut): \(formatDuration(single)) across 1 recorded cook"
            }
            if group.count == 1 {
                return "\(cut): heating not recorded · 1 recorded cook"
            }
            guard !values.isEmpty else {
                return "\(cut): heating not recorded · \(group.count) recorded cooks"
            }
            let median: Double = values.count % 2 == 1
                ? values[values.count / 2]
                : (values[values.count / 2 - 1] + values[values.count / 2]) / 2
            var line = "\(cut): typical (median) \(formatDuration(median)) across \(group.count) recorded cooks · range \(formatDuration(values.first!)) – \(formatDuration(values.last!))"
            if values.count < group.count {
                line += " · heating recorded for \(values.count) of \(group.count)"
            }
            return line
        }
        let heated = rows.filter { positive($0.metrics.heatingMillis) != nil }.count
        let weighed = rows.filter { $0.metrics.weightValue != nil }.count
        return Timing(
            lines: lines,
            coverage: "Heating time recorded for \(heated) of \(rows.count) dishes · weights recorded for \(weighed) of \(rows.count) dishes"
        )
    }

    // MARK: - Wrap and rest differences

    static func methods(rows: [DishRow]) -> Methods {
        let lines = rows.map { row -> String in
            let wrap: String
            switch (row.metrics.wrapAt, row.metrics.foodOnAt) {
            case let (wrap?, foodOn?):
                wrap = "wrapped \(formatElapsed(wrap - foodOn)) after Food on"
            case (.some, .none):
                wrap = "wrapped (Food on not recorded)"
            case (.none, _):
                wrap = "wrap not recorded"
            }
            let rest = positive(row.metrics.restMillis).map { "rested \(formatDuration($0))" } ?? "rest not recorded"
            let hold = positive(row.metrics.holdMillis).map { "held \(formatDuration($0))" } ?? "holding not recorded"
            return "\(row.title): \(wrap) · \(rest) · \(hold)"
        }
        let wrapped = rows.filter { $0.metrics.wrapAt != nil }.count
        let rested = rows.filter { positive($0.metrics.restMillis) != nil }.count
        let held = rows.filter { positive($0.metrics.holdMillis) != nil }.count
        return Methods(
            lines: lines,
            coverage: "Wrap recorded for \(wrapped) of \(rows.count) dishes · rest recorded for \(rested) of \(rows.count) dishes · holding recorded for \(held) of \(rows.count) dishes"
        )
    }

    /// v1 reports 0 (not null) for a measured phase with no intervals; a zero
    /// span means nothing was recorded there, so it stays out of figures.
    static func positive(_ millis: Double?) -> Double? {
        guard let millis, millis > 0 else { return nil }
    }
}

private extension [ProPersonalAnalysis.DishRow] {
    func stableGrouped(by key: KeyPath<Element, String>) -> [(key: String, rows: [Element])] {
        var order: [String] = []
        var groups: [String: [Element]] = [:]
        for row in self {
            let name = row[keyPath: key]
            if groups[name] == nil { order.append(name); groups[name] = [] }
            groups[name]?.append(row)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }
}

private extension Array where Element == String {
    func sortedRemovingDuplicates() -> [String] { Array(Set(self)).sorted() }
}

// MARK: - Advanced curves: explicit alignment over measured sources

/// Aligned curve tables for the Advanced curves Pro view. The user picks the
/// anchor (Food on or Wrap) and which measured channels to compare; points are
/// recorded readings only, normalized to °F so °C and °F cooks compare
/// directly. Channels whose cook lacks the anchor are excluded with a note —
/// never estimated.
enum ProAdvancedCurves {
    enum Anchor: String, Sendable, CaseIterable {
        case foodOn
        case wrap

        var title: String {
            switch self {
            case .foodOn: return "Food on"
            case .wrap: return "Wrap"
            }
        }
    }

    struct ChannelOption: Equatable, Sendable {
        let channelId: String
        let label: String
        let testTag: String
        let readingCount: Int
    }

    struct AlignedPoint: Equatable, Sendable {
        let elapsedMillis: Double
        let valueF: Double

        var line: String {
            "\(ProPersonalAnalysis.formatElapsed(elapsedMillis)) · \(ProPersonalAnalysis.formatTempF(valueF))"
        }
    }

    struct AlignedSeries: Equatable, Sendable {
        let channelId: String
        let label: String
        let testTag: String
        let points: [AlignedPoint]
        let coverage: String
    }

    struct AlignedSnapshot: Equatable, Sendable {
        let anchor: Anchor
        let series: [AlignedSeries]
        let excluded: [String]
    }

    static func channelOptions(report: InsightReport, archive: InsightArchiveInput, titles: [String: String]) -> [ChannelOption] {
        report.cooks.flatMap { cook in
            cook.channels.map { channel in
                let rows = archive.readings.filter { matches(channelId: channel.channelId, cookId: cook.cookId, row: $0) && isMeasured($0) }
                let probe = rows.first?.probeName ?? channel.channelId
                let units = rows.map(\.unit).sortedRemovingDuplicates()
                let unitText = units.isEmpty ? "no recorded readings" : units.joined(separator: ", ")
                let countText = channel.readingCount == 1 ? "1 reading" : "\(channel.readingCount) readings"
                return ChannelOption(
                    channelId: channel.channelId,
                    label: "\(titles[cook.cookId] ?? cook.cookId) · \(channel.role) · \(probe) · \(countText) (\(unitText))",
                    testTag: "curve-channel-" + channel.channelId.map { $0.isLetter || $0.isNumber ? String($0) : "-" }.joined(),
                    readingCount: channel.readingCount
                )
            }
        }
    }

    static func align(report: InsightReport, archive: InsightArchiveInput, titles: [String: String], anchor: Anchor, channelIds: Set<String>) -> AlignedSnapshot {
        var series: [AlignedSeries] = []
        var excluded: [String] = []
        for cook in report.cooks {
            for channel in cook.channels where channelIds.contains(channel.channelId) {
                let label = channelLabel(report: report, archive: archive, titles: titles, cookId: cook.cookId, channel: channel)
                let anchorAt = anchorMillis(report: report, cookId: cook.cookId, dishId: channel.dishId, anchor: anchor)
                guard let anchorAt else {
                    excluded.append("\(label): \(anchor.title) not recorded for this cook — excluded, never estimated.")
                    continue
                }
                let points = archive.readings
                    .filter { matches(channelId: channel.channelId, cookId: cook.cookId, row: $0) && isMeasured($0) }
                    .sorted { $0.measuredAtUtcMillis < $1.measuredAtUtcMillis }
                    .compactMap { row -> AlignedPoint? in
                        guard let value = row.value else { return nil }
                        return AlignedPoint(elapsedMillis: row.measuredAtUtcMillis - anchorAt, valueF: InsightsCalculations.fahrenheit(value, unit: row.unit))
                    }
                series.append(AlignedSeries(
                    channelId: channel.channelId,
                    label: label,
                    testTag: "curve-series-" + channel.channelId.map { $0.isLetter || $0.isNumber ? String($0) : "-" }.joined(),
                    points: points,
                    coverage: "\(points.count) recorded \(points.count == 1 ? "point" : "points") · anchor recorded · values in °F"
                ))
            }
        }
        return AlignedSnapshot(anchor: anchor, series: series, excluded: excluded)
    }

    private static func channelLabel(report: InsightReport, archive: InsightArchiveInput, titles: [String: String], cookId: String, channel: InsightChannelMetrics) -> String {
        let probe = archive.readings.first { matches(channelId: channel.channelId, cookId: cookId, row: $0) && isMeasured($0) }?.probeName ?? channel.channelId
        return "\(titles[cookId] ?? cookId) · \(channel.role) · \(probe)"
    }

    /// Dish-scoped channels align to their dish's anchor; cook-scoped (pit)
    /// channels align to the cook's earliest recorded anchor across dishes.
    static func anchorMillis(report: InsightReport, cookId: String, dishId: String?, anchor: Anchor) -> Double? {
        guard let cook = report.cooks.first(where: { $0.cookId == cookId }) else { return nil }
        func value(_ dish: InsightDishMetrics) -> Double? {
            switch anchor {
            case .foodOn: return dish.foodOnAt
            case .wrap: return dish.wrapAt
            }
        }
        if let dishId, let dish = cook.dishes.first(where: { $0.dishId == dishId }) {
            return value(dish)
        }
        return cook.dishes.compactMap(value).min()
    }

    private static func matches(channelId: String, cookId: String, row: InsightReadingInput) -> Bool {
        InsightsCalculations.channelId(
            cookId: cookId, probeId: row.probeId, probeName: row.probeName,
            sourceDeviceId: row.sourceDeviceId, dishId: row.dishId,
            measurementType: row.measurementType, source: row.source
        ) == channelId
    }

    /// Mirrors the v1 measured-row rule: valid, finite, and a real °F/°C unit.
    private static func isMeasured(_ row: InsightReadingInput) -> Bool {
        row.qualityStatus == "valid" && row.value?.isFinite == true && (row.unit == "°F" || row.unit == "°C")
    }
}
