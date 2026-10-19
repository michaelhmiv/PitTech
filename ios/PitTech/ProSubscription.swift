import Foundation

// MARK: - Product configuration (owner-gated)

// The one monthly PitTech Pro product ID comes from the approved build
// configuration (`PITTECH_PRO_PRODUCT_ID`, mirrored into Info.plist like the
// AdMob unit ID). It is empty until the owner creates the store product; an
// empty or missing value is a graceful not-configured state, never a crash
// and never a fake price.
enum ProProductConfiguration {
    static let productId: String =
        (Bundle.main.object(forInfoDictionaryKey: "PITTECHProProductID") as? String)?
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

    static var isConfigured: Bool { !productId.isEmpty }
}

// MARK: - Store-facing value types (StoreKit stays behind the client protocol)

// The slice-15 free-feature inventory is the do-not-touch list: every gate
// destination below only adds paid views or loading states. It never removes
// or paywalls an existing free tool.
enum ProPlacement: String, Sendable, CaseIterable {
    case analysis
    case curves
    case benchmarks
    case themes
    case more

    var title: String {
        switch self {
        case .analysis: return "Personal analysis"
        case .curves: return "Advanced curves"
        case .benchmarks: return "Community benchmarks"
        case .themes: return "Pro themes"
        case .more: return "PitTech Pro"
        }
    }

    var preview: String {
        switch self {
        case .analysis:
            return "How your pork cooks compare, which recorded attempt you rated best, and how timing shifts across your recorded cuts and weights."
        case .curves:
            return "Align recorded cooks at Food on and Wrap, and choose which measured sources to compare."
        case .benchmarks:
            return "Typical heating times and temperature bands from eligible published cooks."
        case .themes:
            return "Pro card themes, chart palettes, and app icons. Preview before applying."
        case .more:
            return "An ad-free PitTech with deeper personal analysis, advanced curves, and Pro themes."
        }
    }
}

/// Localized monthly product data from the store. The paywall renders
/// `displayPrice` verbatim; no price string is hardcoded anywhere.
struct ProStoreProduct: Sendable, Equatable {
    let productId: String
    let displayPrice: String
    let isMonthly: Bool
}

struct ProStoreTransaction: Sendable, Equatable {
    let transactionId: String
    let signedTransaction: String
}

/// Precise monthly billing text from actual localized store product data.
/// Returns nil when the product is not configured, so the paywall shows its
/// honest unavailable state instead of inventing a price.
func proBillingText(product: ProStoreProduct?) -> String? {
    guard let product, !product.productId.isEmpty, product.isMonthly, !product.displayPrice.isEmpty else { return nil }
    return "\(product.displayPrice) per month, billed monthly. Cancel anytime in the App Store."
}

func proSubscribeAccessibilityLabel(product: ProStoreProduct?) -> String? {
    guard let product, !product.productId.isEmpty, product.isMonthly, !product.displayPrice.isEmpty else { return nil }
    return "Subscribe to PitTech Pro for \(product.displayPrice) per month"
}

// MARK: - Entitlement-driven UI state

/// Where a Pro entry point leads. Computed from the backend lane's verified
/// cache state; the client never derives this from a local purchase callback.
enum ProDestination: Equatable, Sendable {
    case loading
    case paywall
    case pro
    case expiredFallback
}

// A cancelled record that still maps to not-entitled has an exhausted
// paid-through date, so it is an ended subscription like the rest.
private let expiredServerStates: Set<String> = ["expired", "cancelled", "refunded", "revoked"]

/// - Entitled → the Pro view opens.
/// - Not entitled → the contextual paywall with its preview.
/// - Unknown/loading → a neutral loading state. Never a flash of the paywall
///   for a previously entitled user (and never a flash of ads: the ad gate
///   holds for previously entitled accounts while unknown).
/// - Expired (verified ended states) → free fallback: local records, exported
///   files, and all free capabilities keep working; nothing is destroyed.
/// Signed-out users see the paywall preview with a sign-in requirement; no
/// store purchase is ever started without a verified PitTech account.
func proDestination(
    signedIn: Bool,
    entitlement: AdEntitlementState,
    serverState: String?,
    previouslyEntitled: Bool
) -> ProDestination {
    // previouslyEntitled intentionally does not change the destination: unknown
    // is always neutral loading, so a previously entitled user never sees the
    // paywall flash while verification is in flight. The parameter stays so call
    // sites pass the real flag and tests pin both values to loading.
    guard signedIn else { return .paywall }
    switch entitlement {
    case .entitled:
        return .pro
    case .unknown:
        return .loading
    case .notEntitled:
        if let serverState, expiredServerStates.contains(serverState) { return .expiredFallback }
        return .paywall
    }
}

/// The paywall offers a purchase only when the account is verifiably not
/// covered. An entitled account sees its covered state, never a second
/// subscription button.
func proShowsPurchaseOffer(entitlement: AdEntitlementState) -> Bool {
    entitlement != .entitled
}
