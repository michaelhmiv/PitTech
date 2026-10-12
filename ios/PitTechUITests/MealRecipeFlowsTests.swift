import XCTest

final class MealRecipeFlowsTests: XCTestCase {
    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 15))
        for _ in 0..<8 { if element.isHittable { return }; app.swipeUp() }
        XCTAssertTrue(element.isHittable)
    }
    private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
    func testSavedRecipesComposeHistoricalCheckAndFinishWithoutForcedSaving() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-test-reset"]; app.launch()
        app.tabBars.buttons["Saved Recipes"].tap()
        for name in ["Chicken", "Mac and cheese", "Pork"] {
            let toggle = app.switches["Add " + name + " to meal"]; reveal(toggle, app: app); toggle.tap()
        }
        let start = app.buttons["recipe-start-meal"]; reveal(start, app: app); start.tap()
        let foods = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "food-card-"))
        XCTAssertTrue(foods.firstMatch.waitForExistence(timeout: 15)); XCTAssertEqual(foods.count, 3)
        capture("Native composed meal")
        let reminders = app.buttons["Reminders"].firstMatch; reveal(reminders, app: app); reminders.tap()
        let suggestion = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Chicken · last time")).firstMatch
        reveal(suggestion, app: app); suggestion.tap()
        app.buttons["reminder-rule-save"].tap()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.buttons["Allow"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "waiting")).firstMatch.waitForExistence(timeout: 15))
        capture("Native explicit historical reminder")
        app.navigationBars["Reminders"].buttons.element(boundBy: 0).tap()
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
