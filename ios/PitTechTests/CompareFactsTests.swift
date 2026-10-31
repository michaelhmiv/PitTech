import XCTest
@testable import PitTech

/// Flowing Slice 6 (Spread 05) gate evidence at the model level — the
/// mirror of Android's `CompareFactsTest`: the input/outcome diff renders
/// recorded vs not-recorded history honestly (5.2 / 5.5), the canonical
/// stall pair reproduces exactly (5.2), the projection exists only as a
/// tagged §4.1 range for an in-progress cook (5.3), and the teaching
/// sentence appears only with recorded support (5.4). The PROJECTED tag's
/// rendering is asserted through the grid cells the view renders.
final class CompareFactsTests: XCTestCase {
    private let t0 = 1_700_000_000_000.0

    private struct Built {
        let cook: CompareCookInput
        let dishes: [InsightDishInput]
        let events: [InsightEventInput]
    }

    private func cook(
        _ id: String,
        status: String = "completed",
        startedAt: Double? = nil,
        endedAt: Double?? = nil,
        setpoint: Double? = nil,
        dishes: [InsightDishInput],
        events: [InsightEventInput] = []
    ) -> Built {
        let start = startedAt ?? t0
        // Android's helper: endedAt defaults to start + 600 min; pass
        // `.some(nil)` for a cook with no end.
        let end: Double? = endedAt ?? (start + 600 * 60_000)
        return Built(
            cook: CompareCookInput(
                id: id, title: "Cook \(id)", status: status,
                startedAtUtcMillis: start, endedAtUtcMillis: end,
                initialSetpointValue: setpoint, initialSetpointUnit: setpoint != nil ? "°F" : nil),
            dishes: dishes, events: events)
    }

    private func dish(_ id: String, cookId: String, cut: String? = "Whole packer") -> InsightDishInput {
        InsightDishInput(id: id, cookId: cookId, cut: cut, weightValue: nil, weightUnit: nil)
    }

    private func reading(_ id: String, cookId: String, dishId: String?, value: Double, atMillis: Double) -> InsightReadingInput {
        InsightReadingInput(
            id: id, cookId: cookId, dishId: dishId, probeId: "p-\(cookId)", probeName: "Probe",
            measurementType: "food", value: value, unit: "°F", measuredAtUtcMillis: atMillis,
            source: "controller_cloud", sourceDeviceId: nil, qualityStatus: "valid")
    }

    /// A cook-shaped probe series whose §4.1 stall is exactly
    /// `stallMinutes`: climb 100→160°F over an hour, hold the 160°F
    /// plateau, climb out. The detector's first flat reading lands 30 min
    /// into the plateau (its trailing window is fully flat) and its
    /// declared start is backdated 15 min, so the plateau's last reading
    /// sits at stall + 75 minutes. (Android's generator, verbatim.)
    private func stallReadings(cookId: String, dishId: String, startMillis: Double, stallMinutes: Int, prefix: String) -> [InsightReadingInput] {
        let plateauEnd = stallMinutes + 75
        var out: [InsightReadingInput] = []
        var i = 0
        func add(_ minute: Int, _ temp: Double) {
            out.append(reading("\(prefix)-\(i)", cookId: cookId, dishId: dishId, value: temp, atMillis: startMillis + Double(minute) * 60_000))
            i += 1
        }
        for m in stride(from: 0, through: 60, by: 5) { add(m, 100.0 + Double(m)) }
        for m in stride(from: 65, through: plateauEnd, by: 5) { add(m, 160.0) }
        for m in stride(from: plateauEnd + 5, through: plateauEnd + 65, by: 5) { add(m, 160.0 + 8.0 * Double((m - plateauEnd) / 5)) }
        return out
    }

    private func result(_ id: String, cookId: String, dishId: String?, type: String, text: String) -> InsightResultInput {
        InsightResultInput(id: id, cookId: cookId, dishId: dishId, resultType: type, numericValue: nil, textValue: text, recordedAtUtcMillis: t0)
    }

    private func archive(
        _ built: [Built],
        readings: [InsightReadingInput],
        results: [InsightResultInput],
        targets: [InsightTargetInput] = []
    ) -> InsightArchiveInput {
        InsightArchiveInput(
            cooks: built.map { InsightCookInput(id: $0.cook.id, startedAtUtcMillis: $0.cook.startedAtUtcMillis, endedAtUtcMillis: $0.cook.endedAtUtcMillis, timingMode: nil) },
            dishes: built.flatMap(\.dishes),
            events: built.flatMap(\.events),
            readings: readings, results: results, lifecycles: [], targets: targets)
    }

    func testCanonicalStallPairDiffsBesideRecordedInputs() {
        // 5.2's canonical example: stall 2h 40m → 1h 35m, beside the inputs.
        let paper = cook("paper", setpoint: 250, dishes: [dish("dp", cookId: "paper")])
        let foil = cook("foil", setpoint: 225, dishes: [dish("df", cookId: "foil")])
        let readings = stallReadings(cookId: "paper", dishId: "dp", startMillis: t0, stallMinutes: 160, prefix: "rp")
            + stallReadings(cookId: "foil", dishId: "df", startMillis: t0, stallMinutes: 95, prefix: "rf")
        let results = [
            result("w1", cookId: "paper", dishId: "dp", type: "wrap_material", text: "butcher_paper"),
            result("r1", cookId: "paper", dishId: "dp", type: "rub", text: "House rub"),
            result("s1", cookId: "paper", dishId: "dp", type: "spritz", text: "yes"),
            result("sm1", cookId: "paper", dishId: "dp", type: "spritz_medium", text: "Apple juice"),
            result("w2", cookId: "foil", dishId: "df", type: "wrap_material", text: "foil"),
            result("r2", cookId: "foil", dishId: "df", type: "rub", text: "Salt and pepper"),
        ]
        let data = archive([paper, foil], readings: readings, results: results)
        let now = Int64(t0 + 700 * 60_000)
        let paperFacts = CompareFacts.facts(cook: paper.cook, archive: data, nowUtcMillis: now)
        let foilFacts = CompareFacts.facts(cook: foil.cook, archive: data, nowUtcMillis: now)
        XCTAssertEqual(paperFacts.stallMinutes!, 160.0, accuracy: 0.001)
        XCTAssertEqual(foilFacts.stallMinutes!, 95.0, accuracy: 0.001)
        XCTAssertEqual(paperFacts.wrapText, "Butcher paper")
        XCTAssertEqual(foilFacts.wrapText, "Foil")
        XCTAssertEqual(paperFacts.rubText, "House rub")
        XCTAssertEqual(paperFacts.spritzText, "Yes · Apple juice")
        XCTAssertEqual(paperFacts.setpointText, "250°")
        XCTAssertEqual(foilFacts.setpointText, "225°")
        XCTAssertTrue(paperFacts.inputsRecorded)
        XCTAssertNil(paperFacts.projected)
    }

    func testPreCaptureCookReadsNotRecordedWhileOutcomesStillComeFromReadings() {
        // 5.5: no §4.2 rows at all — inputs say so, outcomes still work.
        let old = cook("old", dishes: [dish("do", cookId: "old")])
        let readings = stallReadings(cookId: "old", dishId: "do", startMillis: t0, stallMinutes: 95, prefix: "ro")
        let data = archive([old], readings: readings, results: [])
        let facts = CompareFacts.facts(cook: old.cook, archive: data, nowUtcMillis: Int64(t0 + 700 * 60_000))
        XCTAssertFalse(facts.inputsRecorded)
        XCTAssertEqual(facts.wrapText, "Not recorded")
        XCTAssertEqual(facts.rubText, "Not recorded")
        XCTAssertEqual(facts.spritzText, "Not recorded")
        XCTAssertEqual(facts.setpointText, "Not recorded")
        XCTAssertEqual(facts.stallMinutes!, 95.0, accuracy: 0.001)
        XCTAssertEqual(facts.foodOnUtcMillis, Int64(t0))
    }

    func testAlignmentAnchorsAtTheRecordedFoodOnMoment() {
        // 5.1: the anchor is food-on (the timeline's food_on action,
        // normalized from meat_on), not the session start.
        let foodOn = InsightEventInput(id: "e1", cookId: "anchored", dishIds: ["da"], action: "food_on", at: t0 + 45 * 60_000)
        let anchored = cook("anchored", dishes: [dish("da", cookId: "anchored")], events: [foodOn])
        let data = archive([anchored], readings: [], results: [])
        XCTAssertEqual(CompareFacts.facts(cook: anchored.cook, archive: data, nowUtcMillis: Int64(t0)).foodOnUtcMillis, Int64(t0 + 45 * 60_000))
    }

    func testInProgressCookGetsAProjectedRangeFromThePaceEngine() {
        // 5.3: the in-progress cook's total is a §4.1 range, carried as a
        // projection — and a completed cook never carries one.
        let live = cook("live", status: "active", endedAt: .some(nil), dishes: [dish("dl", cookId: "live", cut: "Boston butt")])
        let done = cook("done", dishes: [dish("dd", cookId: "done", cut: "Boston butt")])
        let readings = (0...18).map { i in
            reading("rl-\(i)", cookId: "live", dishId: "dl", value: 100.0 + Double(i), atMillis: t0 + Double(i) * 5 * 60_000)
        }
        let targets = [InsightTargetInput(id: "tg", cookId: "live", dishId: "dl", targetType: "personal_finish", value: 203, unit: "°F")]
        let data = archive([live, done], readings: readings, results: [], targets: targets)
        let now = Int64(t0 + 90 * 60_000)
        let liveFacts = CompareFacts.facts(cook: live.cook, archive: data, nowUtcMillis: now)
        XCTAssertNotNil(liveFacts.projected)
        XCTAssertGreaterThan(liveFacts.projected!.latestMinutes, liveFacts.projected!.earliestMinutes)
        XCTAssertGreaterThan(liveFacts.projected!.earliestMinutes, liveFacts.elapsedMinutes)
        XCTAssertNil(liveFacts.projectionWithheldReason)
        // §4.1: the range states its basis, and it is the pace basis here.
        XCTAssertEqual(liveFacts.projected!.basisText, "from your last 30 min of readings")
        let doneFacts = CompareFacts.facts(cook: done.cook, archive: data, nowUtcMillis: now)
        XCTAssertNil(doneFacts.projected)
        XCTAssertNil(doneFacts.projectionWithheldReason)
        // The grid tags only the in-progress cook's Time on cell (§6 gate:
        // PROJECTED tagging asserted in tests).
        let rows = CompareFacts.gridRows(facts: [liveFacts, doneFacts])
        let timeOn = rows.first { $0.label == "Time on" }!
        XCTAssertTrue(timeOn.cells[0].isProjected)
        XCTAssertFalse(timeOn.cells[1].isProjected)
    }

    func testSparseInProgressCookStatesWhyNoProjection() {
        // §4.1 honesty: below the evidence minimum the engine declines,
        // and compare carries its reason instead of a fabricated range.
        let live = cook("live", status: "active", endedAt: .some(nil), dishes: [dish("dl", cookId: "live", cut: "Boston butt")])
        let readings = [
            reading("rl-0", cookId: "live", dishId: "dl", value: 100, atMillis: t0),
            reading("rl-1", cookId: "live", dishId: "dl", value: 105, atMillis: t0 + 5 * 60_000),
        ]
        let targets = [InsightTargetInput(id: "tg", cookId: "live", dishId: "dl", targetType: "personal_finish", value: 203, unit: "°F")]
        let data = archive([live], readings: readings, results: [], targets: targets)
        let facts = CompareFacts.facts(cook: live.cook, archive: data, nowUtcMillis: Int64(t0 + 10 * 60_000))
        XCTAssertNil(facts.projected)
        XCTAssertEqual(facts.projectionWithheldReason, "not enough readings yet")
    }

    private struct WrapBuilt {
        let built: Built
        let readings: [InsightReadingInput]
        let results: [InsightResultInput]
    }

    private func wrapCook(_ id: String, material: String?, stallMinutes: Int, dayOffset: Double) -> WrapBuilt {
        let start = t0 + dayOffset
        let item = cook(id, startedAt: start, dishes: [dish("d-\(id)", cookId: id)])
        let results = material.map { [result("w-\(id)", cookId: id, dishId: "d-\(id)", type: "wrap_material", text: $0)] } ?? []
        return WrapBuilt(
            built: item,
            readings: stallReadings(cookId: id, dishId: "d-\(id)", startMillis: start, stallMinutes: stallMinutes, prefix: "r-\(id)"),
            results: results)
    }

    private func sentenceOf(_ built: [WrapBuilt]) -> String? {
        CompareFacts.teachingSentence(
            cooks: built.map(\.built.cook),
            archive: archive(built.map(\.built), readings: built.flatMap(\.readings), results: built.flatMap(\.results)))
    }

    func testTeachingSentenceMatchesTheCanonicalExampleWhenHistorySupportsIt() {
        // 5.4: three recorded paper wraps averaging a 60-minute stall,
        // two recorded foil wraps at 125 — the spec's own sentence.
        let built = [
            wrapCook("p1", material: "butcher_paper", stallMinutes: 60, dayOffset: 0),
            wrapCook("p2", material: "butcher_paper", stallMinutes: 60, dayOffset: 2 * 86_400_000),
            wrapCook("p3", material: "butcher_paper", stallMinutes: 60, dayOffset: 4 * 86_400_000),
            wrapCook("f1", material: "foil", stallMinutes: 125, dayOffset: 86_400_000),
            wrapCook("f2", material: "foil", stallMinutes: 125, dayOffset: 3 * 86_400_000),
        ]
        XCTAssertEqual(sentenceOf(built), "Paper is winning: your last three paper wraps stalled ~1h shorter than foil.")
    }

    func testTeachingSentenceStaysSilentWithoutRecordedSupport() {
        // §9 restraint: fewer than three comparable cooks on the winning
        // side, a margin the data doesn't carry, or no recorded wraps at
        // all — in every case the sentence is absent, never filler.
        let twoPaper = [
            wrapCook("p1", material: "butcher_paper", stallMinutes: 60, dayOffset: 0),
            wrapCook("p2", material: "butcher_paper", stallMinutes: 60, dayOffset: 2 * 86_400_000),
            wrapCook("f1", material: "foil", stallMinutes: 125, dayOffset: 86_400_000),
            wrapCook("f2", material: "foil", stallMinutes: 125, dayOffset: 3 * 86_400_000),
        ]
        XCTAssertNil(sentenceOf(twoPaper))
        let thinMargin = [
            wrapCook("p1", material: "butcher_paper", stallMinutes: 60, dayOffset: 0),
            wrapCook("p2", material: "butcher_paper", stallMinutes: 60, dayOffset: 2 * 86_400_000),
            wrapCook("p3", material: "butcher_paper", stallMinutes: 60, dayOffset: 4 * 86_400_000),
            wrapCook("f1", material: "foil", stallMinutes: 70, dayOffset: 86_400_000),
            wrapCook("f2", material: "foil", stallMinutes: 70, dayOffset: 3 * 86_400_000),
        ]
        XCTAssertNil(sentenceOf(thinMargin))
        let unrecorded = ["a", "b", "c", "d"].enumerated().map { index, id in
            wrapCook(id, material: nil, stallMinutes: 60, dayOffset: Double(index) * 86_400_000)
        }
        XCTAssertNil(sentenceOf(unrecorded))
    }

    func testOffsetStepperLabelReadsFromFoodOn() {
        // 5.1: the stepper's readout names the zero point "Food-on".
        XCTAssertEqual(CompareFacts.offsetLabel(0), "Food-on")
        XCTAssertEqual(CompareFacts.offsetLabel(300_000), "+5m")
        XCTAssertEqual(CompareFacts.offsetLabel(-300_000), "−5m")
        XCTAssertEqual(CompareFacts.offsetLabel(7_200_000), "+120m")
    }
}
