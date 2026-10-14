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
              library.preferences == nil, library.devices.isEmpty, library.probes.isEmpty, library.recordings.isEmpty, library.probeAssignments.isEmpty,
              library.reminders.isEmpty, library.activityTimers.isEmpty, library.reminderRules.isEmpty, library.reminderOccurrences.isEmpty,
              (1...20).contains(library.recipes.count), kind != "recipe" || library.recipes.count == 1, kind == "cook" || library.cooks.isEmpty,
              library.companionRecords.allSatisfy({ ["recipe_reference", "collection_revision"].contains($0.objectValue?["kind"]?.stringValue ?? "") && $0.objectValue?["cookId"]?.stringValue == nil }) else { throw PitTechArchiveError.invalidArchive }
        guard try ids(item["recipeRevisionIds"]) == Set(library.recipeRevisions.compactMap { $0.objectValue?["id"]?.stringValue }),
              try ids(item["dishIds"]) == Set(library.dishes.compactMap { $0.objectValue?["id"]?.stringValue }) else { throw PitTechArchiveError.invalidArchive }
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
                if key.hasSuffix("Json"), let string = child.stringValue, string.hasPrefix("{") || string.hasPrefix("["), let nested = try? JSONDecoder().decode(JSONValue.self, from: Data(string.utf8)) { try rejectPrivateFields(nested) }
                else { try rejectPrivateFields(child) }
            }
        case .array(let rows): try rows.forEach(rejectPrivateFields)
        default: break
        }
    }
}
