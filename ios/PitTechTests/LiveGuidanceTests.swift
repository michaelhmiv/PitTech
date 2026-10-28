import XCTest
import UserNotifications
@testable import PitTech

/// Flowing Slice 2: the verdict line's merge order and the ETA's honesty
/// shapes (spec §5 elements 1.1–1.2), plus the ghost delta (2.4), the
/// same-cut history selection the Live board feeds the pace engine, and
/// the overnight test alert's request shape (E3). Mirrors Android's
/// LiveGuidanceTest case for case.
final class LiveGuidanceTests: XCTestCase {
    private func verdict(
        attention: Int = 0,
        stall: StallState = .notInStall,
        finish: FinishEstimate = .planOnly("not enough readings yet"),
        next: (title: String, dueUtcMillis: Int64)? = nil,
        now: Int64 = 1_000_000,
        eta: LiveEta = .none
    ) -> LiveVerdict {
        LiveGuidance.verdict(
            needsAttention: attention, stall: stall, finish: finish, nextReminder: next,
            nowUtcMillis: now, eta: eta,
            formatTime: { "T\($0)" }, formatDuration: { "\(Int($0))m" })
    }

    func testAttentionBeatsEverything() {
        let v = verdict(attention: 2, stall: .inStall(sinceUtcMillis: 500_000, elapsedMinutes: 52, historicalAverageStallMinutes: 130))
        XCTAssertEqual(v.headline, "2 alerts need attention.")
        XCTAssertEqual(verdict(attention: 1).headline, "An alert needs attention.")
    }

    func testStallIsCalmAndSpecific() {
        let v = verdict(stall: .inStall(sinceUtcMillis: 500_000, elapsedMinutes: 52, historicalAverageStallMinutes: 130))
        XCTAssertEqual(v.headline, "In the stall — normal. Nothing needs doing.")
        XCTAssertEqual(v.detail, "In the stall since T500000 · 52m in · your average stall for this cut is 130m.")
        let noHistory = verdict(stall: .inStall(sinceUtcMillis: 500_000, elapsedMinutes: 52, historicalAverageStallMinutes: nil))
        XCTAssertEqual(noHistory.detail, "In the stall since T500000 · 52m in.")
    }

    func testUpcomingReminderOutranksPlainPace() {
        let eta = LiveEta.ranged(text: "Done R1-2", basis: "from your last 30 min of readings")
        let v = verdict(next: (title: "Wrap the brisket", dueUtcMillis: 1_020_000), eta: eta)
        XCTAssertEqual(v.headline, "Next: Wrap the brisket at T1020000.")
        XCTAssertEqual(v.detail, "Done R1-2 · from your last 30 min of readings")
        // A reminder hours away does not lead; pace does.
        let later = verdict(next: (title: "Wrap the brisket", dueUtcMillis: 9_000_000), eta: eta)
        XCTAssertEqual(later.headline, "On pace — nothing needs doing.")
    }

    func testFinishedAndQuietDefaults() {
        XCTAssertEqual(verdict(finish: .finished).headline, "At target — ready when you are.")
        XCTAssertEqual(verdict().headline, "Nothing needs doing.")
        XCTAssertNil(verdict().detail)
    }

    func testEtaShapesCarryTheirBasis() {
        let ranged = LiveGuidance.eta(finish: .ranged(FinishRange(earliestUtcMillis: 100, latestUtcMillis: 200, basis: .pace)), planWindow: nil, formatRange: { "R\($0)-\($1)" })
        guard case .ranged(let text, let basis) = ranged else { return XCTFail("expected ranged, got \(ranged)") }
        XCTAssertEqual(text, "Done R100-200")
        XCTAssertEqual(basis, "from your last 30 min of readings")

        // PlanOnly with a serve plan: the plan window stands, with the plan basis.
        let planned = LiveGuidance.eta(finish: .planOnly("not enough readings yet"), planWindow: (300, 400), formatRange: { "R\($0)-\($1)" })
        guard case .plan(let planText, let planBasis) = planned else { return XCTFail("expected plan, got \(planned)") }
        XCTAssertEqual(planText, "Planning window R300-400")
        XCTAssertEqual(planBasis, "from your plan — not enough readings yet")

        // PlanOnly with no plan: nothing is invented — the reason is the line.
        let withheld = LiveGuidance.eta(finish: .planOnly("not enough readings yet"), planWindow: nil, formatRange: { "R\($0)-\($1)" })
        guard case .withheld(let reason) = withheld else { return XCTFail("expected withheld, got \(withheld)") }
        XCTAssertEqual(reason, "not enough readings yet")
        XCTAssertEqual(withheld.detail, "No finish range yet — not enough readings yet")
    }

    func testGhostDeltaIsTimeAtSameTemperature() {
        func series(_ points: [(Int64, Double)]) -> [PaceReading] { points.map { PaceReading(atUtcMillis: $0.0 * 60_000, fahrenheit: $0.1) } }
        let current = series([(0, 40), (60, 120), (120, 165), (180, 190)])
        let ghost = series([(0, 40), (60, 110), (120, 150), (180, 172), (240, 195)])
        // At 120 min in, the current cook sits at 165°; the ghost reached
        // 165° at ~158 min — the cook is ~38 minutes ahead of it.
        XCTAssertEqual(LiveGuidance.ghostDeltaMinutes(current: current, ghost: ghost, cursorElapsedMinutes: 120), 38)
        XCTAssertNil(LiveGuidance.ghostDeltaMinutes(current: current, ghost: ghost, cursorElapsedMinutes: 300))
        XCTAssertNil(LiveGuidance.ghostDeltaMinutes(current: current, ghost: series([(0, 40)]), cursorElapsedMinutes: 120))
    }

    func testHistoryNeedsSameCutFinishedCooksAndRecordedTargets() {
        func archive(dishes: [InsightDishInput], results: [InsightResultInput], cooks: [InsightCookInput]) -> InsightArchiveInput {
            InsightArchiveInput(cooks: cooks, dishes: dishes, events: [], readings: [], results: results, lifecycles: [])
        }
        let past = InsightCookInput(id: "past", startedAtUtcMillis: 0, endedAtUtcMillis: 10_000, timingMode: "explicit_phases")
        let live = InsightCookInput(id: "live", startedAtUtcMillis: 0, endedAtUtcMillis: nil, timingMode: "explicit_phases")
        let brisket = InsightDishInput(id: "d1", cookId: "past", cut: "Brisket", weightValue: nil, weightUnit: nil)
        let chicken = InsightDishInput(id: "d2", cookId: "past", cut: "Chicken", weightValue: nil, weightUnit: nil)
        let target = InsightResultInput(id: "r1", cookId: "past", dishId: "d1", resultType: "final_temperature", numericValue: 203, textValue: nil, recordedAtUtcMillis: 9_000)
        XCTAssertEqual(LiveGuidance.historyFor(archive: archive(dishes: [brisket, chicken], results: [target], cooks: [past, live]), currentCookId: "live", currentDishId: "d9", cut: "Chicken"), .none)
        XCTAssertEqual(LiveGuidance.historyFor(archive: archive(dishes: [brisket], results: [], cooks: [past, live]), currentCookId: "live", currentDishId: "d9", cut: "Brisket"), .none)
        XCTAssertEqual(LiveGuidance.historyFor(archive: archive(dishes: [brisket], results: [target], cooks: [past, live]), currentCookId: "live", currentDishId: "d9", cut: "Pork butt"), .none)
        XCTAssertEqual(LiveGuidance.ghostCook(archive: archive(dishes: [brisket, chicken], results: [target], cooks: [past, live]), currentCookId: "live", cut: " brisket ")?.id, "past")
        XCTAssertNil(LiveGuidance.ghostCook(archive: archive(dishes: [brisket], results: [target], cooks: [past, live]), currentCookId: "live", cut: nil))
        XCTAssertNil(LiveGuidance.ghostCook(archive: archive(dishes: [brisket], results: [target], cooks: [past, live]), currentCookId: "past", cut: "Brisket"))
    }

    func testOvernightTestAlertRequestShape() {
        let request = OvernightTestAlert.request(cookId: "cook-9", cookTitle: "Night brisket")
        XCTAssertEqual(request.identifier, "overnight-test-cook-9")
        XCTAssertEqual(request.content.title, "Test alert — Night brisket")
        XCTAssertTrue(request.content.body.contains("safety net can wake you"))
        XCTAssertNil(request.trigger)
    }
}
