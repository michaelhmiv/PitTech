import XCTest
@testable import PitTech

/// The Slice 1 backtest: CookPaceEngine run against the shared pace/stall
/// corpus (contracts/fixtures/pace-stall-corpus-v1.json), judged against the
/// oracles in that fixture's README and the Flowing spec §9 trust bar.
/// Mirrors Android's CookPaceEngineTest protocol for protocol.
final class CookPaceEngineTests: XCTestCase {
    private struct Corpus: Decodable {
        struct Expected: Decodable {
            let targetTempF: Double
            let stallStartUtcMillis: Double?
            let stallEndUtcMillis: Double?
            let finishUtcMillis: Double
        }
        struct Archive: Decodable {
            let cooks: [InsightCookInput]
            let dishes: [InsightDishInput]
            let events: [InsightEventInput]
            let readings: [InsightReadingInput]
            var input: InsightArchiveInput {
                InsightArchiveInput(cooks: cooks, dishes: dishes, events: events, readings: readings, results: [], lifecycles: [])
            }
        }
        struct Case: Decodable {
            let name: String
            let archive: Archive
            let expected: Expected
        }
        let cases: [Case]
    }

    private struct CaseData {
        let name: String
        let archive: InsightArchiveInput
        let targetF: Double
        let foodOn: Int64
        let finish: Int64
        let stallStart: Int64?
        let stallEnd: Int64?
        let wrapAt: Int64?
    }

    private func loadCases() throws -> [CaseData] {
        let bundle = Bundle(for: Self.self)
        let data = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "pace-stall-corpus-v1", withExtension: "json")))
        let corpus = try JSONDecoder().decode(Corpus.self, from: data)
        return corpus.cases.map { c in
            let archive = c.archive.input
            return CaseData(
                name: c.name,
                archive: archive,
                targetF: c.expected.targetTempF,
                foodOn: Int64(archive.events.map { $0.at }.min()!),
                finish: Int64(c.expected.finishUtcMillis),
                stallStart: c.expected.stallStartUtcMillis.map { Int64($0) },
                stallEnd: c.expected.stallEndUtcMillis.map { Int64($0) },
                wrapAt: archive.events.first { $0.action == "wrap" }.map { Int64($0.at) })
        }
    }

    private func series(_ c: CaseData, upto: Int64 = .max) -> [PaceReading] {
        let dish = c.archive.dishes[0]
        return c.archive.readings
            .filter { $0.cookId == dish.cookId && ($0.dishId == dish.id || $0.dishId == nil) &&
                InsightsCalculations.role($0.measurementType) == "food" && $0.qualityStatus == "valid" && $0.value != nil }
            .map { PaceReading(atUtcMillis: Int64($0.measuredAtUtcMillis), fahrenheit: InsightsCalculations.fahrenheit($0.value!, unit: $0.unit)) }
            .filter { $0.atUtcMillis <= upto }
            .sorted { $0.atUtcMillis < $1.atUtcMillis }
    }

    /// Briskets A/B/D lend each other history; chicken and the hand-logged butt have none.
    private func historyFor(_ c: CaseData, all: [CaseData]) -> PaceHistory {
        let briskets = all.filter { $0.name.contains("brisket") }
        guard briskets.contains(where: { $0.name == c.name }) else { return .none }
        let others = briskets.filter { $0.name != c.name }
        var targets: [String: Double] = [:]
        for other in others { for dish in other.archive.dishes { targets["\(dish.cookId)/\(dish.id)"] = other.targetF } }
        return CookPaceEngine.history(archives: others.map { $0.archive }, targetFByDish: targets)
    }

    private struct Statement {
        let at: Int64
        let range: FinishRange
        let stall: StallState
    }

    private func runProtocol(_ c: CaseData, all: [CaseData]) -> [Statement] {
        let history = historyFor(c, all: all)
        let smoother = PaceRangeSmoother()
        var statements: [Statement] = []
        var t = c.foodOn + 30 * 60_000
        while t < c.finish {
            let assessment = CookPaceEngine.assess(readings: series(c, upto: t), targetF: c.targetF, nowUtcMillis: t, foodOnUtcMillis: c.foodOn, history: history)
            var ranged: FinishRange?
            if case .ranged(let r) = assessment.finish { ranged = r }
            if let published = smoother.publish(ranged, nowUtcMillis: t) {
                statements.append(Statement(at: t, range: published, stall: assessment.stall))
            }
            t += 15 * 60_000
        }
        return statements
    }

    func testStallEdgesLandWithinTwentyMinutesOnEveryCorpusBrisket() throws {
        for c in try loadCases().filter({ $0.stallStart != nil }) {
            let spans = CookPaceEngine.stallSpans(series(c))
            XCTAssertEqual(spans.count, 1, "\(c.name): exactly one stall (a pit dip must not split it)")
            let span = spans[0]
            XCTAssertLessThanOrEqual(abs(span.startUtcMillis - c.stallStart!), 20 * 60_000, "\(c.name): start edge")
            XCTAssertLessThanOrEqual(abs(span.endUtcMillis - c.stallEnd!), 20 * 60_000, "\(c.name): end edge")
        }
    }

    func testCanonicalBrisketRainDipIsNeverAStallSignal() throws {
        let c = try loadCases().first { $0.name.contains("canonical") }!
        let spans = CookPaceEngine.stallSpans(series(c))
        let firstInBand = series(c).first { $0.fahrenheit >= PaceConstants.stallBandLowF }!.atUtcMillis
        XCTAssertTrue(spans.allSatisfy { $0.startUtcMillis >= firstInBand - 20 * 60_000 }, "no stall before the probe enters the band")
    }

    func testFoilWrapDoesNotEndTheStall() throws {
        let c = try loadCases().first { $0.name.contains("foil") }!
        let span = CookPaceEngine.stallSpans(series(c))[0]
        XCTAssertTrue((span.startUtcMillis...span.endUtcMillis).contains(c.wrapAt!), "wrap falls inside the stall")
        XCTAssertGreaterThanOrEqual(span.endUtcMillis - c.wrapAt!, 60 * 60_000, "the stall ends on the probe's exit, not the wrap")
        XCTAssertEqual(span.durationMinutes, 160, accuracy: 20, "stall duration is the oracle's 2h40m (±20)")
    }

    func testFastChickenReportsNoStallAndAnEarlyContainingRange() throws {
        let all = try loadCases()
        let c = all.first { $0.name.contains("chicken") }!
        XCTAssertTrue(CookPaceEngine.stallSpans(series(c)).isEmpty, "presence in the band is not a stall")
        let statements = runProtocol(c, all: all)
        XCTAssertFalse(statements.isEmpty)
        XCTAssertLessThanOrEqual(statements[0].at, c.foodOn + 45 * 60_000, "a range is available early")
        XCTAssertTrue((statements[0].range.earliestUtcMillis...statements[0].range.latestUtcMillis).contains(c.finish), "the first range contains the finish")
    }

    func testFirstStatedRangeContainsTheFinishOnEveryRangeProducingShape() throws {
        let all = try loadCases()
        for c in all where !c.name.contains("boston butt") {
            let statements = runProtocol(c, all: all)
            XCTAssertFalse(statements.isEmpty, "\(c.name): the engine states a range")
            let first = statements[0].range
            XCTAssertTrue((first.earliestUtcMillis...first.latestUtcMillis).contains(c.finish), "\(c.name): first stated range contains finish")
        }
    }

    func testPostStallRangesContainTheFinishOnTheCanonicalBrisket() throws {
        let all = try loadCases()
        let c = all.first { $0.name.contains("canonical") }!
        let postStall = runProtocol(c, all: all).filter { $0.at > c.stallEnd! }
        XCTAssertFalse(postStall.isEmpty, "ranges are stated after the stall")
        for s in postStall {
            XCTAssertTrue((s.range.earliestUtcMillis...s.range.latestUtcMillis).contains(c.finish), "post-stall range at \(s.at) contains the finish")
        }
    }

    func testOvernightCreepStallWidensInsteadOfSlidingEarlier() throws {
        let all = try loadCases()
        let c = all.first { $0.name.contains("overnight") }!
        let statements = runProtocol(c, all: all)
        let midStall = statements.filter { s in
            (c.stallStart!...c.stallEnd!).contains(s.at) && { if case .inStall = s.stall { return true }; return false }()
        }
        XCTAssertFalse(midStall.isEmpty, "the creep is detected as a stall mid-cook")
        for s in midStall {
            XCTAssertGreaterThanOrEqual(s.range.widthMinutes, 90, "mid-stall range is wide, not a creep extrapolation")
            XCTAssertTrue((s.range.earliestUtcMillis...s.range.latestUtcMillis).contains(c.finish), "mid-stall range contains the finish")
        }
    }

    func testHandLoggedButtGetsNoRangeAndAnUnknownStallState() throws {
        let all = try loadCases()
        let c = all.first { $0.name.contains("boston butt") }!
        var t = c.foodOn + 30 * 60_000
        while t < c.finish {
            let assessment = CookPaceEngine.assess(readings: series(c, upto: t), targetF: c.targetF, nowUtcMillis: t, foodOnUtcMillis: c.foodOn, history: historyFor(c, all: all))
            if case .ranged = assessment.finish { XCTFail("no pace range from one probe reading (t=\(t))") }
            XCTAssertEqual(assessment.stall, .unknown, "stall state is unknown, never 'no stall' (t=\(t))")
            t += 15 * 60_000
        }
    }

    // MARK: - Unit behavior

    private func synthetic(_ points: [(Int64, Double)]) -> [PaceReading] {
        points.map { PaceReading(atUtcMillis: $0.0 * 60_000, fahrenheit: $0.1) }
    }

    func testFlatInBandSeriesYieldsOneBackdatedSpan() {
        var pts: [(Int64, Double)] = []
        for m in stride(from: 0, through: 60, by: 5) { pts.append((Int64(m), 100.0 + Double(m))) }
        for m in stride(from: 65, through: 150, by: 5) { pts.append((Int64(m), 160.0)) }
        for m in stride(from: 155, through: 200, by: 5) { pts.append((Int64(m), 160.0 + Double(m - 150) * 0.8)) }
        let spans = CookPaceEngine.stallSpans(synthetic(pts))
        XCTAssertEqual(spans.count, 1)
        XCTAssertTrue((60 * 60_000...90 * 60_000).contains(spans[0].startUtcMillis), "declared start is backdated half a window into the flat run")
    }

    func testClimbingThroughTheBandIsNotAStall() {
        let pts = stride(from: 0, through: 40, by: 5).map { (Int64($0), 140.0 + Double($0) * 1.5) }
        XCTAssertTrue(CookPaceEngine.stallSpans(synthetic(pts)).isEmpty)
    }

    func testSlowCreepInBandIsAStall() {
        let pts = stride(from: 0, through: 180, by: 5).map { (Int64($0), 156.0 + Double($0) * 0.023) }
        XCTAssertEqual(CookPaceEngine.stallSpans(synthetic(pts)).count, 1)
    }

    func testALongGapBreaksAFlatRun() {
        var pts: [(Int64, Double)] = []
        for m in stride(from: 0, through: 40, by: 5) { pts.append((Int64(m), 160.0)) }
        for m in stride(from: 90, through: 130, by: 5) { pts.append((Int64(m), 160.0)) }
        XCTAssertTrue(CookPaceEngine.stallSpans(synthetic(pts)).isEmpty, "two 40-minute flats across a 50-minute gap never merge into a stall")
    }

    func testSparseReadingsYieldPlanBasisAndUnknownStall() {
        let assessment = CookPaceEngine.assess(readings: [PaceReading(atUtcMillis: 0, fahrenheit: 140)], targetF: 203, nowUtcMillis: 60 * 60_000, foodOnUtcMillis: 0)
        if case .ranged = assessment.finish { XCTFail("sparse readings must not produce a range") }
        XCTAssertEqual(assessment.stall, .unknown)
    }

    func testReachingTheTargetIsFinishedNotARange() {
        let pts = stride(from: 0, through: 60, by: 5).map { PaceReading(atUtcMillis: Int64($0) * 60_000, fahrenheit: 150.0 + Double($0)) }
        let assessment = CookPaceEngine.assess(readings: pts, targetF: 203, nowUtcMillis: 60 * 60_000, foodOnUtcMillis: 0)
        XCTAssertEqual(assessment.finish, .finished)
    }

    func testSmootherHonorsCadenceAndBoundsEdgeSteps() {
        let smoother = PaceRangeSmoother()
        let first = FinishRange(earliestUtcMillis: 1_000_000, latestUtcMillis: 2_000_000, basis: .pace)
        XCTAssertEqual(smoother.publish(first, nowUtcMillis: 0), first)
        let moved = FinishRange(earliestUtcMillis: 1_000_000 + 60 * 60_000, latestUtcMillis: 2_000_000 + 60 * 60_000, basis: .pace)
        XCTAssertEqual(smoother.publish(moved, nowUtcMillis: 5 * 60_000), first, "inside the cadence the published range stands")
        let stepped = smoother.publish(moved, nowUtcMillis: 11 * 60_000)
        XCTAssertEqual(stepped?.earliestUtcMillis, first.earliestUtcMillis + PaceConstants.smootherMaxEdgeStepMinutes * 60_000, "an edge moves at most the step per publication")
        XCTAssertNil(smoother.publish(nil, nowUtcMillis: 30 * 60_000), "a nil candidate states nothing")
    }

    func testBasisLinesReadAsTheSpecWroteThem() {
        XCTAssertEqual(PaceBasis.pace.displayText, "from your last 30 min of readings")
        XCTAssertEqual(PaceBasis.plan.displayText, "from your plan — not enough readings yet")
    }
}
