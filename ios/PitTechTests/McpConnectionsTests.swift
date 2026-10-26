import XCTest
@testable import PitTech

/// MCP connection management on iOS (issue #114, M1b-3), against a fake
/// transport: parsing, one-time token capture, revoke paths, the 404
/// availability state, and approve-code normalization.
final class McpConnectionsTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private let grantId = "123e4567-e89b-42d3-a456-426614174000"

    private final class API: NativeMcpAPI {
        var calls: [(String, String, [String: Any]?)] = []
        var handler: (String, String) throws -> [String: Any] = { _, _ in [:] }
        func call(_ method: String, _ path: String, body: [String: Any]?, access: String?) async throws -> Data {
            XCTAssertEqual(access, String(repeating: "A", count: 43), "management always carries the native session")
            calls.append((method, path, body))
            return try JSONSerialization.data(withJSONObject: handler(method, path))
        }
    }
    private struct AccountAPI: NativeAccountAPI {
        func call(_ path: String, body: [String: String]?, access: String?) async throws -> Data { Data() }
    }
    private final class Memory: NativeAccountStorage {
        var value: NativeAccountState
        init(_ value: NativeAccountState) { self.value = value }
        func load() throws -> NativeAccountState { value }
        func save(_ value: NativeAccountState) throws { self.value = value }
    }
    private func grant(_ nickname: String = "ChatGPT") -> [String: Any] {
        ["id": grantId, "nickname": nickname, "scopes": ["library:read", "library:write"],
         "createdAt": now - 1000, "expiresAt": now + 90 * 86_400_000,
         "revokedAt": NSNull(), "lastUsedAt": NSNull(), "clientId": NSNull()]
    }
    @MainActor private func fixture(_ api: API) -> NativeMcpConnections {
        let session = NativeAccountSession(accessToken: String(repeating: "A", count: 43), refreshToken: String(repeating: "A", count: 43),
            accessExpiresAt: "2100-01-01T00:00:00.000Z", sessionExpiresAt: "2100-02-01T00:00:00.000Z",
            userId: "12345678-1234-4234-8234-123456789abc")
        let account = NativeAccountCoordinator(enabled: true, vault: Memory(NativeAccountState(session: session)), api: AccountAPI())
        return NativeMcpConnections(account: account, api: api, now: { self.now })
    }

    @MainActor func testRefreshCreateRevokeFlow() async {
        let api = API()
        api.handler = { method, _ in
            switch method {
            case "GET": return ["grants": [self.grant()]]
            case "POST": return ["grant": self.grant("Muse"), "token": "ptm_" + String(repeating: "x", count: 43)]
            default: return ["revoked": true]
            }
        }
        let mcp = fixture(api)
        await mcp.refresh()
        XCTAssertEqual(mcp.grants.count, 1)
        XCTAssertEqual(mcp.grants[0].status(now: now), "Connected")
        await mcp.create(nickname: "Muse", scopes: ["library:read"], expiresInDays: 90)
        XCTAssertEqual(mcp.createdToken, "ptm_" + String(repeating: "x", count: 43))
        XCTAssertEqual(mcp.createdNickname, "Muse")
        mcp.dismissCreatedToken()
        XCTAssertNil(mcp.createdToken)
        await mcp.revoke(grantId)
        XCTAssertEqual(mcp.grants.first?.status(now: now), "Disconnected")
        XCTAssertTrue(api.calls.contains { $0.0 == "DELETE" && $0.1 == "/v1/me/mcp/grants/\(grantId)" })
    }

    @MainActor func testServer404MeansNotAvailableYet() async {
        let api = API()
        api.handler = { _, _ in throw NativeAccountError.status(404) }
        let mcp = fixture(api)
        await mcp.refresh()
        XCTAssertTrue(mcp.loaded)
        XCTAssertFalse(mcp.available)
        XCTAssertNil(mcp.message)
    }

    @MainActor func testApproveNormalizesCodeAndExplainsUnknownCodes() async {
        let api = API()
        var approved: String?
        api.handler = { _, path in
            if path.hasSuffix("approve") { return ["approved": true] }
            return ["grants": []]
        }
        let mcp = fixture(api)
        // capture the posted body through the fake's call log
        await mcp.approve(userCode: " abcd-2345 ")
        XCTAssertTrue(api.calls.contains { $0.1.hasSuffix("approve") && ($0.2?["userCode"] as? String) == "ABCD-2345" })
        _ = approved
        XCTAssertTrue((mcp.message ?? "").contains("Approved"))
        let bad = API()
        bad.handler = { _, path in if path.hasSuffix("approve") { throw NativeAccountError.status(404) }; return ["grants": []] }
        let badMcp = fixture(bad)
        await badMcp.approve(userCode: "XXXX-XXXX")
        XCTAssertTrue((badMcp.message ?? "").contains("isn't valid"))
    }

    /// Slice 8 (PL-16 / DD-9): reviewing a code fetches the client name
    /// and full scope list for the confirmation step; describing approves
    /// nothing, and approving afterwards clears the preview.
    @MainActor func testReviewDescribesTheCodeBeforeApprovalArms() async {
        let api = API()
        api.handler = { _, path in
            if path.hasSuffix("describe") {
                return ["clientName": "ChatGPT", "scopes": ["library:read", "library:delete"], "expiresAt": self.now + 15 * 60_000]
            }
            if path.hasSuffix("approve") { return ["approved": true] }
            return ["grants": []]
        }
        let mcp = fixture(api)
        await mcp.reviewCode(" abcd-2345 ")
        XCTAssertEqual(mcp.approvalPreview?.code, "ABCD-2345")
        XCTAssertEqual(mcp.approvalPreview?.clientName, "ChatGPT")
        XCTAssertEqual(mcp.approvalPreview?.scopes, ["library:read", "library:delete"])
        XCTAssertEqual(mcp.approvalPreview?.expiresAt, now + 15 * 60_000)
        XCTAssertFalse(api.calls.contains { $0.1.hasSuffix("approve") })
        await mcp.approve(userCode: mcp.approvalPreview!.code)
        XCTAssertNil(mcp.approvalPreview)
        XCTAssertTrue((mcp.message ?? "").contains("Approved"))
        let bad = API()
        bad.handler = { _, path in if path.hasSuffix("describe") { throw NativeAccountError.status(404) }; return ["grants": []] }
        let badMcp = fixture(bad)
        await badMcp.reviewCode("XXXX-XXXX")
        XCTAssertNil(badMcp.approvalPreview)
        XCTAssertTrue((badMcp.message ?? "").contains("isn't valid"))
    }

    /// PL-30 (#172): an expired grant renders "Expired" and is NOT
    /// active — it must not offer Disconnect or inflate Disconnect-all.
    /// Mirrors the Android McpGrantView test.
    func testExpiredGrantsAreNotActiveAndRevokedGrantsAreNotActive() {
        let now: Int64 = 1_700_000_000_000
        func grant(revoked: Int64?, expires: Int64) -> McpGrant {
            McpGrant(id: "g", nickname: "ChatGPT", scopes: [], createdAt: now - 10_000, expiresAt: expires, revokedAt: revoked, lastUsedAt: nil, clientId: nil)
        }
        let live = grant(revoked: nil, expires: now + 60_000)
        XCTAssertTrue(live.active(now: now))
        XCTAssertEqual(live.status(now: now), "Connected")
        let expired = grant(revoked: nil, expires: now - 1)
        XCTAssertFalse(expired.active(now: now))
        XCTAssertEqual(expired.status(now: now), "Expired")
        XCTAssertFalse(grant(revoked: nil, expires: now).active(now: now))
        let revoked = grant(revoked: now - 1, expires: now + 60_000)
        XCTAssertFalse(revoked.active(now: now))
        XCTAssertEqual(revoked.status(now: now), "Disconnected")
    }

    /// Slice 8 (PL-14): the connection gate maps the existing entitlement
    /// state — entitled passes, not-entitled gates, unknown checks.
    func testProGateMapsEntitlementStates() {
        XCTAssertEqual(mcpProGate(for: .entitled), .entitled)
        XCTAssertEqual(mcpProGate(for: .notEntitled), .needsPro)
        XCTAssertEqual(mcpProGate(for: .unknown), .checking)
    }
}
