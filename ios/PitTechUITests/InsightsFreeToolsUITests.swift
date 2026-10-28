import XCTest

/// Slice 15 free-feature audit on device UI: signed-out history, results, chart
/// inspection, prior-cook comparison entry, and the guide toggle stay reachable.
final class InsightsFreeToolsUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<12 {
            if element.waitForExistence(timeout: 1), element.isHittable { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }

    func testFreeInsightsHistoryChartComparisonAndGuideStayAvailableSignedOutAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-guide"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            app.tabBars.buttons["Insights"].tap()
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["Temperature readings"].waitForExistence(timeout: 10))
            let history = app.staticTexts["Previous Pork"].firstMatch
            XCTAssertTrue(history.waitForExistence(timeout: 10))
            app.tabBars.buttons["Meals"].tap()
            let guided = app.staticTexts["Guided meal"].firstMatch
            reveal(guided, app: app)
            guided.tap()
            let toggle = app.switches["show-guide"]
            reveal(toggle, app: app)
            XCTAssertEqual(toggle.value as? String, "0")
            let back = app.navigationBars.buttons.firstMatch
            XCTAssertTrue(back.waitForExistence(timeout: 10))
            back.tap()
            let meal = app.staticTexts["Previous Pork"].firstMatch
            reveal(meal, app: app)
            meal.tap()
            let charts = app.segmentedControls.buttons["Charts"]
            reveal(charts, app: app)
            charts.tap()
            let plot = app.descendants(matching: .any)["temperature-plot"].firstMatch
            XCTAssertTrue(plot.waitForExistence(timeout: 10))
            reveal(plot, app: app)
            XCTAssertTrue(app.staticTexts["Compare a prior cook"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }

    /// Flowing §4.2: the composer's one-tap capture — a wrap entry records
    /// its material with one picker selection, and a spritz entry saved
    /// without touching its prompt still records "Spritz: Yes" (the logged
    /// action is the data). Both render as facts on the results card.
    func testComposerCaptureRecordsWrapAndSpritzInputs() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--ui-test-capture"]
        app.launch()
        let meal = app.staticTexts["Capture flow brisket"].firstMatch
        XCTAssertTrue(meal.waitForExistence(timeout: 10))
        meal.tap()
        func chooseAction(_ label: String) {
            let actionRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Action'")).firstMatch
            XCTAssertTrue(actionRow.waitForExistence(timeout: 10))
            actionRow.tap()
            let option = app.buttons[label].firstMatch
            XCTAssertTrue(option.waitForExistence(timeout: 10))
            option.tap()
        }
        func logEntry(action: String) {
            let log = app.buttons["meal-log"]
            XCTAssertTrue(log.waitForExistence(timeout: 10))
            log.tap()
            chooseAction(action)
        }
        // Wrap: pick Foil (one selection) and save.
        logEntry(action: "Wrap")
        let wrapPicker = app.descendants(matching: .any)["composer-wrap-material"].firstMatch
        XCTAssertTrue(wrapPicker.waitForExistence(timeout: 10))
        wrapPicker.tap()
        let foil = app.buttons["Foil"].firstMatch
        XCTAssertTrue(foil.waitForExistence(timeout: 10))
        foil.tap()
        app.buttons["Save"].tap()
        // Spritz: save without touching the prompt — still recorded.
        let live = app.segmentedControls.buttons["Live"]
        XCTAssertTrue(live.waitForExistence(timeout: 10))
        live.tap()
        logEntry(action: "Spritz")
        let spritzPicker = app.descendants(matching: .any)["composer-spritz"].firstMatch
        XCTAssertTrue(spritzPicker.waitForExistence(timeout: 10))
        app.buttons["Save"].tap()
        // The results card is the finished cook's record: inputs raise it
        // only at completion, so finish the meal and read the facts there.
        let finish = app.buttons["finish-meal"]
        XCTAssertTrue(finish.waitForExistence(timeout: 10))
        finish.tap()
        let confirm = app.alerts.buttons["Finish meal"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        let summary = app.buttons["View meal summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 10))
        summary.tap()
        XCTAssertTrue(app.staticTexts["Wrap: Foil"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Spritz: Yes"].waitForExistence(timeout: 10))
        app.terminate()
    }

    func testEmptyInsightsExplainsWhatLoggingUnlocksAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            app.tabBars.buttons["Insights"].tap()
            // PL-25 (#172 Slice 10): the Insights empty title is the shared
            // StateCopy wording on both platforms now.
            XCTAssertTrue(app.staticTexts["No cooks yet"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }
}
