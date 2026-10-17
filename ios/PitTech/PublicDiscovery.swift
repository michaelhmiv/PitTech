import Foundation
import Observation
import ImageIO

enum NativePublicError: LocalizedError {
    case invalid, network, status(Int)
    var errorDescription: String? {
        switch self {
        case .status(404): return "This public recipe is unavailable. Any copy you already saved stays in your cookbook."
        case .status(409): return "This recipe changed. Refresh its preview before saving."
        case .status(428): return "Review the current public-copy terms before publishing a new request."
        case .status(429): return "Too many requests. Wait a minute, then refresh."
        case .invalid: return "This public recipe couldn't be read safely. Try again later."
        default: return "Couldn't reach community recipes. Your local cookbook is available offline."
        }
    }
}
struct NativePublicItem: Decodable, Identifiable {
    struct Credit: Decodable {
        let displayName: String
        let profileUrl: String?
        func validate() throws {
            guard displayName.utf16.count <= 160 else { throw NativePublicError.invalid }
            if let profileUrl { guard try NativePublicLink.destination(profileUrl).hasPrefix("profile:"), profileUrl.hasPrefix("https://pittech.app/p/") else { throw NativePublicError.invalid } }
        }
    }
    let id: String
    let revisionId: String
    let title: String
    let kind: String
    let credit: Credit
    let likes: Int
    let forks: Int
    let heroAssetId: String?
    func validate() throws {
        try credit.validate()
        guard UUID(uuidString: id) != nil, UUID(uuidString: revisionId) != nil, (1...160).contains(title.utf16.count), credit.displayName.utf16.count <= 160,
              ["recipe", "collection", "attempt"].contains(kind), (0...1_000_000_000).contains(likes), (0...1_000_000_000).contains(forks), heroAssetId.map({ UUID(uuidString: $0) != nil }) ?? true else { throw NativePublicError.invalid }
    }
}
struct NativePublicPage: Decodable {
    let items: [NativePublicItem]
    let nextCursor: String?
    func validate() throws { guard items.count <= 20, (nextCursor?.count ?? 0) <= 256, Set(items.map(\.id)).count == items.count else { throw NativePublicError.invalid }; for item in items { try item.validate() } }
}
struct NativePublicDetail: Decodable {
    struct Media: Decodable { let id: String; let url: String; let sha256: String; let mimeType: String }
    let id: String
    let revisionId: String
    let payload: JSONValue
    let credit: NativePublicItem.Credit
    let likes: Int
    let forks: Int
    let media: [Media]
    var title: String { payload.objectValue?["title"]?.stringValue ?? "Recipe" }
    var recipes: [JSONValue] { payload.objectValue?["recipes"]?.arrayValue ?? [] }
    var heroCaption: String { recipes.flatMap { $0.objectValue?["assets"]?.arrayValue ?? [] }.first { $0.objectValue?["id"]?.stringValue == hero?.id }?.objectValue?["caption"]?.stringValue ?? "" }
    var hero: Media? { media.first { $0.id == payload.objectValue?["heroAssetId"]?.stringValue } }
    func validate() throws {
        try credit.validate()
        guard UUID(uuidString: id) != nil, UUID(uuidString: revisionId) != nil, (1...160).contains(title.utf16.count), credit.displayName.utf16.count <= 160,
              (1...20).contains(recipes.count), media.count <= 600, Set(media.map(\.id)).count == media.count, (0...1_000_000_000).contains(likes), (0...1_000_000_000).contains(forks) else { throw NativePublicError.invalid }
        for medium in media {
            guard UUID(uuidString: medium.id) != nil, medium.url == "https://api.pittech.app/v1/recipes/\(id)/assets/\(medium.id)",
                  medium.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil, ["image/jpeg", "image/png", "image/webp"].contains(medium.mimeType) else { throw NativePublicError.invalid }
        }
        for recipe in recipes {
            guard let r = recipe.objectValue, UUID(uuidString: r["id"]?.stringValue ?? "") != nil, let title = r["title"]?.stringValue, (1...160).contains(title.utf16.count),
                  (r["description"]?.stringValue?.utf16.count ?? 0) <= 2000, (r["ingredients"]?.arrayValue?.count ?? 0) <= 100, (r["instructions"]?.arrayValue?.count ?? 0) <= 100 else { throw NativePublicError.invalid }
        }
    }
}
protocol NativePublicAPI {
    func json(_ path: String) async throws -> Data
    func pack(_ id: String, revision: String, to: URL) async throws -> URL
    func image(_ id: String, asset: NativePublicDetail.Media) async throws -> Data
}
struct NativePublicClient: NativePublicAPI {
    private func request(_ path: String) throws -> URLRequest {
        guard path.range(of: "^/v1/profiles/[a-z0-9_]{3,24}(\\?cursor=[a-fA-F0-9-]{36})?$", options: .regularExpression) != nil || path.hasPrefix("/v1/discover?") || path.range(of: "^/v1/recipes/[a-fA-F0-9-]{36}(/pack|/assets/[a-fA-F0-9-]{36})?$", options: .regularExpression) != nil,
              let url = URL(string: "https://api.pittech.app" + path), url.host == "api.pittech.app", url.fragment == nil else { throw NativePublicError.invalid }
        var request = URLRequest(url: url); request.setValue("no-store", forHTTPHeaderField: "Cache-Control"); request.setValue("application/json", forHTTPHeaderField: "Accept"); return request
    }
    func json(_ path: String) async throws -> Data {
        do { return try await NativeRelayTransport(responseError: { NativePublicError.status($0) }, maximumResponseBytes: 8 * 1024 * 1024).run(request(path)).data }
        catch let error as NativePublicError { throw error } catch is CancellationError { throw CancellationError() } catch { throw NativePublicError.network }
    }
    func pack(_ id: String, revision: String, to file: URL) async throws -> URL {
        guard UUID(uuidString: id) != nil, UUID(uuidString: revision) != nil else { throw NativePublicError.invalid }
        var request = try request("/v1/recipes/" + id + "/pack"); request.setValue(revision, forHTTPHeaderField: "If-Match"); request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        do { let value = try await NativeRelayTransport(responseError: { NativePublicError.status($0) }).run(request, download: file); guard let file = value.file else { throw NativePublicError.invalid }; return file }
        catch let error as NativePublicError { throw error } catch is CancellationError { throw CancellationError() } catch { throw NativePublicError.network }
    }
    func image(_ id: String, asset: NativePublicDetail.Media) async throws -> Data {
        var request = try request("/v1/recipes/\(id)/assets/\(asset.id)"); request.setValue(asset.mimeType, forHTTPHeaderField: "Accept")
        let bytes = try await NativeRelayTransport(responseError: { NativePublicError.status($0) }, maximumResponseBytes: 16 * 1024 * 1024).run(request).data
        guard NativeRelayProtocol.hash(bytes) == asset.sha256, let source = CGImageSourceCreateWithData(bytes as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any], let width = properties[kCGImagePropertyPixelWidth] as? Int, let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, Int64(width) * Int64(height) <= 20_000_000 else { throw NativePublicError.invalid }; return bytes
    }
}
actor NativePublicCache {
    private let directory: URL
    init(directory: URL? = nil) { self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("PublicBrowsing-v1", isDirectory: true) }
    private func file(_ key: String) -> URL { directory.appendingPathComponent(NativeRelayProtocol.hash(Data(key.utf8)) + ".data") }
    func read(_ key: String, limit: Int = 8 * 1024 * 1024) throws -> Data? {
        let url = file(key); guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= limit else { try? FileManager.default.removeItem(at: url); return nil }; return try Data(contentsOf: url)
    }
    func remove(_ key: String) { try? FileManager.default.removeItem(at: file(key)) }
    func save(_ key: String, bytes: Data) throws {
        guard bytes.count <= 16 * 1024 * 1024 else { throw NativePublicError.invalid }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var url = directory, values = URLResourceValues(); values.isExcludedFromBackup = true; try url.setResourceValues(values)
        try bytes.write(to: file(key), options: .atomic)
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]).filter { $0.pathExtension == "data" }
        var size = try files.reduce(0) { $0 + (try $1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        for old in files.sorted(by: { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }) where size > 32 * 1024 * 1024 {
            let count = (try? old.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0; try FileManager.default.removeItem(at: old); size -= count
        }
    }
}
@MainActor @Observable final class NativePublicCoordinator {
    let enabled: Bool
    var path: [String] = []
    var linkSequence = 0
    private let api: any NativePublicAPI
    private let cache: NativePublicCache
    private(set) var items: [NativePublicItem] = []
    private(set) var nextCursor: String?
    private(set) var loading = false
    private(set) var preparing = false
    private(set) var message: String?
    private var currentKey = ""
    private(set) var appliedSort = "liked"
    private(set) var appliedFood = ""
    private(set) var appliedQuery = ""
    init(enabled: Bool = false, api: any NativePublicAPI = NativePublicClient(), cache: NativePublicCache = NativePublicCache()) { self.enabled = enabled; self.api = api; self.cache = cache }
    private func pagePath(sort: String, food: String, query: String, cursor: String? = nil) -> String {
        var parts = URLComponents(); parts.path = "/v1/discover"; parts.queryItems = [URLQueryItem(name: "sort", value: sort), URLQueryItem(name: "food", value: food), URLQueryItem(name: "q", value: query)] + (cursor.map { [URLQueryItem(name: "cursor", value: $0)] } ?? [])
        return parts.string!
    }
    private func cached(_ path: String) async throws -> (Data, Bool) {
        do {
            let data = try await api.json(path)
            if path.hasPrefix("/v1/discover?") { try JSONDecoder().decode(NativePublicPage.self, from: data).validate() }
            else { let detail = try JSONDecoder().decode(NativePublicDetail.self, from: data); try detail.validate(); guard path == "/v1/recipes/" + detail.id else { throw NativePublicError.invalid } }
            try? await cache.save(path, bytes: data); return (data, false)
        }
        catch NativePublicError.status(404) { await cache.remove(path); throw NativePublicError.status(404) }
        catch is CancellationError { throw CancellationError() }
        catch { if let data = try? await cache.read(path) { return (data, true) }; throw error }
    }
    func refresh(sort: String, food: String, query: String, more: Bool = false) async {
        guard enabled, !loading, ["liked", "forked"].contains(sort), food.count <= 80, query.count <= 80 else { return }
        let key = pagePath(sort: sort, food: food, query: query); guard !more || currentKey == key && nextCursor != nil else { return }
        if !more && currentKey != key { items = []; nextCursor = nil }
        appliedSort = sort; appliedFood = food; appliedQuery = query
        loading = true; defer { loading = false }
        do {
            let (bytes, saved) = try await cached(pagePath(sort: sort, food: food, query: query, cursor: more ? nextCursor : nil))
            let page = try JSONDecoder().decode(NativePublicPage.self, from: bytes); try page.validate()
            if more { let known = Set(items.map(\.id)); items += page.items.filter { !known.contains($0.id) } } else { items = page.items }
            nextCursor = page.nextCursor; currentKey = key; message = saved ? "Showing saved browsing results. Refresh when you can connect." : nil
        } catch { message = error.localizedDescription }
    }
    func detail(_ id: String) async throws -> (NativePublicDetail, Bool) {
        guard enabled, UUID(uuidString: id) != nil else { throw NativePublicError.invalid }
        let (bytes, saved) = try await cached("/v1/recipes/" + id); let detail = try JSONDecoder().decode(NativePublicDetail.self, from: bytes); try detail.validate(); guard detail.id == id else { throw NativePublicError.invalid }; return (detail, saved)
    }
    func profile(_ handle: String, cursor: String? = nil) async throws -> (NativePublicProfile, [NativePublicItem]) {
        guard enabled, handle.range(of: "^[a-z0-9_]{3,24}$", options: .regularExpression) != nil, cursor.map({ UUID(uuidString: $0) != nil }) ?? true else { throw NativePublicError.invalid }
        // Profile visibility is checked live; private/removed identity is never revived from a stale profile cache.
        let bytes = try await api.json("/v1/profiles/" + handle + (cursor.map { "?cursor=" + $0 } ?? ""))
        let profile = try JSONDecoder().decode(NativePublicProfile.self, from: bytes); try profile.validate(handle)
        var items: [NativePublicItem] = []
        for entry in profile.items {
            do {
                let data = try await api.json("/v1/recipes/" + entry.id)
                let detail = try JSONDecoder().decode(NativePublicDetail.self, from: data); try detail.validate()
                guard detail.id == entry.id, detail.payload.objectValue?["identityMode"]?.stringValue == "named" else { throw NativePublicError.invalid }
                items.append(NativePublicItem(id: detail.id, revisionId: detail.revisionId, title: detail.title, kind: detail.payload.objectValue?["kind"]?.stringValue ?? "recipe", credit: detail.credit, likes: detail.likes, forks: detail.forks, heroAssetId: detail.payload.objectValue?["heroAssetId"]?.stringValue))
            } catch NativePublicError.status(404) { continue }
        }
        return (profile, items)
    }
    func image(_ detail: NativePublicDetail) async throws -> Data? {
        guard let hero = detail.hero else { return nil }; return try await image(detail, asset: hero)
    }
    func image(_ detail: NativePublicDetail, asset hero: NativePublicDetail.Media) async throws -> Data {
        let key = "image:" + hero.sha256
        if let bytes = try? await cache.read(key, limit: 16 * 1024 * 1024), NativeRelayProtocol.hash(bytes) == hero.sha256 { return bytes }
        let bytes = try await api.image(detail.id, asset: hero); try? await cache.save(key, bytes: bytes); return bytes
    }
    func preview(_ detail: NativePublicDetail, cook: Bool, store: CookStore) async {
        guard enabled, !preparing else { return }; preparing = true; defer { preparing = false }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pittech")
        defer { try? FileManager.default.removeItem(at: file) }
        do {
            let downloaded = try await api.pack(detail.id, revision: detail.revisionId, to: file); try Task.checkCancellation()
            await store.receiveArchive(at: downloaded, cookPackOnly: true, expectedPublicSource: (detail.id, detail.revisionId), cookOnSave: cook)
        } catch { message = error.localizedDescription }
    }
}
