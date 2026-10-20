import Foundation
import Observation

// MARK: - Purchase state

enum ProPurchaseState: Equatable, Sendable {
    case idle
    case purchasing
    case verifying
    case pendingStore
    case conflict(recovery: String)
    case failed(message: String)
}

enum ProProductState: Equatable, Sendable {
    case idle
    case loading
    case ready(ProStoreProduct)
    case unavailable
}

// MARK: - Coordinator

/// The subscription source of truth. Entitlement changes only from verified
/// server cache records; a local purchase callback alone never unlocks.
/// Every resolution is also reported to the ad gate so entitled users trigger
/// zero ad requests from the same state the paywall reads.
@MainActor @Observable final class ProSubscriptionCoordinator {
    private let store: any ProStoreClient
    private let api: any NativeSubscriptionAPI
    private let sessionProvider: () async -> String?
    private let ownerIdProvider: () -> String?
    private let initialEnvironment: SubscriptionEnvironment
    private let now: () -> Date
    let accountsEnabled: Bool
    private let cacheStore: ProCacheStore
    private let onEntitlement: (AdEntitlementState) -> Void

    private(set) var cache: SubscriptionEntitlementCache?
    private(set) var signedIn = false
    private(set) var productState = ProProductState.idle
    private(set) var purchaseState = ProPurchaseState.idle
    private(set) var notice: String?
    private(set) var lastTransaction: (store: String, environment: String, key: String)?
    var presentedEntry: ProPlacement?

    private var started = false
    private var updateTask: Task<Void, Never>?
    private var currentOwner: String?
    private var readEnvironment: SubscriptionEnvironment
    private var cacheRevision = 0
    private struct Account { let ownerId: String; let token: String }

    var entitlement: AdEntitlementState { cache?.entitlement(now: now()) ?? .unknown }
    var serverState: String? { cache?.state }
    var isStorePending: Bool { cache?.state == "pending" }
    var busy: Bool {
        switch purchaseState {
        case .purchasing, .verifying: return true
        case .idle, .pendingStore, .conflict, .failed: return false
        }
    }

    init(
        store: any ProStoreClient,
        api: any NativeSubscriptionAPI = NativeSubscriptionClient(),
        sessionProvider: @escaping () async -> String?,
        ownerIdProvider: @escaping () -> String?,
        accountsEnabled: Bool,
        cacheStore: ProCacheStore = ProCacheStore(),
        initialEnvironment: SubscriptionEnvironment = NativeOnlineConfiguration.current.sandbox ? .sandbox : .production,
        now: @escaping () -> Date = Date.init,
        onEntitlement: @escaping (AdEntitlementState) -> Void = { _ in }
    ) {
        self.store = store
        self.api = api
        self.sessionProvider = sessionProvider
        self.ownerIdProvider = ownerIdProvider
        self.initialEnvironment = initialEnvironment
        self.now = now
        self.readEnvironment = initialEnvironment
        self.accountsEnabled = accountsEnabled
        self.cacheStore = cacheStore
        self.onEntitlement = onEntitlement
    }

    static func forApp(account: NativeAccountCoordinator, consent: AdsConsentManager = .shared) -> ProSubscriptionCoordinator {
        #if DEBUG
        if ProSubscriptionUITestSupport.active {
            return ProSubscriptionUITestSupport.make(account: account, consent: consent)
        }
        #endif
        return ProSubscriptionCoordinator(
            store: StoreKitProStoreClient(productId: ProProductConfiguration.productId),
            sessionProvider: { [account] in try? await account.access() },
            ownerIdProvider: { [account] in account.enabled ? account.stateUserId : nil },
            accountsEnabled: account.enabled,
            onEntitlement: { [consent] state in consent.updateEntitlement(state) }
        )
    }

    /// Fetch the store product, restore the bounded offline cache, refresh a
    /// signed-in account, and listen for store transaction updates. Silent:
    /// an active cook is never interrupted by an entitlement transition.
    func start() {
        guard !started else { return }
        started = true
        Task { await loadProduct() }
        Task { await refresh() }
        updateTask = Task { [weak self] in
            guard let self else { return }
            for await transaction in self.store.updates() {
                await self.verifyStoreTransaction(transaction, finishAfterResponse: true)
            }
        }
    }

    func stop() {
        updateTask?.cancel()
        updateTask = nil
    }

    // MARK: - Paywall presentation (explicit taps only)

    /// Opens the Pro gate for one entry point. Live and Finish flows never
    /// call this; no purchase popup interrupts them. Ever.
    func openPro(_ entry: ProPlacement) {
        notice = nil
        if purchaseState == .purchasing || purchaseState == .verifying { return }
        presentedEntry = entry
        ProPaywallPresentation.record(entry)
        Task { await refresh() }
    }

    func closePaywall() {
        presentedEntry = nil
        if case .failed = purchaseState { purchaseState = .idle }
        notice = nil
    }

    // MARK: - Product

    func loadProduct() async {
        guard productState != .loading else { return }
        guard !store.productId.isEmpty else {
            productState = .unavailable
            return
        }
        productState = .loading
        do {
            productState = .ready(try await store.fetchProduct())
        } catch is CancellationError {
            productState = .idle
        } catch {
            productState = .unavailable
        }
    }

    // MARK: - Entitlement refresh

    /// Local account identity survives an offline access-token refresh failure.
    /// An account change clears both its entitlement and transaction reference.
    private func synchronizeOwner() -> String? {
        let owner = ownerIdProvider()
        guard let owner else {
            currentOwner = nil
            readEnvironment = initialEnvironment
            lastTransaction = nil
            cache = nil
            cacheRevision += 1
            signedIn = false
            purchaseState = .idle
            notice = nil
            cacheStore.clear()
            onEntitlement(.unknown)
            return nil
        }
        if owner != currentOwner {
            if currentOwner != nil { cacheStore.clear() }
            currentOwner = owner
            readEnvironment = initialEnvironment
            lastTransaction = nil
            cache = nil
            cacheRevision += 1
            purchaseState = .idle
            notice = nil
            onEntitlement(.unknown)
            if let saved = cacheStore.load(ownerId: owner) {
                readEnvironment = SubscriptionEnvironment(rawValue: saved.environment) ?? initialEnvironment
                if saved.offlineUsable(now: now()) {
                    cache = saved
                    onEntitlement(saved.entitlement(now: now()))
                }
            }
        }
        signedIn = true
        return owner
    }

    private func keepBoundedOfflineCache() {
        if cache?.offlineUsable(now: now()) != true {
            cache = nil
            onEntitlement(.unknown)
        }
    }

    private func account() async -> Account? {
        guard let owner = synchronizeOwner() else { return nil }
        let token = await sessionProvider()
        guard synchronizeOwner() == owner else { return nil }
        guard let token else { keepBoundedOfflineCache(); return nil }
        return Account(ownerId: owner, token: token)
    }

    private func isCurrent(_ account: Account) -> Bool { synchronizeOwner() == account.ownerId }

    /// Silent refresh reads the current account and selected store environment.
    func refresh() async {
        guard let account = await account() else { return }
        let environment = readEnvironment
        let revision = cacheRevision
        do {
            let data = try await api.call(environment.entitlementPath, body: nil, access: account.token)
            guard isCurrent(account), cacheRevision == revision else { return }
            let cache = try SubscriptionEntitlementCache.decode(data)
            guard cache.environment == environment.rawValue else { throw NativeSubscriptionError.invalid }
            apply(cache: cache, ownerId: account.ownerId)
        } catch is CancellationError {
        } catch {
            if isCurrent(account), cacheRevision == revision { keepBoundedOfflineCache() }
        }
    }

    /// Authoritative store re-verification with a converged cache.
    func reconcile() async {
        guard !busy, let account = await account() else { return }
        let environment = readEnvironment
        let revision = cacheRevision
        purchaseState = .verifying
        defer { if currentOwner == account.ownerId, purchaseState == .verifying { purchaseState = .idle } }
        do {
            let data = try await api.call("/v1/me/entitlement/reconcile", body: ["environment": environment.rawValue], access: account.token)
            guard isCurrent(account), cacheRevision == revision else { return }
            let cache = try SubscriptionEntitlementCache.decode(data)
            guard cache.environment == environment.rawValue else { throw NativeSubscriptionError.invalid }
            apply(cache: cache, ownerId: account.ownerId)
            if entitlement == .entitled {
                notice = "PitTech Pro is active on this account."
            }
        } catch is CancellationError {
        } catch {
            guard isCurrent(account), cacheRevision == revision else { return }
            keepBoundedOfflineCache()
            purchaseState = .failed(message: "Couldn't check your subscription status. Your local cookbook is unchanged.")
        }
    }

    private func apply(cache: SubscriptionEntitlementCache, ownerId: String) {
        precondition(ownerId == currentOwner)
        readEnvironment = SubscriptionEnvironment(rawValue: cache.environment) ?? initialEnvironment
        cacheRevision += 1
        self.cache = cache
        cacheStore.save(cache, ownerId: ownerId)
        onEntitlement(cache.entitlement(now: now()))
        if cache.entitlement(now: now()) == .entitled, case .pendingStore = purchaseState {
            purchaseState = .idle
        }
    }

    // MARK: - Purchase

    /// StoreKit purchase bound to the verified PitTech account. The store
    /// transaction is sent to the backend for server-side verification; the
    /// client never sets entitlement from the local callback alone.
    func subscribe() async {
        guard !busy else { return }
        notice = nil
        let account = await account()
        guard entitlement != .entitled else { return }
        guard let account else {
            purchaseState = .failed(message: accountsEnabled
                ? "Sign in to subscribe with your PitTech account."
                : "Accounts arrive with community recipes and private sync. Subscribing needs a PitTech account first.")
            return
        }
        guard case .ready(let product) = productState, product.isMonthly else {
            purchaseState = .failed(message: ProStoreError.unavailable.localizedDescription)
            return
        }
        purchaseState = .purchasing
        let outcome: ProPurchaseOutcome
        do {
            outcome = try await store.purchase()
        } catch is CancellationError {
            if isCurrent(account) { purchaseState = .idle }
            return
        } catch let error as ProStoreError {
            guard isCurrent(account) else { return }
            purchaseState = .failed(message: error.localizedDescription)
            return
        } catch {
            guard isCurrent(account) else { return }
            purchaseState = .failed(message: ProStoreError.network.localizedDescription)
            return
        }
        guard isCurrent(account) else { return }
        switch outcome {
        case .cancelled:
            purchaseState = .idle
            notice = "Purchase cancelled. Nothing was charged."
        case .pending:
            purchaseState = .pendingStore
            notice = "Your purchase is pending with the App Store. Pro unlocks after the store confirms it; nothing is charged by PitTech."
        case .purchased(let transaction):
            await verifyStoreTransaction(transaction, finishAfterResponse: true, expectedOwner: account.ownerId)
        }
    }

    /// Restore re-sends every current store entitlement for server
    /// verification and re-establishes the same account entitlement.
    func restore() async {
        guard !busy else { return }
        notice = nil
        if case .failed = purchaseState { purchaseState = .idle }
        guard let account = await account() else {
            purchaseState = .failed(message: accountsEnabled
                ? "Sign in to restore purchases on your PitTech account."
                : "Accounts arrive with community recipes and private sync. Restoring needs a PitTech account first.")
            return
        }
        purchaseState = .verifying
        let transactions = await store.currentEntitlements()
        guard isCurrent(account) else { return }
        guard !transactions.isEmpty else {
            purchaseState = .idle
            notice = "No App Store purchases were found for PitTech Pro."
            return
        }
        do {
            let proofs = transactions.prefix(10).map { ["store": "apple", "signedTransaction": $0.signedTransaction] }
            let data = try await api.call("/v1/me/purchases/restore", body: ["proofs": proofs], access: account.token)
            guard isCurrent(account) else { return }
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let outcomes = json["outcomes"] as? [[String: Any]],
                  let last = outcomes.last else { throw NativeSubscriptionError.invalid }
            let outcome = try NativeSubscriptionOutcome.decodeObject(last)
            apply(cache: outcome.cache, ownerId: account.ownerId)
            remember(transactionOf: outcome)
            purchaseState = .idle
            if outcome.conflictRecovery != nil {
                purchaseState = .conflict(recovery: outcome.conflictRecovery ?? "")
            } else if entitlement == .entitled {
                notice = "PitTech Pro is active on this account."
            } else {
                notice = "Those purchases don't cover PitTech Pro on this account."
            }
            for transaction in transactions {
                await store.finish(transaction.transactionId)
            }
        } catch is CancellationError {
            if isCurrent(account) { purchaseState = .idle }
        } catch {
            guard isCurrent(account) else { return }
            purchaseState = .failed(message: "Restore couldn't reach PitTech. Retry the same request when you're online.")
        }
    }

    private func verifyStoreTransaction(_ transaction: ProStoreTransaction, finishAfterResponse: Bool, expectedOwner: String? = nil) async {
        guard let account = await account(), expectedOwner == nil || account.ownerId == expectedOwner else { return }
        purchaseState = .verifying
        do {
            let data = try await api.call(
                "/v1/me/purchases/verify",
                body: ["store": "apple", "signedTransaction": transaction.signedTransaction],
                access: account.token
            )
            guard isCurrent(account) else { return }
            let outcome = try NativeSubscriptionOutcome.decode(data)
            apply(cache: outcome.cache, ownerId: account.ownerId)
            remember(transactionOf: outcome)
            if let recovery = outcome.conflictRecovery {
                purchaseState = .conflict(recovery: recovery)
            } else {
                purchaseState = .idle
                if entitlement == .entitled {
                    notice = "PitTech Pro is active on this account."
                }
            }
            if finishAfterResponse {
                await store.finish(transaction.transactionId)
            }
        } catch is CancellationError {
            if isCurrent(account) { purchaseState = .idle }
        } catch {
            guard isCurrent(account) else { return }
            // No server response: leave the transaction unfinished so the
            // store redelivers it, and never unlock from the local callback.
            purchaseState = .failed(message: "PitTech couldn't verify that purchase yet. It will retry automatically; your local cookbook is unchanged.")
        }
    }

    private func remember(transactionOf outcome: NativeSubscriptionOutcome) {
        if let store = outcome.store, let environment = outcome.environment, let key = outcome.transactionKey {
            lastTransaction = (store: store, environment: environment, key: key)
        }
    }

    /// The signed-in holder releases its store binding (for example before
    /// handing the purchase to another account). The holder's live
    /// entitlement ends; the release itself is verified server-side.
    func releasePurchase() async {
        guard !busy else { return }
        notice = nil
        guard let account = await account() else {
            purchaseState = .failed(message: "Sign in to manage this purchase.")
            return
        }
        guard let ref = lastTransaction else {
            purchaseState = .failed(message: "There's no verified purchase on this account to release.")
            return
        }
        purchaseState = .verifying
        do {
            _ = try await api.call("/v1/me/purchases/release", body: ["store": ref.store, "environment": ref.environment, "transactionKey": ref.key], access: account.token)
            guard isCurrent(account) else { return }
            lastTransaction = nil
            cache = nil
            cacheRevision += 1
            cacheStore.clear()
            onEntitlement(.unknown)
            purchaseState = .idle
            notice = "That purchase was released from this account."
            await refresh()
        } catch is CancellationError {
            if isCurrent(account) { purchaseState = .idle }
        } catch {
            guard isCurrent(account) else { return }
            purchaseState = .failed(message: "That purchase couldn't be released. Retry when you're online.")
        }
    }
}

// MARK: - Bounded offline cache

/// Persists the last verified cache record so a recently verified subscriber
/// keeps Pro offline for the server's bounded window. Never grants anything
/// the server did not verify.
struct ProCacheStore {
    private let key = NativeOnlineConfiguration.current.scopedName("pittech-subscription-cache-v2")
    private let legacyKey = NativeOnlineConfiguration.current.scopedName("pittech-subscription-cache-v1")
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load(ownerId: String) -> SubscriptionEntitlementCache? {
        defaults.removeObject(forKey: legacyKey)
        guard let data = defaults.data(forKey: key) else { return nil }
        guard let envelope = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              envelope["ownerId"] as? String == ownerId,
              let record = envelope["cache"],
              let bytes = try? JSONSerialization.data(withJSONObject: record),
              let cache = try? SubscriptionEntitlementCache.decode(bytes) else { clear(); return nil }
        return cache
    }

    func save(_ cache: SubscriptionEntitlementCache, ownerId: String) {
        guard !ownerId.isEmpty, let record = try? JSONSerialization.jsonObject(with: cache.encoded()),
              let data = try? JSONSerialization.data(withJSONObject: ["ownerId": ownerId, "cache": record]) else { return }
        defaults.removeObject(forKey: legacyKey)
        defaults.set(data, forKey: key)
    }

    func clear() {
        defaults.removeObject(forKey: key)
        defaults.removeObject(forKey: legacyKey)
    }
}

// MARK: - Presentation recorder (test instrumentation)

/// Records every paywall presentation so tests prove the paywall appears at
/// Pro entry points and never from Live or Finish.
final class ProPaywallPresentation: Sendable {
    private static let lock = NSLock()
    private static var entries: [ProPlacement] = []

    static func record(_ entry: ProPlacement) {
        lock.lock()
        defer { lock.unlock() }
        entries.append(entry)
    }

    static var recorded: [ProPlacement] {
        lock.lock()
        defer { lock.unlock() }
        return entries
    }

    static func reset() {
        lock.lock()
        defer { lock.unlock() }
        entries = []
    }
}
