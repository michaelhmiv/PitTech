import SwiftUI
import UIKit

struct NativeRecipeRequest: Identifiable {
    let id = UUID().uuidString
    var snapshot: RecipeSnapshot
    var recipeId: String?
    var expectedRevisionId: String?
    var assetRevisionId: String?
    var sourceCookId: String?
    var sourceDishId: String?
    var photoIds: Set<String> = []
    var context: [JSONValue] = []
    var attemptDishId: String?
}

struct SavedRecipesView: View {
    @Environment(CookStore.self) private var store
    @State private var query = ""
    @State private var category = "All categories"
    @State private var tag = "All tags"
    @State private var favorites = false
    @State private var archived = false
    @State private var selected: [String] = []
    @State private var title = ""
    @State private var existingMeal = ""
    @State private var editor: NativeRecipeRequest?
    @State private var error: String?
    @State private var destination: String?
    private var snapshots: [String: RecipeSnapshot] {
        Dictionary(uniqueKeysWithValues: store.library.recipeRevisions.compactMap { row -> (String, RecipeSnapshot)? in
            guard let o = row.objectValue, let id = o["id"]?.stringValue, let raw = o["snapshotJson"]?.stringValue, let snapshot = try? RecipeSnapshot.decode(raw) else { return nil }
            return (id, snapshot)
        })
    }
    private var shown: [JSONValue] {
        store.library.recipes.filter { row in
            guard let o = row.objectValue, let revision = o["currentRevisionId"]?.stringValue else { return false }
            let isArchived = o["archivedAtUtcMillis"]?.numberValue != nil
            let snapshot = snapshots[revision]
            return isArchived == archived && (o["name"]?.stringValue ?? "").localizedCaseInsensitiveContains(query) &&
                (!favorites || o["favorite"]?.boolValue == true) && (category == "All categories" || snapshot?.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["foodType"]?.stringValue == category) &&
                (tag == "All tags" || snapshot?.tags.contains(tag) == true)
        }
    }
    var body: some View {
        List {
            Section {
                TextField("Find a recipe", text: $query)
                Picker("Category", selection: $category) {
                    Text("All categories").tag("All categories")
                    ForEach(Array(Set(snapshots.values.compactMap { $0.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["foodType"]?.stringValue })).sorted(), id: \.self) { Text($0).tag($0) }
                }
                Picker("Tag", selection: $tag) { Text("All tags").tag("All tags"); ForEach(Array(Set(snapshots.values.flatMap(\.tags))).sorted(), id: \.self) { Text($0).tag($0) } }
                Toggle("Favorites", isOn: $favorites); Toggle("Archived", isOn: $archived)
            }
            if shown.isEmpty { Text("Save selected dishes from a finished meal, or change these filters.").foregroundStyle(.secondary) }
            ForEach(shown.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                if let o = store.recipe(id), let revision = o["currentRevisionId"]?.stringValue {
                    Section {
                        NavigationLink(o["name"]?.stringValue ?? "Recipe") { SavedRecipeDetailView(recipeId: id) }
                        Toggle("Add " + (o["name"]?.stringValue ?? "recipe") + " to meal", isOn: Binding(get: { selected.contains(revision) }, set: { if $0 { selected.append(revision) } else { selected.removeAll { $0 == revision } } })).disabled(archived)
                        Text("Preferred version \(Int(store.recipeRevision(revision)?["revisionNumber"]?.numberValue ?? 1))").font(.caption)
                        Button(archived ? "Restore recipe" : o["favorite"]?.boolValue == true ? "Remove favorite" : "Favorite") { act { try store.setRecipeFlag(id, favorite: archived ? nil : !(o["favorite"]?.boolValue ?? false), archived: archived ? false : nil) } }
                    }
                }
            }
            if !selected.isEmpty {
                Section("Build a meal") {
                    let temperatures = Set(selected.compactMap { snapshots[$0]?.preferredPitTemperatureF })
                    if temperatures.count > 1 { Text("These recipes use different smoker settings. Choose the setting for this meal when ready.") }
                    TextField("Meal name (optional)", text: $title)
                    Button("Start meal with \(selected.count) recipes") { act { destination = try store.startRecipeMeal(title: title, revisionIds: selected); selected = [] } }.accessibilityIdentifier("recipe-start-meal")
                    if !store.activeCooks.isEmpty {
                        Picker("Existing meal", selection: $existingMeal) { Text("Choose a meal").tag(""); ForEach(store.activeCooks) { Text($0.title).tag($0.id) } }
                        Button("Add selected recipes") { act { for revision in selected { try store.addRecipeToMeal(cookId: existingMeal, revisionId: revision) }; destination = existingMeal; selected = [] } }.disabled(existingMeal.isEmpty)
                    }
                }
            }
            if !store.library.mealPresets.isEmpty {
                Section("Meal combinations") {
                    ForEach(store.library.mealPresets.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                        if let preset = store.library.mealPresets.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue {
                            Button("Repeat \(preset["name"]?.stringValue ?? "meal")") { act { destination = try store.startMealPreset(id) } }
                        }
                    }
                }
            }
            if let error { Text(error).foregroundStyle(.red) }
        }.navigationTitle("Saved Recipes")
            .navigationDestination(isPresented: Binding(get: { destination != nil }, set: { if !$0 { destination = nil } })) { if let destination { CookDetailView(cookId: destination) } }
    }
    private func act(_ block: () throws -> Void) { do { try block(); error = nil } catch { self.error = error.localizedDescription } }
}

struct SavedRecipeDetailView: View {
    @Environment(CookStore.self) private var store
    @State private var selectedRevision = ""
    @State private var duplicateName = ""
    @State private var editor: NativeRecipeRequest?
    @State private var error: String?
    @State private var destination: String?
    let recipeId: String
    private var recipe: [String: JSONValue] { store.recipe(recipeId) ?? [:] }
    private var history: [[String: JSONValue]] { store.library.recipeRevisions.compactMap(\.objectValue).filter { $0["recipeId"]?.stringValue == recipeId }.sorted { ($0["revisionNumber"]?.numberValue ?? 0) > ($1["revisionNumber"]?.numberValue ?? 0) } }
    private var revisionId: String { selectedRevision.isEmpty ? recipe["currentRevisionId"]?.stringValue ?? "" : selectedRevision }
    var body: some View {
        List {
            Picker("Version", selection: Binding(get: { revisionId }, set: { selectedRevision = $0 })) { ForEach(history.compactMap { $0["id"]?.stringValue }, id: \.self) { id in Text("Version \(Int(store.recipeRevision(id)?["revisionNumber"]?.numberValue ?? 1))").tag(id) } }
            Text(revisionId == recipe["currentRevisionId"]?.stringValue ? "Preferred version" : "Previous version")
            if let snapshot = try? store.recipeSnapshot(revisionId) {
                Text(snapshot.description)
                ForEach(snapshot.instructions.indices, id: \.self) { index in Text(snapshot.instructions[index].objectValue?["title"]?.stringValue ?? "Instruction"); Text(snapshot.instructions[index].objectValue?["details"]?.stringValue ?? "") }
                Text(snapshot.resultNotes)
                RecipeAssetGallery(revisionId: revisionId)
            }
            if let sourceId = store.recipeRevision(revisionId)?["sourceCookId"]?.stringValue {
                if store.library.cooks.contains(where: { $0.id == sourceId }) { NavigationLink("Open source meal") { CookDetailView(cookId: sourceId) } }
                else { Text("Source meal is unavailable. Saved instructions and reference photos remain available.").foregroundStyle(.secondary) }
            }
            Button("Use this version") { act { destination = try store.startRecipeMeal(title: recipe["name"]?.stringValue ?? "New meal", revisionIds: [revisionId]) } }
            Button("Edit a new version") { act { let revision = store.recipeRevision(revisionId) ?? [:]; editor = NativeRecipeRequest(snapshot: try store.recipeSnapshot(revisionId), recipeId: recipeId, expectedRevisionId: recipe["currentRevisionId"]?.stringValue, assetRevisionId: revisionId, sourceCookId: revision["sourceCookId"]?.stringValue, sourceDishId: revision["sourceDishId"]?.stringValue) } }
            TextField("Variation name", text: $duplicateName)
            Button("Duplicate recipe") { act { try store.duplicateRecipe(recipeId, name: duplicateName) } }.disabled(duplicateName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Archive recipe") { act { try store.setRecipeFlag(recipeId, archived: true) } }
            if let error { Text(error).foregroundStyle(.red) }
        }.navigationTitle(recipe["name"]?.stringValue ?? "Recipe")
            .onAppear { if duplicateName.isEmpty { duplicateName = (recipe["name"]?.stringValue ?? "Recipe") + " copy" } }
            .sheet(item: $editor) { NativeRecipeEditor(request: $0) }
            .navigationDestination(isPresented: Binding(get: { destination != nil }, set: { if !$0 { destination = nil } })) { if let destination { CookDetailView(cookId: destination) } }
    }
    private func act(_ block: () throws -> Void) { do { try block(); error = nil } catch { self.error = error.localizedDescription } }
}

struct RecipeAssetGallery: View {
    @Environment(CookStore.self) private var store
    let revisionId: String
    var body: some View {
        ForEach(store.recipeAssets(revisionId).compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
            if let o = store.recipeAssets(revisionId).first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue,
               let path = o["archivePath"]?.stringValue, let image = UIImage(contentsOfFile: store.photoURL(path).path) {
                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220).accessibilityLabel(o["caption"]?.stringValue ?? "Recipe reference photo")
                Text(o["caption"]?.stringValue ?? "")
            }
        }
    }
}
