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

        // PlanOnly with a
...[truncated 3864 chars]