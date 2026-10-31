import XCTest
@testable import PitTech

/// Issue #183: legacy cook reminders — Android's create validations,
/// the PL-02 semantics (Log it records a check-in event, Dismiss ends
/// without recording, Cancel withdraws a pending reminder), and
/// Snooze 10 min.
@MainActor final class LegacyReminderTests: XCTestCase {
    private func makeStore() -> (store: CookStore, cookId: String, folder: URL) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = CookStore(directory: folder)
        store.startCook(title: "Reminder cook", firstDish: "Ribs")
        return (store, store.library.cooks[0].id, folder)
    }

    func testCreateValidations() throws {
        let (store, cookId, folder) = makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        XCTAssertThrowsError(try store.createCookReminder(cookId: cookId, title: "  ", dueAtUtcMillis: now + 60_000))
        XCTAssertThrowsError(try store.createCookReminder(cookId: cookId, title: "Past", dueAtUtcMillis: now - 60_000))
        XCTAssertThrowsError(try store.createCookReminder(cookId: cookId, title: "Too far", dueAtUtcMillis: now + 31 * 24 * 3_600_000))
        XCTAssertThrowsError(try store.createCookReminder(cookId: "missing-cook", title: "No cook", dueAtUtcMillis: now + 60_000))
        let created = try store.createCookReminder(cookId: cookId, title: "Check the bark", dueAtUtcMillis: now + 1_800_000)
        XCTAssertEqual(created.status, "pending")
        XCTAssertEqual(store.legacyReminders(cookId: cookId).count, 1)
    }

    func testCompleteWritesCheckInEventAndDismisses() throws {
        let (store, cookId, folder) = makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let reminder = try store.createCookReminder(cookId: cookId, title: "Check the bark", dueAtUtcMillis: now + 60_000)
        let eventId = try store.completeCookReminder(reminder.id, note: "Bark is set")
        let event = try XCTUnwrap(store.library.events.compactMap(\.objectValue).first { $0["id"]?.stringValue == eventId })
        XCTAssertEqual(event["title"]?.stringValue, "Check-in: Check the bark")
        XCTAssertEqual(event["details"]?.stringValue, "Bark is set")
        let metadata = try XCTUnwrap(event["noteMetadataJson"]?.stringValue)
        XCTAssertTrue(metadata.contains("\"check_in\""))
        XCTAssertEqual(store.legacyReminder(reminder.id)?.status, "completed")
        XCTAssertNotNil(store.legacyReminder(reminder.id)?.completedAtUtcMillis)
    }

    func testCompleteWithoutNoteUsesTheDefaultLine() throws {
        let (store, cookId, folder) = makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let reminder = try store.createCookReminder(cookId: cookId, title: "Spritz", dueAtUtcMillis: now + 60_000)
        let eventId = try store.completeCookReminder(reminder.id, note: nil)
        let event = try XCTUnwrap(store.library.events.compactMap(\.objectValue).first { $0["id"]?.stringValue == eventId })
        XCTAssertEqual(event["details"]?.stringValue, "Reminder check-in completed.")
    }

    func testDismissRecordsNothingAndCancelWithdraws() throws {
        let (store, cookId, folder) = makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let dismissed = try store.createCookReminder(cookId: cookId, title: "Dismissed one", dueAtUtcMillis: now + 60_000)
        let eventsBefore = store.library.events.count
        store.dismissCookReminder(dismissed.id)
        XCTAssertEqual(store.legacyReminder(dismissed.id)?.status, "completed")
        XCTAssertEqual(store.library.events.count, eventsBefore)
        let cancelled = try store.createCookReminder(cookId: cookId, title: "Cancelled one", dueAtUtcMillis: now + 120_000)
        store.cancelCookReminder(cancelled.id)
        XCTAssertEqual(store.legacyReminder(cancelled.id)?.status, "cancelled")
    }

    func testSnoozeMovesDueTenMinutesOut() throws {
        let (store, cookId, folder) = makeStore()
        defer { try? FileManager.default.removeItem(at: folder) }
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let reminder = try store.createCookReminder(cookId: cookId, title: "Snooze me", dueAtUtcMillis: now + 60_000)
        store.snoozeCookReminder(reminder.id)
        let snoozed = try XCTUnwrap(store.legacyReminder(reminder.id))
        XCTAssertEqual(snoozed.status, "pending")
        XCTAssertGreaterThanOrEqual(snoozed.dueAtUtcMillis, now + 600_000 - 5_000)
        XCTAssertLessThan(snoozed.dueAtUtcMillis, now + 600_000 + 60_000)
    }
}
