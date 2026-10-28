import SwiftUI
import PhotosUI
import UIKit

private struct AddedRecipePhoto: Identifiable { let id = UUID().uuidString; let data: Data; var caption = "" }

/// PL-24 (#172): the recipe editor's kept draft — the same device-local
/// autosave/resume standard as Android (UserDefaults, never exported or
/// synced). Newly picked photos are NOT in the draft: iOS PhotosPicker
/// yields in-memory data (Android's durable content URIs persist), so
/// an unsaved photo still lives only until the editor closes.
private struct RecipeEditorDraftState: Codable {
    var snapshot: RecipeSnapshot
    var tags: String
    var numericText: [String: String]
    var keptAssets: [String]
    var keptPhotos: [String]
    var captions: [String: String]
    var contextIds: [String]
    var keptFacts: [String]
    var encoded: Data { (try? JSONEncoder().encode(self)) ?? Data() }
    func same(as other: RecipeEditorDraftState) -> Bool { encoded == other.encoded }
}

struct NativeRecipeEditor: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let request: NativeRecipeRequest
    @State private var snapshot: RecipeSnapshot
    @State private var tags: String
    @State private var numericText: [String: String] = [:]
    @State private var keptAssets = Set<String>()
    @State private var keptPhotos: Set<String>
    @State private var captions: [String: String] = [:]
    @State private var contextIds = Set<String>()
    @State private var keptFacts: Set<String>
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var addedPhotos: [AddedRecipePhoto] = []
    @State private var loadingPhotos = false
    @State private var saving = false
    @State private var initialized = false
    @State private var error: String?
    // PL-24 (#172): draft persistence — the state the editor opened with
    // (restored draft or the request's own values) is the baseline; only
    // divergence from it is kept, and saving clears it.
    private let draftKey: String
    private let initialState: RecipeEditorDraftState
    @State private var openedState: RecipeEditorDraftState
    @State private var draftRestored: Bool
    init(request: NativeRecipeRequest) {
        self.request = request
        let key = "recipe-editor-draft:" + (request.recipeId ?? request.attemptDishId.map { "attempt:\($0)" } ?? "new")
        draftKey = key
        let initial = RecipeEditorDraftState(
            snapshot: request.snapshot,
            tags: request.snapshot.tags.joined(separator: ", "),
            numericText: [:],
            keptAssets: [],
            keptPhotos: request.photoIds.sorted(),
            captions: [:],
            contextIds: [],
            keptFacts: request.snapshot.observations.compactMap { $0.objectValue?["sourceEventId"]?.stringValue }.sorted(),
        )
        initialState = initial
        let stored = UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(RecipeEditorDraftState.self, from: $0) }
        let restored = stored.flatMap { $0.same(as: initial) ? nil : $0 }
        let opened = restored ?? initial
        _openedState = State(initialValue: opened)
        _draftRestored = State(initialValue: restored != nil)
        _snapshot = State(initialValue: opened.snapshot)
        _tags = State(initialValue: opened.tags)
        _numericText = State(initialValue: opened.numericText)
        _keptAssets = State(initialValue: Set(opened.keptAssets))
        _keptPhotos = State(initialValue: Set(opened.keptPhotos))
        _captions = State(initialValue: opened.captions)
        _contextIds = State(initialValue: Set(opened.contextIds))
        _keptFacts = State(initialValue: Set(opened.keptFacts))
    }
    private func currentDraftState() -> RecipeEditorDraftState {
        RecipeEditorDraftState(
            snapshot: snapshot, tags: tags, numericText: numericText,
            keptAssets: keptAssets.sorted(), keptPhotos: keptPhotos.sorted(),
            captions: captions, contextIds: contextIds.sorted(), keptFacts: keptFacts.sorted(),
        )
    }
    private var draftFingerprint: String { String(decoding: currentDraftState().encoded, as: UTF8.self) }
    private func persistDraft() {
        let current = currentDraftState()
        if current.same(as: openedState) { UserDefaults.standard.removeObject(forKey: draftKey) }
        else { UserDefaults.standard.set(current.encoded, forKey: draftKey) }
    }
    private func resetToInitialDraft() {
        openedState = initialState
        snapshot = initialState.snapshot; tags = initialState.tags; numericText = initialState.numericText
        keptAssets = Set(initialState.keptAssets); keptPhotos = Set(initialState.keptPhotos)
        captions = initialState.captions; contextIds = Set(initialState.contextIds); keptFacts = Set(initialState.keptFacts)
    }
    private var dish: [String: JSONValue] { snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue ?? [:] }
    private var ingredients: [JSONValue] { dish["ingredients"]?.arrayValue ?? [] }
    private var assets: [JSONValue] { request.assetRevisionId.map(store.recipeAssets) ?? [] }
    private var sourcePhotos: [JSONValue] { store.library.photos.filter { $0.objectValue?["cookId"]?.stringValue == request.sourceCookId } }
    private var valid: Bool { (try? snapshot.validate()) != nil && numericText.values.allSatisfy { $0.isEmpty || Double($0)?.isFinite == true } }
    private var categories: [String] {
        let known = ["Meat", "Beef", "Pork", "Poultry", "Seafood", "Wild game", "Vegetables", "Pasta", "Sides", "Dairy", "Baked dishes", "Desserts", "Other"]
        let current = dish["foodType"]?.stringValue ?? "Other"
        return known.contains(where: { $0.caseInsensitiveCompare(current) == .orderedSame }) ? known : known + [current]
    }
    private var categoryBinding: Binding<String> { Binding(get: { let value = dish["foodType"]?.stringValue ?? "Other"; return categories.first { $0.caseInsensitiveCompare(value) == .orderedSame } ?? value }, set: { setDish("foodType", .string($0)) }) }
    private var isMeat: Bool { ["meat", "beef", "pork", "poultry", "seafood", "wild game"].contains((dish["foodType"]?.stringValue ?? "").lowercased()) }
    var body: some View {
        NavigationStack {
            Form {
                if draftRestored {
                    Section {
                        // TURN-IN (Slice 3, Area 03 `dark-08`): the restored
                        // draft reads as a status strip, not a red warning —
                        // the Form grammar itself stays (§5.4).
                        VStack(alignment: .leading, spacing: 6) {
                            TurnInMetaLabel("Resume draft")
                            TurnInStatusStrip(text: "Your unsaved changes were kept on this device.", accent: PitTechStyle.cobalt)
                            Button("Discard draft", role: .destructive) {
                                UserDefaults.standard.removeObject(forKey: draftKey)
                                draftRestored = false
                                resetToInitialDraft()
                            }.accessibilityIdentifier("editor-draft-discard")
                        }
                    }
                }
                Section("Recipe") {
                    TextField("Name", text: $snapshot.name)
                    TextField("Description (optional)", text: $snapshot.description, axis: .vertical)
                    TextField("Tags, separated by commas", text: $tags)
                    TextField("Serving amount (optional)", text: $snapshot.servings)
                    TextField("Preferred smoker temperature °F", text: Binding(get: { numericText["pit"] ?? snapshot.preferredPitTemperatureF.map { String($0) } ?? "" }, set: { numericText["pit"] = $0; snapshot.preferredPitTemperatureF = Double($0) })).keyboardType(.decimalPad)
                }
                Section {
                    DisclosureGroup("Dish, ingredients & preparation") {
                        TextField("Dish name", text: dishBinding("name"))
                        Picker("Category", selection: categoryBinding) { ForEach(categories, id: \.self) { Text($0).tag($0) } }
                        TextField("Weight (optional)", text: dishBinding("weight")).keyboardType(.decimalPad)
                        Picker("Weight unit", selection: dishBinding("weightUnit")) { ForEach(["lb", "oz", "kg", "g"], id: \.self) { Text($0).tag($0) } }
                        if isMeat { TextField("Cut (optional)", text: dishBinding("cut")) }
                        TextField("Source or grade (optional)", text: dishBinding("grade"))
                        TextField("Thickness (optional)", text: dishBinding("thickness"))
                        TextField("Placement (optional)", text: dishBinding("placement"))
                        TextField("Starting condition (optional)", text: dishBinding("startingCondition"))
                        if isMeat { Picker("Bone-in", selection: Binding(get: { dish["boneIn"]?.boolValue.map { $0 ? "yes" : "no" } ?? "" }, set: { setDish("boneIn", $0.isEmpty ? .null : .bool($0 == "yes")) })) { Text("Not set").tag(""); Text("Yes").tag("yes"); Text("No").tag("no") } }
                        TextField("Preparation notes", text: dishBinding("prep"), axis: .vertical)
                        ForEach(ingredients.indices, id: \.self) { index in
                            TextField("Ingredient", text: ingredientBinding(index, "name"))
                            TextField("Amount (optional)", text: ingredientBinding(index, "amount")).keyboardType(.decimalPad)
                            Picker("Unit", selection: ingredientBinding(index, "unit")) { ForEach(["cup", "tbsp", "tsp", "fl oz", "ml", "L", "g", "oz", "lb", "kg", "count"], id: \.self) { Text($0).tag($0) } }
                            Picker("Type", selection: ingredientBinding(index, "stage")) { ForEach(["main_ingredient", "seasoning", "sauce", "marinade", "binder", "brine", "glaze", "topping", "other"], id: \.self) { Text($0.replacingOccurrences(of: "_", with: " ").capitalized).tag($0) } }
                            TextField("Brand (optional)", text: ingredientBinding(index, "brand"))
                            Button("Remove ingredient") { var items = ingredients; items.remove(at: index); setDish("ingredients", .array(items)) }
                        }
                        Button("+ Ingredient") { setDish("ingredients", .array(ingredients + [.object(["name": .string(""), "amount": .string(""), "unit": .string("count"), "brand": .string(""), "stage": .string("main_ingredient")])])) }
                    }
                }
                Section {
                    DisclosureGroup("Instructions, timings & targets") {
                        ForEach(snapshot.instructions.indices, id: \.self) { index in
                            TextField("Instruction", text: rowText(index, key: "title", targets: false))
                            TextField("Notes", text: rowText(index, key: "details", targets: false), axis: .vertical)
                            Picker("Method", selection: rowText(index, key: "method", targets: false)) { Text("Not set").tag(""); ForEach(CookingMethod.order, id: \.self) { Text(CookingMethod.labels[$0]!).tag($0) } }
                            TextField("Minutes from On smoker (optional)", text: rowNumber(index, key: "offsetMillis", scale: 60000, targets: false)).keyboardType(.numbersAndPunctuation)
                            TextField("Expected duration, minutes (optional)", text: rowNumber(index, key: "durationMillis", scale: 60000, targets: false)).keyboardType(.decimalPad)
                            Button("Remove instruction") { snapshot.instructions.remove(at: index) }
                        }
                        Button("+ Instruction") { snapshot.instructions.append(.object(["id": .string(UUID().uuidString.lowercased()), "title": .string(""), "details": .string("")])) }
                        ForEach(snapshot.targets.indices, id: \.self) { index in
                            TextField("Target description", text: rowText(index, key: "explanation", targets: true))
                            TextField("Target temperature", text: rowNumber(index, key: "value", scale: 1, targets: true)).keyboardType(.decimalPad)
                            Picker("Unit", selection: rowText(index, key: "unit", targets: true)) { Text("°F").tag("°F"); Text("°C").tag("°C") }
                            Button("Remove target") { snapshot.targets.remove(at: index) }
                        }
                        Button("+ Temperature target") { snapshot.targets.append(.object(["dish": .number(0), "type": .string("personal_finish"), "unit": .string("°F")])) }
                        TextField("Recipe notes & lessons", text: $snapshot.resultNotes, axis: .vertical)
                    }
                }
                if request.attemptDishId == nil {
                    Section {
                        DisclosureGroup("Reference photos") {
                            ForEach(assets.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                                if let o = assets.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue {
                                    Toggle("Keep reference photo", isOn: setBinding(id, assets: true))
                                    recipePhoto(o)
                                    TextField("Caption", text: captionBinding(id, original: o["caption"]?.stringValue ?? ""))
                                }
                            }
                            ForEach(sourcePhotos.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                                if let o = sourcePhotos.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue {
                                    Toggle(o["caption"]?.stringValue ?? "Meal photo", isOn: setBinding(id, assets: false))
                                    if keptPhotos.contains(id) { recipePhoto(o); TextField("Caption", text: captionBinding(id, original: o["caption"]?.stringValue ?? "")) }
                                }
                            }
                            ForEach(addedPhotos.indices, id: \.self) { index in
                                if let image = UIImage(data: addedPhotos[index].data) { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 180) }
                                TextField("Caption", text: $addedPhotos[index].caption)
                                Button("Remove photo") { addedPhotos.remove(at: index) }
                            }
                            PhotosPicker("+ Reference photos", selection: $pickerItems, matching: .images)
                            if loadingPhotos { ProgressView("Loading photos…") }
                        }
                    }
                }
                Section {
                    DisclosureGroup("Recorded history") {
                        Text("Recorded observations stay separate from editable instructions.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                        ForEach(snapshot.observations.compactMap { $0.objectValue?["sourceEventId"]?.stringValue }, id: \.self) { id in
                            if let o = snapshot.observations.first(where: { $0.objectValue?["sourceEventId"]?.stringValue == id })?.objectValue {
                                Toggle(o["title"]?.stringValue ?? "Recorded note", isOn: Binding(get: { keptFacts.contains(id) }, set: { if $0 { keptFacts.insert(id) } else { keptFacts.remove(id) } }))
                                Text(o["details"]?.stringValue ?? "").font(PitTechFont.body)
                                if let offset = o["offsetMillis"]?.numberValue { Text("\(offset / 60000, specifier: "%.1f") minutes from recorded On smoker").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
                            }
                        }
                        ForEach(request.context.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                            if let o = request.context.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue {
                                Toggle("Shared meal context: \(o["title"]?.stringValue ?? "Note")", isOn: Binding(get: { contextIds.contains(id) }, set: { if $0 { contextIds.insert(id) } else { contextIds.remove(id) } }))
                            }
                        }
                    }
                }
                // A3 `dark-07-recipe-editor-invalid`: the invalid state is a
                // flag strip under the form, not a red line of text.
                if !valid { TurnInStatusStrip(text: "Review the name, instruction titles and numeric quantities.", accent: PitTechStyle.flag) }
                if let error { TurnInStatusStrip(text: error, accent: PitTechStyle.flag) }
            }.navigationTitle(request.attemptDishId == nil ? "Save recipe" : "Adjust for this meal")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { save() }.disabled(!valid || loadingPhotos || saving) } }
                .onAppear {
                    if !initialized {
                        // PL-24: the asset seeding is the opened baseline,
                        // not an edit — unless a draft was restored, in
                        // which case the draft's kept set stands.
                        if !draftRestored {
                            keptAssets = Set(assets.compactMap { $0.objectValue?["id"]?.stringValue })
                            openedState.keptAssets = keptAssets.sorted()
                        }
                        initialized = true
                    }
                }
                .onChange(of: draftFingerprint) { _, _ in persistDraft() }
                .onChange(of: pickerItems) { _, items in
                    loadingPhotos = true
                    Task { do { for item in items { if let data = try await item.loadTransferable(type: Data.self), let bytes = UIImage(data: data)?.jpegData(compressionQuality: 0.9) { addedPhotos.append(AddedRecipePhoto(data: bytes)) } else { throw CookNoteError.missingPhoto } }; pickerItems = [] } catch { self.error = error.localizedDescription }; loadingPhotos = false }
                }
        }
    }
    private func dishBinding(_ key: String) -> Binding<String> { Binding(get: { dish[key]?.stringValue ?? "" }, set: { setDish(key, .string($0)) }) }
    private func setDish(_ key: String, _ value: JSONValue) { var setup = snapshot.draft.objectValue ?? [:], updated = dish; updated[key] = value; setup["dishes"] = .array([.object(updated)]); snapshot.draft = .object(setup) }
    private func ingredientBinding(_ index: Int, _ key: String) -> Binding<String> { Binding(get: { ingredients[index].objectValue?[key]?.stringValue ?? "" }, set: { var items = ingredients, row = items[index].objectValue ?? [:]; row[key] = .string($0); items[index] = .object(row); setDish("ingredients", .array(items)) }) }
    private func rowText(_ index: Int, key: String, targets: Bool) -> Binding<String> { Binding(get: { (targets ? snapshot.targets[index] : snapshot.instructions[index]).objectValue?[key]?.stringValue ?? "" }, set: { setRow(index, key: key, value: $0.isEmpty && key == "method" ? .null : .string($0), targets: targets) }) }
    private func rowNumber(_ index: Int, key: String, scale: Double, targets: Bool) -> Binding<String> {
        let fieldId = "\(targets):\(index):\(key)"
        return Binding(get: { numericText[fieldId] ?? (targets ? snapshot.targets[index] : snapshot.instructions[index]).objectValue?[key]?.numberValue.map { String($0 / scale) } ?? "" }, set: { raw in numericText[fieldId] = raw; setRow(index, key: key, value: Double(raw).flatMap { number -> JSONValue? in guard number.isFinite, key == "value" || abs(number * scale) <= 2_592_000_000 else { return nil }; return .number(key == "value" ? number : Double(Int64(number * scale))) } ?? .null, targets: targets) })
    }
    private func setRow(_ index: Int, key: String, value: JSONValue, targets: Bool) { var rows = targets ? snapshot.targets : snapshot.instructions, row = rows[index].objectValue ?? [:]; row[key] = value; rows[index] = .object(row); if targets { snapshot.targets = rows } else { snapshot.instructions = rows } }
    private func setBinding(_ id: String, assets: Bool) -> Binding<Bool> { Binding(get: { assets ? keptAssets.contains(id) : keptPhotos.contains(id) }, set: { selected in if assets { if selected { keptAssets.insert(id) } else { keptAssets.remove(id) } } else { if selected { keptPhotos.insert(id) } else { keptPhotos.remove(id) } } }) }
    private func captionBinding(_ id: String, original: String) -> Binding<String> { Binding(get: { captions[id] ?? original }, set: { captions[id] = $0 }) }
    @ViewBuilder private func recipePhoto(_ o: [String: JSONValue]) -> some View { if let path = o["archivePath"]?.stringValue, let image = store.photoPreview(path) { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 180).accessibilityLabel("Recipe reference photo") } }
    private func save() {
        guard !saving else { return }; saving = true
        do {
            var result = snapshot; result.tags = Array(Set(tags.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })).sorted()
            result.observations = result.observations.filter { keptFacts.contains($0.objectValue?["sourceEventId"]?.stringValue ?? "") }
            if let onSave = request.onSave { try onSave(result, addedPhotos.map(\.data), addedPhotos.map(\.caption)) }
            else if let dishId = request.attemptDishId { try store.updateRecipeAttempt(dishId, snapshot: result) }
            else {
                if let cookId = request.sourceCookId, let dishId = request.sourceDishId, !contextIds.isEmpty {
                    let actual = try store.extractRecipe(cookId: cookId, dishId: dishId, contextIds: contextIds)
                    result.observations += actual.observations.filter { contextIds.contains($0.objectValue?["sourceEventId"]?.stringValue ?? "") }
                    result.instructions += actual.instructions.filter { candidate in contextIds.contains(candidate.objectValue?["sourceEventId"]?.stringValue ?? "") && !result.instructions.contains { original in original.objectValue?["sourceEventId"] == candidate.objectValue?["sourceEventId"] } }
                }
                try store.saveRecipe(result, recipeId: request.recipeId, expectedRevisionId: request.expectedRevisionId, sourceCookId: request.sourceCookId, sourceDishId: request.sourceDishId,
                    assetRevisionId: request.assetRevisionId, keptAssetIds: keptAssets, photoIds: keptPhotos, captions: captions, addedPhotos: addedPhotos.map(\.data), addedPhotoCaptions: addedPhotos.map(\.caption))
            }
            // PL-24: a successful save clears the kept draft (all paths).
            UserDefaults.standard.removeObject(forKey: draftKey)
            dismiss()
        } catch { self.error = error.localizedDescription; saving = false }
    }
}
