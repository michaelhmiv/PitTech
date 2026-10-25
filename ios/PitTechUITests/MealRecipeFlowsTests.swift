import XCTest

final class MealRecipeFlowsTests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }
    private func reveal(_ element: XCUIElement, app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        // 24 attempts: the signed-in Account form grew with the Connect-your-AI
        // and Device actions sections, so its rows now sit deeper than the old
        // 18-attempt / halfway-flip search could reach at large text. A missing
        // (virtualized) row gives no direction hint, so the Form branch below
        // sweeps deterministically: to the top first, then down the form.
        var formPhase = 0 // 0 = seek top, 1 = sweep down, 2 = sweep back up
        var formFingerprint: CGFloat?
        for attempt in 0..<24 {
            let cookScroll = app.scrollViews["cook-content"]
            let navigation = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable && $0.frame.minY.isFinite && $0.frame.width > 0 })
            let log = app.buttons["meal-log"]
            if let navigation, ["Account", "Delete account", "Remove local journal", "Report publication", "Report publisher", "Community controls", "Review synced edits"].contains(navigation.identifier) {
                // Form rows are virtualized: an off-screen field may not exist yet.
                // Scroll the foreground Form before waiting for that row's AX node.
                let top = navigation.frame.maxY + 12, bottom = app.windows.firstMatch.frame.maxY - 24
                if element.waitForExistence(timeout: 1), element.isHittable,
                   element.frame.minY >= top, element.frame.maxY <= bottom { return }
                let down: Bool
                if element.exists {
                    down = element.frame.minY < top
                } else {
                    // Fingerprint the visible rows: when a drag stops moving
                    // them, that end of the form is reached and the sweep
                    // changes phase instead of guessing by attempt count.
                    let cells = app.cells.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.minY.isFinite && $0.frame.height > 0 }
                    let fingerprint = cells.map { $0.frame.minY }.reduce(0, +)
                    let moved = formFingerprint.map { $0 != fingerprint } ?? true
                    formFingerprint = fingerprint
                    if formPhase == 0, attempt > 0, !moved { formPhase = 1 }
                    else if formPhase == 1, !moved { formPhase = 2 }
                    down = formPhase != 1
                }
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
            if let scroll, scroll.exists && scroll.isHittable, scroll.frame.height.isFinite, scroll.frame.height > 0 {
                // Servo drags, not full-screen flings: a fling's displacement exceeds the
                // nav-to-tab acceptance window for tall large-text rows, so the top-edge
                // direction rule limit-cycles (up, down, up, ...) without ever landing
                // in-window. Capping each step at the remaining distance (mirroring the
                // cook-content branch) makes overshoot impossible, so the same direction
                // rule converges instead of oscillating.
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
        if !element.exists || !element.isHittable {
            capture("Unreachable requested action")
            print("Requested element: \(element)")
            print(app.debugDescription)
        }
        XCTAssertTrue(element.exists && element.isHittable, "The requested action must be reachable.", file: file, line: line)
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
    private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
    func testPrivateSyncConflictReviewResolvesOfflineAtBothTextSizes() {
        for large in [false, true] {
            for choice in ["Keep mine", "Use theirs", "Keep both"] {
                let app = XCUIApplication()
                app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--sync-ui-test"] + (large ? ["--screenshot-large-text"] : [])
                app.launch(); app.tabBars.buttons["More"].tap()
                let open = app.buttons["more-account"]; reveal(open, app: app); open.tap()
                XCTAssertTrue(app.staticTexts["account-signed-in"].waitForExistence(timeout: 10))
                let status = app.staticTexts["sync-status"]; reveal(status, app: app)
                XCTAssertEqual(status.label, "Conflicts to review")
                let review = app.buttons["sync-conflicts"]; reveal(review, app: app); review.tap()
                // PL-16: both versions are shown as content — the note's own
                // fields under "Yours" and "Other device" — not summaries.
                let yours = app.staticTexts["Yours"]; reveal(yours, app: app)
                XCTAssertTrue(yours.waitForExistence(timeout: 10))
                let localTitle = app.staticTexts["Local title"]; reveal(localTitle, app: app)
                XCTAssertTrue(localTitle.waitForExistence(timeout: 10))
                let theirs = app.staticTexts["Other device"]; reveal(theirs, app: app)
                XCTAssertTrue(theirs.waitForExistence(timeout: 10))
                let remoteTitle = app.staticTexts["Remote title"]; reveal(remoteTitle, app: app)
                XCTAssertTrue(remoteTitle.waitForExistence(timeout: 10))
                // Slice 7 (PL-10): the review states the concrete consequence for
                // the actual pending set (one conflict in this fixture).
                let consequence = app.staticTexts["sync-conflict-consequence"]; reveal(consequence, app: app)
                XCTAssertTrue(consequence.label.contains("replaces this device's unsynced changes to 1 item"), consequence.label)
                // PL-16: the consequence is printed on the choice itself —
                // immediate on this device, uploads on the next sync.
                let mineNote = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Keep mine applies on this device right away")).firstMatch
                reveal(mineNote, app: app)
                XCTAssertTrue(mineNote.label.contains("uploads on the next sync"), mineNote.label)
                if choice == "Keep mine" { capture(large ? "Native sync conflict review large text" : "Native sync conflict review") }
                let action = app.buttons[choice]; reveal(action, app: app); action.tap()
                // The conflict resolves offline; the review list empties without an upload claim.
                XCTAssertTrue(action.waitForNonExistence(timeout: 10))
                let done = app.buttons["sync-review-done"]; reveal(done, app: app); done.tap()
                let synced = app.staticTexts["sync-status"]; reveal(synced, app: app)
                XCTAssertEqual(synced.label, "Last synced never")
                // Slice 7 (PL-10): with no completed sync, the status row's body
                // carries the first-connection state instead of a bare status.
                let detail = app.staticTexts["sync-status-detail"]; reveal(detail, app: app)
                XCTAssertTrue(detail.label.contains("Waiting for first connection"), detail.label)
                if choice == "Keep mine" {
                    let now = app.buttons["sync-now"]; reveal(now, app: app); now.tap()
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
            // PL-18: the published state offers the landing where the thing went.
            XCTAssertTrue(app.buttons["publication-open-cookbook"].exists)
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
            let item = app.buttons["discover-open-12345678-1234-4234-8234-123456789abc"];reveal(item, app: app);item.tap()
            let title = app.staticTexts["public-title"];XCTAssertTrue(title.waitForExistence(timeout: 15));capture(large ? "Native public detail large text" : "Native public detail")
            let save = app.buttons["public-save"];reveal(save, app: app);save.tap()
            let confirm = app.buttons["pack-save-recipes"]
            // In large text the pack-save sheet can be slow to present under CI
            // load: scroll-poll for the button (which materializes it) before the
            // existence wait, mirroring the journal-removal virtualization fix.
            reveal(confirm, app: app);XCTAssertTrue(confirm.waitForExistence(timeout: 20));XCTAssertEqual(confirm.label, "Save 2 recipes")
            capture(large ? "Native public selective preview large text" : "Native public selective preview");confirm.tap()
            XCTAssertTrue(app.alerts["Cook archive"].waitForExistence(timeout: 20));app.alerts.buttons["OK"].tap()
            // PL-19: after Save, the detail's primary is "Open in Recipes".
            let openRecipes = app.buttons["public-open-recipes"];reveal(openRecipes, app: app);XCTAssertTrue(openRecipes.exists)
            app.tabBars.buttons["Recipes"].tap();XCTAssertTrue(app.staticTexts["Oak-smoked pork shoulder"].firstMatch.waitForExistence(timeout: 10))
            app.staticTexts["Oak-smoked pork shoulder"].firstMatch.tap()
            let review = app.buttons["source-review-open"]; reveal(review, app: app); review.tap()
            let check = app.buttons["source-review-check"]; reveal(check, app: app); check.tap()
            let member = app.buttons["source-review-member-0"]; reveal(member, app: app); member.tap()
            let unchanged = app.staticTexts["Ingredients · unchanged"]; reveal(unchanged, app: app); XCTAssertTrue(unchanged.waitForExistence(timeout: 15))
            capture(large ? "Native source change review large text" : "Native source change review")
            app.tabBars.buttons["Discover"].tap()
            let cook = app.buttons["public-cook"];reveal(cook, app: app);cook.tap()
            let start = app.buttons["pack-save-recipes"];XCTAssertTrue(start.waitForExistence(timeout: 20));reveal(start, app: app);XCTAssertEqual(start.label, "Save 2 recipes & set up meal");start.tap()
            // PL-19 / PL-10 (#172): "Cook this" lands on the unified Start
            // Cook review — the meal starts only when the setup is
            // confirmed there with "Begin preparation".
            let confirmStart = app.buttons["start-meal-save"];XCTAssertTrue(confirmStart.waitForExistence(timeout: 20))
            XCTAssertTrue(app.staticTexts["cook-source"].waitForExistence(timeout: 10));confirmStart.tap()
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
            // PL-14 (#172): the sign-in form now sits below the Session
            // section, so — like the pack-save fix above — scroll-poll the
            // field (which materializes its virtualized row) before the
            // existence wait instead of waiting on an unrealized node.
            let email = app.textFields["account-email"]; reveal(email, app: app); XCTAssertTrue(email.waitForExistence(timeout: 10))
            XCTAssertFalse(app.buttons["account-apple-signin"].exists)
            email.tap(); email.typeText("cook@example.com")
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
    func testAppleSignInAndAttachLinkProvidersAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--account-apple-ui-test"] + (large ? ["--screenshot-large-text"] : [])
            app.launch(); app.tabBars.buttons["More"].tap()
            let open = app.buttons["more-account"]; reveal(open, app: app); open.tap()
            let apple = app.buttons["account-apple-signin"]; reveal(apple, app: app)
            XCTAssertTrue(apple.waitForExistence(timeout: 10))
            capture(large ? "Native account Apple sign in large text" : "Native account Apple sign in")
            apple.tap()
            let signedIn = app.staticTexts["account-signed-in"]; reveal(signedIn, app: app)
            let email = app.staticTexts["account-auth-email"]; reveal(email, app: app)
            XCTAssertTrue(email.waitForExistence(timeout: 10))
            let attach = app.buttons["account-apple-attach"]; reveal(attach, app: app)
            XCTAssertTrue(attach.waitForExistence(timeout: 10))
            capture(large ? "Native account attach Apple large text" : "Native account attach Apple")
            attach.tap()
            let linked = app.staticTexts["account-auth-apple"]; reveal(linked, app: app)
            XCTAssertTrue(linked.waitForExistence(timeout: 10))
            capture(large ? "Native account Apple linked large text" : "Native account Apple linked")
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
            let open = app.buttons["more-account"]; reveal(open, app: app); open.tap()
            let remove = app.buttons["local-journal-remove"]; reveal(remove, app: app); remove.tap()
            let acknowledgment = app.switches["local-journal-acknowledge"].firstMatch
            setSwitch(acknowledgment, on: true, app: app)
            let typed = app.textFields["local-journal-confirmation"]; reveal(typed, app: app); typed.tap(); typed.typeText("REMOVE")
            app.buttons["Hide keyboard"].tap()
            let submit = app.buttons["local-journal-submit"]; reveal(submit, app: app)
            XCTAssertTrue(submit.isEnabled)
            capture(large ? "Local journal removal review large text" : "Local journal removal review")
            submit.tap()
            let message = app.staticTexts["account-message"]
            // The submit dismisses the "Remove local journal" sheet before the
            // Account screen settles: the animating nav bar is not hittable, so
            // wait out the transition before scrolling. In large text the Account
            // Form virtualizes off-screen rows, so scroll the message into view
            // (which materializes it) before waiting on it.
            XCTAssertTrue(app.navigationBars["Remove local journal"].waitForNonExistence(timeout: 10))
            reveal(message, app: app)
            XCTAssertTrue(message.waitForExistence(timeout: 10))
            XCTAssertTrue(message.label.contains("Local journal removed"))
            capture(large ? "Local journal removed large text" : "Local journal removed")
            app.buttons["Done"].tap(); app.tabBars.buttons["Meals"].tap()
            // Slice 1 (#172 PL-13): a fully wiped library lands on the
            // first-run empty Meals card; "No finished cooks yet" now only
            // shows in the completed-meals section of a non-empty library.
            XCTAssertTrue(app.otherElements["meals-empty-card"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }
    private func selectTab(_ name: String, app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let tab = app.tabBars.buttons[name]
        XCTAssertTrue(tab.waitForExistence(timeout: 10), "The requested tab must exist.", file: file, line: line)
        // A tap during a Form update can be swallowed. Confirm navigation before
        // looking for actions on another tab, without reselecting an existing path.
        for _ in 0..<3 {
            if tab.isSelected { return }
            tab.tap()
            let selected = XCTNSPredicateExpectation(
                predicate: NSPredicate { value, _ in (value as? XCUIElement)?.isSelected == true },
                object: tab
            )
            if XCTWaiter.wait(for: [selected], timeout: 3) == .completed { return }
        }
        capture("Requested tab did not become selected")
        print(app.debugDescription)
        XCTAssertTrue(tab.isSelected, "The requested tab must become selected.", file: file, line: line)
    }
    func testAnonymousReportRecoveryAndPublisherBlockAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--community-controls-ui-test"] + (large ? ["--screenshot-large-text"] : [])
            app.launch(); selectTab("Discover", app: app)
            let source = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "discover-open-")).firstMatch
            XCTAssertTrue(source.waitForExistence(timeout: 10)); reveal(source, app: app); source.tap()
            let report = app.buttons["public-report"]; reveal(report, app: app); report.tap()
            let details = app.textViews["report-details"]; reveal(details, app: app); details.tap(); details.typeText("Synthetic private review request")
            app.buttons["Hide keyboard"].tap(); let submit = app.buttons["report-submit"]; reveal(submit, app: app); submit.tap()
            let saved = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Recover or review the saved request")).firstMatch
            XCTAssertTrue(saved.waitForExistence(timeout: 10)); reveal(saved, app: app)
            capture(large ? "Community report saved large text" : "Community report saved")
            app.navigationBars["Report publication"].buttons["Cancel"].tap()
            selectTab("More", app: app); let manage = app.buttons["more-community-controls"]; reveal(manage, app: app)
            let retry = app.buttons["community-retry"]
            // Under CI load the More-list tap can be swallowed while the list settles:
            // retry until the Community Controls screen actually lands.
            for _ in 0..<3 {
                if retry.waitForExistence(timeout: 5) { break }
                reveal(manage, app: app); manage.tap()
            }
            reveal(retry, app: app); retry.tap()
            let status = app.buttons["Check report status"].firstMatch; XCTAssertTrue(status.waitForExistence(timeout: 10)); reveal(status, app: app); status.tap()
            let reviewing = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Reviewing")).firstMatch; XCTAssertTrue(reviewing.waitForExistence(timeout: 10)); reveal(reviewing, app: app)
            capture(large ? "Community report status large text" : "Community report status")
            selectTab("Discover", app: app); let block = app.buttons["public-block"]; reveal(block, app: app); block.tap()
            let search = app.buttons["discover-search"]; reveal(search, app: app); search.tap()
            XCTAssertTrue(app.staticTexts["discover-empty"].waitForExistence(timeout: 10))
            capture(large ? "Publisher blocked large text" : "Publisher blocked")
            selectTab("More", app: app); let unblock = app.buttons["Unblock publisher"].firstMatch; reveal(unblock, app: app); unblock.tap()
            selectTab("Discover", app: app); reveal(search, app: app); search.tap(); XCTAssertTrue(source.waitForExistence(timeout: 10))
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
            // PL-19: adding history keeps the save sheet open with the
            // recipe choice intact; the completion alert arrives only
            // when the sheet is dismissed afterwards.
            let added = app.staticTexts["pack-history-added"]; XCTAssertTrue(added.waitForExistence(timeout: 20))
            XCTAssertTrue(app.buttons["pack-save-recipes"].exists)
            app.navigationBars["Cook Pack"].buttons["Cancel"].tap()
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
        // PL-10 (#172): composing a meal lands on the Start Cook review —
        // no cook (and no food cards) exist until "Begin preparation".
        let confirmStart = app.buttons["start-meal-save"]; XCTAssertTrue(confirmStart.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["cook-source"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "food-card-")).firstMatch.exists)
        capture("Native start cook review recipes")
        confirmStart.tap()
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
    /// PL-13 (#172): leaving a changed Start Cook setup keeps it as a draft
    /// on this phone — the same words and choices as Android — and Meals
    /// offers Resume setup until the draft is discarded or started.
    func testStartCookDiscardKeepsDraftAndResumeRestoresIt() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-reset", "--ui-test-empty"]; app.launch()
        let start = app.buttons["Start meal"]; XCTAssertTrue(start.waitForExistence(timeout: 15)); start.tap()
        let title = app.textFields["meal-title"]; XCTAssertTrue(title.waitForExistence(timeout: 15))
        title.tap(); title.typeText("Draft night ribs")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["Your setup is kept as a draft on this phone. Resume it any time from Meals."].waitForExistence(timeout: 10))
        capture("Native setup leave dialog")
        // The confirmation dialog exposes each button twice in the
        // accessibility tree on this iOS version; firstMatch is the same
        // remedy the sign-out confirmation in this file already uses.
        app.buttons["setup-keep-draft"].firstMatch.tap()
        let resume = app.buttons["resume-setup"]; XCTAssertTrue(resume.waitForExistence(timeout: 15))
        capture("Native meals setup draft")
        resume.tap()
        let restored = app.textFields["meal-title"]; XCTAssertTrue(restored.waitForExistence(timeout: 15))
        XCTAssertEqual(restored.value as? String, "Draft night ribs")
        // Leaving the resumed setup unchanged asks nothing; the draft stays.
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["resume-setup"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["setup-keep-draft"].exists)
        // Discarding from the leave dialog is the explicit way to drop it.
        app.buttons["resume-setup"].tap()
        XCTAssertTrue(app.textFields["meal-title"].waitForExistence(timeout: 15))
        let smoker = app.textFields["cook-smoker"]; reveal(smoker, app: app); smoker.tap(); smoker.typeText("Kettle")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["setup-discard"].firstMatch.waitForExistence(timeout: 10))
        app.buttons["setup-discard"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Start meal"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["resume-setup"].exists)
        app.terminate()
    }
    /// PL-13 (#172): the Start Cook sections appear in the canonical order
    /// both platforms share. Section headers materialize as the form is
    /// swept downward; their first-appearance order is the field order.
    func testStartCookFieldsAppearInCanonicalOrder() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-reset", "--ui-test-empty"]; app.launch()
        let start = app.buttons["Start meal"]; XCTAssertTrue(start.waitForExistence(timeout: 15)); start.tap()
        XCTAssertTrue(app.textFields["meal-title"].waitForExistence(timeout: 15))
        let expected = ["Current stage", "Cook name", "Dishes", "Smoker", "Starting setpoint", "Cook notes", "More cook details"]
        let fieldIds = ["cook-smoker", "cook-setpoint", "cook-notes", "cook-fuel", "cook-wood-blend", "cook-outdoor-temperature", "cook-weather", "cook-wind"]
        var seen: [String] = []
        var fieldsSeen: [String] = []
        // Harvest during the sweep: Form rows virtualize, so a field near
        // the top no longer exists once the sweep reaches the bottom —
        // first-appearance order is collected on the way down instead of
        // checking existence after the fact.
        for _ in 0..<14 {
            for header in expected where !seen.contains(header) {
                if app.staticTexts[header].exists { seen.append(header) }
            }
            for identifier in fieldIds where !fieldsSeen.contains(identifier) {
                if app.textFields[identifier].exists { fieldsSeen.append(identifier) }
            }
            if seen.count == expected.count && fieldsSeen.count == fieldIds.count { break }
            app.swipeUp()
        }
        XCTAssertEqual(seen, expected)
        // Field identifiers appear in the same order within their sections.
        XCTAssertEqual(fieldsSeen, fieldIds, "Start Cook fields out of order or missing")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["Start meal"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["resume-setup"].exists)
        app.terminate()
    }
}
