import XCTest
@testable import PitTech

final class InsightsCalculationsTests: XCTestCase {
    private struct Fixture: Decodable {
        let contractVersion: Int
        let cases: [Case]
    }
    private struct Case: Decodable {
        let name: String
        let asOfUtcMillis: Double
        let archive: InsightArchiveInput
        let expected: InsightReport
    }

    private func assertEqual(_ expected: Double?, _ actual: Double?, _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        if expected == nil { XCTAssertNil(actual, message, file: file, line: line); return }
        XCTAssertEqual(expected!, actual ?? .nan, accuracy: InsightsCalculations.epsilon, message, file: file, line: line)
    }

    private func assertEqual(_ expected: InsightReport, _ actual: InsightReport, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(expected.calculationVersion, actual.calculationVersion, file: file, line: line)
        XCTAssertEqual(expected.cooks.count, actual.cooks.count, file: file, line: line)
        for (want, got) in zip(expected.cooks, actual.cooks) {
            XCTAssertEqual(want.cookId, got.cookId, file: file, line: line)
            assertEqual(want.equipmentHeatingMillis, got.equipmentHeatingMillis, want.cookId, file: file, line: line)
            XCTAssertEqual(want.channels.count, got.channels.count, want.cookId, file: file, line: line)
            for (wc, gc) in zip(want.channels, got.channels) {
                XCTAssertEqual(wc.channelId, gc.channelId, file: file, line: line)
                XCTAssertEqual(wc.dishId, gc.dishId, file: file, line: line)
                XCTAssertEqual(wc.role, gc.role, file: file, line: line)
                XCTAssertEqual(wc.readingCount, gc.readingCount, file: file, line: line)
                XCTAssertEqual(wc.manualCount, gc.manualCount, file: file, line: line)
                assertEqual(wc.minF, gc.minF, wc.channelId, file: file, line: line)
                assertEqual(wc.maxF, gc.maxF, wc.channelId, file: file, line: line)
                XCTAssertEqual(wc.units, gc.units, file: file, line: line)
            }
            XCTAssertEqual(want.dishes.count, got.dishes.count, want.cookId, file: file, line: line)
            for (wd, gd) in zip(want.dishes, got.dishes) {
                XCTAssertEqual(wd.dishId, gd.dishId, file: file, line: line)
                XCTAssertEqual(wd.cut, gd.cut, file: file, line: line)
                assertEqual(wd.weightValue, gd.weightValue, wd.dishId, file: file, line: line)
                XCTAssertEqual(wd.weightUnit, gd.weightUnit, file: file, line: line)
                XCTAssertEqual(wd.methods, gd.methods, file: file, line: line)
                assertEqual(wd.foodOnAt, gd.foodOnAt, wd.dishId, file: file, line: line)
                assertEqual(wd.wrapAt, gd.wrapAt, wd.dishId, file: file, line: line)
                assertEqual(wd.removeAt, gd.removeAt, wd.dishId, file: file, line: line)
                assertEqual(wd.restStartAt, gd.restStartAt, wd.dishId, file: file, line: line)
                assertEqual(wd.holdStartAt, gd.holdStartAt, wd.dishId, file: file, line: line)
                assertEqual(wd.dishDoneAt, gd.dishDoneAt, wd.dishId, file: file, line: line)
                assertEqual(wd.heatingMillis, gd.heatingMillis, wd.dishId, file: file, line: line)
                assertEqual(wd.restMillis, gd.restMillis, wd.dishId, file: file, line: line)
                assertEqual(wd.holdMillis, gd.holdMillis, wd.dishId, file: file, line: line)
                XCTAssertEqual(wd.heatingIntervalCount, gd.heatingIntervalCount, file: file, line: line)
                XCTAssertEqual(wd.validReadings, gd.validReadings, file: file, line: line)
                XCTAssertEqual(wd.manualReadings, gd.manualReadings, file: file, line: line)
                XCTAssertEqual(wd.excludedReadings, gd.excludedReadings, file: file, line: line)
                assertEqual(wd.rating, gd.rating, wd.dishId, file: file, line: line)
                XCTAssertEqual(wd.cookAgain, gd.cookAgain, file: file, line: line)
                XCTAssertEqual(wd.hasResultNotes, gd.hasResultNotes, file: file, line: line)
            }
        }
    }

    func testSharedGoldenArchivesProduceVersionedDishMetrics() throws {
        let bundle = Bundle(for: Self.self)
        let data = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "insights-calculations-cases-v1", withExtension: "json")))
        let fixture = try JSONDecoder().decode(Fixture.self, from: data)
        XCTAssertEqual(fixture.contractVersion, 1); XCTAssertEqual(fixture.cases.count, 14)
        for c in fixture.cases {
            XCTAssertEqual(c.expected.calculationVersion, 1, c.name)
            assertEqual(c.expected, InsightsCalculations.compute(c.archive, asOf: c.asOfUtcMillis))
        }
        let contract = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "insights-calculations-v1", withExtension: "json")))
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: contract).objectValue?["calculationVersion"], .number(1))
        XCTAssertEqual(InsightsCalculations.calculationVersion, 1)
    }

    func testMissingAnchorsStayMissingAndEquipmentUnionNeverDoubleCounts() {
        let archive = InsightArchiveInput(
            cooks: [InsightCookInput(id: "c", startedAtUtcMillis: 1_000, endedAtUtcMillis: 9_000, timingMode: "explicit_phases")],
            dishes: [InsightDishInput(id: "d", cookId: "c", cut: nil, weightValue: nil, weightUnit: nil)],
            events: [], readings: [], results: [],
            lifecycles: [InsightLifecycleInput(cookId: "c", timingMode: "explicit_phases")]
        )
        let dish = InsightsCalculations.compute(archive, asOf: 9_000).cooks.single!.dishes.single!
        XCTAssertNil(dish.heatingMillis); XCTAssertNil(dish.foodOnAt); XCTAssertNil(dish.rating)
        let overlap = InsightArchiveInput(
            cooks: [InsightCookInput(id: "c", startedAtUtcMillis: 0, endedAtUtcMillis: 100, timingMode: "explicit_phases")],
            dishes: [InsightDishInput(id: "a", cookId: "c", cut: nil, weightValue: nil, weightUnit: nil), InsightDishInput(id: "b", cookId: "c", cut: nil, weightValue: nil, weightUnit: nil)],
            events: [
                InsightEventInput(id: "1", cookId: "c", dishIds: ["a"], action: "food_on", at: 0),
                InsightEventInput(id: "2", cookId: "c", dishIds: ["a"], action: "remove", at: 100),
                InsightEventInput(id: "3", cookId: "c", dishIds: ["b"], action: "food_on", at: 0),
                InsightEventInput(id: "4", cookId: "c", dishIds: ["b"], action: "remove", at: 100),
            ],
            readings: [], results: [],
            lifecycles: [InsightLifecycleInput(cookId: "c", timingMode: "explicit_phases")]
        )
        let cook = InsightsCalculations.compute(overlap, asOf: 100).cooks.single!
        XCTAssertEqual(cook.dishes.map(\.heatingMillis), [100, 100])
        XCTAssertEqual(cook.equipmentHeatingMillis, 100)
    }

    func testCelsiusFahrenheitRoundTripStaysStable() {
        for f in [32.0, 150.0, 212.0, 225.0, 100.0] {
            let c = (f - 32) * 5 / 9
            XCTAssertEqual(f, c * 9 / 5 + 32, accuracy: InsightsCalculations.epsilon)
            XCTAssertEqual(f, InsightsCalculations.fahrenheit(c, unit: "°C"), accuracy: InsightsCalculations.epsilon)
        }
    }

    func testComparisonContextShowsRecordedDifferencesWithoutCausalClaims() {
        let lines = InsightsCalculations.comparisonContext(
            currentDish: InsightDishSummary(dishId: "a", cut: "Butt", weightValue: 8.5, weightUnit: "lb", methods: ["Smoked"]),
            sourceDish: InsightDishSummary(dishId: "b", cut: nil, weightValue: nil, weightUnit: nil, methods: []),
            current: InsightReadingSummary(units: ["°F"], validCount: 3, manualCount: 1, excludedCount: 1, roles: ["food", "pit"]),
            source: InsightReadingSummary(units: ["°C"], validCount: 2, manualCount: 0, excludedCount: 0, roles: ["food"])
        )
        XCTAssertEqual(lines, [
            "Methods: Smoked · not recorded",
            "Weights: 8.5 lb · not recorded",
            "Reading units: °F · °C",
            "Recorded readings: 3 (1 manual), 1 unavailable · 2",
            "Channels: food, pit · food",
        ])
        for line in lines {
            for banned in ["faster", "slower", "because", "caus", "predict", "safe", "doneness", "undercook", "overcook", "better", "worse", "should"] {
                XCTAssertFalse(line.lowercased().contains(banned), line)
            }
        }
    }

    @MainActor func testLibraryAdapterMatchesEngineOverLocalCooks() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        store.startCook(title: "Adapter pork", firstDish: "Shoulder")
        let cook = try XCTUnwrap(store.activeCooks.first), dish = try XCTUnwrap(store.dishRecords(cook.id).first)
        let foodOn = Date.now.addingTimeInterval(-7_200).timeIntervalSince1970 * 1_000
        try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: foodOn))
        try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, title: "Check", occurredAt: foodOn + 3_600_000,
            temperatures: [NoteTemperatureDraft(probeName: "Probe 1", valueText: "165", measuredAt: foodOn + 3_600_000, dishId: dish.id)]))
        try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "remove"), title: "Remove", occurredAt: foodOn + 5_400_000))
        try store.adoptTiming(cook.id, foodOn: nil)
        try store.saveDishVerdict(cookId: cook.id, dishId: dish.id, rating: 4.5, again: true, notes: "Great bark")
        let report = InsightsCalculations.compute(store.insightsArchive(), asOf: Date.now.timeIntervalSince1970 * 1_000)
        let metrics = try XCTUnwrap(report.cooks.first { $0.cookId == cook.id }?.dishes.first)
        XCTAssertEqual(report.calculationVersion, 1)
        XCTAssertEqual(try XCTUnwrap(metrics.foodOnAt), foodOn, accuracy: 1)
        XCTAssertEqual(try XCTUnwrap(metrics.heatingMillis), 5_400_000, accuracy: 1)
        XCTAssertEqual(metrics.validReadings, 1)
        XCTAssertEqual(metrics.rating, 4.5)
        XCTAssertEqual(metrics.cookAgain, true)
        XCTAssertTrue(metrics.hasResultNotes)
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
