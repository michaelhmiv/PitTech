import SwiftUI

struct NativePublicProfileView: View {
    @Environment(NativePublicCoordinator.self) private var community
    let handle: String
    @State private var profile: NativePublicProfile?
    @State private var items: [NativePublicItem] = []
    @State private var loading = false
    @State private var message: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let profile {
                    Text(profile.displayName).font(.title.bold())
                    Text("@" + profile.handle).foregroundStyle(.secondary)
                    Text("Named publications from this public cookbook. Saving a recipe makes a private copy.")
                    ForEach(items) { item in
                        NavigationLink(value: item.id) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(item.title).font(.headline)
                                Text("\(item.likes) likes · \(item.forks) forks").font(.caption)
                            }.frame(maxWidth: .infinity, minHeight: 60, alignment: .leading).padding(16).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                        }.buttonStyle(.plain)
                    }
                    if items.isEmpty && !loading { Text(EmptyStateCopy.profilePublicationsTitle); Text(EmptyStateCopy.profilePublicationsBody).font(.footnote) }
                    if let cursor = profile.nextCursor { Button("More recipes") { Task { await load(cursor) } }.disabled(loading) }
                }
                if loading { ProgressView("Loading cookbook…") }
                if let message { Text(message) }
                Button("Refresh cookbook") { Task { await load() } }.disabled(loading)
            }.padding(20)
        }.navigationTitle("Public cookbook").navigationBarTitleDisplayMode(.inline).background(PitTechStyle.canvas.ignoresSafeArea()).task(id: handle) { await load() }
    }
    private func load(_ cursor: String? = nil) async {
        guard !loading else { return }; loading = true; defer { loading = false }
        do {
            let (value, page) = try await community.profile(handle, cursor: cursor)
            if cursor == nil { items = page } else { let known = Set(items.map(\.id)); items += page.filter { !known.contains($0.id) } }
            profile = value; message = nil
        } catch { profile = nil; items = []; message = error.localizedDescription }
    }
}
