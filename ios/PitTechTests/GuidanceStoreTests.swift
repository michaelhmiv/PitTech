import XCTest
@testable import PitTech

/// Issue #183: guidance operations against a real store — Done writes
/// the timeline event and satisfies the step (Android's completeStep),
/// matching timeline actions satisfy steps on their own (onEvent),
/// and the occurrence guard refuses a stale completion.
@MainActor final class GuidanceStoreTests: XCTestCase {
    private func makeStore() throws -> (store: CookStore, cookId: String, dishId: String, folder: URL) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = CookStore(directory: folder)
        store.startCook(title: "Guidance cook", firstDish: "Brisket")
        let cookId = store.library.cooks[0].id
        let dishId = store.dishRecords(cookId)[0].id
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        try store.saveNote(CookNoteDraft(cookId: cookId, dishId: dishId, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: Double(now - 3_600_000)))
        let book = CookPlaybook(
            name: "Guidance book",
            draft: PlaybookCookDraft(title: "Guidance cook", dishes: [PlaybookDishDraft(name: "Brisket", foodType: "Beef")]),
            steps: [
                PlaybookStep(id: "wrap", dishIndex: 0, action: "wrap", title: "Wrap when bark sets", instructions: "Wrap in butcher paper.", trigger: "elapsed", minutes: 30),
                PlaybookStep(id: "rest", dishIndex: 0, action: "rest", title: "Rest the brisket", instructions: "Rest in a cooler.", trigger: "manual"),
            ])
        let plan = CookPlan(book: book, dishIds: [dishId])
        store.library.companionRecords.append(.object([
            "id": .string("plan:" + cookId), "kind": .string("plan"), "cookId": .string(cookId),
            "title": .string("Guidance book"), "payload": .string(try CookPlanCodec.encode(plan)),
            "createdAtUtcMillis": .number(Double(now)), "updatedAtUtcMillis": .number(Double(now)),
        ]))
        store.save()
        return (store, cookId, dishId, folder)
    }

    func testCompleteStepWritesEventAndProgress() throws {
        let (store, cookId, _, folder) = try makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let evaluated = store.evaluatedGuidance(cookId: cookId)
        let wrap = try XCTUnwrap(evaluated.first { $0.step.id == "wrap" })
        XCTAssertTrue(wrap.ready)
        try store.completeGuidanceStep(cookId: cookId, evaluated: wrap)
        let plan = try XCTUnwrap(store.guidancePlan(cookId: cookId))
        XCTAssertEqual(plan.progress["wrap"]?.status, "done")
        let events = store.library.events.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId }
        let written = try XCTUnwrap(events.first { $0["title"]?.stringValue == "Wrap when bark sets" })
        XCTAssertEqual(written["details"]?.stringValue, "Wrap in butcher paper.")
        // A stale completion (same occurrence) is refused.
        XCTAssertThrowsError(try store.completeGuidanceStep(cookId: cookId, evaluated: wrap))
    }

    func testTimelineActionSatisfiesStepOnItsOwn() throws {
        let (store, cookId, dishId, folder) = try makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        try store.saveNote(CookNoteDraft(cookId: cookId, dishId: dishId, metadata: CookNoteMetadata(category: "action", action: "wrap"), title: "Wrapped", occurredAt: Double(Int64(Date.now.timeIntervalSince1970 * 1000))))
        let plan = try XCTUnwrap(store.guidancePlan(cookId: cookId))
        XCTAssertEqual(plan.progress["wrap"]?.status, "done")
        XCTAssertEqual(plan.progress["wrap"]?.eventIds.count, 1)
    }

    func testSnoozeAndSkipAndPause() throws {
        let (store, cookId, _, folder) = try makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let before = Int64(Date.now.timeIntervalSince1970 * 1000)
        let wrap = try XCTUnwrap(store.evaluatedGuidance(cookId: cookId).first { $0.step.id == "wrap" })
        try store.snoozeGuidanceStep(cookId: cookId, evaluated: wrap)
        var plan = try XCTUnwrap(store.guidancePlan(cookId: cookId))
        let snoozedUntil = try XCTUnwrap(plan.progress["wrap"]?.snoozedUntil)
        XCTAssertGreaterThanOrEqual(snoozedUntil, before + 600_000)
        XCTAssertLessThan(snoozedUntil, before + 600_000 + 60_000)
        XCTAssertFalse(store.evaluatedGuidance(cookId: cookId).first { $0.step.id == "wrap" }!.ready)
        try store.skipGuidanceStep(cookId: cookId, stepId: "rest", useNowAsAnchor: true)
        plan = try XCTUnwrap(store.guidancePlan(cookId: cookId))
        XCTAssertEqual(plan.progress["rest"]?.status, "skipped")
        XCTAssertNotNil(plan.progress["rest"]?.skippedAnchorAt)
        try store.setGuidancePaused(cookId: cookId, paused: true)
        XCTAssertTrue(try XCTUnwrap(store.guidancePlan(cookId: cookId)).paused)
        XCTAssertTrue(store.evaluatedGuidance(cookId: cookId).allSatisfy { !$0.ready })
    }

    func testRestCompletionAlsoStartsTheRest() throws {
        let (store, cookId, _, folder) = try makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let rest = try XCTUnwrap(store.evaluatedGuidance(cookId: cookId).first { $0.step.id == "rest" })
        try store.completeGuidanceStep(cookId: cookId, evaluated: rest, rest: true)
        XCTAssertEqual(store.guidancePlan(cookId: cookId)?.progress["rest"]?.status, "done")
        XCTAssertTrue(store.library.actions(for: cookId).contains { $0.action == "rest_start" })
    }
}
