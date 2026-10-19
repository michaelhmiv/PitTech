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
