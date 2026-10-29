import XCTest

/// Flowing Slice 3 (spec §5 Spread 06 + E4): the recipe detail opens
/// with its story when the source cook is on the device and renders
/// exactly as before when it isn't; a finished cook opens on its
/// story (Timeline) and offers the one-time protect-your-log prompt.
/// The --ui-test-flowing-story fixture fixes the story cook on
/// October 12, 2026 so every asserted string is deterministic.
final class FlowingStoryUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func launchStory() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--ui-test-flowing-story"]
        app.launch()
        return app
    }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        // The recipe detail is a List pushed over the Recipes list:
        // the covered list's containers stay in the AX tree, so
        // firstMatch swipes scroll the wrong surface. Drag on the
        // window's content area instead — the drag lands on the
        // foreground surface, like MealRecipeFlowsTests' servo drags.
        for _ in 0..<18 {
            if element.exists && element.isHittable { return }
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
            start.press(forDuration: 0.01, thenDragTo: end)
        }
    }

    func testRecipeOpensWithItsStory() {
        let app = launchStory()
        app.tabBars.buttons["Recipes"].tap()
        let recipe = app.staticTexts["Boston Butt"].firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 15))
        recipe.tap()
        let line = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "From your October 12, 2026 cook")).firstMatch
        XCTAssertTrue(line.waitForExistence(timeout: 15), "the provenance line leads, its date carrying the year")
        XCTAssertTrue(app.descendants(matching: .any)["recipe-story"].exists)
        XCTAssertTrue(app.staticTexts["10H 55M"].waitForExistence(timeout: 10), "the total rides in the story's stat cells")
        reveal(app.buttons["recipe-story-cook-again"], app: app)
        XCTAssertTrue(app.buttons["recipe-story-cook-again"].exists, "Cook this again is the story's own door")
    }

    func testRecipeWithoutSourceCookRendersAsBefore() {
        let app = launchStory()
        app.tabBars.buttons["Recipes"].tap()
        let recipe = app.staticTexts["Plain toast"].firstMatch
        XCTAssertTrue(recipe.waitForExistence(timeout: 15))
        recipe.tap()
        XCTAssertTrue(app.buttons["Use this version"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.descendants(matching: .any)["recipe-story"].exists,
                       "no source cook, no story block — and no placeholder where one would go")
    }

    func testFinishedCookOpensOnStoryWithProtectPrompt() {
        let app = launchStory()
        let cook = app.staticTexts["Finished solo"].firstMatch
        XCTAssertTrue(cook.waitForExistence(timeout: 15))
        cook.tap()
        let timeline = app.segmentedControls.buttons["Timeline"]
        XCTAssertTrue(timeline.waitForExistence(timeout: 15))
        XCTAssertTrue(timeline.isSelected, "a finished cook opens on its story, not the Live board")
        let prompt = app.descendants(matching: .any)["protect-log-prompt"]
        reveal(prompt, app: app)
        XCTAssertTrue(prompt.exists, "the first finished cook asks to protect the log")
        app.buttons["Not now"].tap()
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: prompt)
        wait(for: [gone], timeout: 5)
    }
}
