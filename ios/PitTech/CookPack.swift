import Foundation
import ZIPFoundation

final class NativeCookPackProjection {
    private var identities: [String: String] = [:]
    func identifier(_ value: String) -> String { alias(.string(value)).stringValue! }
    private func alias(_ value: JSONValue?) -> JSONValue {
        guard let source = value?.stringValue, !source.isEmpty else { return .null }
        if identities[source] == nil { identities[source] = UUID().uuidString.lowercased() }
        return .string(identities[source]!)
    }
    private func known(_ value: JSONValue, _ keys: Set<String>) -> [String: JSONValue] { (value.objectValue ?? [:]).filter { keys.contains($0.key) } }
    private func measurements(_ values: [JSONValue]) -> [JSONValue] {
        values.map { value in
            var row = known(value, ["probeName", "measurementType", "value", "unit", "measuredAtUtcMillis", "timeZoneId", "source", "samplingIntervalMillis", "qualityStatus", "timestampBasis"])
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
            "connectionGaps": .array(entry.includeStory ? source["connectionGaps"]?.arrayValue ?? [] : []),
            "results": .array(entry.includeStory ? (source["results"]?.arrayValue ?? []).map { .object(known($0, ["type", "numericValue", "textValue", "unit"])) } : [])])
    }
    func recipe(_ entry: NativeSharingEntry, story: JSONValue) throws -> RecipeSnapshot {
        var result = try entry.snapshot
        result.observations = story.objectValue?["observations"]?.arrayValue ?? []
        result.smokingAnchorUtcMillis = story.objectValue?["anchorUtcMillis"]?.numberValue.map(Int64.init)
        if !entry.includeStory { result.rating = nil; result.cookAgain = nil; result.resultNotes = "" }
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

private struct NativeCookPackInputPhoto: Sendable {
    let sourceId: String
    let entryId: String
    let url: URL
    let caption: String
}
private struct NativeCookPackInput: Sendable {
    let draft: NativeSharingDocument
    let kind: String
    let updated: Double
    let photos: [NativeCookPackInputPhoto]
    let includeRecordedCook: Bool
    func render() throws -> Data {
        let selected = draft.entries.filter(\.included)
        guard !selected.isEmpty else { throw PitTechArchiveError.invalidArchive }
        guard !includeRecordedCook || draft.sourceHistory != nil && selected.allSatisfy({ $0.includeStory && $0.sourceDishId != nil }) else { throw PitTechArchiveError.invalidArchive }
        var derivatives: [String: Data] = [:]
        let packId = UUID().uuidString.lowercased(), projection = NativeCookPackProjection()
        let now = updated
        var portable = PitTechLibrary(), attachments: [String: Data] = [:], entries: [NativeSharingEntry] = []; var expandedPhotos: Int64 = 0; var heroPhotoId: String?
        portable.preferences = nil
        for (index, source) in selected.enumerated() {
            let recipeId = UUID().uuidString.lowercased(), revisionId = UUID().uuidString.lowercased()
            let story = projection.story(source), snapshot = try projection.recipe(source, story: story)
            portable.recipes.append(.object(["id": .string(recipeId), "name": .string(snapshot.name), "description": .string(snapshot.description), "tagsJson": .string(try recipeJSON(snapshot.tags)), "currentRevisionId": .string(revisionId), "createdAtUtcMillis": .number(now), "updatedAtUtcMillis": .number(now), "favorite": .bool(false)]))
            portable.recipeRevisions.append(.object(["id": .string(revisionId), "recipeId": .string(recipeId), "revisionNumber": .number(1), "snapshotSchemaVersion": .number(1), "snapshotJson": .string(try recipeJSON(snapshot)), "createdAtUtcMillis": .number(now), "changeNotes": .string("Shared Cook Pack")]))
            var entry = NativeSharingEntry(sourceRevisionId: revisionId, snapshotJson: try recipeJSON(snapshot), story: story, includeStory: source.includeStory)
            for sourceId in source.photoIds.filter({ $0 == draft.heroPhotoId }) + source.photoIds.filter({ $0 != draft.heroPhotoId }) {
                guard let photo = photos.first(where: { $0.sourceId == sourceId && $0.entryId == source.id }) else { throw CookNoteError.missingPhoto }
                try Task.checkCancellation()
                let derivative = try NativeCookPackPhoto.derivative(Data(contentsOf: photo.url))
                derivatives[sourceId] = derivative
                expandedPhotos += Int64(derivative.count) * 2
                guard expandedPhotos <= NativeArchiveLimits.cookPack.expanded, attachments.count + 2 <= NativeArchiveLimits.cookPack.entries - 3 else { throw PitTechArchiveError.tooLarge }
                let photoId = UUID().uuidString.lowercased(), assetId = UUID().uuidString.lowercased()
                attachments[photoId] = derivative; attachments[assetId] = derivative; entry.photoIds.append(photoId)
                if sourceId == draft.heroPhotoId { heroPhotoId = photoId }
                portable.companionPhotos.append(.object(["id": .string(photoId), "recordId": .string(packId), "archivePath": .string("attachments/\(photoId).jpg"), "mimeType": .string("image/jpeg"), "caption": .string(photo.caption), "dishIndex": .number(Double(index)), "action": .string(entry.id)]))
                portable.recipeAssets.append(.object(["id": .string(assetId), "recipeRevisionId": .string(revisionId), "relativeFilePath": .string("photos/\(assetId).jpg"), "archivePath": .string("attachments/\(assetId).jpg"), "mimeType": .string("image/jpeg"), "caption": .string(photo.caption), "createdAtUtcMillis": .number(now)]))
            }
            entries.append(entry)
        }
        if includeRecordedCook {
            let dishIds = Set(selected.compactMap(\.sourceDishId)), photoIds = Set(selected.flatMap(\.photoIds))
            let source = try NativeCookPackHistory.scope(draft.sourceHistory!, dishIds: dishIds, photoIds: photoIds)
            for photo in NativeCookPackHistory.rows(source, "photos") {
                guard let ownedId = photo["id"]?.stringValue, let data = derivatives[ownedId] else { throw CookNoteError.missingPhoto }
                attachments[projection.identifier(ownedId)] = data
            }
            let title: String
            if selected.count == 1 { title = try selected[0].snapshot.name } else { title = draft.title }
            let root = try NativeCookPackHistory.scope(source, dishIds: dishIds, photoIds: photoIds, title: title, alias: projection.identifier)
            let history = try JSONDecoder().decode(PitTechLibrary.self, from: JSONEncoder().encode(root))
            portable.cooks = history.cooks; portable.dishes = history.dishes; portable.ingredients = history.ingredients; portable.events = history.events
            portable.readings = history.readings; portable.targets = history.targets; portable.results = history.results; portable.probes = history.probes; portable.photos = history.photos
            portable.logReadingLinks = history.logReadingLinks; portable.lifecycles = history.lifecycles; portable.targetHistory = history.targetHistory
        }
        let kind = includeRecordedCook ? "cook" : selected.count == 1 && self.kind != "collection_revision" ? "recipe" : "collection"
        let document = NativeSharingDocument(title: draft.title, heroPhotoId: heroPhotoId, collectionId: packId, entries: entries, card: draft.card)
        portable.companionRecords = [.object(["id": .string(packId), "kind": .string(kind == "recipe" ? "recipe_reference" : "collection_revision"), "title": .string(draft.title), "payload": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)), "created": .number(now), "updated": .number(now)])]
        let metadata: JSONValue = .object(["contractVersion": .number(1), "id": .string(packId), "kind": .string(kind), "recipeRevisionIds": .array(portable.recipeRevisions.compactMap { $0.objectValue?["id"] }), "dishIds": .array(portable.dishes.compactMap { $0.objectValue?["id"] }), "includedStory": .bool(entries.contains { $0.includeStory }), "source": .null])
        try portable.validateCookExperience()
        let result = try NativeCookPackArchive.write(portable, metadata: metadata, attachments: attachments)
        _ = try CookStore.previewCookPack(result)
        return result
    }
}

enum NativeCookPackArchive {
    static func write(_ library: PitTechLibrary, metadata: JSONValue, attachments: [String: Data]) throws -> Data {
        let archive = try Archive(accessMode: .create)
        var expanded: Int64 = 0
        func add(_ path: String, _ data: Data) throws {
            expanded += Int64(data.count)
            guard Int64(data.count) <= NativeArchiveLimits.cookPack.entry, expanded <= NativeArchiveLimits.cookPack.expanded else { throw PitTechArchiveError.tooLarge }
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count), compressionMethod: .deflate) { position, size in data.subdata(in: Int(position)..<(Int(position) + size)) }
        }
        let manifest: JSONValue = .object(["format": .string("PitTech complete cook archive"), "archiveVersion": .number(Double(PitTechLibrary.archiveVersion)), "includesPreferences": .bool(false), "exportedAtUtc": .string(ISO8601DateFormatter().string(from: .now)), "cookPack": metadata])
        try add("manifest.json", JSONEncoder().encode(manifest))
        try add("README.txt", Data("PitTech Cook Pack. Selected recipes and optional recorded guidance; no accounts needed.\n".utf8))
        try add("data/pittech.json", JSONEncoder().encode(library))
        for (id, data) in attachments { try add("attachments/\(id).jpg", data) }
        guard let bytes = archive.data, Int64(bytes.count) <= NativeArchiveLimits.cookPack.compressed else { throw PitTechArchiveError.tooLarge }
        return bytes
    }
}

@MainActor extension CookStore {
    private func cookPackInput(_ id: String, includeRecordedCook: Bool) throws -> NativeCookPackInput {
        guard let row = sharingRecord(id) else { throw RecipeError.unavailable }
        let document = try sharingDocument(id)
        let photos = try sharingPhotos(id).map { photo -> NativeCookPackInputPhoto in
            guard let sourceId = photo["id"]?.stringValue, let entryId = photo["action"]?.stringValue, let path = photo["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
            return NativeCookPackInputPhoto(sourceId: sourceId, entryId: entryId, url: photoURL(path), caption: photo["caption"]?.stringValue ?? "")
        }
        return NativeCookPackInput(draft: document, kind: row["kind"]?.stringValue ?? "sharing_draft", updated: row["updated"]?.numberValue ?? 0, photos: photos, includeRecordedCook: includeRecordedCook)
    }
    func exportCookPack(_ id: String, includeRecordedCook: Bool = false) throws -> Data { try cookPackInput(id, includeRecordedCook: includeRecordedCook).render() }
    func exportCookPackAsync(_ id: String, includeRecordedCook: Bool = false) async throws -> Data {
        let input = try cookPackInput(id, includeRecordedCook: includeRecordedCook)
        let renderer = Task.detached(priority: .userInitiated) { try input.render() }
        return try await withTaskCancellationHandler(operation: { try await renderer.value }, onCancel: { renderer.cancel() })
    }
}
