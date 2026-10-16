import XCTest
@testable import PitTech

@MainActor final class PublicationOperationsTests: XCTestCase {
    private let user = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private let draft = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private func directory() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    func testAccountPurgeRemovesOnlyItsPrivateMetadataAndBodyAndRetriesIdempotently() throws {
        let root = directory(); defer { try? FileManager.default.removeItem(at: root) }
        let store = NativePublicationOperationsStore(directory: root), other = UUID().uuidString.lowercased()
        let a = try store.prepare(user: user, method: "POST", path: "/v1/recipes", bytes: Data("Alice selected public draft".utf8), archive: true)
        let b = try store.prepare(user: other, method: "POST", path: "/v1/recipes", bytes: Data("Bob selected public draft".utf8), archive: true)
        try store.purgeAccount(user); try store.purgeAccount(user)
        XCTAssertNil(try store.pending(user)); XCTAssertFalse(FileManager.default.fileExists(atPath: try store.body(a).path))
        XCTAssertEqual(try store.pending(other)?.id, b.id); XCTAssertEqual(try Data(contentsOf: store.verify(b)), Data("Bob selected public draft".utf8))
    }
    func testArchiveTransportPinsRevisionAndNeverFollowsRedirectsOrLeaksDiagnostics() async throws {
        let root = directory(); try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data("selected public bytes".utf8), file = root.appendingPathComponent("body"); try bytes.write(to: file)
        let operation = NativePublicationOperation(id: "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa", userId: user, method: "PUT", path: "/v1/recipes/cccccccc-cccc-4ccc-cccc-cccccccccccc", contentType: "application/zip", sha256: NativeRelayProtocol.hash(bytes), bytes: bytes.count, expectedRevision: "dddddddd-dddd-4ddd-dddd-dddddddddddd", draftId: draft, termsVersion: NativePublicationTerms.version)
        for status in [200, 302, 401, 409, 999] {
            let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [PublicationURLProtocol.self]; configuration.httpAdditionalHeaders = ["X-Publication-Test": String(status)]
            do { _ = try await NativePublicationClient(configuration: configuration).write(operation, file: file, access: String(repeating: "A", count: 43)); XCTAssertEqual(status, 200) }
            catch { XCTAssertNotEqual(status, 200); XCTAssertFalse(error.localizedDescription.contains("private-provider-diagnostic")) }
        }
    }
    func testOwnerArchiveDownloadPinsRevisionChecksSizeAndHashAndCleansRejectedDownloads() async throws {
        let root = directory(); try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: root) }
        for mode in [200, 302, 401, 409, 998, 999] {
            let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [OwnerArchiveURLProtocol.self]; configuration.httpAdditionalHeaders = ["X-Owner-Test": String(mode)]
            let file = root.appendingPathComponent("download")
            do {
                _ = try await NativePublicationClient(configuration: configuration).archive("dddddddd-dddd-4ddd-dddd-dddddddddddd", revision: "eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee", to: file, access: String(repeating: "A", count: 43))
                XCTAssertEqual(mode, 200); XCTAssertEqual(try Data(contentsOf: file), Data("owner bytes".utf8)); try FileManager.default.removeItem(at: file)
            } catch { XCTAssertNotEqual(mode, 200); XCTAssertFalse(FileManager.default.fileExists(atPath: file.path)); XCTAssertFalse(error.localizedDescription.contains("private-provider-diagnostic")) }
        }
    }
    func testLostResponseRecoversIdenticalBodyAndOperationAfterRestartWithoutDuplicatePublication() async throws {
        let root = directory(); defer { try? FileManager.default.removeItem(at: root) }
        let store = NativePublicationOperationsStore(directory: root), session = PublicationTestSession(user), api = PublicationTestAPI()
        let bytes = Data("exact selected public archive".utf8)
        let operation = try store.prepare(user: user, method: "POST", path: "/v1/recipes", bytes: bytes, archive: true, draft: draft, kind: "attempt", termsVersion: NativePublicationTerms.version)
        api.loseResponse = true
        let first = NativePublicationWriter(session: session, store: store, api: api)
        do { _ = try await first.recover(); XCTFail("Expected lost response") } catch { }
        XCTAssertEqual(try store.pending(user)?.termsVersion, NativePublicationTerms.version)
        XCTAssertEqual(try store.pending(user)?.id, operation.id)
        XCTAssertThrowsError(try store.discardRejected(user))
        let restartedStore = NativePublicationOperationsStore(directory: root)
        let restarted = NativePublicationWriter(session: session, store: restartedStore, api: api)
        _ = try await restarted.recover()
        XCTAssertEqual(api.effects.count, 1); XCTAssertEqual(api.operations, [operation.id, operation.id]); XCTAssertEqual(api.bodies, [bytes, bytes])
        XCTAssertNil(try restartedStore.pending(user)); XCTAssertEqual(try restartedStore.binding(user, draft: draft)?.id, api.receipt.id); XCTAssertEqual(try restartedStore.binding(user, draft: draft)?.kind, "attempt")
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
        let malformed = try store.prepare(user: user, method: "POST", path: "/v1/recipes/" + api.receipt.id + "/like", bytes: Data("{}".utf8), archive: false)
        do { _ = try await NativePublicationWriter(session: session, store: store, api: api).recover(); XCTFail("Malformed success cleared the request") } catch { }
        XCTAssertEqual(try store.pending(user)?.id, malformed.id); XCTAssertEqual(try store.pending(user)?.rejected, false); XCTAssertThrowsError(try store.discardRejected(user))
        api.readStatuses = [401]; session.refreshes = []
        _ = try await NativePublicationWriter(session: session, store: store, api: api).read("/v1/me/profile")
        api.archiveStatuses = [401]; session.refreshes = []
        let download = root.appendingPathComponent("owner.zip"), writer = NativePublicationWriter(session: session, store: store, api: api)
        _ = try await writer.ownedArchive(api.receipt, to: download)
        XCTAssertEqual(api.archives, 2); XCTAssertEqual(session.refreshes, [false, true]); XCTAssertEqual(try Data(contentsOf: download), Data("owner bytes".utf8))
        api.onArchive = { session.publicationUserId = "cccccccc-cccc-4ccc-cccc-cccccccccccc" }
        do { _ = try await writer.ownedArchive(api.receipt, to: download); XCTFail("Changed account received owner bytes") } catch { }
        XCTAssertFalse(FileManager.default.fileExists(atPath: download.path))
        session.publicationUserId = user; session.onAccess = nil
        XCTAssertEqual(api.reads, 2); XCTAssertEqual(try store.pending(user)?.id, malformed.id)
    }
}
@MainActor private final class PublicationTestSession: NativePublicationSession {
    var publicationUserId: String?
    var refreshes: [Bool] = []
    var onAccess: (() -> Void)?
    init(_ user: String) { publicationUserId = user }
    func access(forceRefresh: Bool) async throws -> String { refreshes.append(forceRefresh); onAccess?(); return forceRefresh ? String(repeating: "B", count: 42) + "A" : String(repeating: "A", count: 43) }
}
private final class PublicationTestAPI: NativePublicationAPI {
    let receipt = NativePublicationReceipt(id: "dddddddd-dddd-4ddd-dddd-dddddddddddd", revisionId: "eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee", revision: 1, url: "https://pittech.app/c/dddddddd-dddd-4ddd-dddd-dddddddddddd")
    var operations: [String] = [], bodies: [Data] = [], effects = Set<String>(), statuses: [Int] = []
    var loseResponse = false
    var readStatuses: [Int] = [], reads = 0
    var archiveStatuses: [Int] = [], archives = 0
    var onArchive: (() -> Void)?
    func archive(_ id: String, revision: String, to: URL, access: String) async throws -> URL {
        archives += 1; XCTAssertEqual(id, receipt.id); XCTAssertEqual(revision, receipt.revisionId)
        if !archiveStatuses.isEmpty { throw NativePublicError.status(archiveStatuses.removeFirst()) }
        try Data("owner bytes".utf8).write(to: to); onArchive?(); return to
    }
    func read(_ path: String, access: String) async throws -> Data { reads += 1; if !readStatuses.isEmpty { throw NativePublicError.status(readStatuses.removeFirst()) }; return Data("{}".utf8) }
    func write(_ operation: NativePublicationOperation, file: URL, access: String) async throws -> Data {
        operations.append(operation.id); bodies.append(try Data(contentsOf: file))
        if !statuses.isEmpty { throw NativePublicError.status(statuses.removeFirst()) }
        effects.insert(operation.id)
        if loseResponse { loseResponse = false; throw URLError(.networkConnectionLost) }
        return try JSONEncoder().encode(receipt)
    }
}

private final class PublicationURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "api.pittech.app" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.absoluteString, "https://api.pittech.app/v1/recipes/cccccccc-cccc-4ccc-cccc-cccccccccccc")
        XCTAssertEqual(request.httpMethod, "PUT"); XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa")
        XCTAssertEqual(request.value(forHTTPHeaderField: "If-Match"), "dddddddd-dddd-4ddd-dddd-dddddddddddd")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + String(repeating: "A", count: 43)); XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-PitTech-Publication-Terms"), NativePublicationTerms.version)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/zip")
        let status = Int(request.value(forHTTPHeaderField: "X-Publication-Test") ?? "401") ?? 401
        let bytes = status == 999 ? Data(repeating: 120, count: 8193) : Data((status == 200 ? "{}" : "private-provider-diagnostic").utf8)
        let response = HTTPURLResponse(url: request.url!, statusCode: status == 999 ? 200 : status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json", "Set-Cookie": "sensitive=discard", "Location": "https://attacker.invalid/"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed); client?.urlProtocol(self, didLoad: bytes); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

private final class OwnerArchiveURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "api.pittech.app" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.absoluteString, "https://api.pittech.app/v1/me/recipes/dddddddd-dddd-4ddd-dddd-dddddddddddd/archive")
        XCTAssertEqual(request.httpMethod, "GET"); XCTAssertEqual(request.value(forHTTPHeaderField: "If-Match"), "eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + String(repeating: "A", count: 43)); XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        let mode = Int(request.value(forHTTPHeaderField: "X-Owner-Test") ?? "401") ?? 401, bytes = Data("owner bytes".utf8)
        let response = HTTPURLResponse(url: request.url!, statusCode: mode > 900 ? 200 : mode, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/zip", "Content-Length": mode == 998 ? "67108865" : String(bytes.count), "X-Content-SHA256": mode == 999 ? String(repeating: "0", count: 64) : NativeRelayProtocol.hash(bytes), "Location": "https://attacker.invalid/"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed); client?.urlProtocol(self, didLoad: bytes); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
