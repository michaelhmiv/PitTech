import XCTest
@testable import PitTech

/// Issue #322: the taxonomy is the privacy boundary for GA4. These tests pin
/// the exact event/screen/method vocabulary and prove the sanitizer drops
/// anything outside it — PII-shaped params can never reach the SDK. This
/// mirrors `PitTechAnalyticsTest` on Android; both pin the same vocabulary.
final class AnalyticsTests: XCTestCase {

    func testTaxonomyContainsExactlyTheFiveCoreLoopEvents() {
        XCTAssertEqual(
            AnalyticsTaxonomy.eventNames,
            ["cook_started", "cook_completed", "cook_shared", "recipe_created", "sign_in"]
        )
    }

    func testScreenNamesMatchTheBriefSurfaces() {
        XCTAssertEqual(
            [AnalyticsScreen.home, .liveBoard, .timeline, .history, .recipes, .settings].map(\.rawValue),
            ["Home", "Live board", "Timeline", "History", "Recipes", "Settings"]
        )
    }

    func testSignInMethodsAreTheSmallEnumOnly() {
        XCTAssertEqual(
            [AnalyticsSignInMethod.email, .google, .apple].map(\.rawValue),
            ["email", "google", "apple"]
        )
    }

    func testSanitizerKeepsOnlyTheAllowedMethodParamForSignIn() {
        let clean = AnalyticsTaxonomy.sanitizeParams(
            event: AnalyticsTaxonomy.eventSignIn,
            params: [AnalyticsTaxonomy.paramMethod: "google"]
        )
        XCTAssertEqual(clean as? [String: String], ["method": "google"])
    }

    func testSanitizerDropsPiiShapedParamsEvenForKnownEvents() {
        let clean = AnalyticsTaxonomy.sanitizeParams(
            event: AnalyticsTaxonomy.eventSignIn,
            params: [
                AnalyticsTaxonomy.paramMethod: "email",
                "email": "someone@example.com",
                "cook_title": "Saturday brisket",
                "notes": "wrapped at 165",
                "user_id": "account-123",
            ]
        )
        XCTAssertEqual(clean as? [String: String], ["method": "email"])
    }

    func testSanitizerDropsOutOfEnumMethodValues() {
        let clean = AnalyticsTaxonomy.sanitizeParams(
            event: AnalyticsTaxonomy.eventSignIn,
            params: [AnalyticsTaxonomy.paramMethod: "carrier-pigeon"]
        )
        XCTAssertTrue(clean.isEmpty)
    }

    func testSanitizerSendsNoParamsForParamlessEvents() {
        let clean = AnalyticsTaxonomy.sanitizeParams(
            event: AnalyticsTaxonomy.eventCookStarted,
            params: ["method": "email", "anything": 42]
        )
        XCTAssertTrue(clean.isEmpty)
    }

    func testUnknownEventsAreNotKnown() {
        XCTAssertFalse(AnalyticsTaxonomy.isKnownEvent("cook_deleted"))
        XCTAssertFalse(AnalyticsTaxonomy.isKnownEvent("screen_view"))
        XCTAssertTrue(AnalyticsTaxonomy.isKnownEvent(AnalyticsTaxonomy.eventCookShared))
    }

    private final class RecordingSink: AnalyticsSink {
        var events: [(String, [String: Any])] = []
        var screens: [String] = []
        func logEvent(_ name: String, params: [String: Any]) { events.append((name, params)) }
        func logScreenView(_ screenName: String) { screens.append(screenName) }
    }

    func testWrapperForwardsSanitizedEventsAndScreensToTheSink() {
        let recording = RecordingSink()
        let previous = PitTechAnalytics.installSinkForTests(recording)
        defer { _ = PitTechAnalytics.installSinkForTests(previous) }
        PitTechAnalytics.cookStarted()
        PitTechAnalytics.cookCompleted()
        PitTechAnalytics.cookShared()
        PitTechAnalytics.recipeCreated()
        PitTechAnalytics.signIn(method: .apple)
        PitTechAnalytics.screenView(.liveBoard)
        XCTAssertEqual(
            recording.events.map(\.0),
            ["cook_started", "cook_completed", "cook_shared", "recipe_created", "sign_in"]
        )
        XCTAssertEqual(recording.events.last?.1 as? [String: String], ["method": "apple"])
        XCTAssertTrue(recording.events.dropLast().allSatisfy { $0.1.isEmpty })
        XCTAssertEqual(recording.screens, ["Live board"])
    }

    func testNoOpSinkAcceptsEverythingSilently() {
        // The unconfigured-build path: with no GoogleService-Info.plist in
        // the test bundle the sink stays no-op and nothing can throw.
        PitTechAnalytics.cookStarted()
        PitTechAnalytics.signIn(method: .email)
        PitTechAnalytics.screenView(.home)
    }
}
