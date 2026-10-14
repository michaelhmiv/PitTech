import SwiftUI

struct NativeArchiveImportView: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let source: NativeArchiveIntake
    @State private var selected: Set<String>
    @State private var saving = false
    @State private var error: String?
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
                        Button { saveRecipes() } label: { Text("Save \(selected.count) \(selected.count == 1 ? "recipe" : "recipes")").frame(maxWidth: .infinity, minHeight: 44) }
                            .buttonStyle(.borderedProminent).disabled(selected.isEmpty || saving).accessibilityIdentifier("pack-save-recipes")
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
}

private struct NativeArchiveRecipeCard: View {
    let recipe: NativeArchiveRecipePreview
    @Binding var selected: Bool
    var body: some View {
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
        }.tint(PitTechStyle.sage).padding(18).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16)).accessibilityIdentifier("pack-select-" + recipe.id)
    }
}
