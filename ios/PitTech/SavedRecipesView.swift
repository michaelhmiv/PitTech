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
    var onSave: ((RecipeSnapshot, [Data], [String]) throws -> Void)?
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
    @State private var selecting = false
    @State private var filterDraft: RecipeFilterDraft?
    /// PL-10 (#172): recipe and preset starts land on the Start Cook
    /// review — no cook exists until the review confirms.
    @State private var startSetup: CookSetupDraft?
    private func selection(_ revision: String) -> Binding<Bool> {
        Binding(get: { selected.contains(revision) }, set: { chosen in
            if chosen { if !selected.contains(revision) { selected.append(revision) } }
            else { selected.removeAll { $0 == revision } }
        })
    }
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
            return isArchived == archived && (query.isEmpty || (o["name"]?.stringValue ?? "").localizedCaseInsensitiveContains(query)) &&
                (!favorites || o["favorite"]?.boolValue == true) && (category == "All categories" || snapshot?.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["foodType"]?.stringValue == category) &&
                (tag == "All tags" || snapshot?.tags.contains(tag) == true)
        }
    }
    var body: some View {
        ScrollView {
          VStack(alignment: .leading, spacing: 16) {
            PitTechBrandStrip()
            // TURN-IN (Slice 3, Area 03 `dark-01-recipes`): the library
            // leads with its promise line, a boxed search, and the active
            // filters as one mono line — no title header (§2 framing).
            Text("Your cookbook, ready for another cook.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
            SharingLibrarySection()
            HStack(spacing: 12) {
                HStack { Image(systemName: "magnifyingglass").foregroundStyle(PitTechStyle.inkMuted); TextField("Find a recipe", text: $query).accessibilityIdentifier("recipe-search") }
                    .padding(12).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(PitTechStyle.ruling, lineWidth: 1))
                Button { filterDraft = RecipeFilterDraft(category: category, tag: tag, archived: archived) } label: {
                    Image(systemName: "line.3.horizontal.decrease").frame(width: 44, height: 44)
                }.accessibilityLabel("Filter recipes").accessibilityIdentifier("recipe-filters")
            }
            ViewThatFits(in: .horizontal) {
                HStack { favoriteButton; composeButton }
                VStack(alignment: .leading) { favoriteButton; composeButton }
            }
            if category != "All categories" || tag != "All tags" || archived {
                Text([category == "All categories" ? nil : category, tag == "All tags" ? nil : tag, archived ? "Archived" : nil].compactMap { $0 }.joined(separator: " · "))
                    .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.attentionText)
            }
            if shown.isEmpty {
                // PL-25: the Recipes empties are the shared StateCopy wording.
                ContentUnavailableView(store.library.recipes.isEmpty ? EmptyStateCopy.recipesTitle : EmptyStateCopy.recipesFilteredTitle, systemImage: "book.closed", description: Text(store.library.recipes.isEmpty ? EmptyStateCopy.recipesBody : EmptyStateCopy.recipesFilteredBody))
            }
            ForEach(shown.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                if let o = store.recipe(id), let revision = o["currentRevisionId"]?.stringValue {
                    TurnInCard {
                        VStack(alignment: .leading, spacing: 10) {
                            NavigationLink { SavedRecipeDetailView(recipeId: id) } label: {
                                SavedRecipeCardContent(name: o["name"]?.stringValue ?? "Recipe", revisionId: revision, snapshot: snapshots[revision], favorite: o["favorite"]?.boolValue == true)
                            }.buttonStyle(.plain)
                            HStack {
                                if selecting {
                                    NativeRecipeSelectionButton(name: o["name"]?.stringValue ?? "Recipe", recipeId: id, isSelected: selection(revision), disabled: archived)
                                }
                                Spacer(minLength: 0)
                                Menu {
                                    Button(archived ? "Restore recipe" : o["favorite"]?.boolValue == true ? "Remove favorite" : "Favorite") { act { try store.setRecipeFlag(id, favorite: archived ? nil : !(o["favorite"]?.boolValue ?? false), archived: archived ? false : nil) } }
                                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("Actions for " + (o["name"]?.stringValue ?? "recipe"))
                            }
                        }
                    }
                }
            }
            if !selected.isEmpty {
                // A3 `dark-02-recipes-selecting`: the compose panel is a
                // card with its own header and a cobalt note when the
                // selected recipes disagree on smoker settings.
                TurnInCard {
                    VStack(alignment: .leading, spacing: 10) {
                        TurnInMetaLabel("Build a meal")
                        let temperatures = Set(selected.compactMap { snapshots[$0]?.preferredPitTemperatureF })
                        if temperatures.count > 1 { TurnInStatusStrip(text: "These recipes use different smoker settings. Choose the setting for this meal when ready.", accent: PitTechStyle.cobalt) }
                        TextField("Meal name (optional)", text: $title)
                        Button("Start meal with \(selected.count) recipes") { act { startSetup = try store.recipeStartSetup(title: title, revisionIds: selected, sourceLabel: "From your Recipes · saved recipes stay attached to their dishes"); selected = [] } }.accessibilityIdentifier("recipe-start-meal")
                        if !store.activeCooks.isEmpty {
                            Picker("Existing meal", selection: $existingMeal) { Text("Choose a meal").tag(""); ForEach(store.activeCooks) { Text($0.title).tag($0.id) } }
                            Button("Add selected recipes") { act { for revision in selected { try store.addRecipeToMeal(cookId: existingMeal, revisionId: revision) }; destination = existingMeal; selected = [] } }.disabled(existingMeal.isEmpty)
                        }
                    }
                }
            }
            if !store.library.mealPresets.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    TurnInMetaLabel("Meal combinations")
                    ForEach(store.library.mealPresets.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                        if let preset = store.library.mealPresets.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue {
                            Button("Repeat \(preset["name"]?.stringValue ?? "meal")") { act { startSetup = try store.presetStartSetup(id) } }.buttonStyle(.bordered)
                        }
                    }
                }
            }
            if let error { TurnInStatusStrip(text: error, accent: PitTechStyle.flag) }
          }.padding(20)
        }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Recipes")
            .sheet(item: $filterDraft) { draft in
                RecipeFilterSheet(initial: draft,
                    categories: Array(Set(snapshots.values.compactMap { $0.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["foodType"]?.stringValue })).sorted(),
                    tags: Array(Set(snapshots.values.flatMap(\.tags))).sorted()) { applied in
                        category = applied.category; tag = applied.tag; archived = applied.archived; filterDraft = nil
                    }
            }
            .sheet(item: $startSetup) { setup in StartCookSheet(initialSetup: setup, onStarted: { destination = $0 }) }
            .navigationDestination(isPresented: Binding(get: { destination != nil }, set: { if !$0 { destination = nil } })) { if let destination { CookDetailView(cookId: destination) } }
    }
    private var favoriteButton: some View {
        Button { favorites.toggle() } label: { Label("Favorites", systemImage: favorites ? "heart.fill" : "heart").frame(minHeight: 44) }
            .buttonStyle(.bordered).accessibilityValue(favorites ? "Selected" : "Not selected")
    }
    private var composeButton: some View {
        Button { selecting.toggle(); if !selecting { selected = [] } } label: { Label(selecting ? "Cancel selection" : "Build a meal", systemImage: "plus").frame(minHeight: 44) }
            .buttonStyle(.bordered).accessibilityIdentifier("recipe-compose")
    }
    private func act(_ block: () throws -> Void) { do { try block(); error = nil } catch { self.error = error.localizedDescription } }
}

private struct RecipeFilterDraft: Identifiable {
    let id = UUID()
    var category: String
    var tag: String
    var archived: Bool
}

private struct RecipeFilterSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var initial: RecipeFilterDraft
    let categories: [String]
    let tags: [String]
    let onApply: (RecipeFilterDraft) -> Void
    var body: some View {
        NavigationStack {
            Form {
                Picker("Category", selection: $initial.category) { Text("All categories").tag("All categories"); ForEach(categories, id: \.self) { Text($0).tag($0) } }
                Picker("Tag", selection: $initial.tag) { Text("All tags").tag("All tags"); ForEach(tags, id: \.self) { Text($0).tag($0) } }
                Toggle("Show archived recipes", isOn: $initial.archived)
            }.navigationTitle("Filter recipes").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Apply") { onApply(initial) } }
                }
        }
    }
}

private struct SavedRecipeCardContent: View {
    @Environment(CookStore.self) private var store
    let name: String
    let revisionId: String
    let snapshot: RecipeSnapshot?
    let favorite: Bool
    private var setup: [String: JSONValue] { snapshot?.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue ?? [:] }
    private var hero: UIImage? {
        guard let path = store.recipeAssets(revisionId).first?.objectValue?["archivePath"]?.stringValue else { return nil }
        return store.photoPreview(path)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let hero { Image(uiImage: hero).resizable().scaledToFill().frame(height: 150).clipped().clipShape(RoundedRectangle(cornerRadius: 12)).accessibilityLabel("Recipe reference photo") }
            // A3 `dark-01-recipes`: the name rides the banner face; a
            // favorite wears its heart in the flag color at the name row.
            HStack(alignment: .top, spacing: 8) {
                TurnInBannerText(name).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                if favorite { Image(systemName: "heart.fill").foregroundStyle(PitTechStyle.flag).accessibilityLabel("Favorite") }
            }
            let weight = setup["weight"]?.stringValue ?? ""
            Text([setup["foodType"]?.stringValue, setup["cut"]?.stringValue, weight.isEmpty ? nil : weight + " " + (setup["weightUnit"]?.stringValue ?? "lb")].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            if let description = snapshot?.description, !description.isEmpty { Text(description).font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted).lineLimit(3) }
            Text("Saved recipe · version \(Int(store.recipeRevision(revisionId)?["revisionNumber"]?.numberValue ?? 1))")
                .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct SavedRecipeDetailView: View {
    @Environment(NativePublicCoordinator.self) private var community
    @Environment(CookStore.self) private var store
    @State private var selectedRevision = ""
    @State private var duplicateName = ""
    @State private var editor: NativeRecipeRequest?
    @State private var error: String?
    @State private var destination: String?
    @State private var sharingDraftId: String?
    /// PL-10 (#172): "Use this version" lands on the Start Cook review.
    @State private var startSetup: CookSetupDraft?
    let recipeId: String
    private var recipe: [String: JSONValue] { store.recipe(recipeId) ?? [:] }
    private var history: [[String: JSONValue]] { store.library.recipeRevisions.compactMap(\.objectValue).filter { $0["recipeId"]?.stringValue == recipeId }.sorted { ($0["revisionNumber"]?.numberValue ?? 0) > ($1["revisionNumber"]?.numberValue ?? 0) } }
    private var revisionId: String { selectedRevision.isEmpty ? recipe["currentRevisionId"]?.stringValue ?? "" : selectedRevision }
    var body: some View {
        List {
            Picker("Version", selection: Binding(get: { revisionId }, set: { selectedRevision = $0 })) { ForEach(history.compactMap { $0["id"]?.stringValue }, id: \.self) { id in Text(store.recipeVersionLabel(id)).tag(id) } }
            // A3 `dark-04-recipe-detail`: the current revision wears the
            // marigold pill; older revisions read as quiet mono.
            if revisionId == recipe["currentRevisionId"]?.stringValue {
                Text("Preferred version").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.onAccent)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(PitTechStyle.marigold, in: RoundedRectangle(cornerRadius: 6))
            } else {
                Text("Previous version").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            }
            if let snapshot = try? store.recipeSnapshot(revisionId) {
                if let source = snapshot.publicSource {
                    Text(source.removed ? "Saved from a removed recipe" : "Saved from " + source.displayName).font(PitTechFont.body)
                    if !source.removed { Link("View shared source", destination: source.url)
                        if community.enabled { NavigationLink("Review shared changes") { NativePublicSourceReview(snapshot: snapshot) }.accessibilityIdentifier("source-review-open") }
                    }
                    Text("This saved version is yours. Changes to the public recipe don't replace it.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                }
                if !snapshot.description.isEmpty { Text(snapshot.description).font(PitTechFont.body) }
                // The scoreboard row: pit temperature, serves, and the
                // first recorded target — the same cells Android's detail
                // shows, from the same snapshot fields.
                let scoreCells: [(String, String)] = [
                    snapshot.preferredPitTemperatureF.map { ("Pit temp", "\(Int($0))°F") },
                    snapshot.servings.isEmpty ? nil : ("Serves", snapshot.servings),
                    snapshot.targets.first.flatMap { t in t.objectValue?["value"]?.numberValue.map { ("Target", "\(Int($0))" + (t.objectValue?["unit"]?.stringValue ?? "")) } },
                ].compactMap { $0 }
                if !scoreCells.isEmpty {
                    HStack(spacing: 8) { ForEach(scoreCells, id: \.0) { cell in TurnInScoreCell(label: cell.0, value: cell.1) } }
                        .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20)).listRowBackground(Color.clear)
                }
                let ingredients = snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue?["ingredients"]?.arrayValue ?? []
                if !ingredients.isEmpty {
                    TurnInMetaLabel("Ingredients")
                    ForEach(ingredients.indices, id: \.self) { index in
                        let row = ingredients[index].objectValue ?? [:]
                        HStack {
                            Text(row["name"]?.stringValue ?? "Ingredient").font(PitTechFont.body)
                            Spacer()
                            Text([row["amount"]?.stringValue, row["unit"]?.stringValue].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                        }
                    }
                }
                if !snapshot.instructions.isEmpty {
                    TurnInMetaLabel("Instructions")
                    ForEach(snapshot.instructions.indices, id: \.self) { index in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(snapshot.instructions[index].objectValue?["title"]?.stringValue ?? "Instruction").font(PitTechFont.bodyEmphasis)
                            Text(snapshot.instructions[index].objectValue?["details"]?.stringValue ?? "").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                        }
                    }
                }
                if !snapshot.resultNotes.isEmpty {
                    TurnInMetaLabel("Results note")
                    Text(snapshot.resultNotes).font(PitTechFont.body)
                }
                RecipeAssetGallery(revisionId: revisionId)
            }
            if let sourceId = store.recipeRevision(revisionId)?["sourceCookId"]?.stringValue {
                if store.library.cooks.contains(where: { $0.id == sourceId }) { NavigationLink("Open source meal") { CookDetailView(cookId: sourceId) } }
                else { TurnInStatusStrip(text: "Source meal is unavailable. Saved instructions and reference photos remain available.", accent: PitTechStyle.cobalt) }
            }
            Button("Use this version") { act { startSetup = try store.recipeStartSetup(title: recipe["name"]?.stringValue ?? "New meal", revisionIds: [revisionId], sourceLabel: "From your Recipes · saved recipes stay attached to their dishes") } }
            Button("Share recipe") { act { sharingDraftId = try store.createRecipeSharingDraft(revisionId) } }.accessibilityIdentifier("recipe-share")
            Button("Edit a new version") { act { let revision = store.recipeRevision(revisionId) ?? [:]; editor = NativeRecipeRequest(snapshot: try store.recipeSnapshot(revisionId), recipeId: recipeId, expectedRevisionId: recipe["currentRevisionId"]?.stringValue, assetRevisionId: revisionId, sourceCookId: revision["sourceCookId"]?.stringValue, sourceDishId: revision["sourceDishId"]?.stringValue) } }
            TextField("Variation name", text: $duplicateName)
            Button("Duplicate recipe") { act { try store.duplicateRecipe(recipeId, name: duplicateName) } }.disabled(duplicateName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Archive recipe") { act { try store.setRecipeFlag(recipeId, archived: true) } }.foregroundStyle(PitTechStyle.flag)
            if let error { TurnInStatusStrip(text: error, accent: PitTechStyle.flag) }
        }.navigationTitle(recipe["name"]?.stringValue ?? "Recipe")
            .onAppear { if duplicateName.isEmpty { duplicateName = (recipe["name"]?.stringValue ?? "Recipe") + " copy" } }
            .sheet(item: $editor) { NativeRecipeEditor(request: $0) }
            .sheet(item: $startSetup) { setup in StartCookSheet(initialSetup: setup, onStarted: { destination = $0 }) }
            .sheet(isPresented:Binding(get:{ sharingDraftId != nil },set:{ if !$0 { sharingDraftId = nil } })) {
                if let sharingDraftId { NavigationStack { SharingDraftView(id:sharingDraftId) }.modifier(ScreenshotTextSize()) }
            }
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
               let path = o["archivePath"]?.stringValue, let image = store.photoPreview(path) {
                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220).accessibilityLabel(o["caption"]?.stringValue ?? "Recipe reference photo")
                Text(o["caption"]?.stringValue ?? "")
            }
        }
    }
}

private struct NativeRecipeSelectionButton: View {
    let name: String
    let recipeId: String
    @Binding var isSelected: Bool
    let disabled: Bool
    private var tint: Color { isSelected ? PitTechStyle.sage : PitTechStyle.border }
    var body: some View {
        Button { isSelected.toggle() } label: {
            Label("Add " + name + " to meal", systemImage: isSelected ? "checkmark.circle.fill" : "circle")
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .padding(.horizontal, 12)
                .background(tint.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).foregroundStyle(isSelected ? PitTechStyle.sage : PitTechStyle.ink).disabled(disabled)
            .accessibilityLabel("Add " + name + " to meal")
            .accessibilityValue(isSelected ? "Selected" : "Not selected")
            .accessibilityAddTraits(isSelected ? .isSelected : [])
            .accessibilityHint(isSelected ? "Tap to remove this recipe from the meal" : "Tap to add this recipe to the meal")
            .accessibilityIdentifier("recipe-select-" + recipeId)
    }
}
