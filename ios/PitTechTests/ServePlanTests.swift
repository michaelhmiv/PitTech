import XCTest
@testable import PitTech

/// Slice 4/5 serve-plan domain: the wire codec (byte-compatible with
/// Android's ServeTimePlanner), the DST-aware serving goal, the derived
/// windows, the recorded-range matcher, and the companion-record store.
@MainActor final class ServePlanTests: XCTestCase {
    private let serveAt: Int64 = 1_800_000_000_000 // whole minute

    private func plan(name: String = "Saturday pork", serveAt: Int64? = nil, buffer: Int64 = 60) -> ServePlan {
        let dish = PlaybookDishDraft(name: "Pork shoulder", foodType: "Pork", cut: "Shoulder", weightText: "8.5", weightUnit: "lb")
        let book = CookPlaybook(
            name: name,
            draft: PlaybookCookDraft(title: name, smokerName: "Kettle", setpointText: "250", dishes: [dish]),
            steps: [PlaybookStep(id: "step-1", dishIndex: 0, action: "wrap", title: "Wrap", trigger: "elapsed", minutes: 180)])
        return ServePlan(book: book, serveAtUtcMillis: serveAt ?? self.serveAt, zoneId: "America/New_York", bufferMinutes: buffer,
                         schedules: [DishSchedule(dishIndex: 0, cookMinMinutes: 400, cookMaxMinutes: 460, pitTemperatureF: 250)])
    }

    func testCodecRoundTripsThroughTheAndroidWireShape() throws {
        let value = plan()
        let encoded = try ServeTimePlanner.encode(value)
        let object = try XCTUnwrap(encoded.objectValue)
        XCTAssertEqual(Set(object.keys), ["version", "book", "serveAt", "zoneId", "buffer", "schedules"])
        XCTAssertEqual(object["version"]?.numberValue, 1)
        XCTAssertEqual(try ServeTimePlanner.decode(encoded), value)
        XCTAssertEqual(try ServeTimePlanner.decodeString(ServeTimePlanner.encodeString(value)), value)
        // A started plan carries its cook on the wire.
        var started = value; started.startedCookId = "cook-1"
        XCTAssertEqual(try ServeTimePlanner.decode(ServeTimePlanner.encode(started)), started)
    }

    func testDecodeToleratesAndroidOmissionsAndDefaults() throws {
        // org.json drops null values, so Android's encoder omits them; the
        // decoder fills the same defaults on both platforms.
        let text = """
        {"version":1,"book":{"version":1,"name":"Ribs","draft":{"title":"Ribs","dishes":[{"name":"Spare ribs","foodType":"Pork"}]}},
         "serveAt":1800000000000,"zoneId":"America/New_York",
         "schedules":[{"dish":0,"min":240,"max":300}]}
        """
        let decoded = try ServeTimePlanner.decodeString(text)
        XCTAssertEqual(decoded.bufferMinutes, 60)
        XCTAssertEqual(decoded.book.draft.temperatureUnit, "°F")
        XCTAssertEqual(decoded.book.draft.dishes[0].weightUnit, "lb")
        let schedule = try XCTUnwrap(decoded.schedules.first)
        XCTAssertEqual(schedule.prepMinutes, 30)
        XCTAssertEqual(schedule.preheatMinutes, 30)
        XCTAssertEqual(schedule.restMinutes, 60)
        XCTAssertEqual(schedule.holdMinutes, 0)
        XCTAssertNil(schedule.pitTemperatureF)
        XCTAssertEqual(schedule.evidenceNote, "Your duration range")
    }

    func testValidationMessagesMatchAndroid() {
        // Android's ServeTimePlanner / PlaybookCodec messages, verbatim.
        XCTAssertThrowsError(try ServeTimePlanner.validate(plan(name: "  "))) {
            XCTAssertEqual($0.localizedDescription, "Give this playbook a name.")
        }
        var mismatched = plan(); mismatched.schedules = []
        XCTAssertThrowsError(try ServeTimePlanner.validate(mismatched)) {
            XCTAssertEqual($0.localizedDescription, "Schedule each dish once and use a valid buffer.")
        }
        XCTAssertThrowsError(try ServeTimePlanner.validate(plan(buffer: 1_441))) {
            XCTAssertEqual($0.localizedDescription, "Schedule each dish once and use a valid buffer.")
        }
        var zeroMin = plan(); zeroMin.schedules[0].cookMinMinutes = 0
        XCTAssertThrowsError(try ServeTimePlanner.validate(zeroMin)) {
            XCTAssertEqual($0.localizedDescription, "Check each dish's cooking and preparation durations.")
        }
        var noDishes = plan()
        noDishes.book.draft.dishes = []; noDishes.book.steps = []; noDishes.schedules = []
        XCTAssertThrowsError(try ServeTimePlanner.validate(noDishes)) {
            XCTAssertEqual($0.localizedDescription, "Add a dish to the serving plan.")
        }
    }

    func testLocalGoalRejectsTheSpringForwardGap() {
        XCTAssertThrowsError(try ServeTimePlanner.localGoal(date: "2026-03-08", time: "02:30", zoneId: "America/New_York")) {
            XCTAssertEqual($0.localizedDescription, "This local time is skipped by daylight saving. Choose another time.")
        }
        XCTAssertFalse(ServeTimePlanner.occursTwice(date: "2026-03-08", time: "02:30", zoneId: "America/New_York"))
    }

    func testLocalGoalFoldResolvesEarlierUnlessLaterRequested() throws {
        var utc = Calendar(identifier: .gregorian); utc.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
        func millis(_ hour: Int) -> Int64 {
            let date = utc.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: hour, minute: 30))!
            return Int64(date.timeIntervalSince1970 * 1000)
        }
        // 2026-11-01 01:30 in New York happens at 05:30Z (EDT) and 06:30Z (EST).
        XCTAssertTrue(ServeTimePlanner.occursTwice(date: "2026-11-01", time: "01:30", zoneId: "America/New_York"))
        XCTAssertEqual(try ServeTimePlanner.localGoal(date: "2026-11-01", time: "01:30", zoneId: "America/New_York"), millis(5))
        XCTAssertEqual(try ServeTimePlanner.localGoal(date: "2026-11-01", time: "01:30", zoneId: "America/New_York", laterOffset: true), millis(6))
        XCTAssertFalse(ServeTimePlanner.occursTwice(date: "2026-11-02", time: "01:30", zoneId: "America/New_York"))
    }

    func testWindowsDeriveFromTheServeTime() {
        let value = plan(), schedule = value.schedules[0]
        let minute: Int64 = 60_000
        // foodOn = serveAt − (max 460 + rest 60 + hold 0 + buffer 60)
        XCTAssertEqual(ServeTimePlanner.foodOn(plan: value, schedule: schedule), serveAt - 580 * minute)
        let ready = ServeTimePlanner.readyWindow(plan: value, schedule: schedule)
        XCTAssertEqual(ready.earliest, serveAt - 580 * minute + (400 + 60) * minute)
        XCTAssertEqual(ready.latest, serveAt - 60 * minute)
    }

    func testUpdatedReadyWindowPrefersTheActualRemove() {
        let value = plan(), schedule = value.schedules[0], minute: Int64 = 60_000
        let remove = serveAt - 700 * minute
        // Off the pit, the dish is ready at remove + rest: a point.
        let updated = ServeTimePlanner.updatedReadyWindow(plan: value, schedule: schedule, events: [PlanEvent(action: "remove", dishId: nil, atUtcMillis: remove)])
        XCTAssertEqual(updated.earliest, remove + 60 * minute)
        XCTAssertEqual(updated.latest, remove + 60 * minute)
        // Without a remove, the planned food-on anchors the window.
        let planned = ServeTimePlanner.updatedReadyWindow(plan: value, schedule: schedule, events: [])
        XCTAssertEqual(planned.earliest, ServeTimePlanner.foodOn(plan: value, schedule: schedule) + (400 + 60) * minute)
        XCTAssertEqual(planned.latest, ServeTimePlanner.foodOn(plan: value, schedule: schedule) + (460 + 60) * minute)
    }

    func testWeightKgConversions() {
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(8.5, "lb")), 8.5 * 0.45359237, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(16, "oz")), 0.45359237, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(500, "g")), 0.5, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(2, "kg")), 2, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(0, "lb")), 0, accuracy: 1e-9)
        XCTAssertNil(ServeTimePlanner.weightKg(1, "stone"))
        XCTAssertNil(ServeTimePlanner.weightKg(1, "LB"))
        XCTAssertNil(ServeTimePlanner.weightKg(nil, "lb"))
    }

    func testComparableIsAStrictConjunctionWithOneRowPerCook() {
        let dish = PlaybookDishDraft(name: "Pork shoulder", foodType: "Pork", cut: "Shoulder", weightText: "8.5", weightUnit: "lb")
        let draft = PlaybookCookDraft(title: "Saturday pork", smokerName: "Kettle", setpointText: "250", dishes: [dish])
        func evidence(_ cookId: String, cut: String = "Shoulder", foodType: String = "Pork", smoker: String? = "Kettle", kg: Double? = 3.9, pit: Double? = 250, wrapped: Bool = true, minutes: Int64 = 460) -> DurationEvidence {
            DurationEvidence(dishName: "Dish", cut: cut, foodType: foodType, smokerName: smoker, weightKg: kg, startingCondition: nil, boneIn: nil, setpointF: pit, durationMinutes: minutes, cookId: cookId, wrapped: wrapped, cookedAtUtcMillis: 0)
        }
        let history = [
            evidence("c1"), evidence("c2", cut: "shoulder", minutes: 500),
            evidence("c3", cut: "Butt"), evidence("c4", smoker: "Offset"),
            evidence("c5", kg: 6.0), evidence("c6", pit: nil),
            evidence("c7", wrapped: false), evidence("c1", minutes: 999),
        ]
        let matches = ServeTimePlanner.comparable(dish: dish, draft: draft, evidence: history, wrapped: true)
        XCTAssertEqual(matches.map(\.cookId), ["c1", "c2"])
        XCTAssertEqual(matches.map(\.durationMinutes), [460, 500])
        // Both sides must record the setpoint: a draft with no setpoint
        // matches only evidence with none.
        var noPitDraft = draft; noPitDraft.setpointText = ""
        let noPit = ServeTimePlanner.comparable(dish: dish, draft: noPitDraft, evidence: history, wrapped: true)
        XCTAssertEqual(noPit.map(\.cookId), ["c6"])
        // A dish with no recorded cut has no comparable history at all.
        var noCut = dish; noCut.cut = ""
        XCTAssertTrue(ServeTimePlanner.comparable(dish: noCut, draft: draft, evidence: history, wrapped: true).isEmpty)
    }

    func testStoreRoundTripAttachAndDerivedAlerts() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = CookStore(directory: root)
        var future = plan(); future.serveAtUtcMillis = Int64(Date.now.timeIntervalSince1970 * 1000) / 60_000 * 60_000 + 2 * 86_400_000
        let id = try store.saveServePlan(future, id: nil)
        XCTAssertEqual(store.upcomingServePlans().map(\.id), [id])
        XCTAssertEqual(store.servePlan(id), future)
        // A past serve time is refused with Android's copy.
        var past = plan(); past.serveAtUtcMillis = 60_000
        XCTAssertThrowsError(try store.saveServePlan(past, id: nil)) {
            XCTAssertEqual($0.localizedDescription, "Choose a future serving time.")
        }
        store.startCook(title: "Saturday pork", firstDish: "Pork shoulder")
        let cookId = try XCTUnwrap(store.activeCooks.first?.id)
        try store.attachServePlan(id, cookId: cookId, orderedDishIds: store.dishRecords(cookId).map(\.id))
        XCTAssertEqual(store.serveGoal(cookId: cookId)?.startedCookId, cookId)
        XCTAssertTrue(store.upcomingServePlans().isEmpty, "A started plan leaves the upcoming list.")
        let armed = store.library.reminderRules.compactMap { $0.objectValue?["id"]?.stringValue }.filter { $0.hasPrefix("plan:\(cookId):") }
        XCTAssertEqual(Set(armed), ["plan:\(cookId):0:food_on", "plan:\(cookId):0:wrap", "plan:\(cookId):0:off"])
        try store.deleteServePlan(id)
        XCTAssertNil(store.servePlan(id))
    }
}
