import XCTest
@testable import PitTech

/// Slice 15 free-feature audit at the store level: history, results, phase analysis,
/// raw chart inputs, and Show guide state all work signed-out and offline on this device.
@MainActor final class InsightsFreeToolsTests: XCTestCase {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    func testSignedOutHistoryPhasesVerdictsAndRawChartInputsStayLocal() async throws {
        let store = CookStore(directory: try folder())
        XCTAssertTrue(store.completedCooks.isEmpty)
        store.startCook(title: "Pork history", firstDish: "Shoulder")
        let cook = try XCTUnwrap(store.activeCooks.first), dish = try XCTUnwrap(store.dishRecords(cook.id).first)
        let foodOn = Date.now.addingTimeInterval(-7_200).timeIntervalSince1970 * 1_000
        try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: foodOn))
        try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, title: "Bark check", occurredAt: foodOn + 1_800_000,
            temperatures: [NoteTemperatureDraft(probeName: "Probe 1", valueText: "165", measuredAt: foodOn + 1_800_000, dishId: dish.id)]))
        try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "remove"), title: "Remove", occurredAt: foodOn + 5_400_000))
        try store.saveDishVerdict(cookId: cook.id, dishId: dish.id, rating: 4.5, again: true, notes: "Great bark")
        store.finishCook(cook.id)
        XCTAssertEqual(store.completedCooks.count, 1)
        XCTAssertEqual(store.completedCooks.first?.title, "Pork history")
        let phases = store.phases(cook.id)
        XCTAssertEqual(phases.count, 1)
        XCTAssertTrue(phases.single?.spans.contains { $0.phase == "cooking" } == true)
        XCTAssertEqual(phases.single?.cooking ?? 0, 5_400_000, accuracy: 1)
        let verdict = store.dishVerdict(cookId: cook.id, dishId: dish.id)
        XCTAssertEqual(verdict.rating, 4.5)
        XCTAssertEqual(verdict.again, true)
        XCTAssertEqual(verdict.notes, "Great bark")
        let samples = store.readings(for: cook.id)
        XCTAssertEqual(samples.count, 1)
        XCTAssertEqual(samples.single?.value, 165)
        XCTAssertEqual(samples.single?.unit, "°F")
        let segments = CookChartMath.segments(samples)
        XCTAssertEqual(segments.count, 1)
        XCTAssertEqual(store.events(for: cook.id).count, 3)
    }

    func testShowGuideDefaultsOffAndPersistsPerMeal() async throws {
        let directory = try folder()
        let store = CookStore(directory: directory)
        store.startCook(title: "Guided pork", firstDish: "Shoulder")
        let cook = try XCTUnwrap(store.activeCooks.first)
        XCTAssertFalse(store.guideState(cook.id).visible)
        try store.saveGuideState(cook.id, GuidePresentation(visible: true))
        XCTAssertTrue(store.guideState(cook.id).visible)
        let reopened = CookStore(directory: directory)
        XCTAssertTrue(reopened.guideState(cook.id).visible)
        try reopened.saveGuideState(cook.id, GuidePresentation(visible: false))
        XCTAssertFalse(CookStore(directory: directory).guideState(cook.id).visible)
    }

    func testChartComparisonNeedsMatchingAnchoredChannels() async throws {
        let store = CookStore(directory: try folder())
        for title in ["First ribs", "Second ribs"] {
            store.startCook(title: title, firstDish: "Rack")
            let cook = try XCTUnwrap(store.activeCooks.first { $0.title == title }), dish = try XCTUnwrap(store.dishRecords(cook.id).first)
            let foodOn = Date.now.addingTimeInterval(-7_200).timeIntervalSince1970 * 1_000
            try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: foodOn))
            try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, title: "Check", occurredAt: foodOn + 1_800_000,
                temperatures: [NoteTemperatureDraft(probeName: "Probe 1", valueText: "165", measuredAt: foodOn + 1_800_000, dishId: dish.id)]))
            store.finishCook(cook.id)
        }
        XCTAssertEqual(store.completedCooks.count, 2)
        let cooks = store.completedCooks
        let channels = cooks.map { store.readings(for: $0.id).map(\.seriesId) }
        XCTAssertEqual(channels[0].count, 1)
        XCTAssertEqual(channels[1].count, 1)
        let anchors = cooks.map { store.library.actions(for: $0.id).filter { $0.action == "food_on" }.count }
        XCTAssertEqual(anchors, [1, 1])
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
