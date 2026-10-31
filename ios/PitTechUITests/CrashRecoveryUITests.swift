import XCTest

/// PL-20 (#184): the iOS crash-recovery surface — a launch with a saved,
/// unacknowledged report opens on the recovery screen (not the welcome,
/// not the tabs), Continue returns to the cook that was open at the
/// crash, and "Attach to a problem report" lands in the feedback
/// composer with the report included and its exact payload previewable.
/// The --ui-test-crash-recovery fixture seeds the report and the cook.
final class CrashRecoveryUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func launchCrashRecovery() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--ui-test-crash-recovery"]
        app.launch()
        return app
    }

    /// Multi-line text blobs (details, the plain-text preview) render as
    /// one element whose label is the whole blob — match by content.
    private func elementContaining(_ app: XCUIApplication, _ substring: String) -> XCUIElement {
        app.descendants(matching: .any).containing(NSPredicate(format: "label CONTAINS %@", substring)).firstMatch
    }

    func testRecoverySurfaceShowsReferenceAndSummaryThenContinuesToTheOpenCook() {
        let app = launchCrashRecovery()
        XCTAssertTrue(app.staticTexts["PitTech stopped unexpectedly"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["PT-1A2B3C4D"].exists)
        XCTAssertTrue(app.staticTexts["FixtureException: seeded for the crash-recovery flow"].exists)
        XCTAssertTrue(app.staticTexts["We saved a diagnostic report on this device. Nothing was sent automatically."].exists)

        // Technical details stay tucked away until asked for.
        XCTAssertFalse(elementContaining(app, "Seeded stack trace").exists)
        let details = app.buttons["crash-report-details"]
        XCTAssertTrue(details.exists)
        details.tap()
        XCTAssertTrue(elementContaining(app, "Seeded stack trace").waitForExistence(timeout: 5))

        let continueButton = app.buttons["crash-report-continue"]
        XCTAssertTrue(continueButton.exists)
        continueButton.tap()

        // Continue lands on the cook that was open at the crash — its
        // detail is the sheet the notification route also uses.
        XCTAssertTrue(elementContaining(app, "Crash fixture brisket").waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["crash-report-continue"].waitForNonExistence(timeout: 5))
    }

    func testAttachOpensFeedbackWithTheReportIncludedAndPreviewable() {
        let app = launchCrashRecovery()
        let attach = app.buttons["crash-report-attach"]
        XCTAssertTrue(attach.waitForExistence(timeout: 15))
        attach.tap()

        let toggle = app.switches["feedback-include-diagnostic"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 15))
        XCTAssertEqual(toggle.value as? String, "1", "the attach door opens with the report included")

        let previewToggle = app.buttons["feedback-crash-preview-toggle"]
        XCTAssertTrue(previewToggle.exists)
        previewToggle.tap()
        let preview = app.descendants(matching: .any)["feedback-crash-preview"]
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertTrue(elementContaining(app, "PitTech local diagnostic report").exists)
        XCTAssertTrue(elementContaining(app, "Reference code: PT-1A2B3C4D").exists)
    }
}
