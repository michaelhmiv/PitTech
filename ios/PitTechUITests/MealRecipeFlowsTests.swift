import XCTest

final class MealRecipeFlowsTests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }
    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<18 {
            let cookScroll = app.scrollViews["cook-content"]
            let navigation = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable && $0.frame.minY.isFinite && $0.frame.width > 0 })
            let log = app.buttons["meal-log"]
            if app.scrollViews["relay-import-content"].exists, let navigation {
                // The modal scroll view can also report an infinite AX frame.
                // Its finite navigation bar anchors an ordinary content-gutter swipe.
                if element.waitForExistence(timeout: 1), element.isHittable { return }
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
        XCTAssertTrue(element.exists && element.isHittable)
        let log = app.buttons["meal-log"]
        if app.scrollViews["cook-content"].exists, log.exists, log.isHittable,
           let navigation = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable }) {
            XCTAssertGreaterThanOrEqual(element.frame.minY, navigation.frame.maxY + 10)
            XCTAssertLessThanOrEqual(element.frame.maxY, log.frame.minY - 20)
        }
    }
    private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
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
            XCTAssertEqual(toggle.value as? String, "0"); toggle.tap()
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
            reveal(toggle, app: app); toggle.tap(); XCTAssertEqual(toggle.value as? String, "0")
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
            first.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            XCTAssertTrue(NSPredicate(format: "value == %@", "0").evaluate(with: first) || XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "0"), object: first)], timeout: 5) == .completed)
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
