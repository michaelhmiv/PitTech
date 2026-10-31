import XCTest

/// Issue #183 (iOS half): the guidance card completes a step from
/// Live, the alerts strip counts attention and an alert can be
/// acknowledged on its surface, and a fired legacy reminder's check-in
/// records from the Reminders surface. The --ui-test-plan-alerts
/// fixture seeds one cook carrying all three.
final class CookSupportUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--ui-test-plan-alerts"]
        app.launch()
        app.tabBars.buttons["Meals"].tap()
        let cook = app.staticTexts["Guidance brisket"].firstMatch
        XCTAssertTrue(cook.waitForExistence(timeout: 15))
        cook.tap()
        return app
    }

    private func scrollTo(_ app: XCUIApplication, _ element: XCUIElement, maxSwipes: Int = 8) {
        var attempts = 0
        while !element.exists && attempts < maxSwipes {
            app.swipeUp()
            attempts += 1
        }
    }

    func testGuidanceCardCompletesTheDueStep() {
        let app = launch()
        let done = app.buttons["guidance-done"]
        scrollTo(app, done)
        XCTAssertTrue(app.staticTexts["Wrap when bark sets"].exists)
        XCTAssertTrue(done.exists)
        done.tap()
        // The card advances to the manual rest step.
        let rest = app.staticTexts["Rest the brisket"]
        XCTAssertTrue(rest.waitForExistence(timeout: 10))
    }

    func testAlertStripAndAcknowledgeFromTheAlertsSurface() {
        let app = launch()
        let strip = app.staticTexts["live-alert-strip"]
        XCTAssertTrue(strip.waitForExistence(timeout: 15))
        XCTAssertTrue(strip.label.contains("1 needs attention"))
        let open = app.buttons["live-cook-alerts"]
        scrollTo(app, open)
        XCTAssertTrue(open.exists)
        open.tap()
        let acknowledge = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'alert-acknowledge-'")).firstMatch
        XCTAssertTrue(acknowledge.waitForExistence(timeout: 15))
        acknowledge.tap()
        XCTAssertTrue(app.staticTexts["Acknowledged · condition has not recovered"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let acknowledgedStrip = app.staticTexts["live-alert-strip"]
        XCTAssertTrue(acknowledgedStrip.waitForExistence(timeout: 10))
        XCTAssertTrue(acknowledgedStrip.label.contains("Acknowledged: Brisket target"))
    }

    func testLegacyReminderCheckInRecordsFromReminders() {
        let app = launch()
        let open = app.buttons["Meal reminders"]
        scrollTo(app, open)
        XCTAssertTrue(open.exists)
        open.tap()
        let checkIn = app.buttons["legacy-reminder-checkin-ui-legacy-due"]
        XCTAssertTrue(checkIn.waitForExistence(timeout: 15))
        checkIn.tap()
        XCTAssertTrue(app.staticTexts["How did it go?"].waitForExistence(timeout: 10))
        let note = app.textFields["reminder-checkin-note"]
        if note.exists { note.tap(); note.typeText("Bark is set") }
        let save = app.buttons["reminder-checkin-save"]
        XCTAssertTrue(save.exists)
        save.tap()
        // Back on Reminders, the due reminder is gone (completed).
        XCTAssertTrue(app.buttons["legacy-reminder-checkin-ui-legacy-due"].waitForNonExistence(timeout: 10))
    }
}
