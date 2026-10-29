import XCTest

/// Flowing Slice 2 (spec §5 Spreads 01–02, §5.8, Slice 0 parity): the
/// brand strip on every tab, the Start-a-cook FAB, the Live board's
/// verdict + living ETA with its basis stated, the hero chart opening
/// the full-screen chart with its ghost delta, the pit-target stepper,
/// the overnight test alert's in-app confirmation, and Pause/Finish in
/// the cook menu. The --ui-test-flowing fixture lays the series by
/// hand so every asserted string is deterministic.
final class FlowingLiveUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func launchFlowing() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--ui-test-flowing"]
        app.launch()
        return app
    }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return }
            app.scrollViews.firstMatch.swipeUp()
        }
    }

    func testBrandStripOnEveryTabAndFabOpensStartSheet() {
        let app = launchFlowing()
        for tab in ["Meals", "Recipes", "Discover", "Insights", "More"] {
            let button = app.tabBars.buttons[tab]
            XCTAssertTrue(button.waitForExistence(timeout: 10), "tab \(tab)")
            button.tap()
            // The strip combines its children into one accessibility
            // element, which SwiftUI surfaces as a StaticText — query it
            // type-agnostically.
            XCTAssertTrue(app.descendants(matching: .any)["pittech-brand-strip"].waitForExistence(timeout: 10), "brand strip on \(tab)")
        }
        app.tabBars.buttons["Meals"].tap()
        let fab = app.buttons["start-cook-fab"]
        XCTAssertTrue(fab.waitForExistence(timeout: 10))
        fab.tap()
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()
    }

    func testLiveBoardVerdictEtaChartAndOvernight() {
        let app = launchFlowing()
        let cook = app.staticTexts["Flowing brisket"].firstMatch
        XCTAssertTrue(cook.waitForExistence(timeout: 15))
        cook.tap()

        // 1.1/1.2: the verdict line and the living ETA, basis stated.
        let headline = app.staticTexts["live-verdict-headline"]
        XCTAssertTrue(headline.waitForExistence(timeout: 15))
        XCTAssertEqual(headline.label, "On pace — nothing needs doing.")
        // Below-fold board content is only materialized in the
        // accessibility tree once scrolled toward — reveal before
        // asserting, like the rest of this suite.
        let etaRange = app.staticTexts["live-eta-range"]
        reveal(etaRange, app: app)
        XCTAssertTrue(etaRange.waitForExistence(timeout: 10))
        XCTAssertTrue(etaRange.label.hasPrefix("Done "), etaRange.label)
        XCTAssertEqual(app.staticTexts["live-eta-basis"].label, "from your last 30 min of readings")

        // 1.5: the pit-target stepper writes the app's own target history.
        // The post-tap assert WAITS for the new value: the write +
        // re-render land a beat after the tap, and an immediate read
        // raced it on a fast shard run (250° read at +0.3s).
        let targetValue = app.staticTexts["pit-target-value"]
        reveal(targetValue, app: app)
        XCTAssertEqual(targetValue.label, "250°")
        app.buttons["pit-target-plus"].tap()
        let stepped = expectation(for: NSPredicate(format: "label == '255°'"), evaluatedWith: targetValue)
        XCTAssertEqual(XCTWaiter.wait(for: [stepped], timeout: 10), .completed, "stepper never showed 255°")
        XCTAssertTrue(app.buttons["live-conditions-changed"].exists)

        // 1.3 + Spread 02: the hero is a door to the full chart; the
        // ghost delta speaks at the cursor.
        let hero = app.buttons["live-chart-hero"]
        reveal(hero, app: app)
        XCTAssertTrue(hero.waitForExistence(timeout: 10))
        hero.tap()
        // The readout identifier sits on the readout CARD (a container),
        // not a Text — query it type-agnostically, like the brand strip.
        // (As a merged StaticText it was findable before the card gained
        // children: .contain; as a contained container it is not a
        // StaticText at all, and the staticTexts query timed out.)
        let readout = app.descendants(matching: .any)["live-chart-readout"]
        XCTAssertTrue(readout.waitForExistence(timeout: 15))
        let delta = app.staticTexts["live-chart-ghost-delta"]
        XCTAssertTrue(delta.waitForExistence(timeout: 10))
        // The delta speaks in the spec's own words (§2.4), identical on
        // both platforms; the fixture's geometry makes it exactly +38.
        XCTAssertTrue(delta.label.contains("+38 min vs last brisket at this point"), delta.label)
        app.buttons["live-chart-preset-last2h"].tap()
        XCTAssertTrue(readout.exists)
        app.buttons["live-chart-close"].tap()

        // E3: the overnight panel assembles the net; the test alert posts.
        let testAlert = app.buttons["overnight-test-alert"]
        reveal(testAlert, app: app)
        XCTAssertTrue(testAlert.waitForExistence(timeout: 10))
        testAlert.tap()
        XCTAssertTrue(app.staticTexts["overnight-test-sent"].waitForExistence(timeout: 10))
    }

    func testPauseAndFinishLiveInTheCookMenu() {
        let app = launchFlowing()
        let cook = app.staticTexts["Flowing brisket"].firstMatch
        XCTAssertTrue(cook.waitForExistence(timeout: 15))
        cook.tap()
        XCTAssertTrue(app.buttons["cook-menu"].waitForExistence(timeout: 15))
        app.buttons["cook-menu"].tap()
        XCTAssertTrue(app.buttons["pause-meal"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["finish-meal"].exists)
        app.buttons["pause-meal"].tap()
        // Pausing answers at the top of the board; resume from the menu.
        let pausedStrip = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Paused'")).firstMatch
        XCTAssertTrue(pausedStrip.waitForExistence(timeout: 10))
        app.buttons["cook-menu"].tap()
        let resume = app.buttons["pause-meal"]
        XCTAssertTrue(resume.waitForExistence(timeout: 10))
        resume.tap()
        app.buttons["cook-menu"].tap()
        let finish = app.buttons["finish-meal"]
        XCTAssertTrue(finish.waitForExistence(timeout: 10))
        finish.tap()
        XCTAssertTrue(app.alerts["Finish meal?"].waitForExistence(timeout: 15))
        app.alerts.buttons["Finish meal"].tap()
        XCTAssertTrue(app.buttons["Leave without saving"].waitForExistence(timeout: 15))
        app.buttons["Leave without saving"].tap()
        XCTAssertTrue(app.staticTexts["Finished"].waitForExistence(timeout: 15))
    }
}
