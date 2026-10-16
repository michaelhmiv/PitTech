import XCTest
@testable import PitTech

final class SourceComparisonTests: XCTestCase {
    func testSharedSourceReviewDetectsReorderingWithoutInventingAnIngredientChange() throws {
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "source-review-v1", withExtension: "json"))
        let fixture = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: file)), row = try XCTUnwrap(fixture.objectValue)
        let snapshot = try RecipeSnapshot.decode(recipeJSON(try XCTUnwrap(row["snapshot"])))
        let shared = try XCTUnwrap(row["shared"]), sections = try NativeSourceComparison.sections(snapshot, recipe: shared)
        XCTAssertEqual(sections.map(\.changed), try XCTUnwrap(row["expectedChanged"]?.arrayValue?.compactMap(\.boolValue))); XCTAssertEqual(sections[0].saved, ["Salt · 2 tsp"])
        XCTAssertTrue(sections[1].saved[0].contains("Wrap")); XCTAssertTrue(sections[1].shared[0].contains("Finish"))
        var malformed = try XCTUnwrap(shared.objectValue); malformed["ingredients"] = .array([.object(["name": .string(String(repeating: "x", count: 161)), "amount": .string("")])])
        XCTAssertThrowsError(try NativeSourceComparison.sections(snapshot, recipe: .object(malformed)))
        var hugeOffset = try XCTUnwrap(shared.objectValue), steps = try XCTUnwrap(shared.objectValue?["instructions"]?.arrayValue), step = try XCTUnwrap(steps[0].objectValue)
        step["suggestedOffsetMillis"] = .number(1e15); steps[0] = .object(step); hugeOffset["instructions"] = .array(steps)
        XCTAssertThrowsError(try NativeSourceComparison.sections(snapshot, recipe: .object(hugeOffset)))
        XCTAssertEqual(snapshot.instructions.first?.objectValue?["title"]?.stringValue, "Wrap")
    }
}
