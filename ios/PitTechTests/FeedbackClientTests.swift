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
}
