import Foundation

/// Versioned entitlement cache contract shared with the server (`entitlement-cache-v1`).
enum SubscriptionEntitlementContract {
    static let cacheVersion = "entitlement-cache-v1"
    static let contractVersion = "store-verification-v1"
    static let offlineWindow: TimeInterval = 7 * 24 * 3600
}

/// Verified entitlement behind the ad-request gate. `unknown` means the server
/// has not verified this account yet; it is distinct from `notEntitled`.
enum AdEntitlementState: String, Sendable {
    case unknown
    case notEntitled
    case entitled
}

private let adFreeServerStates: Set<String> = ["active", "grace", "retry", "paused"]

/// Map one versioned server cache record to the gate input. Unverified records,
/// version mismatches and unrecognized states all map to `unknown` so the gate
/// defers to the previously-entitled flag instead of guessing.
func adEntitlementFromServerCache(verified: Bool, state: String, verifiedUntil: Date?, cacheVersion: String = SubscriptionEntitlementContract.cacheVersion, contractVersion: String = SubscriptionEntitlementContract.contractVersion, now: Date = .now) -> AdEntitlementState {
    guard verified, cacheVersion == SubscriptionEntitlementContract.cacheVersion, contractVersion == SubscriptionEntitlementContract.contractVersion else { return .unknown }
    if adFreeServerStates.contains(state) { return .entitled }
    if state == "cancelled", let paidThrough = verifiedUntil, paidThrough > now { return .entitled }
    if ["none", "pending", "expired", "cancelled", "refunded", "revoked"].contains(state) { return .notEntitled }
    return .unknown
}

/// The ad-request gate. Entitled users trigger zero ad requests. While
/// entitlement is unverified, the gate holds requests for a previously entitled
/// account (fail closed) rather than flashing ads at a subscriber.
func shouldRequestAds(entitlement: AdEntitlementState, previouslyEntitled: Bool) -> Bool {
    switch entitlement {
    case .entitled: return false
    case .notEntitled: return true
    case .unknown: return !previouslyEntitled
    }
}

/// Versioned entitlement cache record served by `/v1/me/entitlement`.
struct SubscriptionEntitlementCache: Codable, Equatable, Sendable {
    let version: String
    let environment: String
    let verified: Bool
    let state: String
    let verifiedUntil: Date?
    let lastVerifiedAt: Date?
    let offlineValidUntil: Date?
    let contractVersion: String

    static func decode(_ data: Data) throws -> SubscriptionEntitlementCache {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom({ decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            let fractional = ISO8601DateFormatter()
            fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = fractional.date(from: string) { return date }
            let plain = ISO8601DateFormatter()
            plain.formatOptions = [.withInternetDateTime]
            if let date = plain.date(from: string) { return date }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid ISO-8601 date")
        })
        return try decoder.decode(SubscriptionEntitlementCache.self, from: data)
    }

    func entitlement(now: Date = .now) -> AdEntitlementState {
        adEntitlementFromServerCache(verified: verified, state: state, verifiedUntil: verifiedUntil, cacheVersion: version, contractVersion: contractVersion, now: now)
    }

    /// A recently verified active/grace record stays usable offline for a
    /// bounded window; everything else requires fresh verification.
    func offlineUsable(now: Date = .now) -> Bool {
        guard verified, state == "active" || state == "grace" else { return false }
        guard let lastVerifiedAt, let offlineValidUntil else { return false }
        guard version == SubscriptionEntitlementContract.cacheVersion, contractVersion == SubscriptionEntitlementContract.contractVersion else { return false }
        return now >= lastVerifiedAt && now <= offlineValidUntil
    }
}

/// Durable previously-entitled flag. A subscriber who is merely unverified
/// (offline, fresh install, slow backend) must not see ads flash.
protocol PreviouslyEntitledStore {
    var previouslyEntitled: Bool { get }
    func markEntitled()
}

struct UserDefaultsPreviouslyEntitledStore: PreviouslyEntitledStore {
    private let key = "pittech-previously-entitled"
    var previouslyEntitled: Bool { UserDefaults.standard.bool(forKey: key) }
    func markEntitled() { UserDefaults.standard.set(true, forKey: key) }
}

/// CI instrumentation: records every ad-request attempt the gate allows so the
/// suite can assert zero requests under entitlement.
@MainActor
final class AdRequestMonitor {
    static let shared = AdRequestMonitor()
    private(set) var attempts: [Date] = []

    func recordAttempt(at date: Date = .now) {
        attempts.append(date)
    }

    func reset() {
        attempts = []
    }

    /// Gate a single ad request: record the attempt when allowed, stay silent
    /// when held. Call sites check this immediately before every ad SDK request.
    func shouldAttempt(entitlement: AdEntitlementState, previouslyEntitled: Bool) -> Bool {
        guard shouldRequestAds(entitlement: entitlement, previouslyEntitled: previouslyEntitled) else { return false }
        recordAttempt()
        return true
    }
}
