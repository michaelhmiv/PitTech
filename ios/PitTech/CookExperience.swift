import Foundation

struct NoteTemperatureDraft: Codable, Identifiable, Equatable {
    var id = UUID().uuidString.lowercased()
    var probeName = "Food"
    var measurementType = "food"
    var valueText = ""
    var unit = "°F"
    var measuredAt = Date.now.timeIntervalSince1970 * 1_000
    var dishId: String?
    var probeId: String?
}
struct NotePhotoDraft: Codable, Identifiable, Equatable {
    var id: String
    var path: String
    var mimeType: String
    var caption = ""
    var capturedAt: Double?
}
struct CookNoteDraft: Codable, Identifiable, Equatable {
    var id = UUID().uuidString.lowercased()
    var cookId: String
    var dishId: String?
    var metadata = CookNoteMetadata()
    var title = ""
    var description = ""
    var occurredAt = Date.now.timeIntervalSince1970 * 1_000
    var temperatures: [NoteTemperatureDraft] = []
    var photos: [NotePhotoDraft] = []
    func validate() throws {
        guard occurredAt.isFinite, occurredAt > 0, metadata.isValid, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || metadata.action != nil || !temperatures.isEmpty || !photos.isEmpty || metadata.method != nil || metadata.durationMillis != nil || !(metadata.ingredients ?? []).isEmpty,
              Set(temperatures.map(\.id)).count == temperatures.count, Set(photos.map(\.id)).count == photos.count,
              temperatures.allSatisfy({ Double($0.valueText)?.isFinite == true && $0.measuredAt.isFinite && $0.measuredAt > 0 && !$0.probeName.isEmpty && ["°F", "°C"].contains($0.unit) && ["food", "food_probe", "pit", "pit_ambient"].contains($0.measurementType) }) else { throw CookNoteError.invalid }
    }
}
enum CookNoteError: LocalizedError {
    case invalid, scope, order, missingPhoto
    var errorDescription: String? {
        switch self {
        case .invalid: return "Add a description, photo, action or valid temperature."
        case .scope: return "This note or reading belongs to a different cook."
        case .order: return "Check the actual action time and earlier dish actions."
        case .missingPhoto: return "A photo is missing. Reattach it and save this same note again."
        }
    }
}
struct NoteDishRecord: Identifiable { let id: String; let name: String }
struct DeletedNativeNote { let event: JSONValue; let readings: [JSONValue]; let links: [JSONValue]; let photos: [JSONValue] }

struct CookAction: Identifiable { let id: String; let action: String; let dishIds: [String]; let at: Double }
struct CookPhaseSpan: Identifiable {
    let id: String; let phase: String; let start: Double; let end: Double
}
struct NativeDishPhase: Identifiable {
    let id: String; let name: String; let phase: String; let spans: [CookPhaseSpan]
    func milliseconds(_ phases: [String]) -> Double { spans.filter { phases.contains($0.phase) }.reduce(0) { $0 + max(0, $1.end - $1.start) } }
    var cooking: Double { milliseconds(["cooking", "wrapped"]) }
    var rest: Double { milliseconds(["resting"]) }
    var hold: Double { milliseconds(["holding"]) }
}
enum CookPhaseEngine {
    static let actions = ["food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done"]
    static func target(_ action: String) -> String {
        ["food_on": "cooking", "wrap": "wrapped", "remove": "removed", "rest_start": "resting", "hold_start": "holding", "dish_done": "done"][action] ?? "prep"
    }
    static func label(_ phase: String) -> String {
        ["prep": "Preparing", "cooking": "Cooking", "wrapped": "Wrapped", "removed": "Removed", "resting": "Resting", "holding": "Holding", "done": "Ready to serve"][phase] ?? phase
    }
    static func dish(id: String, name: String, actions: [CookAction], start: Double, end: Double) -> NativeDishPhase {
        let events = actions.filter { Self.actions.contains($0.action) && ($0.dishIds.isEmpty || $0.dishIds.contains(id)) && $0.at <= end }.sorted { $0.at == $1.at ? $0.id < $1.id : $0.at < $1.at }
        var phase = "prep", since = min(start, events.first?.at ?? start), spans: [CookPhaseSpan] = []
        for event in events {
            let next = target(event.action)
            if next != phase {
                if event.at > since { spans.append(CookPhaseSpan(id: event.id + ":" + phase, phase: phase, start: since, end: event.at)) }
                since = event.at; phase = next
            }
        }
        if end > since { spans.append(CookPhaseSpan(id: "current:" + phase, phase: phase, start: since, end: end)) }
        return NativeDishPhase(id: id, name: name, phase: phase, spans: spans)
    }
    static func validate(_ actions: [CookAction], dishIds: [String]) throws {
        for id in dishIds.isEmpty ? ["whole"] : dishIds {
            var phase = "prep"
            for event in actions.filter({ Self.actions.contains($0.action) && ($0.dishIds.isEmpty || $0.dishIds.contains(id)) }).sorted(by: { $0.at == $1.at ? $0.id < $1.id : $0.at < $1.at }) {
                let allowed: [String]
                switch event.action {
                case "food_on": allowed = ["prep", "removed", "resting", "holding"]
                case "wrap", "remove": allowed = ["cooking", "wrapped"]
                case "rest_start": allowed = ["cooking", "wrapped", "removed"]
                case "hold_start": allowed = ["removed", "resting"]
                default: allowed = ["removed", "resting", "holding"]
                }
                guard allowed.contains(phase) else { throw CookNoteError.order }
                phase = target(event.action)
            }
        }
    }
}

extension PitTechLibrary {
    func actions(for cookId: String) -> [CookAction] {
        events.compactMap { row in
            guard let o = row.objectValue, o["cookId"]?.stringValue == cookId, let id = o["id"]?.stringValue, let at = o["occurredAtUtcMillis"]?.numberValue else { return nil }
            let metadata = o["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) }
            let raw = o["eventType"]?.stringValue ?? "note"
            let action = metadata?.action ?? ["meat_on": "food_on", "rest": "rest_start"][raw] ?? raw
            let scopes = metadata?.dishIds.isEmpty == false ? metadata!.dishIds : o["dishId"]?.stringValue.map { [$0] } ?? []
            return CookAction(id: id, action: action, dishIds: scopes, at: at)
        }
    }
}
