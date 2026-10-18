import XCTest
@testable import PitTech

/// Slice 16 native lane: paywall gating, server-verified purchases, and the
/// Live/Finish no-interrupt rule. The store and the backend are stubbed; every
/// entitlement decision under test traces to a scripted verified cache record.
@MainActor final class ProPaywallTests: XCTestCase {
    private final class FakeStore: ProStoreClient, @unchecked Sendable {
        var productId = "stub.pro.monthly"
        var product = ProStoreProduct(productId: "stub.pro.monthly", displayPrice: "$3.00", isMonthly: true)
        var purchaseResult: ProPurchaseOutcome = .cancelled
        var purchaseCalls = 0
        var entitlements: [ProStoreTransaction] = []
        var finished: [String] = []
        var updatesToSend: [ProStoreTransaction] = []
        var failFetch = false

        func fetchProduct() async throws -> ProStoreProduct {
            if failFetch || productId.isEmpty { throw ProStoreError.unavailable }
            return product
        }
        func purchase() async throws -> ProPurchaseOutcome {
            purchaseCalls += 1
            return purchaseResult
        }
        func currentEntitlements() async -> [ProStoreTransaction] { entitlements }
        func finish(_ transactionId: String) async { finished.append(transactionId) }
        func updates() -> AsyncStream<ProStoreTransaction> {
            let items = updatesToSend
            return AsyncStream { continuation in
                for item in items { continuation.yield(item) }
                continuation.finish()
            }
        }
    }

    private final class FakeAPI: NativeSubscriptionAPI, @unchecked Sendable {
        var calls: [String] = []
        var entitlementState = "unknown"
        var entitlementVerified: Bool?
        var throwOnVerify = false
        var throwOnEntitlement = false
        var conflictRecovery: String?
        var verifyNote = "stub-verified"

        private func cacheData(state: String, verified: Bool) throws -> Data {
            let json = """
            {"version":"entitlement-cache-v1","environment":"production","verified":\(verified ? "true" : "false"),"state":"\(state)","verifiedUntil":"2026-11-17T12:00:00.000Z","lastVerifiedAt":"2026-10-18T12:00:00.000Z","offlineValidUntil":"2026-10-25T12:00:00.000Z","contractVersion":"store-verification-v1"}
            """
            return Data(json.utf8)
        }

        func call(_ path: String, body: [String: Any]?, access: String?) async throws -> Data {
            calls.append(path)
            guard access != nil else { throw NativeSubscriptionError.status(401) }
            switch path {
            case "/v1/me/entitlement":
                if throwOnEntitlement { throw NativeSubscriptionError.network }
                let verified = entitlementVerified ?? (entitlementState != "unknown")
                return try cacheData(state: entitlementState, verified: verified)
            case "/v1/me/entitlement/reconcile":
                let verified = entitlementVerified ?? (entitlementState != "unknown")
                return try cacheData(state: entitlementState, verified: verified)
            case "/v1/me/purchases/verify":
                if throwOnVerify { throw NativeSubscriptionError.network }
                XCTAssertEqual(body?["store"] as? String, "apple")
                XCTAssertNotNil(body?["signedTransaction"])
                return try outcome(state: conflictRecovery == nil ? "active" : "none")
            case "/v1/me/purchases/restore":
                let proofs = body?["proofs"] as? [[String: Any]] ?? []
                XCTAssertFalse(proofs.isEmpty)
                return try JSONSerialization.data(withJSONObject: ["outcomes": [try outcomeObject(state: "active")]])
            case "/v1/me/purchases/release":
                return try JSONSerialization.data(withJSONObject: ["released": true])
            default:
                throw NativeSubscriptionError.invalid
            }
        }

        private func outcomeObject(state: String) throws -> [String: Any] {
            let cache = try JSONSerialization.jsonObject(with: cacheData(state: state, verified: true))
            var outcome: [String: Any] = [
                "cache": cache,
                "applied": conflictRecovery == nil,
                "note": verifyNote,
                "transaction": ["store": "apple", "environment": "production", "transactionKey": "stub-transaction", "evidenceRef": String(repeating: "a", count: 64)],
            ]
            if let recovery = conflictRecovery {
                outcome["conflict"] = ["id": "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa", "state": "open", "recovery": recovery]
            }
            return outcome
        }

        private func outcome(state: String) throws -> Data {
            try JSONSerialization.data(withJSONObject: outcomeObject(state: state))
        }
    }

    private var reported: [AdEntitlementState] = []

    private func makeCoordinator(store: FakeStore = FakeStore(), api: FakeAPI = FakeAPI(), token: String? = String(repeating: "A", count: 43)) -> (ProSubscriptionCoordinator, FakeStore, FakeAPI) {
        reported = []
        let coordinator = ProSubscriptionCoordinator(
            store: store,
            api: api,
            sessionProvider: { token },
            accountsEnabled: true,
            cacheStore: ProCacheStore(defaults: UserDefaults(suiteName: "ProPaywallTests-\(UUID().uuidString)")!),
            onEntitlement: { [weak self] in self?.reported.append($0) }
        )
        return (coordinator, store, api)
    }

    // MARK: - Billing text uses the store product's localized price

    func testBillingTextComesFromTheStoreProduct() throws {
        let product = ProStoreProduct(productId: "stub.pro.monthly", displayPrice: "A$4.99", isMonthly: true)
        let text = try XCTUnwrap(proBillingText(product: product))
        XCTAssertTrue(text.contains("A$4.99"), "the price must come from the product object, not a hardcoded string")
        XCTAssertTrue(text.contains("per month"))
        XCTAssertNil(proBillingText(product: nil))
        XCTAssertNil(proBillingText(product: ProStoreProduct(productId: "", displayPrice: "$3.00", isMonthly: true)))
        XCTAssertNil(proBillingText(product: ProStoreProduct(productId: "stub.pro.monthly", displayPrice: "$3.00", isMonthly: false)))
        XCTAssertNil(proBillingText(product: ProStoreProduct(productId: "stub.pro.monthly", displayPrice: "", isMonthly: true)))
        XCTAssertEqual(proSubscribeAccessibilityLabel(product: product), "Subscribe to PitTech Pro for A$4.99 per month")
        XCTAssertNil(proSubscribeAccessibilityLabel(product: nil))
    }

    // MARK: - Entitlement-state UI matrix

    func testGateMatrixRoutesEveryState() {
        // Signed-out users see the paywall preview with a sign-in requirement.
        XCTAssertEqual(proDestination(signedIn: false, entitlement: .unknown, serverState: nil, previouslyEntitled: false), .paywall)
        XCTAssertEqual(proDestination(signedIn: false, entitlement: .entitled, serverState: "active", previouslyEntitled: true), .paywall)
        // Unknown/loading is neutral for previously entitled users too.
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .unknown, serverState: "unknown", previouslyEntitled: true), .loading)
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .unknown, serverState: "unknown", previouslyEntitled: false), .loading)
        // Verified entitled opens Pro; verified not-entitled sees the paywall.
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .entitled, serverState: "active", previouslyEntitled: true), .pro)
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .notEntitled, serverState: "none", previouslyEntitled: false), .paywall)
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .notEntitled, serverState: "pending", previouslyEntitled: false), .paywall)
        // Verified ended states fall back to free capabilities.
        for state in ["expired", "cancelled", "refunded", "revoked"] {
            XCTAssertEqual(proDestination(signedIn: true, entitlement: .notEntitled, serverState: state, previouslyEntitled: true), .expiredFallback, state)
        }
        // No second subscription offer when covered.
        XCTAssertFalse(proShowsPurchaseOffer(entitlement: .entitled))
        XCTAssertTrue(proShowsPurchaseOffer(entitlement: .notEntitled))
        XCTAssertTrue(proShowsPurchaseOffer(entitlement: .unknown))
    }

    func testUnknownNeverFlashesPaywallOrAdsForPreviouslyEntitledUsers() async {
        let (coordinator, _, api) = makeCoordinator()
        api.entitlementState = "unknown"
        await coordinator.refresh()
        XCTAssertEqual(coordinator.entitlement, .unknown)
        XCTAssertEqual(
            proDestination(signedIn: true, entitlement: coordinator.entitlement, serverState: coordinator.serverState, previouslyEntitled: true),
            .loading
        )
        XCTAssertFalse(shouldRequestAds(entitlement: coordinator.entitlement, previouslyEntitled: true))
        let monitor = AdRequestMonitor()
        XCTAssertFalse(monitor.shouldAttempt(entitlement: coordinator.entitlement, previouslyEntitled: true))
        XCTAssertTrue(monitor.attempts.isEmpty)
    }

    func testEntitledStateTriggersZeroAdRequestsThroughTheSharedGate() async {
        let (coordinator, _, api) = makeCoordinator()
        api.entitlementState = "active"
        await coordinator.refresh()
        XCTAssertEqual(coordinator.entitlement, .entitled)
        XCTAssertEqual(reported, [.entitled])
        let monitor = AdRequestMonitor()
        XCTAssertFalse(monitor.shouldAttempt(entitlement: coordinator.entitlement, previouslyEntitled: true))
        XCTAssertTrue(monitor.attempts.isEmpty)
    }

    // MARK: - Purchase flows verify server-side

    func testPendingPurchaseBecomesVerifiedActiveThroughTheBackend() async throws {
        let store = FakeStore()
        let api = FakeAPI()
        store.purchaseResult = .pending
        let (coordinator, _, _) = makeCoordinator(store: store, api: api)
        await coordinator.loadProduct()
        await coordinator.refresh()
        await coordinator.subscribe()
        XCTAssertEqual(coordinator.purchaseState, .pendingStore)
        XCTAssertEqual(coordinator.entitlement, .unknown)
        XCTAssertFalse(api.calls.contains("/v1/me/purchases/verify"), "a store-pending purchase has no transaction to verify")
        // The store later delivers the transaction; the backend verifies it.
        // The plain cache read stays failing so only the verify path can unlock.
        api.throwOnEntitlement = true
        store.updatesToSend = [ProStoreTransaction(transactionId: "stub-transaction", signedTransaction: "stub-jws")]
        coordinator.start()
        let deadline = Date().addingTimeInterval(5)
        while coordinator.entitlement != .entitled, Date() < deadline {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(coordinator.entitlement, .entitled)
        XCTAssertEqual(coordinator.purchaseState, .idle)
        XCTAssertTrue(api.calls.contains("/v1/me/purchases/verify"))
        XCTAssertEqual(store.finished, ["stub-transaction"])
        XCTAssertEqual(reported.last, .entitled)
        coordinator.stop()
    }

    func testLocalPurchaseCallbackAloneNeverUnlocks() async {
        let store = FakeStore()
        let api = FakeAPI()
        api.throwOnVerify = true
        store.purchaseResult = .purchased(ProStoreTransaction(transactionId: "stub-transaction", signedTransaction: "stub-jws"))
        let (coordinator, _, _) = makeCoordinator(store: store, api: api)
        await coordinator.loadProduct()
        await coordinator.refresh()
        await coordinator.subscribe()
        XCTAssertEqual(coordinator.entitlement, .unknown)
        XCTAssertTrue(store.finished.isEmpty, "an unverified transaction stays unfinished so the store redelivers it")
        if case .failed = coordinator.purchaseState {} else {
            XCTFail("expected a failed verification state, got \(coordinator.purchaseState)")
        }
    }

    func testCancelledPurchaseLeavesEntitlementAlone() async {
        let store = FakeStore()
        let api = FakeAPI()
        store.purchaseResult = .cancelled
        let (coordinator, _, _) = makeCoordinator(store: store, api: api)
        await coordinator.loadProduct()
        await coordinator.refresh()
        await coordinator.subscribe()
        XCTAssertEqual(coordinator.purchaseState, .idle)
        XCTAssertEqual(coordinator.notice, "Purchase cancelled. Nothing was charged.")
        XCTAssertEqual(coordinator.entitlement, .unknown)
        XCTAssertFalse(api.calls.contains("/v1/me/purchases/verify"))
    }

    func testAlreadyEntitledShowsNoSecondPurchaseOffer() async {
        let store = FakeStore()
        let api = FakeAPI()
        api.entitlementState = "active"
        let (coordinator, _, _) = makeCoordinator(store: store, api: api)
        await coordinator.loadProduct()
        await coordinator.refresh()
        XCTAssertEqual(coordinator.entitlement, .entitled)
        await coordinator.subscribe()
        XCTAssertEqual(store.purchaseCalls, 0, "a covered account must never see a second purchase flow")
        XCTAssertFalse(proShowsPurchaseOffer(entitlement: coordinator.entitlement))
    }

    func testRestoreReestablishesEntitlementThroughTheBackend() async {
        let store = FakeStore()
        let api = FakeAPI()
        store.entitlements = [ProStoreTransaction(transactionId: "stub-transaction", signedTransaction: "stub-jws")]
        let (coordinator, _, _) = makeCoordinator(store: store, api: api)
        await coordinator.refresh()
        XCTAssertEqual(coordinator.entitlement, .unknown)
        await coordinator.restore()
        XCTAssertTrue(api.calls.contains("/v1/me/purchases/restore"))
        XCTAssertEqual(coordinator.entitlement, .entitled)
        XCTAssertEqual(store.finished, ["stub-transaction"])
        XCTAssertEqual(coordinator.notice, "PitTech Pro is active on this account.")
    }

    func testRestoreWithoutPurchasesExplainsItself() async {
        let (coordinator, _, api) = makeCoordinator()
        await coordinator.refresh()
        await coordinator.restore()
        XCTAssertFalse(api.calls.contains("/v1/me/purchases/restore"))
        XCTAssertEqual(coordinator.notice, "No App Store purchases were found for PitTech Pro.")
    }

    func testBindingConflictShowsTheServersRecoveryText() async {
        let store = FakeStore()
        let api = FakeAPI()
        api.conflictRecovery = "This store purchase is already bound to a different PitTech account."
        store.purchaseResult = .purchased(ProStoreTransaction(transactionId: "stub-transaction", signedTransaction: "stub-jws"))
        let (coordinator, _, _) = makeCoordinator(store: store, api: api)
        await coordinator.loadProduct()
        await coordinator.refresh()
        await coordinator.subscribe()
        XCTAssertEqual(coordinator.purchaseState, .conflict(recovery: "This store purchase is already bound to a different PitTech account."))
        XCTAssertNotEqual(coordinator.entitlement, .entitled)
        XCTAssertEqual(store.finished, ["stub-transaction"], "a handled conflict stops store redelivery")
    }

    func testExpiredCacheFallsBackWithoutUnlocking() async {
        let (coordinator, _, api) = makeCoordinator()
        api.entitlementState = "expired"
        await coordinator.refresh()
        XCTAssertEqual(coordinator.entitlement, .notEntitled)
        XCTAssertEqual(
            proDestination(signedIn: true, entitlement: coordinator.entitlement, serverState: coordinator.serverState, previouslyEntitled: true),
            .expiredFallback
        )
    }

    func testSignOutClearsTheCacheSoAccountsNeverShareEntitlement() async {
        let api = FakeAPI()
        api.entitlementState = "active"
        let tokenBox = SessionBox(token: String(repeating: "A", count: 43))
        reported = []
        let coordinator = ProSubscriptionCoordinator(
            store: FakeStore(),
            api: api,
            sessionProvider: { tokenBox.token },
            accountsEnabled: true,
            cacheStore: ProCacheStore(defaults: UserDefaults(suiteName: "ProPaywallTests-\(UUID().uuidString)")!),
            onEntitlement: { [weak self] in self?.reported.append($0) }
        )
        await coordinator.refresh()
        XCTAssertEqual(coordinator.entitlement, .entitled)
        tokenBox.token = nil
        await coordinator.refresh()
        XCTAssertFalse(coordinator.signedIn)
        XCTAssertNil(coordinator.cache)
        XCTAssertEqual(coordinator.entitlement, .unknown)
        XCTAssertEqual(reported.last, .unknown)
    }

    // MARK: - Product configuration and offline cache

    func testProductNotConfiguredIsGraceful() async {
        let store = FakeStore()
        store.productId = ""
        store.failFetch = true
        let (coordinator, _, _) = makeCoordinator(store: store)
        await coordinator.loadProduct()
        XCTAssertEqual(coordinator.productState, .unavailable)
        XCTAssertNil(proBillingText(product: nil))
        await coordinator.refresh()
        await coordinator.subscribe()
        XCTAssertEqual(store.purchaseCalls, 0)
        if case .failed(let message) = coordinator.purchaseState {
            XCTAssertEqual(message, ProStoreError.unavailable.localizedDescription)
        } else {
            XCTFail("expected an unavailable-product failure, got \(coordinator.purchaseState)")
        }
    }

    func testCacheStoreRoundTripPreservesTheOfflineWindow() throws {
        let defaults = UserDefaults(suiteName: "ProPaywallTests-\(UUID().uuidString)")!
        let cacheStore = ProCacheStore(defaults: defaults)
        let cache = try SubscriptionEntitlementCache.decode(Data("""
        {"version":"entitlement-cache-v1","environment":"production","verified":true,"state":"active","verifiedUntil":"2026-11-17T12:00:00.000Z","lastVerifiedAt":"2026-10-18T12:00:00.000Z","offlineValidUntil":"2026-10-25T12:00:00.000Z","contractVersion":"store-verification-v1"}
        """.utf8))
        cacheStore.save(cache)
        let loaded = try XCTUnwrap(cacheStore.load())
        XCTAssertEqual(loaded, cache)
        XCTAssertTrue(loaded.offlineUsable(now: Date(timeIntervalSince1970: 1_792_324_800)))
        XCTAssertFalse(loaded.offlineUsable(now: Date(timeIntervalSince1970: 1_792_324_800 + 604_801)))
        cacheStore.clear()
        XCTAssertNil(cacheStore.load())
    }

    // MARK: - No purchase popup interrupts Live or Finish

    func testLiveAndFinishNeverPresentThePaywall() throws {
        ProPaywallPresentation.reset()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let store = CookStore(directory: directory)
        store.startCook(title: "Uninterrupted meal", firstDish: "Shoulder")
        let cook = try XCTUnwrap(store.activeCooks.first)
        store.finishCook(cook.id)
        XCTAssertEqual(store.completedCooks.count, 1)
        XCTAssertTrue(ProPaywallPresentation.recorded.isEmpty, "starting and finishing a cook must never present the paywall")
    }

    func testOpeningAProEntryRecordsOnePresentation() async {
        ProPaywallPresentation.reset()
        let (coordinator, _, _) = makeCoordinator(token: nil)
        coordinator.openPro(.analysis)
        XCTAssertEqual(ProPaywallPresentation.recorded, [.analysis])
        XCTAssertEqual(coordinator.presentedEntry, .analysis)
        coordinator.closePaywall()
        XCTAssertNil(coordinator.presentedEntry)
    }
}

private final class SessionBox: @unchecked Sendable {
    var token: String?
    init(token: String?) { self.token = token }
}
