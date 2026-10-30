import SwiftUI
import UniformTypeIdentifiers

private struct NativeCookbookItem: Decodable, Identifiable {
    let id: String, status: String, revisionId: String, title: String, identityMode: String
    let revision: Int
    func validate() throws { guard UUID(uuidString: id) != nil, UUID(uuidString: revisionId) != nil, revision > 0, (1...160).contains(title.utf16.count), ["named", "anonymous"].contains(identityMode), ["active", "pending_review", "unpublished", "taken_down"].contains(status) else { throw NativePublicationError.invalid } }
}
private struct NativeCookbookPage: Decodable { let items: [NativeCookbookItem]; let nextCursor: String? }
private struct NativeCookbookProfile: Codable, Equatable { let handle: String?; let displayName: String; let isPublic: Bool; var reviewStatus: String? = nil }
/// One posted-card presentation from My Cookbook (Spread 07): the
/// resolved sharing draft plus the publication identity for its card.
private struct CookbookCardRequest: Identifiable {
    let id = UUID()
    let draftId: String
    let publication: ShareCardPublication
}
struct NativeMyCookbookView: View {
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(NativePublicCoordinator.self) private var community
    @Environment(CookStore.self) private var store
    @State private var writer: NativePublicationWriter?
    @State private var loadedUser: String?
    @State private var items: [NativeCookbookItem] = []
    @State private var cursor: String?
    @State private var handle = ""
    @State private var name = ""
    @State private var isPublic = false
    @State private var profileReviewStatus: String?
    @State private var profilePreview: NativeCookbookProfile?
    @State private var removing: NativeCookbookItem?
    @State private var pending: NativePublicationOperation?
    @State private var pendingArchive: NativePreparedPublication?
    @State private var pendingProfile: NativeCookbookProfile?
    @State private var busy = false
    @State private var message: String?
    @State private var signingIn = false
    @State private var ownerPreview: NativePreparedPublication?
    @State private var ownerReceipt: NativePublicationReceipt?
    @State private var ownerUser: String?
    @State private var editingDraft: String?
    @State private var cookbookCard: CookbookCardRequest?
    @State private var cardPackRequested = false
    @State private var exportDraftId: String?
    @State private var exporting = false
    @State private var exportDocument = PitTechArchiveDocument(data: Data())
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if !account.signedIn {
                    Text("Your public cookbook and publications belong to your signed-in account. Your private library stays on this device.")
                    Button("Sign in") { signingIn = true }
                } else {
                    if let pending {
                        TurnInBannerText(pending.rejected ? "Rejected change" : "Saved interrupted change")
                        if let pendingArchive { NativePublicationContent(prepared: pendingArchive) }
                        else if let pendingProfile { profileSummary(pendingProfile) }
                        else {
                            Text(pending.path.hasSuffix("/like") ? (pending.method == "POST" ? "Add your like to this recipe" : "Remove your like from this recipe") : "Remove this publication from public access")
                            let parts = pending.path.split(separator: "/")
                            if parts.count >= 3, let url = URL(string: "https://pittech.app/c/" + parts[2]) { Link("View affected recipe", destination: url) }
                        }
                        if !pending.rejected { Button("Retry this exact change") { run { try checkAccount(); _ = try await writer?.recover(); try await reload() } }.disabled(busy).accessibilityIdentifier("cookbook-recover") }
                        else { Button("Discard rejected change", role: .destructive) { run { try checkAccount(); if let loadedUser { try writer?.store.discardRejected(loadedUser) }; try await reload() } }.disabled(busy) }
                    }
                    ownerEditingPreview
                    TurnInBannerText("Cookbook identity")
                    TextField("Display name", text: $name).turnInBoxedField().accessibilityIdentifier("cookbook-name")
                    TextField("Handle (3–24 letters, numbers or underscores)", text: $handle).turnInBoxedField().textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("cookbook-handle")
                    if profileReviewStatus == "pending" { TurnInStatusStrip(text: "Identity changes are awaiting review and are not yet public.", accent: PitTechStyle.marigold) }
                    Toggle("Enable my public cookbook", isOn: $isPublic).accessibilityIdentifier("cookbook-public")
                    Text("Named publications show your display name. Only named publications appear in an enabled public cookbook; anonymous publications stay separate.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    Button("Preview cookbook changes") { run {
                        guard handle.isEmpty || handle.range(of: "^[A-Za-z0-9_]{3,24}$", options: .regularExpression) != nil, name.utf16.count <= 160, !name.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }), !isPublic || !handle.isEmpty && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw NativePublicationError.invalid }
                        profilePreview = NativeCookbookProfile(handle: handle.isEmpty ? nil : handle.lowercased(), displayName: name.trimmingCharacters(in: .whitespacesAndNewlines), isPublic: isPublic)
                    } }.disabled(busy || pending != nil).accessibilityIdentifier("cookbook-preview")
                    if let profilePreview {
                        profileSummary(profilePreview)
                        Button("Save previewed changes") { run { try checkAccount(); _ = try await writer?.json(method: "PUT", path: "/v1/me/profile", body: JSONEncoder().encode(profilePreview)); self.profilePreview = nil; try await reload() } }.disabled(busy || pending != nil).accessibilityIdentifier("cookbook-save")
                    }
                    TurnInBannerText("My publications")
                    Text("Removing a publication hides its public link. Other cooks keep independent copies they already saved.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    publicationCards
                    if cursor != nil { Button("More publications") { run { try await loadItems(more: true) } }.disabled(busy) }
                    Button("Refresh cookbook") { run { try await reload() } }.disabled(busy)
                }
                if busy { ProgressView("Connecting…") }
                if let message { Text(message).accessibilityIdentifier("cookbook-message") }
            }.padding(20)
        }.accessibilityIdentifier("cookbook-content").navigationTitle("My cookbook").navigationBarTitleDisplayMode(.inline)
            .task(id: account.stateUserId) {
                if writer == nil { writer = NativePublicationWriter.forApp(session: account) }
                loadedUser = account.stateUserId; items = []; pending = nil; pendingArchive = nil; pendingProfile = nil; profilePreview = nil; removing = nil; name = ""; handle = ""; isPublic = false; profileReviewStatus = nil; ownerPreview = nil; ownerReceipt = nil; ownerUser = nil; editingDraft = nil
                guard loadedUser != nil else { return }
                do { try await reload() } catch { message = error.localizedDescription }
            }
            .onChange(of: name) { _, _ in profilePreview = nil }.onChange(of: handle) { _, _ in profilePreview = nil }.onChange(of: isPublic) { _, _ in profilePreview = nil }
            .confirmationDialog("Remove this publication from public access?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
                if let item = removing { Button("Remove publication", role: .destructive) { removing = nil; run { try checkAccount(); _ = try await writer?.json(method: "DELETE", path: "/v1/recipes/" + item.id, body: Data("{}".utf8), expected: item.revisionId); try await reload() } }.accessibilityIdentifier("cookbook-unpublish-confirm") }
            }
            .navigationDestination(isPresented: Binding(get: { editingDraft != nil }, set: { if !$0 { editingDraft = nil } })) { if let editingDraft { SharingDraftView(id: editingDraft) } }
            .sheet(item: $cookbookCard, onDismiss: { if cardPackRequested { cardPackRequested = false; if let exportDraftId { self.exportDraftId = nil; exportPack(exportDraftId) } } }) { request in
                NavigationStack { NativeShareCardPreview(id: request.draftId, publication: request.publication) { cardPackRequested = true; exportDraftId = request.draftId; cookbookCard = nil } }.modifier(ScreenshotTextSize())
            }
            .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .pittechCookPack, defaultFilename: "PitTech-Recipe") { result in
                if case .failure(let failure) = result { message = failure.localizedDescription }
                exportDocument = PitTechArchiveDocument(data: Data())
            }
            .sheet(isPresented: $signingIn) { NavigationStack { NativeAccountView() } }
            // Slice 8 (PL-15 / DD-10): sign-in completion returns to the
            // cookbook automatically; cancelling lands here too.
            .onChange(of: account.signedIn) { _, signedIn in if signedIn { signingIn = false } }
    }
    @ViewBuilder private var ownerEditingPreview: some View {
                    if let ownerPreview, let ownerReceipt {
                        TurnInBannerText("Private editing preview", font: PitTechFont.bannerTitle)
                        Text("This creates an editable sharing draft on this device. It does not publish or change your meal history.").foregroundStyle(PitTechStyle.inkMuted)
                        NativePublicationContent(prepared: ownerPreview)
                        Button("Create private editing draft") { run {
                            guard let writer, let user = ownerUser, user == account.stateUserId else { throw NativePublicationError.accountChanged }
                            let draft = try await Task.detached(priority: .userInitiated) { try NativeOwnedPublication.draft(ownerPreview, receipt: ownerReceipt) }.value
                            guard user == account.stateUserId else { throw NativePublicationError.accountChanged }
                            // Bind first. A failed library save can leave only harmless private metadata,
                            // never an unbound draft that silently changes an update into a publication.
                            try writer.store.attachEditContext(user, draft: draft.id, context: draft.context)
                            try store.saveOwnedPublicationDraft(draft); self.ownerPreview = nil; self.ownerReceipt = nil; editingDraft = draft.id
                        } }.disabled(busy || pending != nil).accessibilityIdentifier("cookbook-create-editing-draft")
                        Button("Cancel editing preview") { self.ownerPreview = nil; self.ownerReceipt = nil; ownerUser = nil }.disabled(busy)
                    }
    }
    private var publicationCards: some View {
                    ForEach(items) { item in
                        TurnInCard {
                        VStack(alignment: .leading, spacing: 8) {
                            TurnInBannerText(item.title)
                            Text(item.status == "active" ? "Public · revision \(item.revision) · \(item.identityMode == "named" ? "named" : "anonymous")" : item.status == "pending_review" ? "Submitted for review · not public" : item.status == "unpublished" ? "Removed from public access" : "Unavailable after moderation").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                            if ["active", "pending_review", "unpublished"].contains(item.status) {
                                Button("Preview an editable private copy") { run {
                                    try checkAccount(); guard let writer, let user = loadedUser else { throw NativePublicationError.accountChanged }
                                    let receipt = NativePublicationReceipt(id: item.id, revisionId: item.revisionId, revision: item.revision, url: "https://pittech.app/c/" + item.id)
                                    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip"); defer { try? FileManager.default.removeItem(at: file) }
                                    _ = try await writer.ownedArchive(receipt, to: file)
                                    let prepared = try await Task.detached(priority: .userInitiated) { try NativePreparedPublication.pending(file: file) }.value
                                    guard user == account.stateUserId, user == loadedUser else { throw NativePublicationError.accountChanged }
                                    ownerPreview = prepared; ownerReceipt = receipt; ownerUser = user
                                } }.disabled(busy || pending != nil).accessibilityIdentifier("cookbook-edit-" + item.id)
                            }
                            if item.status == "active" {
                                Link("Open publication", destination: URL(string: "https://pittech.app/c/" + item.id)!)
                                // Flowing Slice 3 (Spread 07): the
                                // posted card lives with the sharing
                                // draft that published this item.
                                Button("Share card") { shareCookbookCard(item) }.disabled(busy || pending != nil).accessibilityIdentifier("cookbook-share-card-" + item.id)
                            }
                            if ["active", "pending_review"].contains(item.status) {
                                Button(item.status == "pending_review" ? "Withdraw review request" : "Remove from public access", role: .destructive) { removing = item }.disabled(busy || pending != nil)
                            }
                        }
                        }
                    }
    }
    @ViewBuilder private func profileSummary(_ profile: NativeCookbookProfile) -> some View {
        TurnInCard {
            VStack(alignment: .leading, spacing: 8) {
                if profile.reviewStatus == "pending" { TurnInStatusStrip(text: "Identity changes are awaiting review and are not yet public.", accent: PitTechStyle.marigold) }
                TurnInBannerText("Public display name · " + (profile.displayName.isEmpty ? "a PitTech cook" : profile.displayName))
                Text(profile.isPublic ? "Public cookbook: https://pittech.app/p/" + (profile.handle ?? "Choose a handle") : "Public cookbook disabled. Your named recipe credit remains visible.").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            }
        }
    }
    /// Spread 07's My Cookbook door: resolve the publication back to
    /// the sharing draft that owns its card on THIS device, fetch the
    /// public detail for live counts, and open the posted card. A
    /// publication posted from another device has no draft here — say
    /// so plainly instead of failing.
    private func shareCookbookCard(_ item: NativeCookbookItem) {
        run {
            try checkAccount(); guard let writer else { throw NativePublicationError.accountChanged }
            guard let found = try writer.bindingForItem(item.id) else {
                message = "That recipe was posted from another device — its share card lives with the sharing draft on that device."
                return
            }
            let detail = try? await community.detail(item.id)
            cookbookCard = CookbookCardRequest(draftId: found.draft, publication: ShareCardPublication(itemId: item.id, likes: detail?.0.likes, forks: detail?.0.forks))
        }
    }
    private func exportPack(_ draftId: String) {
        Task { @MainActor in
            do { exportDocument = PitTechArchiveDocument(data: try await store.exportCookPackAsync(draftId, includeRecordedCook: false)); exporting = true }
            catch { message = error.localizedDescription }
        }
    }
    private func checkAccount() throws { guard loadedUser != nil, loadedUser == account.stateUserId else { throw NativePublicationError.accountChanged } }
    private func pendingPreview() throws {
        pending = try writer?.pending(); pendingArchive = nil; pendingProfile = nil
        if let pending, let writer {
            let file = try writer.store.verify(pending)
            if pending.contentType == "application/zip" { pendingArchive = try NativePreparedPublication.pending(file: file) }
            else if pending.path == "/v1/me/profile" { pendingProfile = try JSONDecoder().decode(NativeCookbookProfile.self, from: Data(contentsOf: file)) }
        }
    }
    private func reload() async throws {
        try checkAccount(); try pendingPreview()
        guard let writer else { return }
        let profile = try JSONDecoder().decode(NativeCookbookProfile.self, from: await writer.read("/v1/me/profile")); try checkAccount()
        name = profile.displayName; handle = profile.handle ?? ""; isPublic = profile.isPublic; profileReviewStatus = profile.reviewStatus
        try await loadItems(more: false)
    }
    private func loadItems(more: Bool) async throws {
        try checkAccount(); guard let writer else { return }
        let page = try JSONDecoder().decode(NativeCookbookPage.self, from: await writer.read("/v1/me/recipes" + (more ? cursor.map { "?cursor=" + $0 } ?? "" : "")))
        guard page.items.count <= 20, page.nextCursor.map({ UUID(uuidString: $0) != nil }) ?? true else { throw NativePublicationError.invalid }
        for item in page.items { try item.validate() }; try checkAccount()
        items = more ? items + page.items.filter { new in !items.contains { $0.id == new.id } } : page.items; cursor = page.nextCursor
    }
    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; message = nil
        Task { @MainActor in do { try await action() } catch { message = error.localizedDescription; try? pendingPreview() }; busy = false }
    }
}
