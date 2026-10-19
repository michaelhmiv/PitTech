import XCTest
@testable import PitTech

/// Slice 18 native lane: Pro personal analysis, advanced curves, Year in Smoke
/// recaps, and customization consume the local v1 report with the same
/// meanings, values, and fixtures as Android. Missing data stays missing;
/// bests follow declared ratings; wording carries no causation, doneness, or
/// opaque-score claims.
@MainActor final class ProPersonalTests: XCTestCase {
    private func fixtureRoot() throws -> [String: Any] {
        let bundle = Bundle(for: Self.self)
        let data = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "pro-personal-v1", withExtension: "json")))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["contractVersion"] as? String, "pro-personal-v1")
        return json
    }

    private func fixtureArchive(_ name: String) throws -> (archive: InsightArchiveInput, titles: [String: String], asOf: Double) {
        let root = try fixtureRoot()
        let libraries = try XCTUnwrap(root["libraries"] as? [String: Any])
        let entry = try XCTUnwrap(libraries[name] as? [String: Any])
        let archiveData = try JSONSerialization.data(withJSONObject: XCTUnwrap(entry["archive"]))
        let archive = try JSONDecoder().decode(InsightArchiveInput.self, from: archiveData)
        let titles = try XCTUnwrap(root["titles"] as? [String: String])
        let asOf = try XCTUnwrap(root["asOfUtcMillis"] as? Double)
        return (archive, titles, asOf)
    }

    private func snapshot(_ name: String) throws -> (ProPersonalAnalysis.Snapshot, InsightReport, InsightArchiveInput, [String: String]) {
        let (archive, titles, asOf) = try fixtureArchive(name)
        let report = InsightsCalculations.compute(archive, asOf: asOf)
        XCTAssertEqual(report.calculationVersion, InsightsCalculations.calculationVersion)
        return (ProPersonalAnalysis.snapshot(report: report, titles: titles), report, archive, titles)
    }

    // MARK: - Empty and sparse libraries

    func testEmptyLibraryShowsHonestEmptyState() throws {
        let (snapshot, _, _, _) = try snapshot("empty")
        XCTAssertEqual(snapshot.state, .empty)
        XCTAssertEqual(snapshot.cookCount, 0)
        XCTAssertTrue(snapshot.cutGroups.isEmpty)
        XCTAssertNil(snapshot.best)
        XCTAssertNil(snapshot.timing)
        XCTAssertNil(snapshot.methods)
        XCTAssertEqual(snapshot.emptyTitle, "No recorded cooks yet")
        XCTAssertEqual(
            snapshot.emptyBody,
            "Personal analysis appears here as you record cooks on this device. Your first finished cook unlocks bests, timing groups, and wrap notes."
        )
    }

    func testSingleCookShowsSparseStateWithAvailableSections() throws {
        let (snapshot, _, _, _) = try snapshot("single")
        XCTAssertEqual(snapshot.state, .sparse)
        XCTAssertEqual(snapshot.cookCount, 1)
        XCTAssertEqual(snapshot.comparisonNote, "Record a second cook to compare cuts side by side.")
        XCTAssertNotNil(snapshot.emptyBody)
        let best = try XCTUnwrap(snapshot.best)
        XCTAssertEqual(best.lines, [
            "First ribs — Baby back",
            "You rated this 4 / 5",
            "Cook-again choice not recorded",
        ])
        XCTAssertEqual(best.coverage, "1 of 1 dishes have a recorded rating")
        let timing = try XCTUnwrap(snapshot.timing)
        XCTAssertEqual(timing.lines, ["Baby back: 5h across 1 recorded cook"])
        XCTAssertEqual(timing.coverage, "Heating time recorded for 1 of 1 dishes · weights recorded for 0 of 1 dishes")
        let methods = try XCTUnwrap(snapshot.methods)
        XCTAssertEqual(methods.lines, ["First ribs: wrap not recorded · rest not recorded · holding not recorded"])
        XCTAssertEqual(methods.coverage, "Wrap recorded for 0 of 1 dishes · rest recorded for 0 of 1 dishes · holding recorded for 0 of 1 dishes")
    }

    // MARK: - Personal analysis values (verified, not labels alone)

    func testCutComparisonShowsRecordedDifferencesWithCoverage() throws {
        let (snapshot, _, _, _) = try snapshot("many")
        XCTAssertEqual(snapshot.state, .full)
        XCTAssertEqual(snapshot.cutGroups.map(\.cut), ["Boston butt", "Thighs", "Whole packer"])
        let butt = try XCTUnwrap(snapshot.cutGroups.first { $0.cut == "Boston butt" })
        XCTAssertEqual(butt.header, "Boston butt · 2 recorded cooks")
        XCTAssertEqual(butt.lines, [
            "Saturday pork butt: heating 8h · 8 lb · Smoked · °F readings",
            "February pork butt: heating 7h · 3.5 kg · Pellet · °C readings",
        ])
        XCTAssertEqual(butt.coverage, "Heating time recorded for 2 of 2 dishes · weights recorded for 2 of 2 dishes")
        let thighs = try XCTUnwrap(snapshot.cutGroups.first { $0.cut == "Thighs" })
        XCTAssertEqual(thighs.lines, ["Weeknight chicken: heating 1h 30m · weight not recorded · Grilled · °F readings"])
        XCTAssertEqual(thighs.coverage, "Heating time recorded for 1 of 1 dishes · weights recorded for 0 of 1 dishes")
        let packer = try XCTUnwrap(snapshot.cutGroups.first { $0.cut == "Whole packer" })
        XCTAssertEqual(packer.lines, ["Whole packer brisket: heating 11h 30m · 14 lb · Oak smoke · °F readings"])
        XCTAssertEqual(packer.coverage, "Heating time recorded for 1 of 1 dishes · weights recorded for 1 of 1 dishes")
    }

    func testTimingAcrossCutsAndWeightsShowsMedianRangeAndCoverage() throws {
        let (snapshot, _, _, _) = try snapshot("many")
        let timing = try XCTUnwrap(snapshot.timing)
        XCTAssertEqual(timing.lines, [
            "Boston butt: typical (median) 7h 30m across 2 recorded cooks · range 7h – 8h",
            "Thighs: 1h 30m across 1 recorded cook",
            "Whole packer: 11h 30m across 1 recorded cook",
        ])
        XCTAssertEqual(timing.coverage, "Heating time recorded for 4 of 4 dishes · weights recorded for 3 of 4 dishes")
    }

    func testPersonalBestFollowsUserRating() throws {
        let (snapshot, _, _, _) = try snapshot("many")
        let best = try XCTUnwrap(snapshot.best)
        XCTAssertEqual(best.lines, [
            "Saturday pork butt — Boston butt",
            "You rated this 5 / 5",
            "Cook again: Yes",
            "Includes your result note",
        ])
        XCTAssertEqual(best.coverage, "3 of 4 dishes have a recorded rating")
    }

    func testMissingAnchorsAndWeightsStayMissing() throws {
        let (snapshot, _, _, _) = try snapshot("many")
        let methods = try XCTUnwrap(snapshot.methods)
        XCTAssertEqual(methods.lines, [
            "Saturday pork butt: wrapped +3:20 after Food on · rested 50m · holding not recorded",
            "February pork butt: wrapped +3:00 after Food on · rested 10m · held 20m",
            "Whole packer brisket: wrap not recorded · rested 20m · holding not recorded",
            "Weeknight chicken: wrap not recorded · rest not recorded · holding not recorded",
        ])
        XCTAssertEqual(methods.coverage, "Wrap recorded for 2 of 4 dishes · rest recorded for 3 of 4 dishes · holding recorded for 1 of 4 dishes")
        for line in methods.lines {
            XCTAssertFalse(line.contains("0m"), "a zero span must read as not recorded: \(line)")
        }
    }

    func testAnalysisReusesV1CalculationsWithoutChangingFreeMeanings() throws {
        let (_, report, _, _) = try snapshot("many")
        XCTAssertEqual(report.calculationVersion, 1)
        let heating = Dictionary(uniqueKeysWithValues: report.cooks.flatMap { cook in
            cook.dishes.map { ($0.dishId, $0.heatingMillis) }
        })
        XCTAssertEqual(heating["dish-a"], 28800000)
        XCTAssertEqual(heating["dish-b"], 25200000)
        XCTAssertEqual(heating["dish-c"], 41400000)
        XCTAssertEqual(heating["dish-d"], 5400000)
        // The free chart-comparison helper keeps its exact lines on the same
        // fixture: no Pro metric silently changes a free chart's meaning.
        let current = InsightDishSummary(dishId: "dish-a", cut: "Boston butt", weightValue: 8, weightUnit: "lb", methods: ["Smoked"])
        let source = InsightDishSummary(dishId: "dish-b", cut: "Boston butt", weightValue: 3.5, weightUnit: "kg", methods: ["Pellet"])
        let currentReadings = InsightReadingSummary(units: ["°F"], validCount: 5, manualCount: 1, excludedCount: 1, roles: ["food", "pit"])
        let sourceReadings = InsightReadingSummary(units: ["°C"], validCount: 5, manualCount: 0, excludedCount: 0, roles: ["food", "pit"])
        XCTAssertEqual(
            InsightsCalculations.comparisonContext(currentDish: current, sourceDish: source, current: currentReadings, source: sourceReadings),
            [
                "Methods: Smoked · Pellet",
                "Weights: 8.0 lb · 3.5 kg",
                "Reading units: °F · °C",
                "Recorded readings: 5 (1 manual), 1 unavailable · 5",
                "Channels: food, pit · food, pit",
            ]
        )
    }

    // MARK: - Advanced curves

    func testCurvesAlignAtFoodOnWithMeasuredSourceSelection() throws {
        let (_, report, archive, titles) = try snapshot("many")
        let options = ProAdvancedCurves.channelOptions(report: report, archive: archive, titles: titles)
        XCTAssertEqual(options.count, 7)
        XCTAssertTrue(options.contains { $0.label == "Saturday pork butt · food · Probe 1 · 4 readings (°F)" })
        XCTAssertTrue(options.contains { $0.label == "February pork butt · food · Probe 1 · 3 readings (°C)" })
        XCTAssertTrue(options.contains { $0.label == "Saturday pork butt · food · Probe 1 · 1 reading (°F)" })
        let food = try XCTUnwrap(options.first { $0.label == "Saturday pork butt · food · Probe 1 · 4 readings (°F)" })
        let celsius = try XCTUnwrap(options.first { $0.label == "February pork butt · food · Probe 1 · 3 readings (°C)" })
        let aligned = ProAdvancedCurves.align(report: report, archive: archive, titles: titles, anchor: .foodOn, channelIds: [food.channelId, celsius.channelId])
        XCTAssertTrue(aligned.excluded.isEmpty)
        let fahrenheitSeries = try XCTUnwrap(aligned.series.first { $0.channelId == food.channelId })
        XCTAssertEqual(fahrenheitSeries.points.map(\.line), ["+1:00 · 150 °F", "+2:00 · 165 °F", "+4:00 · 195 °F", "+8:00 · 203 °F"])
        XCTAssertEqual(fahrenheitSeries.coverage, "4 recorded points · anchor recorded · values in °F")
        // The °C cook normalizes into the same °F comparison: 70 °C is 158 °F.
        let celsiusSeries = try XCTUnwrap(aligned.series.first { $0.channelId == celsius.channelId })
        XCTAssertEqual(celsiusSeries.points.map(\.line), ["+1:00 · 158 °F", "+3:00 · 176 °F", "+6:00 · 194 °F"])
        XCTAssertEqual(celsiusSeries.coverage, "3 recorded points · anchor recorded · values in °F")
    }

    func testCurvesAlignAtWrapWithNegativePreWrapOffsets() throws {
        let (_, report, archive, titles) = try snapshot("many")
        let options = ProAdvancedCurves.channelOptions(report: report, archive: archive, titles: titles)
        let food = try XCTUnwrap(options.first { $0.label == "Saturday pork butt · food · Probe 1 · 4 readings (°F)" })
        let aligned = ProAdvancedCurves.align(report: report, archive: archive, titles: titles, anchor: .wrap, channelIds: [food.channelId])
        XCTAssertTrue(aligned.excluded.isEmpty)
        let series = try XCTUnwrap(aligned.series.first)
        XCTAssertEqual(series.points.map(\.line), ["-2:20 · 150 °F", "-1:20 · 165 °F", "+0:40 · 195 °F", "+4:40 · 203 °F"])
    }

    func testCurvesExcludeChannelsWithoutTheAnchor() throws {
        let (_, report, archive, titles) = try snapshot("many")
        let options = ProAdvancedCurves.channelOptions(report: report, archive: archive, titles: titles)
        let brisket = try XCTUnwrap(options.first { $0.label == "Whole packer brisket · food · Probe 1 · 3 readings (°F)" })
        let chicken = try XCTUnwrap(options.first { $0.label == "Weeknight chicken · food · Probe 1 · 2 readings (°F)" })
        let aligned = ProAdvancedCurves.align(report: report, archive: archive, titles: titles, anchor: .wrap, channelIds: [brisket.channelId, chicken.channelId])
        XCTAssertTrue(aligned.series.isEmpty)
        XCTAssertEqual(aligned.excluded, [
            "Whole packer brisket · food · Probe 1: Wrap not recorded for this cook — excluded, never estimated.",
            "Weeknight chicken · food · Probe 1: Wrap not recorded for this cook — excluded, never estimated.",
        ])
    }

    // MARK: - Recap

    func testRecapFullScopeMatchesFixtureExpectation() throws {
        let (_, report, _, titles) = try snapshot("many")
        let scope = ProRecap.Scope(period: "2026", includedCookIds: ["cook-a", "cook-b", "cook-c", "cook-d"], showStats: true, showMilestones: true, showBest: true, note: "")
        let card = try XCTUnwrap(ProRecap.build(report: report, titles: titles, scope: scope, totalCookCount: 4))
        XCTAssertEqual(card.title, "Year in Smoke · 2026")
        XCTAssertEqual(card.dishes, ["Saturday pork butt", "February pork butt", "Whole packer brisket", "Weeknight chicken"])
        XCTAssertEqual(card.stats.map { "\($0.label): \($0.value)" }, ["Recorded cooks: 4", "Heating time: 28h", "Best recorded rating: 5 / 5"])
        XCTAssertEqual(card.milestones.count, 5)
        XCTAssertEqual(card.totalMilestones, 6)
        XCTAssertEqual(card.milestones.first?.title, "Wrapped +3:20 after Food on")
        XCTAssertEqual(card.unit, "°F")
        XCTAssertEqual(card.coverageNote, "4 of 4 recorded cooks selected · 6 recorded milestones · showing 5")
        XCTAssertEqual(card.privacyNote, "Only the 4 selected cooks appear in this recap. Sharing is a separate explicit step; nothing here changes what is public.")
        let root = try fixtureRoot()
        let recap = try XCTUnwrap((root["recap"] as? [String: Any])?["expectedFull"] as? [String: Any])
        XCTAssertEqual(card.title, recap["title"] as? String)
        XCTAssertEqual(card.dishes.count, recap["dishCount"] as? Int)
        XCTAssertEqual(card.stats.map { "\($0.label): \($0.value)" }, recap["stats"] as? [String])
        XCTAssertEqual(card.milestones.count, recap["milestoneCount"] as? Int)
    }

    func testRecapScopeExcludesUnselectedCooks() throws {
        let (_, report, _, titles) = try snapshot("many")
        let scope = ProRecap.Scope(period: "2026", includedCookIds: ["cook-a", "cook-b", "cook-c"], showStats: true, showMilestones: true, showBest: true, note: "")
        let card = try XCTUnwrap(ProRecap.build(report: report, titles: titles, scope: scope, totalCookCount: 4))
        XCTAssertEqual(card.dishes, ["Saturday pork butt", "February pork butt", "Whole packer brisket"])
        XCTAssertFalse(card.dishes.contains("Weeknight chicken"))
        XCTAssertEqual(card.stats.map { "\($0.label): \($0.value)" }, ["Recorded cooks: 3", "Heating time: 26h 30m", "Best recorded rating: 5 / 5"])
        XCTAssertFalse(card.milestones.contains { $0.dish == "Weeknight chicken" })
        XCTAssertEqual(card.coverageNote, "3 of 4 recorded cooks selected · 5 recorded milestones")
        XCTAssertEqual(card.privacyNote, "Only the 3 selected cooks appear in this recap. Sharing is a separate explicit step; nothing here changes what is public.")
        let root = try fixtureRoot()
        let recap = try XCTUnwrap((root["recap"] as? [String: Any])?["expectedExcluded"] as? [String: Any])
        XCTAssertEqual(card.dishes.count, recap["dishCount"] as? Int)
        XCTAssertEqual(card.stats.map { "\($0.label): \($0.value)" }, recap["stats"] as? [String])
    }

    func testRecapCardUsesShareCardSchemaAndEmptyScopeBuildsNothing() throws {
        let (_, report, _, titles) = try snapshot("many")
        let scope = ProRecap.Scope(period: "2026", includedCookIds: ["cook-a", "cook-d"], showStats: true, showMilestones: false, showBest: false, note: "")
        let card = try XCTUnwrap(ProRecap.build(report: report, titles: titles, scope: scope, totalCookCount: 4))
        XCTAssertEqual(card.stats.map(\.label), ["Recorded cooks", "Heating time"])
        XCTAssertTrue(card.milestones.isEmpty)
        XCTAssertEqual(card.coverageNote, "2 of 4 recorded cooks selected")
        let content = ProRecap.shareCardContent(card, themeId: "ember", paletteId: "color-vision-safe")
        let object = try XCTUnwrap(content.objectValue)
        XCTAssertEqual(object["version"]?.numberValue, 1)
        XCTAssertEqual(object["hero"]?.stringValue, "none")
        XCTAssertEqual(object["theme"]?.stringValue, "ember")
        XCTAssertEqual(object["palette"]?.stringValue, "color-vision-safe")
        XCTAssertEqual(object["relay"], .null)
        XCTAssertEqual(object["dishes"]?.arrayValue?.count, 2)
        XCTAssertLessThanOrEqual(object["milestones"]?.arrayValue?.count ?? 99, 5)
        XCTAssertLessThanOrEqual(object["stats"]?.arrayValue?.count ?? 99, 3)
        let empty = ProRecap.Scope(period: "2026", includedCookIds: [], showStats: true, showMilestones: true, showBest: true, note: "")
        XCTAssertNil(ProRecap.build(report: report, titles: titles, scope: empty, totalCookCount: 4))
    }

    // MARK: - Customization

    func testCustomizationPreviewApplyPersists() {
        let store = ProCustomizationStore(backing: DictionaryCustomizationBacking())
        XCTAssertEqual(store.themeId, "classic")
        XCTAssertEqual(store.paletteId, "classic")
        XCTAssertNil(store.profileAccentId)
        XCTAssertEqual(store.profileLayout, "standard")
        XCTAssertEqual(store.iconId, "classic")
        store.previewThemeId = "ember"
        XCTAssertEqual(store.themeId, "classic", "previewing must not persist")
        store.applyTheme("ember")
        XCTAssertEqual(store.themeId, "ember")
        XCTAssertNil(store.previewThemeId)
        store.applyPalette("color-vision-safe")
        XCTAssertEqual(store.paletteId, "color-vision-safe")
        store.applyProfile(accentId: "slate", layout: "compact")
        XCTAssertEqual(store.profileAccentId, "slate")
        XCTAssertEqual(store.profileLayout, "compact")
    }

    func testExpiredEntitlementFallsBackToClassicWithSelectionsRetained() {
        let store = ProCustomizationStore(backing: DictionaryCustomizationBacking())
        store.applyTheme("ember")
        store.applyPalette("color-vision-safe")
        store.applyProfile(accentId: "slate", layout: "compact")
        XCTAssertEqual(ProCustomization.effectiveTheme(storedId: store.themeId, entitled: false).id, "classic")
        XCTAssertEqual(ProCustomization.effectivePalette(storedId: store.paletteId, entitled: false).id, "classic")
        XCTAssertNil(ProCustomization.effectiveProfileAccent(identity: .named, storedId: store.profileAccentId, entitled: false))
        XCTAssertEqual(ProCustomization.effectiveProfileLayout(identity: .named, stored: store.profileLayout, entitled: false), "standard")
        XCTAssertEqual(ProCustomization.effectiveIcon(storedId: "ember", entitled: false).id, "classic")
        // The fallback never clears the stored selections: resubscribing restores them.
        XCTAssertEqual(store.themeId, "ember")
        XCTAssertEqual(store.paletteId, "color-vision-safe")
        XCTAssertEqual(store.profileAccentId, "slate")
        XCTAssertEqual(ProCustomization.effectiveTheme(storedId: store.themeId, entitled: true).id, "ember")
        XCTAssertEqual(ProCustomization.effectivePalette(storedId: store.paletteId, entitled: true).id, "color-vision-safe")
        // Unknown ids fail safe to Classic whether or not the account is entitled.
        XCTAssertEqual(ProCustomization.effectiveTheme(storedId: "nope", entitled: true).id, "classic")
        XCTAssertEqual(ProCustomization.effectivePalette(storedId: "nope", entitled: true).id, "classic")
        XCTAssertEqual(ProCustomization.effectiveIcon(storedId: "nope", entitled: true).id, "classic")
    }

    func testPaletteContrastMeetsThresholds() throws {
        let root = try fixtureRoot()
        let customization = try XCTUnwrap(root["customization"] as? [String: Any])
        let themes = try XCTUnwrap(customization["themes"] as? [[String: Any]])
        XCTAssertEqual(Set(themes.compactMap { $0["id"] as? String }), Set(ProCustomization.cardThemes.map(\.id)))
        for theme in themes {
            let id = try XCTUnwrap(theme["id"] as? String)
            let pinned = try XCTUnwrap(ProCustomization.cardThemes.first { $0.id == id })
            XCTAssertEqual(pinned.background, theme["background"] as? String)
            XCTAssertEqual(pinned.ink, theme["ink"] as? String)
            XCTAssertEqual(pinned.supporting, theme["supporting"] as? String)
            XCTAssertEqual(pinned.accent, theme["accent"] as? String)
            let ink = try XCTUnwrap(ProCustomization.contrastRatio(pinned.ink, pinned.background))
            let supporting = try XCTUnwrap(ProCustomization.contrastRatio(pinned.supporting, pinned.background))
            XCTAssertGreaterThanOrEqual(ink, 4.5, "\(id) ink")
            XCTAssertGreaterThanOrEqual(supporting, 4.5, "\(id) supporting")
        }
        let palettes = try XCTUnwrap(customization["chartPalettes"] as? [[String: Any]])
        XCTAssertEqual(Set(palettes.compactMap { $0["id"] as? String }), Set(ProCustomization.chartPalettes.map(\.id)))
        for palette in palettes {
            let id = try XCTUnwrap(palette["id"] as? String)
            let pinned = try XCTUnwrap(ProCustomization.chartPalettes.first { $0.id == id })
            XCTAssertEqual(pinned.colors, palette["colors"] as? [String])
            XCTAssertEqual(pinned.intendedBackground, palette["intendedBackground"] as? String)
            for hex in pinned.colors {
                let ratio = try XCTUnwrap(ProCustomization.contrastRatio(hex, pinned.intendedBackground))
                XCTAssertGreaterThanOrEqual(ratio, 3.0, "\(id) \(hex)")
            }
        }
    }

    func testAnonymousProjectionCarriesNoProIdentity() {
        XCTAssertEqual(
            ProCustomization.effectiveProfileAccent(identity: .named, storedId: "slate", entitled: true)?.id,
            "slate"
        )
        XCTAssertEqual(ProCustomization.effectiveProfileLayout(identity: .named, stored: "compact", entitled: true), "compact")
        for entitled in [true, false] {
            XCTAssertNil(
                ProCustomization.effectiveProfileAccent(identity: .anonymous, storedId: "slate", entitled: entitled),
                "anonymous content must never carry a Pro accent (entitled: \(entitled))"
            )
            XCTAssertEqual(
                ProCustomization.effectiveProfileLayout(identity: .anonymous, stored: "compact", entitled: entitled),
                "standard",
                "anonymous content must never carry a Pro layout (entitled: \(entitled))"
            )
        }
    }

    func testIconVariantNamesUsePlatformMechanism() {
        XCTAssertEqual(ProCustomization.iconVariants.map(\.id), ["classic", "ember", "midnight"])
        XCTAssertNil(ProCustomization.icon(id: "classic").alternateName)
        XCTAssertEqual(ProCustomization.icon(id: "ember").alternateName, "ProEmber")
        XCTAssertEqual(ProCustomization.icon(id: "midnight").alternateName, "ProMidnight")
        XCTAssertNil(ProCustomization.icon(id: "nope").alternateName, "unknown ids reset to the primary icon")
    }

    func testShareCardThemeResolutionFallsBackToClassic() {
        let classic = ShareCardRenderTheme.resolve(themeId: nil, paletteId: nil)
        XCTAssertEqual(classic.palette.count, 6)
        let explicit = ShareCardRenderTheme.resolve(themeId: "classic", paletteId: "classic")
        XCTAssertEqual(explicit.palette.count, 6)
        let unknown = ShareCardRenderTheme.resolve(themeId: "nope", paletteId: "nope")
        XCTAssertEqual(unknown.palette.count, 6)
        let night = ShareCardRenderTheme.resolve(themeId: "sage-night", paletteId: nil)
        XCTAssertEqual(night.palette.count, 6)
        // Ember keeps the Classic chart palette unless a palette is requested.
        let ember = ShareCardRenderTheme.resolve(themeId: "ember", paletteId: "color-vision-safe")
        XCTAssertEqual(ember.palette.count, 6)
    }

    // MARK: - Entitlement states

    func testEntitledOfflineCacheOpensProViews() throws {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let now = Date()
        let cache = try SubscriptionEntitlementCache.decode(Data("""
        {"version":"entitlement-cache-v1","environment":"production","verified":true,"state":"active","verifiedUntil":"\(formatter.string(from: now.addingTimeInterval(30 * 86400)))","lastVerifiedAt":"\(formatter.string(from: now.addingTimeInterval(-86400)))","offlineValidUntil":"\(formatter.string(from: now.addingTimeInterval(6 * 86400)))","contractVersion":"store-verification-v1"}
        """.utf8))
        XCTAssertTrue(cache.offlineUsable(now: now))
        XCTAssertEqual(cache.entitlement(now: now), .entitled)
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .entitled, serverState: "active", previouslyEntitled: true), .pro)
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .unknown, serverState: nil, previouslyEntitled: true), .loading)
        XCTAssertEqual(proDestination(signedIn: true, entitlement: .unknown, serverState: nil, previouslyEntitled: false), .loading)
        XCTAssertFalse(shouldRequestAds(entitlement: .entitled, previouslyEntitled: false))
        XCTAssertFalse(shouldRequestAds(entitlement: .unknown, previouslyEntitled: true))
    }

    // MARK: - Wording guards

    func testWordingGuardsForbidCausationDonenessAndOpaqueScores() throws {
        let (snapshot, report, archive, titles) = try snapshot("many")
        var strings: [String] = [
            ProPlacement.analysis.preview, ProPlacement.curves.preview,
            ProPlacement.themes.preview, ProPlacement.more.preview,
        ]
        strings += proPreviewBullets(entry: .analysis) + proPreviewBullets(entry: .curves)
        strings += proPreviewBullets(entry: .themes) + proPreviewBullets(entry: .more)
        for group in snapshot.cutGroups {
            strings += [group.header, group.coverage] + group.lines
        }
        if let best = snapshot.best { strings += best.lines + best.notes + [best.coverage] }
        if let timing = snapshot.timing { strings += timing.lines + [timing.coverage] }
        if let methods = snapshot.methods { strings += methods.lines + [methods.coverage] }
        let options = ProAdvancedCurves.channelOptions(report: report, archive: archive, titles: titles)
        strings += options.map(\.label)
        let aligned = ProAdvancedCurves.align(report: report, archive: archive, titles: titles, anchor: .foodOn, channelIds: Set(options.map(\.channelId)))
        for series in aligned.series {
            strings += [series.label, series.coverage] + series.points.map(\.line)
        }
        strings += aligned.excluded
        let scope = ProRecap.Scope.all(cookIds: report.cooks.map(\.cookId), period: "2026")
        if let card = ProRecap.build(report: report, titles: titles, scope: scope, totalCookCount: 4) {
            strings += [card.title, card.coverageNote, card.privacyNote, card.note] + card.dishes
            strings += card.stats.map { "\($0.label): \($0.value)" }
            strings += card.milestones.map { "\($0.dish) \($0.title)" }
        }
        strings += ProCustomization.cardThemes.map(\.name) + ProCustomization.chartPalettes.map(\.name)
        strings += ProCustomization.profileAccents.map(\.name) + ProCustomization.iconVariants.map(\.name)
        let banned = ["faster", "slower", "because", "caus", "predict", "doneness", "undercook", "overcook", "rank", "leaderboard", "score", "quality"]
        for string in strings {
            for word in banned {
                XCTAssertFalse(string.lowercased().contains(word), "\(string) contains \(word)")
            }
            // "safe" is banned except inside the required "color-vision safe"
            // palette name: no safe-doneness language anywhere.
            let scrubbed = string.lowercased().replacingOccurrences(of: "color-vision safe", with: "")
            XCTAssertFalse(scrubbed.contains("safe"), "\(string) contains safe")
            XCTAssertNil(string.lowercased().range(of: "\\bshould\\b", options: .regularExpression), "\(string) contains should")
        }
        // Bests are declared ratings, never a computed score: the word "rated"
        // appears, the word "score" never does.
        let best = try XCTUnwrap(snapshot.best)
        XCTAssertTrue(best.lines.joined().contains("rated"))
    }
}
