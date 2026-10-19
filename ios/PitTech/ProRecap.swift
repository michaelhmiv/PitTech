import Foundation

// MARK: - Slice 18 Year in Smoke / periodic recap: scoped, previewed, explicit share

/// Pure recap builder over the LOCAL v1 report. The user controls the scope
/// (period plus exactly which cooks and sections are included) and sees a full
/// preview before anything can be shared. The card reuses the share-card v1
/// content schema so the existing native renderers draw it unchanged; recap
/// inclusion changes no publication state — a private cook never becomes
/// public because it appears in a recap. Android `domain/ProRecap.kt` pins the
/// same slots.
enum ProRecap {
    struct Scope: Equatable, Sendable {
        /// A calendar year ("2026") or "all".
        var period: String
        var includedCookIds: [String]
        var showStats: Bool
        var showMilestones: Bool
        var showBest: Bool
        var note: String

        static func all(cookIds: [String], period: String) -> Scope {
            Scope(period: period, includedCookIds: cookIds, showStats: true, showMilestones: true, showBest: true, note: "")
        }
    }

    struct Milestone: Equatable, Sendable {
        let dish: String
        let title: String
    }

    struct Stat: Equatable, Sendable {
        let label: String
        let value: String
    }

    struct Card: Equatable, Sendable {
        let title: String
        let dishes: [String]
        let milestones: [Milestone]
        let totalMilestones: Int
        let stats: [Stat]
        let note: String
        let unit: String
        let coverageNote: String
        let privacyNote: String
    }

    static let allPeriods = "all"

    /// Distinct cook years, newest first, derived from startedAt millis in UTC.
    static func availablePeriods(startedAt: [String: Double]) -> [String] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        let years = Set(startedAt.values.map { calendar.component(.year, from: Date(timeIntervalSince1970: $0 / 1000)) })
        return years.sorted(by: >).map(String.init)
    }

    static func cooksInPeriod(report: InsightReport, archive: InsightArchiveInput, period: String) -> [String] {
        guard period != allPeriods, let year = Int(period) else { return report.cooks.map(\.cookId) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        let starts = Dictionary(uniqueKeysWithValues: archive.cooks.map { ($0.id, $0.startedAtUtcMillis) })
        return report.cooks.map(\.cookId).filter { id in
            guard let start = starts[id] else { return false }
            return calendar.component(.year, from: Date(timeIntervalSince1970: start / 1000)) == year
        }
    }

    /// Builds the recap card for exactly the included cooks. Returns nil when
    /// nothing is selected — the UI shows its select-cooks state instead of a
    /// card. Pure: building never publishes, uploads, or mutates anything.
    static func build(report: InsightReport, titles: [String: String], scope: Scope, totalCookCount: Int) -> Card? {
        let included = report.cooks.filter { scope.includedCookIds.contains($0.cookId) }
        guard !included.isEmpty, included.count <= 20 else { return nil }
        let dishes = included.map { titles[$0.cookId] ?? $0.cookId }

        var milestones: [Milestone] = []
        if scope.showMilestones {
            for cook in included {
                let title = titles[cook.cookId] ?? cook.cookId
                for dish in cook.dishes {
                    if let wrap = dish.wrapAt, let foodOn = dish.foodOnAt {
                        milestones.append(Milestone(dish: title, title: "Wrapped \(ProPersonalAnalysis.formatElapsed(wrap - foodOn)) after Food on"))
                    }
                    if let heating = ProPersonalAnalysis.positive(dish.heatingMillis) {
                        milestones.append(Milestone(dish: title, title: "Finished after \(ProPersonalAnalysis.formatDuration(heating)) of heating"))
                    }
                }
            }
        }
        let total = milestones.count
        let shown = Array(milestones.prefix(5))

        var stats: [Stat] = []
        if scope.showStats {
            stats.append(Stat(label: "Recorded cooks", value: "\(included.count)"))
            let heated = included.flatMap(\.dishes).compactMap { ProPersonalAnalysis.positive($0.heatingMillis) }
            if !heated.isEmpty {
                stats.append(Stat(label: "Heating time", value: ProPersonalAnalysis.formatDuration(heated.reduce(0, +))))
            }
            if scope.showBest, let best = included.flatMap(\.dishes).compactMap(\.rating).max() {
                stats.append(Stat(label: "Best recorded rating", value: ProPersonalAnalysis.formatRating(best)))
            }
        }

        let units = Set(included.flatMap { $0.channels.flatMap(\.units) })
        let title = scope.period == allPeriods
            ? "PitTech recap · All recorded cooks"
            : "Year in Smoke · \(scope.period)"
        let coverage: String
        if scope.showMilestones {
            coverage = total > shown.count
                ? "\(included.count) of \(totalCookCount) recorded cooks selected · \(total) recorded milestones · showing \(shown.count)"
                : "\(included.count) of \(totalCookCount) recorded cooks selected · \(total) recorded milestones"
        } else {
            coverage = "\(included.count) of \(totalCookCount) recorded cooks selected"
        }
        return Card(
            title: title,
            dishes: dishes,
            milestones: shown,
            totalMilestones: total,
            stats: Array(stats.prefix(3)),
            note: scope.note,
            unit: units == ["°C"] ? "°C" : "°F",
            coverageNote: coverage,
            privacyNote: "Only the \(included.count) selected \(included.count == 1 ? "cook" : "cooks") appear in this recap. Sharing is a separate explicit step; nothing here changes what is public."
        )
    }

    /// Emits the share-card v1 content object for the recap so the existing
    /// renderer draws it (hero none: photo-free cards render correctly; long
    /// titles and notes truncate in the image only, as with share cards).
    static func shareCardContent(_ card: Card, themeId: String, paletteId: String) -> JSONValue {
        .object([
            "version": .number(1),
            "title": .string(card.title),
            "dishes": .array(card.dishes.map(JSONValue.string)),
            "hero": .string("none"),
            "unit": .string(card.unit),
            "curves": .array([]),
            "channelCount": .number(0),
            "milestones": .array(card.milestones.map { milestone in
                .object(["dish": .string(milestone.dish), "title": .string(milestone.title), "offsetMillis": .null])
            }),
            "stats": .array(card.stats.map { .object(["label": .string($0.label), "value": .string($0.value)]) }),
            "note": .string(card.note),
            "cropX": .number(0.5),
            "cropY": .number(0.5),
            "zoom": .number(1),
            "theme": .string(themeId),
            "palette": .string(paletteId),
            "relay": .null,
        ])
    }
}
