import XCTest
@testable import PitTech

@MainActor final class SubscriptionEntitlementTests: XCTestCase {
    private final class MemoryStore: PreviouslyEntitledStore {
        var previouslyEntitled = false
        func markEntitled() { previouslyEntitled = true }
    }

    func testEntitledUsersTriggerZeroAdRequests() {
        XCTAssertFalse(shouldRequestAds(entitlement: .entitled, previouslyEntitled: true))
        XCTAssertFalse(shouldRequestAds(entitlement: .entitled, previouslyEntitled: false))
        let monitor = AdRequestMonitor()
        XCTAssertFalse(monitor.shouldAttempt(entitlement: .entitled, previouslyEntitled: true))
        XCTAssertTrue(monitor.attempts.isEmpty)
    }

    func testVerifiedNotEntitledAllowsRequests() {
        XCTAssertTrue(shouldRequestAds(entitlement: .notEntitled, previouslyEntitled: false))
        let monitor = AdRequestMonitor()
        XCTAssertTrue(monitor.shouldAttempt(entitlement: .notEntitled, previouslyEntitled: false))
        XCTAssertEqual(monitor.attempts.count, 1)
    }

    func testUnknownEntitlementFailsClosedForPreviouslyEntitledAccounts() {
        XCTAssertFalse(shouldRequestAds(entitlement: .unknown, previouslyEntitled: true))
        let monitor = AdRequestMonitor()
        XCTAssertFalse(monitor.shouldAttempt(entitlement: .unknown, previouslyEntitled: true))
        XCTAssertTrue(monitor.attempts.isEmpty)
        XCTAssertTrue(shouldRequestAds(entitlement: .unknown, previouslyEntitled: false))
    }

    func testServerCacheMapsLiveBillingStatesToEntitledAndEndedStatesToNotEntitled() {
        let now = Date(timeIntervalSince1970: 1_790_000_000)
        for state in ["active", "grace", "retry", "paused"] {
            XCTAssertEqual(adEntitlementFromServerCache(verified: true, state: state, verifiedUntil: now.addingTimeInterval(1), now: now), .entitled, state)
        }
        XCTAssertEqual(adEntitlementFromServerCache(verified: true, state: "cancelled", verifiedUntil: now.addingTimeInterval(1), now: now), .entitled)
        for state in ["none", "pending", "expired", "refunded", "revoked"] {
            XCTAssertEqual(adEntitlementFromServerCache(verified: true, state: state, verifiedUntil: nil, now: now), .notEntitled, state)
        }
        XCTAssertEqual(adEntitlementFromServerCache(verified: true, state: "cancelled", verifiedUntil: now.addingTimeInterval(-1), now: now), .notEntitled)
        XCTAssertEqual(adEntitlementFromServerCache(verified: false, state: "active", verifiedUntil: now.addingTimeInterval(1), now: now), .unknown)
        XCTAssertEqual(adEntitlementFromServerCache(verified: true, state: "active", verifiedUntil: now.addingTimeInterval(1), cacheVersion: "entitlement-cache-v0", now: now), .unknown)
        XCTAssertEqual(adEntitlementFromServerCache(verified: true, state: "future-state", verifiedUntil: now.addingTimeInterval(1), now: now), .unknown)
    }

    func testVersionedCacheRecordDecodesAndHonorsTheOfflineWindow() throws {
        let json = """
        {"version":"entitlement-cache-v1","environment":"production","verified":true,"state":"active","verifiedUntil":"2026-11-17T12:00:00.000Z","lastVerifiedAt":"2026-10-18T12:00:00.000Z","offlineValidUntil":"2026-10-25T12:00:00.000Z","contractVersion":"store-verification-v1"}
        """
        let cache = try SubscriptionEntitlementCache.decode(Data(json.utf8))
        XCTAssertEqual(cache.version, SubscriptionEntitlementContract.cacheVersion)
        XCTAssertEqual(cache.verifiedUntil, Date(timeIntervalSince1970: 1_792_324_800 + 2_592_000))
        XCTAssertEqual(cache.entitlement(now: Date(timeIntervalSince1970: 1_792_324_800)), .entitled)
        XCTAssertTrue(cache.offlineUsable(now: Date(timeIntervalSince1970: 1_792_324_800)))
        XCTAssertFalse(cache.offlineUsable(now: Date(timeIntervalSince1970: 1_792_324_800 + 604_801)))
        let plain = try SubscriptionEntitlementCache.decode(Data(json.replacingOccurrences(of: ".000Z", with: "Z").utf8))
        XCTAssertEqual(plain, cache)
        let unknown = try SubscriptionEntitlementCache.decode(Data("""
        {"version":"entitlement-cache-v1","environment":"production","verified":false,"state":"unknown","verifiedUntil":null,"lastVerifiedAt":null,"offlineValidUntil":null,"contractVersion":"store-verification-v1"}
        """.utf8))
        XCTAssertEqual(unknown.entitlement(), .unknown)
        XCTAssertFalse(unknown.offlineUsable())
        XCTAssertThrowsError(SubscriptionEntitlementCache.decode(Data(json.replacingOccurrences(of: "\"production\"", with: "\"other\"").utf8)))
    }

    func testConsentManagerPersistsPreviouslyEntitledAndHoldsAdsWhileUnverified() {
        let store = MemoryStore()
        let manager = AdsConsentManager(entitlementStore: store)
        XCTAssertEqual(manager.entitlement, .unknown)
        XCTAssertFalse(manager.previouslyEntitled)
        manager.updateEntitlement(.notEntitled)
        XCTAssertFalse(store.previouslyEntitled)
        manager.updateEntitlement(.entitled)
        XCTAssertTrue(store.previouslyEntitled)
        manager.updateEntitlement(.unknown)
        XCTAssertFalse(shouldRequestAds(entitlement: manager.entitlement, previouslyEntitled: manager.previouslyEntitled))
        XCTAssertFalse(manager.adsServingAllowed)
    }
}

final class SubscriptionTransportTests: XCTestCase {
    func testExactSandboxReadIsAnAuthenticatedGetWithBoundedResponsesAndNoRedirects() async throws {
        for path in ["/v1/me/entitlement", "/v1/me/entitlement?environment=sandbox"] {
            for status in [200, 302, 401, 999] {
                let id = UUID().uuidString
                SubscriptionURLProtocol.set(id, path: path, status: status)
                let configuration = URLSessionConfiguration.ephemeral
                configuration.protocolClasses = [SubscriptionURLProtocol.self]
                configuration.httpAdditionalHeaders = ["X-Subscription-Test": id]
                do {
                    let data = try await NativeSubscriptionClient(configuration: configuration).call(path, body: nil, access: String(repeating: "A", count: 43))
                    XCTAssertEqual(status, 200)
                    XCTAssertEqual(data, Data("{}".utf8))
                } catch {
                    XCTAssertNotEqual(status, 200)
                    XCTAssertFalse(error.localizedDescription.contains("private-provider-diagnostic"))
                }
            }
        }
    }

    func testUnapprovedQueriesAndReadBodiesAreRejectedBeforeNetworkAccess() async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SubscriptionURLProtocol.self]
        let client = NativeSubscriptionClient(configuration: configuration)
        for path in ["/v1/me/entitlement?environment=production", "/v1/me/entitlement?environment=sandbox&environment=production",
                     "/v1/me/entitlement?environment=sandbox#fragment", "/v1/me/purchases/verify?environment=sandbox"] {
            do {
                _ = try await client.call(path, body: nil, access: String(repeating: "A", count: 43))
                XCTFail("Unapproved query accepted")
            } catch NativeSubscriptionError.invalid {} catch { XCTFail("Unexpected error: \(error)") }
        }
        do {
            _ = try await client.call("/v1/me/entitlement?environment=sandbox", body: [:], access: String(repeating: "A", count: 43))
            XCTFail("GET body accepted")
        } catch NativeSubscriptionError.invalid {} catch { XCTFail("Unexpected error: \(error)") }
    }
}

private final class SubscriptionURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var fixtures: [String: (path: String, status: Int)] = [:]
    static func set(_ id: String, path: String, status: Int) {
        lock.lock(); defer { lock.unlock() }
        fixtures[id] = (path, status)
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let fixture = Self.fixtures.removeValue(forKey: request.value(forHTTPHeaderField: "X-Subscription-Test") ?? "")
        Self.lock.unlock()
        guard let fixture else {
            XCTFail("Unexpected network access")
            client?.urlProtocol(self, didFailWithError: NativeSubscriptionError.invalid)
            return
        }
        XCTAssertEqual(request.url?.absoluteString, "https://api.pittech.app" + fixture.path)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertNil(request.httpBody)
        XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + String(repeating: "A", count: 43))
        let bytes = fixture.status == 999 ? Data(repeating: 120, count: 65537) : Data((fixture.status == 200 ? "{}" : "private-provider-diagnostic").utf8)
        let response = HTTPURLResponse(url: request.url!, statusCode: fixture.status == 999 ? 200 : fixture.status,
            httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json", "Location": "https://attacker.invalid/", "Set-Cookie": "sensitive=discard"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: bytes)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
