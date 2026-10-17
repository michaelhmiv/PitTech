#if DEBUG
import Foundation

/// UI automation only: a memory vault and deterministic local API; never contacts a server or sends mail.
@MainActor enum NativeAccountUITestSupport {
    private final class Memory: NativeAccountStorage {
        var value = NativeAccountState()
        func load() throws -> NativeAccountState { value }
        func save(_ value: NativeAccountState) throws { self.value = value }
    }
    private struct API: NativeAccountAPI {
        func call(_ path: String, body: [String: String]?, access: String?) async throws -> Data {
            if path.hasSuffix("challenges") { return try JSONSerialization.data(withJSONObject: ["challengeId": body!["idempotencyId"]!, "expiresInSeconds": 900]) }
            if path.hasSuffix("verify") {
                guard body?["code"] == "123456" else { throw NativeAccountError.status(401) }
                let session = NativeAccountSession(accessToken: String(repeating: "A", count: 43), refreshToken: String(repeating: "A", count: 43), accessExpiresAt: "2100-01-01T00:00:00.000Z", sessionExpiresAt: "2100-02-01T00:00:00.000Z", userId: "12345678-1234-4234-8234-123456789abc")
                return try JSONEncoder().encode(session)
            }
            return Data("{}".utf8)
        }
    }
    static func make() -> NativeAccountCoordinator {
        if NativePublicationUITestSupport.active {
            let vault = Memory(); vault.value.session = NativeAccountSession(accessToken: String(repeating: "A", count: 43), refreshToken: String(repeating: "A", count: 43), accessExpiresAt: "2100-01-01T00:00:00.000Z", sessionExpiresAt: "2100-02-01T00:00:00.000Z", userId: "12345678-1234-4234-8234-123456789abc")
            return NativeAccountCoordinator(enabled: true, vault: vault, api: API())
        }
        if CommandLine.arguments.contains("--account-ui-test") { return NativeAccountCoordinator(enabled: true, vault: Memory(), api: API()) }
        return NativeAccountCoordinator()
    }
}
#endif
