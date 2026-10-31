import XCTest
@testable import PitTech

/// Issue #183: the guidance plan codec + engine — the payload shape is
/// shared with Android byte-for-byte in structure, so the round-trip
/// and the Android-shaped literal both have to decode.
final class CookPlanTests: XCTestCase {
    private func book() -> CookPlaybook {
        CookPlaybook(
            name: "Test book",
            draft: PlaybookCookDraft(title: "Test cook", dishes: [PlaybookDishDraft(name: "Brisket", foodType: "Beef")]),
            steps: [
                PlaybookStep(id: "wrap", dishIndex: 0, action: "wrap", title: "Wrap", instructions: "Paper.", trigger: "elapsed", minutes: 30),
                PlaybookStep(id: "rest", dishIndex: 0, action: "rest", title: "Rest", trigger: "manual"),
            ])
    }

    func testCodecRoundTripsPlanAndProgress() throws {
        var plan = CookPlan(book: book(), dishIds: ["dish-1"], playbookId: "book-1", paused: true)
        plan.progress["wrap"] = StepProgress(status: "done", eventIds: ["e1"], lastDoneAt: 123, occurrence: 1)
        let decoded = try CookPlanCodec.decode(CookPlanCodec.encode(plan))
        XCTAssertEqual(decoded, plan)
    }

    func testCodecDecodesAndroidShapedPayload() throws {
        // The exact field layout CookPlanEngine.encode writes on
        // Android: book fields at the top level (draft keys smoker /
        // setpoint / unit / fuel / wood; step keys dish / clock /
        // repeat), plan fields merged in, progress with JSON nulls.
        let raw = """
        {"version":1,"name":"Android book","draft":{"title":"T","smoker":"","setpoint":"","unit":"°F","notes":"","fuel":"","wood":"","dishes":[{"name":"Brisket","foodType":"Beef","cut":"","weightText":"","weightUnit":"lb","placement":"","grade":"","thickness":"","prep":"","ingredients":[]}]},"steps":[{"id":"s1","dish":0,"action":"wrap","title":"Wrap","instructions":"Paper.","trigger":"elapsed","minutes":30,"anchor":"food_on","stage":"cooking","observations":"","probeName":""}],"targets":[],"revision":1,"keepDoing":"","changeNextTime":"","dishIds":["d1"],"playbookId":null,"paused":false,"progress":{"s1":{"status":"pending","eventIds":[],"lastDoneAt":null,"snoozedUntil":null,"occurrence":0,"notifiedOccurrence":null,"skippedAnchorAt":null}}}
        """
        let plan = try CookPlanCodec.decode(raw)
        XCTAssertEqual(plan.book.name, "Android book")
        XCTAssertEqual(plan.dishIds, ["d1"])
        XCTAssertNil(plan.playbookId)
        XCTAssertEqual(plan.book.steps.first?.dishIndex, 0)
        XCTAssertEqual(plan.progress["s1"]?.status, "pending")
        XCTAssertEqual(plan.progress["s1"]?.occurrence, 0)
    }

    func testCodecRejectsDishMappingMismatch() throws {
        let plan = CookPlan(book: book(), dishIds: ["dish-1", "dish-2"])
        let raw = try CookPlanCodec.encode(plan)
        XCTAssertThrowsError(try CookPlanCodec.decode(raw))
    }

    func testStageFollowsLatestAction() {
        let on = GuidanceEvent(id: "1", action: "food_on", dishId: "d1", at: 100)
        let off = GuidanceEvent(id: "2", action: "remove", dishId: "d1", at: 200)
        let rest = GuidanceEvent(id: "3", action: "rest_start", dishId: "d1", at: 300)
        XCTAssertEqual(CookPlanEngine.stage(events: [], dishId: "d1"), "prep")
        XCTAssertEqual(CookPlanEngine.stage(events: [on], dishId: "d1"), "cooking")
        XCTAssertEqual(CookPlanEngine.stage(events: [on, off], dishId: "d1"), "removed")
        XCTAssertEqual(CookPlanEngine.stage(events: [on, off, rest], dishId: "d1"), "resting")
        XCTAssertEqual(CookPlanEngine.stage(events: [on], dishId: "other"), "prep")
    }

    func testElapsedStepBecomesReadyAtItsDueTime() {
        let plan = CookPlan(book: book(), dishIds: ["dish-1"])
        let events = [GuidanceEvent(id: "e1", action: "food_on", dishId: "dish-1", at: 1_000_000)]
        let early = CookPlanEngine.evaluate(plan: plan, events: events, readings: [], now: 1_000_000 + 29 * 60_000)
        XCTAssertEqual(early[0].dueAt, 1_000_000 + 30 * 60_000)
        XCTAssertFalse(early[0].ready)
        let due = CookPlanEngine.evaluate(plan: plan, events: events, readings: [], now: 1_000_000 + 30 * 60_000)
        XCTAssertTrue(due[0].ready)
        // The manual step is stage-gated to "cooking" and ready now.
        XCTAssertTrue(due[1].ready)
    }

    func testElapsedStepWithoutAnchorWaits() {
        let plan = CookPlan(book: book(), dishIds: ["dish-1"])
        let evaluated = CookPlanEngine.evaluate(plan: plan, events: [], readings: [], now: 9_999_999)
        XCTAssertFalse(evaluated[0].ready)
        // Stage gate wins: the dish is still in prep, not yet cooking.
        XCTAssertEqual(evaluated[0].waitingFor, "Waiting for cooking")
    }

    func testSatisfyCompletesNonRepeatingAndAdvancesRepeating() {
        var repeatingBook = book()
        repeatingBook.steps[0].repeatEveryMinutes = 45
        let plan = CookPlan(book: repeatingBook, dishIds: ["dish-1"])
        let event = GuidanceEvent(id: "e9", action: "wrap", dishId: "dish-1", at: 5000)
        let once = CookPlanEngine.satisfy(plan: plan, step: repeatingBook.steps[0], event: event)
        XCTAssertEqual(once.progress["wrap"]?.status, "pending")
        XCTAssertEqual(once.progress["wrap"]?.occurrence, 1)
        XCTAssertEqual(once.progress["wrap"]?.lastDoneAt, 5000)
        // The same event never counts twice.
        XCTAssertEqual(CookPlanEngine.satisfy(plan: once, step: repeatingBook.steps[0], event: event), once)
        let plain = CookPlan(book: book(), dishIds: ["dish-1"])
        let done = CookPlanEngine.satisfy(plan: plain, step: book().steps[0], event: event)
        XCTAssertEqual(done.progress["wrap"]?.status, "done")
    }
}
