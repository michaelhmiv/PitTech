import Foundation
import StoreKit

// MARK: - Store client (StoreKit 2 behind a testable protocol)

enum ProStoreError: LocalizedError {
    case unavailable
    case unverified
    case network

    var errorDescription: String? {
        switch self {
        case .unavailable: return "PitTech Pro isn't available on this build yet."
        case .unverified: return "The store could not verify that purchase. Nothing was charged by PitTech."
        case .network: return "Couldn't reach the App Store. Try again when you're online."
        }
    }
}

enum ProPurchaseOutcome: Sendable, Equatable {
    case purchased(ProStoreTransaction)
    case pending
    case cancelled
}

/// The one monthly product behind a protocol so tests and UI automation use a
/// stub with localized product data instead of touching StoreKit.
protocol ProStoreClient: Sendable {
    var productId: String { get }
    func fetchProduct() async throws -> ProStoreProduct
    func purchase() async throws -> ProPurchaseOutcome
    func currentEntitlements() async -> [ProStoreTransaction]
    func finish(_ transactionId: String) async
    func updates() -> AsyncStream<ProStoreTransaction>
}

struct StoreKitProStoreClient: ProStoreClient {
    let productId: String

    func fetchProduct() async throws -> ProStoreProduct {
        guard !productId.isEmpty else { throw ProStoreError.unavailable }
        do {
            let products = try await Product.products(for: [productId])
            guard let product = products.first, product.type == .autoRenewable else { throw ProStoreError.unavailable }
            let period = product.subscription?.subscriptionPeriod
            return ProStoreProduct(productId: productId, displayPrice: product.displayPrice, isMonthly: period?.unit == .month && period?.value == 1)
        } catch let error as ProStoreError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ProStoreError.network
        }
    }

    func purchase() async throws -> ProPurchaseOutcome {
        guard !productId.isEmpty else { throw ProStoreError.unavailable }
        let products: [Product]
        do {
            products = try await Product.products(for: [productId])
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ProStoreError.network
        }
        guard let product = products.first, product.type == .autoRenewable else { throw ProStoreError.unavailable }
        let result: Product.PurchaseResult
        do {
            result = try await product.purchase()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ProStoreError.network
        }
        switch result {
        case .success(let verification):
            switch verification {
            case .verified(let transaction):
                return .purchased(ProStoreTransaction(transactionId: String(transaction.id), signedTransaction: verification.jwsRepresentation))
            case .unverified:
                throw ProStoreError.unverified
            }
        case .userCancelled:
            return .cancelled
        case .pending:
            return .pending
        @unknown default:
            return .cancelled
        }
    }

    func currentEntitlements() async -> [ProStoreTransaction] {
        guard !productId.isEmpty else { return [] }
        var out: [ProStoreTransaction] = []
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result, transaction.productID == productId {
                out.append(ProStoreTransaction(transactionId: String(transaction.id), signedTransaction: result.jwsRepresentation))
            }
        }
        return out
    }

    func finish(_ transactionId: String) async {
        for await result in Transaction.unfinished {
            if case .verified(let transaction) = result, String(transaction.id) == transactionId {
                await transaction.finish()
                return
            }
        }
    }

    func updates() -> AsyncStream<ProStoreTransaction> {
        let productId = self.productId
        return AsyncStream { continuation in
            let task = Task {
                for await result in Transaction.updates {
                    if case .verified(let transaction) = result, transaction.productID == productId {
                        continuation.yield(ProStoreTransaction(transactionId: String(transaction.id), signedTransaction: result.jwsRepresentation))
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

// MARK: - Backend verification transport

enum NativeSubscriptionError: LocalizedError {
    case invalid
    case unavailable
    case network
    case status(Int)

    var errorDescription: String? {
        switch self {
        case .invalid: return "PitTech couldn't verify that purchase. Your local cookbook is unchanged."
        case .unavailable: return "Subscriptions are not available in this build. Your local cookbook works without one."
        case .network: return "Couldn't reach PitTech. Retry the same request when you're online."
        case .status(401): return "Sign in again to verify this purchase. Your local cookbook is unchanged."
        case .status(409): return "This purchase is already bound to a different PitTech account."
        default: return "PitTech couldn't complete verification. Retry later."
        }
    }
}

protocol NativeSubscriptionAPI {
    func call(_ path: String, body: [String: Any]?, access: String?) async throws -> Data
}

struct NativeSubscriptionClient: NativeSubscriptionAPI {
    var configuration: URLSessionConfiguration?

    func call(_ path: String, body: [String: Any]? = nil, access: String? = nil) async throws -> Data {
        let read = path == "/v1/me/entitlement" || path == "/v1/me/entitlement?environment=sandbox"
        guard read || ["/v1/me/entitlement/reconcile", "/v1/me/purchases/verify", "/v1/me/purchases/restore", "/v1/me/purchases/release"].contains(path) else { throw NativeSubscriptionError.invalid }
        var request = URLRequest(url: URL(string: "\(NativeOnlineConfiguration.current.apiOrigin)" + path)!)
        request.httpMethod = read ? "GET" : "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if !read {
            request.httpBody = try JSONSerialization.data(withJSONObject: body ?? [:])
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if body != nil {
            throw NativeSubscriptionError.invalid
        }
        guard (request.httpBody?.count ?? 0) <= 65536 else { throw NativeSubscriptionError.invalid }
        if let access {
            guard NativeAccountProtocol.credential(access) else { throw NativeSubscriptionError.invalid }
            request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        } else {
            throw NativeSubscriptionError.invalid
        }
        do {
            let result = try await NativeRelayTransport(configuration: configuration, responseError: { NativeSubscriptionError.status($0) }, maximumResponseBytes: 65536).run(request)
            guard result.data.count <= 65536 else { throw NativeSubscriptionError.invalid }
            return result.data
        } catch let error as NativeSubscriptionError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw NativeSubscriptionError.network
        }
    }
}

// MARK: - Verified outcomes (server-owned, never client-derived)

/// One verified purchase outcome. `cache` is the converged
/// `entitlement-cache-v1` record; `conflictRecovery` carries the server's
/// binding-conflict recovery text when the purchase belongs elsewhere.
struct NativeSubscriptionOutcome {
    let cache: SubscriptionEntitlementCache
    let applied: Bool
    let note: String
    let store: String?
    let environment: String?
    let transactionKey: String?
    let conflictRecovery: String?

    static func decode(_ data: Data) throws -> NativeSubscriptionOutcome {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw NativeSubscriptionError.invalid }
        return try decodeObject(json)
    }

    static func decodeObject(_ json: [String: Any]) throws -> NativeSubscriptionOutcome {
        guard let cacheJson = json["cache"] as? [String: Any],
              let cacheData = try? JSONSerialization.data(withJSONObject: cacheJson),
              let cache = try? SubscriptionEntitlementCache.decode(cacheData),
              let applied = json["applied"] as? Bool,
              let note = json["note"] as? String else { throw NativeSubscriptionError.invalid }
        let transaction = json["transaction"] as? [String: Any]
        let conflict = json["conflict"] as? [String: Any]
        return NativeSubscriptionOutcome(
            cache: cache,
            applied: applied,
            note: note,
            store: transaction?["store"] as? String,
            environment: transaction?["environment"] as? String,
            transactionKey: transaction?["transactionKey"] as? String,
            conflictRecovery: conflict?["recovery"] as? String
        )
    }
}
