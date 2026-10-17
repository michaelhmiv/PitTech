import SwiftUI
import PhotosUI
import UIKit

private struct AddedRecipePhoto: Identifiable { let id = UUID().uuidString; let data: Data; var caption = "" }

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
    init(request: NativeRecipeRequest) {
        self.request = request; _snapshot = State(initialValue: request.snapshot); _tags = State(initialValue: request.snapshot.tags.joined(separator: ", "))
        _keptPhotos = State(initialValue: request.photoIds); _keptFacts = State(initialValue: Set(request.snapshot.observations.compactMap { $0.objectValue?["sourceEventId"]?.stringValue }))
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
                        Text("Recorded observations stay separate from editable instructions.").font(.caption)
                        ForEach(snapshot.observations.compactMap { $0.objectValue?["sourceEventId"]?.stringValue }, id: \.self) { id in
                            if let o = snapshot.observations.first(where: { $0.objectValue?["sourceEventId"]?.stringValue == id })?.objectValue {
                                Toggle(o["title"]?.stringValue ?? "Recorded note", isOn: Binding(get: { keptFacts.contains(id) }, set: { if $0 { keptFacts.insert(id) } else { keptFacts.remove(id) } }))
                                Text(o["details"]?.stringValue ?? "")
                                if let offset = o["offsetMillis"]?.numberValue { Text("\(offset / 60000, specifier: "%.1f") minutes from recorded On smoker").font(.caption) }
                            }
                        }
                        ForEach(request.context.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                            if let o = request.context.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue {
                                Toggle("Shared meal context: \(o["title"]?.stringValue ?? "Note")", isOn: Binding(get: { contextIds.contains(id) }, set: { if $0 { contextIds.insert(id) } else { contextIds.remove(id) } }))
                            }
                        }
                    }
                }
                if !valid { Text("Review the name, instruction titles and numeric quantities.").foregroundStyle(.red) }
                if let error { Text(error).foregroundStyle(.red) }
            }.navigationTitle(request.attemptDishId == nil ? "Save recipe" : "Adjust for this meal")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Save") { save() }.disabled(!valid || loadingPhotos || saving) } }
                .onAppear { if !initialized { keptAssets = Set(assets.compactMap { $0.objectValue?["id"]?.stringValue }); initialized = true } }
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
            dismiss()
        } catch { self.error = error.localizedDescription; saving = false }
    }
}
