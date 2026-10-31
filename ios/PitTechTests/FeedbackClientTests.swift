import XCTest
@testable import PitTech

final class FeedbackClientTests: XCTestCase {
    func testBlankReportIsRejectedBeforeOpeningTheNetwork() async {
        let value = NativeFeedbackSubmission(kind: "BUG", title: "  ", description: "", appVersion: "test", osVersion: "17", device: "iPhone")
        do { _ = try await NativeFeedbackClient.submit(value); XCTFail("Blank title accepted") }
        catch { XCTAssertTrue(error is NativeFeedbackError) }
    }
    func testFeedbackPayloadIncludesOnlyReviewedTextAndCoarseAppDetails() throws {
        let value = NativeFeedbackSubmission(kind: "FEATURE", title: "Recipe order", description: "Custom sort", appVersion: "1.0", osVersion: "17", device: "iPhone")
        let data = try JSONEncoder().encode(value)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["kind", "title", "description", "appVersion", "platform", "osVersion", "device"])
        XCTAssertEqual(json["platform"] as? String, "ios")
        XCTAssertNil(json["diagnosticReport"])
        XCTAssertNil(json["deviceId"])
        XCTAssertNil(json["cooks"])
    }
    func testDiagnosticReportRidesOnlyWhenAttached() throws {
        // PL-20 (#184): the payload is exactly the relay's five fields,
        // present only when the user opted in.
        let report = DiagnosticReport(referenceCode: "PT-ABCDEF12", occurredAtUtc: "2026-10-30 09:15:00 UTC", source: "Uncaught application exception", summary: "Boom: it broke", details: "Thread: main")
        let value = NativeFeedbackSubmission(kind: "BUG", title: "Crash", description: "It crashed", appVersion: "1.0", osVersion: "17", device: "iPhone", diagnosticReport: report)
        let data = try JSONEncoder().encode(value)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let attached = try XCTUnwrap(json["diagnosticReport"] as? [String: Any])
        XCTAssertEqual(Set(attached.keys), ["referenceCode", "occurredAtUtc", "source", "summary", "details"])
        XCTAssertEqual(attached["referenceCode"] as? String, "PT-ABCDEF12")
        XCTAssertEqual(attached["summary"] as? String, "Boom: it broke")
    }
}
