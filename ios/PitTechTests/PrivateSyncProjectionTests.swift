import XCTest
@testable import PitTech

final class PrivateSyncProjectionTests: XCTestCase {
    private struct Fixture: Decodable { let contractVersion: Int; let cases: [Case]; let projection: Projection }
    private struct Case: Decodable { let name: String; let rows: [NativePrivateProjectedRecord]; let rejected: Bool }
    private struct Projection: Decodable { let archive: JSONValue; let assets: [String: String]; let expected: [NativePrivateProjectedRecord] }
    func testSharedProjectionRejectsActivationCredentialsAndBrokenOwnership() throws {
        let bundle = Bundle(for: Self.self)
        let data = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "private-sync-projection-v1", withExtension: "json")))
        let projection = try NativePrivateSyncProjection(data: data)
        let fixtureData = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "private-sync-projection-cases-v1", withExtension: "json")))
        let fixture = try JSONDecoder().decode(Fixture.self, from: fixtureData)
        XCTAssertEqual(fixture.contractVersion, 1); XCTAssertEqual(fixture.cases.count, 20)
        for c in fixture.cases {
            if c.rejected { XCTAssertThrowsError(try projection.validateRows(c.rows), c.name) }
            else { XCTAssertNoThrow(try projection.validateRows(c.rows), c.name) }
        }
        let p = fixture.projection
        XCTAssertEqual(try projection.project(p.archive, assets: p.assets), p.expected)
        XCTAssertThrowsError(try projection.project(p.archive))
        var unknown = try XCTUnwrap(p.archive.objectValue); unknown["unknownCredentialStore"] = .object([:])
        XCTAssertThrowsError(try projection.project(.object(unknown), assets: p.assets))
        XCTAssertEqual(try JSONDecoder().decode(JSONValue.self, from: data).objectValue?["releaseReady"], .bool(false))
    }
}
