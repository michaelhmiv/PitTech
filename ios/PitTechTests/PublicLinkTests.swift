import XCTest
@testable import PitTech

final class PublicLinkTests: XCTestCase {
    func testNavigationOnlyLinksMatchSharedContract() throws {
        let data = try Data(contentsOf: XCTUnwrap(Bundle(for: Self.self).url(forResource: "public-links-v1", withExtension: "json")))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for row in try XCTUnwrap(fixture["valid"] as? [[String: String]]) {
            XCTAssertEqual(try NativePublicLink.destination(XCTUnwrap(row["input"])), row["destination"])
        }
        for input in try XCTUnwrap(fixture["invalid"] as? [String]) { XCTAssertThrowsError(try NativePublicLink.destination(input), input) }
    }
    @MainActor func testDisabledCommunityRejectsNavigationWithoutImportingOrLoggingIn() {
        let coordinator = NativePublicCoordinator()
        XCTAssertFalse(coordinator.receivePublic(URL(string: "https://pittech.app/p/oak_cook")!))
        XCTAssertTrue(coordinator.path.isEmpty)
        XCTAssertEqual(coordinator.linkSequence, 0)
    }
}
