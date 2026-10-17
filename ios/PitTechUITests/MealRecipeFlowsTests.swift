import XCTest

final class MealRecipeFlowsTests: XCTestCase {
    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<12 {
            if element.waitForExistence(timeout: 1) && element.isHittable {
                let top = app.navigationBars.firstMatch.exists ? app.navigationBars.firstMatch.frame.maxY : app.windows.firstMatch.frame.minY
                let bottom = app.tabBars.firstMatch.exists ? app.tabBars.firstMatch.frame.minY : app.windows.firstMatch.frame.maxY
                if element.frame.minY >= top && element.frame.maxY <= bottom - 8 { return }
            }
            let scroll = app.scrollViews["cook-content"].exists ? app.scrollViews["cook-content"] : app.scrollViews.firstMatch
            let top = app.navigationBars.firstMatch.exists ? app.navigationBars.firstMatch.frame.maxY : app.windows.firstMatch.frame.minY
            if scroll.exists && scroll.isHittable {
                if element.exists && element.frame.maxY < top + 8 { scroll.swipeDown() } else { scroll.swipeUp() }
            } else { app.swipeUp() }
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }
    private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
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
        for _ in 0..<6 { if reminders.exists && reminders.isHittable { break }; app.swipeDown() }
        reveal(reminders, app: app); reminders.tap()
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
