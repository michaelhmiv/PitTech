import Foundation

extension JSONValue {
    var arrayValue: [JSONValue]? { if case .array(let value) = self { value } else { nil } }
    var boolValue: Bool? { if case .bool(let value) = self { value } else { nil } }
}
func recipeJSON<T: Encodable>(_ value: T) throws -> String { String(data: try JSONEncoder().encode(value), encoding: .utf8)! }
enum RecipeError: LocalizedError, Equatable {
    case unavailable, changed
    var errorDescription: String? { self == .changed ? "This recipe changed while you were editing. Open its latest version and try again." : "This recipe or meal is unavailable." }
}

@MainActor extension CookStore {
    func recipeVersionLabel(_ id: String) -> String {
        guard let revision = recipeRevision(id), let number = revision["revisionNumber"]?.numberValue else { return "Version" }
        let siblings = library.recipeRevisions.compactMap(\.objectValue).filter { $0["recipeId"] == revision["recipeId"] && $0["revisionNumber"] == revision["revisionNumber"] }.compactMap { $0["id"]?.stringValue }.sorted()
        return "Version \(Int(number))" + (siblings.count > 1 ? " · branch \((siblings.firstIndex(of: id) ?? 0) + 1)" : "")
    }

    func recipe(_ id: String) -> [String: JSONValue]? { library.recipes.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue }
    func recipeRevision(_ id: String) -> [String: JSONValue]? { library.recipeRevisions.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue }
    func recipeSnapshot(_ revisionId: String) throws -> RecipeSnapshot {
        guard let raw = recipeRevision(revisionId)?["snapshotJson"]?.stringValue else { throw RecipeError.unavailable }
        return try RecipeSnapshot.decode(raw)
    }
    func recipeAssets(_ revisionId: String) -> [JSONValue] { library.recipeAssets.filter { $0.objectValue?["recipeRevisionId"]?.stringValue == revisionId } }
    func recipeSource(_ dishId: String) -> [String: JSONValue]? { library.dishRecipeSources.first { $0.objectValue?["dishId"]?.stringValue == dishId }?.objectValue }

    func extractRecipe(cookId: String, dishId: String, contextIds: Set<String> = []) throws -> RecipeSnapshot {
        guard let dish = library.dishes.first(where: { $0.objectValue?["id"]?.stringValue == dishId && $0.objectValue?["cookId"]?.stringValue == cookId })?.objectValue else { throw RecipeError.unavailable }
        let events = library.events.filter { $0.objectValue?["cookId"]?.stringValue == cookId }
        func metadata(_ row: JSONValue) -> CookNoteMetadata? { row.objectValue?["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) } }
        func owns(_ row: JSONValue) -> Bool { row.objectValue?["dishId"]?.stringValue == dishId || metadata(row)?.dishIds.contains(dishId) == true }
        let sharedActions = Set(library.actions(for: cookId).filter { $0.dishIds.isEmpty && ["food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done"].contains($0.action) }.map(\.id))
        let owned = events.filter { owns($0) || contextIds.contains($0.objectValue?["id"]?.stringValue ?? "") || sharedActions.contains($0.objectValue?["id"]?.stringValue ?? "") }
        let anchor = library.actions(for: cookId).filter { $0.action == "food_on" && ($0.dishIds.isEmpty || $0.dishIds.contains(dishId)) }.map { Double(Int64($0.at)) }.min()
        var ingredients = library.ingredients.filter { $0.objectValue?["dishId"]?.stringValue == dishId }.map { row -> JSONValue in
            let o = row.objectValue ?? [:]
            return .object(["name": o["name"] ?? .string(""), "stage": o["stage"] ?? .string("main_ingredient"), "brand": o["brand"] ?? .string(""),
                "amount": .string(o["amountText"]?.stringValue ?? o["amountValue"]?.numberValue.map { String($0) } ?? ""), "unit": .string(o["amountUnit"]?.stringValue ?? "count")])
        }
        for event in owned { for item in metadata(event)?.ingredients ?? [] { ingredients.append(try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(item))) } }
        var setup: [String: JSONValue] = ["name": dish["name"] ?? .string("Dish"), "foodType": dish["foodType"] ?? .string("Other"),
            "cut": dish["cut"] ?? .string(""), "weight": .string(dish["weightValue"]?.numberValue.map { String($0) } ?? ""), "weightUnit": dish["weightUnit"] ?? .string("lb"),
            "prep": dish["prepNotes"] ?? .string(""), "placement": dish["placement"] ?? .string(""), "grade": dish["gradeOrSource"] ?? .string(""),
            "thickness": dish["thicknessNotes"] ?? .string(""), "ingredients": .array(ingredients)]
        for key in ["startingCondition", "boneIn"] { if let value = dish[key] { setup[key] = value } }
        let selectedEvents = owned.sorted { ($0.objectValue?["occurredAtUtcMillis"]?.numberValue ?? 0) < ($1.objectValue?["occurredAtUtcMillis"]?.numberValue ?? 0) }
        var observations: [JSONValue] = [], instructions: [JSONValue] = []
        for row in selectedEvents {
            guard let o = row.objectValue, let id = o["id"]?.stringValue, let rawAt = o["occurredAtUtcMillis"]?.numberValue else { continue }
            let at = Double(Int64(rawAt))
            let m = metadata(row), action = library.actions(for: cookId).first { $0.id == id && CookNoteMetadata.actions.contains($0.action) }?.action
            let linkedIds = Set(library.logReadingLinks.filter { $0.objectValue?["eventId"]?.stringValue == id }.compactMap { $0.objectValue?["readingId"]?.stringValue })
            let temperatures = library.readings.compactMap { sample -> JSONValue? in
                guard let r = sample.objectValue, let readingId = r["id"]?.stringValue, linkedIds.contains(readingId), r["qualityStatus"]?.stringValue == "valid",
                      let value = r["value"]?.numberValue, value.isFinite,
                      r["dishId"]?.stringValue == dishId || r["dishId"]?.stringValue == nil && ["pit", "pit_ambient", "setpoint"].contains(r["measurementType"]?.stringValue ?? "") || r["dishId"]?.stringValue == nil && contextIds.contains(id),
                      let time = r["measuredAtUtcMillis"]?.numberValue else { return nil }
                let unit = r["unit"]?.stringValue ?? "°F"
                return .object(["sourceReadingId": .string(readingId), "probeId": r["probeId"] ?? .null, "probeName": r["probeName"] ?? .string("Food"),
                    "measurementType": r["measurementType"] ?? .string("food"), "value": .number(value), "unit": .string(unit.hasPrefix("°") ? unit : "°" + unit),
                    "measuredAtUtcMillis": .number(Double(Int64(time))), "timeZoneId": r["timeZoneId"] ?? .string("UTC"), "sourceDishId": r["dishId"] ?? .null])
            }
            var scopedContext: JSONValue = .null
            if let raw = o["temperatureContextJson"]?.stringValue, let context = try? JSONDecoder().decode([JSONValue].self, from: Data(raw.utf8)) {
                let kept = context.filter { value in
                    guard let c = value.objectValue else { return false }
                    let matches = library.readings.compactMap(\.objectValue).filter { $0["probeName"] == c["name"] && $0["value"] == c["value"] && $0["unit"] == c["unit"] && $0["measuredAtUtcMillis"] == c["observedAtUtcMillis"] && $0["source"] == c["source"] }
                    if matches.isEmpty { return o["dishId"]?.stringValue == dishId }
                    return matches.allSatisfy { $0["dishId"]?.stringValue == dishId || $0["dishId"]?.stringValue == nil && ["pit", "pit_ambient", "setpoint"].contains($0["measurementType"]?.stringValue ?? "") }
                }
                if !kept.isEmpty { scopedContext = .string(try recipeJSON(kept)) }
            }
            observations.append(.object(["sourceEventId": .string(id), "occurredAtUtcMillis": .number(at), "timeZoneId": o["timeZoneId"] ?? .string("UTC"),
                "title": o["title"] ?? .string("Note"), "details": o["details"] ?? .string(""), "action": action.map(JSONValue.string) ?? .null,
                "offsetMillis": anchor.map { .number(at - $0) } ?? .null, "temperatureContextJson": scopedContext, "recordedTemperatures": .array(temperatures),
                "noteMetadataJson": o["noteMetadataJson"] ?? .null, "sourceCookId": .string(cookId), "sourceDishId": .string(dishId)]))
            instructions.append(.object(["id": .string(UUID().uuidString.lowercased()), "title": o["title"] ?? .string("Note"), "details": o["details"] ?? .string(""),
                "sourceEventId": .string(id), "offsetMillis": anchor.map { .number(at - $0) } ?? .null, "method": m?.method.map(JSONValue.string) ?? .null,
                "durationMillis": m?.durationMillis.map(JSONValue.number) ?? .null]))
        }
        let targets = library.targets.filter { $0.objectValue?["dishId"]?.stringValue == dishId }.map { row -> JSONValue in let o = row.objectValue ?? [:]
            let unit = o["unit"]?.stringValue ?? "°F"
            return .object(["dish": .number(0), "type": o["targetType"] ?? .string("personal_finish"), "value": o["value"] ?? .number(0),
                "unit": .string(unit.hasPrefix("°") ? unit : "°" + unit), "explanation": o["explanation"] ?? .null]) }
        let resultNotes = library.results.filter { $0.objectValue?["dishId"]?.stringValue == dishId }.compactMap { $0.objectValue?["textValue"]?.stringValue }.joined(separator: "\n")
        let result = RecipeSnapshot(version: 1, name: dish["name"]?.stringValue ?? "Dish", description: "", tags: [],
            draft: .object(["title": dish["name"] ?? .string("Dish"), "dishes": .array([.object(setup)])]), instructions: instructions, observations: observations,
            targets: targets, resultNotes: resultNotes, rating: dishVerdict(cookId: cookId, dishId: dishId).rating, smokingAnchorUtcMillis: anchor.map { Int64($0) }, preferredPitTemperatureF: nil, servings: "", cookAgain: dishVerdict(cookId: cookId, dishId: dishId).again)
        try result.validate(); return result
    }

    @discardableResult func saveRecipe(_ snapshot: RecipeSnapshot, recipeId: String? = nil, expectedRevisionId: String? = nil,
        sourceCookId: String? = nil, sourceDishId: String? = nil, assetRevisionId: String? = nil, keptAssetIds: Set<String>? = nil,
        photoIds: Set<String> = [], captions: [String: String] = [:], addedPhotos: [Data] = [], addedPhotoCaptions: [String] = [],
        pinnedGuideStory: JSONValue? = nil, changeNotes: String = "") throws -> String {
        try snapshot.validate()
        let previous = recipeId.flatMap(recipe)
        guard recipeId == nil || previous != nil else { throw RecipeError.unavailable }
        guard previous == nil || previous?["currentRevisionId"]?.stringValue == expectedRevisionId else { throw RecipeError.changed }
        let priorRevision = previous?["currentRevisionId"]?.stringValue.flatMap(recipeRevision)
        let id = recipeId ?? UUID().uuidString.lowercased(), revisionId = UUID().uuidString.lowercased(), now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        var assets: [JSONValue] = [], written: [URL] = []
        let prior = library
        do {
            func copyPhoto(bytes: Data, mime: String, caption: String, sourceId: String?, ext: String = "jpg") throws {
                guard !bytes.isEmpty, bytes.count <= 16 * 1024 * 1024, mime.hasPrefix("image/") else { throw CookNoteError.missingPhoto }
                let assetId = UUID().uuidString.lowercased(), path = "attachments/\(assetId).\(ext)"
                let url = photoURL(path); try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                written.append(url); try bytes.write(to: url, options: .atomic)
                assets.append(.object(["id": .string(assetId), "recipeRevisionId": .string(revisionId), "relativeFilePath": .string("photos/\(assetId).\(ext)"),
                    "archivePath": .string(path), "mimeType": .string(mime), "caption": .string(caption), "sourcePhotoId": sourceId.map(JSONValue.string) ?? .null, "createdAtUtcMillis": .number(now)]))
            }
            if let revision = assetRevisionId ?? expectedRevisionId {
                for row in recipeAssets(revision) { guard let o = row.objectValue, let assetId = o["id"]?.stringValue, keptAssetIds == nil || keptAssetIds!.contains(assetId),
                    let path = o["archivePath"]?.stringValue else { continue }
                    if let sourceId = o["sourcePhotoId"]?.stringValue, photoIds.contains(sourceId) { continue }
                    try copyPhoto(bytes: Data(contentsOf: photoURL(path)), mime: o["mimeType"]?.stringValue ?? "image/jpeg", caption: captions[assetId] ?? o["caption"]?.stringValue ?? "", sourceId: o["sourcePhotoId"]?.stringValue, ext: URL(fileURLWithPath: path).pathExtension)
                }
            }
            for photoId in photoIds {
                guard let o = library.photos.first(where: { $0.objectValue?["id"]?.stringValue == photoId && $0.objectValue?["cookId"]?.stringValue == sourceCookId })?.objectValue,
                      let path = o["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
                try copyPhoto(bytes: Data(contentsOf: photoURL(path)), mime: o["mimeType"]?.stringValue ?? "image/jpeg", caption: captions[photoId] ?? o["caption"]?.stringValue ?? "", sourceId: photoId, ext: URL(fileURLWithPath: path).pathExtension)
            }
            for (index, bytes) in addedPhotos.enumerated() { try copyPhoto(bytes: bytes, mime: "image/jpeg", caption: addedPhotoCaptions.indices.contains(index) ? addedPhotoCaptions[index] : "", sourceId: nil) }
            var header = previous ?? ["id": .string(id), "createdAtUtcMillis": .number(now), "favorite": .bool(false)]
            header["name"] = .string(snapshot.name); header["description"] = .string(snapshot.description); header["tagsJson"] = .string(try recipeJSON(snapshot.tags))
            header["currentRevisionId"] = .string(revisionId); header["updatedAtUtcMillis"] = .number(now)
            let selectedParent = (assetRevisionId ?? expectedRevisionId).flatMap(recipeRevision).flatMap { $0["recipeId"]?.stringValue == id ? $0 : nil }
            let parent = previous == nil ? nil : selectedParent ?? priorRevision
            let revisionNumber = (library.recipeRevisions.compactMap(\.objectValue).filter { $0["recipeId"]?.stringValue == id }.compactMap { $0["revisionNumber"]?.numberValue }.max() ?? 0) + 1
            let revision: JSONValue = .object(["id": .string(revisionId), "recipeId": .string(id), "parentRevisionId": parent?["id"] ?? .null,
                "revisionNumber": .number(revisionNumber), "sourceCookId": sourceCookId.map(JSONValue.string) ?? priorRevision?["sourceCookId"] ?? .null,
                "sourceDishId": sourceDishId.map(JSONValue.string) ?? priorRevision?["sourceDishId"] ?? .null, "snapshotSchemaVersion": .number(1), "snapshotJson": .string(try recipeJSON(snapshot)),
                "createdAtUtcMillis": .number(now), "changeNotes": .string(changeNotes)])
            library.recipes.removeAll { $0.objectValue?["id"]?.stringValue == id }; library.recipes.append(.object(header))
            library.recipeRevisions.append(revision); library.recipeAssets.append(contentsOf: assets)
            try retainRecipeGuide(revisionId, snapshot: snapshot, inheritedRevisionId: assetRevisionId ?? priorRevision?["id"]?.stringValue, pinnedStory: pinnedGuideStory)
            try library.validateCookExperience(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }
            return id
        } catch { library = prior; written.forEach { try? FileManager.default.removeItem(at: $0) }; throw error }
    }
    func duplicateRecipe(_ id: String, name: String) throws {
        guard let header = recipe(id), let revisionId = header["currentRevisionId"]?.stringValue, let revision = recipeRevision(revisionId) else { throw RecipeError.unavailable }
        var snapshot = try recipeSnapshot(revisionId); snapshot.name = name
        var setup = snapshot.draft.objectValue ?? [:], dishes = setup["dishes"]?.arrayValue ?? []
        if var dish = dishes.first?.objectValue { dish["name"] = .string(name); dishes[0] = .object(dish); setup["dishes"] = .array(dishes); snapshot.draft = .object(setup) }
        try saveRecipe(snapshot, sourceCookId: revision["sourceCookId"]?.stringValue, sourceDishId: revision["sourceDishId"]?.stringValue, assetRevisionId: revisionId)
    }
    func setRecipeFlag(_ id: String, favorite: Bool? = nil, archived: Bool? = nil) throws {
        guard let index = library.recipes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == id }), var o = library.recipes[index].objectValue else { throw RecipeError.unavailable }
        let prior = library
        if let favorite { o["favorite"] = .bool(favorite) }
        if let archived { o["archivedAtUtcMillis"] = archived ? .number(Double(Int64(Date.now.timeIntervalSince1970 * 1000))) : .null }
        o["updatedAtUtcMillis"] = .number(Double(Int64(Date.now.timeIntervalSince1970 * 1000))); library.recipes[index] = .object(o); save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
}
