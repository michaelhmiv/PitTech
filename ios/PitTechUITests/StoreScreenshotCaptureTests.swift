import XCTest

/// App Store screenshot capture — the iOS mirror of Android's
/// StoreScreenshotCaptureTest (scripts/capture-store-screenshots.sh).
/// Like the Android capture, this class is deliberately NOT part of the
/// test gate: it is registered in scripts/ios-test-shards.json under the
/// "store-capture" shard, which the ios-build.yml ui-shards matrix does
/// not run. The dispatch-only ios-store-screenshots.yml workflow runs it
/// on demand and exports the attached screenshots from the result
/// bundle, so the store listing always shows the real app.
///
/// Every capture is seeded with the proven Flowing fixture — the same
/// hand-laid brisket board the Android store screenshots use: one live
/// brisket six hours in with a stated verdict and ETA, and one finished
/// brisket behind it. Scenes: Home, the Live board, the live cook's
/// Timeline, and the finished cook's record (History).
final class StoreScreenshotCaptureTests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func launchFlowing() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--ui-test-flowing"]
        app.launch()
        return app
    }

    private func capture(_ name: String) {
        // Let the board's last render settle so the frame is the
        // finished screen, not a transition.
        Thread.sleep(forTimeInterval: 1.0)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testCaptureStoreScreenshots() {
        let app = launchFlowing()

        // Home (Meals tab): the live brisket and the finished one.
        let liveCook = app.staticTexts["Flowing brisket"].firstMatch
        XCTAssertTrue(liveCook.waitForExistence(timeout: 15), "the seeded live cook leads the home list")
        XCTAssertTrue(app.staticTexts["Last brisket"].firstMatch.waitForExistence(timeout: 10),
                      "the seeded finished cook is on the home list")
        capture("home")

        // Live board: verdict headline proves the board rendered.
        liveCook.tap()
        let headline = app.staticTexts["live-verdict-headline"]
        XCTAssertTrue(headline.waitForExistence(timeout: 15), "the live board rendered its verdict")
        capture("live-board")

        // Timeline: the live cook's own story segment.
        let timeline = app.segmentedControls.buttons["Timeline"]
        XCTAssertTrue(timeline.waitForExistence(timeout: 10), "the cook section picker is present")
        timeline.tap()
        XCTAssertTrue(app.staticTexts["Food on"].firstMatch.waitForExistence(timeout: 15),
                      "the timeline shows the cook's recorded events")
        capture("timeline")

        // History: relaunch (the fixture re-seeds deterministically) and
        // open the finished brisket — a finished cook opens on its
        // recorded story, which is the history record.
        app.terminate()
        let relaunched = launchFlowing()
        let finishedCook = relaunched.staticTexts["Last brisket"].firstMatch
        XCTAssertTrue(finishedCook.waitForExistence(timeout: 15), "the finished cook is listed after relaunch")
        finishedCook.tap()
        let storySegment = relaunched.segmentedControls.buttons["Timeline"]
        XCTAssertTrue(storySegment.waitForExistence(timeout: 15), "the finished cook opened its record")
        XCTAssertTrue(storySegment.isSelected, "a finished cook opens on its story")
        // The one-time protect-your-log prompt may cover the record on
        // first open; dismiss it so the capture is the record itself.
        let notNow = relaunched.buttons["Not now"]
        if notNow.waitForExistence(timeout: 3) {
            notNow.tap()
            let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: notNow)
            wait(for: [gone], timeout: 5)
        }
        capture("history")
    }
}
