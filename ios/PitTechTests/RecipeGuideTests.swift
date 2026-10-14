import XCTest
@testable import PitTech

@MainActor final class RecipeGuideTests: XCTestCase {
    func testThreeGuidesPersistSelectionWhileActionsAndRemindersStayIndependent() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder), now = Double(Int64(Date.now.timeIntervalSince1970 * 1000)), sourceAnchor = Double(Int64(Date.now.timeIntervalSince1970 * 1000)) - 7200000
        var revisions: [String] = []
        for name in ["Chicken", "Mac", "Pork"] {
            store.startCook(title: name, firstDish: name)
            let cook = store.activeCooks[0].id, dish = store.dishRecords(cook)[0].id
            try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, title: "Prep " + name, occurredAt: sourceAnchor - 60000,
                temperatures: [NoteTemperatureDraft(probeName: "Same name", valueText: "74.125", unit: "°C", measuredAt: sourceAnchor - 60000, dishId: dish)]))
            try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: sourceAnchor))
            store.finishCook(cook)
            let recipe = try store.saveRecipe(store.extractRecipe(cookId: cook, dishId: dish), sourceCookId: cook, sourceDishId: dish)
            revisions.append(try XCTUnwrap(store.recipe(recipe)?["currentRevisionId"]?.stringValue))
        }
        store.library.cooks = []; store.library.dishes = []; store.library.events = []; store.library.readings = []
        store.library.probes = []; store.library.lifecycles = []; store.library.logReadingLinks = []
        let cook = try store.startRecipeMeal(title: "Three guides", revisionIds: revisions), dishes = store.dishRecords(cook)
        for (index, dish) in dishes.enumerated() {
            try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: now - 3600000 + Double(index * 600000)))
        }
        try store.saveReminderRule(cookId: cook, dishId: dishes[0].id, title: "My check", config: ["version": .number(1), "type": .string("historical"), "anchorAction": .string("food_on"), "offsetMillis": .number(7200000)], id: "guide-check")
        let before = store.library
        let selected = [dishes[1].id, dishes[2].id].sorted()
        try store.saveGuideState(cook, GuidePresentation(visible: true, dishIds: selected))
        XCTAssertEqual(store.alignedGuides(cook).count, 2)
        let reading = try XCTUnwrap(store.alignedGuides(cook).first?.readings.first)
        let dish = try XCTUnwrap(store.guides(cook).first { reading.dishId == $0.id })
        XCTAssertEqual(reading.measuredAt.timeIntervalSince1970 * 1000, store.guideAnchor(cook, dishId: dish.id)! - 60000, accuracy: 0.001)
        XCTAssertEqual(reading.value, 74.125); XCTAssertEqual(reading.unit, "°C")
        var working = dish.working; working.instructions = [.object(["id": .string("changed"), "title": .string("My own instruction")])]
        try store.updateRecipeAttempt(dish.id, snapshot: working)
        XCTAssertEqual(store.guides(cook).first { $0.id == dish.id }?.guide.snapshot.observations, dish.guide.snapshot.observations)
        try store.saveGuideState(cook, GuidePresentation(visible: false, dishIds: selected))
        XCTAssertTrue(store.alignedGuides(cook).isEmpty)
        XCTAssertEqual(store.library.events, before.events); XCTAssertEqual(store.library.readings, before.readings)
        XCTAssertEqual(store.library.reminderRules, before.reminderRules); XCTAssertEqual(store.library.reminderOccurrences, before.reminderOccurrences)
        XCTAssertTrue(store.library.recordings.isEmpty)
        let reopened = CookStore(directory: folder)
        XCTAssertEqual(reopened.guideState(cook), GuidePresentation(visible: false, dishIds: selected))
        XCTAssertEqual(reopened.reminderRule("guide-check")?["enabled"]?.boolValue, true)
        let destination = CookStore(directory: folder.appendingPathComponent("restored"))
        _ = try destination.importArchive(store.exportArchive())
        XCTAssertEqual(destination.guideState(cook).dishIds, selected)
        XCTAssertFalse(destination.reminderRule("guide-check")?["enabled"]?.boolValue ?? true)
    }
    func testSameNamedChannelsKeepGapsNegativeOffsetsAndOriginalPrecision() throws {
        let snapshot = RecipeSnapshot(version: 1, name: "Pork", description: "", tags: [], draft: .object(["dishes": .array([.object(["name": .string("Pork")])])]), instructions: [], observations: [], targets: [], resultNotes: "", smokingAnchorUtcMillis: 1000000, servings: "")
        func sample(_ id: String, _ probe: String, _ at: Double, quality: String = "valid") -> JSONValue {
            .object(["id": .string(id), "probeId": .string(probe), "probeName": .string("Same name"), "measurementType": .string("food"), "value": .number(74.125), "unit": .string("°C"), "measuredAtUtcMillis": .number(at), "source": .string("recorded"), "qualityStatus": .string(quality), "samplingIntervalMillis": .number(15000)])
        }
        let story: JSONValue = .object(["version": .number(1), "attemptId": .string("source-dish"), "anchorUtcMillis": .number(1000000), "observations": .array([]), "readings": .array([sample("a", "probe-a", 940000), sample("b", "probe-a", 955000), sample("c", "probe-a", 970000), sample("d", "probe-b", 970000), sample("e", "probe-a", 985000, quality: "unavailable"), sample("f", "probe-a", 1000000)]), "connectionGaps": .array([.number(960000)]), "results": .array([])])
        let guide = NativePinnedRecipeGuide(revisionId: "source-version", snapshot: snapshot, story: story)
        let projected = RecipeGuideProjection.aligned(guide, dishId: "current-dish", name: "Pork", anchor: 2000000)
        XCTAssertEqual(Set(projected.readings.map(\.seriesId)).count, 2)
        XCTAssertEqual(projected.readings.first?.measuredAt.timeIntervalSince1970, 1940)
        XCTAssertEqual(CookChartMath.segments(projected.readings, gaps: projected.gaps).count, 4)
        let corrected = RecipeGuideProjection.aligned(guide, dishId: "current-dish", name: "Pork", anchor: 2100000)
        XCTAssertEqual(corrected.readings.map { $0.measuredAt.timeIntervalSince1970 }, projected.readings.map { $0.measuredAt.timeIntervalSince1970 + 100 })
        XCTAssertEqual(corrected.originalTimes, projected.originalTimes)
        XCTAssertTrue(RecipeGuideProjection.aligned(guide, dishId: "current-dish", name: "Pork", anchor: nil).readings.isEmpty)
        XCTAssertNotEqual(RecipeGuideProjection.role("setpoint"), RecipeGuideProjection.role("pit"))
    }
}
