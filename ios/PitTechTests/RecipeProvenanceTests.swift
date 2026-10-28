import XCTest
@testable import PitTech

/// Mirrors Android's RecipeProvenanceTest: the payload assembled from a
/// synthetic archive in the Boston-butt shape.
final class RecipeProvenanceTests: XCTestCase {
    private let start = Int64(1_791_799_500_000) // Mon Oct 12, 2026 10:05 UTC = 6:05 AM EDT
    private func at(_ minute: Int64) -> Int64 { start + minute * 60_000 }

    private func archiveData(withStory: Bool = true) throws -> Data {
        var root: [String: Any] = [
            "cooks": [[
                "id": "c1", "title": "Boston Butt", "status": "completed",
                "startedAtUtcMillis": start, "endedAtUtcMillis": at(655),
                "startedTimeZoneId": "America/New_York",
                "smokerName": "Offset smoker", "initialSetpointValue": 250, "initialSetpointUnit": "°F",
                "woodOrPelletBlend": "Hickory", "fuelType": "Charcoal",
            ]],
            "dishes": [[
                "id": "d1", "cookId": "c1", "name": "Boston Butt", "foodType": "Pork",
                "cut": "Boston butt", "weightValue": 9.4, "weightUnit": "lb",
            ]],
            "lifecycles": [["cookId": "c1", "timingMode": "explicit_phases"]],
            "events": [], "readings": [], "results": [], "photos": [],
        ]
        guard withStory else { return try JSONSerialization.data(withJSONObject: root) }
        func event(_ id: String, _ action: String, _ minute: Int64) -> [String: Any] {
            ["id": id, "cookId": "c1", "dishIds": ["d1"], "action": action, "at": at(minute)]
        }
        root["events"] = [event("e1", "food_on", 0), event("e2", "wrap", 195), event("e3", "remove", 580),
                          event("e4", "rest_start", 580), event("e5", "dish_done", 655)]
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
                "measuredAtUtcMillis": at(minute), "source": "manual", "qualityStatus": "valid",
            ])
            minute += 10
        }
        root["readings"] = readings
        func result(_ id: String, _ type: String, _ numeric: Double?, _ text: String?) -> [String: Any] {
            ["id": id, "cookId": "c1", "dishId": "d1", "resultType": type,
             "numericValue": numeric ?? NSNull(), "textValue": text ?? NSNull(), "recordedAtUtcMillis": at(655)]
        }
        root["results"] = [result("x1", "overall_rating", 4.0, nil), result("x2", "cook_again", 1.0, nil),
                           result("x3", "keep_doing", nil, "Hickory"), result("x4", "change_next_time", nil, "Wrap earlier"),
                           result("x5", "result_notes", nil, "Bark set by hour 5.")]
        func photo(_ id: String, _ eventId: String?, _ minute: Int64) -> [String: Any] {
            ["id": id, "cookId": "c1", "dishId": "d1", "eventId": eventId ?? NSNull(), "caption": NSNull(),
             "capturedAtUtcMillis": at(minute), "addedAtUtcMillis": at(minute)]
        }
        root["photos"] = [photo("p1", "e1", 5), photo("p2", "e2", 200), photo("p3", "e3", 585), photo("p4", nil, 300)]
        return try JSONSerialization.data(withJSONObject: root)
    }

    func testPayloadCarriesTheWholeStory() throws {
        let payload = try XCTUnwrap(RecipeProvenance.fromArchive(data: archiveData(), sourceCookId: "c1", sourceDishId: "d1"))
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
        XCTAssertEqual(payload.photos.map { $0.role }, [.onPit, .atWrap, .sliced, .other])
        XCTAssertFalse(payload.curve.isEmpty)
        XCTAssertLessThanOrEqual(payload.curve.count, RecipeProvenance.maxCurvePoints)
        XCTAssertEqual(payload.curve.first?.offsetMinutes, 0)
        XCTAssertEqual(payload.curve.last?.fahrenheit, 203)
    }

    func testNoSourceCookMeansNoPayload() throws {
        let data = try archiveData()
        XCTAssertNil(RecipeProvenance.fromArchive(data: data, sourceCookId: nil, sourceDishId: "d1"))
        XCTAssertNil(RecipeProvenance.fromArchive(data: data, sourceCookId: "missing-cook", sourceDishId: "d1"))
        XCTAssertNil(RecipeProvenance.fromArchive(data: data, sourceCookId: "c1", sourceDishId: nil))
        XCTAssertNil(RecipeProvenance.fromArchive(data: data, sourceCookId: "c1", sourceDishId: "missing-dish"))
    }

    func testACookWithoutAStoryYieldsHonestAbsencesNotFabrications() throws {
        let payload = try XCTUnwrap(RecipeProvenance.fromArchive(data: archiveData(withStory: false), sourceCookId: "c1", sourceDishId: "d1"))
        XCTAssertEqual(payload.cookedDateText, "October 12, 2026")
        XCTAssertNil(payload.totalMinutes)
        XCTAssertNil(payload.stallMinutes)
        XCTAssertNil(payload.wrappedAtF)
        XCTAssertNil(payload.rating)
        XCTAssertNil(payload.note)
        XCTAssertTrue(payload.curve.isEmpty())
        XCTAssertTrue(payload.photos.isEmpty())
        XCTAssertTrue(payload.keepChips.isEmpty() && payload.changeChips.isEmpty())
    }
}
