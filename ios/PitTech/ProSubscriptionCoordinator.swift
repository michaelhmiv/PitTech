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

    var entitlement: AdEntitlementState { cache?.entitlement() ?? .unknown }
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
        accountsEnabled: Bool,
        cacheStore: ProCacheStore = ProCacheStore(),
        onEntitlement: @escaping (AdEntitlementState) -> Void = { _ in }
    ) {
        self.store = store
        self.api = api
        self.sessionProvider = sessionProvider
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
        if let saved = cacheStore.load(), saved.offlineUsable() {
            cache = saved
            onEntitlement(saved.entitlement())
        }
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

    /// Silent refresh. A sign-out clears the cache so one account's
    /// entitlement never leaks into another session.
    func refresh() async {
        guard let token = await sessionProvider() else {
            signedIn = false
            cache = nil
            cacheStore.clear()
            onEntitlement(.unknown)
            return
        }
        signedIn = true
        do {
            let data = try await api.call("/v1/me/entitlement", body: nil, access: token)
            apply(cache: try SubscriptionEntitlementCache.decode(data))
        } catch is CancellationError {
        } catch {
            // Unverified keeps the provisional offline cache (or unknown);
            // the UI stays in its neutral loading state, never flashing the
            // paywall or ads at a previously entitled user.
        }
    }

    /// Authoritative store re-verification with a converged cache.
    func reconcile() async {
        guard let token = await sessionProvider() else {
            signedIn = false
            return
        }
        signedIn = true
        purchaseState = .verifying
        defer { if purchaseState == .verifying { purchaseState = .idle } }
        do {
            let data = try await api.call("/v1/me/entitlement/reconcile", body: [:], access: token)
            apply(cache: try SubscriptionEntitlementCache.decode(data))
            if entitlement == .entitled {
                notice = "PitTech Pro is active on this account."
            }
        } catch is CancellationError {
        } catch {
            purchaseState = .failed(message: "Couldn't check your subscription status. Your local cookbook is unchanged.")
        }
    }

    private func apply(cache: SubscriptionEntitlementCache) {
        self.cache = cache
        cacheStore.save(cache)
        onEntitlement(cache.entitlement())
        if cache.entitlement() == .entitled, case .pendingStore = purchaseState {
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
        guard entitlement != .entitled else { return }
        guard signedIn, await sessionProvider() != nil else {
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
            purchaseState = .idle
            return
        } catch let error as ProStoreError {
            purchaseState = .failed(message: error.localizedDescription)
            return
        } catch {
            purchaseState = .failed(message: ProStoreError.network.localizedDescription)
            return
        }
        switch outcome {
        case .cancelled:
            purchaseState = .idle
            notice = "Purchase cancelled. Nothing was charged."
        case .pending:
            purchaseState = .pendingStore
            notice = "Your purchase is pending with the App Store. Pro unlocks after the store confirms it; nothing is charged by PitTech."
        case .purchased(let transaction):
            await verifyStoreTransaction(transaction, finishAfterResponse: true)
        }
    }

    /// Restore re-sends every current store entitlement for server
    /// verification and re-establishes the same account entitlement.
    func restore() async {
        guard !busy else { return }
        notice = nil
        if case .failed = purchaseState { purchaseState = .idle }
        guard signedIn, let token = await sessionProvider() else {
            purchaseState = .failed(message: accountsEnabled
                ? "Sign in to restore purchases on your PitTech account."
                : "Accounts arrive with community recipes and private sync. Restoring needs a PitTech account first.")
            return
        }
        purchaseState = .verifying
        let transactions = await store.currentEntitlements()
        guard !transactions.isEmpty else {
            purchaseState = .idle
            notice = "No App Store purchases were found for PitTech Pro."
            return
        }
        do {
            let proofs = transactions.prefix(10).map { ["store": "apple", "signedTransaction": $0.signedTransaction] }
            let data = try await api.call("/v1/me/purchases/restore", body: ["proofs": proofs], access: token)
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let outcomes = json["outcomes"] as? [[String: Any]],
                  let last = outcomes.last else { throw NativeSubscriptionError.invalid }
            let outcome = try NativeSubscriptionOutcome.decodeObject(last)
            apply(cache: outcome.cache)
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
            purchaseState = .idle
        } catch {
            purchaseState = .failed(message: "Restore couldn't reach PitTech. Retry the same request when you're online.")
        }
    }

    private func verifyStoreTransaction(_ transaction: ProStoreTransaction, finishAfterResponse: Bool) async {
        guard let token = await sessionProvider() else {
            signedIn = false
            return
        }
        signedIn = true
        purchaseState = .verifying
        do {
            let data = try await api.call(
                "/v1/me/purchases/verify",
                body: ["store": "apple", "signedTransaction": transaction.signedTransaction],
                access: token
            )
            let outcome = try NativeSubscriptionOutcome.decode(data)
            apply(cache: outcome.cache)
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
            purchaseState = .idle
        } catch {
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
        guard signedIn, let token = await sessionProvider() else {
            purchaseState = .failed(message: "Sign in to manage this purchase.")
            return
        }
        guard let ref = lastTransaction else {
            purchaseState = .failed(message: "There's no verified purchase on this account to release.")
            return
        }
        purchaseState = .verifying
        do {
            _ = try await api.call("/v1/me/purchases/release", body: ["store": ref.store, "environment": ref.environment, "transactionKey": ref.key], access: token)
            purchaseState = .idle
            notice = "That purchase was released from this account."
            await refresh()
        } catch is CancellationError {
            purchaseState = .idle
        } catch {
            purchaseState = .failed(message: "That purchase couldn't be released. Retry when you're online.")
        }
    }
}

// MARK: - Bounded offline cache

/// Persists the last verified cache record so a recently verified subscriber
/// keeps Pro offline for the server's bounded window. Never grants anything
/// the server did not verify.
struct ProCacheStore {
    private let key = "pittech-subscription-cache-v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> SubscriptionEntitlementCache? {
        guard let data = defaults.data(forKey: key),
              let cache = try? SubscriptionEntitlementCache.decode(data) else { return nil }
        return cache
    }

    func save(_ cache: SubscriptionEntitlementCache) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(cache) else { return }
        defaults.set(data, forKey: key)
    }

    func clear() {
        defaults.removeObject(forKey: key)
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
