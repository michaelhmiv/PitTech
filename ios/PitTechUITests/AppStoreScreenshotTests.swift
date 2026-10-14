import XCTest

/// Guided tour that captures the App Store screenshot set. The app seeds rich
/// demo data when launched with --screenshot-tour (see UIFlowFixtures.swift).
/// The iOS workflow exports these attachments and renames them with
/// scripts/collect-tour-screenshots.py.
final class AppStoreScreenshotTests: XCTestCase {
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func settle(_ seconds: TimeInterval = 2) {
        Thread.sleep(forTimeInterval: seconds)
    }

    private func segment(_ label: String, in app: XCUIApplication) -> XCUIElement {
        let segmented = app.segmentedControls.buttons[label]
        if segmented.waitForExistence(timeout: 5) { return segmented }
        return app.buttons[label]
    }

    /// Tab bar items are Buttons on iPhone but Cells in iPad's floating tab bar.
    private func tab(_ label: String, in app: XCUIApplication) -> XCUIElement {
        let button = app.tabBars.buttons[label]
        if button.waitForExistence(timeout: 5) { return button }
        let cell = app.tabBars.cells[label]
        if cell.waitForExistence(timeout: 5) { return cell }
        return app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    func testAppStoreTour() {
        let app = XCUIApplication()
        app.launchArguments = ["--screenshot-tour", "--screenshot-no-ads"]
        app.launch()

        // 1. Meals home: active brisket cook up top, finished cooks below.
        let brisket = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Weekend Brisket")).firstMatch
        XCTAssertTrue(brisket.waitForExistence(timeout: 30))
        settle()
        capture("01-Meals-Home")

        // 2. Cook detail, Live section.
        brisket.tap()
        let charts = segment("Charts", in: app)
        XCTAssertTrue(charts.waitForExistence(timeout: 20))
        settle()
        capture("02-Cook-Live")

        // 3. Temperature chart with the brisket and pit curves.
        charts.tap()
        let chartTitle = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Temperature")).firstMatch
        _ = chartTitle.waitForExistence(timeout: 10)
        settle(3)
        capture("03-Temperature-Chart")

        // 4. Timeline of the cook.
        segment("Timeline", in: app).tap()
        settle()
        capture("04-Cook-Timeline")

        // Back to the tabs.
        let backButton = app.navigationBars["Meal"].buttons["BackButton"]
        if backButton.exists { backButton.tap() } else { app.navigationBars.buttons.firstMatch.tap() }

        // 5. Insights with real totals from the seeded history.
        let insights = tab("Insights", in: app)
        XCTAssertTrue(insights.waitForExistence(timeout: 10))
        insights.tap()
        _ = app.staticTexts["Finished cooks"].waitForExistence(timeout: 10)
        settle()
        capture("05-Insights")

        // 6. Saved Recipes list.
        tab("Saved Recipes", in: app).tap()
        let recipeButton = app.buttons["Pork shoulder"]
        let recipe: XCUIElement = recipeButton.waitForExistence(timeout: 5) ? recipeButton : app.cells["Pork shoulder"]
        XCTAssertTrue(recipe.waitForExistence(timeout: 10))
        settle()
        capture("06-Saved-Recipes")

        // 7. One saved recipe opened as a playbook.
        recipe.tap()
        settle()
        capture("07-Recipe-Detail")
    }
}
