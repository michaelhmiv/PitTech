import XCTest
@testable import PitTech

/// Mirrors Android's ProvenanceArchiveSeamTest (Flowing Slice 3): the
/// archive→provenance seam proven against the REAL storage vocabulary.
///
/// The fixture below is a full library archive whose rows are built
/// key-for-key from the writers' own serialization shapes: cook rows as
/// `CookRecord` encodes them, note events exactly as `CookStore.saveNote`
/// writes them (`eventType: "note"`, the action and dish scopes inside
/// `noteMetadataJson` produced by `CookNoteMetadata.json` itself), one
/// legacy event row in the imported shape (`eventType: "meat_on"`, a
/// bare `dishId`, no metadata), readings and photos in the note writer's
/// row shape, results in the results writer's field set. Slice 1's
/// parser instead reads the insights vocabulary (`action`, `at`,
/// `dishIds`) — feeding it the raw archive must yield NO payload (the
/// control), and the transfer-layer bridge
/// (`PitTechLibrary.provenanceReadyArchive`, the production insights
/// adapter's own resolution) must carry the whole story through.
final class ProvenanceArchiveSeamTests: XCTestCase {
    private let start = Int64(1_791_799_500_000) // Mon Oct 12, 2026 10:05 UTC = 6:05 AM EDT
    private func at(_ minute: Int64) -> Int64 { start + minute * 60_000 }

    /// A note event row in exactly the shape `CookStore.saveNote` writes.
    private func noteEvent(_ id: String, _ action: String, _ minute: Int64) -> [String: Any] {
        [
            "id": id, "cookId": "c1",
            "recordedAtUtcMillis": at(minute), "createdAtUtcMillis": at(minute),
            "timeZoneId": "America/New_York", "source": "manual",
            "eventType": "note", "title": action, "details": "",
            "occurredAtUtcMillis": at(minute), "updatedAtUtcMillis": at(minute),
            "dishId": "d1",
            "noteMetadataJson": CookNoteMetadata(category: "action", action: action, dishIds: ["d1"]).json,
        ]
    }

    private func libraryData() throws -> Data {
        var root: [String: Any] = [
            "schemaVersion": PitTechLibrary.archiveVersion,
            "cooks": [[
                "id": "c1", "title": "Boston Butt", "status": "completed",
                "startedAtUtcMillis": start, "startedTimeZoneId": "America/New_York",
                "endedAtUtcMillis": at(655),
                "smokerName": "Offset smoker", "initialSetpointValue": 250, "initialSetpointUnit": "°F",
                "notes": NSNull(), "createdAtUtcMillis": start, "updatedAtUtcMillis": at(655),
                "fuelType": "Charcoal", "woodOrPelletBlend": "Hickory",
                "outdoorTemperatureValue": NSNull(), "outdoorTemperatureUnit": NSNull(),
                "weatherNotes": NSNull(), "windNotes": NSNull(),
            ]],
            "dishes": [[
                "id": "d1", "cookId": "c1", "name": "Boston Butt", "foodType": "Pork",
                "cut": "Boston butt", "weightValue": 9.4, "weightUnit": "lb",
            ]],
            "ingredients": [], "targets": [], "devices": [], "probes": [],
            "reminders": [], "recordings": [], "probeAssignments": [],
            "companionRecords": [], "companionPhotos": [], "logReadingLinks": [],
            "targetHistory": [], "recipes": [], "recipeRevisions": [], "recipeAssets": [],
            "dishRecipeSources": [], "mealPresets": [], "activityTimers": [],
            "reminderRules": [], "reminderOccurrences": [],
            "lifecycles": [["cookId": "c1", "timingMode": "explicit_phases"]],
            "events": [], "readings": [], "results": [], "photos": [],
        ]
        // The legacy imported shape: the action lives in `eventType`
        // under its legacy name, the scope in the row's own `dishId`,
        // and there is no metadata at all. The adapter's mapping
        // (meat_on → food_on) is the only thing that resolves it.
        let legacyFoodOn: [String: Any] = [
            "id": "e1", "cookId": "c1", "dishId": "d1", "eventType": "meat_on",
            "title": "Meat on", "details": "",
            "occurredAtUtcMillis": at(0), "recordedAtUtcMillis": at(0),
            "timeZoneId": "America/New_York", "source": "manual",
            "createdAtUtcMillis": at(0), "updatedAtUtcMillis": at(0),
        ]
        root["events"] = [legacyFoodOn,
                          noteEvent("e2", "wrap", 195), noteEvent("e3", "remove", 580),
                          noteEvent("e4", "rest_start", 580), noteEvent("e5", "dish_done", 655)]
        var readings: [[String: Any]] = []
        var minute: Int64 = 0
        while minute <= 580 {
            let value: Double
            switch minute {
            case ...120: value = 40.0 + Double(minute)
            case ...210: value = 160.0
            case ...296: value = 160.0 + Double(minute - 210) * 0.5
            default: value = 203.0
            }
            readings.append([
                "id": "r\(minute)", "cookId": "c1", "dishId": "d1", "probeId": "p1", "probeName": "Probe 1",
                "measurementType": "food_probe", "value": value, "unit": "°F",
                "measuredAtUtcMillis": at(minute), "recordedAtUtcMillis": at(minute),
                "timeZoneId": "America/New_York", "source": "manual", "qualityStatus": "valid",
                "timestampBasis": "measurement", "samplingIntervalMillis": 0,
            ])
            minute += 10
        }
        root["readings"] = readings
        func result(_ id: String, _ type: String, _ numeric: Double?, _ text: String?) -> [String: Any] {
            ["id": id, "cookId": "c1", "dishId": "d1", "resultType": type,
             "numericValue": numeric ?? NSNull(), "unit": NSNull(), "textValue": text ?? NSNull(),
             "notes": NSNull(), "recordedAtUtcMillis": at(655)]
        }
        root["results"] = [result("x1", "overall_rating", 4.0, nil), result("x2", "cook_again", 1.0, nil),
                           result("x3", "keep_doing", nil, "Hickory"), result("x4", "change_next_time", nil, "Wrap earlier"),
                           result("x5", "result_notes", nil, "Bark set by hour 5.")]
        func photo(_ id: String, _ eventId: String?, _ minute: Int64) -> [String: Any] {
            ["id": id, "cookId": "c1", "originalFileName": "\(id).jpg", "mimeType": "image/jpeg",
             "archivePath": "photos/\(id).jpg", "addedAtUtcMillis": at(minute),
             "eventId": eventId ?? NSNull(), "caption": "", "dishId": "d1",
             "capturedAtUtcMillis": at(minute)]
        }
        root["photos"] = [photo("p1", "e1", 5), photo("p2", "e2", 200), photo("p3", "e3", 585), photo("p4", nil, 300)]
        return try JSONSerialization.data(withJSONObject: root)
    }

    /// The control: Slice 1's parser contract is unchanged, so the raw
    /// storage-vocabulary archive still yields no payload. If this ever
    /// starts passing a payload, the parser grew a second vocabulary —
    /// the bridge is the one sanctioned crossing.
    func testUnbridgedStorageVocabularyYieldsNoPayload() throws {
        let data = try libraryData()
        XCTAssertNil(RecipeProvenance.fromArchive(data: data, sourceCookId: "c1", sourceDishId: "d1"),
                     "storage-vocabulary events must not decode as insights events")
    }

    func testBridgeResolvesEventsThroughTheProductionAdapter() throws {
        let library = try JSONDecoder().decode(PitTechLibrary.self, from: libraryData())
        let bridged = library.provenanceReadyArchive()
        let events = (bridged.objectValue?["events"]?.arrayValue ?? []).compactMap(\.objectValue)
        XCTAssertEqual(events.count, 5)
        let foodOn = try XCTUnwrap(events.first { $0["id"]?.stringValue == "e1" })
        XCTAssertEqual(foodOn["action"]?.stringValue, "food_on", "the legacy eventType resolves through the adapter mapping")
        XCTAssertEqual(foodOn["at"]?.numberValue, Double(at(0)))
        XCTAssertEqual(foodOn["dishIds"]?.arrayValue?.compactMap(\.stringValue), ["d1"], "the row's dishId becomes the scope")
        let wrap = try XCTUnwrap(events.first { $0["id"]?.stringValue == "e2" })
        XCTAssertEqual(wrap["action"]?.stringValue, "wrap", "the metadata action wins over eventType")
        XCTAssertEqual(wrap["at"]?.numberValue, Double(at(195)))
        XCTAssertEqual(wrap["dishIds"]?.arrayValue?.compactMap(\.stringValue), ["d1"], "the metadata dish scope wins over the row's dishId")
    }

    func testBridgedArchiveCarriesTheWholeStory() throws {
        let library = try JSONDecoder().decode(PitTechLibrary.self, from: libraryData())
        let data = try JSONEncoder().encode(library.provenanceReadyArchive())
        let payload = try XCTUnwrap(RecipeProvenance.fromArchive(data: data, sourceCookId: "c1", sourceDishId: "d1"),
                                    "the bridged archive must carry the story the raw archive could not")
        XCTAssertEqual(payload.cookedDateText, "October 12, 2026")
        XCTAssertEqual(payload.pitName, "Offset smoker")
        XCTAssertEqual(payload.setpointF, 250)
        XCTAssertEqual(payload.wood, "Hickory")
        XCTAssertEqual(payload.weightText, "9.4 lb")
        XCTAssertEqual(payload.totalMinutes, 655)
        XCTAssertTrue((60.0...120.0).contains(payload.stallMinutes!), "a real stall is measured, not implied")
        XCTAssertEqual(payload.wrappedAtF!, 160, accuracy: 0.5)
        XCTAssertEqual(payload.restMinutes, 75)
        XCTAssertEqual(payload.rating, 4.0)
        XCTAssertEqual(payload.cookAgain, true)
        XCTAssertEqual(payload.note, "Bark set by hour 5.")
        XCTAssertEqual(payload.keepChips, ["Hickory"])
        XCTAssertEqual(payload.changeChips, ["Wrap earlier"])
        XCTAssertEqual(payload.photos.map { $0.role }, [.onPit, .atWrap, .sliced, .other],
                       "photo roles resolve through the bridged event actions")
        XCTAssertFalse(payload.curve.isEmpty)
        XCTAssertEqual(payload.curve.first?.offsetMinutes, 0)
        XCTAssertEqual(payload.curve.last?.fahrenheit, 203)
    }
}
