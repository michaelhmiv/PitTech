import XCTest
@testable import PitTech

/// Slice 4's day board over Slice 2's timeline engine: the headline lane,
/// the one-pit conflict resolution and the stretch that keeps every dish
/// landing together.
@MainActor final class PlanBoardTests: XCTestCase {
    private let serveAt: Int64 = 1_800_000_000_000

    private func plan(_ specs: [(name: String, pit: Double?, min: Int64, max: Int64)]) -> ServePlan {
        let book = CookPlaybook(
            name: "Board night",
            draft: PlaybookCookDraft(title: "Board night", dishes: specs.map { PlaybookDishDraft(name: $0.name, foodType: "Pork") }))
        return ServePlan(book: book, serveAtUtcMillis: serveAt, zoneId: "America/New_York", bufferMinutes: 60,
                         schedules: specs.enumerated().map { DishSchedule(dishIndex: $0.offset, cookMinMinutes: $0.element.min, cookMaxMinutes: $0.element.max, pitTemperatureF: $0.element.pit) })
    }

    func testHeadlineIsTheEarliestFoodOnWithIndexTieBreak() throws {
        let board = plan([("Quick", 250, 200, 300), ("Long", 250, 400, 460)])
        let timeline = PlanBoard.resolvedTimeline(plan: board, chosenPitF: nil)
        XCTAssertEqual(PlanBoard.headlineLane(timeline)?.dishIndex, 1)
        let tied = plan([("First", 250, 200, 300), ("Second", 250, 200, 300)])
        XCTAssertEqual(PlanBoard.headlineLane(PlanBoard.resolvedTimeline(plan: tied, chosenPitF: nil))?.dishIndex, 0)
        let window = try XCTUnwrap(PlanBoard.boardWindow(timeline))
        XCTAssertEqual(window.endUtcMillis, serveAt)
        XCTAssertLessThan(window.startUtcMillis, serveAt)
    }

    func testConflictingPitsResolveToTheHeadlinePitAndStretchTheRest() {
        // The shoulder starts first at 225°, so 225° is the default pit;
        // the ribs were planned hotter (250°) and stretch to land on time.
        let board = plan([("Shoulder", 225, 400, 460), ("Ribs", 250, 260, 300)])
        let timeline = PlanBoard.resolvedTimeline(plan: board, chosenPitF: nil)
        XCTAssertFalse(timeline.conflicts.isEmpty)
        XCTAssertEqual(timeline.chosenPitF, 225)
        XCTAssertEqual(PlanBoard.defaultPitF(timeline), 225)
        XCTAssertEqual(PlanBoard.conflictPitChoices(timeline), [225, 250])
        let stretched = PlanBoard.stretchedPlan(board, timeline: timeline)
        XCTAssertEqual(stretched.schedules[0], board.schedules[0], "The lane already at the chosen pit is untouched.")
        let ribs = stretched.schedules[1]
        XCTAssertEqual(ribs.pitTemperatureF, 225)
        XCTAssertGreaterThan(ribs.cookMaxMinutes, 300)
        XCTAssertTrue(ribs.evidenceNote.contains("Stretched +"), ribs.evidenceNote)
        XCTAssertTrue(ribs.evidenceNote.contains("(planned 250°)"), ribs.evidenceNote)
        // An explicit choice wins over the default.
        let other = PlanBoard.resolvedTimeline(plan: board, chosenPitF: 250)
        XCTAssertEqual(other.chosenPitF, 250)
    }

    func testUnconflictedPlanPassesThroughUnstretched() {
        let board = plan([("Shoulder", 250, 400, 460), ("Ribs", 250, 260, 300)])
        let timeline = PlanBoard.resolvedTimeline(plan: board, chosenPitF: nil)
        XCTAssertTrue(timeline.conflicts.isEmpty)
        XCTAssertEqual(PlanBoard.stretchedPlan(board, timeline: timeline), board)
    }

    func testLaneSpanStaysInsideTheBoardWindow() throws {
        let board = plan([("Shoulder", 250, 400, 460), ("Ribs", 225, 260, 300)])
        let timeline = PlanBoard.resolvedTimeline(plan: board, chosenPitF: nil)
        let window = try XCTUnwrap(PlanBoard.boardWindow(timeline))
        for lane in timeline.lanes {
            let span = PlanBoard.laneSpan(lane, window: window)
            XCTAssertGreaterThanOrEqual(span.start, 0)
            XCTAssertLessThanOrEqual(span.end, 1)
            XCTAssertLessThan(span.start, span.end)
        }
        XCTAssertEqual(PlanBoard.formatTemp(nil), "the chosen pit")
        XCTAssertEqual(PlanBoard.formatTemp(250), "250°")
        XCTAssertEqual(PlanBoard.formatTemp(247.5), "247.5°")
    }
}
