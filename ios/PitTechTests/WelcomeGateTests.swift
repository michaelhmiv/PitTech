import XCTest
@testable import PitTech

/// #187: welcome launch routing — identical rule to Android's
/// resolveLaunchSurface (crash precedence, seen-once, fixture suppression).
final class WelcomeGateTests: XCTestCase {
    func testFreshInstallWithoutCrashShowsWelcome() {
        XCTAssertTrue(WelcomeGate.shouldShow(seen: false, crashPendingAtLaunch: false, suppressedForTesting: false))
    }

    func testSeenWelcomeNeverShowsAgain() {
        XCTAssertFalse(WelcomeGate.shouldShow(seen: true, crashPendingAtLaunch: false, suppressedForTesting: false))
    }

    func testCrashPendingAtLaunchSuppressesWelcome() {
        XCTAssertFalse(WelcomeGate.shouldShow(seen: false, crashPendingAtLaunch: true, suppressedForTesting: false))
    }

    func testFixtureLaunchesSuppressWelcome() {
        XCTAssertFalse(WelcomeGate.shouldShow(seen: false, crashPendingAtLaunch: false, suppressedForTesting: true))
    }

    func testSeenKeyMatchesAndroidPreferenceKey() {
        XCTAssertEqual(WelcomeGate.seenKey, "pittech-welcome-seen-v1")
    }
}
