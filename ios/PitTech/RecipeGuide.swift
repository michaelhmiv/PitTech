import Foundation

struct GuidePresentation: Codable, Equatable {
    var version = 1
    var visible = false
    var dishIds: [String]? = nil
    var channels: [String: String] = [:]
}
struct NativePinnedRecipeGuide {
    let revisionId: String
    let snapshot: RecipeSnapshot
    let story: JSONValue
    var recordedSnapshot: RecipeSnapshot {
        var result = snapshot
        result.observations = story.objectValue?["observations"]?.arrayValue ?? []
        result.smokingAnchorUtcMillis = story.objectValue?["anchorUtcMillis"]?.numberValue.map(Int64.init)
        return result
    }
    init(revisionId: String, snapshot: RecipeSnapshot, story: JSONValue) {
        self.revisionId = revisionId; self.snapshot = snapshot; self.story = story
    }
    init(_ value: JSONValue) throws {
        guard let o = value.objectValue, o["version"]?.numberValue == 1, let revision = o["revisionId"]?.stringValue,
              let snapshot = o["snapshot"], let story = o["story"] else { throw PitTechArchiveError.invalidArchive }
        self.init(revisionId: revision, snapshot: try RecipeSnapshot.decode(recipeJSON(snapshot)), story: story)
        let entry = NativeSharingEntry(snapshotJson: try recipeJSON(self.snapshot), story: story)
        try NativeSharingDocument(title: String(self.snapshot.name.prefix(160)), entries: [entry]).validate()
    }
    func encoded() throws -> JSONValue {
        .object(["version": .number(1), "revisionId": .string(revisionId), "snapshot": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(snapshot)), "story": story])
    }
}
struct NativeDishGuide: Identifiable {
    let id: String
    let name: String
    let guide: NativePinnedRecipeGuide
    let working: RecipeSnapshot
}
struct CachedRecipeGuide {
    let stamp: Double
    let revisionId: String
    let guide: NativePinnedRecipeGuide
    let working: RecipeSnapshot
}
struct NativeAlignedGuide {
    var readings: [TemperatureSample] = []
    var gaps: [Date] = []
    var originalTimes: [String: Double] = [:]
    var matches: [String: String] = [:]
}
enum RecipeGuideProjection {
    static func role(_ raw: String) -> String {
        if ["food", "food_probe"].contains(raw) { return "food" }
        if ["pit", "pit_ambient", "ambient_temperature", "grill_temperature", "pit_temperature", "pit_probe"].contains(raw) { return "pit" }
        return raw
    }
    static func channel(_ dishId: String, _ row: [String: JSONValue]) -> String {
        [dishId, row["probeId"]?.stringValue ?? "sample:" + (row["id"]?.stringValue ?? ""), role(row["measurementType"]?.stringValue ?? "food")].joined(separator: "|")
    }
    static func aligned(_ guide: NativePinnedRecipeGuide, dishId: String, name: String, anchor: Double?) -> NativeAlignedGuide {
        guard let anchor, let original = guide.story.objectValue?["anchorUtcMillis"]?.numberValue else { return NativeAlignedGuide() }
        var result = NativeAlignedGuide()
        for value in guide.story.objectValue?["readings"]?.arrayValue ?? [] {
            guard var row = value.objectValue, let id = row["id"]?.stringValue, let at = row["measuredAtUtcMillis"]?.numberValue else { continue }
            let alias = "guide:\(dishId):\(id)"
            result.originalTimes[alias] = at
            row["id"] = .string(alias); row["cookId"] = .string("guide:" + dishId); row["dishId"] = .string(dishId)
            if row["probeId"]?.stringValue == nil { row["probeId"] = .string("sample:" + id) }
            row["probeName"] = .string("Author · \(name) · \(row["probeName"]?.stringValue ?? "Source channel")")
            row["measuredAtUtcMillis"] = .number(anchor + at - original)
            if let sample = TemperatureSample(json: .object(row)) { result.readings.append(sample) }
        }
        result.gaps = (guide.story.objectValue?["connectionGaps"]?.arrayValue ?? []).compactMap { $0.numberValue }.map { Date(timeIntervalSince1970: (anchor + $0 - original) / 1_000) }
        return result
    }
}

extension PitTechLibrary {
    func validateGuidePresentation() throws {
        for row in companionRecords where row.objectValue?["kind"]?.stringValue == "guide_presentation" {
            guard let o = row.objectValue, let cook = o["cookId"]?.stringValue, cooks.contains(where: { $0.id == cook }), let payload = o["payload"] else { throw PitTechArchiveError.invalidArchive }
            let state = try JSONDecoder().decode(GuidePresentation.self, from: JSONEncoder().encode(payload))
            let ids = state.dishIds ?? []
            guard state.version == 1, ids.count <= 30, Set(ids).count == ids.count, state.channels.count <= 200,
                  ids.allSatisfy({ id in dishes.contains { $0.objectValue?["id"]?.stringValue == id && $0.objectValue?["cookId"]?.stringValue == cook } }) else { throw PitTechArchiveError.invalidArchive }
        }
        for row in companionRecords where row.objectValue?["kind"]?.stringValue == "recipe_attempt" {
            if let value = row.objectValue?["payload"]?.objectValue?["guide"], value != .null {
                let guide = try NativePinnedRecipeGuide(value)
                guard recipeRevisions.contains(where: { $0.objectValue?["id"]?.stringValue == guide.revisionId }) else { throw PitTechArchiveError.invalidArchive }
            }
        }
    }
}

@MainActor extension CookStore {
    func pinGuide(_ revisionId: String, snapshot: RecipeSnapshot, inheritedRevisionId: String? = nil) throws -> JSONValue {
        let revision = recipeRevision(revisionId)
        let inheritedId = inheritedRevisionId ?? revision?["parentRevisionId"]?.stringValue
        let canInherit = inheritedId.flatMap(recipeRevision).map { $0["sourceCookId"] == revision?["sourceCookId"] } ?? false
        var inherited: NativeSharingEntry?
        for record in library.companionRecords where ["recipe_reference", "collection_revision"].contains(record.objectValue?["kind"]?.stringValue ?? "") {
            if let payload = record.objectValue?["payload"] {
                let entries = try NativeSharingDocument.decode(payload).entries
                if let entry = entries.first(where: { $0.sourceRevisionId == revisionId && $0.includeStory }) { return try NativePinnedRecipeGuide(revisionId: revisionId, snapshot: snapshot, story: entry.story).encoded() }
                if canInherit, let entry = entries.first(where: { $0.sourceRevisionId == inheritedId && $0.includeStory }) { inherited = entry }
            }
        }
        if let inherited { return try NativePinnedRecipeGuide(revisionId: revisionId, snapshot: snapshot, story: inherited.story).encoded() }
        var readings: [JSONValue] = [], gaps: [JSONValue] = []
        var attempt: JSONValue = revision?["sourceDishId"] ?? .null
        if let cook = revision?["sourceCookId"]?.stringValue, let dish = revision?["sourceDishId"]?.stringValue, dishRecords(cook).contains(where: { $0.id == dish }) {
            for row in library.readings.compactMap(\.objectValue).filter({ $0["cookId"]?.stringValue == cook && ($0["dishId"]?.stringValue == dish || $0["dishId"]?.stringValue == nil && RecipeGuideProjection.role($0["measurementType"]?.stringValue ?? "") != "food") }) {
                var safe = row.filter { ["id", "probeId", "probeName", "measurementType", "value", "unit", "measuredAtUtcMillis", "timeZoneId", "source", "samplingIntervalMillis", "qualityStatus", "timestampBasis"].contains($0.key) }
                safe["source"] = .string(row["source"]?.stringValue == "manual" ? "manual" : "recorded")
                if let unit = safe["unit"]?.stringValue, ["C", "F"].contains(unit) { safe["unit"] = .string("°" + unit) }
                readings.append(.object(safe))
            }
            gaps = library.events.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cook && $0["eventType"]?.stringValue == "connection_gap" && ($0["dishId"]?.stringValue == nil || $0["dishId"]?.stringValue == dish) }.compactMap { $0["occurredAtUtcMillis"] }
        } else {
            var ids = Set<String>()
            readings = snapshot.observations.flatMap { $0.objectValue?["recordedTemperatures"]?.arrayValue ?? [] }.compactMap { row in
                guard var o = row.objectValue, let id = o["sourceReadingId"]?.stringValue, ids.insert(id).inserted else { return nil }
                o["id"] = .string(id); o["source"] = .string("manual"); o["samplingIntervalMillis"] = .number(0); o["qualityStatus"] = .string("valid")
                if attempt == .null { attempt = .string("saved-observations:" + revisionId) }
                return .object(o)
            }
        }
        let story: JSONValue = .object(["version": .number(1), "attemptId": attempt, "anchorUtcMillis": snapshot.smokingAnchorUtcMillis.map { .number(Double($0)) } ?? .null,
            "observations": .array(snapshot.observations), "readings": .array(readings), "connectionGaps": .array(gaps), "results": .array([])])
        return try NativePinnedRecipeGuide(revisionId: revisionId, snapshot: snapshot, story: story).encoded()
    }
    func retainRecipeGuide(_ revisionId: String, snapshot: RecipeSnapshot, inheritedRevisionId: String?) throws {
        let pinned = try pinGuide(revisionId, snapshot: snapshot, inheritedRevisionId: inheritedRevisionId)
        let entry = NativeSharingEntry(sourceDishId: recipeRevision(revisionId)?["sourceDishId"]?.stringValue, sourceRevisionId: revisionId, snapshotJson: try recipeJSON(snapshot), story: pinned.objectValue?["story"] ?? .null, includeStory: true)
        let document = NativeSharingDocument(title: String(snapshot.name.prefix(160)), entries: [entry]), now = Date.now.timeIntervalSince1970 * 1000
        library.companionRecords.append(.object(["id": .string("recipe-guide:" + revisionId), "kind": .string("recipe_reference"), "title": .string(document.title), "payload": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)), "created": .number(now), "updated": .number(now)]))
    }
    func guideState(_ cookId: String) -> GuidePresentation {
        guard let payload = library.companionRecords.first(where: { $0.objectValue?["id"]?.stringValue == "guide-presentation:" + cookId })?.objectValue?["payload"],
              let bytes = try? JSONEncoder().encode(payload), let state = try? JSONDecoder().decode(GuidePresentation.self, from: bytes) else { return GuidePresentation() }
        return state
    }
    func saveGuideState(_ cookId: String, _ state: GuidePresentation) throws {
        let previous = library, now = Date.now.timeIntervalSince1970 * 1_000, id = "guide-presentation:" + cookId
        do {
            let created = library.companionRecords.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue?["created"] ?? .number(now)
            let payload = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(state))
            library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == id }
            library.companionRecords.append(.object(["id": .string(id), "kind": .string("guide_presentation"), "cookId": .string(cookId), "title": .string("Show guide"), "payload": payload, "created": created, "updated": .number(now)]))
            try library.validateGuidePresentation(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }
        } catch { library = previous; throw error }
    }
    func ensureGuides(_ cookId: String) throws {
        var changed = false
        for index in library.companionRecords.indices {
            guard var row = library.companionRecords[index].objectValue, row["kind"]?.stringValue == "recipe_attempt", row["cookId"]?.stringValue == cookId,
                  var payload = row["payload"]?.objectValue, payload["guide"] == nil || payload["guide"] == .null, let revision = payload["revisionId"]?.stringValue else { continue }
            payload["guide"] = try pinGuide(revision, snapshot: recipeSnapshot(revision)); row["payload"] = .object(payload)
            library.companionRecords[index] = .object(row); changed = true
        }
        if changed { save() }
    }
    func guides(_ cookId: String) -> [NativeDishGuide] {
        dishRecords(cookId).compactMap { dish in
            guard let record = library.companionRecords.first(where: { $0.objectValue?["id"]?.stringValue == "recipe-attempt:" + dish.id })?.objectValue,
                  let payload = record["payload"]?.objectValue, let raw = payload["guide"], let revision = raw.objectValue?["revisionId"]?.stringValue else { return nil }
            let stamp = record["updated"]?.numberValue ?? 0
            if let cached = recipeGuideCache[dish.id], cached.stamp == stamp && cached.revisionId == revision {
                return NativeDishGuide(id: dish.id, name: dish.name, guide: cached.guide, working: cached.working)
            }
            guard let guide = try? NativePinnedRecipeGuide(raw), let working = try? recipeAttempt(dish.id) else { return nil }
            recipeGuideCache[dish.id] = CachedRecipeGuide(stamp: stamp, revisionId: revision, guide: guide, working: working)
            return NativeDishGuide(id: dish.id, name: dish.name, guide: guide, working: working)
        }
    }
    func guideAnchor(_ cookId: String, dishId: String) -> Double? {
        let created = library.dishes.first { $0.objectValue?["id"]?.stringValue == dishId }?.objectValue?["createdAtUtcMillis"]?.numberValue ?? 0
        return library.actions(for: cookId).filter { action in
            guard action.action == "food_on" else { return false }
            if action.dishIds.contains(dishId) { return true }
            guard action.dishIds.isEmpty else { return false }
            let record = library.events.first { $0.objectValue?["id"]?.stringValue == action.id }?.objectValue
            return (record?["recordedAtUtcMillis"]?.numberValue ?? record?["createdAtUtcMillis"]?.numberValue ?? .greatestFiniteMagnitude) >= created
        }.map(\.at).min()
    }
    func alignedGuides(_ cookId: String) -> [NativeAlignedGuide] {
        let state = guideState(cookId)
        guard state.visible else { return [] }
        return guides(cookId).filter { state.dishIds == nil || state.dishIds!.contains($0.id) }.map { item in
            var result = RecipeGuideProjection.aligned(item.guide, dishId: item.id, name: item.name, anchor: guideAnchor(cookId, dishId: item.id))
            for row in (item.guide.story.objectValue?["readings"]?.arrayValue ?? []).compactMap(\.objectValue) {
                let key = RecipeGuideProjection.channel(item.id, row), role = RecipeGuideProjection.role(row["measurementType"]?.stringValue ?? "food")
                if let mapped = state.channels[key], let id = row["id"]?.stringValue,
                   readings(for: cookId).contains(where: { $0.seriesId == mapped && RecipeGuideProjection.role($0.measurementType) == role && ($0.dishId == item.id || $0.dishId == nil && role != "food") }) {
                    result.matches["guide:\(item.id):\(id)"] = mapped
                }
            }
            return result
        }
    }
}
