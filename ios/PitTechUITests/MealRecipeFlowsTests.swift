import XCTest

final class MealRecipeFlowsTests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }
    // MARK: - Hardened interaction helpers (large-text CI robustness)
    //
    // Large-text UI passes run the same flows at an accessibility text size where
    // SwiftUI virtualizes off-screen rows and every layout/animation is slower.
    // Under CI load three timing fragilities have bitten these tests (rounds 6-10),
    // never a product bug: swallowed taps while a list settles, nodes that exist
    // but are not yet hittable because a sheet/nav transition is still animating,
    // and rows that only materialize once scrolled into view. The helpers below
    // make the waits poll-based and bounded instead of fixed, and verify each
    // tap's effect before moving on.
    private func reveal(_ element: XCUIElement, app: XCUIApplication, timeout: TimeInterval = 60) {
        // The scroll budget is time-based, not a fixed attempt count: at large
        // text under CI load, rows materialize slowly and a fixed budget expired
        // on slow-but-healthy runs.
        let deadline = Date().addingTimeInterval(timeout)
        for attempt in 0..<28 {
            guard Date() < deadline else { break }
            let cookScroll = app.scrollViews["cook-content"]
            let navigation = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable && $0.frame.minY.isFinite && $0.frame.width > 0 })
            let log = app.buttons["meal-log"]
            if let navigation, ["Account", "Delete account", "Remove local journal", "Report publication", "Report publisher", "Community controls", "Review synced edits"].contains(navigation.identifier) {
                // Form rows are virtualized: an off-screen field may not exist yet.
                // Scroll the foreground Form before waiting for that row's AX node.
                let top = navigation.frame.maxY + 12, bottom = app.windows.firstMatch.frame.maxY - 24
                if element.waitForExistence(timeout: 1), element.isHittable,
                   element.frame.minY >= top, element.frame.maxY <= bottom { return }
                let down = element.exists && element.frame.minY < top || !element.exists && attempt >= 9
                let origin = navigation.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: 20, dy: down ? navigation.frame.height + 32 : navigation.frame.height + 400))
                let end = origin.withOffset(CGVector(dx: 20, dy: down ? navigation.frame.height + 400 : navigation.frame.height + 32))
                start.press(forDuration: 0.01, thenDragTo: end)
                continue
            }
            if (app.scrollViews["relay-import-content"].exists || app.scrollViews["publication-content"].exists || app.scrollViews["cookbook-content"].exists || app.scrollViews["sharing-draft-content"].exists || app.scrollViews["source-review-content"].exists), let navigation {
                // The modal scroll view can also report an infinite AX frame.
                // Its finite navigation bar anchors an ordinary content-gutter swipe.
                let top = navigation.frame.maxY + 12
                let bottom = app.windows.firstMatch.frame.maxY - 24
                if element.waitForExistence(timeout: 1), element.isHittable,
                   element.frame.minY >= top, element.frame.maxY <= bottom { return }
                let down = element.exists && element.frame.minY < navigation.frame.maxY + 8
                let origin = navigation.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: 32, dy: down ? navigation.frame.height + 24 : navigation.frame.height + 324))
                let end = origin.withOffset(CGVector(dx: 32, dy: down ? navigation.frame.height + 324 : navigation.frame.height + 24))
                start.press(forDuration: 0.01, thenDragTo: end)
                continue
            }
            if cookScroll.exists, let navigation, log.exists, log.isHittable, log.frame.minY.isFinite {
                // SwiftUI can expose this scroll container with an infinite AX frame.
                // Anchor to the finite navigation bar and stay inside the content gutter,
                // above the fixed quick actions and away from the chart/slider gestures.
                let top = navigation.frame.maxY + 10
                let bottom = log.frame.minY - 20
                if element.waitForExistence(timeout: 1), element.isHittable,
                   element.frame.minY >= top, element.frame.maxY <= bottom { return }
                let down = element.exists && element.frame.minY < top
                let span = max(60, (bottom - top) * 0.55)
                let distance = element.exists ? (down ? top - element.frame.minY : element.frame.maxY - bottom) + 24 : span
                let travel = min(span, max(60, distance))
                let startY = down ? top + 24 : bottom - 24
                let endY = down ? startY + travel : startY - travel
                let origin = navigation.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: 32, dy: startY - navigation.frame.minY))
                let end = origin.withOffset(CGVector(dx: 32, dy: endY - navigation.frame.minY))
                start.press(forDuration: 0.01, thenDragTo: end)
                continue
            }
            let top = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable })?.frame.maxY ?? app.windows.firstMatch.frame.minY
            let bottom = app.tabBars.allElementsBoundByIndex.first(where: { $0.isHittable })?.frame.minY ?? app.windows.firstMatch.frame.maxY
            if element.waitForExistence(timeout: 1) && element.isHittable {
                if element.frame.minY >= top && element.frame.maxY <= bottom - 8 { return }
            }
            let scroll = cookScroll.exists && cookScroll.isHittable ? cookScroll : app.scrollViews.allElementsBoundByIndex.last(where: { $0.isHittable })
            if let scroll, scroll.exists && scroll.isHittable {
                if element.exists && element.frame.minY < top + 8 { scroll.swipeDown() } else { scroll.swipeUp() }
            } else { app.swipeUp() }
        }
        if !(element.exists && element.isHittable) {
            // Grace period: the node can exist while a sheet or navigation
            // transition is still settling (large text animates more layout).
            // Poll for hittability before failing instead of asserting blind.
            let settled = expectation(for: NSPredicate(format: "exists == YES AND hittable == YES"), evaluatedWith: element)
            _ = XCTWaiter.wait(for: [settled], timeout: 10)
        }
        XCTAssertTrue(element.exists && element.isHittable)
        let log = app.buttons["meal-log"]
        if app.scrollViews["cook-content"].exists, log.exists, log.isHittable,
           let navigation = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable }) {
            XCTAssertGreaterThanOrEqual(element.frame.minY, navigation.frame.maxY + 10)
            XCTAssertLessThanOrEqual(element.frame.maxY, log.frame.minY - 20)
        }
    }
    private func setSwitch(_ element: XCUIElement, on: Bool, app: XCUIApplication) {
        let expected = on ? "1" : "0"
        reveal(element, app: app)
        for _ in 0..<2 {
            if element.value as? String == expected { return }
            // SwiftUI exposes both a row and its contained UISwitch. Tap the
            // finite control, then verify state before any conditional retry.
            let control = app.switches.allElementsBoundByIndex.first {
                $0.identifier.isEmpty && $0.frame.width < 100 && $0.frame.height < 100 &&
                element.frame.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) && $0.isHittable
            } ?? element
            control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            let changed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", expected), object: element)
            if XCTWaiter.wait(for: [changed], timeout: 3) == .completed { return }
            reveal(element, app: app)
        }
        XCTAssertEqual(element.value as? String, expected)
    }
    /// Taps `element`, retrying while the tap has no observable effect.
    /// Under CI load a tap can be swallowed while the list settles; the next
    /// screen then never appears and the following waitForExistence burns its
    /// whole timeout on the wrong screen. Re-reveal and re-tap instead.
    /// - Parameter landed: returns true once the tap's effect is visible.
    private func tapUntilLanded(_ element: XCUIElement, app: XCUIApplication, landed: () -> Bool, attempts: Int = 3) {
        var ok = false
        for _ in 0..<attempts {
            reveal(element, app: app)
            element.tap()
            if landed() { ok = true; break }
        }
        XCTAssertTrue(ok, "Tap on '\(element.identifier)' had no observable effect after \(attempts) attempts")
    }
    /// Fails unless `element` disappears within `timeout`.
    /// Use after a sheet/modal dismissal before interacting: the animating
    /// navigation bar is not hittable, so scrolling during the transition races it.
    private func waitForGone(_ element: XCUIElement, timeout: TimeInterval = 10, file: StaticString = #file, line: UInt = #line) {
        XCTAssertTrue(element.waitForNonExistence(timeout: timeout), "Expected '\(element.identifier)' to disappear within \(timeout)s", file: file, line: line)
    }
    private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
    func testPrivateSyncConflictReviewResolvesOfflineAtBothTextSizes() {
        for large in [false, true] {
            for choice in ["Keep mine", "Use theirs", "Keep both"] {
                let app = XCUIApplication()
                app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--sync-ui-test"] + (large ? ["--screenshot-large-text"] : [])
                app.launch(); app.tabBars.buttons["More"].tap()
                let open = app.buttons["more-account"]; tapUntilLanded(open, app: app) { app.staticTexts["account-signed-in"].waitForExistence(timeout: 10) }
                XCTAssertTrue(app.staticTexts["account-signed-in"].waitForExistence(timeout: 10))
                let status = app.staticTexts["sync-status"]; reveal(status, app: app)
                XCTAssertEqual(status.label, "Conflicts to review")
                let review = app.buttons["sync-conflicts"]; tapUntilLanded(review, app: app) { app.staticTexts["Yours: Note \"Local title\""].waitForExistence(timeout: 10) }
                XCTAssertTrue(app.staticTexts["Yours: Note \"Local title\""].waitForExistence(timeout: 10))
                XCTAssertTrue(app.staticTexts["Other device: Note \"Remote title\""].waitForExistence(timeout: 10))
                if choice == "Keep mine" { capture(large ? "Native sync conflict review large text" : "Native sync conflict review") }
                let action = app.buttons[choice]; tapUntilLanded(action, app: app) { action.waitForNonExistence(timeout: 5) }
                // The conflict resolves offline; the review list empties without an upload claim.
                XCTAssertTrue(action.waitForNonExistence(timeout: 10))
                let done = app.buttons["sync-review-done"]; tapUntilLanded(done, app: app) { app.staticTexts["sync-status"].waitForExistence(timeout: 5) }
                let synced = app.staticTexts["sync-status"]; reveal(synced, app: app)
                XCTAssertEqual(synced.label, "Last synced never")
                if choice == "Keep mine" {
                    let now = app.buttons["sync-now"]; tapUntilLanded(now, app: app) { app.staticTexts["Waiting for connection"].waitForExistence(timeout: 15) }
                    XCTAssertTrue(app.staticTexts["Waiting for connection"].waitForExistence(timeout: 15))
                }
                app.terminate()
            }
        }
    }
    func testSelectedPublicationPreviewAndCookbookChangesRequireSeparateConfirmationsAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication(); app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--publishing-ui-test"] + (large ? ["--screenshot-large-text"] : []); app.launch()
            let open = app.buttons["sharing-publish"]; reveal(open, app: app); open.tap()
            let prepare = app.buttons["publication-prepare"]; reveal(prepare, app: app); prepare.tap()
            XCTAssertTrue(app.staticTexts["publication-preview-title"].waitForExistence(timeout: 15))
            let confirm = app.buttons["publication-confirm"]; reveal(confirm, app: app); XCTAssertFalse(confirm.isEnabled)
            capture(large ? "Native publication preview large text" : "Native publication preview")
            let reviewed = app.switches["publication-reviewed"]; reveal(reviewed, app: app); reviewed.tap()
            reveal(confirm, app: app); XCTAssertTrue(confirm.isEnabled); confirm.tap()
            XCTAssertTrue(app.staticTexts["publication-complete"].waitForExistence(timeout: 15)); capture(large ? "Native publication complete large text" : "Native publication complete")
            app.buttons["publication-done"].tap()
            reveal(open, app: app); open.tap(); reveal(prepare, app: app); prepare.tap()
            reveal(confirm, app: app); XCTAssertEqual(confirm.label, "Publish this update"); XCTAssertFalse(confirm.isEnabled)
            reveal(reviewed, app: app); reviewed.tap(); reveal(confirm, app: app); confirm.tap()
            XCTAssertTrue(app.staticTexts["publication-complete"].waitForExistence(timeout: 15)); app.buttons["publication-done"].tap()
            let cookbook = app.buttons["publication-test-cookbook"]; reveal(cookbook, app: app); cookbook.tap()
            let name = app.textFields["cookbook-name"]; reveal(name, app: app); XCTAssertTrue(name.waitForExistence(timeout: 15))
            let publicToggle = app.switches["cookbook-public"]; reveal(publicToggle, app: app); publicToggle.tap()
            let preview = app.buttons["cookbook-preview"]; reveal(preview, app: app); preview.tap()
            let save = app.buttons["cookbook-save"]; reveal(save, app: app); capture(large ? "Native cookbook opt-in preview large text" : "Native cookbook opt-in preview"); save.tap()
            let revision = app.staticTexts["Public · revision 2 · anonymous"]; reveal(revision, app: app); XCTAssertTrue(revision.waitForExistence(timeout: 15))
            let remove = app.buttons["Remove from public access"]; reveal(remove, app: app); remove.tap()
            let removeConfirm = app.buttons.matching(identifier: "cookbook-unpublish-confirm").firstMatch; XCTAssertTrue(removeConfirm.waitForExistence(timeout: 10)); removeConfirm.tap()
            let removed = app.staticTexts["Removed from public access"]; reveal(removed, app: app); XCTAssertTrue(removed.waitForExistence(timeout: 15))
            let edit = app.buttons["cookbook-edit-dddddddd-dddd-4ddd-dddd-dddddddddddd"]; reveal(edit, app: app); edit.tap()
            let createDraft = app.buttons["cookbook-create-editing-draft"]; reveal(createDraft, app: app)
            capture(large ? "Native owner editing preview large text" : "Native owner editing preview"); createDraft.tap()
            let newTitle = app.textFields["sharing-title"]; reveal(newTitle, app: app); XCTAssertTrue(newTitle.waitForExistence(timeout: 15))
            reveal(open, app: app); open.tap(); reveal(prepare, app: app); prepare.tap()
            reveal(confirm, app: app); XCTAssertEqual(confirm.label, "Publish this update"); XCTAssertFalse(confirm.isEnabled)
            capture(large ? "Native owner update preview large text" : "Native owner update preview")
            reveal(reviewed, app: app); reviewed.tap(); reveal(confirm, app: app); confirm.tap()
            XCTAssertTrue(app.staticTexts["publication-complete"].waitForExistence(timeout: 15))
            app.terminate()
        }
    }
    func testSignedOutDiscoverySavesPrivateCopiesAndStartsANewMealAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication();app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--discovery-ui-test"] + (large ? ["--screenshot-large-text"] : []);app.launch()
            app.tabBars.buttons["Discover"].tap()
            let item = app.buttons["discover-open-12345678-1234-4234-8234-123456789abc"];tapUntilLanded(item, app: app) { app.staticTexts["public-title"].waitForExistence(timeout: 15) }
            let title = app.staticTexts["public-title"];XCTAssertTrue(title.waitForExistence(timeout: 15));capture(large ? "Native public detail large text" : "Native public detail")
            let save = app.buttons["public-save"];tapUntilLanded(save, app: app) { app.buttons["pack-save-recipes"].waitForExistence(timeout: 20) }
            let confirm = app.buttons["pack-save-recipes"];XCTAssertTrue(confirm.waitForExistence(timeout: 20));reveal(confirm, app: app);XCTAssertEqual(confirm.label, "Save 2 recipes")
            capture(large ? "Native public selective preview large text" : "Native public selective preview");tapUntilLanded(confirm, app: app) { app.alerts["Cook archive"].waitForExistence(timeout: 20) }
            XCTAssertTrue(app.alerts["Cook archive"].waitForExistence(timeout: 20));app.alerts.buttons["OK"].tap()
            app.tabBars.buttons["Recipes"].tap();XCTAssertTrue(app.staticTexts["Oak-smoked pork shoulder"].firstMatch.waitForExistence(timeout: 10))
            app.staticTexts["Oak-smoked pork shoulder"].firstMatch.tap()
            let review = app.buttons["source-review-open"]; reveal(review, app: app); review.tap()
            let check = app.buttons["source-review-check"]; reveal(check, app: app); check.tap()
            let member = app.buttons["source-review-member-0"]; reveal(member, app: app); member.tap()
            let unchanged = app.staticTexts["Ingredients · unchanged"]; reveal(unchanged, app: app); XCTAssertTrue(unchanged.waitForExistence(timeout: 15))
            capture(large ? "Native source change review large text" : "Native source change review")
            app.tabBars.buttons["Discover"].tap()
            let cook = app.buttons["public-cook"];tapUntilLanded(cook, app: app) { app.buttons["pack-save-recipes"].waitForExistence(timeout: 20) }
            let start = app.buttons["pack-save-recipes"];XCTAssertTrue(start.waitForExistence(timeout: 20));reveal(start, app: app);XCTAssertEqual(start.label, "Save 2 recipes & start meal");tapUntilLanded(start, app: app) { app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "food-card-")).firstMatch.waitForExistence(timeout: 20) }
            let food = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "food-card-"));XCTAssertTrue(food.firstMatch.waitForExistence(timeout: 20))
            capture(large ? "Native public cook large text" : "Native public cook");app.terminate()
        }
    }
    func testAccountCodeFlowAndSignOutKeepLocalCookbookAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--account-ui-test"] + (large ? ["--screenshot-large-text"] : [])
            app.launch(); app.tabBars.buttons["More"].tap()
            let open = app.buttons["more-account"]; reveal(open, app: app); open.tap()
            let email = app.textFields["account-email"]; XCTAssertTrue(email.waitForExistence(timeout: 10)); email.tap(); email.typeText("cook@example.com")
            app.buttons["Hide keyboard"].tap()
            let start = app.buttons["account-start"]; reveal(start, app: app); start.tap()
            let code = app.textFields["account-code"]; reveal(code, app: app); XCTAssertTrue(code.waitForExistence(timeout: 10)); code.tap(); code.typeText("123456")
            app.buttons["Hide keyboard"].tap()
            let verify = app.buttons["account-verify"]; reveal(verify, app: app); verify.tap()
            let signedIn = app.staticTexts["account-signed-in"]
            // In large text the Form virtualizes off-screen rows, so the signed-in
            // marker only materializes once scrolled into view: scroll it into view
            // instead of waiting on a possibly-unrealized node. reveal() polls
            // (~18 attempts), covering the fixture-backed verify latency too.
            // (The old retry-on-consumed-tap guard is dropped: the message row it
            // read is itself virtualized off-screen in large text, so the guard
            // could never fire reliably.)
            reveal(signedIn, app: app)
            capture(large ? "Native account signed in large text" : "Native account signed in")
            let signOut = app.buttons["Sign out"]; reveal(signOut, app: app); signOut.tap()
            let confirm = app.buttons["account-confirm-signout"].firstMatch; XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.tap()
            reveal(start, app: app); XCTAssertTrue(start.waitForExistence(timeout: 10))
            capture(large ? "Native account signed out large text" : "Native account signed out")
            app.buttons["Done"].tap(); app.tabBars.buttons["Meals"].tap()
            XCTAssertFalse(app.staticTexts["Signed in"].exists)
            app.terminate()
        }
    }
    func testAccountDeletionReviewRecoveryAndCompletionAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--account-deletion-ui-test"] + (large ? ["--screenshot-large-text"] : [])
            app.launch(); app.tabBars.buttons["More"].tap()
            let open = app.buttons["more-account"]; reveal(open, app: app); open.tap()
            let deletion = app.buttons["account-delete"]; reveal(deletion, app: app); deletion.tap()
            let acknowledgment = app.switches["account-deletion-acknowledge"].firstMatch
            setSwitch(acknowledgment, on: true, app: app)
            let typed = app.textFields["account-deletion-confirmation"]; reveal(typed, app: app); typed.tap(); typed.typeText("DELETE")
            app.buttons["Hide keyboard"].tap()
            let submit = app.buttons["account-deletion-submit"]; reveal(submit, app: app)
            XCTAssertTrue(submit.isEnabled)
            capture(large ? "Account deletion review large text" : "Account deletion review")
            submit.tap()
            let status = app.staticTexts["account-deletion-status"]; XCTAssertTrue(status.waitForExistence(timeout: 10)); reveal(status, app: app)
            XCTAssertTrue(status.label.contains("queued"))
            let recover = app.buttons["account-deletion-recover"]; reveal(recover, app: app); recover.tap()
            let check = app.buttons["account-deletion-check"]; reveal(check, app: app); check.tap()
            let finished = app.buttons["Close completed request"]; reveal(finished, app: app)
            capture(large ? "Account deletion complete large text" : "Account deletion complete")
            finished.tap()
            let email = app.textFields["account-email"]
            if !email.waitForExistence(timeout: 5), finished.exists, finished.isEnabled {
                // The dismissal tap can land while the button is still disabled from
                // the status check; retry once the request has settled.
                finished.tap()
            }
            // In large text the Form virtualizes off-screen rows: scroll the field
            // into view (which materializes it) instead of waiting on a
            // possibly-unrealized node.
            reveal(email, app: app)
            app.terminate()
        }
    }
    func testLocalJournalRemovalConfirmationAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            // The account entry point only exists when accounts are enabled.
            app.launchArguments = ["--ui-test-reset", "--account-ui-test"] + (large ? ["--screenshot-large-text"] : [])
            app.launch(); app.tabBars.buttons["More"].tap()
            let open = app.buttons["more-account"]; tapUntilLanded(open, app: app) { app.buttons["local-journal-remove"].waitForExistence(timeout: 10) }
            let remove = app.buttons["local-journal-remove"]; tapUntilLanded(remove, app: app) { app.switches["local-journal-acknowledge"].firstMatch.waitForExistence(timeout: 10) }
            let acknowledgment = app.switches["local-journal-acknowledge"].firstMatch
            setSwitch(acknowledgment, on: true, app: app)
            let typed = app.textFields["local-journal-confirmation"]; reveal(typed, app: app); typed.tap(); typed.typeText("REMOVE")
            app.buttons["Hide keyboard"].tap()
            let submit = app.buttons["local-journal-submit"]; reveal(submit, app: app)
            XCTAssertTrue(submit.isEnabled)
            capture(large ? "Local journal removal review large text" : "Local journal removal review")
            tapUntilLanded(submit, app: app) { app.navigationBars["Remove local journal"].waitForNonExistence(timeout: 5) }
            let message = app.staticTexts["account-message"]
            // The submit dismisses the "Remove local journal" sheet before the
            // Account screen settles: the animating nav bar is not hittable, so
            // wait out the transition before scrolling. In large text the Account
            // Form virtualizes off-screen rows, so scroll the message into view
            // (which materializes it) before waiting on it.
            waitForGone(app.navigationBars["Remove local journal"])
            reveal(message, app: app)
            XCTAssertTrue(message.waitForExistence(timeout: 10))
            XCTAssertTrue(message.label.contains("Local journal removed"))
            capture(large ? "Local journal removed large text" : "Local journal removed")
            app.buttons["Done"].tap(); app.tabBars.buttons["Meals"].tap()
            XCTAssertTrue(app.staticTexts["No finished cooks yet"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }
    func testAnonymousReportRecoveryAndPublisherBlockAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--community-controls-ui-test"] + (large ? ["--screenshot-large-text"] : [])
            app.launch(); app.tabBars.buttons["Discover"].tap()
            let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "discover-open-")).firstMatch
            XCTAssertTrue(source.waitForExistence(timeout: 10)); tapUntilLanded(source, app: app) { app.buttons["public-report"].waitForExistence(timeout: 10) }
            let report = app.buttons["public-report"]; tapUntilLanded(report, app: app) { app.textViews["report-details"].waitForExistence(timeout: 10) }
            let details = app.textViews["report-details"]; reveal(details, app: app); details.tap(); details.typeText("Synthetic private review request")
            app.buttons["Hide keyboard"].tap(); let submit = app.buttons["report-submit"]; tapUntilLanded(submit, app: app) { app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Recover or review the saved request")).firstMatch.waitForExistence(timeout: 10) }
            let saved = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Recover or review the saved request")).firstMatch
            XCTAssertTrue(saved.waitForExistence(timeout: 10)); reveal(saved, app: app)
            capture(large ? "Community report saved large text" : "Community report saved")
            app.navigationBars["Report publication"].buttons["Cancel"].tap()
            app.tabBars.buttons["More"].tap(); let manage = app.buttons["more-community-controls"]
            let retry = app.buttons["community-retry"]
            // Under CI load the More-list tap can be swallowed while the list settles:
            // retry until the Community Controls screen actually lands.
            tapUntilLanded(manage, app: app) { retry.waitForExistence(timeout: 5) }
            reveal(retry, app: app); tapUntilLanded(retry, app: app) { app.buttons["Check report status"].firstMatch.waitForExistence(timeout: 10) }
            let status = app.buttons["Check report status"].firstMatch; XCTAssertTrue(status.waitForExistence(timeout: 10)); tapUntilLanded(status, app: app) { app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Reviewing")).firstMatch.waitForExistence(timeout: 10) }
            let reviewing = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Reviewing")).firstMatch; XCTAssertTrue(reviewing.waitForExistence(timeout: 10)); reveal(reviewing, app: app)
            capture(large ? "Community report status large text" : "Community report status")
            app.tabBars.buttons["Discover"].tap(); let block = app.buttons["public-block"]; tapUntilLanded(block, app: app) { app.buttons["discover-search"].waitForExistence(timeout: 10) }
            let search = app.buttons["discover-search"]; reveal(search, app: app); search.tap()
            XCTAssertTrue(app.staticTexts["discover-empty"].waitForExistence(timeout: 10))
            capture(large ? "Publisher blocked large text" : "Publisher blocked")
            app.tabBars.buttons["More"].tap(); let unblock = app.buttons["Unblock publisher"].firstMatch; reveal(unblock, app: app); unblock.tap()
            app.tabBars.buttons["Discover"].tap(); reveal(search, app: app); search.tap(); XCTAssertTrue(source.waitForExistence(timeout: 10))
            app.terminate()
        }
    }
    func testCodeImportRejectsForeignLinksBeforeAnyImportAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication(); app.launchArguments = ["--ui-test-reset", "--ui-test-empty"] + (large ? ["--screenshot-large-text"] : []); app.launch()
            app.tabBars.buttons["More"].tap()
            let open = app.buttons["more-relay-import"]; reveal(open, app: app); open.tap()
            let input = app.textFields["relay-code-input"]; XCTAssertTrue(input.waitForExistence(timeout: 10)); input.tap(); input.typeText("https://foreign.invalid/s/ABCD-1234-5678-9EF0")
            let submit = app.keyboards.buttons["Go"]; XCTAssertTrue(submit.waitForExistence(timeout: 5)); submit.tap()
            XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
            let message = app.staticTexts["relay-message"]; XCTAssertTrue(message.waitForExistence(timeout: 10)); reveal(message, app: app)
            XCTAssertEqual(message.label, "Only PitTech share links are supported.")
            XCTAssertFalse(app.navigationBars["Cook Pack"].exists)
            capture(large ? "Native code import large text" : "Native code import")
            app.terminate()
        }
    }
    func testSavedRecipeCardPreviewAndCanceledShareKeepDraftAtBothTextSizes() {
        for large in [false,true] {
            let app = XCUIApplication();app.launchArguments = ["--ui-test-reset"] + (large ? ["--screenshot-large-text"] : []);app.launch()
            app.tabBars.buttons["Recipes"].tap()
            let recipe = app.staticTexts["Chicken"].firstMatch;reveal(recipe,app:app);recipe.tap()
            let share = app.buttons["recipe-share"];reveal(share,app:app);share.tap()
            let preview = app.buttons["sharing-preview-card"];reveal(preview,app:app);preview.tap()
            let image = app.images["share-card-preview"];XCTAssertTrue(image.waitForExistence(timeout:20))
            capture(large ? "Native share card preview large text" : "Native share card preview")
            let systemShare = app.buttons["card-share"];reveal(systemShare,app:app);systemShare.tap()
            let close = app.buttons["Close"].firstMatch
            if close.waitForExistence(timeout:5) { close.tap() } else { app.swipeDown() }
            let back = app.buttons["card-close"];reveal(back,app:app);back.tap()
            XCTAssertTrue(app.textFields["sharing-title"].waitForExistence(timeout:10))
            app.terminate()
        }
    }
    func testOneGuideToggleControlsAllSectionsAndPersistsAtStandardAndLargeText() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-guide"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            let meal = app.staticTexts["Guided meal"].firstMatch; reveal(meal, app: app); meal.tap()
            let toggle = app.switches["show-guide"]; reveal(toggle, app: app)
            XCTAssertEqual(toggle.value as? String, "0"); setSwitch(toggle, on: true, app: app)
            let reference = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "guide-reference-", "Guide · ")).firstMatch
            XCTAssertTrue(reference.waitForExistence(timeout: 10)); reveal(reference, app: app)
            capture(large ? "Native Show guide Live large text" : "Native Show guide Live")
            let timeline = app.segmentedControls.buttons["Timeline"]; reveal(timeline, app: app); timeline.tap()
            XCTAssertTrue(reference.waitForExistence(timeout: 10)); reveal(reference, app: app)
            capture(large ? "Native Show guide Timeline large text" : "Native Show guide Timeline")
            let charts = app.segmentedControls.buttons["Charts"]; reveal(charts, app: app); charts.tap()
            let legend = app.staticTexts["guide-chart-legend"]; reveal(legend, app: app)
            capture(large ? "Native Show guide Charts large text" : "Native Show guide Charts")
            let plot = app.descendants(matching: .any)["temperature-plot"].firstMatch; reveal(plot, app: app)
            plot.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.45, thenDragTo: plot.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5)))
            let sourceTime = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Author source time · ")).firstMatch
            XCTAssertTrue(sourceTime.waitForExistence(timeout: 10), "A deliberate hold-and-drag still selects measured source data.")
            reveal(toggle, app: app); setSwitch(toggle, on: false, app: app)
            app.terminate()
            app.launchArguments = ["--screenshot-no-ads"] + (large ? ["--screenshot-large-text"] : [])
            app.launch(); let reopened = app.staticTexts["Guided meal"].firstMatch; reveal(reopened, app: app); reopened.tap()
            let restored = app.switches["show-guide"]; reveal(restored, app: app); XCTAssertEqual(restored.value as? String, "0")
            app.terminate()
        }
    }
    func testCookPackPreviewSavesOneRecipeWithoutImportingHistoryAtStandardAndLargeText() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-pack"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            XCTAssertTrue(app.navigationBars["Cook Pack"].waitForExistence(timeout: 20))
            if large { XCTAssertGreaterThan(app.staticTexts["Choose what to save"].frame.height, 40, "Exercise the sheet at the requested accessibility text size.") }
            let choices = app.switches.matching(NSPredicate(format: "identifier BEGINSWITH %@", "pack-select-"))
            XCTAssertTrue(choices.firstMatch.waitForExistence(timeout: 10))
            let first = choices.firstMatch; reveal(first, app: app)
            setSwitch(first, on: false, app: app)
            let save = app.buttons["pack-save-recipes"]; reveal(save, app: app)
            XCTAssertEqual(save.label, "Save 1 recipe")
            capture(large ? "Native Cook Pack selective preview large text" : "Native Cook Pack selective preview")
            save.tap()
            XCTAssertTrue(app.alerts["Cook archive"].waitForExistence(timeout: 20)); app.alerts.buttons["OK"].tap()
            app.tabBars.buttons["Recipes"].tap()
            XCTAssertFalse(app.staticTexts["Sharing drafts"].exists)
            capture(large ? "Native Cook Pack saved recipe large text" : "Native Cook Pack saved recipe")
            app.terminate()
        }
    }
    func testBetaConnectionOnboardingRejectsInsecureServersAtStandardAndLargeText() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            app.tabBars.buttons["More"].tap()
            app.buttons["more-devices"].tap()
            let add = app.buttons["telemetry-add"]
            reveal(add, app: app)
            capture(large ? "Native Beta connections large text" : "Native Beta connections")
            add.tap()
            let server = app.textFields["telemetry-ha-server"]
            XCTAssertTrue(server.waitForExistence(timeout: 10))
            XCTAssertFalse(app.buttons["telemetry-ha-authorize"].isEnabled)
            XCTAssertFalse(app.buttons["telemetry-connect"].isEnabled)
            capture(large ? "Native HA onboarding large text" : "Native HA onboarding")
            server.tap(); server.typeText("http://example.com")
            XCTAssertFalse(app.buttons["telemetry-ha-authorize"].isEnabled)
            app.navigationBars["Connect · Beta"].buttons["Cancel"].tap()
            XCTAssertTrue(add.waitForExistence(timeout: 5))
            app.terminate()
        }
    }
    func testRecordedCookImportDoesNotSaveRecipesAtStandardAndLargeText() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-pack", "--ui-test-recorded-pack"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            XCTAssertTrue(app.navigationBars["Cook Pack"].waitForExistence(timeout: 20))
            let add = app.buttons["pack-add-history"]; reveal(add, app: app)
            if large { let heading = app.staticTexts["Choose what to save"]; reveal(heading, app: app); XCTAssertGreaterThan(heading.frame.height, 40); reveal(add, app: app) }
            capture(large ? "Native recorded meal choice large text" : "Native recorded meal choice")
            add.tap()
            XCTAssertTrue(app.alerts["Cook archive"].waitForExistence(timeout: 20)); app.alerts.buttons["OK"].tap()
            app.tabBars.buttons["Meals"].tap()
            XCTAssertTrue(app.staticTexts["Pork and potatoes"].firstMatch.waitForExistence(timeout: 10))
            capture(large ? "Native imported history large text" : "Native imported history")
            app.tabBars.buttons["Recipes"].tap()
            XCTAssertTrue(app.staticTexts["Your cookbook starts here"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }
    func testSavedRecipesComposeHistoricalCheckAndFinishWithoutForcedSaving() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-reset"]; app.launch()
        app.tabBars.buttons["Recipes"].tap()
        app.buttons["recipe-compose"].tap()
        for name in ["Chicken", "Mac and cheese", "Pork"] {
            let toggle = app.buttons["Add " + name + " to meal"]; reveal(toggle, app: app); toggle.tap()
            expectation(for: NSPredicate(format: "selected == YES"), evaluatedWith: toggle)
            waitForExpectations(timeout: 5)
        }
        let start = app.buttons["recipe-start-meal"]; reveal(start, app: app); start.tap()
        let foods = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "food-card-"))
        XCTAssertTrue(foods.firstMatch.waitForExistence(timeout: 15))
        // Scroll views expose visible cards to accessibility, so collect stable identities across the viewport.
        var seenFoods = Set<String>()
        for _ in 0..<6 {
            seenFoods.formUnion(foods.allElementsBoundByIndex.map(\.identifier))
            if seenFoods.count == 3 { break }
            app.swipeUp()
        }
        XCTAssertEqual(seenFoods.count, 3)
        reveal(foods.firstMatch, app: app)
        capture("Native composed meal")
        let reminders = app.buttons["Reminders"].firstMatch
        // This action is in the fixed horizontal toolbar, outside cook-content.
        // Page scrolling cannot reveal a clipped quick action.
        for _ in 0..<4 {
            if reminders.exists && reminders.isHittable { break }
            let navigation = app.navigationBars["Meal"]
            let origin = navigation.coordinate(withNormalizedOffset: .zero)
            let y = app.buttons["meal-log"].frame.midY - navigation.frame.minY
            origin.withOffset(CGVector(dx: navigation.frame.width - 32, dy: y))
                .press(forDuration: 0.01, thenDragTo: origin.withOffset(CGVector(dx: 32, dy: y)))
        }
        XCTAssertTrue(reminders.exists && reminders.isHittable); reminders.tap()
        let suggestion = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Chicken · last time")).firstMatch
        reveal(suggestion, app: app); suggestion.tap()
        let saveReminder = app.buttons["reminder-rule-save"]
        saveReminder.tap()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 10) { allow.tap() }
        expectation(for: NSPredicate(format: "hittable == NO"), evaluatedWith: saveReminder)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.staticTexts["Chicken · waiting"].waitForExistence(timeout: 15))
        capture("Native explicit historical reminder")
        let back = app.navigationBars["Reminders"].buttons["BackButton"]
        XCTAssertTrue(back.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "hittable == YES"), evaluatedWith: back)
        waitForExpectations(timeout: 10)
        back.tap()
        expectation(for: NSPredicate(format: "exists == NO"), evaluatedWith: app.navigationBars["Reminders"])
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.navigationBars["Meal"].waitForExistence(timeout: 10))
        let finish = app.buttons["finish-meal"]; reveal(finish, app: app); finish.tap()
        XCTAssertTrue(app.alerts["Finish meal?"].waitForExistence(timeout: 5))
        app.alerts.buttons["Finish meal"].tap()
        XCTAssertTrue(app.buttons["Leave without saving"].waitForExistence(timeout: 15))
        capture("Native finish and recipe choices")
        app.buttons["Leave without saving"].tap()
        XCTAssertTrue(app.staticTexts["Finished"].waitForExistence(timeout: 10))
    }
    func testLargeTextStartsAnEmptyMealAndFinishesWithoutInventedMilestones() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--screenshot-large-text"]; app.launch()
        let start = app.buttons["Start meal"]; XCTAssertTrue(start.waitForExistence(timeout: 15)); start.tap()
        app.buttons["start-meal-save"].tap()
        XCTAssertTrue(app.buttons["finish-meal"].waitForExistence(timeout: 15))
        capture("Native large text simple start")
        reveal(app.buttons["finish-meal"], app: app); app.buttons["finish-meal"].tap(); app.alerts.buttons["Finish meal"].tap()
        XCTAssertTrue(app.buttons["Leave without saving"].waitForExistence(timeout: 15)); reveal(app.buttons["Leave without saving"], app: app); app.buttons["Leave without saving"].tap()
        XCTAssertTrue(app.staticTexts["Finished"].waitForExistence(timeout: 10))
    }
}
