import XCTest
@testable import PitTech

@MainActor final class PublicationOperationsTests: XCTestCase {
    private let user = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private let draft = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private func directory() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    func testLostResponseRecoversIdenticalBodyAndOperationAfterRestartWithoutDuplicatePublication() async throws {
        let root = directory(); defer { try? FileManager.default.removeItem(at: root) }
        let store = NativePublicationOperationsStore(directory: root), session = PublicationTestSession(user), api = PublicationTestAPI()
        let bytes = Data("exact selected public archive".utf8)
        let operation = try store.prepare(user: user, method: "POST", path: "/v1/recipes", bytes: bytes, archive: true, draft: draft)
        api.loseResponse = true
        let first = NativePublicationWriter(session: session, store: store, api: api)
        do { _ = try await first.recover(); XCTFail("Expected lost response") } catch { }
        XCTAssertEqual(try store.pending(user)?.id, operation.id)
        XCTAssertThrowsError(try store.discardRejected(user))
        let restartedStore = NativePublicationOperationsStore(directory: root)
        let restarted = NativePublicationWriter(session: session, store: restartedStore, api: api)
        _ = try await restarted.recover()
        XCTAssertEqual(api.effects.count, 1); XCTAssertEqual(api.operations, [operation.id, operation.id]); XCTAssertEqual(api.bodies, [bytes, bytes])
        XCTAssertNil(try restartedStore.pending(user)); XCTAssertEqual(try restartedStore.binding(user, draft: draft)?.id, api.receipt.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: try store.body(operation).path))
        let values = try root.resourceValues(forKeys: [.isExcludedFromBackupKey]); XCTAssertEqual(values.isExcludedFromBackup, true)
    }
    func testAccountChangeDuringAuthorizationAndCorruptBodyNeverReachServer() async throws {
        let root = directory(); defer { try? FileManager.default.removeItem(at: root) }
        let store = NativePublicationOperationsStore(directory: root), session = PublicationTestSession(user), api = PublicationTestAPI()
        let operation = try store.prepare(user: user, method: "POST", path: "/v1/recipes", bytes: Data("preview".utf8), archive: true, draft: draft)
        session.onAccess = { session.publicationUserId = "cccccccc-cccc-4ccc-cccc-cccccccccccc" }
        let writer = NativePublicationWriter(session: session, store: store, api: api)
        do { _ = try await writer.recover(); XCTFail("Changed account sent request") } catch { }
        XCTAssertTrue(api.operations.isEmpty); XCTAssertNil(try writer.pending()); XCTAssertEqual(try store.pending(user)?.id, operation.id)
        session.publicationUserId = user; session.onAccess = nil
        try Data("changed".utf8).write(to: store.body(operation))
        do { _ = try await writer.recover(); XCTFail("Corrupt body sent request") } catch { }
        XCTAssertTrue(api.operations.isEmpty); XCTAssertEqual(try store.pending(user)?.id, operation.id)
    }
    func testUnauthorizedRetriesSameOperationOnceAndDefiniteConflictRequiresExplicitDiscard() async throws {
        let root = directory(); defer { try? FileManager.default.removeItem(at: root) }
        let store = NativePublicationOperationsStore(directory: root), session = PublicationTestSession(user), api = PublicationTestAPI()
        let operation = try store.prepare(user: user, method: "POST", path: "/v1/recipes", bytes: Data("preview".utf8), archive: true, draft: draft)
        api.statuses = [401]
        _ = try await NativePublicationWriter(session: session, store: store, api: api).recover()
        XCTAssertEqual(session.refreshes, [false, true]); XCTAssertEqual(api.operations, [operation.id, operation.id])
        let conflict = try store.prepare(user: user, method: "DELETE", path: "/v1/recipes/" + api.receipt.id, bytes: Data("{}".utf8), archive: false, expected: api.receipt.revisionId)
        api.statuses = [409]
        do { _ = try await NativePublicationWriter(session: session, store: store, api: api).recover(); XCTFail("Expected revision conflict") } catch { }
        XCTAssertEqual(try store.pending(user)?.id, conflict.id); XCTAssertEqual(try store.pending(user)?.rejected, true)
        XCTAssertThrowsError(try store.prepare(user: user, method: "POST", path: "/v1/recipes", bytes: Data("other".utf8), archive: true))
        try store.discardRejected(user); XCTAssertNil(try store.pending(user))
    }
}
@MainActor private final class PublicationTestSession: NativePublicationSession {
    var publicationUserId: String?
    var refreshes: [Bool] = []
    var onAccess: (() -> Void)?
    init(_ user: String) { publicationUserId = user }
    func access(forceRefresh: Bool) async throws -> String { refreshes.append(forceRefresh); onAccess?(); return String(repeating: forceRefresh ? "B" : "A", count: 43) }
}
private final class PublicationTestAPI: NativePublicationAPI {
    let receipt = NativePublicationReceipt(id: "dddddddd-dddd-4ddd-dddd-dddddddddddd", revisionId: "eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee", revision: 1, url: "https://pittech.app/c/dddddddd-dddd-4ddd-dddd-dddddddddddd")
    var operations: [String] = [], bodies: [Data] = [], effects = Set<String>(), statuses: [Int] = []
    var loseResponse = false
    func read(_ path: String, access: String) async throws -> Data { Data("{}".utf8) }
    func write(_ operation: NativePublicationOperation, file: URL, access: String) async throws -> Data {
        operations.append(operation.id); bodies.append(try Data(contentsOf: file))
        if !statuses.isEmpty { throw NativePublicError.status(statuses.removeFirst()) }
        effects.insert(operation.id)
        if loseResponse { loseResponse = false; throw URLError(.networkConnectionLost) }
        return try JSONEncoder().encode(receipt)
    }
}
