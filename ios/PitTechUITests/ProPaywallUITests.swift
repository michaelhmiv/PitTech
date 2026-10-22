import XCTest

/// Slice 16 native lane on device UI: the contextual paywall appears at Pro
/// entry points with the store's localized price, and never interrupts Live
/// or Finish. The stubbed product and scripted backend come from
/// `--pro-ui-test` launch arguments; no App Store or server is contacted.
final class ProPaywallUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<12 {
            if element.waitForExistence(timeout: 1), element.isHittable { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.exists && element.isHittable)
    }

    private func launch(extra: [String] = [], large: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-guide", "--pro-ui-test"] + extra + (large ? ["--screenshot-large-text"] : [])
        app.launch()
        return app
    }

    private func openPaywall(from entry: String, app: XCUIApplication) {
        let button = app.buttons[entry]
        reveal(button, app: app)
        button.tap()
        XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
    }

    private func closePaywall(app: XCUIApplication) {
        app.buttons["pro-paywall-close"].tap()
        XCTAssertFalse(app.scrollViews["pro-paywall"].waitForExistence(timeout: 2))
    }

    private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }

    private func assertNoProSurface(app: XCUIApplication) {
        for id in ["pro-paywall", "pro-loading", "pro-placeholder", "pro-analysis", "pro-curves", "pro-customization", "pro-more-hub", "pro-recap-scope", "pro-recap-preview", "pro-expired-fallback"] {
            XCTAssertFalse(app.scrollViews[id].exists, "no Pro surface may appear here: \(id)")
        }
    }

    func testPaywallShowsStorePriceLinksAndCloseAtEveryEntryPointAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-not-entitled"], large: large)
            app.tabBars.buttons["Insights"].tap()
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            openPaywall(from: "pro-entry-analysis", app: app)
            let price = app.staticTexts["pro-price"]
            XCTAssertTrue(price.waitForExistence(timeout: 10))
            XCTAssertTrue(price.label.contains("$3.00"), "the price must come from the stubbed store product, not a hardcoded string")
            for id in ["pro-subscribe", "pro-restore"] {
                let element = app.buttons[id]
                reveal(element, app: app)
                XCTAssertTrue(element.exists)
            }
            for id in ["pro-manage", "pro-privacy", "pro-terms"] {
                let element = app.descendants(matching: .any)[id].firstMatch
                reveal(element, app: app)
                XCTAssertTrue(element.exists)
            }
            closePaywall(app: app)

            openPaywall(from: "pro-entry-curves", app: app)
            closePaywall(app: app)

            app.tabBars.buttons["More"].tap()
            let more = app.buttons["more-pro"]
            reveal(more, app: app)
            more.tap()
            XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
            closePaywall(app: app)

            app.tabBars.buttons["Meals"].tap()
            let guided = app.staticTexts["Guided meal"].firstMatch
            reveal(guided, app: app)
            guided.tap()
            let share = app.buttons["meal-sharing-draft"]
            reveal(share, app: app)
            share.tap()
            let preview = app.buttons["sharing-preview-card"]
            reveal(preview, app: app)
            preview.tap()
            XCTAssertTrue(app.descendants(matching: .any)["share-card-preview"].firstMatch.waitForExistence(timeout: 10))
            let themes = app.buttons["pro-entry-themes"]
            reveal(themes, app: app)
            themes.tap()
            XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
            closePaywall(app: app)
            app.terminate()
        }
    }

    func testSignedOutPaywallRequiresSignInAndOffersNoPurchaseAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-not-entitled", "--pro-signed-out"], large: large)
            app.tabBars.buttons["Insights"].tap()
            openPaywall(from: "pro-entry-analysis", app: app)
            XCTAssertTrue(app.staticTexts["pro-signin-required"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.buttons["pro-subscribe"].exists)
            closePaywall(app: app)
            app.terminate()
        }
    }

    func testEntitledAccountOpensProWithNoSecondOfferAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled"], large: large)
            app.tabBars.buttons["Insights"].tap()
            let button = app.buttons["pro-entry-analysis"]
            reveal(button, app: app)
            button.tap()
            XCTAssertTrue(app.scrollViews["pro-analysis"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            XCTAssertFalse(app.scrollViews["pro-placeholder"].exists)
            XCTAssertFalse(app.buttons["pro-subscribe"].exists)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testExpiredAccountKeepsFreeFallbackAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-expired"], large: large)
            app.tabBars.buttons["Insights"].tap()
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            let button = app.buttons["pro-entry-curves"]
            reveal(button, app: app)
            button.tap()
            XCTAssertTrue(app.scrollViews["pro-expired-fallback"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.scrollViews["pro-placeholder"].exists)
            let resubscribe = app.buttons["pro-resubscribe"]
            reveal(resubscribe, app: app)
            resubscribe.tap()
            XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
            closePaywall(app: app)
            // The free Insights surface is intact behind the fallback.
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }

    func testUnknownEntitlementShowsNeutralLoadingWithNoPaywallFlashAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(large: large)
            app.tabBars.buttons["Insights"].tap()
            let button = app.buttons["pro-entry-analysis"]
            reveal(button, app: app)
            button.tap()
            XCTAssertTrue(app.scrollViews["pro-loading"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testUnconfiguredProductShowsNoPriceAndNoPurchaseAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-not-entitled", "--pro-no-product"], large: large)
            app.tabBars.buttons["Insights"].tap()
            openPaywall(from: "pro-entry-analysis", app: app)
            XCTAssertTrue(app.staticTexts["pro-product-unavailable"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.staticTexts["pro-price"].exists)
            XCTAssertFalse(app.buttons["pro-subscribe"].exists)
            closePaywall(app: app)
            app.terminate()
        }
    }

    /// Store tooling: the deterministic App Store review capture of the Pro
    /// paywall with the $3/month offer. Default text size only, fixed entry
    /// point; the online-store-paywall-capture lane retains this attachment
    /// as the subscription's review screenshot.
    func testPaywallReviewScreenshotCapture() {
        let app = launch(extra: ["--pro-not-entitled"])
        app.tabBars.buttons["Insights"].tap()
        XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
        openPaywall(from: "pro-entry-analysis", app: app)
        let price = app.staticTexts["pro-price"]
        XCTAssertTrue(price.waitForExistence(timeout: 10))
        XCTAssertTrue(price.label.contains("$3.00"), "the review capture must show the $3/month offer")
        let subscribe = app.buttons["pro-subscribe"]
        reveal(subscribe, app: app)
        XCTAssertTrue(subscribe.exists)
        capture("Native Pro paywall review screenshot")
        app.terminate()
    }

    func testLiveAndFinishNeverPresentThePaywallAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            let start = app.buttons["Start meal"]
            XCTAssertTrue(start.waitForExistence(timeout: 15))
            start.tap()
            app.buttons["start-meal-save"].tap()
            XCTAssertTrue(app.buttons["finish-meal"].waitForExistence(timeout: 15))
            assertNoProSurface(app: app)
            let finish = app.buttons["finish-meal"]
            reveal(finish, app: app)
            finish.tap()
            XCTAssertTrue(app.alerts["Finish meal?"].waitForExistence(timeout: 5))
            app.alerts.buttons["Finish meal"].tap()
            XCTAssertTrue(app.buttons["Leave without saving"].waitForExistence(timeout: 15))
            assertNoProSurface(app: app)
            app.buttons["Leave without saving"].tap()
            XCTAssertTrue(app.staticTexts["Finished"].waitForExistence(timeout: 10))
            assertNoProSurface(app: app)
            app.terminate()
        }
    }
}
