import Foundation

/// The draft is the same portable one-dish setup used by Android; unknown optional fields survive edits.
struct RecipeSnapshot: Codable {
    var version: Int
    var name: String
    var description: String
    var tags: [String]
    var draft: JSONValue
    var instructions: [JSONValue]
    var observations: [JSONValue]
    var targets: [JSONValue]
    var resultNotes: String
    var rating: Double?
    var smokingAnchorUtcMillis: Int64?
    var preferredPitTemperatureF: Double?
    var servings: String

    static func decode(_ raw: String) throws -> RecipeSnapshot {
        guard let bytes = raw.data(using: .utf8) else { throw PitTechArchiveError.invalidArchive }
        let snapshot = try JSONDecoder().decode(Self.self, from: bytes)
        try snapshot.validate()
        return snapshot
    }
    func validate() throws {
        guard version == 1, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              name.count <= 200, tags.count <= 100, instructions.count <= 500, observations.count <= 5000,
              case .array(let dishes)? = draft.objectValue?["dishes"], dishes.count == 1,
              let dishName = dishes[0].objectValue?["name"]?.stringValue, !dishName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw PitTechArchiveError.invalidArchive }
        let setup = dishes[0].objectValue ?? [:]
        let weight = setup["weight"]?.stringValue ?? ""
        guard weight.isEmpty || Double(weight).map({ $0.isFinite && $0 > 0 }) == true else { throw PitTechArchiveError.invalidArchive }
        let ingredients = setup["ingredients"]?.arrayValue ?? []
        guard ingredients.count <= 100, ingredients.allSatisfy({ row in
            guard let o = row.objectValue, let name = o["name"]?.stringValue, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
            let amount = o["amount"]?.stringValue ?? ""
            return amount.isEmpty || Double(amount).map { $0.isFinite && $0 > 0 } == true
        }) else { throw PitTechArchiveError.invalidArchive }
        var seen = Set<String>()
        for row in observations {
            guard let o = row.objectValue, let id = o["sourceEventId"]?.stringValue, seen.insert(id).inserted,
                  let at = o["occurredAtUtcMillis"]?.numberValue, at > 0 else { throw PitTechArchiveError.invalidArchive }
            let expected = smokingAnchorUtcMillis.map { at - Double($0) }
            guard o["offsetMillis"]?.numberValue == expected else { throw PitTechArchiveError.invalidArchive }
        }
        for row in observations {
            let temperatures = row.objectValue?["recordedTemperatures"]?.arrayValue ?? []
            guard temperatures.count <= 100, temperatures.allSatisfy({ value in let o = value.objectValue ?? [:]
                return o["sourceReadingId"]?.stringValue != nil && o["value"]?.numberValue?.isFinite == true && ["°F", "°C"].contains(o["unit"]?.stringValue ?? "") && (o["measuredAtUtcMillis"]?.numberValue ?? 0) > 0
            }) else { throw PitTechArchiveError.invalidArchive }
        }
        seen = []
        for row in instructions {
            guard let o = row.objectValue, let id = o["id"]?.stringValue, seen.insert(id).inserted,
                  let title = o["title"]?.stringValue, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  (o["durationMillis"]?.numberValue ?? 0) >= 0 else { throw PitTechArchiveError.invalidArchive }
        }
        guard targets.allSatisfy({ $0.objectValue?["dish"]?.numberValue == 0 && $0.objectValue?["value"]?.numberValue?.isFinite == true && ["°F", "°C"].contains($0.objectValue?["unit"]?.stringValue ?? "") }),
              rating?.isFinite != false, preferredPitTemperatureF?.isFinite != false else { throw PitTechArchiveError.invalidArchive }
    }
}

extension PitTechLibrary {
    func validateRecipes() throws {
        func index(_ rows: [JSONValue], key: String = "id") throws -> [String: [String: JSONValue]] {
            var result: [String: [String: JSONValue]] = [:]
            for row in rows {
                guard let o = row.objectValue, let id = o[key]?.stringValue, !id.isEmpty, id.count <= 128, result[id] == nil else { throw PitTechArchiveError.invalidArchive }
                result[id] = o
            }
            return result
        }
        let recipeIndex = try index(recipes), revisionIndex = try index(recipeRevisions), dishIndex = try index(dishes)
        _ = try index(dishRecipeSources, key: "dishId")
        _ = try index(mealPresets)
        _ = try index(photos + companionPhotos + recipeAssets)
        for r in recipeIndex.values {
            guard let current = r["currentRevisionId"]?.stringValue, revisionIndex[current]?["recipeId"] == r["id"],
                  let name = r["name"]?.stringValue, !name.isEmpty else { throw PitTechArchiveError.invalidArchive }
        }
        var numbers = Set<String>()
        for r in revisionIndex.values {
            guard let recipe = r["recipeId"]?.stringValue, recipeIndex[recipe] != nil,
                  let number = r["revisionNumber"]?.numberValue, number > 0, number.rounded() == number,
                  numbers.insert("\(recipe):\(number)").inserted, r["snapshotSchemaVersion"]?.numberValue == 1,
                  let raw = r["snapshotJson"]?.stringValue else { throw PitTechArchiveError.invalidArchive }
            _ = try RecipeSnapshot.decode(raw)
            if let parentId = r["parentRevisionId"]?.stringValue {
                guard let parent = revisionIndex[parentId], parent["recipeId"] == r["recipeId"],
                      (parent["revisionNumber"]?.numberValue ?? number) < number else { throw PitTechArchiveError.invalidArchive }
            }
            if let dishId = r["sourceDishId"]?.stringValue {
                guard r["sourceCookId"]?.stringValue != nil else { throw PitTechArchiveError.invalidArchive }
                if let dish = dishIndex[dishId], dish["cookId"] != r["sourceCookId"] { throw PitTechArchiveError.invalidArchive }
            }
        }
        for row in dishRecipeSources {
            guard let o = row.objectValue, let dish = o["dishId"]?.stringValue, dishIndex[dish] != nil,
                  let revision = o["recipeRevisionId"]?.stringValue, revisionIndex[revision]?["recipeId"] == o["recipeId"] else { throw PitTechArchiveError.invalidArchive }
        }
        for row in recipeAssets {
            guard let o = row.objectValue, let id = o["id"]?.stringValue,
                  let revision = o["recipeRevisionId"]?.stringValue, revisionIndex[revision] != nil,
                  let path = o["archivePath"]?.stringValue, CookStore.isExpectedAttachment(path: path, id: id),
                  o["relativeFilePath"]?.stringValue == "photos/" + path.components(separatedBy: "/").last!,
                  o["mimeType"]?.stringValue?.hasPrefix("image/") == true else { throw PitTechArchiveError.invalidArchive }
        }
        for row in mealPresets {
            guard let o = row.objectValue, let name = o["name"]?.stringValue, !name.isEmpty,
                  let raw = o["itemsJson"]?.stringValue, let bytes = raw.data(using: .utf8),
                  let items = try? MealPresetPayload.decode(raw).items, items.count <= 30 else { throw PitTechArchiveError.invalidArchive }
            for item in items {
                if let raw = item.objectValue?["setupSnapshotJson"]?.stringValue { _ = try RecipeSnapshot.decode(raw) }
                if let revision = item.objectValue?["recipeRevisionId"]?.stringValue {
                    guard revisionIndex[revision] != nil else { throw PitTechArchiveError.invalidArchive }
                } else {
                    guard let raw = item.objectValue?["snapshotJson"]?.stringValue else { throw PitTechArchiveError.invalidArchive }
                    _ = try RecipeSnapshot.decode(raw)
                }
            }
        }
    }
    func rejectRecipeConflicts(_ imported: PitTechLibrary) throws {
        for pair in [(recipeRevisions, imported.recipeRevisions), (recipeAssets, imported.recipeAssets), (mealPresets, imported.mealPresets)] {
            let existing = Dictionary(uniqueKeysWithValues: pair.0.compactMap { row in row.objectValue?["id"]?.stringValue.map { ($0, row) } })
            for row in pair.1 {
                if let id = row.objectValue?["id"]?.stringValue, let saved = existing[id], saved != row { throw PitTechArchiveError.invalidArchive }
            }
        }
        for row in imported.recipeRevisions {
            guard let o = row.objectValue else { throw PitTechArchiveError.invalidArchive }
            if recipeRevisions.contains(where: { $0.objectValue?["recipeId"] == o["recipeId"] && $0.objectValue?["revisionNumber"] == o["revisionNumber"] && $0.objectValue?["id"] != o["id"] }) { throw PitTechArchiveError.invalidArchive }
        }
    }
}
