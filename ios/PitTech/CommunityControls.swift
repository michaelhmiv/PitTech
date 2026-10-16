import Foundation
import Observation

struct NativeCommunityBlock: Codable { let alias: String; let title: String }
struct NativeCommunityReportReceipt: Codable, Identifiable { let id: String; let status: String; var userId: String? = nil }
struct NativeCommunityPending: Codable {
    let userId: String?
    let operationId: String
    let kind: String
    let body: Data
    let sha256: String
    var rejected = false
    func validate() throws {
        guard UUID(uuidString: operationId) != nil, userId.map({ UUID(uuidString: $0) != nil }) ?? true,
              ["report", "block"].contains(kind), body.count <= 8192, NativeRelayProtocol.hash(body) == sha256,
              let value = try JSONSerialization.jsonObject(with: body) as? [String: Any], value["operationId"] as? String == operationId else { throw NativePublicError.invalid }
        if kind == "block" {
            guard userId != nil, Set(value.keys) == Set(["operationId", "alias", "blocked"]), UUID(uuidString: value["alias"] as? String ?? "") != nil, value["blocked"] is Bool else { throw NativePublicError.invalid }
        } else {
            guard Set(value.keys) == Set(["operationId", "itemId", "assetId", "kind", "reason", "details"]),
                  UUID(uuidString: value["itemId"] as? String ?? "") != nil,
                  value["assetId"] is NSNull || UUID(uuidString: value["assetId"] as? String ?? "") != nil,
                  ["content", "publisher"].contains(value["kind"] as? String ?? ""),
                  NativeCommunityControls.reasons.contains(value["reason"] as? String ?? ""),
                  let details = value["details"] as? String, details.utf16.count <= 2000,
                  value["kind"] as? String != "publisher" || value["assetId"] is NSNull else { throw NativePublicError.invalid }
        }
    }
}
struct NativeCommunityControlsState: Codable {
    var session: String
    var generation = UUID().uuidString.lowercased()
    var blocks: [NativeCommunityBlock] = []
    var pending: NativeCommunityPending?
    var reports: [NativeCommunityReportReceipt] = []
    func validate() throws {
        guard UUID(uuidString: generation) != nil, NativeAccountProtocol.credential(session), blocks.count <= 100, reports.count <= 20,
              Set(blocks.map { $0.alias.lowercased() }).count == blocks.count,
              blocks.allSatisfy({ UUID(uuidString: $0.alias) != nil && $0.title.utf16.count <= 160 }),
              reports.allSatisfy({ UUID(uuidString: $0.id) != nil && ["received", "open", "reviewing", "resolved", "dismissed"].contains($0.status) }) else { throw NativePublicError.invalid }
        try pending?.validate()
    }
}
protocol NativeCommunityStorage {
    func load() throws -> NativeCommunityControlsState?
    func save(_ value: NativeCommunityControlsState) throws
}
struct NativeCommunityVault: NativeCommunityStorage {
    private let vault = NativeAccountVault(service: "com.pittech.community-controls.production.v1")
    func load() throws -> NativeCommunityControlsState? {
        guard let bytes = try vault.loadBytes() else { return nil }
        let state = try JSONDecoder().decode(NativeCommunityControlsState.self, from: bytes); try state.validate(); return state
    }
    func save(_ value: NativeCommunityControlsState) throws { try value.validate(); try vault.saveBytes(JSONEncoder().encode(value)) }
    static func aliases() throws -> [String] { try NativeCommunityVault().load()?.blocks.map(\.alias).sorted() ?? [] }
    static func publicAccount() throws -> NativeAccountSession? {
        let state = try NativeAccountVault().load()
        guard state.deletion == nil, let session = state.session else { return nil }; try session.validate()
        guard try NativeAccountProtocol.date(session.sessionExpiresAt) > Date() else { throw NativePublicError.status(401) }; return session
    }
    static func cacheAudience() throws -> String { (try NativeCommunityVault().load()?.generation ?? "empty") + "|" + (try publicAccount()?.userId ?? "anonymous") }
}
protocol NativeCommunityAPI {
    func call(_ path: String, body: Data?, access: String?, session: String) async throws -> Data
}
struct NativeCommunityClient: NativeCommunityAPI {
    func call(_ path: String, body: Data?, access: String?, session: String) async throws -> Data {
        guard NativeAccountProtocol.credential(session), body.map({ $0.count <= 8192 }) ?? true,
              ["/v1/reports", "/v1/me/blocks"].contains(path) || path.range(of: "^/v1/reports/[a-fA-F0-9-]{36}$", options: .regularExpression) != nil,
              path != "/v1/reports" || body != nil else { throw NativePublicError.invalid }
        var request = URLRequest(url: URL(string: "https://api.pittech.app" + path)!)
        request.httpMethod = body == nil ? "GET" : "POST"; request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Accept"); request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue(session, forHTTPHeaderField: "X-PitTech-Report-Session")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let access { guard NativeAccountProtocol.credential(access) else { throw NativePublicError.invalid }; request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization") }
        return try await NativeRelayTransport(responseError: { NativePublicError.status($0) }, maximumResponseBytes: 8192).run(request).data
    }
}
@MainActor @Observable final class NativeCommunityControls {
    nonisolated static let reasons = ["spam", "harassment", "unsafe", "privacy", "copyright", "other"]
    private let storage: any NativeCommunityStorage
    private let api: any NativeCommunityAPI
    private var state: NativeCommunityControlsState?
    private var ready = true
    private(set) var busy = false
    private(set) var message: String?
    var blocks: [NativeCommunityBlock] { state?.blocks ?? [] }
    var pending: NativeCommunityPending? { state?.pending }
    var reports: [NativeCommunityReportReceipt] { state?.reports ?? [] }
    init(storage: any NativeCommunityStorage = NativeCommunityVault(), api: any NativeCommunityAPI = NativeCommunityClient()) {
        self.storage = storage; self.api = api
        do { state = try storage.load(); try state?.validate() } catch { ready = false; message = "This device's community controls are unavailable. Try again before browsing." }
    }
    private func current() throws -> NativeCommunityControlsState {
        guard ready else { throw NativePublicError.invalid }
        if let state { return state }
        let value = NativeCommunityControlsState(session: try NativeAccountProtocol.verifier()); try save(value); return value
    }
    private func save(_ value: NativeCommunityControlsState) throws { try value.validate(); guard try JSONEncoder().encode(value).count <= 16384 else { throw NativePublicError.invalid }; try storage.save(value); state = value }
    private func perform(_ work: () async throws -> Void) async {
        guard !busy else { return }; busy = true; message = nil; defer { busy = false }
        do { try await work() }
        catch is CancellationError { message = "Request interrupted. Recover the saved request on this device." }
        catch { message = "Community change was not confirmed. Your saved request and device blocks are kept. Retry or review it here." }
    }
    func report(item: String, asset: String?, publisher: Bool, reason: String, details: String, account: NativeAccountCoordinator) async {
        await perform {
            var next = try self.current(); guard next.pending == nil else { throw NativePublicError.invalid }
            let operation = UUID().uuidString.lowercased()
            let bytes = try JSONSerialization.data(withJSONObject: ["operationId": operation, "itemId": item, "assetId": asset as Any? ?? NSNull(), "kind": publisher ? "publisher" : "content", "reason": reason, "details": details], options: [.sortedKeys])
            next.pending = NativeCommunityPending(userId: account.stateUserId, operationId: operation, kind: "report", body: bytes, sha256: NativeRelayProtocol.hash(bytes)); try self.save(next)
            try await self.send(account)
        }
    }
    func block(alias: String, title: String, blocked: Bool, account: NativeAccountCoordinator, changed: () -> Void = {}) async {
        await perform {
            var next = try self.current(); guard next.pending == nil, UUID(uuidString: alias) != nil, title.utf16.count <= 160 else { throw NativePublicError.invalid }
            next.blocks.removeAll { $0.alias.lowercased() == alias.lowercased() }
            if blocked { next.blocks.append(NativeCommunityBlock(alias: alias.lowercased(), title: title)) }
            if let user = account.stateUserId {
                let operation = UUID().uuidString.lowercased(), bytes = try JSONSerialization.data(withJSONObject: ["operationId": operation, "alias": alias.lowercased(), "blocked": blocked], options: [.sortedKeys])
                next.pending = NativeCommunityPending(userId: user, operationId: operation, kind: "block", body: bytes, sha256: NativeRelayProtocol.hash(bytes))
            }
            next.generation = UUID().uuidString.lowercased()
            try self.save(next); changed() // Device filtering is durable even if the server response is lost.
            if next.pending != nil { try await self.send(account) } else { self.message = blocked ? "Publisher blocked on this device." : "Publisher unblocked on this device." }
        }
    }
    private func send(_ account: NativeAccountCoordinator) async throws {
        var next = try current(); guard let pending = next.pending, !pending.rejected else { throw NativePublicError.invalid }; try pending.validate()
        let path = pending.kind == "report" ? "/v1/reports" : "/v1/me/blocks"
        var access: String?
        if let user = pending.userId { guard account.stateUserId == user else { throw NativePublicError.invalid }; access = try await account.access(); guard account.stateUserId == user else { throw NativePublicError.invalid } }
        let bytes: Data
        do {
            do { bytes = try await api.call(path, body: pending.body, access: access, session: next.session) }
            catch NativePublicError.status(401) {
                guard let user = pending.userId, account.stateUserId == user else { throw NativePublicError.invalid }
                let token = try await account.access(forceRefresh: true); guard account.stateUserId == user else { throw NativePublicError.invalid }
                bytes = try await api.call(path, body: pending.body, access: token, session: next.session)
            }
        } catch let error as NativePublicError {
            if case .status(let status) = error, [400,403,404,409,413,415,422].contains(status) { next.pending?.rejected = true; try save(next) }
            throw error
        }
        guard pending.userId == nil || pending.userId == account.stateUserId,
              let value = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw NativePublicError.invalid }
        if pending.kind == "report" {
            guard let id = value["reportId"] as? String, UUID(uuidString: id) != nil, value["status"] as? String == "received" else { throw NativePublicError.invalid }
            next.reports.removeAll { $0.id == id }; next.reports.insert(NativeCommunityReportReceipt(id: id, status: "received", userId: pending.userId), at: 0); next.reports = Array(next.reports.prefix(20))
            message = "Report received. Moderator notes and account identities stay private."
        } else {
            guard let original = try JSONSerialization.jsonObject(with: pending.body) as? [String: Any] else { throw NativePublicError.invalid }
            guard value["alias"] as? String == original["alias"] as? String, value["blocked"] as? Bool == original["blocked"] as? Bool else { throw NativePublicError.invalid }
            message = "Account block change confirmed."
        }
        next.pending = nil; try save(next)
    }
    func checkReport(_ receipt: NativeCommunityReportReceipt, account: NativeAccountCoordinator) async {
        await perform {
            var next = try self.current(); var access: String?
            if let user = receipt.userId { guard user == account.stateUserId else { throw NativePublicError.invalid }; access = try await account.access() }
            let bytes = try await self.api.call("/v1/reports/" + receipt.id, body: nil, access: access, session: next.session)
            guard receipt.userId == nil || receipt.userId == account.stateUserId,
                  let value = try JSONSerialization.jsonObject(with: bytes) as? [String: String], value["reportId"] == receipt.id,
                  let status = value["status"], ["open", "reviewing", "resolved", "dismissed"].contains(status),
                  let index = next.reports.firstIndex(where: { $0.id == receipt.id }) else { throw NativePublicError.invalid }
            next.reports[index] = NativeCommunityReportReceipt(id: receipt.id, status: status, userId: receipt.userId); try self.save(next); self.message = "Report status updated."
        }
    }
    func retry(account: NativeAccountCoordinator) async { await perform { try await self.send(account) } }
    func discardRejected() {
        guard !busy, var next = state, next.pending?.rejected == true else { return }
        do { next.pending = nil; try save(next); message = "Rejected request discarded. Device blocks are unchanged." } catch { message = "The saved request could not be cleared." }
    }
}
