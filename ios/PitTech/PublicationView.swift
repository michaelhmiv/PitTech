import SwiftUI
import ImageIO

struct NativePublicationView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CookStore.self) private var store
    @Environment(NativeAccountCoordinator.self) private var account
    let draft: String
    @State private var writer: NativePublicationWriter?
    @State private var prepared: NativePreparedPublication?
    @State private var previewUser: String?
    @State private var identity = "anonymous"
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
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Publish a selected recipe, collection or actual cook. Anyone can read and save a public copy.").foregroundStyle(.secondary)
                if !account.signedIn {
                    Text("Sign in to publish. This sharing draft stays on this device, and sign-in won't publish it.")
                    Button("Sign in to continue") { signingIn = true }.accessibilityIdentifier("publication-sign-in")
                } else if let pending {
                    Text(pending.rejected ? "This request was rejected" : "An interrupted request is saved").font(.headline)
                    Text("Retry uses the exact saved content and the same account. Editing the sharing draft won't change this request.")
                    if let prepared { NativePublicationContent(prepared: prepared) }
                    else { Text(pending.method == "DELETE" ? "Saved request: remove a public publication or like." : "A saved account or like change must be recovered in My cookbook.") }
                    if pending.draftId == draft, prepared != nil, !pending.rejected {
                        Button("Retry the saved publication") { run { _ = try await writer?.recover(); prepared = nil; try refreshPending(); message = "The saved publication was recovered." } }.disabled(busy).accessibilityIdentifier("publication-recover")
                    }
                    if pending.rejected {
                        Text("Review the current publication before making a new preview. A rejection did not apply this request.")
                        Button("Discard rejected request", role: .destructive) { run { if let user = account.stateUserId { try writer?.store.discardRejected(user) }; prepared = nil; try refreshPending() } }.disabled(busy)
                    }
                } else if let published {
                    Text("Published").font(.title2.bold()).accessibilityIdentifier("publication-complete")
                    Link("Open public recipe", destination: URL(string: published.url)!)
                    ShareLink("Share public link", item: URL(string: published.url)!)
                    Text("Your local recipe and measured results stay on this device. Independent copies saved by other cooks remain theirs.")
                } else {
                    Picker("Publication", selection: $kind) { Text("Recipe").tag("recipe"); Text("Meal collection").tag("collection"); Text("Actual recorded cook").tag("attempt") }.disabled(busy)
                    Picker("Public credit", selection: $identity) { Text("Anonymous · a PitTech cook").tag("anonymous"); Text("My display name").tag("named") }.disabled(busy)
                    Text(identity == "anonymous" ? "Your profile name and cookbook link won't appear on this publication." : "Your current display name appears publicly. Your cookbook link appears only while your public cookbook is enabled.").font(.subheadline)
                    if linked != nil { Toggle("Update the publication linked to this draft", isOn: $updating).disabled(busy) }
                    Button("Prepare public preview") { run { try await prepare() } }.disabled(busy).accessibilityIdentifier("publication-prepare")
                    if let prepared {
                        Text("Public credit · " + displayName).font(.headline).accessibilityIdentifier("publication-credit")
                        if let updateSummary { Text(updateSummary).font(.headline) }
                        NativePublicationContent(prepared: prepared)
                        Toggle("I reviewed the selected content and public credit", isOn: $reviewed).accessibilityIdentifier("publication-reviewed")
                        Button(updating && linked != nil ? "Publish this update" : "Publish this preview") { run {
                            guard reviewed, previewUser == account.stateUserId, let writer else { throw NativePublicationError.accountChanged }
                            let response = try await writer.submit(draft: draft, prepared: prepared, update: updating ? linked : nil)
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
                prepared = nil; reviewed = false; published = nil
                do { try refreshPending(); if pending == nil, let prior = linked?.kind { kind = prior } } catch { message = error.localizedDescription }
            }
            .onChange(of: identity) { _, _ in invalidate() }.onChange(of: kind) { _, _ in invalidate() }.onChange(of: updating) { _, _ in invalidate() }
            .sheet(isPresented: $signingIn) { NavigationStack { NativeAccountView() } }
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
        var document = try store.sharingDocument(draft); document.identityMode = identity
        try store.saveSharingDraft(draft, document: document, expectedUpdated: store.sharingRecord(draft)?["updated"]?.numberValue ?? 0)
        let value = try await store.preparePublication(draft, kind: kind)
        guard account.stateUserId == user else { throw NativePublicationError.accountChanged }
        linked = try writer.binding(draft); updateSummary = nil
        if let old = linked, updating {
            let bytes = try await writer.read("/v1/me/recipes/" + old.id)
            guard let current = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], current["id"] as? String == old.id,
                  let revision = current["revision"] as? Int, let revisionId = current["revisionId"] as? String,
                  let status = current["status"] as? String, ["active", "unpublished"].contains(status) else { throw NativePublicationError.invalid }
            let receipt = NativePublicationReceipt(id: old.id, revisionId: revisionId, revision: revision, url: old.url); try receipt.validate(); linked = receipt
            updateSummary = status == "unpublished" ? "This publication is currently removed. Publishing this preview will make it public again." : "This preview will replace public revision \(revision)."
        }
        guard account.stateUserId == user else { throw NativePublicationError.accountChanged }
        prepared = value; previewUser = user; reviewed = false
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
            Text(payload["title"]?.stringValue ?? "Public preview").font(.title.bold()).accessibilityIdentifier("publication-preview-title")
            Text(payload["kind"]?.stringValue == "attempt" ? "Actual recorded cook" : payload["kind"]?.stringValue == "collection" ? "Meal collection" : "Recipe").font(.headline)
            Text("\(recipes.count) selected recipes · \(prepared.photos.count) selected photos")
            Text("Public credit: " + (payload["identityMode"]?.stringValue == "named" ? "your display name" : "a PitTech cook"))
            ForEach(recipes.indices, id: \.self) { index in
                let recipe = recipes[index]
                Text(recipe["title"]?.stringValue ?? "Recipe").font(.title2.bold())
                Text(recipe["description"]?.stringValue ?? "")
                Text([recipe["foodType"]?.stringValue, recipe["cut"]?.stringValue, recipe["method"]?.stringValue].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                if let weight = recipe["weightGrams"]?.numberValue { Text("Weight · \(weight / 1000, specifier: "%.2f") kg") }
                Text((recipe["tags"]?.arrayValue ?? []).compactMap(\.stringValue).joined(separator: " · ")).font(.caption)
                if let source = recipe["source"]?.objectValue, let id = source["itemId"]?.stringValue {
                    Text(source["removed"]?.boolValue == true ? "Based on a removed recipe" : "Based on a pinned public recipe")
                    if source["removed"]?.boolValue != true, let url = URL(string: "https://pittech.app/c/" + id) { Link("View source recipe", destination: url) }
                }
                let ingredients = (recipe["ingredients"]?.arrayValue ?? []).compactMap(\.objectValue)
                ForEach(ingredients.indices, id: \.self) { row in Text((ingredients[row]["name"]?.stringValue ?? "") + " · " + (ingredients[row]["amount"]?.stringValue ?? "")) }
                let instructions = (recipe["instructions"]?.arrayValue ?? []).compactMap(\.objectValue)
                ForEach(instructions.indices, id: \.self) { row in
                    Text(instructions[row]["title"]?.stringValue ?? "Step").font(.headline)
                    Text(instructions[row]["details"]?.stringValue ?? "")
                    if let method = instructions[row]["method"]?.stringValue { Text("Method · " + method) }
                    if let action = instructions[row]["action"]?.stringValue { Text("Action · " + action) }
                    if let offset = instructions[row]["suggestedOffsetMillis"]?.numberValue { Text("Suggested timing · " + RecipeReferenceTimeline.relative(offset)) }
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
                Button("Like this recipe") { set(true) }.disabled(busy).accessibilityIdentifier("public-like")
                Button("Remove my like") { set(false) }.disabled(busy).accessibilityIdentifier("public-unlike")
            } else {
                Button("Sign in to like this recipe") { signingIn = true }
                Text("Signing in won't add a like automatically.").font(.caption)
            }
            if let message { Text(message) }
        }.task { if writer == nil { writer = NativePublicationWriter.forApp(session: account) } }
            .onChange(of: account.stateUserId) { _, _ in message = nil }
            .sheet(isPresented: $signingIn) { NavigationStack { NativeAccountView() } }
    }
    private func set(_ liked: Bool) {
        guard !busy else { return }; busy = true
        Task { @MainActor in
            do {
                guard let writer else { throw NativePublicationError.invalid }
                _ = try await writer.json(method: liked ? "POST" : "DELETE", path: "/v1/recipes/" + id + "/like", body: Data("{}".utf8))
                message = liked ? "Your like is saved." : "Your like is removed."
            } catch { message = error.localizedDescription + " Recover interrupted changes in My cookbook." }
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
                Text("Selected photo \(index + 1) of \(assets.count)").font(.headline)
                if let image { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 260).accessibilityLabel(asset["caption"]?.stringValue ?? "Selected public photo") }
                if hero == asset["id"]?.stringValue { Text("Cover photo").font(.caption) }
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
