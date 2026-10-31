import XCTest
@testable import PitTech

/// Issue #183: the cook-alert engine (Android's CookAlertEngine port),
/// its payload codec, the store operations, and the E1
/// missing-readings default.
final class CookAlertsTests: XCTestCase {
    private func sample(_ fahrenheit: Double, at: Int64, receipt: Int64? = nil) -> AlertSample {
        AlertSample(fahrenheit: fahrenheit, at: at, receiptAt: receipt ?? at, valid: true)
    }

    func testCodecRoundTripsRuleAndState() throws {
        let rule = CookAlertRule(id: "a1", type: "pit_range", lowF: 225, highF: 275, dwellMillis: 300_000)
        let state = CookAlertState(active: true, badSince: 42, acknowledged: true, notifiedCount: 2)
        let decoded = try CookAlertEngine.decode(CookAlertEngine.encode(rule, state))
        XCTAssertEqual(decoded.rule, rule)
        XCTAssertEqual(decoded.state, state)
    }

    func testValidationRejectsBadRules() {
        XCTAssertThrowsError(try CookAlertEngine.validate(CookAlertRule(type: "target", targetF: nil)))
        XCTAssertThrowsError(try CookAlertEngine.validate(CookAlertRule(type: "pit_range", lowF: 300, highF: 200)))
        XCTAssertThrowsError(try CookAlertEngine.validate(CookAlertRule(type: "missing", missingMillis: 30_000)))
    }

    func testTargetActivatesAtTargetAndRecoversBelowHysteresis() {
        let rule = CookAlertRule(type: "target", targetF: 165, recoveryF: 5)
        let now: Int64 = 10_000_000
        let hit = CookAlertEngine.evaluate(rule: rule, previous: CookAlertState(), sample: sample(166, at: now), lastReceipt: nil, reportedOffline: nil, stage: "cooking", now: now)
        XCTAssertTrue(hit.state.active)
        XCTAssertTrue(hit.notify)
        // Still bad at target - 3 (inside the recovery band)…
        let held = CookAlertEngine.evaluate(rule: rule, previous: hit.state, sample: sample(162, at: now + 1000), lastReceipt: nil, reportedOffline: nil, stage: "cooking", now: now + 1000)
        XCTAssertTrue(held.state.active)
        // …recovered at target - 6.
        let recovered = CookAlertEngine.evaluate(rule: rule, previous: hit.state, sample: sample(159, at: now + 2000), lastReceipt: nil, reportedOffline: nil, stage: "cooking", now: now + 2000)
        XCTAssertFalse(recovered.state.active)
        // Wrong stage: inactive, state cleared.
        let staged = CookAlertEngine.evaluate(rule: rule, previous: hit.state, sample: sample(170, at: now), lastReceipt: nil, reportedOffline: nil, stage: "resting", now: now)
        XCTAssertFalse(staged.state.active)
        XCTAssertEqual(staged.detail, "Inactive for this stage")
    }

    func testStaleSampleNeverActivates() {
        let rule = CookAlertRule(type: "target", targetF: 165)
        let now: Int64 = 10_000_000
        let stale = CookAlertEngine.evaluate(rule: rule, previous: CookAlertState(), sample: sample(200, at: now - 400_000), lastReceipt: nil, reportedOffline: nil, stage: "cooking", now: now)
        XCTAssertFalse(stale.state.active)
    }

    func testPitRangeNeedsSustainedDwell() {
        let rule = CookAlertRule(type: "pit_range", lowF: 225, highF: 275, dwellMillis: 120_000)
        let t0: Int64 = 10_000_000
        let first = CookAlertEngine.evaluate(rule: rule, previous: CookAlertState(), sample: sample(300, at: t0), lastReceipt: nil, reportedOffline: nil, stage: "cooking", now: t0)
        XCTAssertFalse(first.state.active)
        let later = CookAlertEngine.evaluate(rule: rule, previous: first.state, sample: sample(300, at: t0 + 130_000), lastReceipt: nil, reportedOffline: nil, stage: "cooking", now: t0 + 130_000)
        XCTAssertTrue(later.state.active)
        XCTAssertTrue(later.notify)
    }

    func testMissingFiresAfterTheSilenceWindow() {
        let rule = CookAlertRule(type: "missing", missingMillis: 180_000)
        let receipt: Int64 = 10_000_000
        let quiet = CookAlertEngine.evaluate(rule: rule, previous: CookAlertState(), sample: nil, lastReceipt: receipt, reportedOffline: nil, stage: "cooking", now: receipt + 60_000)
        XCTAssertFalse(quiet.state.active)
        let fired = CookAlertEngine.evaluate(rule: rule, previous: CookAlertState(), sample: nil, lastReceipt: receipt, reportedOffline: nil, stage: "cooking", now: receipt + 600_000)
        XCTAssertTrue(fired.state.active)
        XCTAssertEqual(fired.detail, "No accepted reading for 10 min")
    }

    func testStripSummaryNamesAcknowledgedAndCountsAttention() {
        XCTAssertNil(CookAlertEngine.stripSummary(alerts: []))
        let summary = CookAlertEngine.stripSummary(alerts: [
            (title: "Brisket target", state: CookAlertState(active: true, acknowledged: true)),
            (title: "Pit range", state: CookAlertState()),
        ])
        XCTAssertEqual(summary, "Alerts · 1 needs attention · Acknowledged: Brisket target")
    }

    /// The fixture's shape, at store level: a target alert made
    /// active by a logged temperature stays active across later saves
    /// (the reconciliations re-evaluate on every save; a fresh reading
    /// inside the recovery band must not clear it).
    @MainActor func testActivatedAlertSurvivesLaterSaves() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        store.startCook(title: "Fixture cook", firstDish: "Brisket")
        let cookId = store.library.cooks[0].id
        let dishId = store.dishRecords(cookId)[0].id
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        try store.saveNote(CookNoteDraft(cookId: cookId, dishId: dishId, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: Double(now - 3_600_000)))
        try store.saveCookAlert(cookId: cookId, title: "Brisket target", rule: CookAlertRule(dishId: dishId, targetF: 165))
        try store.saveNote(CookNoteDraft(cookId: cookId, dishId: dishId, title: "Checked temp", occurredAt: Double(now),
            temperatures: [NoteTemperatureDraft(probeName: "Probe 1", valueText: "170", unit: "°F", measuredAt: Double(now), dishId: dishId)]))
        XCTAssertEqual(store.cookAlerts(cookId: cookId).first?.state.active, true)
        // Later saves (any mutation) re-evaluate and keep it active.
        _ = try store.createCookReminder(cookId: cookId, title: "Rotate", dueAtUtcMillis: now + 3_600_000)
        store.save()
        XCTAssertEqual(store.cookAlerts(cookId: cookId).first?.state.active, true)
        XCTAssertEqual(store.alertStripSummary(cookId: cookId), "Alerts · 1 needs attention")
    }

    @MainActor func testStoreSaveChangeAndMissingDefault() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        store.startCook(title: "Alert cook", firstDish: "Brisket")
        let cookId = store.library.cooks[0].id
        // No source attached: no default is provisioned.
        store.ensureMissingReadingsDefault(cookId: cookId)
        XCTAssertTrue(store.cookAlerts(cookId: cookId).isEmpty)
        // Attach a recording: the default appears, once.
        store.library.recordings.append(.object(["id": .string("rec-1"), "cookId": .string(cookId), "status": .string("recording")]))
        store.ensureMissingReadingsDefault(cookId: cookId)
        store.ensureMissingReadingsDefault(cookId: cookId)
        XCTAssertEqual(store.cookAlerts(cookId: cookId).filter { $0.rule.type == "missing" }.count, 1)
        // Save + actions: acknowledge and toggle round-trip.
        try store.saveCookAlert(cookId: cookId, title: "Brisket target", rule: CookAlertRule(dishId: nil, targetF: 195))
        let entry = try XCTUnwrap(store.cookAlerts(cookId: cookId).first { $0.rule.type == "target" })
        XCTAssertEqual(entry.title, "Brisket target")
        store.changeCookAlert(id: entry.id, action: "toggle")
        XCTAssertEqual(store.alertEntry(entry.id)?.rule.enabled, false)
        store.changeCookAlert(id: entry.id, action: "toggle")
        XCTAssertEqual(store.alertEntry(entry.id)?.rule.enabled, true)
    }
}
