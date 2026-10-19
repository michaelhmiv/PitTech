import XCTest

/// Slice 18 native lane on device UI: entitled accounts see real personal
/// analysis, advanced curves, recap building, and customization behind the
/// existing slice-16 gate — no second gate, no placeholder. Every other gate
/// state shows its honest surface, and Live/Finish never show a Pro surface.
/// Entitlement is scripted (`--pro-ui-test` plus `--pro-*`); the library comes
/// from the standard UI fixtures; no server is contacted.
final class ProPersonalUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        let needsHittable = element.elementType == .button
        for _ in 0..<18 {
            // Cook-screen branch, mirroring MealRecipeFlowsTests.reveal()'s
            // cook-content branch exactly: the meal detail carries a fixed
            // shortcut bar in a bottom safe-area inset ABOVE the tab bar, so the
            // nav-to-tab window below would accept a button parked under that
            // bar — reported hittable, but the tap lands on the bar and the
            // expected alert never appears. Anchor gutter drags to the finite
            // navigation bar and stop above the fixed quick actions.
            let cookScroll = app.scrollViews["cook-content"]
            let cookNav = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable && $0.frame.minY.isFinite && $0.frame.width > 0 })
            let cookLog = app.buttons["meal-log"]
            if cookScroll.exists, let cookNav, cookLog.exists, cookLog.isHittable, cookLog.frame.minY.isFinite {
                let top = cookNav.frame.maxY + 10
                let bottom = cookLog.frame.minY - 20
                if element.waitForExistence(timeout: 1), element.isHittable,
                   element.frame.minY >= top, element.frame.maxY <= bottom { return }
                let down = element.exists && element.frame.minY < top
                let span = max(60, (bottom - top) * 0.55)
                let distance = element.exists ? (down ? top - element.frame.minY : element.frame.maxY - bottom) + 24 : span
                let travel = min(span, max(60, distance))
                let startY = down ? top + 24 : bottom - 24
                let endY = down ? startY + travel : startY - travel
                let origin = cookNav.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: 32, dy: startY - cookNav.frame.minY))
                let end = origin.withOffset(CGVector(dx: 32, dy: endY - cookNav.frame.minY))
                start.press(forDuration: 0.01, thenDragTo: end)
                continue
            }
            let top = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable })?.frame.maxY ?? app.windows.firstMatch.frame.minY
            let bottom = app.tabBars.allElementsBoundByIndex.first(where: { $0.isHittable })?.frame.minY ?? app.windows.firstMatch.frame.maxY
            if element.waitForExistence(timeout: 1), !needsHittable || element.isHittable {
                if element.frame.minY >= top && element.frame.maxY <= bottom - 8 { return }
            }
            let scroll = ["pro-analysis", "pro-curves", "pro-customization", "pro-more-hub", "pro-recap-scope", "pro-recap-preview", "pro-paywall"].compactMap { id -> XCUIElement? in
                let candidate = app.scrollViews[id]
                return candidate.exists && candidate.isHittable ? candidate : nil
            }.first ?? app.scrollViews.allElementsBoundByIndex.first(where: { $0.isHittable })
            if let scroll, scroll.exists && scroll.isHittable, scroll.frame.height.isFinite, scroll.frame.height > 0 {
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
        if needsHittable {
            XCTAssertTrue(element.exists && element.isHittable)
        } else {
            XCTAssertTrue(element.exists)
        }
        // Positional proof on the cook screen (mirrors MealRecipeFlowsTests):
        // hittable alone has proven insufficient here — the button must rest
        // above the fixed shortcut bar, never parked under it.
        let log = app.buttons["meal-log"]
        if app.scrollViews["cook-content"].exists, log.exists, log.isHittable,
           let navigation = app.navigationBars.allElementsBoundByIndex.last(where: { $0.isHittable }) {
            XCTAssertGreaterThanOrEqual(element.frame.minY, navigation.frame.maxY + 10)
            XCTAssertLessThanOrEqual(element.frame.maxY, log.frame.minY - 20)
        }
    }

    private func launch(extra: [String] = [], large: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-reset", "--ui-test-guide", "--pro-ui-test"] + extra + (large ? ["--screenshot-large-text"] : [])
        app.launch()
        return app
    }

    private func openEntry(_ entry: String, app: XCUIApplication) {
        app.tabBars.buttons["Insights"].tap()
        XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
        let button = app.buttons[entry]
        reveal(button, app: app)
        button.tap()
    }

    private func openThemesThroughHub(app: XCUIApplication) {
        app.tabBars.buttons["More"].tap()
        let more = app.buttons["more-pro"]
        reveal(more, app: app)
        more.tap()
        XCTAssertTrue(app.scrollViews["pro-more-hub"].waitForExistence(timeout: 10))
        let themes = app.buttons["pro-hub-themes"]
        reveal(themes, app: app)
        themes.tap()
        XCTAssertTrue(app.scrollViews["pro-customization"].waitForExistence(timeout: 10))
    }

    private func assertNoProSurface(app: XCUIApplication) {
        for id in ["pro-analysis", "pro-curves", "pro-customization", "pro-more-hub", "pro-recap-scope", "pro-recap-preview"] {
            XCTAssertFalse(app.scrollViews[id].exists, "no Pro content may appear here: \(id)")
        }
    }

    private func containing(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    func testEntitledAnalysisShowsRealQuestionsAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled"], large: large)
            openEntry("pro-entry-analysis", app: app)
            XCTAssertTrue(app.scrollViews["pro-analysis"].waitForExistence(timeout: 10))
            for title in ["How your cooks of a cut compare", "Your best-rated recorded attempt", "Timing across recorded cuts and weights", "Wrap and rest differences"] {
                let text = app.staticTexts[title].firstMatch
                reveal(text, app: app)
                XCTAssertTrue(text.exists, title)
            }
            let coverage = containing(app, "dishes have a recorded rating")
            reveal(coverage, app: app)
            XCTAssertTrue(coverage.exists)
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            XCTAssertFalse(app.buttons["pro-subscribe"].exists)
            app.buttons["pro-close"].tap()
            XCTAssertFalse(app.scrollViews["pro-analysis"].waitForExistence(timeout: 2))
            app.terminate()
        }
    }

    func testEntitledCurvesShowAlignmentAndSourceSelectionAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled"], large: large)
            openEntry("pro-entry-curves", app: app)
            XCTAssertTrue(app.scrollViews["pro-curves"].waitForExistence(timeout: 10))
            let sources = app.staticTexts["Measured sources"].firstMatch
            reveal(sources, app: app)
            XCTAssertTrue(sources.exists)
            let channel = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'curve-channel-'")).firstMatch
            reveal(channel, app: app)
            channel.tap()
            let series = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'curve-series-'")).firstMatch
            reveal(series, app: app)
            XCTAssertTrue(series.exists)
            let coverage = containing(app, "recorded point")
            reveal(coverage, app: app)
            XCTAssertTrue(coverage.exists)
            app.buttons["Wrap"].tap()
            XCTAssertTrue(app.scrollViews["pro-curves"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testEntitledThemesPreviewApplyPersistsAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled"], large: large)
            openThemesThroughHub(app: app)
            let theme = app.buttons["pro-theme-ember"]
            reveal(theme, app: app)
            theme.tap()
            let apply = app.buttons["pro-theme-apply-ember"]
            reveal(apply, app: app)
            XCTAssertTrue(apply.exists)
            apply.tap()
            XCTAssertTrue(app.descendants(matching: .any)["pro-theme-applied-ember"].firstMatch.waitForExistence(timeout: 10))
            let palette = app.buttons["pro-palette-color-vision-safe"]
            reveal(palette, app: app)
            palette.tap()
            let applyPalette = app.buttons["pro-palette-apply-color-vision-safe"]
            reveal(applyPalette, app: app)
            applyPalette.tap()
            XCTAssertTrue(app.descendants(matching: .any)["pro-palette-applied-color-vision-safe"].firstMatch.waitForExistence(timeout: 10))
            let icon = app.buttons["pro-icon-ember"]
            reveal(icon, app: app)
            icon.tap()
            let applyIcon = app.buttons["pro-icon-apply-ember"]
            reveal(applyIcon, app: app)
            applyIcon.tap()
            XCTAssertTrue(app.descendants(matching: .any)["pro-icon-applied-ember"].firstMatch.waitForExistence(timeout: 10))
            app.buttons["pro-close"].tap()
            XCTAssertFalse(app.scrollViews["pro-customization"].waitForExistence(timeout: 2))
            // Reopening shows the persisted selections — preview then apply sticks.
            openThemesThroughHub(app: app)
            XCTAssertTrue(app.descendants(matching: .any)["pro-theme-applied-ember"].firstMatch.waitForExistence(timeout: 10))
            XCTAssertTrue(app.descendants(matching: .any)["pro-palette-applied-color-vision-safe"].exists)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testRecapBuilderPreviewsBeforeShareAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled"], large: large)
            openEntry("pro-entry-analysis", app: app)
            XCTAssertTrue(app.scrollViews["pro-analysis"].waitForExistence(timeout: 10))
            let entry = app.buttons["pro-recap-entry"]
            reveal(entry, app: app)
            entry.tap()
            XCTAssertTrue(app.scrollViews["pro-recap-scope"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.buttons["pro-recap-share"].exists, "no share affordance may precede the preview")
            let cook = app.buttons.matching(NSPredicate(format: "identifier == 'pro-recap-cook'")).firstMatch
            reveal(cook, app: app)
            cook.tap()
            let preview = app.buttons["pro-recap-preview-button"]
            reveal(preview, app: app)
            preview.tap()
            XCTAssertTrue(app.scrollViews["pro-recap-preview"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.descendants(matching: .any)["pro-recap-card"].firstMatch.waitForExistence(timeout: 10))
            let coverage = containing(app, "recorded cooks selected")
            reveal(coverage, app: app)
            XCTAssertTrue(coverage.exists)
            let privacy = containing(app, "nothing here changes what is public")
            reveal(privacy, app: app)
            XCTAssertTrue(privacy.exists)
            // The share action exists only on the preview, and is never tapped here.
            XCTAssertTrue(app.buttons["pro-recap-share"].waitForExistence(timeout: 10))
            let back = app.buttons["pro-recap-back"]
            reveal(back, app: app)
            back.tap()
            XCTAssertTrue(app.scrollViews["pro-recap-scope"].waitForExistence(timeout: 10))
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testEmptyLibraryShowsHonestEmptyAtBothTextSizes() {
        for large in [false, true] {
            let app = XCUIApplication()
            app.launchArguments = ["--ui-test-reset", "--ui-test-empty", "--pro-ui-test", "--pro-entitled"] + (large ? ["--screenshot-large-text"] : [])
            app.launch()
            openEntry("pro-entry-analysis", app: app)
            XCTAssertTrue(app.scrollViews["pro-analysis"].waitForExistence(timeout: 10))
            let empty = app.staticTexts["No recorded cooks yet"].firstMatch
            reveal(empty, app: app)
            XCTAssertTrue(empty.exists)
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testNonProShowsPaywallWithoutProContentAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-not-entitled"], large: large)
            openEntry("pro-entry-analysis", app: app)
            XCTAssertTrue(app.scrollViews["pro-paywall"].waitForExistence(timeout: 10))
            assertNoProSurface(app: app)
            app.buttons["pro-paywall-close"].tap()
            app.terminate()
        }
    }

    func testUnknownEntitlementShowsNeutralLoadingAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(large: large)
            openEntry("pro-entry-curves", app: app)
            XCTAssertTrue(app.scrollViews["pro-loading"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            assertNoProSurface(app: app)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testExpiredShowsFreeFallbackAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-expired"], large: large)
            openEntry("pro-entry-curves", app: app)
            XCTAssertTrue(app.scrollViews["pro-expired-fallback"].waitForExistence(timeout: 10))
            assertNoProSurface(app: app)
            let intact = containing(app, "nothing was deleted")
            reveal(intact, app: app)
            XCTAssertTrue(intact.exists)
            app.buttons["pro-close"].tap()
            // The free Insights surface is intact behind the fallback.
            XCTAssertTrue(app.staticTexts["Finished cooks"].waitForExistence(timeout: 10))
            app.terminate()
        }
    }

    func testCommunityInsufficientDataDoesNotBlockPersonalEntryAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled", "--community-insufficient"], large: large)
            openEntry("pro-entry-benchmarks", app: app)
            XCTAssertTrue(app.scrollViews["community-benchmarks"].waitForExistence(timeout: 10))
            let thin = app.staticTexts["Not enough cooks yet"].firstMatch
            reveal(thin, app: app)
            XCTAssertTrue(thin.exists)
            app.buttons["pro-close"].tap()
            XCTAssertFalse(app.scrollViews["community-benchmarks"].waitForExistence(timeout: 2))
            // The thin community cell neither blocks nor shames the personal entry.
            openEntry("pro-entry-analysis", app: app)
            XCTAssertTrue(app.scrollViews["pro-analysis"].waitForExistence(timeout: 10))
            let title = app.staticTexts["How your cooks of a cut compare"].firstMatch
            reveal(title, app: app)
            XCTAssertTrue(title.exists)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testRenderedAnalysisCarriesNoBannedWordsAtBothTextSizes() {
        for large in [false, true] {
            let app = launch(extra: ["--pro-entitled"], large: large)
            openEntry("pro-entry-analysis", app: app)
            XCTAssertTrue(app.scrollViews["pro-analysis"].waitForExistence(timeout: 10))
            let scroll = app.scrollViews["pro-analysis"]
            var labels: [String] = []
            for _ in 0..<10 {
                for text in scroll.staticTexts.allElementsBoundByIndex {
                    labels.append(text.label)
                }
                scroll.swipeUp()
            }
            let banned = ["faster", "slower", "because", "caus", "predict", "safe", "doneness", "undercook", "overcook", "rank", "leaderboard", "score", "quality"]
            for label in Set(labels) {
                for word in banned {
                    XCTAssertFalse(label.lowercased().contains(word), "\(label) contains \(word)")
                }
                XCTAssertNil(label.lowercased().range(of: "\\bshould\\b", options: .regularExpression), "\(label) contains should")
            }
            XCTAssertFalse(labels.isEmpty)
            app.buttons["pro-close"].tap()
            app.terminate()
        }
    }

    func testLiveAndFinishShowNoProSurfacesAtBothTextSizes() {
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
            XCTAssertFalse(app.scrollViews["pro-paywall"].exists)
            XCTAssertFalse(app.scrollViews["pro-loading"].exists)
            XCTAssertFalse(app.scrollViews["pro-expired-fallback"].exists)
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
