import XCTest

/// TURN-IN Slice 0 (BUILD-SPEC §3.5): the Settings appearance control is a
/// working Dark / Light / System selector, exercised in both modes. Dark is
/// the fresh-install default.
final class SettingsAppearanceUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testAppearanceControlDefaultsToDarkAndSwitchesModes() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset"]
        app.launch()

        app.tabBars.buttons["More"].tap()
        let settings = app.buttons["Settings & support"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()

        let picker = app.segmentedControls.firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        let dark = picker.buttons["Dark"]
        let light = picker.buttons["Light"]
        let system = picker.buttons["System"]
        XCTAssertTrue(dark.exists && light.exists && system.exists)

        // Fresh install: Dark is selected before the user touches anything.
        XCTAssertTrue(dark.isSelected)
        XCTAssertTrue(app.staticTexts["Dark is the default — light is the same layout."].exists)

        light.tap()
        XCTAssertTrue(light.isSelected)

        system.tap()
        XCTAssertTrue(system.isSelected)

        dark.tap()
        XCTAssertTrue(dark.isSelected)
        app.terminate()
    }
}
