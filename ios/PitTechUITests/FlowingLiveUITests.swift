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
        // Position the stepper VERIFIABLY on screen before tapping
        // (round 7, from run 6's retained recording): reveal()
        // stopped after ONE swipe — which carried the button past
        // the top of the viewport (it sits less than a swipe below
        // the fold) — because its exit condition trusts hittability,
        // and this fleet misreports hittability for off-screen
        // elements (the fork sheet's keyboard-buried field read
        // hittable too, run 5). All six receipt taps then
        // synthesized at the button's off-screen frame. Servo on
        // the FRAME instead — the signal that has been reliable in
        // every round: swipe toward the button until its center
        // sits inside the window, clear of the nav and tab bars.
        let plus = app.buttons["pit-target-plus"]
        let windowFrame = app.windows.firstMatch.frame
        // The in-band sample must be a SETTLED position, read twice
        // (#279 follow-up, from run 41432357674): on the slow runners
        // of attempts 1–2 every frame evaluation took seconds, so the
        // single trusted sample was inherently post-settle — the
        // servo spent three corrective swipes getting there and one
        // tap then stepped within ~1 s. On attempt 3's fast runner
        // the evaluations returned in ~0.5–3 s: reveal() exited after
        // ONE swipe, the servo broke on the FIRST in-band sample,
        // taken ~0.6 s after its single corrective swipe — inside
        // the scroll's bounce/deceleration window, before the view
        // came to rest — and all six receipt taps then synthesized
        // cleanly at a position the settled view did not hold (the
        // loop's own guard re-reads prove the label stayed 250°
        // throughout). A fast sample can catch a transient in-band
        // frame; two identical in-band frames a beat apart cannot —
        // the scroll has provably come to rest at the tapped
        // position. The stability check spends loop iterations
        // without swiping, so the cap rises 8 → 10; the band, the
        // swipe directions, and the settle sleeps are unchanged.
        var settledMidY: CGFloat?
        for _ in 0..<10 {
            let midY = plus.frame.midY
            if midY > windowFrame.minY + 120 && midY < windowFrame.maxY - 100 {
                if let settledMidY, abs(midY - settledMidY) < 1 { break }
                settledMidY = midY
                Thread.sleep(forTimeInterval: 1)
                continue
            }
            settledMidY = nil
            if midY <= windowFrame.minY + 120 { app.swipeDown() } else { app.swipeUp() }
            Thread.sleep(forTimeInterval: 0.6)
        }
        // Tap the stepper — and verify each tap by its RECEIPT
        // (#279): this fleet intermittently drops synthetic taps
        // entirely (run 4: two clean taps on this button, the label
        // never moved in 45 s; the same dropped-activation signature
        // starved the fork sheet's target field in flows-a). A
        // re-tap issued on a fixed schedule can double-step a
        // slow-but-live tap; a re-tap issued only while the label
        // still reads 250° cannot overshoot — the value itself
        // gates every attempt. Bounded at six taps; the assertion
        // is unchanged: the stepper must show 255°.
        var stepped = false
        for _ in 0..<6 where !stepped {
            // A step that lands between the waiter's last poll and
            // this re-check is the receipt, not an exit: recognize
            // 255° here instead of breaking with `stepped` still
            // false. Any other value (a double-step, a reset) still
            // breaks, and the assertion below is unchanged.
            if targetValue.label == "255°" { stepped = true; break }
            guard targetValue.label == "250°" else { break }
            plus.tap()
            let stepWait = expectation(for: NSPredicate(format: "label == '255°'"), evaluatedWith: targetValue)
            stepped = XCTWaiter.wait(for: [stepWait], timeout: 6) == .completed
        }
        XCTAssertTrue(stepped, "stepper never showed 255°")
        XCTAssertTrue(app.buttons["live-conditions-changed"].exists)

        // 1.3 + Spread 02: the hero is a door to the full chart; the
        // ghost delta speaks at the cursor.
        let hero = app.buttons["live-chart-hero"]
        reveal(hero, app: app)
        XCTAssertTrue(hero.waitForExistence(timeout: 10))
        hero.tap()
        // The readout identifier sits on the readout CARD (a .contain
        // container), not a Text — it is queried as otherElements
        // below (round 6: the type-agnostic query it replaces never
        // completed an evaluation on this screen; see there).
        // WAIT OUT THE RENDER QUERY-FREE (#279, restructured after
        // run 3): the chart's first render saturates the accessibility
        // tree for ~40 s under shard load, and any query issued into
        // that window can wedge inside XCTest's snapshot evaluation
        // until it hard-errors — "Timed out while evaluating UI
        // query". That is how the gate's waitForExistence died, and
        // run 3 proved a plain .exists poll on the readout dies the
        // same way: two quick false reads early (the app not yet in
        // the heavy phase), then the third evaluation wedged for
        // ~90 s of internal retries. The waiting strategy was never
        // the variable — issuing queries mid-render is. Polling a
        // "calmer" element has the same exposure (every poll is a
        // snapshot), so the only safe wait is one that issues NO
        // queries: sleep past the observed render window, then run
        // the test's own settle check and content asserts against a
        // calm tree, as in the runs that pass.
        Thread.sleep(forTimeInterval: 60)
        let closeButton = app.buttons["live-chart-close"]
        // Budget sized to the measured per-poll service cost (#279
        // follow-up, run 41432357674 attempts 1–2): with the chart
        // AX-subtree collapse in place every evaluation on this sheet
        // COMPLETES (zero snapshot hard errors), but each isHittable
        // poll costs ~93 s (three internal snapshot retries at ~31 s
        // each; polls began at t≈120.9 / 215.0 / 307.1). The old
        // 120 s deadline therefore admitted fewer than two full polls
        // and expired mid-third. 300 s admits at least three full
        // polls (3 × ~93 s ≈ 279 s) with margin. The settle CONDITION
        // is unchanged — the close button hittable, asserted below
        // exactly as before; only the budget is sized to the service.
        let settleDeadline = Date().addingTimeInterval(300)
        while !closeButton.isHittable && Date() < settleDeadline {
            Thread.sleep(forTimeInterval: 2)
        }
        let closeHittable = closeButton.isHittable
        XCTAssertTrue(closeHittable)
        // Query the readout by its real element type (round 6, from
        // run 5's retained screen recording): the recording's final
        // frame shows the chart COMPLETE on screen — readout card
        // ("Probe 175°", "+38 min vs last brisket at this point"),
        // presets, both plotted series — while the type-agnostic
        // descendants(.any) evaluation never returned at all (~110 s
        // of internal retries, then the hard snapshot timeout). On
        // this screen the whole-tree query is the one evaluation
        // that cannot complete on a loaded runner; typed finds
        // (buttons above) completed at ~31 s a pass. The readout is
        // a .contain container (LiveChartView), the same
        // construction as pit-card and meals-empty-card, which this
        // suite queries as otherElements — so query it that way,
        // and poll at a lazy interval so evaluations never stack.
        let readout = app.otherElements["live-chart-readout"]
        let readoutDeadline = Date().addingTimeInterval(240)
        while !readout.exists && Date() < readoutDeadline {
            Thread.sleep(forTimeInterval: 5)
        }
        XCTAssertTrue(readout.exists, "live-chart-readout never appeared")
        let delta = app.staticTexts["live-chart-ghost-delta"]
        let deltaDeadline = Date().addingTimeInterval(120)
        while !delta.exists && Date() < deltaDeadline {
            Thread.sleep(forTimeInterval: 5)
        }
        XCTAssertTrue(delta.exists, "live-chart-ghost-delta never appeared")
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
