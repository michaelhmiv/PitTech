import Foundation

/// Private editing metadata. It never enters a library archive or public payload.
struct NativePublicationEditContext: Codable, Sendable {
    var receipt: NativePublicationReceipt
    let rootSource: JSONValue
    let memberIds: [String: String]
    let memberSources: [String: JSONValue]
    let instructionIds: [String: String]
    let assetIds: [String: String]
    let measuredAttemptId: String?
    var memberMethods: [String: String]? = nil
    func validate() throws {
        try receipt.validate()
        guard receipt.kind != nil, (1...20).contains(memberIds.count), Set(memberIds.keys) == Set(memberSources.keys), Set(memberIds.values).count == memberIds.count,
              instructionIds.count <= 2000, assetIds.count <= 600, Set(assetIds.values).count == assetIds.count,
              (Array(memberIds.keys) + Array(memberIds.values) + Array(instructionIds.keys) + Array(instructionIds.values) + Array(assetIds.keys) + Array(assetIds.values)).allSatisfy({ UUID(uuidString: $0) != nil }),
              measuredAttemptId.map({ UUID(uuidString: $0) != nil }) ?? true, receipt.kind != "attempt" || measuredAttemptId != nil else { throw NativePublicationError.invalid }
        guard (memberMethods ?? [:]).allSatisfy({ memberIds[$0.key] != nil && $0.value.utf16.count <= 160 }) else { throw NativePublicationError.invalid }
        for source in [rootSource] + Array(memberSources.values) { _ = try Self.source(source, excluding: receipt.id) }
    }
    static func source(_ value: JSONValue, excluding: String) throws -> JSONValue {
        if value == .null { return .null }
        guard let row = value.objectValue, let item = row["itemId"]?.stringValue, UUID(uuidString: item) != nil, item != excluding,
              let revision = row["revisionId"]?.stringValue, UUID(uuidString: revision) != nil, let removed = row["removed"]?.boolValue else { throw NativePublicationError.invalid }
        return .object(["itemId": .string(item), "revisionId": .string(revision), "removed": .bool(removed)])
    }
    func apply(_ prepared: NativePreparedPublication, document: NativeSharingDocument, kind: String) throws -> NativePreparedPublication {
        try validate(); guard ["recipe", "collection", "attempt"].contains(kind) else { throw NativePublicationError.invalid }; let selected = document.entries.filter(\.included)
        guard var payload = prepared.payload.objectValue, let rows = payload["recipes"]?.arrayValue, rows.count == selected.count else { throw NativePublicationError.invalid }
        var recipes: [JSONValue] = [], photos: [String: Data] = [:]
        for (index, entry) in selected.enumerated() {
            guard var recipe = rows[index].objectValue, recipe["id"]?.stringValue == NativePublicationProjection.id("recipe:" + entry.id) else { throw NativePublicationError.invalid }
            if let original = memberIds[entry.id] { recipe["id"] = .string(original); recipe["source"] = memberSources[entry.id] ?? .null; recipe["method"] = (memberMethods?[entry.id]).map(JSONValue.string) ?? .null }
            if recipe["source"]?.objectValue?["itemId"]?.stringValue == receipt.id { throw NativePublicationError.invalid }
            recipe["instructions"] = .array(try (recipe["instructions"]?.arrayValue ?? []).map { value in
                guard var step = value.objectValue, let id = step["id"]?.stringValue else { throw NativePublicationError.invalid }
                if let original = instructionIds[id] { step["id"] = .string(original) }; return .object(step)
            })
            recipe["assets"] = .array(try (recipe["assets"]?.arrayValue ?? []).map { value in
                guard var asset = value.objectValue, let id = asset["id"]?.stringValue, let bytes = prepared.photos[id] else { throw NativePublicationError.invalid }
                let target = assetIds[id] ?? id; guard photos[target] == nil else { throw NativePublicationError.invalid }
                photos[target] = bytes; asset["id"] = .string(target); asset["path"] = .string("attachments/" + target + ".jpg"); return .object(asset)
            })
            recipes.append(.object(recipe))
        }
        payload["recipes"] = .array(recipes); payload["source"] = rootSource; payload["kind"] = .string(kind)
        if kind == "attempt" {
            guard receipt.kind == "attempt", recipes.count == 1, let attempt = measuredAttemptId,
                  recipes[0].objectValue?["story"]?.objectValue?["attemptId"]?.stringValue == attempt else { throw NativePublicationError.invalid }
            payload["measuredAttemptId"] = .string(attempt)
        } else { payload["measuredAttemptId"] = .null }
        if let hero = payload["heroAssetId"]?.stringValue { payload["heroAssetId"] = .string(assetIds[hero] ?? hero) }
        return try NativePublicationProjection.package(.object(payload), photos: photos)
    }
}
struct NativeOwnedDraftPhoto: Sendable { let id: String; let entryId: String; let caption: String; let bytes: Data }
struct NativeOwnedPublicationDraft: Sendable {
    let id: String
    let document: NativeSharingDocument
    let photos: [NativeOwnedDraftPhoto]
    let context: NativePublicationEditContext
}
enum NativeOwnedPublication {
    static func draft(_ prepared: NativePreparedPublication, receipt input: NativePublicationReceipt) throws -> NativeOwnedPublicationDraft {
        try input.validate()
        guard let payload = prepared.payload.objectValue, let title = payload["title"]?.stringValue, let kind = payload["kind"]?.stringValue,
              let recipes = payload["recipes"]?.arrayValue, (1...20).contains(recipes.count), let identity = payload["identityMode"]?.stringValue else { throw NativePublicationError.invalid }
        var receipt = input; receipt.kind = kind; try receipt.validate()
        var entries: [NativeSharingEntry] = [], photos: [NativeOwnedDraftPhoto] = [], members: [String: String] = [:], sources: [String: JSONValue] = [:], steps: [String: String] = [:], assets: [String: String] = [:]
        var methods: [String: String] = [:]
        var hero: String?
        for value in recipes {
            guard let recipe = value.objectValue, let publicId = recipe["id"]?.stringValue, UUID(uuidString: publicId) != nil else { throw NativePublicationError.invalid }
            let entryId = UUID().uuidString.lowercased(), story = recipe["story"]?.objectValue, origin = 946_684_800_000.0
            let anchor = story?["foodOnOffsetMillis"]?.numberValue.map { origin + $0 }
            var observations = (story?["milestones"]?.arrayValue ?? []).compactMap(\.objectValue).map { row -> JSONValue in
                let at = origin + (row["offsetMillis"]?.numberValue ?? 0)
                let label = (row["text"]?.stringValue).flatMap { $0.isEmpty ? nil : $0 } ?? row["action"]?.stringValue ?? "Recorded action"
                return .object(["sourceEventId": .string(UUID().uuidString.lowercased()), "occurredAtUtcMillis": .number(at), "timeZoneId": .string("UTC"), "title": .string(label), "details": row["text"] ?? .string(""), "action": row["action"] ?? .null, "offsetMillis": anchor.map { .number(at - $0) } ?? .null, "recordedTemperatures": .array([])])
            }
            for (key, action, label) in [("foodOnOffsetMillis", "food_on", "Food on"), ("foodOffOffsetMillis", "remove", "Food off")] {
                if let offset = story?[key]?.numberValue, !observations.contains(where: { $0.objectValue?["action"]?.stringValue == action }) {
                    let at = origin + offset
                    observations.append(.object(["sourceEventId": .string(UUID().uuidString.lowercased()), "occurredAtUtcMillis": .number(at), "timeZoneId": .string("UTC"), "title": .string(label), "details": .string(""), "action": .string(action), "offsetMillis": anchor.map { .number(at - $0) } ?? .null, "recordedTemperatures": .array([])]))
                }
            }
            let channels = (story?["channels"]?.arrayValue ?? []).compactMap(\.objectValue)
            var probes: [String: String] = [:]
            for channel in channels { if let id = channel["id"]?.stringValue { probes[id] = UUID().uuidString.lowercased() } }
            let readings = try (story?["measurements"]?.arrayValue ?? []).map { value -> JSONValue in
                guard let row = value.objectValue, let channelId = row["channelId"]?.stringValue, let probe = probes[channelId], let channel = channels.first(where: { $0["id"]?.stringValue == channelId }), let celsius = row["valueCelsius"]?.numberValue, let offset = row["offsetMillis"]?.numberValue else { throw NativePublicationError.invalid }
                return .object(["id": .string(UUID().uuidString.lowercased()), "probeId": .string(probe), "probeName": channel["label"] ?? .string("Recorded channel"), "measurementType": row["role"] ?? .string("food"), "value": .number(celsius), "unit": .string("°C"), "measuredAtUtcMillis": .number(origin + offset), "timeZoneId": .string("UTC"), "source": .string(row["quality"]?.stringValue == "manual" ? "manual" : "recorded"), "qualityStatus": .string(row["quality"]?.stringValue == "recovered" ? "recovered" : "valid"), "samplingIntervalMillis": .number(0)])
            }
            let instructions = try (recipe["instructions"]?.arrayValue ?? []).map { value -> JSONValue in
                guard let row = value.objectValue, let original = row["id"]?.stringValue else { throw NativePublicationError.invalid }
                let id = UUID().uuidString.lowercased(); steps[NativePublicationProjection.id("instruction:" + id)] = original
                return .object(["id": .string(id), "title": row["title"] ?? .string("Step"), "details": row["details"] ?? .string(""), "method": row["method"] ?? .null, "action": row["action"] ?? .null, "offsetMillis": row["suggestedOffsetMillis"] ?? .null, "durationMillis": .null, "sourceEventId": .null])
            }
            let ingredients = (recipe["ingredients"]?.arrayValue ?? []).compactMap(\.objectValue).map { row -> JSONValue in .object(["name": row["name"] ?? .string(""), "stage": .string("main_ingredient"), "brand": .string(""), "amount": .string(""), "unit": row["amount"] ?? .string("")]) }
            let name = recipe["title"]?.stringValue ?? "Recipe", verdict = story?["verdict"]?.objectValue ?? [:]
            let setup: JSONValue = .object(["title": .string(name), "dishes": .array([.object(["name": .string(name), "foodType": recipe["foodType"] ?? .string("other"), "cut": recipe["cut"]?.stringValue.map(JSONValue.string) ?? .string(""), "weight": .string(recipe["weightGrams"]?.numberValue.map { String($0 / 1000) } ?? ""), "weightUnit": .string("kg"), "prep": recipe["method"]?.stringValue.map(JSONValue.string) ?? .string(""), "ingredients": .array(ingredients)])])])
            let snapshot = RecipeSnapshot(version: 1, name: name, description: recipe["description"]?.stringValue ?? "", tags: (recipe["tags"]?.arrayValue ?? []).compactMap(\.stringValue), draft: setup, instructions: instructions, observations: observations, targets: [], resultNotes: verdict["notes"]?.stringValue ?? "", rating: verdict["rating"]?.numberValue, smokingAnchorUtcMillis: anchor.map(Int64.init), preferredPitTemperatureF: nil, servings: "", cookAgain: verdict["cookAgain"]?.boolValue, publicSource: PublicRecipeSource(itemId: receipt.id, revisionId: receipt.revisionId, displayName: "a PitTech cook"))
            try snapshot.validate()
            let gaps = (story?["gaps"]?.arrayValue ?? []).compactMap(\.objectValue).flatMap { row -> [JSONValue] in ["fromMillis", "toMillis"].compactMap { row[$0]?.numberValue.map { .number(origin + $0) } } }
            let frozen: JSONValue = .object(["version": .number(1), "attemptId": story?["attemptId"] ?? .null, "anchorUtcMillis": anchor.map(JSONValue.number) ?? .null, "observations": .array(observations), "readings": .array(readings), "connectionGaps": .array(gaps), "results": .array([]), "publicStory": recipe["story"] ?? .null])
            var photoIds: [String] = []
            for value in recipe["assets"]?.arrayValue ?? [] {
                guard let asset = value.objectValue, let original = asset["id"]?.stringValue, let bytes = prepared.photos[original] else { throw NativePublicationError.invalid }
                let id = UUID().uuidString.lowercased(); photos.append(NativeOwnedDraftPhoto(id: id, entryId: entryId, caption: asset["caption"]?.stringValue ?? "", bytes: try NativeCookPackPhoto.derivative(bytes))); photoIds.append(id)
                assets[NativePublicationProjection.id("photo:" + id)] = original
                if payload["heroAssetId"]?.stringValue == original { hero = id }
            }
            entries.append(NativeSharingEntry(id: entryId, snapshotJson: try recipeJSON(snapshot), story: frozen, included: true, includeStory: story != nil, photoIds: photoIds))
            if let method = recipe["method"]?.stringValue { methods[entryId] = method }
            members[entryId] = publicId; sources[entryId] = try NativePublicationEditContext.source(recipe["source"] ?? .null, excluding: receipt.id)
        }
        let document = NativeSharingDocument(title: title, identityMode: identity, heroPhotoId: hero, entries: entries); try document.validate()
        let context = NativePublicationEditContext(receipt: receipt, rootSource: try NativePublicationEditContext.source(payload["source"] ?? .null, excluding: receipt.id), memberIds: members, memberSources: sources, instructionIds: steps, assetIds: assets, measuredAttemptId: payload["measuredAttemptId"]?.stringValue, memberMethods: methods); try context.validate()
        return NativeOwnedPublicationDraft(id: UUID().uuidString.lowercased(), document: document, photos: photos, context: context)
    }
}

@MainActor extension CookStore {
    func saveOwnedPublicationDraft(_ draft: NativeOwnedPublicationDraft) throws {
        guard sharingRecord(draft.id) == nil else { throw NativePublicationError.invalid }; try draft.document.validate()
        let prior = library; var written: [URL] = []
        do {
            for photo in draft.photos {
                let path = "attachments/" + photo.id + ".jpg", file = photoURL(path)
                guard !FileManager.default.fileExists(atPath: file.path) else { throw NativePublicationError.invalid }
                try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
                try photo.bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); written.append(file)
                library.companionPhotos.append(.object(["id": .string(photo.id), "recordId": .string(draft.id), "archivePath": .string(path), "mimeType": .string("image/jpeg"), "caption": .string(photo.caption), "dishIndex": .number(Double(draft.document.entries.firstIndex(where: { $0.id == photo.entryId }) ?? 0)), "action": .string(photo.entryId)]))
            }
            let now = Date().timeIntervalSince1970 * 1000
            library.companionRecords.append(.object(["id": .string(draft.id), "kind": .string("sharing_draft"), "title": .string(draft.document.title), "payload": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(draft.document)), "created": .number(now), "updated": .number(now)]))
            try library.validateSharingDocuments(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }
        } catch { library = prior; written.forEach { try? FileManager.default.removeItem(at: $0) }; throw error }
    }
}
