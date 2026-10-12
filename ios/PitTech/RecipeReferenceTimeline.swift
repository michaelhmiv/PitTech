import Foundation

struct HistoricalHeatingInterval: Equatable { let startedAt: Double; let endedAt: Double? }
enum RecipeReferenceTimeline {
    static func events(_ snapshot: RecipeSnapshot) -> [JSONValue] {
        snapshot.observations.sorted { ($0.objectValue?["occurredAtUtcMillis"]?.numberValue ?? 0) < ($1.objectValue?["occurredAtUtcMillis"]?.numberValue ?? 0) }
    }
    static func heating(_ snapshot: RecipeSnapshot) -> [HistoricalHeatingInterval] {
        var intervals: [HistoricalHeatingInterval] = [], start: Double?
        for row in events(snapshot) {
            guard let o = row.objectValue, let at = o["occurredAtUtcMillis"]?.numberValue else { continue }
            if o["action"]?.stringValue == "food_on", start == nil { start = at }
            if ["remove", "rest_start", "hold_start", "dish_done"].contains(o["action"]?.stringValue ?? ""), let began = start { intervals.append(.init(startedAt: began, endedAt: at)); start = nil }
        }
        if let start { intervals.append(.init(startedAt: start, endedAt: nil)) }
        return intervals
    }
    static func relative(_ offset: Double?) -> String {
        guard let offset else { return "No recorded Food on anchor" }
        let minutes = Int(abs(offset) / 60_000)
        return "\(offset < 0 ? "−" : "+")\(minutes / 60)h \(minutes % 60)m"
    }
    static func time(_ at: Double?, zone: String? = nil) -> String {
        guard let at else { return "Date unavailable" }
        let formatter = DateFormatter(); formatter.dateFormat = "MMM d, yyyy HH:mm z"
        formatter.timeZone = zone.flatMap(TimeZone.init(identifier:)) ?? .current
        return formatter.string(from: Date(timeIntervalSince1970: at / 1_000))
    }
}
