import Foundation

/** How a retained conflict is resolved. A restore replays loser/chosen content through the
 * same transactional apply as a merge; just dropping the copy still sends its explicit ID. */
struct NativeConflictResolution: Equatable {
    var resolvedId: String
    var restoreType: String?
    var restoreId: String?
    var restorePayload: [String: JSONValue]?
    var restoreAssetId: String?
    var newIdentity = false
}
/// One readable field of a conflicting version's content, for the PL-16
/// side-by-side preview. Derived from the payload only — presentation, never
/// a change to what sync stores or sends.
struct NativeConflictPreviewField: Equatable {
    var label: String
    var value: String
}
struct NativePrivateSyncConflictView: Equatable, Identifiable {
    var id: String
    var mineSummary: String
    var theirsSummary: String
    var reason: String
    var mineIsWinner: Bool
    var canKeepBoth: Bool
    var recordType: String
    var recordLabel: String
    var mineFields: [NativeConflictPreviewField]
    var theirsFields: [NativeConflictPreviewField]
}

/** Pure sync-envelope helpers. No storage, clock, account or network access. */
enum NativePrivateSyncProtocol {
    static let chunkBytes = 1024 * 1024
    static let maxPhotoBytes = 16 * 1024 * 1024
    static let maxSnapshotBytes = 32 * 1024 * 1024
    static let maxCounter: Int64 = 9_007_199_254_740_990
    private static let photoKinds: Set<String> = ["photos", "recipeAssets", "companionPhotos"]
    private static let noKeepBoth: Set<String> = ["logReadingLinks", "lifecycles"]
    static func keyField(_ type: String) -> String {
        switch type { case "dishRecipeSources": return "dishId"; case "lifecycles": return "cookId"; case "logReadingLinks": return "readingId"; default: return "id" }
    }
    private static func uuid(_ value: String) -> Bool { UUID(uuidString: value) != nil && value == value.lowercased() }

    /** Stamp the projection output with per-record mutation metadata. Rows whose portable
     * content still matches the acknowledged base keep their versions; every other row —
     * including explicit tombstones for base rows missing locally — advances to the
     * device's current logical counter. Phone time never enters a version. */
    static func stampLocal(_ projected: [NativePrivateProjectedRecord], base: [NativeSyncRecord], deviceId: String, counter: Int64) throws -> [NativeSyncRecord] {
        guard uuid(deviceId), (1...maxCounter).contains(counter) else { throw NativeSyncError.invalid }
        try NativePrivateSyncMerge.validateRecords(base)
        let old = Dictionary(uniqueKeysWithValues: base.map { ($0.key, $0) })
        var seen = Set<String>(), rows: [NativeSyncRecord] = []
        for row in projected {
            let key = row.recordType + "|" + row.recordId
            guard seen.insert(key).inserted else { throw NativeSyncError.invalid }
            let candidate = NativeSyncRecord(recordType: row.recordType, recordId: row.recordId, version: NativeSyncStamp(counter: counter, clientId: deviceId), deleted: false, payload: row.payload, assetIds: row.assetIds)
            if let prior = old[key], !prior.deleted, prior.sameContent(candidate) { rows.append(prior) } else { rows.append(candidate) }
        }
        for key in old.keys.sorted() where !seen.contains(key) {
            let prior = old[key]!
            if prior.deleted { rows.append(prior) }
            else { rows.append(NativeSyncRecord(recordType: prior.recordType, recordId: prior.recordId, version: NativeSyncStamp(counter: counter, clientId: deviceId), deleted: true, payload: nil, assetIds: [])) }
        }
        let result = rows.sorted { $0.key < $1.key }
        try NativePrivateSyncMerge.validateRecords(result)
        return result
    }

    struct PutBody: Equatable { var data: Data; var conflicts: [NativeSyncConflict]; var resolvedSent: [String] }
    /** Build the exact PUT body. Retained and freshly merged conflicts merge by ID; every
     * locally resolved conflict drops, but only IDs the server already holds are sent as
     * explicit resolutions. The body must be persisted before any network access. */
    static func putBody(baseVersion: Int64, mutationId: String, deviceId: String, records: [NativeSyncRecord], retained: [NativeSyncConflict], fresh: [NativeSyncConflict], resolved: [String], serverConflictIds: Set<String>) throws -> PutBody {
        guard (0...maxCounter).contains(baseVersion), uuid(mutationId), uuid(deviceId) else { throw NativeSyncError.invalid }
        try NativePrivateSyncMerge.validateRecords(records)
        var merged: [String: NativeSyncConflict] = [:]
        for conflict in retained + fresh { merged[conflict.id] = conflict }
        for id in resolved { merged.removeValue(forKey: id) }
        let conflicts = merged.values.sorted { $0.id < $1.id }
        try NativePrivateSyncMerge.validateConflicts(conflicts)
        let resolvedSent = resolved.filter { serverConflictIds.contains($0) }.sorted()
        var envelope: [String: JSONValue] = ["contractVersion": .number(1), "baseVersion": .number(Double(baseVersion)), "mutationId": .string(mutationId), "clientId": .string(deviceId),
            "records": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(records))),
            "conflicts": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(conflicts)))]
        if !resolvedSent.isEmpty { envelope["resolvedConflictIds"] = .array(resolvedSent.map(JSONValue.string)) }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(envelope)
        guard data.count <= maxSnapshotBytes else { throw NativeSyncError.invalid }
        return PutBody(data: data, conflicts: conflicts, resolvedSent: resolvedSent)
    }

    static func parseSnapshotGet(_ data: Data) throws -> (Int64, NativeSyncEnvelope?) {
        guard let raw = try JSONDecoder().decode(JSONValue.self, from: data).objectValue,
              let version = raw["version"]?.numberValue, version == version.rounded(), version >= 0, version <= Double(maxCounter) else { throw NativeSyncError.invalid }
        guard let envelopeValue = raw["envelope"], envelopeValue != .null else { return (Int64(version), nil) }
        let envelope = try JSONDecoder().decode(NativeSyncEnvelope.self, from: JSONEncoder().encode(envelopeValue))
        guard envelope.contractVersion == 1 else { throw NativeSyncError.invalid }
        try NativePrivateSyncMerge.validateRecords(envelope.records); try NativePrivateSyncMerge.validateConflicts(envelope.conflicts)
        return (Int64(version), envelope)
    }

    static func conflictSides(_ conflict: NativeSyncConflict, deviceId: String) -> (mine: NativeSyncRecord, theirs: NativeSyncRecord) {
        if conflict.winner.version.clientId == deviceId { return (conflict.winner, conflict.loser) }
        if conflict.loser.version.clientId == deviceId { return (conflict.loser, conflict.winner) }
        return (conflict.winner, conflict.loser)
    }

    /** Keep mine / Use theirs / Keep both, matching merge semantics. A choice that differs
     * from a live winner overwrites under the same identity; anything that would rewrite a
     * tombstone or an immutable row instead restores as a fresh identity. */
    static func resolve(_ conflict: NativeSyncConflict, choice: String, deviceId: String, freshId: String = UUID().uuidString.lowercased()) throws -> NativeConflictResolution {
        guard ["mine", "theirs", "both"].contains(choice), uuid(deviceId), uuid(freshId) else { throw NativeSyncError.invalid }
        try NativePrivateSyncMerge.validateConflicts([conflict])
        let winner = conflict.winner, loser = conflict.loser
        let sides = conflictSides(conflict, deviceId: deviceId)
        if choice == "both" {
            guard !noKeepBoth.contains(winner.recordType) else { throw NativeSyncError.invalid }
            return restore(conflict.id, loser, targetId: freshId, newIdentity: true)
        }
        let chosen = choice == "mine" ? sides.mine : sides.theirs
        if chosen.sameContent(winner) { return NativeConflictResolution(resolvedId: conflict.id) }
        if winner.deleted || conflict.reason == "immutable_identity" { return restore(conflict.id, chosen, targetId: freshId, newIdentity: true) }
        return restore(conflict.id, chosen, targetId: chosen.recordId, newIdentity: false)
    }

    private static func restore(_ id: String, _ chosen: NativeSyncRecord, targetId: String, newIdentity: Bool) -> NativeConflictResolution {
        var payload = chosen.payload ?? [:]
        payload[keyField(chosen.recordType)] = .string(targetId)
        if chosen.recordType == "companionRecords" {
            // iOS readers use either timestamp convention; keep both keys equal.
            if let created = payload["created"]?.numberValue { payload["createdAtUtcMillis"] = .number(created) }
            if let updated = payload["updated"]?.numberValue { payload["updatedAtUtcMillis"] = .number(updated) }
        }
        let asset = photoKinds.contains(chosen.recordType) ? chosen.assetIds.first : nil
        return NativeConflictResolution(resolvedId: id, restoreType: chosen.recordType, restoreId: targetId, restorePayload: payload, restoreAssetId: asset, newIdentity: newIdentity)
    }

    static func conflictView(_ conflict: NativeSyncConflict, deviceId: String) -> NativePrivateSyncConflictView {
        let sides = conflictSides(conflict, deviceId: deviceId)
        let reason = conflict.reason == "delete_edit" ? "Deleted on one device" : conflict.reason == "immutable_identity" ? "Measured history changed" : "Edited on two devices"
        let type = conflict.winner.recordType
        return NativePrivateSyncConflictView(id: conflict.id, mineSummary: summarize(sides.mine), theirsSummary: summarize(sides.theirs), reason: reason,
            mineIsWinner: sides.mine.sameContent(conflict.winner), canKeepBoth: !noKeepBoth.contains(type),
            recordType: type, recordLabel: label(type), mineFields: previewFields(sides.mine), theirsFields: previewFields(sides.theirs))
    }

    /// Record kinds whose identity is a link or a singleton, so two versions
    /// can never coexist: `logReadingLinks` is keyed by reading (a reading
    /// links to one note) and `lifecycles` by cook (a meal has one timing
    /// record). Every other projected kind is keyed by its own id and can
    /// keep both versions (the loser restores under a fresh identity).
    static func keepBothUnavailableTypes() -> Set<String> { noKeepBoth }

    // PL-16: the content preview behind the side-by-side sheet. Fields render
    // in a curated order with human labels; reference ids and bookkeeping
    // timestamps are omitted because they carry no judging value, and the
    // payload itself is never altered. Mirrors PrivateSyncProtocol.kt.
    private static let previewOrder = [
        "title", "name", "status", "eventType", "kind", "details", "description", "caption",
        "value", "numericValue", "weightValue", "amountValue", "amountText", "targetType", "resultType", "textValue",
        "changeNotes", "revisionNumber", "timingMode", "method", "targetTypeNote",
        "foodType", "cut", "gradeOrSource", "thicknessNotes", "startingCondition", "boneIn", "placement", "pieceCount", "prepNotes",
        "stage", "brand", "smokerName", "fuelType", "woodOrPelletBlend", "initialSetpointValue", "outdoorTemperatureValue",
        "weatherNotes", "windNotes", "measurementType", "probeName", "scope", "explanation", "sourceReference",
        "deviceName", "role", "firmwareVersion", "mimeType", "favorite", "tagsJson",
        "occurredAtUtcMillis", "measuredAtUtcMillis", "startedAtUtcMillis", "endedAtUtcMillis", "appliedAtUtcMillis",
        "capturedAtUtcMillis", "addedAtUtcMillis", "effectiveAt", "archivedAtUtcMillis",
        "notes", "snapshotJson", "itemsJson", "payload", "triggerConfig", "noteMetadataJson", "temperatureContextJson",
    ]
    private static let previewLabels: [String: String] = [
        "title": "Title", "name": "Name", "status": "Status", "eventType": "Type", "kind": "Kind",
        "details": "Details", "description": "Description", "caption": "Caption", "value": "Value",
        "numericValue": "Value", "weightValue": "Weight", "amountValue": "Amount", "amountText": "Amount",
        "targetType": "Target", "resultType": "Result", "textValue": "Text", "changeNotes": "Change notes",
        "revisionNumber": "Version", "timingMode": "Timing mode", "foodType": "Food", "cut": "Cut",
        "gradeOrSource": "Grade / source", "thicknessNotes": "Thickness", "startingCondition": "Starting condition",
        "boneIn": "Bone in", "placement": "Placement", "pieceCount": "Pieces", "prepNotes": "Prep notes",
        "stage": "Stage", "brand": "Brand", "smokerName": "Smoker", "fuelType": "Fuel",
        "woodOrPelletBlend": "Wood / pellets", "initialSetpointValue": "Starting setpoint",
        "outdoorTemperatureValue": "Outdoor temperature", "weatherNotes": "Weather", "windNotes": "Wind",
        "measurementType": "Measurement", "probeName": "Probe", "scope": "Scope", "explanation": "Why",
        "sourceReference": "Source", "deviceName": "Device name", "role": "Role", "firmwareVersion": "Firmware",
        "mimeType": "File type", "favorite": "Favorite", "tagsJson": "Tags",
        "occurredAtUtcMillis": "Occurred", "measuredAtUtcMillis": "Measured", "startedAtUtcMillis": "Started",
        "endedAtUtcMillis": "Ended", "appliedAtUtcMillis": "Applied", "capturedAtUtcMillis": "Captured",
        "addedAtUtcMillis": "Added", "effectiveAt": "Effective", "archivedAtUtcMillis": "Archived",
        "notes": "Notes", "snapshotJson": "Contents", "itemsJson": "Items", "payload": "Contents",
        "triggerConfig": "Trigger", "noteMetadataJson": "Note details", "temperatureContextJson": "Temperature context",
    ]
    private static let previewSkip: Set<String> = [
        "id", "cookId", "dishId", "recipeId", "recipeRevisionId", "parentRevisionId", "currentRevisionId",
        "sourceCookId", "sourceDishId", "sourcePhotoId", "sourceMilestoneId", "sourceRecipeRevisionId",
        "eventId", "readingId", "deviceId", "probeId", "recordId", "assetId",
        "createdAtUtcMillis", "updatedAtUtcMillis", "recordedAtUtcMillis", "created", "updated",
        "schemaVersion", "snapshotSchemaVersion", "source", "sourceDeviceId", "timeZoneId", "startedTimeZoneId", "timestampBasis",
    ]
    private static let previewTimestamps: Set<String> = [
        "occurredAtUtcMillis", "measuredAtUtcMillis", "startedAtUtcMillis", "endedAtUtcMillis", "appliedAtUtcMillis",
        "capturedAtUtcMillis", "addedAtUtcMillis", "effectiveAt", "archivedAtUtcMillis",
    ]
    private static let previewUnitFor: [String: String] = [
        "value": "unit", "numericValue": "unit", "weightValue": "weightUnit",
        "amountValue": "amountUnit", "initialSetpointValue": "initialSetpointUnit",
        "outdoorTemperatureValue": "outdoorTemperatureUnit",
    ]
    private static let previewTimeFormat: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "MMM d, yyyy · h:mm a 'UTC'"
        return formatter
    }()

    static func previewFields(_ row: NativeSyncRecord) -> [NativeConflictPreviewField] {
        if row.deleted {
            return [NativeConflictPreviewField(label: "Status", value: "Deleted"),
                    NativeConflictPreviewField(label: "Entry", value: label(row.recordType) + " " + String(row.recordId.prefix(8)))]
        }
        let payload = row.payload ?? [:]
        var consumed = Set<String>(), fields: [NativeConflictPreviewField] = []
        func render(_ key: String) {
            guard !consumed.contains(key), !previewSkip.contains(key), let raw = payload[key], raw != .null else { return }
            if case .string(let text) = raw, text.trimmingCharacters(in: .whitespaces).isEmpty { return }
            consumed.insert(key)
            var unit = ""
            if let unitKey = previewUnitFor[key], let unitValue = payload[unitKey]?.stringValue, !unitValue.isEmpty {
                consumed.insert(unitKey); unit = unitValue.trimmingCharacters(in: .whitespaces)
            }
            let value = previewValue(key, raw, unit: unit)
            if !value.isEmpty { fields.append(NativeConflictPreviewField(label: previewLabels[key] ?? previewLabel(key), value: value)) }
        }
        for key in previewOrder { render(key) }
        for key in payload.keys.sorted() { render(key) }
        if fields.isEmpty { fields.append(NativeConflictPreviewField(label: "Entry", value: label(row.recordType) + " " + String(row.recordId.prefix(8)))) }
        return Array(fields.prefix(18))
    }

    private static func previewLabel(_ key: String) -> String {
        var result = ""
        for character in key {
            if character.isUppercase { result += " " }
            result.append(character)
        }
        return String(result.prefix(1)).uppercased() + String(result.dropFirst())
    }

    private static func previewValue(_ key: String, _ raw: JSONValue, unit: String) -> String {
        if previewTimestamps.contains(key), let number = raw.numberValue {
            return previewTimeFormat.string(from: Date(timeIntervalSince1970: number / 1000))
        }
        switch raw {
        case .bool(let value): return value ? "Yes" : "No"
        case .number(let value):
            let base = key.hasSuffix("Millis") ? trimNumber(value) + " ms" : trimNumber(value)
            return unit.isEmpty ? base : base + " " + unit
        case .string(let value): return previewText(value)
        case .array(let values): return previewArray(values)
        case .object(let object): return previewObject(object)
        case .null: return ""
        }
    }

    private static func previewText(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        if trimmed.hasPrefix("[") || trimmed.hasPrefix("{"), let data = trimmed.data(using: .utf8),
           let value = try? JSONDecoder().decode(JSONValue.self, from: data) {
            switch value {
            case .array(let values): return previewArray(values)
            case .object(let object): return previewObject(object)
            default: break
            }
        }
        return String(trimmed.prefix(200))
    }

    private static func previewArray(_ values: [JSONValue]) -> String {
        let strings = values.compactMap(\.stringValue)
        if !strings.isEmpty, strings.count == values.count { return String(strings.joined(separator: ", ").prefix(200)) }
        return values.count == 1 ? "1 item" : "\(values.count) items"
    }

    private static func previewObject(_ object: [String: JSONValue]) -> String {
        for key in ["title", "name"] {
            if let value = object[key]?.stringValue?.trimmingCharacters(in: .whitespaces), !value.isEmpty { return String(value.prefix(200)) }
        }
        return object.count == 1 ? "1 detail" : "\(object.count) details"
    }

    static func summarize(_ row: NativeSyncRecord) -> String {
        if row.deleted { return label(row.recordType) + " " + String(row.recordId.prefix(8)) + " · deleted" }
        let payload = row.payload ?? [:]
        func text(_ keys: String...) -> String? { keys.compactMap { payload[$0]?.stringValue }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } }
        let title: String
        switch row.recordType {
        case "readings":
            if let value = payload["value"]?.numberValue { title = (trimNumber(value) + " " + (payload["unit"]?.stringValue ?? "")).trimmingCharacters(in: .whitespaces) } else { title = "" }
        case "recipeRevisions": title = text("changeNotes") ?? (payload["revisionNumber"]?.numberValue.map { "Version " + trimNumber($0) } ?? "")
        case "targets", "targetHistory":
            title = ((payload["targetType"]?.stringValue ?? "") + " " + (payload["value"]?.numberValue.map(trimNumber) ?? "") + " " + (payload["unit"]?.stringValue ?? "")).split(separator: " ").joined(separator: " ")
        case "results": title = payload["resultType"]?.stringValue ?? ""
        case "dishRecipeSources": title = "Recipe link"
        case "lifecycles": title = "Meal timing"
        case "logReadingLinks": title = "Note temperature"
        default: title = text("title", "name", "deviceName", "caption", "changeNotes") ?? ""
        }
        let name = title.isEmpty ? String(row.recordId.prefix(8)) : String(title.prefix(120))
        return ["readings", "targets", "targetHistory"].contains(row.recordType) ? label(row.recordType) + " " + name : label(row.recordType) + " \"" + name + "\""
    }

    private static func trimNumber(_ value: Double) -> String { value == value.rounded() && abs(value) < 9_007_199_254_740_992 ? String(Int64(value)) : String(value) }
    private static func label(_ type: String) -> String {
        switch type {
        case "cooks": return "Meal"; case "dishes": return "Dish"; case "ingredients": return "Ingredient"; case "events": return "Note"; case "readings": return "Reading"
        case "targets": return "Target"; case "results": return "Result"; case "devices": return "Device"; case "probes": return "Probe"; case "photos": return "Photo"
        case "recipes": return "Recipe"; case "recipeRevisions": return "Recipe version"; case "recipeAssets": return "Recipe photo"
        case "companionRecords": return "Saved document"; case "companionPhotos": return "Photo"; case "mealPresets": return "Meal preset"
        case "dishRecipeSources": return "Recipe link"; case "lifecycles": return "Meal timing"; case "targetHistory": return "Target history"
        case "logReadingLinks": return "Note temperature"; case "reminderRules": return "Reminder rule"; default: return "Entry"
        }
    }
}

struct NativeSyncEnvelope: Codable, Equatable {
    var contractVersion: Int
    var records: [NativeSyncRecord]
    var conflicts: [NativeSyncConflict]
}
