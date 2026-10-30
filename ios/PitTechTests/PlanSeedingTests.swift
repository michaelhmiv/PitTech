import XCTest
@testable import PitTech

/// Slice 4/5 plan seeding: durations come from the recipe's own provenance
/// or its frozen public story — and say so honestly when neither exists.
@MainActor final class PlanSeedingTests: XCTestCase {
    private func snapshot(anchor: Int64?, observations: [JSONValue], publicSource: PublicRecipeSource? = nil) -> RecipeSnapshot {
        let dish: JSONValue = .object([
            "name": .string("Oak-smoked pork shoulder"), "foodType": .string("Pork"), "cut": .string("Shoulder"),
            "weight": .string("8.5"), "weightUnit": .string("lb"), "ingredients": .array([]),
        ])
        return RecipeSnapshot(
            version: 1, name: "Oak-smoked pork shoulder", description: "", tags: [],
            draft: .object(["title": .string("Weekend smoke"), "dishes": .array([dish])]),
            instructions: [], observations: observations, targets: [], resultNotes: "",
            rating: nil, smokingAnchorUtcMillis: anchor, preferredPitTemperatureF: nil,
            servings: "", cookAgain: nil, publicSource: publicSource)
    }

    private func observation(_ action: String, _ offset: Int64, anchor: Int64) -> JSONValue {
        .object([
            "id": .string(UUID().uuidString.lowercased()), "sourceEventId": .string(UUID().uuidString.lowercased()),
            "action": .string(action), "title": .string(action), "details": .string(""),
            "occurredAtUtcMillis": .number(Double(anchor + offset)), "offsetMillis": .number(Double(offset)),
        ])
    }

    func testFromRecipeSeedsTheMeasuredTotalAndRest() {
        let anchor: Int64 = 946_684_800_000
        let provenance = RecipeProvenance(
            sourceCookId: "cook-1", sourceDishId: "dish-1", cookTitle: "October smoke",
            cookedDateText: "October 12, 2026", cookedAtUtcMillis: anchor, timeZoneId: "America/New_York",
            pitName: "Kettle", setpointF: 250, wood: nil, fuel: nil, dishName: "Pork shoulder",
            cut: "Shoulder", weightText: "8.5 lb", totalMinutes: 578, stallMinutes: nil, wrappedAtF: nil,
            restMinutes: 42, curve: [], photos: [], note: nil, rating: nil, cookAgain: nil, keepChips: [], changeChips: [])
        let lane = PlanSeeding.fromRecipe(snapshot: snapshot(anchor: anchor, observations: []), provenance: provenance)
        XCTAssertEqual(lane?.schedule.cookMinMinutes, 578)
        XCTAssertEqual(lane?.schedule.cookMaxMinutes, 578)
        XCTAssertEqual(lane?.schedule.restMinutes, 42)
        XCTAssertEqual(lane?.schedule.pitTemperatureF, 250)
        XCTAssertEqual(lane?.schedule.evidenceCount, 1)
        XCTAssertEqual(lane?.schedule.evidenceNote, "Seeded from your October 12, 2026 cook — 9 h 38 min on the pit, 42 min rest (one recorded cook; adjust if this one runs different)")
    }

    func testFromRecipeWithoutAMeasuredTotalSeedsNothing() {
        let anchor: Int64 = 946_684_800_000
        var provenance = RecipeProvenance(
            sourceCookId: "cook-1", sourceDishId: "dish-1", cookTitle: "October smoke",
            cookedDateText: "October 12, 2026", cookedAtUtcMillis: anchor, timeZoneId: "America/New_York",
            pitName: nil, setpointF: nil, wood: nil, fuel: nil, dishName: "Pork shoulder",
            cut: nil, weightText: nil, totalMinutes: nil, stallMinutes: nil, wrappedAtF: nil,
            restMinutes: nil, curve: [], photos: [], note: nil, rating: nil, cookAgain: nil, keepChips: [], changeChips: [])
        XCTAssertNil(PlanSeeding.fromRecipe(snapshot: snapshot(anchor: anchor, observations: []), provenance: provenance))
        XCTAssertNil(PlanSeeding.fromRecipe(snapshot: snapshot(anchor: anchor, observations: []), provenance: nil))
        provenance = RecipeProvenance(
            sourceCookId: "cook-1", sourceDishId: "dish-1", cookTitle: "October smoke",
            cookedDateText: "October 12, 2026", cookedAtUtcMillis: anchor, timeZoneId: "America/New_York",
            pitName: nil, setpointF: nil, wood: nil, fuel: nil, dishName: "Pork shoulder",
            cut: nil, weightText: nil, totalMinutes: 0, stallMinutes: nil, wrappedAtF: nil,
            restMinutes: nil, curve: [], photos: [], note: nil, rating: nil, cookAgain: nil, keepChips: [], changeChips: [])
        XCTAssertNil(PlanSeeding.fromRecipe(snapshot: snapshot(anchor: anchor, observations: []), provenance: provenance))
    }

    func testFromSnapshotStorySeedsFromTheFrozenStory() {
        let anchor: Int64 = 946_684_800_000
        let source = PublicRecipeSource(itemId: "item-1", revisionId: "rev-1", displayName: "a PitTech cook")
        let seeded = PlanSeeding.fromSnapshotStory(snapshot(anchor: anchor, observations: [
            observation("food_on", 0, anchor: anchor),
            observation("wrap", 12_000_000, anchor: anchor),
            observation("remove", 27_600_000, anchor: anchor),
        ], publicSource: source))
        XCTAssertEqual(seeded?.schedule.cookMinMinutes, 460)
        XCTAssertEqual(seeded?.schedule.cookMaxMinutes, 460)
        XCTAssertEqual(seeded?.schedule.evidenceNote, "Durations from a PitTech cook's recorded cook — 7 h 40 min on the pit (adjust anytime in the plan)")
        // No public credit: the note names the recipe's own cook instead.
        let own = PlanSeeding.fromSnapshotStory(snapshot(anchor: anchor, observations: [
            observation("food_on", 0, anchor: anchor),
            observation("remove", 27_600_000, anchor: anchor),
        ]))
        XCTAssertEqual(own?.schedule.evidenceNote, "Seeded from this recipe's recorded cook — 7 h 40 min on the pit (adjust anytime in the plan)")
    }

    func testFromSnapshotStoryNeedsAnAnchorAndAnEnd() {
        let anchor: Int64 = 946_684_800_000
        XCTAssertNil(PlanSeeding.fromSnapshotStory(snapshot(anchor: nil, observations: [observation("remove", 27_600_000, anchor: anchor)])))
        XCTAssertNil(PlanSeeding.fromSnapshotStory(snapshot(anchor: anchor, observations: [observation("food_on", 0, anchor: anchor)])))
    }

    func testFormatDuration() {
        XCTAssertEqual(PlanSeeding.formatDuration(460), "7 h 40 min")
        XCTAssertEqual(PlanSeeding.formatDuration(120), "2 h")
        XCTAssertEqual(PlanSeeding.formatDuration(45), "45 min")
        XCTAssertEqual(PlanSeeding.formatDuration(60), "1 h")
    }
}
