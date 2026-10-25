import SwiftUI

struct SharingDraftView: View {
    @Environment(CookStore.self) private var store
    @Environment(NativePublicCoordinator.self) private var community
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let id: String
    @State private var document: NativeSharingDocument?
    @State private var updated: Double = 0
    @State private var saveTask: Task<Void, Never>?
    @State private var saved = true
    @State private var editor: NativeRecipeRequest?
    @State private var choosingRecipe = false
    @State private var error: String?
    @State private var includeRecordedCook = false
    @State private var preparingExport = false
    @State private var exporting = false
    @State private var cardSettings = false
    @State private var cardPreview = false
    @State private var relayShare = false
    @State private var publishing = false
    @State private var cardPackRequested = false
    @State private var exportDocument = PitTechArchiveDocument(data: Data())
    @State private var exportTask: Task<Void, Never>?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(saved ? "Saved on this device" : "Saving edits…").font(.caption).foregroundStyle(PitTechStyle.sage)
                Text("Choose exactly what you want to share. Your original meal stays unchanged.").font(.subheadline).foregroundStyle(PitTechStyle.supporting)
                if let current = document {
                    TextField("Title", text: Binding(get: { document?.title ?? "" }, set: { value in change { $0.title = String(value.prefix(160)) } })).textFieldStyle(.roundedBorder).accessibilityIdentifier("sharing-title")
                    ForEach(current.entries) { entry in
                        VStack(alignment: .leading, spacing: 12) {
                            Toggle((try? entry.snapshot.name) ?? "Dish", isOn: Binding(get: { document?.entries.first { $0.id == entry.id }?.included ?? false }, set: { value in change { doc in
                                if let index = doc.entries.firstIndex(where: { $0.id == entry.id }) { doc.entries[index].included = value; if !value && doc.entries[index].photoIds.contains(doc.heroPhotoId ?? "") { doc.heroPhotoId = nil } }
                            } })).font(.headline).accessibilityIdentifier("sharing-include-" + entry.id)
                            Button("Edit recipe, ingredients & instructions") {
                                act {
                                    try persist(); saveTask?.cancel()
                                    editor = NativeRecipeRequest(snapshot: try entry.snapshot, onSave: { snapshot, photos, captions in
                                        try store.editSharingEntry(id, entryId: entry.id, snapshot: snapshot, addedPhotos: photos, captions: captions, expectedUpdated: updated); try reload()
                                    })
                                }
                            }.frame(minHeight: 44)
                            Toggle("Include recorded cook story", isOn: entryBinding(entry.id, key: "story"))
                            if entry.includeStory { Text("Recorded milestones, readings and results remain source facts.").font(.caption).foregroundStyle(PitTechStyle.supporting) }
                            ForEach(store.sharingPhotos(id).filter { $0["action"]?.stringValue == entry.id }.compactMap { $0["id"]?.stringValue }, id: \.self) { assetId in
                                if let asset = store.sharingPhotos(id).first(where: { $0["id"]?.stringValue == assetId }) {
                                    Toggle(asset["caption"]?.stringValue?.isEmpty == false ? asset["caption"]!.stringValue! : "Reference photo", isOn: Binding(get: { document?.entries.first { $0.id == entry.id }?.photoIds.contains(assetId) ?? false }, set: { value in change { doc in
                                        if let index = doc.entries.firstIndex(where: { $0.id == entry.id }) {
                                            if value { doc.entries[index].photoIds.append(assetId) } else { doc.entries[index].photoIds.removeAll { $0 == assetId }; if doc.heroPhotoId == assetId { doc.heroPhotoId = nil } }
                                        }
                                    } }))
                                    if let path = asset["archivePath"]?.stringValue, let image = store.photoPreview(path) { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 150).accessibilityLabel(asset["caption"]?.stringValue ?? "Reference photo") }
                                }
                            }
                            HStack {
                                Button("Move up") { move(entry.id, by: -1) }.disabled(current.entries.first?.id == entry.id)
                                Button("Move down") { move(entry.id, by: 1) }.disabled(current.entries.last?.id == entry.id)
                            }.buttonStyle(.bordered)
                        }.padding(18).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                    }
                    Button("Add a saved recipe") { act { try persist(); choosingRecipe = true } }.buttonStyle(.bordered).frame(minHeight: 44).disabled(current.entries.count >= 20)
                    Text("Identity is chosen when you publish. File sharing and saved collections do not require an account.").font(.caption).foregroundStyle(PitTechStyle.supporting)
                    if current.sourceHistory != nil {
                        Toggle("Include this recorded meal", isOn: $includeRecordedCook).disabled(!canIncludeHistory(current) || preparingExport).accessibilityIdentifier("sharing-include-history")
                        Text(canIncludeHistory(current) ? "Recipients can choose to add this finished meal to history." : "Enable the recorded story for each selected original dish first.").font(.caption).foregroundStyle(PitTechStyle.supporting)
                    }
                    if preparingExport { ProgressView("Preparing Cook Pack…") }
                    Button("Card appearance & note") { cardSettings.toggle() }.frame(minHeight:44)
                    if cardSettings { ShareCardOptionsView(document:Binding(get:{ document ?? current },set:{ value in change { $0 = value } })) }
                    // PL-18: the exits have a hierarchy — the Cook Pack file
                    // is the primary exit, the share card and the code are
                    // secondary, Publish sits inside its own panel, and
                    // saving a collection is a local action, not a share.
                    // The file button is named for what it does: it opens
                    // the Files save picker, so it is "Save Cook Pack file".
                    Button { prepareExport() } label: { Label("Save Cook Pack file", systemImage: "square.and.arrow.down") }
                        .buttonStyle(.borderedProminent).frame(minHeight: 44).disabled(preparingExport || !current.entries.contains { $0.included }).accessibilityIdentifier("sharing-export-pack")
                    Button("Preview share card") { act { try persist();cardPreview = true } }.buttonStyle(.bordered).frame(minHeight:44).disabled(!current.entries.contains { $0.included }).accessibilityIdentifier("sharing-preview-card")
                    Button("Share by code") { act { try persist(); relayShare = true } }.buttonStyle(.bordered).frame(minHeight: 44).disabled(preparingExport || !current.entries.contains { $0.included }).accessibilityIdentifier("sharing-relay")
                    if community.enabled && account.enabled {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Publish").font(.headline)
                            Text("Make this draft public on your cookbook. You preview exactly what posts first — nothing is published without that separate confirmation.").font(.caption).foregroundStyle(PitTechStyle.supporting)
                            Button("Preview public publication") { act { try persist(); publishing = true } }.buttonStyle(.bordered).frame(minHeight: 44).disabled(!current.entries.contains { $0.included }).accessibilityIdentifier("sharing-publish")
                        }.padding(18).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                    }
                    Button("Save meal collection") { act { try persist(); _ = try store.copySharingDocument(id, asCollection: true); try store.discardSharingDraft(id); document = nil; dismiss() } }
                        .buttonStyle(.bordered).frame(minHeight: 44).disabled(!current.entries.contains { $0.included })
                    Button("Discard draft", role: .destructive) { act { saveTask?.cancel(); try store.discardSharingDraft(id); document = nil; dismiss() } }.frame(minHeight: 44)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }.padding(20)
        }.accessibilityIdentifier("sharing-draft-content").background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Sharing draft").navigationBarTitleDisplayMode(.inline)
            .onAppear { act { try reload() } }
            .onChange(of: document) { _, value in
                if let value, !canIncludeHistory(value) { includeRecordedCook = false }
                saveTask?.cancel(); guard value != nil, editor == nil else { return }; saved = false
                saveTask = Task { @MainActor in
                    do { try await Task.sleep(for: .milliseconds(500)); guard !Task.isCancelled else { return }; try persist(); error = nil }
                    catch is CancellationError { }
                    catch { self.error = error.localizedDescription }
                }
            }
            .onChange(of: scenePhase) { _, phase in if phase != .active && editor == nil { act { try persist() } } }
            .onDisappear { saveTask?.cancel(); exportTask?.cancel(); if document != nil && editor == nil { act { try persist() } } }
            .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .pittechCookPack, defaultFilename: document?.title ?? "PitTech-Recipe") { result in
                if case .failure(let failure) = result { error = failure.localizedDescription }
                exportDocument = PitTechArchiveDocument(data: Data())
            }
            .sheet(item: $editor, onDismiss: { act { try reload() } }) { NativeRecipeEditor(request: $0) }
            .sheet(isPresented: $publishing, onDismiss: { act { try reload() } }) { NavigationStack { NativePublicationView(draft: id) }.modifier(ScreenshotTextSize()) }
            .sheet(isPresented: $relayShare) { NavigationStack { NativeRelayShareView(id: id, includeHistory: includeRecordedCook) }.modifier(ScreenshotTextSize()) }
            .sheet(isPresented:$cardPreview,onDismiss:{ if cardPackRequested { cardPackRequested = false;prepareExport() } }) {
                NavigationStack { NativeShareCardPreview(id:id) { cardPackRequested = true;cardPreview = false } }.modifier(ScreenshotTextSize())
            }
            .sheet(isPresented: $choosingRecipe) {
                NavigationStack {
                    List(store.library.recipes.compactMap(\.objectValue).filter { $0["archivedAtUtcMillis"]?.numberValue == nil }.compactMap { $0["id"]?.stringValue }, id: \.self) { recipeId in
                        if let recipe = store.recipe(recipeId), let revision = recipe["currentRevisionId"]?.stringValue { Button(recipe["name"]?.stringValue ?? "Recipe") { act { try store.addRecipeToSharingDraft(id, revisionId: revision, expectedUpdated: updated); try reload(); choosingRecipe = false } } }
                    }.navigationTitle("Add a recipe").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { choosingRecipe = false } } }
                }
            }
    }
    private func canIncludeHistory(_ value: NativeSharingDocument) -> Bool {
        guard let history = value.sourceHistory else { return false }
        let original = Set(NativeCookPackHistory.rows(history, "dishes").compactMap { $0["id"]?.stringValue })
        let selected = value.entries.filter(\.included)
        return !selected.isEmpty && selected.allSatisfy { $0.includeStory && $0.sourceDishId.map(original.contains) == true }
    }
    private func reload() throws { document = try store.sharingDocument(id); updated = store.sharingRecord(id)?["updated"]?.numberValue ?? 0; saved = true }
    private func prepareExport() {
        guard !preparingExport else { return }
        act {
            try persist(); preparingExport = true
            exportTask = Task { @MainActor in
                do {
                    let data = try await store.exportCookPackAsync(id, includeRecordedCook: includeRecordedCook)
                    try Task.checkCancellation()
                    exportDocument = PitTechArchiveDocument(data: data); exporting = true; error = nil
                } catch is CancellationError { }
                catch { self.error = error.localizedDescription }
                preparingExport = false
            }
        }
    }
    private func persist() throws { saveTask?.cancel(); guard let document else { return }; try store.saveSharingDraft(id, document: document, expectedUpdated: updated); updated = store.sharingRecord(id)?["updated"]?.numberValue ?? updated; saved = true }
    private func change(_ mutation: (inout NativeSharingDocument) -> Void) { guard var current = document else { return }; mutation(&current); document = current }
    private func entryBinding(_ id: String, key: String) -> Binding<Bool> { Binding(get: { document?.entries.first { $0.id == id }?.includeStory ?? false }, set: { value in change { if let index = $0.entries.firstIndex(where: { $0.id == id }) { $0.entries[index].includeStory = value } } }) }
    private func move(_ id: String, by offset: Int) { change { doc in if let index = doc.entries.firstIndex(where: { $0.id == id }), doc.entries.indices.contains(index + offset) { doc.entries.swapAt(index, index + offset) } } }
    private func act(_ action: () throws -> Void) { do { try action(); error = nil } catch { self.error = error.localizedDescription } }
}

struct SharingLibrarySection: View {
    @Environment(CookStore.self) private var store
    @State private var destination: String?
    @State private var error: String?
    private var drafts: [[String: JSONValue]] { store.library.companionRecords.compactMap(\.objectValue).filter { $0["kind"]?.stringValue == "sharing_draft" } }
    private var collections: [[String: JSONValue]] {
        var seen = Set<String>()
        return store.library.companionRecords.compactMap(\.objectValue).filter { $0["kind"]?.stringValue == "collection_revision" }.sorted { ($0["created"]?.numberValue ?? 0) > ($1["created"]?.numberValue ?? 0) }.filter { seen.insert($0["payload"]?.objectValue?["collectionId"]?.stringValue ?? "").inserted }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !drafts.isEmpty { Text("Sharing drafts").font(.headline) }
            ForEach(drafts.compactMap { $0["id"]?.stringValue }, id: \.self) { id in if let row = store.sharingRecord(id) { NavigationLink("Resume " + (row["title"]?.stringValue ?? "draft")) { SharingDraftView(id: id) }.frame(minHeight: 44) } }
            if !collections.isEmpty { Text("Meal collections").font(.headline) }
            ForEach(collections.compactMap { $0["id"]?.stringValue }, id: \.self) { id in if let row = store.sharingRecord(id) { Button("Copy & customize " + (row["title"]?.stringValue ?? "collection")) { do { destination = try store.copySharingDocument(id) } catch { self.error = error.localizedDescription } }.frame(minHeight: 44) } }
            if let error { Text(error).foregroundStyle(.red) }
        }.navigationDestination(isPresented: Binding(get: { destination != nil }, set: { if !$0 { destination = nil } })) { if let destination { SharingDraftView(id: destination) } }
    }
}
