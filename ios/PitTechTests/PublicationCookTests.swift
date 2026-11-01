import XCTest
@testable import PitTech

/// Publishable Cooks (issue #250), iOS writer: a meal draft that carries
/// its recorded session publishes as kind=cook — session object on the
/// payload, every dish's story placed on the session clock, cook inputs
/// lifted into the stories — and forking the result saves its dishes as
/// library recipes (Option A: the food, not the session). Mirrors
/// Android's PublicationCookTest case for case.
final class PublicationCookTests: XCTestCase {
    private let start = 1_700_000_000_000.0

    private func sessionJson() -> JSONValue {
        .object([
            "startedAtUtcMillis": .number(start), "endedAtUtcMillis": .number(start + 40_800_000),
            "smokerName": .string("Offset smoker"), "fuelType": .string("Pellets"), "woodOrPelletBlend": .string("Oak"),
            "setpointValue": .number(250), "setpointUnit": .string("°F"),
            "outdoorTemperatureValue": .number(61), "outdoorTemperatureUnit": .string("°F"),
            "weatherNotes": .string("Clear"), "windNotes": .string(""), "notes": .string("Game day"),
            "serveAtUtcMillis": .number(start + 39_600_000),
        ])
    }

    private func observation(id: String, at: Double, anchor: Double, action: String, title: String) -> JSONValue {
        .object(["sourceEventId": .string(id), "occurredAtUtcMillis": .number(at), "timeZoneId": .string("UTC"),
                 "title": .string(title), "details": .string(""), "action": .string(action),
                 "offsetMillis": .number(at - anchor), "recordedTemperatures": .array([])])
    }

    private func reading(id: String, at: Double, type: String, value: Double) -> JSONValue {
        .object(["id": .string(id), "probeId": .string("probe-" + id), "probeName": .string(type == "pit" ? "Pit probe" : "Food probe"),
                 "measurementType": .string(type), "value": .number(value), "unit": .string("°F"),
                 "measuredAtUtcMillis": .number(at), "timeZoneId": .string("UTC"), "timestampBasis": .string("recorded"),
                 "samplingIntervalMillis": .number(0), "qualityStatus": .string("valid"), "source": .string("recorded")])
    }

    private func result(_ type: String, text: String? = nil, numeric: Double? = nil) -> JSONValue {
        .object(["type": .string(type), "textValue": text.map(JSONValue.string) ?? .null,
                 "numericValue": numeric.map(JSONValue.number) ?? .null, "unit": .null])
    }

    private func story(attemptId: String, anchor: Double, observations: [JSONValue], readings: [JSONValue], results: [JSONValue]) -> JSONValue {
        .object(["version": .number(1), "attemptId": .string(attemptId), "anchorUtcMillis": .number(anchor),
                 "connectionGaps": .array([]), "observations": .array(observations),
                 "readings": .array(readings), "results": .array(results)])
    }

    private func snapshot(_ name: String, foodType: String) throws -> String {
        let dish: [String: JSONValue] = ["name": .string(name), "foodType": .string(foodType)]
        let snapshot = RecipeSnapshot(version: 1, name: name, description: "", tags: [], draft: .object(["dishes": .array([.object(dish)])]), instructions: [], observations: [], targets: [], resultNotes: "", rating: nil, smokingAnchorUtcMillis: nil, servings: "")
        return try recipeJSON(snapshot)
    }

    private func brisket() throws -> NativeSharingEntry {
        let anchor = start + 3_600_000
        return NativeSharingEntry(sourceDishId: "dish-1", snapshotJson: try snapshot("Brisket", foodType: "Beef"),
            story: story(attemptId: "dish-1", anchor: anchor,
                observations: [observation(id: "e1", at: anchor, anchor: anchor, action: "food_on", title: "Food on"),
                               observation(id: "e2", at: anchor + 7_200_000, anchor: anchor, action: "wrap", title: "Wrapped")],
                readings: [reading(id: "r1", at: anchor + 600_000, type: "pit", value: 250)],
                results: [result("wrap_material", text: "butcher_paper"), result("rub", text: "House rub"), result("spritz", text: "yes"), result("spritz_medium", text: "Apple juice"), result("overall_rating", numeric: 5), result("result_notes", text: "Great bark")]),
            included: true, includeStory: true)
    }

    private func potatoes() throws -> NativeSharingEntry {
        let anchor = start + 5_400_000
        return NativeSharingEntry(sourceDishId: "dish-2", snapshotJson: try snapshot("Smoked potatoes", foodType: "Potatoes"),
            story: story(attemptId: "dish-2", anchor: anchor,
                observations: [observation(id: "e3", at: anchor, anchor: anchor, action: "food_on", title: "Food on"),
                               observation(id: "e4", at: anchor + 18_000_000, anchor: anchor, action: "remove", title: "Food off")],
                readings: [reading(id: "r2", at: anchor + 600_000, type: "food", value: 200)],
                results: [result("overall_rating", numeric: 4)]),
            included: true, includeStory: true)
    }

    private func document(entries: [NativeSharingEntry]? = nil, session: JSONValue?? = nil) throws -> NativeSharingDocument {
        let resolved = try entries ?? [brisket(), potatoes()]
        return NativeSharingDocument(title: "Game day cook", sourceCookId: "cook-1", category: "beef",
                                     entries: resolved,
                                     sessionJson: session ?? sessionJson())
    }

    func testCookPublicationCarriesTheSessionStoriesOnOneClockAndLiftedInputs() throws {
        let payload = try NativePublicationProjection.build(try document(), updated: 1, photos: [], kind: "cook").payload
        XCTAssertEqual(payload.objectValue?["kind"]?.stringValue, "cook")
        XCTAssertEqual(payload.objectValue?["measuredAttemptId"], .null)
        let session = try XCTUnwrap(payload.objectValue?["session"]?.objectValue)
        XCTAssertEqual(session["smokerName"]?.stringValue, "Offset smoker")
        XCTAssertEqual(session["fuelType"]?.stringValue, "Pellets")
        XCTAssertEqual(session["setpoint"]?.objectValue?["value"]?.numberValue, 250)
        XCTAssertEqual(session["setpoint"]?.objectValue?["unit"]?.stringValue, "°F")
        XCTAssertEqual(session["serveAtOffsetMillis"]?.numberValue, 39_600_000)
        XCTAssertEqual(session["durationMillis"]?.numberValue, 40_800_000)
        let recipes = try XCTUnwrap(payload.objectValue?["recipes"]?.arrayValue)
        let first = try XCTUnwrap(recipes[0].objectValue?["story"]?.objectValue)
        XCTAssertEqual(first["anchorOffsetMillis"]?.numberValue, 3_600_000)
        XCTAssertEqual(first["foodOnOffsetMillis"]?.numberValue, 0)
        let inputs = try XCTUnwrap(first["inputs"]?.objectValue)
        XCTAssertEqual(inputs["wrapMaterial"]?.stringValue, "butcher_paper")
        XCTAssertEqual(inputs["rub"]?.stringValue, "House rub")
        XCTAssertEqual(inputs["spritz"]?.boolValue, true)
        XCTAssertEqual(inputs["spritzMedium"]?.stringValue, "Apple juice")
        let second = try XCTUnwrap(recipes[1].objectValue?["story"]?.objectValue)
        XCTAssertEqual(second["anchorOffsetMillis"]?.numberValue, 5_400_000)
        XCTAssertEqual(second["inputs"], .null, "a dish with no recorded inputs publishes inputs as null, never invented ones")
    }

    func testOtherKindsGainNoSessionAndNoSessionClockFields() throws {
        let payload = try NativePublicationProjection.build(try document(), updated: 1, photos: [], kind: "collection").payload
        XCTAssertNil(payload.objectValue?["session"])
        let story = try XCTUnwrap(payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue?["story"]?.objectValue)
        XCTAssertNil(story["anchorOffsetMillis"])
        XCTAssertNil(story["inputs"])
    }

    func testCookPublicationRequiresTheSessionAndEveryStory() throws {
        XCTAssertThrowsError(try NativePublicationProjection.build(try document(session: .some(nil)), updated: 1, photos: [], kind: "cook"))
        var storyless = try potatoes(); storyless.includeStory = false
        XCTAssertThrowsError(try NativePublicationProjection.build(try document(entries: [try brisket(), storyless]), updated: 1, photos: [], kind: "cook")) { error in
            XCTAssertEqual((error as? NativePublicationError)?.errorDescription, "A cook publication needs the recorded session and every dish's story. Exclude a dish to leave it out of the cook.")
        }
    }

    func testCookPublicationRejectsAStoryFromOutsideTheSessionWindow() throws {
        let anchor = start - 60_000
        let foreign = NativeSharingEntry(sourceDishId: "dish-9", snapshotJson: try snapshot("Yesterday's ribs", foodType: "Pork"),
            story: story(attemptId: "dish-9", anchor: anchor,
                observations: [observation(id: "e9", at: anchor, anchor: anchor, action: "food_on", title: "Food on")],
                readings: [], results: []),
            included: true, includeStory: true)
        XCTAssertThrowsError(try NativePublicationProjection.build(try document(entries: [try brisket(), foreign]), updated: 1, photos: [], kind: "cook")) { error in
            XCTAssertEqual((error as? NativePublicationError)?.errorDescription, "A dish's recorded story doesn't belong to this cook session, so it can't join the cook publication. Exclude it, or publish the meal as a collection instead.")
        }
    }

    func testSessionFactsRoundTripThroughTheDraftCodecAndLegacyDraftsStayReadable() throws {
        let encoded = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(try document()))
        XCTAssertEqual(encoded.objectValue?["session"], sessionJson())
        let decoded = try NativeSharingDocument.decode(encoded)
        XCTAssertEqual(decoded.sessionJson, sessionJson())
        var legacy = try XCTUnwrap(encoded.objectValue); legacy["session"] = nil
        XCTAssertNil(try NativeSharingDocument.decode(.object(legacy)).sessionJson)
    }

    func testDiscoverParsesCookItems() throws {
        let json = "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"revisionId\":\"00000000-0000-4000-8000-000000000002\",\"title\":\"Game day cook\",\"kind\":\"cook\",\"credit\":{\"displayName\":\"a PitTech cook\"},\"likes\":3,\"forks\":1,\"heroAssetId\":null,\"category\":\"beef\"}"
        let item = try JSONDecoder().decode(NativePublicItem.self, from: Data(json.utf8)); try item.validate()
        XCTAssertEqual(item.kind, "cook")
    }

    func testCookCardFactsPreferTheSessionDuration() throws {
        let payload = try NativePublicationProjection.build(try document(), updated: 1, photos: [], kind: "cook").payload
        let detail = NativePublicDetail(id: "00000000-0000-4000-8000-0000000000aa", revisionId: "00000000-0000-4000-8000-0000000000ab", payload: payload,
                                         credit: NativePublicItem.Credit(displayName: "a PitTech cook", profileUrl: nil), likes: 0, forks: 0, media: [])
        XCTAssertEqual(publicCardFacts(detail: detail, temperatureUnit: "°F").timeLabel, "11H 20M")
    }

    func testForkingACookPublicationSavesItsDishesAsRecipes() throws {
        let payload = try NativePublicationProjection.build(try document(), updated: 1, photos: [], kind: "cook").payload
        let detail = NativePublicDetail(id: "00000000-0000-4000-8000-0000000000aa", revisionId: "00000000-0000-4000-8000-0000000000ab", payload: payload,
                                         credit: NativePublicItem.Credit(displayName: "a PitTech cook", profileUrl: nil), likes: 0, forks: 0, media: [])
        let saved = try PublicationFork.prepare(detail: detail, tweaks: PublicationFork.untweaked(detail: detail))
        XCTAssertEqual(saved.count, 2)
        XCTAssertEqual(saved.map { $0.snapshot.name }, ["Brisket", "Smoked potatoes"])
        for entry in saved {
            XCTAssertEqual(entry.snapshot.publicSource?.itemId, detail.id)
            XCTAssertTrue(entry.storyPresent, "each forked dish keeps its measured story pinned")
        }
    }

    func testOwnerUpdateOfACookRebuildsTheIdenticalSessionAndFrozenStories() throws {
        let owner = "feedfeed-feed-4eed-8eed-feedfeedfeed"
        let receipt = NativePublicationReceipt(id: owner, revisionId: "eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee", revision: 3, url: "https://pittech.app/c/" + owner)
        let prepared = try NativePublicationProjection.build(try document(), updated: 1, photos: [], kind: "cook")
        let draft = try NativeOwnedPublication.draft(prepared, receipt: receipt)
        XCTAssertEqual(draft.document.sessionJson?.objectValue?["startedAtUtcMillis"]?.numberValue, 946_684_800_000)
        let rebuilt = try NativePublicationProjection.build(draft.document, updated: 2, photos: [], kind: "cook")
        XCTAssertEqual(rebuilt.payload.objectValue?["session"], prepared.payload.objectValue?["session"])
        XCTAssertEqual(rebuilt.payload.objectValue?["kind"]?.stringValue, "cook")
        XCTAssertEqual(rebuilt.payload.objectValue?["measuredAttemptId"], .null)
        let before = try XCTUnwrap(prepared.payload.objectValue?["recipes"]?.arrayValue)
        let after = try XCTUnwrap(rebuilt.payload.objectValue?["recipes"]?.arrayValue)
        XCTAssertEqual(after.count, before.count)
        for index in before.indices {
            XCTAssertEqual(after[index].objectValue?["story"], before[index].objectValue?["story"], "frozen stories pass through owner updates untouched")
        }
    }
}
