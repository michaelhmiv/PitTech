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
        XCTAssertThrowsError(try ServeTimePlanner.validate(plan(name: "  "))) {
            XCTAssertEqual($0.localizedDescription, "Name the cook before planning it.")
        }
        XCTAssertThrowsError(try ServeTimePlanner.validate(plan(serveAt: serveAt + 1))) {
            XCTAssertEqual($0.localizedDescription, "Serving time must be a whole minute.")
        }
        var mismatched = plan(); mismatched.schedules = []
        XCTAssertThrowsError(try ServeTimePlanner.validate(mismatched)) {
            XCTAssertEqual($0.localizedDescription, "Every dish needs exactly one duration schedule.")
        }
    }

    func testLocalGoalRejectsTheSpringForwardGap() {
        XCTAssertThrowsError(try ServeTimePlanner.localGoal(date: "2026-03-08", time: "02:30", zoneId: "America/New_York")) {
            XCTAssertEqual($0.localizedDescription, "Local serving time does not occur in America/New_York")
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
        XCTAssertEqual(ready.earliest, serveAt - 580 * minute + 400 * minute)
        XCTAssertEqual(ready.latest, serveAt - 60 * minute)
    }

    func testUpdatedReadyWindowPrefersTheActualRemove() {
        let value = plan(), schedule = value.schedules[0], minute: Int64 = 60_000
        let remove = serveAt - 700 * minute
        let updated = ServeTimePlanner.updatedReadyWindow(plan: value, schedule: schedule, events: [PlanEvent(action: "remove", dishId: nil, atUtcMillis: remove)])
        XCTAssertEqual(updated.earliest, remove + (400 + 60) * minute)
        XCTAssertEqual(updated.latest, remove + (460 + 60) * minute)
        // Without a remove, the planned food-on anchors the window.
        let planned = ServeTimePlanner.updatedReadyWindow(plan: value, schedule: schedule, events: [])
        XCTAssertEqual(planned.earliest, ServeTimePlanner.foodOn(plan: value, schedule: schedule) + (400 + 60) * minute)
    }

    func testWeightKgConversions() {
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(8.5, "lb")), 8.5 * 0.45359237, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(16, "oz")), 0.45359237, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(500, "g")), 0.5, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(ServeTimePlanner.weightKg(2, "kg")), 2, accuracy: 1e-9)
        XCTAssertNil(ServeTimePlanner.weightKg(1, "stone"))
        XCTAssertNil(ServeTimePlanner.weightKg(0, "lb"))
        XCTAssertNil(ServeTimePlanner.weightKg(nil, "lb"))
    }

    func testComparableRequiresFoodTypeAndStaysInTheWrapClass() {
        let dish = PlaybookDishDraft(name: "Pork shoulder", foodType: "Pork", cut: "Shoulder", weightText: "8.5", weightUnit: "lb")
        let draft = PlaybookCookDraft(title: "Saturday pork", smokerName: "Kettle", setpointText: "250", dishes: [dish])
        func evidence(_ foodType: String, _ wrapped: Bool, _ minutes: Int64, _ at: Int64) -> DurationEvidence {
            DurationEvidence(dishName: "Dish", cut: "Shoulder", foodType: foodType, smokerName: "Kettle", weightKg: 3.86, startingCondition: nil, boneIn: nil, setpointF: 250, durationMinutes: minutes, cookId: "cook-\(at)", wrapped: wrapped, cookedAtUtcMillis: at)
        }
        let history = [evidence("Pork", true, 460, 300), evidence("Pork", true, 500, 200), evidence("Beef", true, 300, 400), evidence("Pork", false, 420, 500)]
        let wrapped = ServeTimePlanner.comparable(dish: dish, draft: draft, evidence: history, wrapped: true)
        XCTAssertEqual(wrapped?.sampleCount, 2)
        XCTAssertEqual(wrapped?.minDurationMinutes, 460)
        XCTAssertEqual(wrapped?.maxDurationMinutes, 500)
        let unwrapped = ServeTimePlanner.comparable(dish: dish, draft: draft, evidence: history, wrapped: false)
        XCTAssertEqual(unwrapped?.sampleCount, 1)
        XCTAssertEqual(unwrapped?.minDurationMinutes, 420)
        var fish = dish; fish.foodType = "Fish"
        XCTAssertNil(ServeTimePlanner.comparable(dish: fish, draft: draft, evidence: history, wrapped: true))
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
