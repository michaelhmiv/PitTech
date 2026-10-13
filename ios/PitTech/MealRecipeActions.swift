import SwiftUI

struct MealRecipeActions: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    var initiallyExpanded = false
    @State private var expanded = false
    @State private var editor: NativeRecipeRequest?
    @State private var presetName = ""
    @State private var error: String?
    private var cook: CookRecord? { store.library.cooks.first { $0.id == cookId } }
    private var dishes: [NoteDishRecord] { store.dishRecords(cookId) }
    var body: some View {
        if !dishes.isEmpty && (cook?.isActive == false || dishes.contains(where: { store.recipeSource($0.id) != nil })) {
            DisclosureGroup(cook?.isActive == false ? "Save selected dishes as recipes" : "Recipes in this meal", isExpanded: $expanded) {
                ForEach(dishes) { dish in
                    Text(dish.name).font(.headline)
                    if cook?.isActive == false {
                        Button("Save as new recipe") { act { editor = try request(dish.id) } }
                        if let source = store.recipeSource(dish.id), let recipeId = source["recipeId"]?.stringValue {
                            Button("Review update to saved recipe") { act {
                                guard let header = store.recipe(recipeId), let revisionId = header["currentRevisionId"]?.stringValue else { throw RecipeError.unavailable }
                                var draft = try request(dish.id), baseline = try store.recipeSnapshot(revisionId)
                                let actualDish = draft.snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue ?? [:]
                                var setup = baseline.draft.objectValue ?? [:], merged = actualDish
                                if actualDish["ingredients"]?.arrayValue?.isEmpty != false { merged["ingredients"] = baseline.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["ingredients"] ?? .array([]) }
                                if (actualDish["prep"]?.stringValue ?? "").isEmpty { merged["prep"] = baseline.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["prep"] ?? .string("") }
                                setup["dishes"] = .array([.object(merged)]); baseline.draft = .object(setup)
                                baseline.observations = draft.snapshot.observations; baseline.smokingAnchorUtcMillis = draft.snapshot.smokingAnchorUtcMillis
                                if !draft.snapshot.instructions.isEmpty { baseline.instructions = mergeRecipeInstructions(baseline.instructions, draft.snapshot.instructions) }
                                if !draft.snapshot.resultNotes.isEmpty { baseline.resultNotes = draft.snapshot.resultNotes }
                                if !draft.snapshot.targets.isEmpty { baseline.targets = draft.snapshot.targets }
                                draft.snapshot = baseline; draft.recipeId = recipeId; draft.expectedRevisionId = revisionId; draft.assetRevisionId = revisionId; editor = draft
                            } }
                        }
                    } else if store.recipeSource(dish.id) != nil {
                        Button("Adjust for this meal") { act {
                            guard var snapshot = try store.recipeAttempt(dish.id) else { throw RecipeError.unavailable }
                            let actual = try store.extractRecipe(cookId: cookId, dishId: dish.id)
                            snapshot.draft = actual.draft; snapshot.targets = actual.targets
                            editor = NativeRecipeRequest(snapshot: snapshot, attemptDishId: dish.id)
                        } }
                    }
                }
                if cook?.isActive == false {
                    TextField("Meal combination name", text: $presetName)
                    Button("Save meal combination") { act { try store.saveMealPreset(cookId, name: presetName) } }.disabled(presetName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }.sheet(item: $editor) { NativeRecipeEditor(request: $0) }
                .onAppear { if initiallyExpanded { expanded = true }; if presetName.isEmpty { presetName = cook?.title ?? "Meal" } }
        }
    }
    private func request(_ dishId: String) throws -> NativeRecipeRequest {
        var snapshot = try store.extractRecipe(cookId: cookId, dishId: dishId)
        if let working = try store.recipeAttempt(dishId) {
            snapshot.description = working.description; snapshot.tags = working.tags; snapshot.servings = working.servings; snapshot.preferredPitTemperatureF = working.preferredPitTemperatureF
            snapshot.instructions = mergeRecipeInstructions(working.instructions, snapshot.instructions)
        }
        let events = Set(snapshot.observations.compactMap { $0.objectValue?["sourceEventId"]?.stringValue })
        let photos = Set(store.library.photos.filter { $0.objectValue?["dishId"]?.stringValue == dishId || events.contains($0.objectValue?["eventId"]?.stringValue ?? "") }.compactMap { $0.objectValue?["id"]?.stringValue })
        let context = store.library.events.filter { row in
            guard let o = row.objectValue, o["cookId"]?.stringValue == cookId, o["dishId"]?.stringValue == nil else { return false }
            let m = o["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) }
            return m?.dishIds.isEmpty != false && !events.contains(o["id"]?.stringValue ?? "")
        }
        return NativeRecipeRequest(snapshot: snapshot, sourceCookId: cookId, sourceDishId: dishId, photoIds: photos, context: context)
    }
    private func act(_ block: () throws -> Void) { do { try block(); error = nil } catch { self.error = error.localizedDescription } }
}

private func mergeRecipeInstructions(_ previous: [JSONValue], _ recorded: [JSONValue]) -> [JSONValue] {
    func key(_ row: JSONValue) -> String { (row.objectValue?["title"]?.stringValue ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased() + ":" + (row.objectValue?["method"]?.stringValue ?? "") }
    let groups = Dictionary(grouping: recorded, by: key)
    var used: [String: Int] = [:]
    let result = previous.map { old -> JSONValue in let k = key(old), index = used[k] ?? 0; used[k] = index + 1; return groups[k].flatMap { $0.indices.contains(index) ? $0[index] : nil } ?? old }
    return result + recorded.enumerated().compactMap { index, row in
        let k = key(row); let ordinal = recorded.prefix(index).filter { key($0) == k }.count
        return ordinal >= (used[k] ?? 0) ? row : nil
    }
}
