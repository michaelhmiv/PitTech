import XCTest
@testable import PitTech

/// Mirrors Android's PlanTimelineTest: lanes, derived alerts, and the
/// book's canonical warn-and-stretch case (+35 min for 275° ribs at 250°).
final class PlanTimelineTests: XCTestCase {
    /// Saturday Oct 31, 2026, 6:00 PM in America/New_York (EDT, UTC−4).
    private var serveAt: Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York")!
        let date = cal.date(from: DateComponents(year: 2026, month: 10, day: 31, hour: 18))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    private func canonicalInput() -> PlanInput {
        PlanInput(
            serveAtUtcMillis: serveAt,
            smokerName: "Austin XL",
            schedules: [
                PlanDishSchedule(dishIndex: 0, dishName: "Brisket", cookMinMinutes: 600, cookMaxMinutes: 780, restMinutes: 60, pitF: 250),
                PlanDishSchedule(dishIndex: 1, dishName: "Ribs", cookMinMinutes: 240, cookMaxMinutes: 280, restMinutes: 15, pitF: 275),
                PlanDishSchedule(dishIndex: 2, dishName: "Beans", cookMinMinutes: 90, cookMaxMinutes: 120, restMinutes: 0, pitF: 250),
            ],
            wrapSteps: [PlanWrapStep(dishIndex: 0, trigger: "elapsed", minutes: 360)])
    }

    func testLanesCarryFinishTargetsAgainstTheServeLine() {
        let input = canonicalInput()
        let timeline = PlanTimeline.derive(input)
        XCTAssertEqual(timeline.lanes.count, 3)
        let brisket = timeline.lanes.first { $0.dishIndex == 0 }!
        // Latest remove = serve − rest(60) − buffer(60) = 16:00; food on = 16:00 − 780m = 03:00.
        XCTAssertEqual(brisket.foodOnAtUtcMillis, input.serveAtUtcMillis - (60 + 60 + 780) * 60_000)
        XCTAssertEqual(brisket.readyLatestUtcMillis, input.serveAtUtcMillis - 60 * 60_000)
        XCTAssertTrue(timeline.conflicts.contains(PlanConflict(dishIndexA: 0, dishIndexB: 1)))
    }

    func testAlertsAreDerivedFromTheLanesNeverReEntered() {
        let timeline = PlanTimeline.derive(canonicalInput())
        let brisket = timeline.lanes.first { $0.dishIndex == 0 }!
        XCTAssertEqual(timeline.alerts.filter { $0.kind == .foodOn }.count, 3, "one food-on alert per dish")
        let wrap = timeline.alerts.first { $0.kind == .wrap }!
        XCTAssertEqual(wrap.dishIndex, 0)
        XCTAssertEqual(wrap.atUtcMillis, brisket.foodOnAtUtcMillis + 360 * 60_000, "wrap fires from the step's elapsed trigger")
        let offs = timeline.alerts.filter { $0.kind == .off }
        XCTAssertEqual(offs.count, 3)
        XCTAssertEqual(offs.first { $0.dishIndex == 0 }!.atUtcMillis, brisket.removeEarliestUtcMillis)
    }

    func testTemperatureTriggeredWrapIsAnAlertWithoutAFixedTime() {
        var input = canonicalInput()
        input.wrapSteps = [PlanWrapStep(dishIndex: 0, trigger: "temperature", temperatureF: 165)]
        let wrap = PlanTimeline.derive(input).alerts.first { $0.kind == .wrap }!
        XCTAssertNil(wrap.atUtcMillis)
        XCTAssertEqual(wrap.temperatureF, 165)
    }

    func testWarnAndStretchHitsTheBooksCanonicalNumbers() {
        let input = canonicalInput()
        let resolved = PlanTimeline.applyPitChoice(input, chosenPitF: 250)
        let ribs = resolved.lanes.first { $0.dishIndex == 1 }!
        let stretch = try! XCTUnwrap(ribs.stretch, "ribs must stretch in a 250° pit")
        // 280 max × (1 + 0.005 × 25°) = 315 → +35 minutes, the book's number.
        XCTAssertEqual(stretch.addedMinutes, 35)
        XCTAssertEqual(stretch.adjustedCookMaxMinutes, 315)
        XCTAssertTrue(stretch.basis.contains("+35 min"))
        XCTAssertTrue(stretch.basis.contains("250°"))
        XCTAssertTrue(stretch.basis.contains("275°"))
        let unstretched = PlanTimeline.derive(input).lanes.first { $0.dishIndex == 1 }!
        XCTAssertEqual(ribs.foodOnAtUtcMillis, unstretched.foodOnAtUtcMillis - 35 * 60_000, "the stretched dish goes on earlier")
        XCTAssertEqual(ribs.removeLatestUtcMillis, unstretched.removeLatestUtcMillis, "and still lands on the serve line")
        XCTAssertNil(resolved.lanes.first { $0.dishIndex == 0 }!.stretch)
        XCTAssertNil(resolved.lanes.first { $0.dishIndex == 2 }!.stretch)
        XCTAssertEqual(resolved.chosenPitF, 250)
    }

    func testAHotterChosenPitDoesNotStretch() {
        let resolved = PlanTimeline.applyPitChoice(canonicalInput(), chosenPitF: 275)
        XCTAssertTrue(resolved.lanes.allSatisfy { $0.stretch == nil })
    }
}
