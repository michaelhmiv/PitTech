import Foundation

enum NativeCookPackContract {
    private static let keys: Set<String> = ["contractVersion", "id", "kind", "recipeRevisionIds", "dishIds", "includedStory", "source"]
    private static func validId(_ value: String?) -> Bool {
        guard let value, let id = UUID(uuidString: value) else { return false }
        return value.lowercased() == id.uuidString.lowercased()
    }
    private static func ids(_ value: JSONValue?) throws -> Set<String> {
        guard let values = value?.arrayValue, values.count <= 20 else { throw PitTechArchiveError.invalidArchive }
        let ids = values.compactMap(\.stringValue)
        guard ids.count == values.count, Set(ids).count == ids.count, ids.allSatisfy({ validId($0) }) else { throw PitTechArchiveError.invalidArchive }
        return Set(ids)
    }
    static func validate(_ metadata: JSONValue, library: PitTechLibrary, root: JSONValue) throws {
        guard let item = metadata.objectValue, Set(item.keys) == keys, item["contractVersion"]?.numberValue == 1, validId(item["id"]?.stringValue),
              let kind = item["kind"]?.stringValue, ["recipe", "collection", "cook"].contains(kind), item["includedStory"]?.boolValue != nil,
              library.preferences == nil, library.devices.isEmpty, library.probes.allSatisfy({ $0.objectValue?["deviceId"]?.stringValue == nil && validId($0.objectValue?["id"]?.stringValue) }), library.recordings.isEmpty, library.probeAssignments.isEmpty,
              library.reminders.isEmpty, library.activityTimers.isEmpty, library.reminderRules.isEmpty, library.reminderOccurrences.isEmpty,
              (kind == "cook" ? 0...20 : 1...20).contains(library.recipes.count), kind != "recipe" || library.recipes.count == 1, kind == "cook" || library.cooks.isEmpty,
              library.companionRecords.allSatisfy({ ["recipe_reference", "collection_revision"].contains($0.objectValue?["kind"]?.stringValue ?? "") && $0.objectValue?["cookId"]?.stringValue == nil }) else { throw PitTechArchiveError.invalidArchive }
        guard try ids(item["recipeRevisionIds"]) == Set(library.recipeRevisions.compactMap { $0.objectValue?["id"]?.stringValue }),
              try ids(item["dishIds"]) == Set(library.dishes.compactMap { $0.objectValue?["id"]?.stringValue }) else { throw PitTechArchiveError.invalidArchive }
        if kind == "cook" {
            guard library.cooks.count == 1, (1...20).contains(library.dishes.count), library.cooks.allSatisfy({ $0.status == "completed" && $0.endedAtUtcMillis != nil }),
                  item["includedStory"]?.boolValue == true else { throw PitTechArchiveError.invalidArchive }
            guard library.cooks.allSatisfy({ cook in cook.endedAtUtcMillis.map { $0 >= cook.startedAtUtcMillis } == true }) else { throw PitTechArchiveError.invalidArchive }
        } else {
            guard library.cooks.isEmpty, [library.dishes, library.ingredients, library.events, library.readings, library.targets, library.results, library.probes, library.photos, library.logReadingLinks, library.lifecycles, library.targetHistory].allSatisfy(\.isEmpty) else { throw PitTechArchiveError.invalidArchive }
        }
        let documents = try library.companionRecords.map { try NativeSharingDocument.decode($0.objectValue?["payload"] ?? .null) }
        guard item["includedStory"]?.boolValue == (kind == "cook" || documents.contains { $0.entries.contains { $0.includeStory } }) else { throw PitTechArchiveError.invalidArchive }
        for document in documents {
            guard document.sourceCookId == nil, document.sourceHistory == nil else { throw PitTechArchiveError.invalidArchive }
            for entry in document.entries {
                guard entry.sourceDishId == nil else { throw PitTechArchiveError.invalidArchive }
                if !entry.includeStory {
                    let story = entry.story.objectValue ?? [:], snapshot = try entry.snapshot
                    guard story["attemptId"]?.stringValue == nil, story["anchorUtcMillis"]?.numberValue == nil,
                          ["observations", "readings", "results"].allSatisfy({ story[$0]?.arrayValue?.isEmpty == true }),
                          snapshot.rating == nil, snapshot.cookAgain == nil, snapshot.resultNotes.isEmpty else { throw PitTechArchiveError.invalidArchive }
                }
            }
        }
        if item["source"] != .null {
            guard let source = item["source"]?.objectValue, Set(source.keys).isSubset(of: ["itemId", "revisionId", "displayName", "removed"]),
                  validId(source["itemId"]?.stringValue), validId(source["revisionId"]?.stringValue), source["removed"]?.boolValue != nil,
                  (source["displayName"]?.stringValue?.count ?? 0) <= 160 else { throw PitTechArchiveError.invalidArchive }
        }
        try rejectPrivateFields(root)
    }
    private static func rejectPrivateFields(_ value: JSONValue) throws {
        switch value {
        case .object(let rows):
            for (key, child) in rows {
                guard child == .null || !["credentials", "credential", "password", "accessToken", "refreshToken", "secret", "apiKey", "sourceDeviceId", "deviceId", "controllerKey", "macAddress", "serialNumber"].contains(key) else { throw PitTechArchiveError.invalidArchive }
                let string = child.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if key.hasSuffix("Json"), string.hasPrefix("{") || string.hasPrefix("["), let nested = try? JSONDecoder().decode(JSONValue.self, from: Data(string.utf8)) { try rejectPrivateFields(nested) }
                else { try rejectPrivateFields(child) }
            }
        case .array(let rows): try rows.forEach(rejectPrivateFields)
        default: break
        }
    }
}
