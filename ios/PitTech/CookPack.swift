import Foundation

final class NativeCookPackProjection {
    private var identities: [String: String] = [:]
    private func alias(_ value: JSONValue?) -> JSONValue {
        guard let source = value?.stringValue, !source.isEmpty else { return .null }
        if identities[source] == nil { identities[source] = UUID().uuidString.lowercased() }
        return .string(identities[source]!)
    }
    private func known(_ value: JSONValue, _ keys: Set<String>) -> [String: JSONValue] { (value.objectValue ?? [:]).filter { keys.contains($0.key) } }
    private func measurements(_ values: [JSONValue]) -> [JSONValue] {
        values.map { value in
            var row = known(value, ["probeName", "measurementType", "value", "unit", "measuredAtUtcMillis", "timeZoneId", "source", "samplingIntervalMillis", "qualityStatus"])
            for key in ["id", "sourceReadingId", "probeId"] { if let id = value.objectValue?[key] { row[key] = alias(id) } }
            return .object(row)
        }
    }
    private func observations(_ values: [JSONValue]) -> [JSONValue] {
        values.map { value in
            var row = known(value, ["occurredAtUtcMillis", "timeZoneId", "title", "details", "action", "offsetMillis"])
            row["sourceEventId"] = alias(value.objectValue?["sourceEventId"])
            row["recordedTemperatures"] = .array(measurements(value.objectValue?["recordedTemperatures"]?.arrayValue ?? []))
            return .object(row)
        }
    }
    func story(_ entry: NativeSharingEntry) -> JSONValue {
        let source = entry.story.objectValue ?? [:]
        return .object(["version": .number(1), "attemptId": entry.includeStory ? alias(source["attemptId"]) : .null,
            "anchorUtcMillis": entry.includeStory ? source["anchorUtcMillis"] ?? .null : .null,
            "observations": .array(entry.includeStory ? observations(source["observations"]?.arrayValue ?? []) : []),
            "readings": .array(entry.includeStory ? measurements(source["readings"]?.arrayValue ?? []) : []),
            "results": .array(entry.includeStory ? (source["results"]?.arrayValue ?? []).map { .object(known($0, ["type", "numericValue", "textValue", "unit"])) } : [])])
    }
    func recipe(_ entry: NativeSharingEntry, story: JSONValue) throws -> RecipeSnapshot {
        var result = try entry.snapshot
        result.observations = story.objectValue?["observations"]?.arrayValue ?? []
        result.smokingAnchorUtcMillis = story.objectValue?["anchorUtcMillis"]?.numberValue.map(Int64.init)
        result.instructions = result.instructions.map { value in
            var row = known(value, ["title", "details", "method", "offsetMillis", "durationMillis"])
            row["id"] = alias(value.objectValue?["id"]); row["sourceEventId"] = alias(value.objectValue?["sourceEventId"])
            return .object(row)
        }
        let dishes = result.draft.objectValue?["dishes"]?.arrayValue ?? []
        result.draft = .object(["title": .string(result.name), "dishes": .array(dishes.map { value in
            var row = known(value, ["name", "foodType", "cut", "weight", "weightUnit", "pieceCount", "startingCondition", "boneIn", "placement", "grade", "thickness", "prep"])
            row["ingredients"] = .array((value.objectValue?["ingredients"]?.arrayValue ?? []).map { .object(known($0, ["name", "stage", "brand", "amount", "unit"])) })
            return .object(row)
        })])
        try result.validate(); return result
    }
}

@MainActor extension CookStore {
    /// Public derivatives are held separately from private originals and never written over them.
    func exportCookPack(_ id: String) throws -> Data {
        guard let row = sharingRecord(id) else { throw RecipeError.unavailable }
        let draft = try sharingDocument(id), selected = draft.entries.filter(\.included)
        guard !selected.isEmpty else { throw PitTechArchiveError.invalidArchive }
        let packId = UUID().uuidString.lowercased(), projection = NativeCookPackProjection()
        let now = row["updated"]?.numberValue ?? 0
        var portable = PitTechLibrary(), attachments: [String: Data] = [:], entries: [NativeSharingEntry] = []
        for (index, source) in selected.enumerated() {
            let recipeId = UUID().uuidString.lowercased(), revisionId = UUID().uuidString.lowercased()
            let story = projection.story(source), snapshot = try projection.recipe(source, story: story)
            portable.recipes.append(.object(["id": .string(recipeId), "name": .string(snapshot.name), "description": .string(snapshot.description), "tagsJson": .string(try recipeJSON(snapshot.tags)), "currentRevisionId": .string(revisionId), "createdAtUtcMillis": .number(now), "updatedAtUtcMillis": .number(now), "favorite": .bool(false)]))
            portable.recipeRevisions.append(.object(["id": .string(revisionId), "recipeId": .string(recipeId), "revisionNumber": .number(1), "snapshotSchemaVersion": .number(1), "snapshotJson": .string(try recipeJSON(snapshot)), "createdAtUtcMillis": .number(now), "changeNotes": .string("Shared Cook Pack")]))
            var entry = NativeSharingEntry(sourceRevisionId: revisionId, snapshotJson: try recipeJSON(snapshot), story: story, includeStory: source.includeStory)
            for sourceId in source.photoIds {
                guard let photo = sharingPhotos(id).first(where: { $0["id"]?.stringValue == sourceId && $0["action"]?.stringValue == source.id }), let path = photo["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
                let derivative = try NativeCookPackPhoto.derivative(Data(contentsOf: photoURL(path)))
                let photoId = UUID().uuidString.lowercased(), assetId = UUID().uuidString.lowercased()
                attachments[photoId] = derivative; attachments[assetId] = derivative; entry.photoIds.append(photoId)
                portable.companionPhotos.append(.object(["id": .string(photoId), "recordId": .string(packId), "archivePath": .string("attachments/\(photoId).jpg"), "mimeType": .string("image/jpeg"), "caption": photo["caption"] ?? .string(""), "dishIndex": .number(Double(index)), "action": .string(entry.id)]))
                portable.recipeAssets.append(.object(["id": .string(assetId), "recipeRevisionId": .string(revisionId), "relativeFilePath": .string("photos/\(assetId).jpg"), "archivePath": .string("attachments/\(assetId).jpg"), "mimeType": .string("image/jpeg"), "caption": photo["caption"] ?? .string(""), "createdAtUtcMillis": .number(now)]))
            }
            entries.append(entry)
        }
        let kind = selected.count == 1 && row["kind"]?.stringValue != "collection_revision" ? "recipe" : "collection"
        let document = NativeSharingDocument(title: draft.title, collectionId: packId, entries: entries)
        portable.companionRecords = [.object(["id": .string(packId), "kind": .string(kind == "recipe" ? "recipe_reference" : "collection_revision"), "title": .string(draft.title), "payload": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)), "created": .number(now), "updated": .number(now)])]
        let metadata: JSONValue = .object(["contractVersion": .number(1), "id": .string(packId), "kind": .string(kind), "recipeRevisionIds": .array(portable.recipeRevisions.compactMap { $0.objectValue?["id"] }), "dishIds": .array([]), "includedStory": .bool(entries.contains { $0.includeStory }), "source": .null])
        try portable.validateCookExperience()
        let result = try exportArchive(sourceLibrary: portable, pack: metadata, attachments: attachments)
        _ = try Self.previewArchive(result, limits: .cookPack)
        return result
    }
}
