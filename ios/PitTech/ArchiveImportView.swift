import SwiftUI

struct NativeArchiveImportView: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let source: NativeArchiveIntake
    @State private var selected: Set<String>
    @State private var saving = false
    @State private var error: String?
    @State private var historyAdded = false
    init(source: NativeArchiveIntake) {
        self.source = source
        _selected = State(initialValue: Set(source.recipes.map(\.id)))
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if source.preview.cookPack != nil {
                        Text("Choose what to save").font(.title2.weight(.semibold))
                        Text("Save either dish or the whole collection. Your copies can be edited and cooked offline.").foregroundStyle(PitTechStyle.supporting)
                        ForEach(source.recipes) { recipe in
                            NativeArchiveRecipeCard(recipe: recipe, selected: selection(recipe.id)).disabled(saving)
                        }
                        Button { saveRecipes() } label: { Text("Save \(selected.count) \(selected.count == 1 ? "recipe" : "recipes")" + (source.publicCookOnSave ? " & set up meal" : "")).frame(maxWidth: .infinity, minHeight: 44) }
                            .buttonStyle(.borderedProminent).disabled(selected.isEmpty || saving).accessibilityIdentifier("pack-save-recipes")
                        if source.preview.cookPack?.objectValue?["kind"]?.stringValue == "cook" {
                            Text("Add all \(source.preview.dishes) recorded dishes in this file. Recipe selections above apply when saving recipes.").font(.caption).foregroundStyle(PitTechStyle.supporting)
                            if historyAdded {
                                // PL-19: this sheet stays open after adding
                                // history — the recipe selection above is
                                // still the user's to save.
                                Button("Added to history") {}.buttonStyle(.bordered).frame(minHeight: 44).disabled(true).accessibilityIdentifier("pack-add-history")
                                Text("The recorded meal is in your history. Your recipe choices above are unchanged — save them whenever you're ready.").font(.caption).foregroundStyle(PitTechStyle.supporting).accessibilityIdentifier("pack-history-added")
                            } else {
                                Button("Add recorded meal to history") { saveHistory() }.buttonStyle(.bordered).frame(minHeight: 44).disabled(saving).accessibilityIdentifier("pack-add-history")
                            }
                        }
                        Text("Guides stay separate from your own cooking history. Reminders are yours to choose when you cook.").font(.caption).foregroundStyle(PitTechStyle.supporting)
                    } else {
                        Text("Restore your backup").font(.title2.weight(.semibold))
                        Text("\(source.preview.cooks) meals · \(source.preview.dishes) dishes · \(source.preview.recipes) recipes · \(source.preview.readings) readings").foregroundStyle(PitTechStyle.supporting)
                        Text("Existing meal IDs are skipped. Restored monitoring and reminders remain inactive.")
                        Button("Restore backup") {
                            do { let result = try store.importArchive(at: source.url); store.archiveCompletionMessage = "Restored \(result.imported) meals; skipped \(result.skipped) duplicates."; dismiss() }
                            catch { self.error = error.localizedDescription }
                        }.buttonStyle(.borderedProminent).frame(minHeight: 44).accessibilityIdentifier("archive-restore")
                    }
                    if saving { ProgressView("Saving recipes…") }
                    if let error { Text(error).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
                }.padding(20)
            }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle(source.preview.cookPack != nil ? "Cook Pack" : "Cook archive").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) } }
                .interactiveDismissDisabled(saving)
        }
    }
    private func selection(_ revision: String) -> Binding<Bool> {
        Binding(get: { selected.contains(revision) }, set: { if $0 { selected.insert(revision) } else { selected.remove(revision) } })
    }
    private func saveRecipes() {
        saving = true; error = nil
        Task { @MainActor in
            do { try await store.importSelectedRecipes(source, selected: selected); if store.archiveIntake?.id == source.id { dismiss() } }
            catch { self.error = error.localizedDescription }
            saving = false
        }
    }
    private func saveHistory() {
        saving = true; error = nil
        Task { @MainActor in
            // PL-19: adding the recorded meal no longer dismisses this
            // sheet — dismissing used to drop the pending recipe save
            // choice. The history confirmation stays visible here, and
            // the completion message still surfaces when the sheet is
            // dismissed afterwards (ContentView moves it to the alert).
            do { try await store.importRecordedCook(source); historyAdded = true }
            catch { self.error = error.localizedDescription }
            saving = false
        }
    }
}

private struct NativeArchiveRecipeCard: View {
    @Environment(\.dynamicTypeSize) private var textSize
    let recipe: NativeArchiveRecipePreview
    @Binding var selected: Bool
    var body: some View {
        Group {
        if textSize >= .xxxLarge {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    if let data = recipe.thumbnail, let image = UIImage(data: data) {
                        Image(uiImage: image).resizable().scaledToFill().frame(width: 64, height: 64).clipShape(RoundedRectangle(cornerRadius: 10)).accessibilityHidden(true)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(recipe.title).font(.headline)
                        if !recipe.detail.isEmpty { Text(recipe.detail).font(.subheadline).foregroundStyle(PitTechStyle.supporting) }
                    }.fixedSize(horizontal: false, vertical: true)
                }
                if !recipe.description.isEmpty { Text(recipe.description).font(.subheadline) }
                if recipe.hasGuide { Label("Recorded cook guide included", systemImage: "clock.arrow.circlepath").font(.caption).foregroundStyle(PitTechStyle.sage) }
                Toggle("Save recipe", isOn: $selected).frame(minHeight: 44).accessibilityIdentifier("pack-select-" + recipe.id)
            }
        } else {
        Toggle(isOn: $selected) {
            HStack(alignment: .top, spacing: 12) {
                if let data = recipe.thumbnail, let image = UIImage(data: data) {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: 64, height: 64).clipShape(RoundedRectangle(cornerRadius: 10)).accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(recipe.title).font(.headline)
                    if !recipe.detail.isEmpty { Text(recipe.detail).font(.subheadline).foregroundStyle(PitTechStyle.supporting) }
                    if !recipe.description.isEmpty { Text(recipe.description).font(.subheadline).lineLimit(3) }
                    if recipe.hasGuide { Label("Recorded cook guide included", systemImage: "clock.arrow.circlepath").font(.caption).foregroundStyle(PitTechStyle.sage) }
                }.fixedSize(horizontal: false, vertical: true)
            }
        }.accessibilityIdentifier("pack-select-" + recipe.id)
        }
        }.tint(PitTechStyle.sage).padding(18).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}
