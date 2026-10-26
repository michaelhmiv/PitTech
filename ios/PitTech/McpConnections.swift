import Foundation

/// MCP connection management (issue #114, M1b): the owner's controls for
/// AI-assistant connections on iOS, mirroring Android's McpConnections.
/// Management always carries the native account session — a grant token
/// is never a session. A created token lives in memory exactly once and
/// is never persisted on the device.

protocol NativeMcpAPI { func call(_ method: String, _ path: String, body: [String: Any]?, access: String?) async throws -> Data }

struct NativeMcpClient: NativeMcpAPI {
    var configuration: URLSessionConfiguration?
    func call(_ method: String, _ path: String, body: [String: Any]? = nil, access: String? = nil) async throws -> Data {
        let allowed = path == "/v1/me/mcp/grants" || path == "/v1/me/mcp/oauth/approve" || path == "/v1/me/mcp/oauth/describe" ||
            path == "/v1/me/mcp/devices" || path == "/v1/me/mcp/device-commands" ||
            path.range(of: "^/v1/me/mcp/grants/[0-9a-fA-F-]{36}$", options: .regularExpression) != nil ||
            path.range(of: "^/v1/me/mcp/devices/[0-9a-fA-F-]{36}$", options: .regularExpression) != nil ||
            path.range(of: "^/v1/me/mcp/devices/[0-9a-fA-F-]{36}/claim$", options: .regularExpression) != nil ||
            path.range(of: "^/v1/me/mcp/devices/[0-9a-fA-F-]{36}/receipt$", options: .regularExpression) != nil
        guard ["GET", "POST", "DELETE"].contains(method), allowed else { throw NativeAccountError.invalid }
        var request = URLRequest(url: URL(string: "\(NativeOnlineConfiguration.current.apiOrigin)" + path)!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        guard (request.httpBody?.count ?? 0) <= 8192 else { throw NativeAccountError.invalid }
        if let access { guard NativeAccountProtocol.credential(access) else { throw NativeAccountError.invalid }; request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization") }
        do {
            let result = try await NativeRelayTransport(configuration: configuration, responseError: { NativeAccountError.status($0) }, maximumResponseBytes: 65536).run(request)
            guard result.data.count <= 65536 else { throw NativeAccountError.invalid }; return result.data
        } catch let error as NativeAccountError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch { throw NativeAccountError.network }
    }
}

struct McpGrant: Identifiable, Equatable {
    let id: String
    let nickname: String
    let scopes: [String]
    let createdAt: Int64
    let expiresAt: Int64
    var revokedAt: Int64?
    let lastUsedAt: Int64?
    let clientId: String?
    func status(now: Int64) -> String {
        if revokedAt != nil { return "Disconnected" }
        if expiresAt <= now { return "Expired" }
        return "Connected"
    }
    /// PL-30 (#172): a grant counts as active only while it can actually
    /// be used — not revoked AND not expired. Expired grants render as
    /// "Expired" (see `status(now:)`) and must not offer Disconnect or
    /// inflate the connection counts and Disconnect-all. Mirrors Android.
    func active(now: Int64) -> Bool { revokedAt == nil && expiresAt > now }
    static func parse(_ o: [String: Any]) -> McpGrant? {
        guard let id = o["id"] as? String, let nickname = o["nickname"] as? String,
              let created = (o["createdAt"] as? NSNumber)?.int64Value, let expires = (o["expiresAt"] as? NSNumber)?.int64Value
        else { return nil }
        return McpGrant(
            id: id, nickname: nickname, scopes: (o["scopes"] as? [String]) ?? [],
            createdAt: created, expiresAt: expires,
            revokedAt: (o["revokedAt"] as? NSNumber)?.int64Value,
            lastUsedAt: (o["lastUsedAt"] as? NSNumber)?.int64Value,
            clientId: o["clientId"] as? String)
    }
}

enum McpScopeCatalog {
    struct Entry: Identifiable { let id: String; let label: String; let detail: String }
    static let all: [Entry] = [
        Entry(id: "library:read", label: "See your library", detail: "Meals, recipes, logs and plans can be read."),
        Entry(id: "library:write", label: "Add and edit", detail: "New meals, recipes and notes can be created and edited."),
        Entry(id: "library:delete", label: "Delete", detail: "Meals, recipes and notes can be deleted."),
        Entry(id: "media:read", label: "See photos", detail: "Cook photos can be viewed."),
        Entry(id: "media:write", label: "Add photos", detail: "Photos can be added to your cooks."),
        Entry(id: "planning:write", label: "Plans", detail: "Cook plans can be created and changed."),
        Entry(id: "device_actions:request", label: "Ask for device actions", detail: "The assistant can ask; you always confirm in PitTech."),
        Entry(id: "exports:read", label: "Exports", detail: "Exports of your data can be downloaded."),
        Entry(id: "imports:write", label: "Imports", detail: "Data can be imported into your library."),
        Entry(id: "community:read", label: "Community benchmarks", detail: "Anonymous community comparisons can be viewed."),
    ]
    static let defaultSelected: Set<String> = ["library:read", "library:write", "media:read", "media:write", "planning:write", "community:read"]
    static func label(_ id: String) -> String { all.first { $0.id == id }?.label ?? id }
}

/// What an entered OAuth user code would connect (Slice 8, PL-16):
/// fetched by the describe step so the confirmation can name the client
/// and list its full scopes before Approve exists. Mirrors Android.
struct McpApprovalPreview: Equatable {
    let code: String
    let clientName: String
    let scopes: [String]
    let expiresAt: Int64
}

/// The Connect-your-AI Pro gate, derived from the existing subscription
/// entitlement (Slice 8, PL-14). Creating and approving gate on it;
/// listing and disconnecting existing connections never do.
enum McpProGate: Equatable { case entitled, checking, needsPro }

func mcpProGate(for entitlement: AdEntitlementState) -> McpProGate {
    switch entitlement {
    case .entitled: return .entitled
    case .notEntitled: return .needsPro
    case .unknown: return .checking
    }
}

@MainActor @Observable final class NativeMcpConnections {
    private(set) var loaded = false
    private(set) var available = true
    private(set) var grants: [McpGrant] = []
    private(set) var busy = false
    private(set) var message: String?
    /// The one-time token from a create, in memory only until dismissed.
    private(set) var createdToken: String?
    private(set) var createdNickname: String?
    /// The reviewed code awaiting an informed approval, if any.
    private(set) var approvalPreview: McpApprovalPreview?

    private let account: NativeAccountCoordinator
    private let api: NativeMcpAPI
    private let now: () -> Int64
    init(account: NativeAccountCoordinator, api: NativeMcpAPI = NativeMcpClient(), now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.account = account
        self.api = api
        self.now = now
    }

    private func perform(_ operation: () async throws -> Void) async {
        if busy { return }
        busy = true; message = nil
        do { try await operation() }
        catch let error as NativeAccountError {
            if case .status(401) = error { message = "Sign in again to manage AI connections." }
            else if case .network = error { message = "Couldn't reach PitTech. Your connections are unchanged." }
            else { message = "Something went wrong. Your connections are unchanged." }
        } catch is CancellationError { message = "Request interrupted. Try again." }
        catch { message = "Something went wrong. Your connections are unchanged." }
        busy = false
    }

    private func loadLocked(_ token: String) async throws {
        do {
            let data = try await api.call("GET", "/v1/me/mcp/grants", body: nil, access: token)
            let reply = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            grants = ((reply?["grants"] as? [[String: Any]]) ?? []).compactMap(McpGrant.parse)
            loaded = true; available = true
        } catch let error as NativeAccountError {
            if case .status(404) = error { loaded = true; available = false; return }
            throw error
        }
    }

    func refresh() async {
        await perform {
            guard account.signedIn else { loaded = true; return }
            try await loadLocked(account.access())
        }
    }

    func create(nickname: String, scopes: Set<String>, expiresInDays: Int) async {
        await perform {
            let token = try await account.access()
            let body: [String: Any] = ["nickname": nickname.trimmingCharacters(in: .whitespaces), "scopes": Array(scopes), "expiresInDays": expiresInDays]
            let data = try await api.call("POST", "/v1/me/mcp/grants", body: body, access: token)
            guard let reply = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let created = reply["token"] as? String, let grant = (reply["grant"] as? [String: Any]).flatMap(McpGrant.parse)
            else { throw NativeAccountError.invalid }
            grants = [grant] + grants.filter { $0.id != grant.id }
            createdToken = created; createdNickname = grant.nickname
            message = "Connection created. Copy its token now — it is shown only once."
        }
    }

    func dismissCreatedToken() { createdToken = nil; createdNickname = nil; message = nil }

    func revoke(_ id: String) async {
        await perform {
            let token = try await account.access()
            _ = try await api.call("DELETE", "/v1/me/mcp/grants/\(id)", body: nil, access: token)
            grants = grants.map { $0.id == id ? McpGrant(id: $0.id, nickname: $0.nickname, scopes: $0.scopes, createdAt: $0.createdAt, expiresAt: $0.expiresAt, revokedAt: now(), lastUsedAt: $0.lastUsedAt, clientId: $0.clientId) : $0 }
            message = "Connection disconnected."
        }
    }

    func revokeAll() async {
        await perform {
            let token = try await account.access()
            _ = try await api.call("DELETE", "/v1/me/mcp/grants", body: nil, access: token)
            grants = grants.map { $0.active(now: now()) ? McpGrant(id: $0.id, nickname: $0.nickname, scopes: $0.scopes, createdAt: $0.createdAt, expiresAt: $0.expiresAt, revokedAt: now(), lastUsedAt: $0.lastUsedAt, clientId: $0.clientId) : $0 }
            message = "All connections disconnected."
        }
    }

    /// Slice 8 (PL-16 / DD-9): describe the code before approval can arm.
    /// Read-only — it approves nothing — and what it returns (client name,
    /// full scope list, expiry) is exactly what the confirmation step
    /// shows. Unknown, expired and already-approved codes are
    /// indistinguishable, matching the endpoint.
    func reviewCode(_ userCode: String) async {
        await perform {
            let code = userCode.trimmingCharacters(in: .whitespaces).uppercased()
            guard !code.isEmpty else { return }
            let token = try await account.access()
            let data: Data
            do {
                data = try await api.call("POST", "/v1/me/mcp/oauth/describe", body: ["userCode": code], access: token)
            } catch let error as NativeAccountError {
                if case .status(404) = error {
                    approvalPreview = nil
                    message = "That code isn't valid or has expired. Check the code in your browser and try again."
                    return
                }
                throw error
            }
            guard let reply = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let clientName = reply["clientName"] as? String,
                  let scopes = reply["scopes"] as? [String],
                  let expiresAt = (reply["expiresAt"] as? NSNumber)?.int64Value
            else { throw NativeAccountError.invalid }
            approvalPreview = McpApprovalPreview(code: code, clientName: clientName, scopes: scopes, expiresAt: expiresAt)
        }
    }

    func dismissApprovalPreview() { approvalPreview = nil }

    func approve(userCode: String) async {
        await perform {
            let code = userCode.trimmingCharacters(in: .whitespaces).uppercased()
            guard !code.isEmpty else { return }
            let token = try await account.access()
            do {
                _ = try await api.call("POST", "/v1/me/mcp/oauth/approve", body: ["userCode": code], access: token)
            } catch let error as NativeAccountError {
                if case .status(404) = error { approvalPreview = nil; message = "That code isn't valid or has expired. Check the code in your browser and try again."; return }
                throw error
            }
            approvalPreview = nil
            message = "Approved. The assistant finishes connecting in your browser."
            try await loadLocked(token)
        }
    }
}
