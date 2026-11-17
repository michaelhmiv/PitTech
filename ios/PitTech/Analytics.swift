import Foundation
import FirebaseAnalytics
import FirebaseCore

/// Product analytics — GA4 via Firebase Analytics (issue #322). This enum is
/// the ONE owner of product analytics for the iOS app, mirroring the Android
/// wrapper (`com.pittech.analytics.PitTechAnalytics`) and the CyberCache
/// wrapper (CyberCache PR #322): every screen and service reports through the
/// small typed functions below and never touches the Firebase SDK directly.
///
/// What this feeds: the GA4 property linked to the PitTech Firebase project
/// (`pittech-529a1`) — its standard reports (active users DAU/WAU/MAU,
/// retention cohorts, engagement) come from the SDK's automatic collection;
/// this wrapper adds the app's own vocabulary on top: screen views for the
/// main surfaces and one custom event per core-loop verb.
///
/// PRIVACY LAW (do not break):
///   - No PII, ever: no emails, no cook titles, no notes text, no photos, no
///     raw user/account ids in event params. Firebase's own pseudonymous
///     app-instance id is the only identity GA4 sees.
///   - Params are content ids and small enums only, enforced by
///     `AnalyticsTaxonomy`'s allowlist sanitizer — a param that is not in
///     the taxonomy cannot reach the SDK even if a future caller tries.
///   - Analytics is never a gate: a failed or absent analytics config must
///     never break, block, or delay cooking. Every public function here is
///     fire-and-forget and total: it cannot throw into a caller.
///
/// CONFIG: until the real Firebase client config is installed
/// (GoogleService-Info.plist in the app bundle, Phase B console work),
/// `configure()` finds no plist, leaves analytics unconfigured, and every
/// function is a silent no-op: nothing is faked, no placeholder id shipped.

/// The main surfaces that report a screen view.
enum AnalyticsScreen: String {
    case home = "Home"
    case liveBoard = "Live board"
    case timeline = "Timeline"
    case history = "History"
    case recipes = "Recipes"
    case settings = "Settings"
}

/// Sign-in methods for the `sign_in` event — a small enum, never free text.
enum AnalyticsSignInMethod: String {
    case email
    case google
    case apple
}

/// The event taxonomy: the ONLY custom events and params analytics may send.
/// Adding an event means adding it here, adding a typed function on
/// `PitTechAnalytics`, and calling it from the ONE place the action settles —
/// never from a render path, never speculatively before the action succeeds.
/// This mirrors `AnalyticsTaxonomy` on Android exactly; the unit tests on
/// both platforms pin the same vocabulary.
enum AnalyticsTaxonomy {
    static let eventCookStarted = "cook_started"
    static let eventCookCompleted = "cook_completed"
    static let eventCookShared = "cook_shared"
    static let eventRecipeCreated = "recipe_created"
    static let eventSignIn = "sign_in"

    static let paramMethod = "method"

    static let eventNames: Set<String> = [
        eventCookStarted, eventCookCompleted, eventCookShared, eventRecipeCreated, eventSignIn,
    ]

    /// Per-event param allowlist. An event not listed sends no params.
    private static let allowedParams: [String: Set<String>] = [
        eventSignIn: [paramMethod],
    ]

    private static let signInMethods: Set<String> = ["email", "google", "apple"]

    /// GA4 caps string param values at 100 characters.
    private static let maxParamValueLength = 100

    static func isKnownEvent(_ name: String) -> Bool { eventNames.contains(name) }

    /// Keep only the params the taxonomy allows for `event`, with GA4-safe
    /// scalar values. Unknown keys, nils, unsupported types and out-of-enum
    /// values are dropped — this is the enforcement point for the no-PII law.
    static func sanitizeParams(event: String, params: [String: Any]) -> [String: Any] {
        guard let allowed = allowedParams[event], !params.isEmpty else { return [:] }
        var clean: [String: Any] = [:]
        for (key, value) in params {
            guard allowed.contains(key) else { continue }
            switch value {
            case let string as String:
                if event == eventSignIn, key == paramMethod, !signInMethods.contains(string) { continue }
                clean[key] = String(string.prefix(maxParamValueLength))
            case let number as NSNumber:
                clean[key] = number
            default:
                continue // Unsupported types never reach the SDK.
            }
        }
        return clean
    }
}

/// Where sanitized events go. The Firebase sink exists only when configured.
protocol AnalyticsSink {
    func logEvent(_ name: String, params: [String: Any])
    func logScreenView(_ screenName: String)
}

struct NoOpAnalyticsSink: AnalyticsSink {
    func logEvent(_ name: String, params: [String: Any]) {}
    func logScreenView(_ screenName: String) {}
}

struct FirebaseAnalyticsSink: AnalyticsSink {
    func logEvent(_ name: String, params: [String: Any]) {
        Analytics.logEvent(name, parameters: params)
    }

    func logScreenView(_ screenName: String) {
        Analytics.logEvent(AnalyticsEventScreenView, parameters: [
            AnalyticsParameterScreenName: screenName,
            AnalyticsParameterScreenClass: "PitTechApp",
        ])
    }
}

enum PitTechAnalytics {
    private static var sink: AnalyticsSink = NoOpAnalyticsSink()
    private static var configureAttempted = false

    /// One-time setup from `PitTechApp.init`. Configures Firebase ONLY when
    /// GoogleService-Info.plist is in the bundle; otherwise analytics stays
    /// a silent no-op. Never throws.
    static func configure() {
        guard !configureAttempted else { return }
        configureAttempted = true
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        FirebaseApp.configure()
        if FirebaseApp.app() != nil { sink = FirebaseAnalyticsSink() }
    }

    /// Test seam: swap the sink, returning the previous one for restore.
    static func installSinkForTests(_ replacement: AnalyticsSink) -> AnalyticsSink {
        let previous = sink
        sink = replacement
        return previous
    }

    static func screenView(_ screen: AnalyticsScreen) {
        sink.logScreenView(screen.rawValue)
    }

    static func cookStarted() { logEvent(AnalyticsTaxonomy.eventCookStarted, params: [:]) }

    static func cookCompleted() { logEvent(AnalyticsTaxonomy.eventCookCompleted, params: [:]) }

    /// A relay share code was created — the cook can now be followed by link.
    static func cookShared() { logEvent(AnalyticsTaxonomy.eventCookShared, params: [:]) }

    static func recipeCreated() { logEvent(AnalyticsTaxonomy.eventRecipeCreated, params: [:]) }

    static func signIn(method: AnalyticsSignInMethod) {
        logEvent(AnalyticsTaxonomy.eventSignIn, params: [AnalyticsTaxonomy.paramMethod: method.rawValue])
    }

    private static func logEvent(_ name: String, params: [String: Any]) {
        guard AnalyticsTaxonomy.isKnownEvent(name) else { return }
        sink.logEvent(name, params: AnalyticsTaxonomy.sanitizeParams(event: name, params: params))
    }
}
