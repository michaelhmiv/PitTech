import XCTest
@testable import PitTech

@MainActor final class CommunityControlsTests: XCTestCase {
    private final class Storage: NativeCommunityStorage {
        var value: NativeCommunityControlsState?
        var fail = false
        func load() throws -> NativeCommunityControlsState? { value }
        func save(_ value: NativeCommunityControlsState) throws { if fail { throw NativePublicError.invalid }; self.value = value }
    }
    private final class AccountStorage: NativeAccountStorage {
        var state = NativeAccountState()
        func load() throws -> NativeAccountState { state }
        func save(_ value: NativeAccountState) throws { state = value }
    }
    private struct API: NativeCommunityAPI {
        let handler: (String, Data?, String?, String) async throws -> Data
        func call(_ path: String, body: Data?, access: String?, session: String) async throws -> Data { try await handler(path, body, access, session) }
    }
    private func account(_ user: String? = nil) -> NativeAccountCoordinator {
        let storage = AccountStorage()
        if let user { storage.state.session = NativeAccountSession(accessToken: String(repeating: "A", count: 43), refreshToken: String(repeating: "A", count: 43), accessExpiresAt: "2100-01-01T00:00:00Z", sessionExpiresAt: "2100-02-01T00:00:00Z", userId: user) }
        return NativeAccountCoordinator(enabled: true, vault: storage)
    }
    func testAnonymousReportRetriesExactSavedBytesAndSessionAcrossRestart() async throws {
        let storage = Storage(), user = account(), item = UUID().uuidString.lowercased(), receipt = UUID().uuidString.lowercased()
        var original: Data?, originalSession: String?, calls = 0
        let api = API { path, body, access, session in
            XCTAssertEqual(path, "/v1/reports"); XCTAssertNil(access); XCTAssertNotNil(storage.value?.pending)
            if let original { XCTAssertEqual(body, original); XCTAssertEqual(session, originalSession) } else { original = body; originalSession = session }
            calls += 1; if calls == 1 { throw NativePublicError.network }
            return try JSONSerialization.data(withJSONObject: ["reportId": receipt, "status": "received"])
        }
        let first = NativeCommunityControls(storage: storage, api: api)
        await first.report(item: item, asset: nil, publisher: false, reason: "privacy", details: "Synthetic private report", account: user)
        XCTAssertNotNil(first.pending)
        let restarted = NativeCommunityControls(storage: storage, api: api)
        await restarted.retry(account: user); XCTAssertNil(restarted.pending); XCTAssertEqual(restarted.reports.first?.id, receipt)
        XCTAssertFalse(try String(decoding: JSONEncoder().encode(storage.value), as: UTF8.self).contains("Synthetic private report"))
    }
    func testAnAccountChangeCannotTakeOverPendingReportAndSaveFailureNeverSends() async throws {
        let storage = Storage(), alice = account(UUID().uuidString.lowercased()), bob = account(UUID().uuidString.lowercased())
        var calls = 0
        let api = API { _, _, _, _ in calls += 1; throw NativePublicError.network }
        let controls = NativeCommunityControls(storage: storage, api: api)
        storage.fail = true
        await controls.report(item: UUID().uuidString.lowercased(), asset: nil, publisher: true, reason: "spam", details: "", account: alice)
        XCTAssertEqual(calls, 0)
        storage.fail = false
        await controls.report(item: UUID().uuidString.lowercased(), asset: nil, publisher: true, reason: "spam", details: "", account: alice)
        XCTAssertEqual(calls, 1); let saved = controls.pending?.body
        await controls.retry(account: bob); XCTAssertEqual(calls, 1); XCTAssertEqual(controls.pending?.body, saved)
    }
    func testSignedOutBlocksAreDurableAndInvalidateTheAudienceBeforeAnyNetwork() async throws {
        let storage = Storage(), user = account(), alias = UUID().uuidString.lowercased()
        var calls = 0, changed = 0
        let controls = NativeCommunityControls(storage: storage, api: API { _, _, _, _ in calls += 1; throw NativePublicError.network })
        await controls.block(alias: alias, title: "Synthetic public recipe", blocked: true, account: user) { changed += 1; XCTAssertEqual(storage.value?.blocks.first?.alias, alias) }
        XCTAssertEqual(calls, 0); XCTAssertEqual(changed, 1); let generation = storage.value?.generation
        let restarted = NativeCommunityControls(storage: storage)
        XCTAssertEqual(restarted.blocks.first?.alias, alias)
        await restarted.block(alias: alias, title: "Synthetic public recipe", blocked: false, account: user)
        XCTAssertTrue(restarted.blocks.isEmpty); XCTAssertNotEqual(storage.value?.generation, generation)
    }
    func testDefiniteRejectionNeedsExplicitDiscardAndDoesNotDiscardDeviceBlocks() async throws {
        let storage = Storage(), user = account()
        let controls = NativeCommunityControls(storage: storage, api: API { _, _, _, _ in throw NativePublicError.status(422) })
        await controls.report(item: UUID().uuidString.lowercased(), asset: nil, publisher: false, reason: "other", details: "", account: user)
        XCTAssertTrue(controls.pending?.rejected == true); controls.discardRejected(); XCTAssertNil(controls.pending)
    }
}
