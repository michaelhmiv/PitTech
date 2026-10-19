import XCTest

/// Slice 17 native lane on device UI: entitled accounts see benchmark cards
/// with n and compute date, every other gate state shows its honest surface,
/// and Live/Finish never show a benchmark surface. Entitlement and figures are
/// scripted (`--pro-ui-test` plus `--community-*`); no server is contacted.
final class CommunityBenchmarksUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<12 {
            if element.waitForExistence(timeout: 1), element.isHittable { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }

    private func launch(extra: [String] = [], large: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-guide", "--pro-ui-test"] + extra + (large ? ["--screenshot-large-text"] : [])
        app.launch()
        return app
    }

    private func openBenchmarks(app: XCUIApplication) {
        app.tabBars.buttons["Insights"].tap()
        XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
        let button = app.buttons["pro-entry-benchmarks"]
        reveal(button, app: app)
        button.tap()
    }

    private func assertNoBenchmarkSurface(app: XCUIApplication) {
        for id in ["community-benchmarks", "community-empty", "community-unavailable", "community-loading"] {
            XCTAssertFalse(app.scrollViews[id].exists, "no benchmark surface may appear here: \(id)")
        }
    }

    func testEntitledBenchmarksShowFigureCardsWithNAndDateAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled"], large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["community-benchmarks"].waitForExistence(timeout: 10))
            for title in ["Typical heating time", "Temperature percentile bands", "Stall summary", "Wrap and spritz prevalence", "Not enough cooks yet"] {
                let text = app.staticTexts[title].firstMatch
                reveal(text, app: app)
                XCTAssertTrue(text.exists)
            }
            for line in [
                "10 eligible cooks · Computed 2026-10-18",
                "9 eligible cooks · Computed 2026-10-18",
                "Pork butt / shoulder · Medium · Method: smoke",
                "Pork loin · Medium · Method: smoke",
                "Typical (median): 5h 30m",
                "Food band hidden: 3 of 10 cooks in this interval (30%). Bands need 10 cooks.",
                "Percentile bands summarize many cooks. They are not one cook's readings.",
                "Measured stall windows only; sparse attempts are excluded from n, never interpolated.",
                "Wrapped: 6 of 10 (60%)",
            ] {
                let text = app.staticTexts[line].firstMatch
                reveal(text, app: app)
                XCTAssertTrue(text.exists, line)
            }
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            app.buttons["pro-close"].tap()
            XCTAssertFalse(app.scrollViews["community-benchmarks"].waitForExistence(timeout: 2))
            app.terminate()
        }
    }

    func testInsufficientDataCardExplainsThresholdAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled", "--community-insufficient"], large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["community-benchmarks"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.descendants(matching: .any)["community-insufficient-median-heating-time-v1-pork-loin"].firstMatch.waitForExistence(timeout: 10))
            let explanation = app.staticTexts["9 of 10 eligible cooks recorded. Figures appear when 10 distinct eligible cooks are recorded."].firstMatch
            reveal(explanation, app: app)
            XCTAssertTrue(explanation.exists)
            XCTAssertFalse(app.descendants(matching: .any)["community-card-median-heating-time-v1-pork-butt-shoulder"].exists)
            XCTAssertFalse(app.staticTexts["Typical (median): 5h 30m"].exists)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testEmptyStateShowsNoFiguresAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled", "--community-empty"], large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["community-empty"].waitForExistence(timeout: 10))
            let empty = app.staticTexts["No community figures yet"].firstMatch
            reveal(empty, app: app)
            XCTAssertTrue(empty.exists)
            XCTAssertFalse(app.scrollViews["community-benchmarks"].exists)
            app.buttons["pro-close"].tap()
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }

    func testSignedOutShowsPaywallWithSignInAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-not-entitled", "--pro-signed-out"], large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["pro-signin-required"].waitForExistence(timeout: 10))
            assertNoBenchmarkSurface(app: app)
            app.buttons["pro-paywall-close"].tap()
            app.terminate()
        }
    }

    func testNonProShowsPaywallWithoutFiguresAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-not-entitled"], large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
            assertNoBenchmarkSurface(app: app)
            app.buttons["pro-paywall-close"].tap()
            app.terminate()
        }
    }

    func testUnknownEntitlementShowsNeutralLoadingAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["pro-loading"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            assertNoBenchmarkSurface(app: app)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testUnavailableShowsRetryAndPreservesFreeToolsAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled", "--community-unavailable"], large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["community-unavailable"].waitForExistence(timeout: 10))
            let retry = app.buttons["community-retry"]
            reveal(retry, app: app)
            retry.tap()
            XCTAssertTrue(app.scrollViews["community-unavailable"].waitForExistence(timeout: 10))
            app.buttons["pro-close"].tap()
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["Temperature readings"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }

    func testStaleForbiddenFallsBackToPaywallAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled", "--community-forbidden"], large: large)
            openBenchmarks(app: app)
            XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
            assertNoBenchmarkSurface(app: app)
            XCTAssertFalse(app.buttons["pro-subscribe"].exists)
            app.buttons["pro-paywall-close"].tap()
            app.terminate()
        }
    }

    func testLiveAndFinishShowNoBenchmarkSurfacesAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            let start = app.buttons["Start meal"]
            XCTAssertTrue(start.waitForExistence(timeout: 15))
            start.tap()
            app.buttons["start-meal-save"].tap()
            XCTAssertTrue(app.buttons["finish-meal"].waitForExistence(timeout: 15))
            assertNoBenchmarkSurface(app: app)
            let finish = app.buttons["finish-meal"]
            reveal(finish, app: app)
            finish.tap()
            XCTAssertTrue(app.alerts["Finish meal?"].waitForExistence(timeout: 5))
            app.alerts.buttons["Finish meal"].tap()
            XCTAssertTrue(app.buttons["Leave without saving"].waitForExistence(timeout: 15))
            assertNoBenchmarkSurface(app: app)
            app.buttons["Leave without saving"].tap()
            XCTAssertTrue(app.staticTexts["Finished"].waitForExistence(timeout: 10))
            assertNoBenchmarkSurface(app: app)
            app.terminate()
        }
    }
}
