import XCTest
@testable import PitTech
final class UnifiedReminderTests: XCTestCase {
    func testHistoricalAndSpecificActionRulesUseActualNewAttemptTimestamps() throws {
        let raw = "{\"version\":1,\"type\":\"historical\",\"anchorAction\":\"food_on\",\"offsetMillis\":12000000}"
        let config = try NativeReminderEngine.configuration(raw)
        XCTAssertNil(NativeReminderEngine.due(config, dishId: "mac", actions: []))
        let actual = CookAction(id: "current", action: "food_on", dishIds: ["mac"], at: 5000)
        XCTAssertEqual(NativeReminderEngine.due(config, dishId: "mac", actions: [.init(id: "other", action: "food_on", dishIds: ["pork"], at: 100), actual]), 12005000)
        XCTAssertEqual(NativeReminderEngine.due(config, dishId: "mac", actions: [.init(id: actual.id, action: actual.action, dishIds: actual.dishIds, at: 8000)]), 12008000)
        XCTAssertThrowsError(try NativeReminderEngine.configuration(raw.replacingOccurrences(of: "12000000", with: "1.5")))
    }
    @MainActor func testDurableSchedulesSkipAndExplicitConfirmationRestoreInactive() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let original = CookStore(directory: folder); original.startCook(title: "Mac", firstDish: "Mac")
        let cook = original.library.cooks[0].id, dish = original.dishRecords(cook)[0].id
        let start = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        let config: [String: JSONValue] = ["version": .number(1), "type": .string("duration"), "startedAt": .number(start), "delayMillis": .number(600000)]
        try original.saveReminderRule(cookId: cook, dishId: dish, title: "Stir", config: config, id: "stir")
        let store = CookStore(directory: folder)
        XCTAssertEqual(store.reminderState("stir")?["dueAtUtcMillis"]?.numberValue, start + 600000)
        XCTAssertEqual(store.library.reminderOccurrences.filter { ($0.objectValue?["sequence"]?.numberValue ?? 0) > 0 }.count, 1)
        try store.changeReminder("stir", action: "skip")
        XCTAssertFalse(store.library.actions(for: cook).contains { $0.action == "food_on" })
        try store.changeReminder("stir", action: "toggle")
        let id = try store.confirmReminderAction("stir", action: "food_on")
        XCTAssertEqual(try store.confirmReminderAction("stir", action: "food_on"), id)
        XCTAssertEqual(store.library.events.filter { $0.objectValue?["id"]?.stringValue == id }.count, 1)
        let destination = CookStore(directory: folder.appendingPathComponent("restored"))
        let bytes = try store.exportArchive(); _ = try destination.importArchive(bytes); _ = try destination.importArchive(bytes)
        XCTAssertEqual(destination.library.reminderRules.count, 1)
        XCTAssertEqual(destination.reminderRule("stir")?["enabled"]?.boolValue, false)
        XCTAssertEqual(destination.reminderRule("stir")?["status"]?.stringValue, "inactive")
        try destination.library.validateReminderRules()
    }
    @MainActor func testAnnouncedActionComesFromTheMilestoneAndDishActionsRecordInOneTap() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        let anchor = Double(Int64(Date.now.timeIntervalSince1970 * 1000)) - 14_400_000
        store.startCook(title: "Source", firstDish: "Brisket")
        let sourceCook = store.library.cooks[0].id, sourceDish = store.dishRecords(sourceCook)[0].id
        try store.saveNote(CookNoteDraft(cookId: sourceCook, dishId: sourceDish, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: anchor))
        try store.saveNote(CookNoteDraft(id: "source-wrap", cookId: sourceCook, dishId: sourceDish, metadata: CookNoteMetadata(category: "action", action: "wrap"), title: "Wrap", occurredAt: anchor + 10_800_000))
        store.finishCook(sourceCook)
        let recipe = try store.saveRecipe(store.extractRecipe(cookId: sourceCook, dishId: sourceDish), sourceCookId: sourceCook, sourceDishId: sourceDish)
        let revision = try XCTUnwrap(store.recipe(recipe)?["currentRevisionId"]?.stringValue)
        store.startCook(title: "Attempt", firstDish: "Brisket")
        let cook = store.activeCooks[0].id, dish = store.dishRecords(cook)[0].id
        // PL-01: a milestone rule announces its milestone's action; a plain
        // rule announces nothing, so nothing can be blanket-preselected.
        let historical: [String: JSONValue] = ["version": .number(1), "type": .string("historical"), "anchorAction": .string("food_on"), "offsetMillis": .number(10_800_000)]
        try store.saveReminderRule(cookId: cook, dishId: dish, title: "Last time: Wrap", config: historical, id: "milestone", revisionId: revision, milestoneId: "source-wrap")
        XCTAssertEqual(store.announcedReminderAction(try XCTUnwrap(store.reminderRule("milestone"))), "wrap")
        let plain: [String: JSONValue] = ["version": .number(1), "type": .string("duration"), "startedAt": .number(Double(Int64(Date.now.timeIntervalSince1970 * 1000))), "delayMillis": .number(600_000)]
        try store.saveReminderRule(cookId: cook, dishId: dish, title: "Check", config: plain, id: "plain")
        XCTAssertNil(store.announcedReminderAction(try XCTUnwrap(store.reminderRule("plain"))))
        // PL-01: the food card's one-tap record writes the action immediately.
        let eventId = try store.recordDishAction(cookId: cook, dishId: dish, action: "wrap")
        XCTAssertTrue(store.library.actions(for: cook).contains { $0.id == eventId && $0.action == "wrap" })
    }
    @MainActor func testUnsupportedProbeConfigurationIsPreservedWithoutArmingNativeMonitoring() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); store.startCook(title: "Mac", firstDish: "Mac")
        let cook = store.library.cooks[0].id, now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        let raw = "{\"version\":1,\"type\":\"probe\",\"seriesId\":\"food-channel\",\"probeId\":\"probe\",\"measurementType\":\"food\",\"direction\":\"above\",\"thresholdF\":165,\"sustainMillis\":0,\"hysteresisF\":3}"
        store.library.reminderRules.append(.object(["id": .string("probe-rule"), "cookId": .string(cook), "title": .string("Check temperature"), "kind": .string("probe"), "triggerConfig": .string(raw), "enabled": .bool(false), "status": .string("inactive"), "schemaVersion": .number(1), "createdAtUtcMillis": .number(now), "updatedAtUtcMillis": .number(now)]))
        try store.library.validateReminderRules()
        XCTAssertTrue(store.prepareReminderSchedules().schedules.isEmpty)
        XCTAssertEqual(store.reminderRule("probe-rule")?["triggerConfig"]?.stringValue, raw)
        XCTAssertThrowsError(try store.changeReminder("probe-rule", action: "toggle"))
        store.library.cooks[0].status = "paused"; XCTAssertTrue(store.library.cooks[0].isActive)
    }
}
