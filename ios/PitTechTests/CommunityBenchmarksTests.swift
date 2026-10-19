import XCTest
@testable import PitTech

/// Slice 17 native lane: benchmark cards consume the merged aggregate API with
/// the same meanings, counts, and fixtures as Android. Every figure renders
/// with its eligible n and compute date; suppressed cells stay honest; unknown
/// or malformed figures fail closed to the unavailable state.
@MainActor final class CommunityBenchmarksTests: XCTestCase {
    private final class FakeAPI: NativeCommunityBenchmarksAPI, @unchecked Sendable {
        var aggregateCalls: [(bucket: String?, metric: String?, runId: String?)] = []
        var result: Result<Data, Error> = .failure(NativeCommunityError.network)

        func aggregates(bucket: String?, metric: String?, runId: String?, access: String?) async throws -> Data {
            aggregateCalls.append((bucket, metric, runId))
            guard access != nil else { throw NativeCommunityError.status(401) }
            return try result.get()
        }

        func jobRuns(limit: Int?, cursor: String?, access: String?) async throws -> Data {
            guard access != nil else { throw NativeCommunityError.status(401) }
            return try JSONSerialization.data(withJSONObject: ["runs": [], "nextCursor": NSNull()])
        }
    }

    private func fixturePayload(_ name: String) throws -> Data {
        let bundle = Bundle(for: Self.self)
        let data = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "community-benchmarks-v1", withExtension: "json")))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["contractVersion"] as? String, "community-benchmarks-v1")
        let payloads = try XCTUnwrap(json["payloads"] as? [String: Any])
        return try JSONSerialization.data(withJSONObject: XCTUnwrap(payloads[name]))
    }

    private func makeLoader(api: FakeAPI = FakeAPI(), token: String? = String(repeating: "A", count: 43), mismatched: Box<Int> = Box(0)) -> CommunityBenchmarksLoader {
        CommunityBenchmarksLoader(
            api: api,
            sessionProvider: { token },
            onAuthMismatch: { mismatched.value += 1 }
        )
    }

    // MARK: - Fixture-driven cards (same figures as Android)

    func testSharedFixtureVisibleFiguresDecodeWithNAndDate() throws {
        let payload = try CommunityBenchmarks.decodeAggregates(try fixturePayload("visible"))
        XCTAssertEqual(payload.run?.runId, "12345678-1234-4234-8234-123456789abc")
        XCTAssertEqual(payload.figures.count, 4)
        let built = CommunityBenchmarks.cards(figures: payload.figures)
        XCTAssertEqual(built.cards.count, 4)
        XCTAssertTrue(built.omitted.isEmpty)
        let heating = try XCTUnwrap(built.cards.first { $0.title == "Typical heating time" })
        XCTAssertEqual(heating.testTag, "community-card-median-heating-time-v1-pork-butt-shoulder")
        XCTAssertEqual(heating.bucketLine, "Pork butt / shoulder · Medium · Method: smoke")
        XCTAssertEqual(heating.lines, [
            "Typical (median): 5h 30m",
            "Middle half: 3h 15m – 7h 45m",
            "Recorded range: 1h – 10h",
        ])
        XCTAssertEqual(heating.coverageLine, "10 eligible cooks · Computed 2026-10-18")
        XCTAssertFalse(heating.insufficient)
        for card in built.cards {
            XCTAssertTrue(card.coverageLine.contains("10 eligible cooks"), card.title)
            XCTAssertTrue(card.coverageLine.contains("2026-10-18"), card.title)
        }
    }

    func testInsufficientDataCardShowsNAndDateWithoutFigure() throws {
        let payload = try CommunityBenchmarks.decodeAggregates(try fixturePayload("suppressed"))
        let built = CommunityBenchmarks.cards(figures: payload.figures)
        XCTAssertEqual(built.cards.count, 1)
        let card = try XCTUnwrap(built.cards.first)
        XCTAssertTrue(card.insufficient)
        XCTAssertEqual(card.testTag, "community-insufficient-median-heating-time-v1-pork-loin")
        XCTAssertEqual(card.title, "Typical heating time")
        XCTAssertEqual(card.bucketLine, "Pork loin · Medium · Method: smoke")
        XCTAssertEqual(card.lines, [
            "Not enough cooks yet",
            "9 of 10 eligible cooks recorded. Figures appear when 10 distinct eligible cooks are recorded.",
        ])
        XCTAssertEqual(card.coverageLine, "9 eligible cooks · Computed 2026-10-18")
        for line in card.lines {
            XCTAssertFalse(line.contains("Typical (median)"), "a suppressed cell must never partially render: \(line)")
        }
    }

    func testRunNullMapsToEmptyState() async throws {
        let api = FakeAPI()
        api.result = .success(try fixturePayload("empty"))
        let loader = makeLoader(api: api)
        await loader.load()
        XCTAssertEqual(loader.state, .empty)
    }

    func testCurveIntervalSuppressionShowsNAndCoverage() throws {
        let payload = try CommunityBenchmarks.decodeAggregates(try fixturePayload("visible"))
        let built = CommunityBenchmarks.cards(figures: payload.figures)
        let curve = try XCTUnwrap(built.cards.first { $0.title == "Temperature percentile bands" })
        XCTAssertEqual(curve.lines, [
            "+0:00–0:15",
            "Pit: p10 100.9 °C · p50 104.5 °C · p90 108.1 °C (n=10, 100% of cooks)",
            "Food: p10 50.9 °C · p50 54.5 °C · p90 58.1 °C (n=10, 100% of cooks)",
            "+0:15–0:30",
            "Pit: p10 110.0 °C · p50 115.5 °C · p90 120.2 °C (n=10, 100% of cooks)",
            "Food band hidden: 3 of 10 cooks in this interval (30%). Bands need 10 cooks.",
        ])
        XCTAssertEqual(curve.notes, ["Percentile bands summarize many cooks. They are not one cook's readings."])
        XCTAssertEqual(curve.coverageLine, "10 eligible cooks · Computed 2026-10-18")
    }

    func testStallCardCarriesSparseExclusionNote() throws {
        let payload = try CommunityBenchmarks.decodeAggregates(try fixturePayload("visible"))
        let built = CommunityBenchmarks.cards(figures: payload.figures)
        let stall = try XCTUnwrap(built.cards.first { $0.title == "Stall summary" })
        XCTAssertEqual(stall.lines, [
            "Stalled: 6 of 10 assessed cooks (60%)",
            "Stall start (median): +0:00",
            "Stall length (median): 2h",
            "Stall temperature (median): 72.5 °C",
        ])
        XCTAssertEqual(stall.notes, ["Measured stall windows only; sparse attempts are excluded from n, never interpolated."])
        XCTAssertTrue(stall.notes.single?.contains("excluded") == true)
        XCTAssertTrue(stall.notes.single?.contains("never interpolated") == true)
        XCTAssertEqual(stall.coverageLine, "10 eligible cooks · Computed 2026-10-18")
    }

    func testWrapCardShowsPrevalenceWithoutCausation() throws {
        let payload = try CommunityBenchmarks.decodeAggregates(try fixturePayload("visible"))
        let built = CommunityBenchmarks.cards(figures: payload.figures)
        let wrap = try XCTUnwrap(built.cards.first { $0.title == "Wrap and spritz prevalence" })
        XCTAssertEqual(wrap.lines, [
            "Wrapped: 6 of 10 (60%)",
            "Spritzed or mopped: 3 of 10 (30%)",
            "Method smoke: 10 of 10 (100%)",
        ])
        XCTAssertEqual(wrap.notes, ["Prevalence only: these shares describe what cooks recorded."])
        XCTAssertEqual(wrap.coverageLine, "10 eligible cooks · Computed 2026-10-18")
    }

    // MARK: - Fail-closed decoding

    func testBucketValidationRejectsUnknownGroupsAndBadShapes() {
        XCTAssertTrue(CommunityBenchmarks.bucketAllowed("pork-butt-shoulder|medium|method:smoke"))
        XCTAssertTrue(CommunityBenchmarks.bucketAllowed("pork-loin|unknown|method:unknown"))
        XCTAssertTrue(CommunityBenchmarks.bucketAllowed("beef-brisket|large|method:smoke|equipment:offset"))
        XCTAssertFalse(CommunityBenchmarks.bucketAllowed("evil"))
        XCTAssertFalse(CommunityBenchmarks.bucketAllowed("pork-butt-shoulder|medium"))
        XCTAssertFalse(CommunityBenchmarks.bucketAllowed("brisket|medium|method:smoke"))
        XCTAssertFalse(CommunityBenchmarks.bucketAllowed("pork-loin|xl|method:smoke"))
        XCTAssertFalse(CommunityBenchmarks.bucketAllowed("pork-loin|medium|smoke"))
        XCTAssertFalse(CommunityBenchmarks.bucketAllowed("pork-loin|medium|method:smoke|equipment:offset|extra"))
        XCTAssertTrue(CommunityBenchmarks.metricAllowed("median-heating-time-v1"))
        XCTAssertTrue(CommunityBenchmarks.metricAllowed("pit-food-curve-percentiles-v1"))
        XCTAssertTrue(CommunityBenchmarks.metricAllowed("stall-summary-v1"))
        XCTAssertTrue(CommunityBenchmarks.metricAllowed("wrap-spritz-prevalence-v1"))
        XCTAssertFalse(CommunityBenchmarks.metricAllowed("stub-heating-presence"))
        XCTAssertFalse(CommunityBenchmarks.metricAllowed("future-metric-v99"))
        XCTAssertTrue(CommunityBenchmarks.runIdValid("12345678-1234-4234-8234-123456789abc"))
        XCTAssertFalse(CommunityBenchmarks.runIdValid("not-a-uuid"))
        XCTAssertTrue(CommunityBenchmarks.jobRunsLimitValid(1))
        XCTAssertTrue(CommunityBenchmarks.jobRunsLimitValid(100))
        XCTAssertFalse(CommunityBenchmarks.jobRunsLimitValid(0))
        XCTAssertFalse(CommunityBenchmarks.jobRunsLimitValid(101))
    }

    func testUnknownMetricFailsClosedToUnavailable() async throws {
        let data = try fixturePayload("unknownMetric")
        do {
            _ = try CommunityBenchmarks.decodeAggregates(data)
            XCTFail("an unknown metric ID must fail decoding")
        } catch let error as CommunityDecodeError {
            XCTAssertEqual(error, .unknownMetric("future-metric-v99"))
        }
        let api = FakeAPI()
        api.result = .success(data)
        let loader = makeLoader(api: api)
        await loader.load()
        XCTAssertEqual(loader.state, .unavailable)
    }

    func testMalformedFigureFailsClosedToUnavailable() async throws {
        let data = try fixturePayload("malformed")
        XCTAssertThrowsError(try CommunityBenchmarks.decodeAggregates(data))
        let api = FakeAPI()
        api.result = .success(data)
        let loader = makeLoader(api: api)
        await loader.load()
        XCTAssertEqual(loader.state, .unavailable)
    }

    func testUnsupportedSummaryOmitsFigureWithCoverageNoteIntact() async throws {
        let payload = try CommunityBenchmarks.decodeAggregates(try fixturePayload("unsupportedKind"))
        let built = CommunityBenchmarks.cards(figures: payload.figures)
        XCTAssertEqual(built.cards.count, 1)
        XCTAssertEqual(built.cards.single?.title, "Typical heating time")
        XCTAssertEqual(built.omitted.count, 1)
        let omitted = try XCTUnwrap(built.omitted.single)
        XCTAssertEqual(omitted.title, "Temperature percentile bands")
        XCTAssertTrue(omitted.note.contains("10 eligible cooks"), omitted.note)
        XCTAssertTrue(omitted.note.contains("2026-10-18"), omitted.note)
        let api = FakeAPI()
        api.result = .success(try fixturePayload("unsupportedKind"))
        let loader = makeLoader(api: api)
        await loader.load()
        guard case .loaded(let snapshot) = loader.state else {
            return XCTFail("expected a loaded snapshot with one card and one note, got \(loader.state)")
        }
        XCTAssertEqual(snapshot.cards.count, 1)
        XCTAssertEqual(snapshot.omitted.count, 1)
    }

    func testMissingBandOmitsOnlyThatBandWithNoteIntact() throws {
        let figure = try JSONDecoder().decode(CommunityFigure.self, from: Data("""
        {"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"pit-food-curve-percentiles-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"intervalMillis":900000,"kind":"percentile-bands","intervals":[{"startElapsedMillis":0,"endElapsedMillis":900000,"pit":{"n":10,"coverage":1,"p10":100.9,"p50":104.5,"p90":108.1}}]},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"}
        """.utf8))
        let built = CommunityBenchmarks.cards(figures: [figure])
        let card = try XCTUnwrap(built.cards.single)
        XCTAssertEqual(card.lines, [
            "+0:00–0:15",
            "Pit: p10 100.9 °C · p50 104.5 °C · p90 108.1 °C (n=10, 100% of cooks)",
            "Food band unavailable for this interval.",
        ])
        XCTAssertEqual(card.coverageLine, "10 eligible cooks · Computed 2026-10-18")
    }

    func testEmptyStalledSubsetShowsNoMedians() throws {
        let figure = try JSONDecoder().decode(CommunityFigure.self, from: Data("""
        {"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"stall-summary-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"nStalled":0,"prevalence":0,"startElapsedMillis":{"p25":null,"median":null,"p75":null},"durationMillis":{"p25":null,"median":null,"p75":null},"tempCelsius":{"p25":null,"median":null,"p75":null}},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"}
        """.utf8))
        let built = CommunityBenchmarks.cards(figures: [figure])
        let card = try XCTUnwrap(built.cards.single)
        XCTAssertEqual(card.lines, [
            "Stalled: 0 of 10 assessed cooks (0%)",
            "No stalled cooks in this group.",
        ])
        XCTAssertEqual(card.notes, ["Sparse attempts are excluded from n, never interpolated."])
    }

    // MARK: - Loader states

    func testSignedOutPerformsNoNetworkCall() async {
        let api = FakeAPI()
        let mismatched = Box(0)
        let loader = makeLoader(api: api, token: nil, mismatched: mismatched)
        await loader.load()
        XCTAssertEqual(loader.state, .signedOut)
        XCTAssertTrue(api.aggregateCalls.isEmpty, "a signed-out loader must not touch the network")
        XCTAssertEqual(mismatched.value, 1, "one convergence refresh per view lifetime")
    }

    func testApi401MapsToSignedOutAnd403ToForbidden() async {
        for (status, expected) in [(401, CommunityBenchmarksState.signedOut), (403, CommunityBenchmarksState.forbidden)] {
            let api = FakeAPI()
            api.result = .failure(NativeCommunityError.status(status))
            let mismatched = Box(0)
            let loader = makeLoader(api: api, mismatched: mismatched)
            await loader.load()
            XCTAssertEqual(loader.state, expected, "status \(status)")
            XCTAssertEqual(mismatched.value, 1, "one convergence refresh per view lifetime")
            await loader.load()
            XCTAssertEqual(mismatched.value, 1, "the refresh must not loop")
        }
    }

    func testOfflineErrorMapsToUnavailable() async {
        for error in [NativeCommunityError.network, NativeCommunityError.status(404), NativeCommunityError.status(500)] {
            let api = FakeAPI()
            api.result = .failure(error)
            let loader = makeLoader(api: api)
            await loader.load()
            XCTAssertEqual(loader.state, .unavailable, "\(error)")
        }
        let loader = makeLoader()
        loader.reset()
        XCTAssertEqual(loader.state, .loading)
    }

    // MARK: - Wording guards

    func testWordingGuardsForbidRankingDonenessAndCausationClaims() throws {
        let payload = try CommunityBenchmarks.decodeAggregates(try fixturePayload("mixed"))
        let built = CommunityBenchmarks.cards(figures: payload.figures)
        var strings: [String] = [ProPlacement.benchmarks.preview] + proPreviewBullets(entry: .benchmarks)
        for card in built.cards {
            strings += [card.title, card.bucketLine, card.coverageLine] + card.lines + card.notes
        }
        let banned = ["faster", "slower", "because", "caus", "predict", "safe", "doneness", "undercook", "overcook", "rank", "leaderboard"]
        for string in strings {
            for word in banned {
                XCTAssertFalse(string.lowercased().contains(word), "\(string) contains \(word)")
            }
            // "should" is a whole-word check: the cut name "shoulder" in a
            // bucket line is a label, not a directive claim.
            XCTAssertNil(string.lowercased().range(of: "\\bshould\\b", options: .regularExpression), "\(string) contains should")
        }
        let curve = try XCTUnwrap(built.cards.first { $0.title == "Temperature percentile bands" })
        XCTAssertTrue(curve.title.contains("percentile bands"))
        XCTAssertTrue(curve.notes.joined().contains("Percentile bands"))
        for string in [curve.title, curve.bucketLine, curve.coverageLine] + curve.lines + curve.notes {
            XCTAssertFalse(string.lowercased().contains("individual"), "\(string) must never read as one cook's measurements")
        }
    }

    func testLoinAndButtNeverShareAGroupLabel() {
        XCTAssertEqual(CommunityBenchmarks.groupLabel("pork-loin"), "Pork loin")
        XCTAssertEqual(CommunityBenchmarks.groupLabel("pork-butt-shoulder"), "Pork butt / shoulder")
        XCTAssertNotEqual(CommunityBenchmarks.groupLabel("pork-loin"), CommunityBenchmarks.groupLabel("pork-butt-shoulder"))
        let labels = CommunityBenchmarks.groups.map(CommunityBenchmarks.groupLabel)
        XCTAssertEqual(Set(labels).count, labels.count, "every taxonomy group needs its own label")
        XCTAssertTrue(CommunityBenchmarks.bucketLine("pork-loin|medium|method:smoke").contains("Pork loin"))
        XCTAssertTrue(CommunityBenchmarks.bucketLine("pork-butt-shoulder|medium|method:smoke").contains("Pork butt / shoulder"))
    }

    func testStubPayloadsMatchSharedFixtureFile() throws {
        #if DEBUG
        let stubs: [(name: String, stub: Data)] = [
            ("mixed", CommunityBenchmarksUITestSupport.mixedPayload()),
            ("suppressed", CommunityBenchmarksUITestSupport.suppressedPayload()),
            ("empty", CommunityBenchmarksUITestSupport.emptyPayload()),
        ]
        for entry in stubs {
            let fromStub = try CommunityBenchmarks.decodeAggregates(entry.stub)
            let fromFile = try CommunityBenchmarks.decodeAggregates(try fixturePayload(entry.name))
            XCTAssertEqual(fromStub, fromFile, entry.name)
        }
        #endif
    }

    func testDurationFormattingPinsSharedRules() {
        XCTAssertEqual(CommunityBenchmarks.formatHours(19800000), "5h 30m")
        XCTAssertEqual(CommunityBenchmarks.formatHours(11700000), "3h 15m")
        XCTAssertEqual(CommunityBenchmarks.formatHours(27900000), "7h 45m")
        XCTAssertEqual(CommunityBenchmarks.formatHours(3600000), "1h")
        XCTAssertEqual(CommunityBenchmarks.formatHours(36000000), "10h")
        XCTAssertEqual(CommunityBenchmarks.formatHours(2700000), "45m")
        XCTAssertEqual(CommunityBenchmarks.formatHours(7200000), "2h")
        XCTAssertEqual(CommunityBenchmarks.formatHours(16200000), "4h 30m")
        XCTAssertEqual(CommunityBenchmarks.formatElapsed(0), "+0:00")
        XCTAssertEqual(CommunityBenchmarks.formatElapsed(900000), "+0:15")
        XCTAssertEqual(CommunityBenchmarks.formatElapsed(1800000), "+0:30")
        XCTAssertEqual(CommunityBenchmarks.formatElapsed(7200000), "+2:00")
        XCTAssertEqual(CommunityBenchmarks.formatPercent(0.6), "60%")
        XCTAssertEqual(CommunityBenchmarks.formatPercent(1), "100%")
        XCTAssertEqual(CommunityBenchmarks.formatPercent(0.3), "30%")
        XCTAssertEqual(CommunityBenchmarks.formatPercent(0), "0%")
        XCTAssertEqual(CommunityBenchmarks.formatTemp(104.5), "104.5 °C")
        XCTAssertEqual(CommunityBenchmarks.formatTemp(72.5), "72.5 °C")
        XCTAssertEqual(CommunityBenchmarks.formatTemp(110.0), "110.0 °C")
    }

    // MARK: - Typed client validation (no network)

    func testClientRejectsInvalidFiltersWithoutNetwork() async {
        let client = NativeCommunityBenchmarksClient()
        let token = String(repeating: "A", count: 43)
        let invalid: [(bucket: String?, metric: String?, runId: String?, access: String)] = [
            ("evil", nil, nil, token),
            (nil, "stub-heating-presence", nil, token),
            (nil, nil, "not-a-uuid", token),
            (nil, nil, nil, "short"),
        ]
        for call in invalid {
            do {
                _ = try await client.aggregates(bucket: call.bucket, metric: call.metric, runId: call.runId, access: call.access)
                XCTFail("invalid aggregates call accepted: \(call)")
            } catch let error as NativeCommunityError {
                XCTAssertEqual(error.localizedDescription, NativeCommunityError.invalid.localizedDescription)
            } catch {
                XCTFail("wrong error for \(call): \(error)")
            }
        }
        do {
            _ = try await client.aggregates(access: nil)
            XCTFail("missing Bearer [REDACTED]")
        } catch let error as NativeCommunityError {
            XCTAssertEqual(error.localizedDescription, NativeCommunityError.invalid.localizedDescription)
        } catch {
            XCTFail("wrong error for missing bearer: \(error)")
        }
    }

    func testJobRunsValidationRejectsBadLimitAndCursor() async {
        let client = NativeCommunityBenchmarksClient()
        let token = String(repeating: "A", count: 43)
        let invalid: [(limit: Int?, cursor: String?)] = [(0, nil), (101, nil), (nil, "bad")]
        for call in invalid {
            do {
                _ = try await client.jobRuns(limit: call.limit, cursor: call.cursor, access: token)
                XCTFail("invalid job-runs call accepted: \(call)")
            } catch let error as NativeCommunityError {
                XCTAssertEqual(error.localizedDescription, NativeCommunityError.invalid.localizedDescription)
            } catch {
                XCTFail("wrong error: \(error)")
            }
        }
        do {
            _ = try await client.jobRuns(access: nil)
            XCTFail("missing Bearer [REDACTED]")
        } catch let error as NativeCommunityError {
            XCTAssertEqual(error.localizedDescription, NativeCommunityError.invalid.localizedDescription)
        } catch {
            XCTFail("wrong error for missing bearer: \(error)")
        }
    }
}

private final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
