import SwiftUI

struct NativePublicDiscoveryView: View {
    @Environment(NativePublicCoordinator.self) private var community
    @State private var query = ""
    @State private var food = ""
    @State private var sort = "liked"
    @State private var initialized = false
    var body: some View {
        NavigationStack(path: Binding(get: { community.path }, set: { community.path = $0 })) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("Find your next cook").font(.title.bold())
                    Text("Browse, save and cook without an account. Saving makes your own private copy.").foregroundStyle(.secondary)
                    TextField("Search recipes", text: $query).textFieldStyle(.roundedBorder).accessibilityIdentifier("discover-query")
                    TextField("Food type (optional)", text: $food).textFieldStyle(.roundedBorder).accessibilityIdentifier("discover-food")
                    Picker("Sort", selection: $sort) { Text("Most liked").tag("liked"); Text("Most forked").tag("forked") }.pickerStyle(.segmented)
                    Button("Search recipes") { Task { await community.refresh(sort: sort, food: food, query: query) } }.disabled(community.loading).accessibilityIdentifier("discover-search")
                    if community.loading { ProgressView("Loading recipes…") }
                    if let message = community.message { Text(message).accessibilityIdentifier("discover-message") }
                    if community.items.isEmpty && !community.loading && community.message == nil { Text("No recipes here yet. Try another food or search.").accessibilityIdentifier("discover-empty") }
                    LazyVStack(spacing: 14) {
                        ForEach(community.items) { item in
                            NavigationLink(value: item.id) {
                                VStack(alignment: .leading, spacing: 8) {
                                    Label(item.title, systemImage: "book.closed").font(.headline)
                                    Text(item.credit.displayName).font(.subheadline)
                                    Text("\(item.likes) likes · \(item.forks) forks").font(.caption)
                                }.frame(maxWidth: .infinity, minHeight: 72, alignment: .leading).padding(16)
                                    .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                            }.buttonStyle(.plain).accessibilityIdentifier("discover-open-" + item.id)
                        }
                    }
                    if community.nextCursor != nil { Button("More recipes") { Task { await community.refresh(sort: community.appliedSort, food: community.appliedFood, query: community.appliedQuery, more: true) } }.disabled(community.loading) }
                }.padding(20)
            }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Discover").navigationBarTitleDisplayMode(.inline)
                .navigationDestination(for: String.self) { value in
                    if value.hasPrefix("profile:") { NativePublicProfileView(handle: String(value.dropFirst(8))) } else { NativePublicRecipeView(id: value) }
                }
                .task { if !initialized { sort = community.appliedSort; food = community.appliedFood; query = community.appliedQuery; initialized = true }; if community.items.isEmpty { await community.refresh(sort: sort, food: food, query: query) } }
        }
    }
}
struct NativePublicRecipeView: View {
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(NativePublicCoordinator.self) private var community
    @Environment(CookStore.self) private var store
    let id: String
    @State private var detail: NativePublicDetail?
    @State private var image: Data?
    @State private var loading = false
    @State private var message: String?
    @State private var task: Task<Void, Never>?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if loading { ProgressView("Loading recipe…") }
                if let detail {
                    if let image, let picture = UIImage(data: image) {
                        Image(uiImage: picture).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 16)).accessibilityLabel(detail.heroCaption.isEmpty ? "Selected photograph for " + detail.title : detail.heroCaption)
                        if !detail.heroCaption.isEmpty { Text(detail.heroCaption).font(.caption) }
                    }
                    Text(detail.title).font(.title.bold()).accessibilityIdentifier("public-title")
                    Text("Shared by " + detail.credit.displayName)
                    if let url = detail.credit.profileUrl, let destination = try? NativePublicLink.destination(url), destination.hasPrefix("profile:") { NavigationLink("View public cookbook", value: destination) }
                    if account.enabled && community.enabled { NativePublicLikeActions(id: id) }
                    NativeCommunityActions(detail: detail)
                    Text("Your saved version stays yours. Publishing a variation is a separate choice.").font(.subheadline).foregroundStyle(.secondary)
                    HStack {
                        Button("Save recipes") { preview(detail, cook: false) }.buttonStyle(.borderedProminent).accessibilityIdentifier("public-save")
                        Button("Cook this") { preview(detail, cook: true) }.buttonStyle(.bordered).accessibilityIdentifier("public-cook")
                    }.disabled(community.preparing)
                    if community.preparing { ProgressView("Preparing your preview…"); Button("Cancel download") { task?.cancel() } }
                    ForEach(detail.recipes.indices, id: \.self) { index in
                        let recipe = detail.recipes[index].objectValue ?? [:]
                        Text(recipe["title"]?.stringValue ?? "Recipe").font(.title2.bold())
                        if let source = recipe["source"]?.objectValue {
                            if source["removed"]?.boolValue == true { Text("Based on a removed recipe").font(.subheadline).foregroundStyle(.secondary) }
                            else if let item = source["itemId"]?.stringValue, UUID(uuidString: item) != nil {
                                NavigationLink("Based on a recipe shared by " + (source["displayName"]?.stringValue ?? "a PitTech cook"), value: item).font(.subheadline)
                            }
                        }
                        Text(recipe["description"]?.stringValue ?? "")
                        if let grams = recipe["weightGrams"]?.numberValue { Text("Recorded weight · \(grams / 1000, specifier: "%.2f") kg") }
                        let ingredients = recipe["ingredients"]?.arrayValue ?? []
                        ForEach(ingredients.indices, id: \.self) { ingredient in
                            let row = ingredients[ingredient].objectValue ?? [:]
                            Text((row["name"]?.stringValue ?? "") + " · " + (row["amount"]?.stringValue ?? ""))
                        }
                        let instructions = recipe["instructions"]?.arrayValue ?? []
                        ForEach(instructions.indices, id: \.self) { step in
                            let row = instructions[step].objectValue ?? [:]
                            Text(row["title"]?.stringValue ?? "Step").font(.headline)
                            if let method = row["method"]?.stringValue, !method.isEmpty { Text("Method · " + method).font(.subheadline) }
                            Text(row["details"]?.stringValue ?? "")
                        }
                        if let story = recipe["story"]?.objectValue { NativePublicStoryView(story: story) }
                        let assets = recipe["assets"]?.arrayValue ?? []
                        ForEach(assets.indices, id: \.self) { photo in
                            let row = assets[photo].objectValue ?? [:]
                            if let medium = detail.media.first(where: { $0.id == row["id"]?.stringValue }), medium.id != detail.hero?.id {
                                NativePublicAdditionalPhoto(detail: detail, asset: medium, caption: row["caption"]?.stringValue ?? "")
                            }
                        }
                    }
                }
                if let message { Text(message).accessibilityIdentifier("public-message") }
                if let error = community.message { Text(error).accessibilityIdentifier("public-download-message") }
                Button("Refresh preview") { Task { await load() } }.disabled(loading || community.preparing)
            }.padding(20)
        }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Community recipe").navigationBarTitleDisplayMode(.inline)
            .task(id: id) { await load() }
            .onDisappear { task?.cancel() }
    }
    private func load() async {
        loading = true; defer { loading = false }
        do {
            let (value, saved) = try await community.detail(id)
            let loadedImage = try? await community.image(value)
            // Present the hero and actions together so a late photo cannot move
            // the save/cook controls underneath an in-progress tap.
            try Task.checkCancellation()
            detail = value; image = loadedImage
            message = saved ? "Showing a saved preview. Connect to check for updates." : nil
        } catch { detail = nil; image = nil; message = (error as? NativePublicError)?.localizedDescription ?? "This recipe couldn't be read safely. Try refreshing." }
    }
    private func preview(_ detail: NativePublicDetail, cook: Bool) { task = Task { await community.preview(detail, cook: cook, store: store) } }
}
#if DEBUG
struct NativePublicFixtureAPI: NativePublicAPI {
    let fixture: [String: Any]
    init(data: Data) { fixture = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:] }
    func json(_ path: String) async throws -> Data { try JSONSerialization.data(withJSONObject: fixture[path.hasPrefix("/v1/discover?") ? "feed" : "detail"]!) }
    func pack(_ id: String, revision: String, to: URL) async throws -> URL {
        let detail = fixture["detail"] as! [String: Any]
        guard detail["id"] as? String == id, detail["revisionId"] as? String == revision, let bytes = Data(base64Encoded: fixture["packBase64"] as! String) else { throw NativePublicError.invalid }
        try bytes.write(to: to, options: .atomic); return to
    }
    func image(_ id: String, asset: NativePublicDetail.Media) async throws -> Data { Data(base64Encoded: fixture["imageBase64"] as! String)! }
}
#endif
@MainActor extension NativePublicCoordinator {
    static func forApp() -> NativePublicCoordinator {
        #if DEBUG
        if NativeCommunityUITestSupport.active { return NativeCommunityUITestSupport.community() }
        if CommandLine.arguments.contains("--discovery-ui-test") || NativePublicationUITestSupport.active { return NativePublicCoordinator(enabled: true, api: NativePublicFixtureAPI(data: NativePublicUITestFixture.data), cache: NativePublicCache(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))) }
        #endif
        return NativePublicCoordinator()
    }
}
