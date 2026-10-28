import XCTest

/// Slice 17 native lane on device UI: entitled accounts see benchmark cards
/// with n and compute date, every other gate state shows its honest surface,
/// and Live/Finish never show a benchmark surface. Entitlement and figures are
/// scripted (`--pro-ui-test` plus `--community-*`); no server is contacted.
final class CommunityBenchmarksUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        // The benchmarks/paywall destination is a sheet with its own ScrollView:
        // swiping the application window does not reliably scroll that sheet, and
        // off-viewport SwiftUI content is not in the AX hierarchy until scrolled
        // into view. Scroll the sheet's scroll view itself with direction-aware
        // servo drags (mirroring MealRecipeFlowsTests.reveal()'s generic branch).
        let needsHittable = element.elementType == .button
        for _ in 0..<18 {
            // Cook-screen branch, mirroring MealRecipeFlowsTests.reveal()'s
            // cook-content branch exactly: the meal detail carries a fixed
            // shortcut bar in a bottom safe-area inset ABOVE the tab bar, so the
            // nav-to-tab window below would accept a button parked under that
            // bar — reported hittable, but the tap lands on the bar and the
            // expected alert never appears. Anchor gutter drags to the finite
            // navigation bar and stop above the fixed quick actions.
            let cookScroll = app.scrollViews["cook-content"]
            let cookNav = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable && $0.frame.minY.isFinite && $0.frame.width > 0 })
            let cookLog = app.buttons["meal-log"]
            if cookScroll.exists, let cookNav, cookLog.exists, cookLog.isHittable, cookLog.frame.minY.isFinite {
                let top = cookNav.frame.maxY + 10
                let bottom = cookLog.frame.minY - 20
                if element.waitForExistence(timeout: 1), element.isHittable,
                   element.frame.minY >= top, element.frame.maxY <= bottom { return }
                let down = element.exists && element.frame.minY < top
                let span = max(60, (bottom - top) * 0.55)
                let distance = element.exists ? (down ? top - element.frame.minY : element.frame.maxY - bottom) + 24 : span
                let travel = min(span, max(60, distance))
                let startY = down ? top + 24 : bottom - 24
                let endY = down ? startY + travel : startY - travel
                let origin = cookNav.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: 32, dy: startY - cookNav.frame.minY))
                let end = origin.withOffset(CGVector(dx: 32, dy: endY - cookNav.frame.minY))
                start.press(forDuration: 0.01, thenDragTo: end)
                continue
            }
            let top = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable })?.frame.maxY ?? app.windows.firstMatch.frame.minY
            let bottom = app.tabBars.allElementsBoundByIndex.first(where: { $0.isHittable })?.frame.minY ?? app.windows.firstMatch.frame.maxY
            if element.waitForExistence(timeout: 1), !needsHittable || element.isHittable {
                if element.frame.minY >= top && element.frame.maxY <= bottom - 8 { return }
            }
            let scroll = ["community-benchmarks", "pro-paywall"].compactMap { id -> XCUIElement? in
                let candidate = app.scrollViews[id]
                return candidate.exists && candidate.isHittable ? candidate : nil
            }.first ?? app.scrollViews.allElementsBoundByIndex.first(where: { $0.isHittable })
            if let scroll, scroll.exists && scroll.isHittable, scroll.frame.height.isFinite, scroll.frame.height > 0 {
                // Servo drags, not full-screen flings: a fling's displacement exceeds the
                // nav-to-tab acceptance window for tall large-text rows, so the top-edge
                // direction rule limit-cycles (up, down, up, ...) without ever landing
                // in-window. Capping each step at the remaining distance makes overshoot
                // impossible, so the same direction rule converges instead of oscillating.
                let down = element.exists && element.frame.minY < top + 8
                let span = max(60, (bottom - top) * 0.25)
                let distance = element.exists ? (down ? top - element.frame.minY : element.frame.maxY - (bottom - 8)) + 24 : span
                let travel = min(span, max(60, distance)) / scroll.frame.height
                let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                let end = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.5 + travel : 0.5 - travel))
                start.press(forDuration: 0.01, thenDragTo: end)
            } else if let scroll, scroll.exists && scroll.isHittable {
                if element.exists && element.frame.minY < top + 8 { scroll.swipeDown() } else { scroll.swipeUp() }
            } else { app.swipeUp() }
        }
        if needsHittable {
            XCTAssertTrue(element.exists && element.isHittable)
        } else {
            XCTAssertTrue(element.exists)
        }
        // Positional proof on the cook screen (mirrors MealRecipeFlowsTests):
        // hittable alone has proven insufficient here — the button must rest
        // above the fixed shortcut bar, never parked under it.
        let log = app.buttons["meal-log"]
        if app.scrollViews["cook-content"].exists, log.exists, log.isHittable,
           let navigation = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable }) {
            XCTAssertGreaterThanOrEqual(element.frame.minY, navigation.frame.maxY + 10)
            XCTAssertLessThanOrEqual(element.frame.maxY, log.frame.minY - 20)
        }
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
            // Flowing Slice 2 (1.6): Finish lives in the cook menu now —
            // the toolbar is always visible, so the old scroll-reveal
            // dance (and its parking-under-the-shortcut-bar failure) is gone.
            XCTAssertTrue(app.buttons["cook-menu"].waitForExistence(timeout: 15))
            assertNoBenchmarkSurface(app: app)
            app.buttons["cook-menu"].tap()
            let finish = app.buttons["finish-meal"]
            XCTAssertTrue(finish.waitForExistence(timeout: 10))
            XCTAssertTrue(finish.isHittable)
            finish.tap()
            XCTAssertTrue(app.alerts["Finish meal?"].waitForExistence(timeout: 20))
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
