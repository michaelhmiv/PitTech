import XCTest
@testable import PitTech

final class ActivityTimerTests: XCTestCase {
    @MainActor func testTimerAnchorsPersistAndSaveOneMeasuredNote() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let original = CookStore(directory: directory)
        original.startCook(title: "Mac", firstDish: "Mac")
        let cook = original.library.cooks[0].id
        let dish = original.dishRecords(cook)[0].id
        try original.startActivityTimer(cookId: cook, dishId: dish, title: "Boiled macaroni", method: "stovetop", target: 480000)
        try original.startActivityTimer(cookId: cook, dishId: dish, title: "Sauce", method: "stovetop", target: nil)
        let store = CookStore(directory: directory)
        XCTAssertEqual(store.library.activityTimers.count, 2)
        let timer = store.library.activityTimers[0].objectValue!
        let start = timer["startedAtUtcMillis"]!.numberValue!
        let draft = try store.stopActivityTimer(timer["id"]!.stringValue!, at: start + 480000)
        XCTAssertEqual(draft.metadata.durationMillis, 480000)
        try store.saveNote(draft)
        try store.saveNote(draft)
        XCTAssertEqual(store.library.events.filter { $0.objectValue?["id"]?.stringValue == draft.id }.count, 1)
        XCTAssertEqual(store.library.activityTimers[0].objectValue?["status"]?.stringValue, "logged")
        let loaded = try store.loadNote(draft.id)
        XCTAssertEqual(loaded.metadata.durationMillis, 480000)
        XCTAssertEqual(loaded.metadata.method, "stovetop")
    }
}
