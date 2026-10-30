import Foundation

func shareCardLimited(_ text: String, _ limit: Int) -> String {
    var result = "";for scalar in text.unicodeScalars.prefix(limit) { result.unicodeScalars.append(scalar) };return result
}

struct ShareCardOptions: Codable, Equatable, Sendable {
    var version = 1
    var hero = "chart"
    var cropX = 0.5
    var cropY = 0.5
    var zoom = 1.0
    var note = ""
    var includeVerdict = false
    var unit = "auto"
    var theme = "classic"
    func validate() throws {
        guard version == 1, ["photo", "chart", "none"].contains(hero), cropX.isFinite, cropY.isFinite,
              (0...1).contains(cropX), (0...1).contains(cropY), zoom.isFinite, (1...3).contains(zoom),
              note.unicodeScalars.count <= 2000, ["auto", "°C", "°F"].contains(unit), ProCustomization.shareCardThemeIds.contains(theme) else { throw PitTechArchiveError.invalidArchive }
    }
}
struct PreparedNativeShareCard: Sendable { let content: JSONValue; let photo: Data? }
/// Supplied by the relay client after a successful upload; there is no editable code field.
struct ShareCardRelay: Sendable {
    let code: String
    let url: String
    func validate() throws {
        guard code.range(of: "^[A-Z0-9]+(?:-[A-Z0-9]+){2,5}$", options: .regularExpression) != nil,
              url == "https://pittech.app/s/" + code else { throw PitTechArchiveError.invalidArchive }
    }
}
/// Flowing Slice 3 (Spread 07): a posted recipe's card identity. The
/// footer QR encodes the canonical public link; likes/forks ride only
/// as a pair, and only when a surface supplies real counts — a card
/// never invents engagement. A card carries one footer: the Cook Pack
/// code (relay) or the publication link, never both.
struct ShareCardPublication: Equatable, Sendable {
    let itemId: String
    var likes: Int? = nil
    var forks: Int? = nil
    var url: String { "https://pittech.app/c/\(itemId)" }
    func validate() throws {
        guard UUID(uuidString: itemId) != nil, (likes == nil) == (forks == nil),
              likes.map({ (0...1_000_000_000).contains($0) }) ?? true,
              forks.map({ (0...1_000_000_000).contains($0) }) ?? true
        else { throw PitTechArchiveError.invalidArchive }
    }
}
enum NativeShareCardContent {
    private static let actions = ["food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done", "season", "trim", "preheat_start"]
    private static func rows(_ value: JSONValue, _ key: String) -> [[String: JSONValue]] { (value.objectValue?[key]?.arrayValue ?? []).compactMap(\.objectValue) }
    private static func number(_ value: Double) -> String {
        var text = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), value)
        while text.last == "0" { text.removeLast() }; if text.last == "." { text.removeLast() }; return text
    }
    static func elapsed(_ ms: Double) -> String { "\(Int(ms / 3600000))h \(Int(ms / 60000) % 60)m" }
    static func offset(_ ms: Double?) -> String { ms.map { ($0 < 0 ? "−" : "+") + elapsed(abs($0)) } ?? "Recorded" }
    static func build(_ document: NativeSharingDocument, photo: Data?, relay: ShareCardRelay? = nil, publication: ShareCardPublication? = nil) throws -> PreparedNativeShareCard {
        try document.validate(); try document.cardOptions.validate(); try relay?.validate(); try publication?.validate()
        guard relay == nil || publication == nil else { throw PitTechArchiveError.invalidArchive }
        let entries = document.entries.filter(\.included); guard !entries.isEmpty else { throw RecipeError.unavailable }
        let projection = NativeCookPackProjection(), stories = entries.map(projection.story)
        let all = stories.flatMap { rows($0, "readings") }.filter { ($0["qualityStatus"]?.stringValue ?? "valid") == "valid" }
        let settings = document.cardOptions
        let unit = settings.unit == "auto" ? (!all.isEmpty && all.allSatisfy { $0["unit"]?.stringValue == "°C" } ? "°C" : "°F") : settings.unit
        func temperature(_ row: [String: JSONValue]) -> Double {
            let value = row["value"]?.numberValue ?? 0, f = row["unit"]?.stringValue == "°C" ? value * 9 / 5 + 32 : value
            return unit == "°C" ? (f - 32) * 5 / 9 : f
        }
        var milestones: [[String: JSONValue]] = []
        for i in entries.indices {
            for row in rows(stories[i], "observations") where actions.contains(row["action"]?.stringValue ?? "") {
                milestones.append(["dish": .string(try entries[i].snapshot.name), "title": row["title"] ?? .string("Recorded action"), "offsetMillis": row["offsetMillis"] ?? .null])
            }
        }
        milestones.sort { ($0["offsetMillis"]?.numberValue ?? .greatestFiniteMagnitude) < ($1["offsetMillis"]?.numberValue ?? .greatestFiniteMagnitude) }
        var curves: [JSONValue] = [], channelCount = 0
        var anyDrawableSegment = false
        for i in entries.indices {
            guard let anchor = stories[i].objectValue?["anchorUtcMillis"]?.numberValue else { continue }
            let gaps = (stories[i].objectValue?["connectionGaps"]?.arrayValue ?? []).compactMap(\.numberValue)
            let channels = Dictionary(grouping: rows(stories[i], "readings")) { row in
                [row["probeId"]?.stringValue ?? row["id"]?.stringValue ?? "", row["measurementType"]?.stringValue ?? ""].joined(separator: ":")
            }
            channelCount += channels.count
            // First occurrence order matches the Android writer and the selected source scope.
            var order: [String] = []
            for row in rows(stories[i], "readings") {
                let key = [row["probeId"]?.stringValue ?? row["id"]?.stringValue ?? "", row["measurementType"]?.stringValue ?? ""].joined(separator: ":")
                if !order.contains(key) { order.append(key) }
            }
            for key in order {
                guard curves.count < 6, let values = channels[key], let first = values.first else { continue }
                var segments: [[[String: JSONValue]]] = [], current: [[String: JSONValue]] = []
                func flush() { if !current.isEmpty { segments.append(current) }; current = [] }
                for row in values.sorted(by: { ($0["measuredAtUtcMillis"]?.numberValue ?? 0) < ($1["measuredAtUtcMillis"]?.numberValue ?? 0) }) {
                    guard (row["qualityStatus"]?.stringValue ?? "valid") == "valid" else { flush(); continue }
                    if let previous = current.last {
                        let from = previous["measuredAtUtcMillis"]?.numberValue ?? 0, at = row["measuredAtUtcMillis"]?.numberValue ?? 0
                        let interval = max(row["samplingIntervalMillis"]?.numberValue ?? 15000, previous["samplingIntervalMillis"]?.numberValue ?? 15000)
                        if row["source"]?.stringValue == "manual" || interval == 0 || at - from > max(45000, interval + 30000) || gaps.contains(where: { $0 > from && $0 <= at }) { flush() }
                    }
                    current.append(row)
                }
                flush()
                var retained = segments
                if segments.count > 50 {
                    var indices = Set([0,segments.count-1])
                    let ranges = segments.map { segment in segment.reduce((Double.infinity,-Double.infinity)) { range,row in
                        let value = temperature(row);return (min(range.0,value),max(range.1,value))
                    } }
                    if let low = segments.indices.min(by:{ ranges[$0].0 < ranges[$1].0 }) { indices.insert(low) }
                    if let high = segments.indices.max(by:{ ranges[$0].1 < ranges[$1].1 }) { indices.insert(high) }
                    for n in 0...45 { indices.insert(n*(segments.count-1)/45) }
                    retained = indices.sorted().map { segments[$0] }
                }
                let budget = 200 / max(1,retained.count)
                let points: [JSONValue] = retained.map { segment in
                    var selected = segment
                    if segment.count > budget {
                        selected = [segment[0], segment[segment.count - 1]]
                        let buckets = max(1,(budget-2)/2), size = (segment.count+buckets-1)/buckets
                        for start in stride(from: 0, to: segment.count, by: size) {
                            let bucket = segment[start..<min(start + size, segment.count)]
                            if let low = bucket.min(by: { temperature($0) < temperature($1) }), let high = bucket.max(by: { temperature($0) < temperature($1) }) { selected += [low, high] }
                        }
                        var seen = Set<String>(); selected = selected.sorted { ($0["measuredAtUtcMillis"]?.numberValue ?? 0) < ($1["measuredAtUtcMillis"]?.numberValue ?? 0) }.filter { seen.insert($0["id"]?.stringValue ?? "").inserted }
                    }
                    return .array(selected.map { .object(["x": .number((($0["measuredAtUtcMillis"]?.numberValue ?? 0) - anchor) / 60000), "y": .number(temperature($0))]) })
                }
                if points.contains(where: { ($0.arrayValue?.count ?? 0) >= 2 }) { anyDrawableSegment = true }
                if !points.isEmpty { curves.append(.object(["label": .string("\(try entries[i].snapshot.name) · \(first["probeName"]?.stringValue ?? "") · \(first["measurementType"]?.stringValue ?? "")"), "segments": .array(points)])) }
            }
        }
        var stats: [JSONValue] = []
        func stat(_ label: String, _ value: String) { if stats.count < 3 { stats.append(.object(["label": .string(label), "value": .string(value)])) } }
        if entries.count == 1 {
            let story = stories[0], results = rows(story, "results")
            if settings.includeVerdict {
                if let rating = results.first(where: { ["overall_rating", "overall"].contains($0["type"]?.stringValue ?? "") })?["numericValue"]?.numberValue { stat("Recorded rating", number(rating) + " / 5") }
                if let again = results.first(where: { $0["type"]?.stringValue == "cook_again" })?["numericValue"]?.numberValue { stat("Cook again", again == 1 ? "Yes" : "No") }
            }
            if let anchor = story.objectValue?["anchorUtcMillis"]?.numberValue,
               let end = rows(story, "observations").filter({ $0["action"]?.stringValue == "dish_done" }).compactMap({ $0["occurredAtUtcMillis"]?.numberValue }).max(), end >= anchor { stat("Food on → done", elapsed(end - anchor)) }
            if let last = all.filter({ RecipeGuideProjection.role($0["measurementType"]?.stringValue ?? "") == "food" }).max(by: { ($0["measuredAtUtcMillis"]?.numberValue ?? 0) < ($1["measuredAtUtcMillis"]?.numberValue ?? 0) }) { stat("Last recorded food", number(temperature(last)) + " " + unit) }
            let dish = try entries[0].snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue ?? [:]
            if let weight = dish["weight"]?.stringValue, !weight.isEmpty { stat("Weight", weight + " " + (dish["weightUnit"]?.stringValue ?? "")) }
        } else { stat("Selected dishes", String(entries.count)) }
        // Flowing Slice 3 (Spread 07): the manual-logger variant's
        // chips — hand-entered food readings in time order, sampled
        // evenly to at most twelve, each labeled by its clock time in
        // the reading's own timezone and valued in the card's unit.
        let storyReadings: [[String: JSONValue]] = stories.flatMap { rows($0, "readings") }
        let manualLogged: [[String: JSONValue]] = storyReadings.filter { row in
            let isManual = row["source"]?.stringValue == "manual"
            let isValid = (row["qualityStatus"]?.stringValue ?? "valid") == "valid"
            let isFood = RecipeGuideProjection.role(row["measurementType"]?.stringValue ?? "") == "food"
            return isManual && isValid && isFood
        }.sorted { first, second in
            (first["measuredAtUtcMillis"]?.numberValue ?? 0) < (second["measuredAtUtcMillis"]?.numberValue ?? 0)
        }
        let sampledTemps: [[String: JSONValue]] = manualLogged.count <= 12 ? manualLogged : {
            var indices: [Int] = []
            for i in 0..<12 {
                let index = Int((Double(i) * Double(manualLogged.count - 1) / 11.0).rounded())
                if !indices.contains(index) { indices.append(index) }
            }
            return indices.map { manualLogged[$0] }
        }()
        let manualTemps: [JSONValue] = sampledTemps.map { row in
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US")
            formatter.dateFormat = "h:mm a"
            formatter.timeZone = TimeZone(identifier: row["timeZoneId"]?.stringValue ?? "UTC") ?? TimeZone(secondsFromGMT: 0)
            let label = formatter.string(from: Date(timeIntervalSince1970: (row["measuredAtUtcMillis"]?.numberValue ?? 0) / 1000))
            return .object(["label": .string(label), "value": .string(String(format: "%.0f", temperature(row)) + " " + unit)])
        }
        var hero = settings.hero
        if hero == "photo" && photo == nil { throw CookNoteError.missingPhoto }
        // A curve is drawable only if some segment holds two or more
        // points; hand-logged readings never join segments, so a
        // hand-logged cook's chart hero honestly becomes the manual
        // variant (or nothing) instead of a scatter of lone dots.
        if hero == "chart" {
            hero = anyDrawableSegment ? "chart" : (!manualTemps.isEmpty ? "manual" : "none")
        }
        let publicationObject: JSONValue = publication.map { pub in
            var object: [String: JSONValue] = ["url": .string(pub.url)]
            if let likes = pub.likes { object["likes"] = .number(Double(likes)) }
            if let forks = pub.forks { object["forks"] = .number(Double(forks)) }
            return JSONValue.object(object)
        } ?? .null
        let content: JSONValue = .object(["version": .number(1), "title": .string(document.title), "dishes": .array(try entries.map { .string(try $0.snapshot.name) }), "hero": .string(hero), "unit": .string(unit),
            "curves": .array(curves), "channelCount": .number(Double(channelCount)), "milestones": .array(milestones.prefix(5).map(JSONValue.object)), "stats": .array(stats), "note": .string(settings.note),
            "cropX": .number(settings.cropX), "cropY": .number(settings.cropY), "zoom": .number(settings.zoom), "theme": .string(settings.theme), "relay": relay.map { .object(["code": .string($0.code), "url": .string($0.url)]) } ?? .null,
            "manualTemps": .array(manualTemps),
            "publication": publicationObject])
        return PreparedNativeShareCard(content: content, photo: photo)
    }
}

@MainActor extension CookStore {
    /// Prepares the card for rendering. `themeOverride`/`paletteOverride` apply
    /// the caller's entitlement-resolved Pro selections to the rendered copy
    /// only; the sharing draft itself is never mutated.
    func prepareShareCard(_ id: String, relay: ShareCardRelay? = nil, publication: ShareCardPublication? = nil, themeOverride: String? = nil, paletteOverride: String? = nil) async throws -> PreparedNativeShareCard {
        let document = try sharingDocument(id)
        var original: Data?
        if document.cardOptions.hero == "photo" {
            guard let hero = document.heroPhotoId, document.entries.contains(where: { $0.included && $0.photoIds.contains(hero) }),
                  let photo = sharingPhotos(id).first(where: { $0["id"]?.stringValue == hero }), let path = photo["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
            original = try Data(contentsOf: photoURL(path))
        }
        let captured = original
        let prepared = try await Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let photo = try captured.map { try NativeCookPackPhoto.derivative($0) }
            return try NativeShareCardContent.build(document, photo: photo, relay: relay, publication: publication)
        }.value
        guard var root = prepared.content.objectValue, themeOverride != nil || paletteOverride != nil else { return prepared }
        if let themeOverride { root["theme"] = .string(themeOverride) }
        if let paletteOverride { root["palette"] = .string(paletteOverride) }
        return PreparedNativeShareCard(content: .object(root), photo: prepared.photo)
    }
}
