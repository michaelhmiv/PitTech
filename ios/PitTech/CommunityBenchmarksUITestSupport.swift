#if DEBUG
import Foundation

/// UI automation only: scripted aggregate responses with the same figures as
/// `contracts/fixtures/community-benchmarks-v1.json`. Never contacts a server.
///
/// Launch arguments (alongside `--pro-ui-test`, which scripts entitlement):
/// - default: the mixed payload (four visible figures plus one n=9
///   insufficient-data card);
/// - `--community-insufficient`: only the n=9 suppressed figure;
/// - `--community-empty`: `{ run: null, figures: [] }`;
/// - `--community-unavailable`: every request throws a network error;
/// - `--community-forbidden`: every request answers 403 `pro_required`.
@MainActor enum CommunityBenchmarksUITestSupport {
    static var active: Bool {
        CommandLine.arguments.contains("--pro-ui-test") || CommandLine.arguments.contains("--community-ui-test")
    }

    // The stub payloads below repeat the shared fixture's values byte for
    // byte in meaning; CommunityBenchmarksTests pins them equal to the file.
    nonisolated static func mixedPayload() -> Data {
        Data("""
        {"run":{"runId":"12345678-1234-4234-8234-123456789abc","cutoffMillis":1792324800000,"computedAtMillis":1792324860000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1","metricVersions":[{"metricId":"median-heating-time-v1","metricVersion":"community-metrics-v1"},{"metricId":"pit-food-curve-percentiles-v1","metricVersion":"community-metrics-v1"},{"metricId":"stall-summary-v1","metricVersion":"community-metrics-v1"},{"metricId":"wrap-spritz-prevalence-v1","metricVersion":"community-metrics-v1"}],"inputCount":19,"eligibleCount":19,"unbucketedCount":0,"cellCount":5,"visibleCount":4,"outputSha256":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"},"figures":[{"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"median-heating-time-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"medianHeatingMillis":19800000,"p25HeatingMillis":11700000,"p75HeatingMillis":27900000,"iqrMillis":16200000,"minHeatingMillis":3600000,"maxHeatingMillis":36000000},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"},{"bucket":"pork-loin|medium|method:smoke","metricId":"median-heating-time-v1","metricVersion":"community-metrics-v1","n":9,"status":"insufficient_data","summary":null,"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"},{"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"pit-food-curve-percentiles-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"intervalMillis":900000,"kind":"percentile-bands","intervals":[{"startElapsedMillis":0,"endElapsedMillis":900000,"pit":{"n":10,"coverage":1,"p10":100.9,"p50":104.5,"p90":108.1},"food":{"n":10,"coverage":1,"p10":50.9,"p50":54.5,"p90":58.1}},{"startElapsedMillis":900000,"endElapsedMillis":1800000,"pit":{"n":10,"coverage":1,"p10":110.0,"p50":115.5,"p90":120.2},"food":{"n":3,"coverage":0.3,"p10":null,"p50":null,"p90":null}}]},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"},{"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"stall-summary-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"nStalled":6,"prevalence":0.6,"startElapsedMillis":{"p25":0,"median":0,"p75":900000},"durationMillis":{"p25":7200000,"median":7200000,"p75":8100000},"tempCelsius":{"p25":71.25,"median":72.5,"p75":73.75},"note":"Measured stall windows only; sparse attempts are excluded from n, never interpolated."},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"},{"bucket":"pork-butt-shoulder|medium|method:smoke","metricId":"wrap-spritz-prevalence-v1","metricVersion":"community-metrics-v1","n":10,"status":"visible","summary":{"n":10,"nWrapped":6,"wrapPrevalence":0.6,"nSpritzed":3,"spritzPrevalence":0.3,"methods":[{"label":"smoke","n":10,"prevalence":1}],"nMethodRecorded":10,"nMethodUnrecorded":0},"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"}]}
        """.utf8)
    }

    nonisolated static func suppressedPayload() -> Data {
        Data("""
        {"run":{"runId":"12345678-1234-4234-8234-123456789abc","cutoffMillis":1792324800000,"computedAtMillis":1792324860000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1","metricVersions":[{"metricId":"median-heating-time-v1","metricVersion":"community-metrics-v1"}],"inputCount":9,"eligibleCount":9,"unbucketedCount":0,"cellCount":1,"visibleCount":0,"outputSha256":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"},"figures":[{"bucket":"pork-loin|medium|method:smoke","metricId":"median-heating-time-v1","metricVersion":"community-metrics-v1","n":9,"status":"insufficient_data","summary":null,"computedDate":"2026-10-18","cutoffMillis":1792324800000,"algorithmVersion":"community-aggregation-framework-v0","taxonomyVersion":"community-taxonomy-v1","bandVersion":"community-bands-v1"}]}
        """.utf8)
    }

    nonisolated static func emptyPayload() -> Data {
        Data("""{"run":null,"figures":[]}""".utf8)
    }

    private struct StubAPI: NativeCommunityAPI {
        func aggregates(bucket: String?, metric: String?, runId: String?, access: String?) async throws -> Data {
            guard access != nil else { throw NativeCommunityError.status(401) }
            if CommandLine.arguments.contains("--community-unavailable") { throw NativeCommunityError.network }
            if CommandLine.arguments.contains("--community-forbidden") { throw NativeCommunityError.status(403) }
            if CommandLine.arguments.contains("--community-empty") { return CommunityBenchmarksUITestSupport.emptyPayload() }
            if CommandLine.arguments.contains("--community-insufficient") { return CommunityBenchmarksUITestSupport.suppressedPayload() }
            return CommunityBenchmarksUITestSupport.mixedPayload()
        }

        func jobRuns(limit: Int?, cursor: String?, access: String?) async throws -> Data {
            guard access != nil else { throw NativeCommunityError.status(401) }
            return try JSONSerialization.data(withJSONObject: ["runs": [], "nextCursor": NSNull()])
        }
    }

    static func make() -> CommunityBenchmarksLoader {
        let signedIn = !CommandLine.arguments.contains("--pro-signed-out")
        return CommunityBenchmarksLoader(
            api: StubAPI(),
            sessionProvider: { signedIn ? String(repeating: "A", count: 43) : nil }
        )
    }
}
#endif
