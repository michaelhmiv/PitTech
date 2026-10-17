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
        XCTAssertEqual(fixture.contractVersion, 1); XCTAssertEqual(fixture.cases.count, 64)
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
    @MainActor func testActualAndroidArchiveProjectsFullyAfterNativeImportWithoutChangingSavedJournal() throws {
        let bundle = Bundle(for: Self.self)
        let contract = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "private-sync-projection-v1", withExtension: "json")))
        let projection = try NativePrivateSyncProjection(data: contract)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        let archive = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: "android-guide-meal-v10", withExtension: "zip")))
        _ = try store.importArchive(archive)
        let before = store.library
        XCTAssertThrowsError(try projection.project(before))
        // The complete portable vocabulary covers this fixture: full-library projection
        // succeeds and preserves every authored document, not just the core graph.
        let excluded = Set((try? JSONDecoder().decode(JSONValue.self, from: contract).objectValue?["excludedDocumentKinds"]?.arrayValue?.compactMap(\.stringValue)) ?? [])
        var assets: [String: String] = [:]
        for (type, rows) in [("photos", before.photos), ("recipeAssets", before.recipeAssets), ("companionPhotos", before.companionPhotos)] {
            for row in rows { let id = try XCTUnwrap(row.objectValue?["id"]?.stringValue); assets[type + "|" + id] = UUID().uuidString.lowercased() }
        }
        let projected = try projection.project(before, assets: assets)
        XCTAssertEqual(projected.filter { $0.recordType == "cooks" }.count, before.cooks.count)
        XCTAssertEqual(projected.filter { $0.recordType == "recipes" }.count, 4)
        XCTAssertEqual(projected.filter { $0.recordType == "readings" }.count, before.readings.count)
        XCTAssertEqual(projected.filter { $0.recordType == "dishes" }.count, before.dishes.count)
        XCTAssertEqual(projected.filter { $0.recordType == "events" }.count, before.events.count)
        XCTAssertEqual(projected.filter { $0.recordType == "reminderRules" }.count, before.reminderRules.count)
        let expectedCompanions = before.companionRecords.filter { !excluded.contains($0.objectValue?["kind"]?.stringValue ?? "") }.count
        XCTAssertGreaterThan(expectedCompanions, 0)
        XCTAssertEqual(projected.filter { $0.recordType == "companionRecords" }.count, expectedCompanions)
        XCTAssertEqual(store.library.companionRecords, before.companionRecords)
        XCTAssertEqual(store.library.reminderRules, before.reminderRules)
        XCTAssertTrue(projected.allSatisfy { $0.payload["archivePath"] == nil })
    }
}
