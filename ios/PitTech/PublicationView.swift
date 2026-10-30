import SwiftUI
import ImageIO
import UniformTypeIdentifiers

struct NativePublicationView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CookStore.self) private var store
    @Environment(NativeAccountCoordinator.self) private var account
    let draft: String
    @State private var writer: NativePublicationWriter?
    @State private var prepared: NativePreparedPublication?
    @State private var previewUser: String?
    @State private var identity = "anonymous"
    @State private var category = FoodCategory.defaultId
    @State private var kind = "recipe"
    @State private var linked: NativePublicationReceipt?
    @State private var updating = true
    @State private var updateSummary: String?
    @State private var pending: NativePublicationOperation?
    @State private var displayName = "a PitTech cook"
    @State private var busy = false
    @State private var reviewed = false
    @State private var signingIn = false
    @State private var message: String?
    @State private var published: NativePublicationReceipt?
    @State private var lastUserId: String?
    @State private var postedCard = false
    @State private var cardPackRequested = false
    @State private var exporting = false
    @State private var exportDocument = PitTechArchiveDocument(data: Data())
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Publish a selected recipe, collection or actual cook. Anyone can read and save a public copy.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                if !account.signedIn {
                    Text("Sign in to publish. This sharing draft stays on this device, and sign-in won't publish it.")
                    Button("Sign in to continue") { signingIn = true }.accessibilityIdentifier("publication-sign-in")
                } else if let pending {
                    TurnInBannerText(pending.rejected ? "This request was rejected" : "An interrupted request is saved")
                    Text("Retry uses the exact saved content and the same account. Editing the sharing draft won't change this request.").foregroundStyle(PitTechStyle.inkMuted)
                    if let prepared { NativePublicationContent(prepared: prepared) }
                    else { Text(pending.method == "DELETE" ? "Saved request: remove a public publication or like." : "A saved account or like change must be recovered in Public cookbook.") }
                    if pending.draftId == draft, prepared != nil, !pending.rejected {
                        Button("Retry the saved publication") { run { _ = try await writer?.recover(); prepared = nil; try refreshPending(); message = "The saved publication was recovered." } }.disabled(busy).accessibilityIdentifier("publication-recover")
                    }
                    if pending.rejected {
                        Text("Review the current publication before making a new preview. A rejection did not apply this request.")
                        Button("Discard rejected request", role: .destructive) { run { if let user = account.stateUserId { try writer?.store.discardRejected(user) }; prepared = nil; try refreshPending() } }.disabled(busy)
                    }
                } else if let published {
                    Text(published.status == "pending_review" ? "Submitted for review" : "Published").font(PitTechFont.bannerTitle).accessibilityIdentifier("publication-complete")
                    if published.status == "pending_review" { Text("This revision becomes public after its text and selected photos pass review. Check Public cookbook for its status.") }
                    else {
                        Link("Open public recipe", destination: URL(string: published.url)!); ShareLink("Share public link", item: URL(string: published.url)!)
                        // Flowing Slice 3 (Spread 07): the posted
                        // recipe's card, QR footer and all.
                        Button("Share posted card") { postedCard = true }.accessibilityIdentifier("publication-share-card")
                    }
                    // J9: after publishing, the next step lands where the
                    // thing went — Public cookbook — not back at the draft.
                    NavigationLink("Open Public cookbook") { NativeMyCookbookView() }.accessibilityIdentifier("publication-open-cookbook")
                    Text("Your local recipe and measured results stay on this device. Independent copies saved by other cooks remain theirs.")
                } else {
                    Picker("Publication", selection: $kind) { Text("Recipe").tag("recipe"); Text("Meal collection").tag("collection"); Text("Actual recorded cook").tag("attempt") }.disabled(busy)
                    Picker("Public credit", selection: $identity) { Text("Anonymous · a PitTech cook").tag("anonymous"); Text("My display name").tag("named") }.disabled(busy)
                    Picker("Category", selection: $category) {
                        ForEach(FoodCategory.all, id: \.id) { option in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(option.label)
                                Text(option.examples.joined(separator: " · ")).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                            }.tag(option.id)
                        }
                    }.disabled(busy).accessibilityIdentifier("publication-category")
                    Text(identity == "anonymous" ? "Your profile name and cookbook link won't appear on this publication." : "Your current display name appears publicly. Your cookbook link appears only while your public cookbook is enabled.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    if linked != nil { Toggle("Update the publication linked to this draft", isOn: $updating).disabled(busy) }
                    Button("Prepare public preview") { run { try await prepare() } }.disabled(busy).accessibilityIdentifier("publication-prepare")
                    if let prepared {
                        Text("Public credit · " + displayName).font(PitTechFont.bannerCard).accessibilityIdentifier("publication-credit")
                        if let updateSummary { TurnInStatusStrip(text: updateSummary, accent: PitTechStyle.marigold) }
                        NativePublicationContent(prepared: prepared)
                        Text(NativePublicationTerms.text).font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted).accessibilityIdentifier("publication-terms")
                        Toggle("I reviewed the content and credit and agree to these public-copy terms", isOn: $reviewed).accessibilityIdentifier("publication-reviewed")
                        Button(updating && linked != nil ? "Publish this update" : "Publish this preview") { run {
                            guard reviewed, previewUser == account.stateUserId, let writer else { throw NativePublicationError.accountChanged }
                            let response = try await writer.submit(draft: draft, prepared: prepared, update: updating ? linked : nil, acceptedTerms: NativePublicationTerms.version)
                            published = try JSONDecoder().decode(NativePublicationReceipt.self, from: response); try published?.validate(); self.prepared = nil; try refreshPending()
                        } }.buttonStyle(.borderedProminent).disabled(busy || !reviewed).accessibilityIdentifier("publication-confirm")
                    }
                }
                if busy { ProgressView("Connecting…") }
                if let message { Text(message).accessibilityIdentifier("publication-message") }
            }.padding(20)
        }.accessibilityIdentifier("publication-content").navigationTitle("Publish preview").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.disabled(busy).accessibilityIdentifier("publication-done") } }
            .task(id: account.stateUserId) {
                if writer == nil { writer = NativePublicationWriter.forApp(session: account) }
                // PL-18: a prepared preview survives the sign-in detour.
                // It is only discarded when the signed-in account actually
                // changes to a different user than the one who prepared it
                // (the confirm step separately re-checks exactly that).
                // Signing out clears the published receipt but keeps the
                // preview state, which stays inaccessible behind this
                // sheet's signed-out branch until someone signs in — and a
                // different returning user invalidates it via previewUser.
                if let current = account.stateUserId {
                    if let previewUser, previewUser != current { invalidate() }
                    if let lastUserId, lastUserId != current { published = nil }
                    lastUserId = current
                } else {
                    published = nil
                }
                do { let document = try? store.sharingDocument(draft); identity = document?.identityMode ?? "anonymous"; category = document?.category ?? FoodCategory.defaultId; try refreshPending(); if pending == nil, let prior = linked?.kind { kind = prior } } catch { message = error.localizedDescription }
            }
            .onChange(of: identity) { _, _ in invalidate() }.onChange(of: category) { _, _ in invalidate() }.onChange(of: kind) { _, _ in invalidate() }.onChange(of: updating) { _, _ in invalidate() }
            .sheet(isPresented: $signingIn) { NavigationStack { NativeAccountView() } }
            .sheet(isPresented: $postedCard, onDismiss: { if cardPackRequested { cardPackRequested = false; exportPack() } }) {
                if let published { NavigationStack { NativeShareCardPreview(id: draft, publication: ShareCardPublication(itemId: published.id)) { cardPackRequested = true; postedCard = false } }.modifier(ScreenshotTextSize()) }
            }
            .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .pittechCookPack, defaultFilename: "PitTech-Recipe") { result in
                if case .failure(let failure) = result { message = failure.localizedDescription }
                exportDocument = PitTechArchiveDocument(data: Data())
            }
            // Slice 8 (PL-15 / DD-10): completing sign-in returns to this
            // sheet on its own; cancelling the account sheet lands here too.
            .onChange(of: account.signedIn) { _, signedIn in if signedIn { signingIn = false } }
    }
    private func invalidate() { prepared = nil; reviewed = false; previewUser = nil; updateSummary = nil }
    private func refreshPending() throws {
        pending = try writer?.pending(); linked = try writer?.binding(draft)
        if let pending, pending.draftId == draft, pending.contentType == "application/zip", let writer {
            prepared = try NativePreparedPublication.pending(file: writer.store.verify(pending))
        }
    }
    private func prepare() async throws {
        guard let writer, let user = account.stateUserId else { throw NativePublicationError.accountChanged }
        invalidate()
        let profile = try JSONSerialization.jsonObject(with: await writer.read("/v1/me/profile")) as? [String: Any]
        displayName = identity == "anonymous" ? "a PitTech cook" : (profile?["displayName"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "a PitTech cook"
        var document = try store.sharingDocument(draft); document.identityMode = identity; document.category = category
        try store.saveSharingDraft(draft, document: document, expectedUpdated: store.sharingRecord(draft)?["updated"]?.numberValue ?? 0)
        let context: NativePublicationEditContext?
        if updating && linked != nil { context = try writer.editContext(draft) } else { context = nil }
        let value = try await store.preparePublication(draft, kind: kind, editContext: context)
        guard account.stateUserId == user else { throw NativePublicationError.accountChanged }
        linked = try writer.binding(draft); updateSummary = nil
        if let old = linked, updating {
            let bytes = try await writer.read("/v1/me/recipes/" + old.id)
            guard let current = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], current["id"] as? String == old.id,
                  let revision = current["revision"] as? Int, let revisionId = current["revisionId"] as? String,
                  let status = current["status"] as? String, ["active", "pending_review", "unpublished"].contains(status) else { throw NativePublicationError.invalid }
            if let context, context.receipt.revisionId != revisionId { throw NativePublicError.status(409) }
            let receipt = NativePublicationReceipt(id: old.id, revisionId: revisionId, revision: revision, url: old.url); try receipt.validate(); linked = receipt
            updateSummary = status == "unpublished" ? "This publication is currently removed. Publishing this preview will make it public again." : "This preview will replace public revision \(revision)."
        }
        guard account.stateUserId == user else { throw NativePublicationError.accountChanged }
        prepared = value; previewUser = user; reviewed = false
    }
    private func exportPack() {
        Task { @MainActor in
            do { exportDocument = PitTechArchiveDocument(data: try await store.exportCookPackAsync(draft, includeRecordedCook: false)); exporting = true }
            catch { message = error.localizedDescription }
        }
    }
    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; message = nil
        Task { @MainActor in
            do { try await action() } catch { message = error.localizedDescription; try? refreshPending() }
            busy = false
        }
    }
}

struct NativePublicationContent: View {
    let prepared: NativePreparedPublication
    private var payload: [String: JSONValue] { prepared.payload.objectValue ?? [:] }
    private var recipes: [[String: JSONValue]] { (payload["recipes"]?.arrayValue ?? []).compactMap(\.objectValue) }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(payload["title"]?.stringValue ?? "Public preview").font(PitTechFont.bannerTitle).accessibilityIdentifier("publication-preview-title")
            Text(payload["kind"]?.stringValue == "attempt" ? "Actual recorded cook" : payload["kind"]?.stringValue == "collection" ? "Meal collection" : "Recipe").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            Text("\(recipes.count) selected recipes · \(prepared.photos.count) selected photos").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            Text("Public credit: " + (payload["identityMode"]?.stringValue == "named" ? "your display name" : "a PitTech cook"))
            ForEach(recipes.indices, id: \.self) { index in
                let recipe = recipes[index]
                TurnInBannerText(recipe["title"]?.stringValue ?? "Recipe")
                Text(recipe["description"]?.stringValue ?? "")
                Text([recipe["foodType"]?.stringValue, recipe["cut"]?.stringValue, recipe["method"]?.stringValue].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                if let weight = recipe["weightGrams"]?.numberValue { Text("Weight · \(weight / 1000, specifier: "%.2f") kg").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
                Text((recipe["tags"]?.arrayValue ?? []).compactMap(\.stringValue).joined(separator: " · ")).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                if let source = recipe["source"]?.objectValue, let id = source["itemId"]?.stringValue {
                    Text(source["removed"]?.boolValue == true ? "Based on a removed recipe" : "Based on a pinned public recipe")
                    if source["removed"]?.boolValue != true, let url = URL(string: "https://pittech.app/c/" + id) { Link("View source recipe", destination: url) }
                }
                let ingredients = (recipe["ingredients"]?.arrayValue ?? []).compactMap(\.objectValue)
                ForEach(ingredients.indices, id: \.self) { row in Text((ingredients[row]["name"]?.stringValue ?? "") + " · " + (ingredients[row]["amount"]?.stringValue ?? "")) }
                let instructions = (recipe["instructions"]?.arrayValue ?? []).compactMap(\.objectValue)
                ForEach(instructions.indices, id: \.self) { row in
                    Text(instructions[row]["title"]?.stringValue ?? "Step").font(PitTechFont.bodyEmphasis)
                    Text(instructions[row]["details"]?.stringValue ?? "")
                    if let method = instructions[row]["method"]?.stringValue { Text("Method · " + method).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
                    if let action = instructions[row]["action"]?.stringValue { Text("Action · " + action).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
                    if let offset = instructions[row]["suggestedOffsetMillis"]?.numberValue { Text("Suggested timing · " + RecipeReferenceTimeline.relative(offset)).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
                }
                let assets = (recipe["assets"]?.arrayValue ?? []).compactMap(\.objectValue)
                NativePublicationPhotoPreview(assets: assets, photos: prepared.photos, hero: payload["heroAssetId"]?.stringValue)
                if let story = recipe["story"]?.objectValue { NativePublicStoryView(story: story) }
                Divider()
            }
        }
    }
}

struct NativePublicLikeActions: View {
    @Environment(NativeAccountCoordinator.self) private var account
    let id: String
    @State private var writer: NativePublicationWriter?
    @State private var busy = false
    @State private var signingIn = false
    @State private var message: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if account.signedIn {
                // §7 disposition: the Star is a placard, not a text button
                // (Slice 5 renames the Like to its spec name; the endpoint
                // is the same POST/DELETE /v1/recipes/<id>/like). This
                // surface carries no star state or count, so the placard
                // keeps the action copy; Remove stays a button.
                TurnInPlacard(systemImage: "star.fill", label: "Star this recipe", identifier: "public-star") { set(true) }.disabled(busy)
                Button("Remove my star") { set(false) }.disabled(busy).accessibilityIdentifier("public-unstar")
            } else {
                Button("Sign in to star this recipe") { signingIn = true }
                Text("Signing in won't add a star automatically.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
            }
            if let message { Text(message) }
        }.task { if writer == nil { writer = NativePublicationWriter.forApp(session: account) } }
            .onChange(of: account.stateUserId) { _, _ in message = nil }
            // Slice 8 (PL-15 / DD-10): sign-in completion closes the
            // account sheet and returns here automatically.
            .onChange(of: account.signedIn) { _, signedIn in if signedIn { signingIn = false } }
            .sheet(isPresented: $signingIn) { NavigationStack { NativeAccountView() } }
    }
    private func set(_ liked: Bool) {
        guard !busy else { return }; busy = true
        Task { @MainActor in
            do {
                guard let writer else { throw NativePublicationError.invalid }
                _ = try await writer.json(method: liked ? "POST" : "DELETE", path: "/v1/recipes/" + id + "/like", body: Data("{}".utf8))
                message = liked ? "Your star is saved." : "Your star is removed."
            } catch { message = error.localizedDescription + " Recover interrupted changes in Public cookbook." }
            busy = false
        }
    }
}

private struct NativePublicationPhotoPreview: View {
    let assets: [[String: JSONValue]]
    let photos: [String: Data]
    let hero: String?
    @State private var selected = 0
    @State private var image: UIImage?
    private var index: Int { min(selected, max(0, assets.count - 1)) }
    private var asset: [String: JSONValue] { assets.isEmpty ? [:] : assets[index] }
    var body: some View {
        if !assets.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                TurnInBannerText("Selected photo \(index + 1) of \(assets.count)")
                if let image { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 260).accessibilityLabel(asset["caption"]?.stringValue ?? "Selected public photo") }
                if hero == asset["id"]?.stringValue { Text("Cover photo").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.attentionText) }
                Text(asset["caption"]?.stringValue ?? "")
                if assets.count > 1 {
                    HStack { Button("Previous photo") { selected = max(0, index - 1) }.disabled(index == 0); Button("Next photo") { selected = min(assets.count - 1, index + 1) }.disabled(index == assets.count - 1) }.frame(minHeight: 44)
                }
            }.task(id: asset["id"]?.stringValue) {
                image = nil
                if let id = asset["id"]?.stringValue, let bytes = photos[id], let source = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                   let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 800] as CFDictionary) { image = UIImage(cgImage: thumbnail) }
            }
        }
    }
}
