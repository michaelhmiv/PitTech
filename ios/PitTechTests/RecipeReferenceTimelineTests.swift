import XCTest
@testable import PitTech
final class RecipeReferenceTimelineTests: XCTestCase {
    func testRecordedIntervalsAndNegativePreparationRemainIndependentFromInstructions() throws {
        func event(_ id: String, _ at: Double, _ action: String?) -> JSONValue { .object(["sourceEventId": .string(id), "occurredAtUtcMillis": .number(at), "timeZoneId": .string("UTC"), "title": .string(id), "details": .string("Recorded"), "action": action.map(JSONValue.string) ?? .null, "offsetMillis": .number(at - 100_000)]) }
        var snapshot = RecipeSnapshot(version: 1, name: "Mac", description: "", tags: [], draft: .object(["dishes": .array([.object(["name": .string("Mac")])])]), instructions: [], observations: [event("prep", 40_000, nil), event("on", 100_000, "food_on"), event("wrap", 150_000, "wrap"), event("off", 200_000, "remove"), event("return", 300_000, "food_on")], targets: [], resultNotes: "", smokingAnchorUtcMillis: 100_000, servings: "")
        XCTAssertEqual(RecipeReferenceTimeline.heating(snapshot), [.init(startedAt: 100_000, endedAt: 200_000), .init(startedAt: 300_000, endedAt: nil)])
        XCTAssertEqual(RecipeReferenceTimeline.relative(-60_000), "−0h 1m")
        let original = RecipeReferenceTimeline.events(snapshot); snapshot.instructions = [.object(["id": .string("new"), "title": .string("Different instruction")])]
        XCTAssertEqual(RecipeReferenceTimeline.events(snapshot), original)
        XCTAssertEqual(try RecipeSnapshot.decode(recipeJSON(snapshot)).observations, original)
        XCTAssertEqual(RecipeReferenceTimeline.relative(nil), "No recorded Food on anchor")
    }
}
