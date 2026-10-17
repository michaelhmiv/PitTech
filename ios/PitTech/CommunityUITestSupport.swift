#if DEBUG
import Foundation

/// Local automation fixtures only: no server calls, real identities or email.
@MainActor enum NativeCommunityUITestSupport {
    static var active: Bool { CommandLine.arguments.contains("--community-controls-ui-test") }
    static let alias = "34567890-1234-4234-8234-123456789abc"
    static let receipt = "45678901-1234-4234-8234-123456789abc"
    private final class Storage: NativeCommunityStorage {
        var value: NativeCommunityControlsState?
        func load() throws -> NativeCommunityControlsState? { value }
        func save(_ value: NativeCommunityControlsState) throws { self.value = value }
    }
    private static let storage = Storage()
    private final class Reports: NativeCommunityAPI {
        var lost = true
        func call(_ path: String, body: Data?, access: String?, session: String) async throws -> Data {
            guard access == nil else { throw NativePublicError.invalid }
            if path == "/v1/reports" {
                if lost { lost = false; throw NativePublicError.network }
                return try JSONSerialization.data(withJSONObject: ["reportId": NativeCommunityUITestSupport.receipt, "status": "received"])
            }
            guard path == "/v1/reports/" + NativeCommunityUITestSupport.receipt else { throw NativePublicError.invalid }
            return try JSONSerialization.data(withJSONObject: ["reportId": NativeCommunityUITestSupport.receipt, "status": "reviewing"])
        }
    }
    private final class PublicAPI: NativePublicAPI {
        let base = NativePublicFixtureAPI(data: NativePublicUITestFixture.data)
        var blocked: Bool { NativeCommunityUITestSupport.storage.value?.blocks.contains(where: { $0.alias == NativeCommunityUITestSupport.alias }) == true }
        func json(_ path: String) async throws -> Data {
            if blocked {
                if path.hasPrefix("/v1/discover?") { return try JSONSerialization.data(withJSONObject: ["items": [], "nextCursor": NSNull()]) }
                throw NativePublicError.status(404)
            }
            let bytes = try await base.json(path)
            if path.hasPrefix("/v1/discover?") { return bytes }
            var detail = try JSONSerialization.jsonObject(with: bytes) as! [String: Any]; detail["publisherAlias"] = NativeCommunityUITestSupport.alias
            return try JSONSerialization.data(withJSONObject: detail)
        }
        func pack(_ id: String, revision: String, to: URL) async throws -> URL { guard !blocked else { throw NativePublicError.status(404) }; return try await base.pack(id, revision: revision, to: to) }
        func image(_ id: String, asset: NativePublicDetail.Media) async throws -> Data { guard !blocked else { throw NativePublicError.status(404) }; return try await base.image(id, asset: asset) }
    }
    static func controls() -> NativeCommunityControls { NativeCommunityControls(storage: storage, api: Reports()) }
    static func community() -> NativePublicCoordinator { NativePublicCoordinator(enabled: true, api: PublicAPI(), cache: NativePublicCache(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)), audience: { storage.value?.generation ?? "empty" }) }
}
#endif
@MainActor extension NativeCommunityControls {
    static func forApp() -> NativeCommunityControls {
        #if DEBUG
        if NativeCommunityUITestSupport.active { return NativeCommunityUITestSupport.controls() }
        #endif
        return NativeCommunityControls()
    }
}
