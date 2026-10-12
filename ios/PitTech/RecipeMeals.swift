import Foundation

extension PitTechLibrary {
    func validateRecipeAttempts() throws {
        for row in companionRecords where row.objectValue?["kind"]?.stringValue == "recipe_attempt" {
            guard let record = row.objectValue, let cookId = record["cookId"]?.stringValue,
                  let payload = record["payload"]?.objectValue, payload["version"]?.numberValue == 1,
                  let dishId = payload["dishId"]?.stringValue, let revision = payload["revisionId"]?.stringValue,
                  dishes.contains(where: { $0.objectValue?["id"]?.stringValue == dishId && $0.objectValue?["cookId"]?.stringValue == cookId }),
                  dishRecipeSources.contains(where: { $0.objectValue?["dishId"]?.stringValue == dishId && $0.objectValue?["recipeRevisionId"]?.stringValue == revision }),
                  let snapshot = payload["snapshot"] else { throw PitTechArchiveError.invalidArchive }
            _ = try RecipeSnapshot.decode(recipeJSON(snapshot))
        }
    }
}

@MainActor extension CookStore {
    @discardableResult func startRecipeMeal(title: String, revisionIds: [String]) throws -> String {
        let selected = try revisionIds.map { try recipeSnapshot($0) }
        let prior = library, cook = CookRecord.start(title: title.isEmpty ? "New meal" : title)
        do {
            library.cooks.append(cook); library.lifecycles.append(.object(["cookId": .string(cook.id), "timingMode": .string("explicit_phases")]))
            for index in selected.indices { _ = try appendRecipeDish(cookId: cook.id, snapshot: selected[index], revisionId: revisionIds[index]) }
            try library.validateCookExperience(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }
            return cook.id
        } catch { library = prior; throw error }
    }
    @discardableResult func addRecipeToMeal(cookId: String, revisionId: String) throws -> String {
        guard library.cooks.contains(where: { $0.id == cookId && $0.isActive }) else { throw RecipeError.unavailable }
        let prior = library
        do { let id = try appendRecipeDish(cookId: cookId, snapshot: recipeSnapshot(revisionId), revisionId: revisionId)
            try library.validateCookExperience(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }; return id
        } catch { library = prior; throw error }
    }
    private func appendRecipeDish(cookId: String, snapshot: RecipeSnapshot, revisionId: String?) throws -> String {
        try snapshot.validate()
        let id = UUID().uuidString.lowercased(), now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        let row = try dishSetup(snapshot, id: id, cookId: cookId, created: now, now: now)
        library.dishes.append(.object(row)); try replaceRecipeIngredients(snapshot, dishId: id, cookId: cookId, now: now)
        if let revisionId {
            guard let revision = recipeRevision(revisionId), let recipeId = revision["recipeId"]?.stringValue else { throw RecipeError.unavailable }
            library.dishRecipeSources.append(.object(["dishId": .string(id), "recipeId": .string(recipeId), "recipeRevisionId": .string(revisionId), "appliedAtUtcMillis": .number(now)]))
            try storeRecipeAttempt(dishId: id, cookId: cookId, revisionId: revisionId, snapshot: snapshot, now: now)
        }
        addRecipeTargets(snapshot, dishId: id, cookId: cookId, now: now)
        return id
    }
    private func dishSetup(_ snapshot: RecipeSnapshot, id: String, cookId: String, created: Double, now: Double) throws -> [String: JSONValue] {
        guard let dish = snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue else { throw CookNoteError.invalid }
        var row: [String: JSONValue] = ["id": .string(id), "cookId": .string(cookId), "name": dish["name"] ?? .string(snapshot.name),
            "foodType": dish["foodType"] ?? .string("Other"), "cut": dish["cut"] ?? .null, "weightUnit": dish["weightUnit"] ?? .null,
            "weightValue": dish["weight"]?.stringValue.flatMap(Double.init).map(JSONValue.number) ?? .null,
            "prepNotes": dish["prep"] ?? .null, "gradeOrSource": dish["grade"] ?? .null, "thicknessNotes": dish["thickness"] ?? .null,
            "placement": dish["placement"] ?? .null, "createdAtUtcMillis": .number(created), "updatedAtUtcMillis": .number(now)]
        for key in ["startingCondition", "boneIn"] { if let value = dish[key] { row[key] = value } }
        return row
    }
    private func replaceRecipeIngredients(_ snapshot: RecipeSnapshot, dishId: String, cookId: String, now: Double) throws {
        library.ingredients.removeAll { $0.objectValue?["dishId"]?.stringValue == dishId }
        for row in snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["ingredients"]?.arrayValue ?? [] {
            guard let o = row.objectValue, let name = o["name"]?.stringValue else { throw CookNoteError.invalid }
            let amount = o["amount"]?.stringValue ?? ""
            library.ingredients.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId), "dishId": .string(dishId), "name": .string(name),
                "stage": o["stage"] ?? .string("main_ingredient"), "brand": o["brand"] ?? .null, "amountValue": Double(amount).map(JSONValue.number) ?? .null,
                "amountText": amount.isEmpty ? .null : .string(amount), "amountUnit": o["unit"] ?? .null, "createdAtUtcMillis": .number(now)]))
        }
    }
    private func addRecipeTargets(_ snapshot: RecipeSnapshot, dishId: String, cookId: String, now: Double) {
        for row in snapshot.targets {
            guard let o = row.objectValue else { continue }
            library.targets.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId), "dishId": .string(dishId),
                "targetType": o["type"] ?? .string("personal_finish"), "value": o["value"] ?? .null, "unit": o["unit"] ?? .string("°F"),
                "scope": .string("dish"), "explanation": o["explanation"] ?? .null, "createdAtUtcMillis": .number(now)]))
        }
    }
    private func storeRecipeAttempt(dishId: String, cookId: String, revisionId: String, snapshot: RecipeSnapshot, now: Double) throws {
        let snapshotValue = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(snapshot))
        let id = "recipe-attempt:\(dishId)"
        let old = library.companionRecords.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == id }
        library.companionRecords.append(.object(["id": .string(id), "kind": .string("recipe_attempt"), "cookId": .string(cookId), "title": .string(snapshot.name),
            "payload": .object(["version": .number(1), "dishId": .string(dishId), "revisionId": .string(revisionId), "snapshot": snapshotValue]),
            "created": old?["created"] ?? .number(now), "updated": .number(now)]))
    }
    func recipeAttempt(_ dishId: String) throws -> RecipeSnapshot? {
        guard let value = library.companionRecords.first(where: { $0.objectValue?["id"]?.stringValue == "recipe-attempt:\(dishId)" })?.objectValue?["payload"]?.objectValue?["snapshot"] else { return nil }
        return try RecipeSnapshot.decode(recipeJSON(value))
    }
    func updateRecipeAttempt(_ dishId: String, snapshot: RecipeSnapshot) throws {
        guard let index = library.dishes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == dishId }), let old = library.dishes[index].objectValue,
              let cookId = old["cookId"]?.stringValue, let source = recipeSource(dishId), let revisionId = source["recipeRevisionId"]?.stringValue else { throw RecipeError.unavailable }
        let prior = library, now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        do {
            try snapshot.validate()
            library.dishes[index] = .object(try dishSetup(snapshot, id: dishId, cookId: cookId, created: old["createdAtUtcMillis"]?.numberValue ?? now, now: now))
            try replaceRecipeIngredients(snapshot, dishId: dishId, cookId: cookId, now: now)
            library.targets.removeAll { $0.objectValue?["dishId"]?.stringValue == dishId }; addRecipeTargets(snapshot, dishId: dishId, cookId: cookId, now: now)
            try storeRecipeAttempt(dishId: dishId, cookId: cookId, revisionId: revisionId, snapshot: snapshot, now: now)
            try library.validateCookExperience(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }
        } catch { library = prior; throw error }
    }
    func saveMealPreset(_ cookId: String, name: String) throws {
        guard !name.isEmpty else { throw CookNoteError.invalid }
        var items: [JSONValue] = []
        for dish in dishRecords(cookId) {
            if let source = recipeSource(dish.id), let revision = source["recipeRevisionId"]?.stringValue {
                var working: RecipeSnapshot
                if let existing = try recipeAttempt(dish.id) { working = existing } else { working = try recipeSnapshot(revision) }
                let actual = try extractRecipe(cookId: cookId, dishId: dish.id); working.draft = actual.draft; working.targets = actual.targets
                items.append(.object(["recipeRevisionId": .string(revision), "setupSnapshotJson": .string(try recipeJSON(working))]))
            }
            else { var snapshot = try extractRecipe(cookId: cookId, dishId: dish.id); snapshot.instructions = []; snapshot.observations = []; snapshot.smokingAnchorUtcMillis = nil; snapshot.resultNotes = ""; snapshot.rating = nil
                items.append(.object(["snapshotJson": .string(try recipeJSON(snapshot))])) }
        }
        let prior = library, now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        let cook = library.cooks.first { $0.id == cookId }
        let setup: JSONValue = .object(["title": .string(name), "dishes": .array([]), "smoker": .string(cook?.smokerName ?? ""),
            "setpoint": .string(cook?.initialSetpointValue.map { String($0) } ?? ""), "unit": .string(cook?.initialSetpointUnit ?? "°F"),
            "notes": .string(cook?.notes ?? ""), "fuel": .string(cook?.fuelType ?? ""), "wood": .string(cook?.woodOrPelletBlend ?? "")])
        let payload: JSONValue = .object(["version": .number(1), "items": .array(items), "setup": setup])
        library.mealPresets.append(.object(["id": .string(UUID().uuidString.lowercased()), "name": .string(name), "itemsJson": .string(try recipeJSON(payload)), "createdAtUtcMillis": .number(now), "updatedAtUtcMillis": .number(now)]))
        do { try library.validateRecipes(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) } } catch { library = prior; throw error }
    }
    @discardableResult func startMealPreset(_ id: String) throws -> String {
        guard let preset = library.mealPresets.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue,
              let raw = preset["itemsJson"]?.stringValue else { throw RecipeError.unavailable }
        let payload = try MealPresetPayload.decode(raw), items = payload.items
        let prior = library
        var cook = CookRecord.start(title: preset["name"]?.stringValue ?? "New meal")
        cook.smokerName = payload.setup["smoker"]?.stringValue
        cook.initialSetpointValue = payload.setup["setpoint"]?.stringValue.flatMap(Double.init)
        cook.initialSetpointUnit = payload.setup["unit"]?.stringValue
        cook.notes = payload.setup["notes"]?.stringValue
        cook.fuelType = payload.setup["fuel"]?.stringValue
        cook.woodOrPelletBlend = payload.setup["wood"]?.stringValue
        do {
            library.cooks.append(cook); library.lifecycles.append(.object(["cookId": .string(cook.id), "timingMode": .string("explicit_phases")]))
            for item in items {
                let revision = item.objectValue?["recipeRevisionId"]?.stringValue
                let snapshot: RecipeSnapshot
                if let raw = item.objectValue?["setupSnapshotJson"]?.stringValue { snapshot = try RecipeSnapshot.decode(raw) }
                else if let revision { snapshot = try recipeSnapshot(revision) }
                else { snapshot = try RecipeSnapshot.decode(item.objectValue?["snapshotJson"]?.stringValue ?? "") }
                _ = try appendRecipeDish(cookId: cook.id, snapshot: snapshot, revisionId: revision)
            }
            try library.validateCookExperience(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) }; return cook.id
        } catch { library = prior; throw error }
    }
}
