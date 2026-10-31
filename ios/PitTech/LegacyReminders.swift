import Foundation
import UserNotifications

/// Legacy cook reminders (issue #183, iOS half): Android's
/// CookRepository reminder operations over the archive `reminders`
/// rows. iOS carried the rows through import/sync/export but had no
/// create, no scheduling, and no check-in — the "How did it go?"
/// surface existed only on Android. Rows are archive data (the sync
/// projection excludes the collection), with Android's field shape.
struct LegacyReminder: Equatable, Identifiable {
    var id: String
    var cookId: String
    var title: String
    var dueAtUtcMillis: Int64
    var timeZoneId: String
    var status: String = "pending"
    var createdAtUtcMillis: Int64
    var completedAtUtcMillis: Int64? = nil

    init(row: [String: JSONValue]) {
        id = row["id"]?.stringValue ?? ""
        cookId = row["cookId"]?.stringValue ?? ""
        title = row["title"]?.stringValue ?? ""
        dueAtUtcMillis = Int64(row["dueAtUtcMillis"]?.numberValue ?? 0)
        timeZoneId = row["timeZoneId"]?.stringValue ?? TimeZone.current.identifier
        status = row["status"]?.stringValue ?? "pending"
        createdAtUtcMillis = Int64(row["createdAtUtcMillis"]?.numberValue ?? 0)
        completedAtUtcMillis = row["completedAtUtcMillis"]?.numberValue.map(Int64.init)
    }

    var row: [String: JSONValue] {
        var row: [String: JSONValue] = [
            "id": .string(id), "cookId": .string(cookId), "title": .string(title),
            "dueAtUtcMillis": .number(Double(dueAtUtcMillis)), "timeZoneId": .string(timeZoneId),
            "status": .string(status), "createdAtUtcMillis": .number(Double(createdAtUtcMillis)),
        ]
        row["completedAtUtcMillis"] = completedAtUtcMillis.map { .number(Double($0)) } ?? .null
        return row
    }

    var isDue: Bool { status == "pending" && dueAtUtcMillis <= Int64(Date.now.timeIntervalSince1970 * 1000) }
}

enum LegacyReminderError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { switch self { case .invalid(let message): message } }
}

@MainActor extension CookStore {
    func legacyReminders(cookId: String) -> [LegacyReminder] {
        library.reminders.compactMap { $0.objectValue }.filter { $0["cookId"]?.stringValue == cookId }
            .map(LegacyReminder.init(row:))
            .sorted { $0.dueAtUtcMillis < $1.dueAtUtcMillis }
    }

    func legacyReminder(_ id: String) -> LegacyReminder? {
        library.reminders.compactMap { $0.objectValue }.first { $0["id"]?.stringValue == id }.map(LegacyReminder.init(row:))
    }

    private func putLegacyReminder(_ reminder: LegacyReminder) {
        library.reminders.removeAll { $0.objectValue?["id"]?.stringValue == reminder.id }
        library.reminders.append(.object(reminder.row))
    }

    @discardableResult
    func createCookReminder(cookId: String, title: String, dueAtUtcMillis: Int64) throws -> LegacyReminder {
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        guard library.cooks.contains(where: { $0.id == cookId }) else {
            throw LegacyReminderError.invalid("This cook is no longer available.")
        }
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LegacyReminderError.invalid("Give the reminder a short title.")
        }
        guard dueAtUtcMillis > now else {
            throw LegacyReminderError.invalid("Choose a time in the future.")
        }
        guard dueAtUtcMillis <= now + 30 * 24 * 60 * 60 * 1000 else {
            throw LegacyReminderError.invalid("Reminders can be scheduled up to 30 days ahead.")
        }
        let reminder = LegacyReminder(row: [
            "id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId),
            "title": .string(title.trimmingCharacters(in: .whitespacesAndNewlines)),
            "dueAtUtcMillis": .number(Double(dueAtUtcMillis)),
            "timeZoneId": .string(TimeZone.current.identifier),
            "status": .string("pending"), "createdAtUtcMillis": .number(Double(now)),
            "completedAtUtcMillis": .null,
        ])
        putLegacyReminder(reminder)
        save()
        scheduleLegacyReminderNotification(reminder)
        return reminder
    }

    func cancelCookReminder(_ id: String) {
        guard var reminder = legacyReminder(id), reminder.status == "pending" else { return }
        reminder.status = "cancelled"
        putLegacyReminder(reminder)
        save()
        cancelLegacyReminderNotification(id)
    }

    /// PL-02: Dismiss ends a legacy reminder the same way it ends a
    /// unified rule — handled, with nothing recorded on the timeline.
    /// Distinct from cancelling one that has not fired yet.
    func dismissCookReminder(_ id: String) {
        guard var reminder = legacyReminder(id), reminder.status == "pending" else { return }
        reminder.status = "completed"
        reminder.completedAtUtcMillis = Int64(Date.now.timeIntervalSince1970 * 1000)
        putLegacyReminder(reminder)
        save()
        cancelLegacyReminderNotification(id)
    }

    func snoozeCookReminder(_ id: String) {
        guard var reminder = legacyReminder(id), reminder.status == "pending" else { return }
        reminder.dueAtUtcMillis = Int64(Date.now.timeIntervalSince1970 * 1000) + 600_000
        putLegacyReminder(reminder)
        save()
        scheduleLegacyReminderNotification(reminder)
    }

    /// The check-in ("How did it go?"): completes the reminder and
    /// records its timeline event — "Check-in: <title>", the note (or
    /// Android's default line), a check_in action in the metadata, and
    /// the optional photo attached to the event. Returns the event id.
    @discardableResult
    func completeCookReminder(_ id: String, note: String?, photo: NotePhotoDraft? = nil) throws -> String? {
        guard let reminder = legacyReminder(id), reminder.status == "pending" else { return nil }
        let trimmed = note?.trimmingCharacters(in: .whitespacesAndNewlines)
        var draft = CookNoteDraft(
            cookId: reminder.cookId,
            metadata: CookNoteMetadata(category: "action", action: "check_in"),
            title: "Check-in: \(reminder.title)",
            description: (trimmed?.isEmpty == false ? trimmed! : "Reminder check-in completed."))
        if let photo { draft.photos = [photo] }
        try saveNote(draft)
        dismissCookReminder(id)
        return draft.id
    }

    // MARK: - Notifications

    static func legacyReminderIdentifier(_ id: String) -> String { "cook-reminder:" + id }

    func scheduleLegacyReminderNotification(_ reminder: LegacyReminder) {
        let content = UNMutableNotificationContent()
        content.title = reminder.title
        content.body = "Time to check your cook. Tap to log what happened."
        content.sound = .default
        content.categoryIdentifier = "pittech-cook-reminder"
        content.userInfo = ["cookId": reminder.cookId, "reminderId": reminder.id, "kind": "legacy-reminder"]
        let interval = max(1, Double(reminder.dueAtUtcMillis - Int64(Date.now.timeIntervalSince1970 * 1000)) / 1000)
        let request = UNNotificationRequest(identifier: Self.legacyReminderIdentifier(reminder.id), content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false))
        UNUserNotificationCenter.current().add(request)
    }

    func cancelLegacyReminderNotification(_ id: String) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.legacyReminderIdentifier(id)])
        center.removeDeliveredNotifications(withIdentifiers: [Self.legacyReminderIdentifier(id)])
    }

    /// Re-arms notifications for pending reminders after a launch —
    /// iOS drops nothing across launches, but a reminder created on
    /// another platform (or before an update) has no request yet.
    func rescheduleLegacyReminderNotifications() {
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        for row in library.reminders.compactMap({ $0.objectValue }) {
            let reminder = LegacyReminder(row: row)
            guard reminder.status == "pending", reminder.dueAtUtcMillis > now,
                  library.cooks.contains(where: { $0.id == reminder.cookId && $0.status != "completed" }) else { continue }
            scheduleLegacyReminderNotification(reminder)
        }
    }
}
