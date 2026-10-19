import Foundation

// MARK: - Slice 17 community benchmark cards: contract and validation

/// Typed client-side contract for the merged aggregate read API
/// (`GET /v1/community/aggregates` and `GET /v1/community/job-runs`).
/// Figures render exactly as recorded — with eligible n and compute date on
/// every figure — and the client never invents, interpolates, or partially
/// renders a figure.
enum CommunityBenchmarks {
    static let aggregatesPath = "/v1/community/aggregates"
    static let jobRunsPath = "/v1/community/job-runs"
    static let minimumN = 10

    static let metricIds: Set<String> = [
        "median-heating-time-v1",
        "pit-food-curve-percentiles-v1",
        "stall-summary-v1",
        "wrap-spritz-prevalence-v1",
    ]

    static let groups: Set<String> = [
        "beef-brisket",
        "pork-butt-shoulder",
        "pork-spare-ribs",
        "pork-baby-back-ribs",
        "pork-loin",
        "chicken-whole",
        "chicken-pieces",
        "turkey-whole",
        "other",
    ]

    static let bands: Set<String> = ["small", "medium", "large", "unknown"]

    /// Strict request filter: mirrors the server's bucket allowlist. Requests
    /// with any other bucket never reach the network.
    static func bucketAllowed(_ bucket: String) -> Bool {
        guard bucket.count >= 1, bucket.count <= 320 else { return false }
        let parts = bucket.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3, parts.count <= 4 else { return false }
        guard groups.contains(parts[0]), bands.contains(parts[1]) else { return false }
        guard parts[2].hasPrefix("method:"), parts[2].count >= 8, parts[2].count <= 120 else { return false }
        if parts.count == 4 {
            guard parts[3].hasPrefix("equipment:"), parts[3].count >= 12, parts[3].count <= 100 else { return false }
        }
        return true
    }

    /// Recorded buckets keep their shape even when the vocabulary grows: a
    /// well-formed bucket with a newer group label still renders (raw), while
    /// a misshapen bucket fails the whole response closed.
    static func bucketShapeValid(_ bucket: String) -> Bool {
        guard bucket.count >= 1, bucket.count <= 320 else { return false }
        let parts = bucket.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3, parts.count <= 4 else { return false }
        guard !parts[0].isEmpty, !parts[1].isEmpty else { return false }
        guard parts[2].hasPrefix("method:"), parts[2].count >= 8, parts[2].count <= 120 else { return false }
        if parts.count == 4 {
            guard parts[3].hasPrefix("equipment:"), parts[3].count >= 12, parts[3].count <= 100 else { return false }
        }
        return true
    }

    static func metricAllowed(_ metric: String) -> Bool { metricIds.contains(metric) }

    static func runIdValid(_ runId: String) -> Bool { UUID(uuidString: runId) != nil }

    static func jobRunsLimitValid(_ limit: Int) -> Bool { limit >= 1 && limit <= 100 }
}

// MARK: - Strict wire decoding (fail closed, never crash, never render)

enum CommunityDecodeError: Error, Equatable {
    case invalid
    case unknownMetric(String)
}

struct CommunityMetricVersion: Decodable, Equatable, Sendable {
    let metricId: String
    let metricVersion: String
}

struct CommunityJobRun: Decodable, Equatable, Sendable {
    let runId: String
    let cutoffMillis: Int64
    let computedAtMillis: Int64
    let algorithmVersion: String
    let taxonomyVersion: String
    let bandVersion: String
    let metricVersions: [CommunityMetricVersion]
    let inputCount: Int
    let eligibleCount: Int
    let unbucketedCount: Int
    let cellCount: Int
    let visibleCount: Int
    let outputSha256: String
}

enum CommunityFigureStatus: String, Decodable, Sendable {
    case visible
    case insufficientData = "insufficient_data"
}

struct CommunityFigure: Decodable, Equatable, Sendable {
    let bucket: String
    let metricId: String
    let metricVersion: String
    let n: Int
    let status: CommunityFigureStatus
    let summary: JSONValue?
    let computedDate: String
    let cutoffMillis: Int64
    let algorithmVersion: String
    let taxonomyVersion: String
    let bandVersion: String
}

struct CommunityAggregatesPayload: Equatable, Sendable {
    let run: CommunityJobRun?
    let figures: [CommunityFigure]
}

private struct CommunityAggregatesWire: Decodable {
    let run: CommunityJobRun?
    let figures: [CommunityFigure]
}

extension CommunityBenchmarks {
    /// Strict response decode. Unknown metric IDs, misshapen buckets, bad
    /// dates, negative counts, and status/summary mismatches throw, and the
    /// loader maps every throw to the unavailable state.
    static func decodeAggregates(_ data: Data) throws -> CommunityAggregatesPayload {
        let wire: CommunityAggregatesWire
        do {
            wire = try JSONDecoder().decode(CommunityAggregatesWire.self, from: data)
        } catch {
            throw CommunityDecodeError.invalid
        }
        let date = try NSRegularExpression(pattern: "^\\d{4}-\\d{2}-\\d{2}$")
        for figure in wire.figures {
            guard bucketShapeValid(figure.bucket) else { throw CommunityDecodeError.invalid }
            guard metricAllowed(figure.metricId) else { throw CommunityDecodeError.unknownMetric(figure.metricId) }
            guard figure.n >= 0, !figure.metricVersion.isEmpty else { throw CommunityDecodeError.invalid }
            let range = NSRange(figure.computedDate.startIndex..<figure.computedDate.endIndex, in: figure.computedDate)
            guard date.firstMatch(in: figure.computedDate, range: range) != nil else { throw CommunityDecodeError.invalid }
            switch figure.status {
            case .visible:
                guard case .object = figure.summary else { throw CommunityDecodeError.invalid }
            case .insufficientData:
                guard figure.summary == nil || figure.summary == .null else { throw CommunityDecodeError.invalid }
            }
        }
        return CommunityAggregatesPayload(run: wire.run, figures: wire.figures)
    }
}

// MARK: - Recorded summaries (per-metric strictness, lenient sub-objects)

private func communityInt(_ value: JSONValue?) -> Int? {
    guard let number = value?.numberValue, number.isFinite, number == number.rounded(),
          number >= 0, number <= Double(Int.max) else { return nil }
    return Int(number)
}

private func communityMillis(_ value: JSONValue?) -> Int64? {
    guard let number = value?.numberValue, number.isFinite, number == number.rounded(),
          number >= 0, number <= 9e15 else { return nil }
    return Int64(number)
}

private func communityShare(_ value: JSONValue?) -> Double? {
    guard let number = value?.numberValue, number.isFinite, number >= 0, number <= 1 else { return nil }
    return number
}

private func communityTemp(_ value: JSONValue?) -> Double? {
    guard let number = value?.numberValue, number.isFinite else { return nil }
    return number
}

struct CommunityHeatingSummary: Equatable, Sendable {
    let n: Int
    let medianMillis: Int64
    let p25Millis: Int64
    let p75Millis: Int64
    let minMillis: Int64
    let maxMillis: Int64

    init(object: [String: JSONValue]) throws {
        guard let n = communityInt(object["n"]),
              let median = communityMillis(object["medianHeatingMillis"]),
              let p25 = communityMillis(object["p25HeatingMillis"]),
              let p75 = communityMillis(object["p75HeatingMillis"]),
              let min = communityMillis(object["minHeatingMillis"]),
              let max = communityMillis(object["maxHeatingMillis"]) else { throw CommunityDecodeError.invalid }
        self.n = n
        self.medianMillis = median
        self.p25Millis = p25
        self.p75Millis = p75
        self.minMillis = min
        self.maxMillis = max
    }
}

struct CommunityCurveBand: Equatable, Sendable {
    let n: Int
    let coverage: Double
    let p10: Double?
    let p50: Double?
    let p90: Double?

    /// A band with null percentiles is suppressed but representable: n and
    /// coverage still render. A structurally broken band is nil, and the card
    /// omits that band with its note intact.
    init?(object: [String: JSONValue]) {
        guard let n = communityInt(object["n"]), let coverage = communityShare(object["coverage"]) else { return nil }
        func percentile(_ key: String) -> Double?? {
            guard let raw = object[key], raw != .null else { return .some(nil) }
            guard let value = communityTemp(raw) else { return nil }
            return .some(value)
        }
        guard let p10 = percentile("p10"), let p50 = percentile("p50"), let p90 = percentile("p90") else { return nil }
        self.n = n
        self.coverage = coverage
        self.p10 = p10
        self.p50 = p50
        self.p90 = p90
    }

    var suppressed: Bool { p10 == nil || p50 == nil || p90 == nil }
}

struct CommunityCurveInterval: Equatable, Sendable {
    let startMillis: Int64
    let endMillis: Int64
    let pit: CommunityCurveBand?
    let food: CommunityCurveBand?

    /// Intervals the client cannot represent are nil, and the card skips them
    /// while its n/date/coverage lines stay intact.
    init?(value: JSONValue) {
        guard let object = value.objectValue,
              let start = communityMillis(object["startElapsedMillis"]),
              let end = communityMillis(object["endElapsedMillis"]) else { return nil }
        func band(_ key: String) -> CommunityCurveBand? {
            guard let raw = object[key], raw != .null, let inner = raw.objectValue else { return nil }
            return CommunityCurveBand(object: inner)
        }
        self.startMillis = start
        self.endMillis = end
        self.pit = band("pit")
        self.food = band("food")
    }
}

struct CommunityCurveSummary: Equatable, Sendable {
    let n: Int
    let intervals: [CommunityCurveInterval]

    init(object: [String: JSONValue]) throws {
        guard let n = communityInt(object["n"]),
              object["kind"]?.stringValue == "percentile-bands",
              let raw = object["intervals"], case .array(let items) = raw else { throw CommunityDecodeError.invalid }
        self.n = n
        self.intervals = items.compactMap(CommunityCurveInterval.init(value:))
    }
}

struct CommunityStallBand: Equatable, Sendable {
    let p25: Double?
    let median: Double?
    let p75: Double?

    init(object: [String: JSONValue]) throws {
        func optional(_ key: String) throws -> Double? {
            guard let raw = object[key], raw != .null else { return nil }
            guard let value = raw.numberValue, value.isFinite else { throw CommunityDecodeError.invalid }
            return value
        }
        self.p25 = try optional("p25")
        self.median = try optional("median")
        self.p75 = try optional("p75")
    }

    var isEmpty: Bool { p25 == nil && median == nil && p75 == nil }
}

struct CommunityStallSummary: Equatable, Sendable {
    let n: Int
    let nStalled: Int
    let prevalence: Double
    let start: CommunityStallBand
    let duration: CommunityStallBand
    let temp: CommunityStallBand
    let note: String?

    init(object: [String: JSONValue]) throws {
        guard let n = communityInt(object["n"]),
              let nStalled = communityInt(object["nStalled"]),
              let prevalence = communityShare(object["prevalence"]),
              let startRaw = object["startElapsedMillis"]?.objectValue,
              let durationRaw = object["durationMillis"]?.objectValue,
              let tempRaw = object["tempCelsius"]?.objectValue else { throw CommunityDecodeError.invalid }
        let start = try CommunityStallBand(object: startRaw)
        let duration = try CommunityStallBand(object: durationRaw)
        let temp = try CommunityStallBand(object: tempRaw)
        // A stalled subset with no medians contradicts the recorded counts, so
        // the figure is omitted rather than partially rendered.
        if nStalled > 0, start.isEmpty || duration.isEmpty || temp.isEmpty { throw CommunityDecodeError.invalid }
        self.n = n
        self.nStalled = nStalled
        self.prevalence = prevalence
        self.start = start
        self.duration = duration
        self.temp = temp
        self.note = object["note"]?.stringValue
    }
}

struct CommunityMethodShare: Equatable, Sendable {
    let label: String
    let n: Int
    let prevalence: Double
}

struct CommunityWrapSummary: Equatable, Sendable {
    let n: Int
    let nWrapped: Int
    let wrapPrevalence: Double
    let nSpritzed: Int
    let spritzPrevalence: Double
    let methods: [CommunityMethodShare]

    init(object: [String: JSONValue]) throws {
        guard let n = communityInt(object["n"]),
              let nWrapped = communityInt(object["nWrapped"]),
              let wrapPrevalence = communityShare(object["wrapPrevalence"]),
              let nSpritzed = communityInt(object["nSpritzed"]),
              let spritzPrevalence = communityShare(object["spritzPrevalence"]),
              let raw = object["methods"], case .array(let items) = raw else { throw CommunityDecodeError.invalid }
        self.n = n
        self.nWrapped = nWrapped
        self.wrapPrevalence = wrapPrevalence
        self.nSpritzed = nSpritzed
        self.spritzPrevalence = spritzPrevalence
        self.methods = items.compactMap { item -> CommunityMethodShare? in
            guard let method = item.objectValue,
                  let label = method["label"]?.stringValue, !label.isEmpty,
                  let count = communityInt(method["n"]),
                  let share = communityShare(method["prevalence"]) else { return nil }
            return CommunityMethodShare(label: label, n: count, prevalence: share)
        }
    }
}

// MARK: - Rendered cards (pure strings; both clients pin the same meanings)

/// One benchmark card's rendered strings. `coverageLine` always carries the
/// eligible n and the UTC compute date; figures never render without them.
struct CommunityCardContent: Equatable, Sendable {
    let testTag: String
    let title: String
    let bucketLine: String
    let lines: [String]
    let notes: [String]
    let coverageLine: String
    let insufficient: Bool
}

/// A visible figure the client cannot represent. The values are omitted — never
/// zeroed — while the coverage note (n + compute date) stays intact.
struct CommunityOmittedFigure: Equatable, Sendable {
    let testTag: String
    let title: String
    let bucketLine: String
    let note: String
}

struct CommunityBenchmarksSnapshot: Equatable, Sendable {
    let runId: String
    let cards: [CommunityCardContent]
    let omitted: [CommunityOmittedFigure]
}

extension CommunityBenchmarks {
    static func metricTitle(_ metricId: String) -> String {
        switch metricId {
        case "median-heating-time-v1": return "Typical heating time"
        case "pit-food-curve-percentiles-v1": return "Temperature percentile bands"
        case "stall-summary-v1": return "Stall summary"
        case "wrap-spritz-prevalence-v1": return "Wrap and spritz prevalence"
        default: return "Community figure"
        }
    }

    /// Pork loin and pork butt are different groups and stay different labels.
    static func groupLabel(_ group: String) -> String {
        switch group {
        case "beef-brisket": return "Beef brisket"
        case "pork-butt-shoulder": return "Pork butt / shoulder"
        case "pork-spare-ribs": return "Pork spare ribs"
        case "pork-baby-back-ribs": return "Pork baby back ribs"
        case "pork-loin": return "Pork loin"
        case "chicken-whole": return "Whole chicken"
        case "chicken-pieces": return "Chicken pieces"
        case "turkey-whole": return "Whole turkey"
        case "other": return "Other cooks"
        default: return group
        }
    }

    static func bandLabel(_ band: String) -> String {
        switch band {
        case "small": return "Small"
        case "medium": return "Medium"
        case "large": return "Large"
        case "unknown": return "Unknown weight"
        default: return band
        }
    }

    /// Recorded bucket key rendered for display. Unknown groups fall back to
    /// the raw key segment — shown exactly, never merged into a named group.
    static func bucketLine(_ bucket: String) -> String {
        let parts = bucket.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3 else { return bucket }
        var line = "\(groupLabel(parts[0])) · \(bandLabel(parts[1]))"
        let method = parts[2].hasPrefix("method:") ? String(parts[2].dropFirst(7)) : parts[2]
        line += method == "unknown" || method.isEmpty ? " · Method not recorded" : " · Method: \(method)"
        if parts.count == 4, parts[3].hasPrefix("equipment:") {
            line += " · Equipment: \(parts[3].dropFirst(10))"
        }
        return line
    }

    static func cardTag(metricId: String, bucket: String, insufficient: Bool) -> String {
        let group = bucket.split(separator: "|").first.map(String.init) ?? "other"
        return (insufficient ? "community-insufficient-" : "community-card-") + metricId + "-" + group
    }

    static func coverageLine(n: Int, computedDate: String) -> String {
        "\(n) eligible cooks · Computed \(computedDate)"
    }

    /// Builds one card per recorded figure. Summaries the client cannot
    /// represent become omitted notes with their coverage intact.
    static func cards(figures: [CommunityFigure]) -> (cards: [CommunityCardContent], omitted: [CommunityOmittedFigure]) {
        var cards: [CommunityCardContent] = []
        var omitted: [CommunityOmittedFigure] = []
        for figure in figures {
            let bucketLine = bucketLine(figure.bucket)
            let insufficientTag = cardTag(metricId: figure.metricId, bucket: figure.bucket, insufficient: true)
            if figure.status == .insufficientData {
                cards.append(CommunityCardContent(
                    testTag: insufficientTag,
                    title: metricTitle(figure.metricId),
                    bucketLine: bucketLine,
                    lines: [
                        "Not enough cooks yet",
                        "\(figure.n) of \(minimumN) eligible cooks recorded. Figures appear when \(minimumN) distinct eligible cooks are recorded.",
                    ],
                    notes: [],
                    coverageLine: coverageLine(n: figure.n, computedDate: figure.computedDate),
                    insufficient: true
                ))
                continue
            }
            guard let summary = figure.summary, let object = summary.objectValue else {
                omitted.append(omittedNote(figure: figure, bucketLine: bucketLine))
                continue
            }
            do {
                cards.append(try visibleCard(figure: figure, object: object, bucketLine: bucketLine))
            } catch {
                omitted.append(omittedNote(figure: figure, bucketLine: bucketLine))
            }
        }
        return (cards, omitted)
    }

    private static func omittedNote(figure: CommunityFigure, bucketLine: String) -> CommunityOmittedFigure {
        CommunityOmittedFigure(
            testTag: "community-omitted-" + figure.metricId + "-" + (figure.bucket.split(separator: "|").first.map(String.init) ?? "other"),
            title: metricTitle(figure.metricId),
            bucketLine: bucketLine,
            note: "One figure could not be displayed (\(coverageLine(n: figure.n, computedDate: figure.computedDate)))."
        )
    }

    private static func visibleCard(figure: CommunityFigure, object: [String: JSONValue], bucketLine: String) throws -> CommunityCardContent {
        let tag = cardTag(metricId: figure.metricId, bucket: figure.bucket, insufficient: false)
        let coverage = coverageLine(n: figure.n, computedDate: figure.computedDate)
        let title = metricTitle(figure.metricId)
        switch figure.metricId {
        case "median-heating-time-v1":
            let summary = try CommunityHeatingSummary(object: object)
            return CommunityCardContent(
                testTag: tag, title: title, bucketLine: bucketLine,
                lines: [
                    "Typical (median): \(formatHours(summary.medianMillis))",
                    "Middle half: \(formatHours(summary.p25Millis)) – \(formatHours(summary.p75Millis))",
                    "Recorded range: \(formatHours(summary.minMillis)) – \(formatHours(summary.maxMillis))",
                ],
                notes: [],
                coverageLine: coverage,
                insufficient: false
            )
        case "pit-food-curve-percentiles-v1":
            let summary = try CommunityCurveSummary(object: object)
            var lines: [String] = []
            for interval in summary.intervals {
                lines.append(intervalHeader(start: interval.startMillis, end: interval.endMillis))
                lines.append(bandLine(name: "Pit", band: interval.pit, figureN: summary.n))
                lines.append(bandLine(name: "Food", band: interval.food, figureN: summary.n))
            }
            if summary.intervals.isEmpty {
                lines.append("No intervals could be displayed.")
            }
            return CommunityCardContent(
                testTag: tag, title: title, bucketLine: bucketLine,
                lines: lines,
                notes: ["Percentile bands summarize many cooks. They are not one cook's readings."],
                coverageLine: coverage,
                insufficient: false
            )
        case "stall-summary-v1":
            let summary = try CommunityStallSummary(object: object)
            var lines = ["Stalled: \(summary.nStalled) of \(summary.n) assessed cooks (\(formatPercent(summary.prevalence)))"]
            if summary.nStalled == 0 {
                lines.append("No stalled cooks in this group.")
            } else {
                if let start = summary.start.median, let startMillis = millisValue(start) {
                    lines.append("Stall start (median): \(formatElapsed(startMillis))")
                }
                if let duration = summary.duration.median, let durationMillis = millisValue(duration) {
                    lines.append("Stall length (median): \(formatHours(durationMillis))")
                }
                if let temp = summary.temp.median {
                    lines.append("Stall temperature (median): \(formatTemp(temp))")
                }
            }
            return CommunityCardContent(
                testTag: tag, title: title, bucketLine: bucketLine,
                lines: lines,
                notes: [summary.note ?? "Sparse attempts are excluded from n, never interpolated."],
                coverageLine: coverage,
                insufficient: false
            )
        case "wrap-spritz-prevalence-v1":
            let summary = try CommunityWrapSummary(object: object)
            var lines = [
                "Wrapped: \(summary.nWrapped) of \(summary.n) (\(formatPercent(summary.wrapPrevalence)))",
                "Spritzed or mopped: \(summary.nSpritzed) of \(summary.n) (\(formatPercent(summary.spritzPrevalence)))",
            ]
            if summary.methods.isEmpty {
                lines.append("Methods recorded: none listed")
            } else {
                for method in summary.methods {
                    lines.append("Method \(method.label): \(method.n) of \(summary.n) (\(formatPercent(method.prevalence)))")
                }
            }
            return CommunityCardContent(
                testTag: tag, title: title, bucketLine: bucketLine,
                lines: lines,
                notes: ["Prevalence only: these shares describe what cooks recorded."],
                coverageLine: coverage,
                insufficient: false
            )
        default:
            throw CommunityDecodeError.invalid
        }
    }

    private static func millisValue(_ value: Double) -> Int64? {
        guard value.isFinite, value == value.rounded(), value >= 0, value <= 9e15 else { return nil }
        return Int64(value)
    }

    private static func intervalHeader(start: Int64, end: Int64) -> String {
        "+" + elapsedCore(start) + "–" + elapsedCore(end)
    }

    private static func bandLine(name: String, band: CommunityCurveBand?, figureN: Int) -> String {
        guard let band else { return "\(name) band unavailable for this interval." }
        if band.suppressed {
            return "\(name) band hidden: \(band.n) of \(figureN) cooks in this interval (\(formatPercent(band.coverage))). Bands need \(minimumN) cooks."
        }
        return "\(name): p10 \(formatTemp(band.p10!)) · p50 \(formatTemp(band.p50!)) · p90 \(formatTemp(band.p90!)) (n=\(band.n), \(formatPercent(band.coverage)) of cooks)"
    }

    // MARK: - Shared formatting (same rules on both clients)

    /// 19800000 -> "5h 30m"; 3600000 -> "1h"; 2700000 -> "45m".
    static func formatHours(_ millis: Int64) -> String {
        let hours = millis / 3_600_000
        let minutes = (millis % 3_600_000) / 60_000
        if hours > 0, minutes > 0 { return "\(hours)h \(minutes)m" }
        if hours > 0 { return "\(hours)h" }
        return "\(minutes)m"
    }

    /// 0 -> "+0:00"; 900000 -> "+0:15"; 7200000 -> "+2:00".
    static func formatElapsed(_ millis: Int64) -> String { "+" + elapsedCore(millis) }

    private static func elapsedCore(_ millis: Int64) -> String {
        let hours = millis / 3_600_000
        let minutes = (millis % 3_600_000) / 60_000
        return "\(hours):\(String(format: "%02d", minutes))"
    }

    /// 0.6 -> "60%"; 1.0 -> "100%".
    static func formatPercent(_ share: Double) -> String { "\(Int((share * 100).rounded()))%" }

    /// Recorded Celsius, exactly as recorded.
    static func formatTemp(_ celsius: Double) -> String { "\(celsius) °C" }
}
