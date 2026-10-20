import XCTest
@testable import PitTech

final class RecipeCompatibilityTests: XCTestCase {
    private let snapshot = """
    {"version":1,"name":"Mac","description":"","tags":[],"draft":{"title":"Mac","dishes":[{"name":"Mac","foodType":"Pasta","ingredients":[{"name":"Gouda","amount":"4","unit":"oz"}]}]},"instructions":[],"observations":[{"sourceEventId":"prep","occurredAtUtcMillis":1000,"timeZoneId":"UTC","title":"Boiled macaroni","details":"Eight minutes","offsetMillis":-1000}],"targets":[],"resultNotes":"","smokingAnchorUtcMillis":2000,"servings":""}
    """
    func testPortableRecipePreservesOriginalHistoryAndValidatesAncestry() throws {
        let content = try RecipeSnapshot.decode(snapshot)
        XCTAssertEqual(content.observations[0].objectValue?["offsetMillis"]?.numberValue, -1000)
        var library = PitTechLibrary()
        library.recipes = [.object(["id": .string("recipe"), "name": .string("Mac"), "currentRevisionId": .string("revision")])]
        library.recipeRevisions = [.object(["id": .string("revision"), "recipeId": .string("recipe"), "revisionNumber": .number(1), "snapshotSchemaVersion": .number(1), "snapshotJson": .string(snapshot)])]
        try library.validateRecipes()
        let roundTrip = try JSONDecoder().decode(PitTechLibrary.self, from: JSONEncoder().encode(library))
        XCTAssertEqual(library.recipes, roundTrip.recipes)
        XCTAssertEqual(library.recipeRevisions, roundTrip.recipeRevisions)
        try roundTrip.validateRecipes()
        var invalid = library
        var row = invalid.recipeRevisions[0].objectValue!
        row["parentRevisionId"] = .string("revision")
        invalid.recipeRevisions[0] = .object(row)
        XCTAssertThrowsError(try invalid.validateRecipes())
        row.removeValue(forKey: "parentRevisionId")
        row["snapshotJson"] = .string(snapshot.replacingOccurrences(of: "\"Mac\"", with: "\"Spicy\""))
        invalid.recipeRevisions[0] = .object(row)
        XCTAssertThrowsError(try library.rejectRecipeConflicts(invalid))
        var branched = library
        var left = library.recipeRevisions[0].objectValue!
        left["id"] = .string("branch-a"); left["parentRevisionId"] = .string("revision"); left["revisionNumber"] = .number(2)
        var right = left; right["id"] = .string("branch-b"); right["changeNotes"] = .string("Another phone")
        branched.recipeRevisions += [.object(left), .object(right)]
        try branched.validateRecipes(); try library.rejectRecipeConflicts(branched)
        let branchRoundTrip = try JSONDecoder().decode(PitTechLibrary.self, from: JSONEncoder().encode(branched))
        try branchRoundTrip.validateRecipes()
        XCTAssertEqual(branchRoundTrip.recipeRevisions.count, 3)
        XCTAssertEqual(branchRoundTrip.recipes[0].objectValue?["currentRevisionId"]?.stringValue, "revision")
    }
    func testMissingAnchorsAndLegacyArchiveRemainUninvented() throws {
        let old = try JSONDecoder().decode(PitTechLibrary.self, from: Data("{\"schemaVersion\":6}".utf8))
        XCTAssertTrue(old.recipes.isEmpty)
        let raw = snapshot.replacingOccurrences(of: ",\"smokingAnchorUtcMillis\":2000", with: "")
        XCTAssertThrowsError(try RecipeSnapshot.decode(raw))
        let withoutOffset = raw.replacingOccurrences(of: ",\"offsetMillis\":-1000", with: "")
        XCTAssertNil(try RecipeSnapshot.decode(withoutOffset).smokingAnchorUtcMillis)
    }
}
