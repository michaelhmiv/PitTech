import Foundation

struct NativeSharingEntry: Codable, Identifiable, Equatable {
    var id = UUID().uuidString.lowercased()
    var sourceDishId: String?
    var sourceRevisionId: String?
    var snapshotJson: String
    var story: JSONValue
    var included = true
    var includeStory = false
    var photoIds: [String] = []
    var snapshot: RecipeSnapshot { get throws { try RecipeSnapshot.decode(snapshotJson) } }
}
struct NativeSharingDocument: Codable, Equatable {
    var version = 1
    var title: String
    var sourceCookId: String?
    var identityMode = "anonymous"
    var heroPhotoId: String?
    var collectionId: String?
    var parentRevisionId: String?
    var entries: [NativeSharingEntry]
    func validate() throws {
        guard version == 1, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, title.count <= 160,
              (1...20).contains(entries.count), ["named", "anonymous"].contains(identityMode), Set(entries.map(\.id)).count == entries.count else { throw PitTechArchiveError.invalidArchive }
        for entry in entries {
            _ = try entry.snapshot
            guard entry.id.range(of: "^[A-Za-z0-9_-]{1,80}$", options: .regularExpression) != nil,
                  entry.photoIds.count <= 100, Set(entry.photoIds).count == entry.photoIds.count,
                  let story = entry.story.objectValue, story["version"]?.numberValue == 1,
                  let readings = story["readings"]?.arrayValue, readings.count <= 250000,
                  (story["attemptId"]?.stringValue.map { !$0.isEmpty } ?? readings.isEmpty) else { throw PitTechArchiveError.invalidArchive }
            for sample in readings {
                guard let row = sample.objectValue, row["value"]?.numberValue?.isFinite == true,
                      ["°F", "°C"].contains(row["unit"]?.stringValue ?? ""), (row["measuredAtUtcMillis"]?.numberValue ?? 0) > 0,
                      ["manual", "recorded"].contains(row["source"]?.stringValue ?? ""), (row["probeName"]?.stringValue?.count ?? 201) <= 200,
                      row["sourceDeviceId"] == nil, row["deviceId"] == nil, row["credentials"] == nil else { throw PitTechArchiveError.invalidArchive }
            }
        }
        if let heroPhotoId, !entries.contains(where: { $0.included && $0.photoIds.contains(heroPhotoId) }) { throw PitTechArchiveError.invalidArchive }
    }
    static func decode(_ value: JSONValue) throws -> Self {
        let result = try JSONDecoder().decode(Self.self, from: JSONEncoder().encode(value)); try result.validate(); return result
    }
}

extension PitTechLibrary {
    func validateSharingDocuments() throws {
        for row in companionRecords where ["sharing_draft", "collection_revision"].contains(row.objectValue?["kind"]?.stringValue ?? "") {
            guard let record = row.objectValue, record["cookId"]?.stringValue == nil, let id = record["id"]?.stringValue, let payload = record["payload"] else { throw PitTechArchiveError.invalidArchive }
            let document = try NativeSharingDocument.decode(payload)
            let owned = Set(companionPhotos.compactMap(\.objectValue).filter { $0["recordId"]?.stringValue == id }.compactMap { $0["id"]?.stringValue })
            guard document.entries.allSatisfy({ entry in
                let scoped = Set(companionPhotos.compactMap(\.objectValue).filter { $0["recordId"]?.stringValue == id && $0["action"]?.stringValue == entry.id }.compactMap { $0["id"]?.stringValue })
                return Set(entry.photoIds).isSubset(of: owned.intersection(scoped))
            }) else { throw PitTechArchiveError.invalidArchive }
        }
    }
}

@MainActor extension CookStore {
    func sharingRecord(_ id: String) -> [String: JSONValue]? {
        library.companionRecords.compactMap(\.objectValue).first { $0["id"]?.stringValue == id && ["sharing_draft", "collection_revision"].contains($0["kind"]?.stringValue ?? "") }
    }
    func sharingDocument(_ id: String) throws -> NativeSharingDocument {
        guard let payload = sharingRecord(id)?["payload"] else { throw RecipeError.unavailable }; return try NativeSharingDocument.decode(payload)
    }
    func sharingPhotos(_ id: String) -> [[String: JSONValue]] { library.companionPhotos.compactMap(\.objectValue).filter { $0["recordId"]?.stringValue == id } }
    @discardableResult func createSharingDraft(cookId: String, dishIds: Set<String>) throws -> String {
        guard let cook = library.cooks.first(where: { $0.id == cookId }), !dishIds.isEmpty, dishIds.isSubset(of: Set(dishRecords(cookId).map(\.id))) else { throw RecipeError.unavailable }
        let id = UUID().uuidString.lowercased(), prior = library; var copied: [String] = []
        do {
            let entries = try dishRecords(cookId).filter { dishIds.contains($0.id) }.enumerated().map { index, dish -> NativeSharingEntry in
                let actual = try extractRecipe(cookId: cookId, dishId: dish.id)
                var snapshot = try recipeAttempt(dish.id) ?? actual
                snapshot.draft = actual.draft; snapshot.observations = actual.observations; snapshot.targets = actual.targets
                snapshot.name = actual.name; snapshot.smokingAnchorUtcMillis = actual.smokingAnchorUtcMillis
                snapshot.resultNotes = actual.resultNotes; snapshot.rating = actual.rating; snapshot.cookAgain = actual.cookAgain
                let actions = library.actions(for: cookId).filter { $0.dishIds.isEmpty || $0.dishIds.contains(dish.id) }.sorted { $0.at < $1.at }
                let samples = library.readings.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && ($0["dishId"]?.stringValue == dish.id || $0["dishId"]?.stringValue == nil && ["pit", "pit_ambient", "setpoint", "grill_temperature", "pit_temperature", "pit_probe", "ambient_temperature"].contains($0["measurementType"]?.stringValue ?? "")) }
                let rows = samples.map { source -> JSONValue in
                    var row = source.filter { ["id", "probeId", "probeName", "measurementType", "value", "unit", "measuredAtUtcMillis", "samplingIntervalMillis", "qualityStatus"].contains($0.key) }
                    row["samplingIntervalMillis"] = row["samplingIntervalMillis"] ?? .number(15000); row["qualityStatus"] = row["qualityStatus"] ?? .string("valid")
                    row["source"] = .string(source["source"]?.stringValue == "manual" ? "manual" : "recorded")
                    return .object(row)
                }
                let results = library.results.compactMap(\.objectValue).filter { $0["dishId"]?.stringValue == dish.id }.map { row -> JSONValue in
                    .object(["type": row["resultType"] ?? .string(""), "numericValue": row["numericValue"] ?? .null, "textValue": row["textValue"] ?? .null, "unit": row["unit"] ?? .null])
                }
                let story: JSONValue = .object(["version": .number(1), "attemptId": .string(dish.id), "anchorUtcMillis": actions.first { $0.action == "food_on" }.map { .number($0.at) } ?? .null,
                    "observations": .array(actual.observations), "readings": .array(rows), "results": .array(results)])
                let entry = NativeSharingEntry(sourceDishId: dish.id, sourceRevisionId: recipeSource(dish.id)?["recipeRevisionId"]?.stringValue, snapshotJson: try recipeJSON(snapshot), story: story)
                let eventIds = Set(snapshot.observations.compactMap { $0.objectValue?["sourceEventId"]?.stringValue })
                for source in library.photos.compactMap(\.objectValue).filter({ $0["cookId"]?.stringValue == cookId && ($0["dishId"]?.stringValue == dish.id || eventIds.contains($0["eventId"]?.stringValue ?? "")) }) {
                    guard let path = source["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
                    let newId = UUID().uuidString.lowercased(), destination = "attachments/\(newId).\(URL(fileURLWithPath: path).pathExtension)"
                    try FileManager.default.createDirectory(at: photoURL(destination).deletingLastPathComponent(), withIntermediateDirectories: true)
                    try Data(contentsOf: photoURL(path)).write(to: photoURL(destination), options: .atomic); copied.append(destination)
                    library.companionPhotos.append(.object(["id": .string(newId), "recordId": .string(id), "archivePath": .string(destination), "mimeType": source["mimeType"] ?? .string("image/jpeg"), "caption": source["caption"] ?? .string(""), "dishIndex": .number(Double(index)), "action": .string(entry.id)]))
                }
                return entry
            }
            let document = NativeSharingDocument(title: String(cook.title.prefix(160)), sourceCookId: cookId, entries: entries)
            try document.validate(); let now = Date.now.timeIntervalSince1970 * 1000
            library.companionRecords.append(.object(["id": .string(id), "kind": .string("sharing_draft"), "title": .string(document.title), "payload": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)), "created": .number(now), "updated": .number(now)]))
            try library.validateSharingDocuments(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }; return id
        } catch { library = prior; copied.forEach { try? FileManager.default.removeItem(at: photoURL($0)) }; throw error }
    }
    func saveSharingDraft(_ id: String, document: NativeSharingDocument, expectedUpdated: Double) throws {
        guard let index = library.companionRecords.firstIndex(where: { $0.objectValue?["id"]?.stringValue == id }), let record = sharingRecord(id), record["kind"]?.stringValue == "sharing_draft",
              record["updated"]?.numberValue == expectedUpdated else { throw RecipeError.changed }
        let before = try sharingDocument(id); try document.validate()
        guard document.entries.allSatisfy({ entry in before.entries.contains { $0.id == entry.id && $0.story == entry.story } }) else { throw PitTechArchiveError.invalidArchive }
        let prior = library; var updated = record
        do {
            updated["title"] = .string(document.title); updated["payload"] = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)); updated["updated"] = .number(max(Date.now.timeIntervalSince1970 * 1000, expectedUpdated + 1))
            library.companionRecords[index] = .object(updated); try library.validateSharingDocuments(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }
        } catch { library = prior; throw error }
    }
    func discardSharingDraft(_ id: String) throws {
        guard sharingRecord(id)?["kind"]?.stringValue == "sharing_draft" else { throw RecipeError.unavailable }
        let prior = library, owned = sharingPhotos(id)
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == id }; library.companionPhotos.removeAll { $0.objectValue?["recordId"]?.stringValue == id }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
        for row in owned { if let path = row["archivePath"]?.stringValue { try? FileManager.default.removeItem(at: photoURL(path)) } }
    }

    @discardableResult func copySharingDocument(_ id: String, asCollection: Bool = false) throws -> String {
        guard let source = sharingRecord(id) else { throw RecipeError.unavailable }
        var document = try sharingDocument(id)
        if asCollection {
            document.entries = document.entries.filter(\.included)
            guard !document.entries.isEmpty else { throw PitTechArchiveError.invalidArchive }
            document.collectionId = document.collectionId ?? UUID().uuidString.lowercased()
        } else if source["kind"]?.stringValue == "collection_revision" { document.parentRevisionId = id }
        let newId = UUID().uuidString.lowercased(), prior = library; var copied: [String] = []; var mapping: [String: String] = [:]
        do {
            let entryIds = Set(document.entries.map(\.id))
            for asset in sharingPhotos(id) where entryIds.contains(asset["action"]?.stringValue ?? "") {
                guard let oldId = asset["id"]?.stringValue, let path = asset["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
                let assetId = UUID().uuidString.lowercased(), destination = "attachments/\(assetId).\(URL(fileURLWithPath: path).pathExtension)"
                try Data(contentsOf: photoURL(path)).write(to: photoURL(destination), options: .atomic); copied.append(destination); mapping[oldId] = assetId
                var copy = asset; copy["id"] = .string(assetId); copy["recordId"] = .string(newId); copy["archivePath"] = .string(destination); library.companionPhotos.append(.object(copy))
            }
            for index in document.entries.indices { document.entries[index].photoIds = try document.entries[index].photoIds.map { guard let newId = mapping[$0] else { throw CookNoteError.missingPhoto }; return newId } }
            document.heroPhotoId = document.heroPhotoId.flatMap { mapping[$0] }
            let now = Date.now.timeIntervalSince1970 * 1000
            library.companionRecords.append(.object(["id": .string(newId), "kind": .string(asCollection ? "collection_revision" : "sharing_draft"), "title": .string(document.title), "payload": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)), "created": .number(now), "updated": .number(now)]))
            try library.validateSharingDocuments(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }; return newId
        } catch { library = prior; copied.forEach { try? FileManager.default.removeItem(at: photoURL($0)) }; throw error }
    }
    func addRecipeToSharingDraft(_ id: String, revisionId: String, expectedUpdated: Double) throws {
        guard let index = library.companionRecords.firstIndex(where: { $0.objectValue?["id"]?.stringValue == id }), let source = sharingRecord(id), source["kind"]?.stringValue == "sharing_draft", source["updated"]?.numberValue == expectedUpdated,
              let revision = recipeRevision(revisionId) else { throw RecipeError.changed }
        var document = try sharingDocument(id); let snapshot = try recipeSnapshot(revisionId)
        let entry = NativeSharingEntry(sourceDishId: revision["sourceDishId"]?.stringValue, sourceRevisionId: revisionId, snapshotJson: try recipeJSON(snapshot), story: .object(["version": .number(1), "attemptId": revision["sourceDishId"] ?? .null, "anchorUtcMillis": snapshot.smokingAnchorUtcMillis.map { .number(Double($0)) } ?? .null, "observations": .array(snapshot.observations), "readings": .array([]), "results": .array([])]))
        let prior = library; var copied: [String] = []
        do {
            for row in recipeAssets(revisionId).compactMap(\.objectValue) {
                guard let path = row["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
                let photoId = UUID().uuidString.lowercased(), destination = "attachments/\(photoId).\(URL(fileURLWithPath: path).pathExtension)"
                try Data(contentsOf: photoURL(path)).write(to: photoURL(destination), options: .atomic); copied.append(destination)
                library.companionPhotos.append(.object(["id": .string(photoId), "recordId": .string(id), "archivePath": .string(destination), "mimeType": row["mimeType"] ?? .string("image/jpeg"), "caption": row["caption"] ?? .string(""), "dishIndex": .number(Double(document.entries.count)), "action": .string(entry.id)]))
            }
            document.entries.append(entry); try document.validate(); var updated = source
            updated["payload"] = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)); updated["updated"] = .number(max(Date.now.timeIntervalSince1970 * 1000, expectedUpdated + 1)); library.companionRecords[index] = .object(updated)
            try library.validateSharingDocuments(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }
        } catch { library = prior; copied.forEach { try? FileManager.default.removeItem(at: photoURL($0)) }; throw error }
    }
    func editSharingEntry(_ id: String, entryId: String, snapshot: RecipeSnapshot, addedPhotos: [Data], captions: [String], expectedUpdated: Double) throws {
        var document = try sharingDocument(id)
        guard let index = document.entries.firstIndex(where: { $0.id == entryId }), addedPhotos.count == captions.count else { throw RecipeError.unavailable }
        let prior = library; var copied: [String] = []
        do {
            for photoIndex in addedPhotos.indices {
                let photoId = UUID().uuidString.lowercased(), path = "attachments/\(photoId).jpg"
                try FileManager.default.createDirectory(at: photoURL(path).deletingLastPathComponent(), withIntermediateDirectories: true)
                try addedPhotos[photoIndex].write(to: photoURL(path), options: .atomic); copied.append(path)
                library.companionPhotos.append(.object(["id": .string(photoId), "recordId": .string(id), "archivePath": .string(path), "mimeType": .string("image/jpeg"), "caption": .string(captions[photoIndex]), "dishIndex": .number(Double(index)), "action": .string(entryId)]))
                document.entries[index].photoIds.append(photoId)
            }
            document.entries[index].snapshotJson = try recipeJSON(snapshot)
            try saveSharingDraft(id, document: document, expectedUpdated: expectedUpdated)
        } catch { library = prior; copied.forEach { try? FileManager.default.removeItem(at: photoURL($0)) }; throw error }
    }
}
