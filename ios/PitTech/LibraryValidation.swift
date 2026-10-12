import Foundation

extension PitTechLibrary {
    /// Validate portable relationships before files are copied or the saved library changes.
    func validateCookExperience() throws {
        try validateRecipes()
        try validateRecipeAttempts()
        try validateActivityTimers()
        let cooksById = Set(cooks.map(\.id))
        func index(_ rows: [JSONValue], key: String = "id") throws -> [String: [String: JSONValue]] {
            var result: [String: [String: JSONValue]] = [:]
            for row in rows {
                guard let item = row.objectValue, let id = item[key]?.stringValue, !id.isEmpty, result[id] == nil else { throw PitTechArchiveError.invalidArchive }
                result[id] = item
            }
            return result
        }
        let eventsById = try index(events), readingsById = try index(readings), dishesById = try index(dishes)
        var linked = Set<String>()
        for row in logReadingLinks {
            guard let item = row.objectValue, let readingId = item["readingId"]?.stringValue,
                  let eventId = item["eventId"]?.stringValue, linked.insert(readingId).inserted,
                  let reading = readingsById[readingId], let event = eventsById[eventId],
                  reading["cookId"] == event["cookId"], reading["source"]?.stringValue == "manual" else { throw PitTechArchiveError.invalidArchive }
        }
        _ = try index(lifecycles, key: "cookId")
        for row in lifecycles {
            guard let item = row.objectValue, let cookId = item["cookId"]?.stringValue, cooksById.contains(cookId),
                  ["legacy_preserved", "explicit_phases"].contains(item["timingMode"]?.stringValue ?? "") else { throw PitTechArchiveError.invalidArchive }
        }
        _ = try index(targetHistory)
        for row in targetHistory {
            guard let item = row.objectValue, let cookId = item["cookId"]?.stringValue, cooksById.contains(cookId),
                  let value = item["value"]?.numberValue, value.isFinite,
                  ["°F", "°C"].contains(item["unit"]?.stringValue ?? ""), item["effectiveAt"]?.numberValue != nil else { throw PitTechArchiveError.invalidArchive }
            if let dishId = item["dishId"]?.stringValue, dishesById[dishId]?["cookId"]?.stringValue != cookId { throw PitTechArchiveError.invalidArchive }
        }
        for event in eventsById.values {
            guard let raw = event["noteMetadataJson"]?.stringValue else { continue }
            guard let data = raw.data(using: .utf8), let metadata = try? JSONDecoder().decode(CookNoteMetadata.self, from: data),
                  metadata.isValid, metadata.dishIds.allSatisfy({ dishesById[$0]?["cookId"] == event["cookId"] }) else { throw PitTechArchiveError.invalidArchive }
        }
    }
}

struct CookNoteMetadata: Codable, Equatable {
    var version = 1
    var category = "general"
    var action: String?
    var dishIds: [String] = []
    var method: String?
    var durationMillis: Double?
    var startedAtUtcMillis: Double?
    var endedAtUtcMillis: Double?
    var ingredients: [NoteIngredient]? = nil
    static let actions = ["food_on", "spritz", "wrap", "check_in", "remove", "rest_start", "hold_start", "dish_done", "preheat_start", "season", "trim", "marinate", "add_fuel"]
    var isValid: Bool {
        guard [1, 2].contains(version), ["general", "temperature", "photo", "action"].contains(category), action == nil || Self.actions.contains(action!), Set(dishIds).count == dishIds.count,
              method == nil || CookingMethod.labels[method!] != nil, durationMillis == nil || durationMillis!.isFinite && (0...2_592_000_000).contains(durationMillis!),
              startedAtUtcMillis == nil || startedAtUtcMillis!.isFinite && startedAtUtcMillis! > 0, endedAtUtcMillis == nil || endedAtUtcMillis!.isFinite && endedAtUtcMillis! > 0,
              (ingredients ?? []).count <= 100, (ingredients ?? []).allSatisfy({ !$0.name.isEmpty && ($0.amount.isEmpty || Double($0.amount).map { $0.isFinite && $0 > 0 } == true) }) else { return false }
        if let start = startedAtUtcMillis, let end = endedAtUtcMillis, end < start || durationMillis != nil && durationMillis != end - start { return false }
        return true
    }
    var json: String {
        var copy = self
        copy.version = method != nil || durationMillis != nil || startedAtUtcMillis != nil || endedAtUtcMillis != nil || !(ingredients ?? []).isEmpty ? 2 : 1
        return String(data: (try? JSONEncoder().encode(copy)) ?? Data(), encoding: .utf8) ?? ""
    }
}
struct NoteIngredient: Codable, Equatable {
    var name = ""
    var stage = "main_ingredient"
    var brand = ""
    var amount = ""
    var unit = "count"
}
enum CookingMethod {
    static let labels = ["prep": "Preparation", "stovetop": "Stovetop", "smoker": "Smoker", "oven": "Oven", "grill": "Grill", "resting": "Resting", "holding": "Holding", "other": "Other"]
    static let order = ["prep", "stovetop", "smoker", "oven", "grill", "resting", "holding", "other"]
}
