import XCTest
import Security
@testable import PitTech

final class RelayTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "relay-links-v1", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }
    func testSharedLinkCasesAndFractionalSecondReceipt() throws {
        let input = try fixture(), expected = try XCTUnwrap(input["canonical"] as? String)
        for value in try XCTUnwrap(input["valid"] as? [String]) { XCTAssertEqual(try NativeRelayProtocol.code(value), expected) }
        for value in try XCTUnwrap(input["invalid"] as? [String]) { XCTAssertThrowsError(try NativeRelayProtocol.code(value), value) }
        let receipt = try JSONDecoder().decode(NativeRelayReceipt.self, from: JSONSerialization.data(withJSONObject: input["receipt"]!))
        try receipt.validate(); XCTAssertEqual(receipt.dishes, 2); XCTAssertNotEqual(receipt.expiry, .distantPast)
    }
    func testReceiptsRejectImpossibleSizesCountsAndChecksums() throws {
        let input = try XCTUnwrap(try fixture()["receipt"] as? [String: Any])
        for (key, value) in [("bytes", 0 as Any), ("bytes", NativeRelayProtocol.maximumBytes + 1), ("dishes", 21), ("sha256", "unverified"), ("expiresAt", "not-a-date"), ("code", "EMBER-OAK-7")] {
            var changed = input; changed[key] = value
            XCTAssertThrowsError(try JSONDecoder().decode(NativeRelayReceipt.self, from: JSONSerialization.data(withJSONObject: changed)).validate())
        }
    }
    func testDeviceOnlyCapabilitiesPersistAndNewSharesRetainEarlierOwners() throws {
        let service = "com.pittech.relay.tests." + UUID().uuidString
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer {
            SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
            try? FileManager.default.removeItem(at: root)
        }
        let vault = NativeRelayVault(service: service, directory: root)
        var first = try vault.prepare("selected-draft", revision: 1, title: "Selected pork", bytes: Data("immutable first export".utf8))
        let restored = try XCTUnwrap(NativeRelayVault(service: service, directory: root).all().first)
        XCTAssertEqual(restored.owner, first.owner); XCTAssertEqual(try Data(contentsOf: vault.pack(restored)), Data("immutable first export".utf8))
        XCTAssertThrowsError(try vault.prepare("selected-draft", revision: 2, title: "Changed selection", bytes: Data("changed export".utf8)))
        let oldOrphan = root.appendingPathComponent(UUID().uuidString + ".pittech")
        let freshOrphan = root.appendingPathComponent(UUID().uuidString + ".pittech")
        try Data("incomplete export".utf8).write(to: oldOrphan)
        try Data("being prepared".utf8).write(to: freshOrphan)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -172_800)], ofItemAtPath: oldOrphan.path)
        try vault.prunePending()
        XCTAssertFalse(FileManager.default.fileExists(atPath: oldOrphan.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: freshOrphan.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: try vault.pack(first).path))
        let json = try JSONSerialization.data(withJSONObject: try fixture()["receipt"]!)
        first.receipt = try JSONDecoder().decode(NativeRelayReceipt.self, from: json); try vault.save(first)
        let second = try vault.prepare("selected-draft", revision: 2, title: "Changed selection", bytes: Data("second immutable export".utf8))
        XCTAssertNotEqual(first.owner, second.owner); XCTAssertEqual(try vault.all().count, 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: try vault.pack(first).path))
        XCTAssertTrue(try vault.directory().resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        var attributes: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: first.id, kSecReturnAttributes as String: true] as CFDictionary, &attributes)
        XCTAssertEqual(status, errSecSuccess)
        let values = try XCTUnwrap(attributes as? [String: Any])
        XCTAssertEqual(values[kSecAttrAccessible as String] as? String, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        XCTAssertNotEqual(values[kSecAttrSynchronizable as String] as? Bool, true)
    }
    func testTransportVerifiesBytesAndRejectsCorruptionOversizeAndForeignOrigin() async throws {
        for mode in [RelayURLProtocol.Mode.valid, .corrupt, .oversize] {
            let token = UUID().uuidString; RelayURLProtocol.set(token, mode: mode)
            let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [RelayURLProtocol.self]
            let url = URL(string: "https://api.pittech.app/test/" + token)!
            let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: target) }
            do {
                let result = try await NativeRelayTransport(configuration: config).run(URLRequest(url: url), download: target)
                XCTAssertEqual(mode, .valid); XCTAssertEqual(try Data(contentsOf: XCTUnwrap(result.file)), RelayURLProtocol.bytes)
            } catch { XCTAssertNotEqual(mode, .valid); XCTAssertFalse(FileManager.default.fileExists(atPath: target.path)) }
        }
        do { _ = try await NativeRelayTransport().run(URLRequest(url: URL(string: "https://api.pittech.app.attacker.invalid/packs/ABCD-1234-5678-9EF0")!)); XCTFail("Foreign origin accepted") }
        catch { XCTAssertTrue(error is NativeRelayError) }
    }
    func testCancelBeforeResponseLeavesNoDownloadedFile() async throws {
        let token = UUID().uuidString; RelayURLProtocol.set(token, mode: .delayed)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [RelayURLProtocol.self]
        let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let task = Task { try await NativeRelayTransport(configuration: config).run(URLRequest(url: URL(string: "https://api.pittech.app/test/" + token)!), download: target) }
        task.cancel()
        do { _ = try await task.value; XCTFail("Canceled download completed") } catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: target.path))
    }
}

private final class RelayURLProtocol: URLProtocol, @unchecked Sendable {
    enum Mode: Equatable { case valid, corrupt, oversize, delayed }
    static let bytes = Data("bounded synthetic Cook Pack bytes".utf8)
    private static let lock = NSLock()
    private static var modes: [String: Mode] = [:]
    private var stopped = false
    static func set(_ key: String, mode: Mode) { lock.lock(); modes[key] = mode; lock.unlock() }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "api.pittech.app" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); let mode = Self.modes.removeValue(forKey: request.url!.lastPathComponent) ?? .corrupt; Self.lock.unlock()
        if mode == .delayed { return }
        let headers = ["Content-Type": "application/octet-stream", "Content-Length": String(mode == .oversize ? NativeRelayProtocol.maximumBytes + 1 : Int64(Self.bytes.count)), "X-Content-SHA256": mode == .corrupt ? String(repeating: "b", count: 64) : NativeRelayProtocol.hash(Self.bytes)]
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.bytes); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { stopped = true }
}
