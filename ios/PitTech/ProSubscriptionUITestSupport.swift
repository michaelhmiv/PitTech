#if DEBUG
import Foundation

/// UI automation only: a stub store product with localized product data and a
/// scripted verification backend. Never contacts the App Store or a server.
///
/// Launch arguments:
/// - `--pro-ui-test` enables stub mode (implies a signed-in account unless
///   `--pro-signed-out` is present).
/// - `--pro-entitled` / `--pro-expired` script the verified cache state;
///   default is unknown/loading with no paywall flash.
/// - `--pro-no-product` makes the product fetch fail like an unconfigured build.
@MainActor enum ProSubscriptionUITestSupport {
    static var active: Bool { CommandLine.arguments.contains("--pro-ui-test") }

    static let stubProduct = ProStoreProduct(productId: "pittech.pro.monthly.stub", displayPrice: "$3.00", isMonthly: true)

    static func cache(state: String, verified: Bool = true) throws -> SubscriptionEntitlementCache {
        try SubscriptionEntitlementCache.decode(Data("""
        {"version":"entitlement-cache-v1","environment":"production","verified":\(verified ? "true" : "false"),"state":"\(state)","verifiedUntil":"2026-11-17T12:00:00.000Z","lastVerifiedAt":"2026-10-18T12:00:00.000Z","offlineValidUntil":"2026-10-25T12:00:00.000Z","contractVersion":"store-verification-v1"}
        """.utf8))
    }

    private struct StubStore: ProStoreClient {
        let productId: String
        func fetchProduct() async throws -> ProStoreProduct {
            if CommandLine.arguments.contains("--pro-no-product") { throw ProStoreError.unavailable }
            return ProSubscriptionUITestSupport.stubProduct
        }
        func purchase() async throws -> ProPurchaseOutcome {
            .purchased(ProStoreTransaction(transactionId: "stub-transaction", signedTransaction: "stub-jws"))
        }
        func currentEntitlements() async -> [ProStoreTransaction] {
            [ProStoreTransaction(transactionId: "stub-transaction", signedTransaction: "stub-jws")]
        }
        func finish(_ transactionId: String) async {}
        func updates() -> AsyncStream<ProStoreTransaction> {
            AsyncStream { $0.finish() }
        }
    }

    private struct StubAPI: NativeSubscriptionAPI {
        let state: String
        func call(_ path: String, body: [String: Any]?, access: String?) async throws -> Data {
            guard access != nil else { throw NativeSubscriptionError.status(401) }
            switch path {
            case "/v1/me/entitlement":
                return try ProSubscriptionUITestSupport.cache(state: state, verified: state != "unknown").encoded()
            case "/v1/me/entitlement/reconcile":
                return try ProSubscriptionUITestSupport.cache(state: state, verified: state != "unknown").encoded()
            case "/v1/me/purchases/verify", "/v1/me/purchases/restore":
                let cache = try ProSubscriptionUITestSupport.cache(state: "active").encoded()
                let outcome: [String: Any] = [
                    "cache": try JSONSerialization.jsonObject(with: cache),
                    "applied": true,
                    "note": "stub-verified",
                    "transaction": ["store": "apple", "environment": "production", "transactionKey": "stub-transaction", "evidenceRef": String(repeating: "a", count: 64)],
                ]
                if path.hasSuffix("restore") {
                    return try JSONSerialization.data(withJSONObject: ["outcomes": [outcome]])
                }
                return try JSONSerialization.data(withJSONObject: outcome)
            case "/v1/me/purchases/release":
                return try JSONSerialization.data(withJSONObject: ["released": true])
            default:
                throw NativeSubscriptionError.invalid
            }
        }
    }

    static func make(account: NativeAccountCoordinator, consent: AdsConsentManager) -> ProSubscriptionCoordinator {
        let state: String
        if CommandLine.arguments.contains("--pro-entitled") { state = "active" }
        else if CommandLine.arguments.contains("--pro-expired") { state = "expired" }
        else if CommandLine.arguments.contains("--pro-not-entitled") { state = "none" }
        else { state = "unknown" }
        let signedIn = !CommandLine.arguments.contains("--pro-signed-out")
        return ProSubscriptionCoordinator(
            store: StubStore(productId: stubProduct.productId),
            api: StubAPI(state: state),
            sessionProvider: { signedIn ? String(repeating: "A", count: 43) : nil },
            accountsEnabled: true,
            onEntitlement: { [consent] in consent.updateEntitlement($0) }
        )
    }
}
#endif
