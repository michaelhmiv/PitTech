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
