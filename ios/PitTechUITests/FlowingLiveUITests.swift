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

    /// Drags the board's scroll view by a MEASURED amount of content
    /// travel, in points (the content follows the finger: a positive
    /// shift drags the content down, moving the element's window
    /// midY down by the same amount; the drag tracks the finger,
    /// then holds a beat so no momentum carries past the target). The servos below actuate with this instead of full
    /// swipes for the reason the stepper evidence proved (run
    /// 41504631806, retained recording, measured in the stepper
    /// comment further down): a full swipe is bang-bang — it can only
    /// park an element at its travel extremes — and for the pit
    /// stepper one extreme is inside the fixed bottom shortcut bar's
    /// footprint, where a tap's delivery is a coin flip. A computed
    /// drag can stop at any offset, so the element can be parked at
    /// the band CENTER, clear of both bars, and the loop's next
    /// sample corrects whatever the drag over- or under-travelled.
    private func dragBoardContent(by shift: CGFloat, app: XCUIApplication) {
        let clamped = max(-350, min(350, shift))
        guard abs(clamped) > 4 else { return }
        let scrollView = app.scrollViews.firstMatch
        let start = scrollView.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let end = start.withOffset(CGVector(dx: 0, dy: clamped))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .default, thenHoldForDuration: 0.25)
    }

    /// Settled-position tap for BOARD (scroll-view) elements — the
    /// gate the stepper servo proved (#279 follow-up) and the hero
    /// door now gets as well (third mechanism, below). reveal()'s
    /// exit condition trusts existence + hittability, and this fleet
    /// misreports both for elements whose scroll is still in motion:
    /// a tap synthesized from a transient frame lands wherever that
    /// frame pointed, not where the settled element sits. Servo on
    /// the FRAME instead: drag the element's center to the band
    /// center (see dragBoardContent for why drags, not swipes) and
    /// tap only when the SAME frame (midX and midY) reads twice, a
    /// beat apart — two identical frames spanning a beat cannot be a
    /// scroll still bouncing, so the tap goes to the position the
    /// settled view actually holds. The band's bottom edge sits
    /// ABOVE the fixed bottom shortcut bar: that bar's strip starts
    /// ~146 pt above the window's bottom (measured from the run
    /// 41504631806 recording), so a center parked lower than
    /// maxY − 170 can sit inside the bar's footprint even when the
    /// accessibility frame calls it in-window. Returns false when
    /// the element never settles into the band; the caller fails,
    /// naming it.
    @discardableResult
    private func tapBoardElementWhenSettled(_ element: XCUIElement, app: XCUIApplication) -> Bool {
        let windowFrame = app.windows.firstMatch.frame
        let bandTop = windowFrame.minY + 120
        let bandBottom = windowFrame.maxY - 170
        let bandCenter = (bandTop + bandBottom) / 2
        var settledMidY: CGFloat?
        var settledMidX: CGFloat?
        for _ in 0..<12 {
            guard element.exists, element.frame.width > 0 else {
                settledMidY = nil
                settledMidX = nil
                app.scrollViews.firstMatch.swipeUp()
                Thread.sleep(forTimeInterval: 0.6)
                continue
            }
            let frame = element.frame
            if frame.midY > bandTop && frame.midY < bandBottom {
                if let settledMidY, let settledMidX,
                   abs(frame.midY - settledMidY) < 1, abs(frame.midX - settledMidX) < 1 {
                    element.tap()
                    return true
                }
                settledMidY = frame.midY
                settledMidX = frame.midX
                Thread.sleep(forTimeInterval: 1)
                continue
            }
            settledMidY = nil
            settledMidX = nil
            dragBoardContent(by: bandCenter - frame.midY, app: app)
            Thread.sleep(forTimeInterval: 0.6)
        }
        return false
    }

    /// Settled-position tap for SHEET chrome (chart presets, the
    /// chart's Done/close): no scrolling is involved, but the tap
    /// still follows the sheet's presentation and render churn, so
    /// the same two-agreeing-samples rule applies before tapping —
    /// sample the frame twice, a beat apart, and tap only when both
    /// samples agree. Returns false when the frame never settles.
    @discardableResult
    private func tapSheetElementWhenSettled(_ element: XCUIElement) -> Bool {
        var settledMidY: CGFloat?
        var settledMidX: CGFloat?
        for _ in 0..<8 {
            let frame = element.frame
            if frame.width > 0, let settledMidY, let settledMidX,
               abs(frame.midY - settledMidY) < 1, abs(frame.midX - settledMidX) < 1 {
                element.tap()
                return true
            }
            settledMidY = frame.width > 0 ? frame.midY : nil
            settledMidX = frame.width > 0 ? frame.midX : nil
            Thread.sleep(forTimeInterval: 1)
        }
        return false
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
        // ROUND 8 (run 41504631806, three executed attempts, all
        // failed HERE with the gate verifiably engaged): the retained
        // recording + landmark measurement name the real cause, and
        // it is the band itself. The board's fixed bottom shortcut
        // bar starts ~146 pt above the window's bottom; the servo's
        // band ran to maxY − 100, so it certified — and the receipt
        // loop then tapped — a resting center measured at ≈744 pt,
        // ~16 pt INSIDE that bar's footprint, where whether the
        // synthesized touch reaches the button or dies in the bar's
        // strip is hit-test luck (it fired from the same visual
        // position in run 41468955142; it swallowed 18 of 18 taps
        // here). The full swipes are the other half: bang-bang
        // actuation can only park this row at its travel extremes
        // (≈43 pt or ≈744 pt on this content), never mid-band, and
        // the swipe-down's rubber-band spring-back (~166 pt, still
        // completing seconds later in the recording) means the two
        // agreeing samples can certify geometry the renderer has
        // already left. The servo now (1) ends its band at
        // maxY − 170, above the bar's strip, and (2) actuates with
        // dragBoardContent — a computed drag toward the band CENTER
        // that can stop at any offset — so the row is parked clear
        // of the bar before the unchanged two-sample gate certifies
        // the position and the unchanged receipt loop taps. The cap
        // is 12 because drag corrections spend iterations like
        // stability samples do.
        let bandTop = windowFrame.minY + 120
        let bandBottom = windowFrame.maxY - 170
        let bandCenter = (bandTop + bandBottom) / 2
        var settledMidY: CGFloat?
        for _ in 0..<12 {
            let midY = plus.frame.midY
            if midY > bandTop && midY < bandBottom {
                if let settledMidY, abs(midY - settledMidY) < 1 { break }
                settledMidY = midY
                Thread.sleep(forTimeInterval: 1)
                continue
            }
            settledMidY = nil
            dragBoardContent(by: bandCenter - midY, app: app)
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
        // THIRD MECHANISM — the hero door has the stepper's disease
        // (#279 follow-up, run 41468955142 attempt 2, fast board;
        // retained 385 s recording): the hero was tapped through the
        // bare reveal() idiom — existence/hittability only, no
        // settled-position verification — immediately after the
        // scroll + stepper-write churn, and its snapshot frame did
        // not match the settled layout: the tap synthesized at
        // t≈21.3 s landed on the board's Reminders action (the fixed
        // bottom shortcut bar the stale frame extended into), a
        // Reminders page slid in at t≈22 s, and the test then spent
        // 60 s + 300 s polling for a chart close button on a surface
        // that has none. Same stale-geometry family as the stepper,
        // one door later. The door gets the stepper's treatment:
        // (1) a settled-position gate before the tap — the hero's
        // frame must read identically twice, a beat apart, inside
        // the tappable band (tapBoardElementWhenSettled); (2) a
        // PRESENTATION RECEIPT after it — the chart-sheet-only
        // close button must exist before the settle loop below is
        // entered. The receipt poll checks the close button first
        // (on the chart its evaluation may be slow, but it is the
        // signal that resolves the door), then the wrong-surface
        // markers that exist ONLY on the Reminders surface (its
        // navigation bar, its composer button — both cheap on that
        // light surface). A receipt that positively shows the wrong
        // surface recovers ONCE: navigate back and re-drive the
        // door through the same gate. A second wrong-surface
        // receipt fails here, naming the surface, instead of
        // burning the settle budget on it. A receipt that confirms
        // NEITHER surface is not a failure — on a degraded runner
        // the chart's first render can saturate the tree past the
        // receipt window — so the test falls through to the
        // query-free wait and the settle assert below, which remain
        // the authority exactly as before. No assertion changes.
        let closeButton = app.buttons["live-chart-close"]
        var chartConfirmed = false
        var landedSurface: String?
        var drivesLeft = 2
        while drivesLeft > 0 && !chartConfirmed {
            drivesLeft -= 1
            XCTAssertTrue(tapBoardElementWhenSettled(hero, app: app), "hero door never settled into the tappable band")
            var onReminders = false
            let receiptDeadline = Date().addingTimeInterval(90)
            while Date() < receiptDeadline && !chartConfirmed && !onReminders {
                if closeButton.exists { chartConfirmed = true; break }
                if app.navigationBars["Reminders"].exists || app.buttons["Set a cook reminder"].exists { onReminders = true; break }
                Thread.sleep(forTimeInterval: 1)
            }
            if onReminders {
                landedSurface = "Reminders"
                if drivesLeft > 0 {
                    // Recover once: pop the wrong surface and let the
                    // loop re-drive the door through the settle gate.
                    let back = app.navigationBars["Reminders"].buttons.firstMatch
                    if back.exists { back.tap() }
                    _ = hero.waitForExistence(timeout: 10)
                }
            } else if !chartConfirmed {
                // Unconfirmed receipt: no wrong surface seen. Do NOT
                // re-drive — while the sheet may still be presenting
                // there is no safe target for a second tap. The
                // query-free wait + settle assert below decide.
                break
            }
        }
        if let landedSurface, !chartConfirmed {
            XCTFail("hero tap landed on the \(landedSurface) surface on both drives; the chart sheet never presented")
        }
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
        // ROUND 9 (run 41534998086 attempt 1): the loop now KEEPS
        // the value it observes, and the assert below judges that
        // kept value — the previous shape re-read isHittable after
        // the loop, and on this fleet the re-read is not free or
        // neutral: each evaluation on this sheet costs ~31 s per
        // snapshot attempt, and an attempt that times out returns
        // false. Attempt 1's log shows the loop's own polls
        // completing at t≈189 / 220 / 251 with the failure asserting
        // at t≈282 — one final ~31 s timeout cadence after the last
        // poll — i.e. the condition was observed satisfied inside
        // the budget and the post-loop re-read then timed out and
        // contradicted it. Same class as the stepper receipt-guard
        // correction in round 7 (a receipt landing between the
        // waiter's last poll and a re-check is the receipt, not a
        // failure). The CONDITION and the BUDGET are unchanged: the
        // assert still fails whenever no evaluation observes the
        // close button hittable within the 300 s deadline.
        var closeHittable = false
        while !closeHittable && Date() < settleDeadline {
            closeHittable = closeButton.isHittable
            // Round 10: poll at 10 s, not 2 s. Each isHittable is a
            // full snapshot evaluation on this sheet (~28–61 s on
            // this runner class — see the round-10 note at the
            // readout below), so the sleep never gates the cadence;
            // it only spaces the polls. Fewer issued evaluations =
            // fewer chances for one to hard-error.
            if !closeHittable { Thread.sleep(forTimeInterval: 10) }
        }
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
        let delta = app.staticTexts["live-chart-ghost-delta"]
        // Round 10 (run 41543539394 attempts 2–3, head 96d491f):
        // poll the delta ALONE, then verify the readout with a
        // single evaluation. On this runner class every evaluation
        // on this sheet costs ~28–61 s, and any single one can
        // exceed XCTest's internal snapshot timeout and hard-error
        // the test ("Failed to get matching snapshots: Timed out
        // while evaluating UI query") — attempt 2 died inside the
        // readout poll after four evaluations, attempt 3 cleared
        // the readout and died inside the delta poll after four
        // more. Two sequential multi-evaluation polls double the
        // exposure, and no budget survives a hard error. The delta
        // renders INSIDE the readout card, so a single combined
        // poll on the delta (budget = the previous 240 s + 120 s)
        // followed by one readout evaluation verifies both
        // elements with a fraction of the evaluations. The assert
        // judges the value the loop observed (round 9's discipline:
        // a post-loop re-read is another ~31 s evaluation that can
        // time out and contradict a satisfied condition). Every
        // element is still asserted present; nothing is skipped.
        var deltaSeen = false
        let deltaDeadline = Date().addingTimeInterval(360)
        while !deltaSeen && Date() < deltaDeadline {
            deltaSeen = delta.exists
            if !deltaSeen { Thread.sleep(forTimeInterval: 5) }
        }
        XCTAssertTrue(deltaSeen, "live-chart-ghost-delta never appeared")
        XCTAssertTrue(readout.exists, "live-chart-readout never appeared")
        // The delta speaks in the spec's own words (§2.4), identical on
        // both platforms; the fixture's geometry makes it exactly +38.
        // Judge the label value from ONE evaluation, not two reads.
        let deltaLabel = delta.label
        XCTAssertTrue(deltaLabel.contains("+38 min vs last brisket at this point"), deltaLabel)
        // Audit gate (same pass as the hero door): these two taps sit
        // on the chart sheet and follow its presentation/render churn
        // — the preset tap re-renders the chart, and the close tap
        // follows that re-render — so both take the settled-position
        // gate before tapping. Their receipts already exist and are
        // unchanged: the readout must still exist after the preset,
        // and the overnight leg below can only proceed once the
        // sheet has actually dismissed back to the board.
        XCTAssertTrue(tapSheetElementWhenSettled(app.buttons["live-chart-preset-last2h"]), "chart preset never settled before tapping")
        XCTAssertTrue(readout.exists)
        XCTAssertTrue(tapSheetElementWhenSettled(closeButton), "chart close never settled before tapping")

        // E3: the overnight panel assembles the net; the test alert posts.
        // Audit gate: this tap used the same bare reveal() idiom as
        // the hero door, on board scroll-view content, immediately
        // after the chart sheet's dismissal churn — it takes the
        // settled-position gate too. Its receipt is the existing
        // assert below: the sent confirmation must appear.
        let testAlert = app.buttons["overnight-test-alert"]
        XCTAssertTrue(tapBoardElementWhenSettled(testAlert, app: app), "overnight test alert button never settled into the tappable band")
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
