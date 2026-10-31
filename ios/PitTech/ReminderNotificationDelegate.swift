import Foundation
import UserNotifications

/// Notification categories for the cook-support systems (#183): the
/// action identifiers are the delegate's vocabulary, and their titles
/// are the shared PL-02 words — Done / Snooze 10 min / Acknowledge /
/// Dismiss mean the same thing on every surface, Android included.
enum PitTechNotificationCategories {
    static func register() {
        let center = UNUserNotificationCenter.current()
        let guidance = UNNotificationCategory(
            identifier: "pittech-guidance",
            actions: [
                UNNotificationAction(identifier: "guidance-done", title: "Done", options: []),
                UNNotificationAction(identifier: "guidance-snooze", title: "Snooze 10 min", options: []),
            ],
            intentIdentifiers: [])
        let alert = UNNotificationCategory(
            identifier: "pittech-cook-alert",
            actions: [
                UNNotificationAction(identifier: "alert-acknowledge", title: "Acknowledge", options: []),
                UNNotificationAction(identifier: "alert-snooze", title: "Snooze 10 min", options: []),
            ],
            intentIdentifiers: [])
        let legacyReminder = UNNotificationCategory(
            identifier: "pittech-cook-reminder",
            actions: [
                UNNotificationAction(identifier: "reminder-snooze", title: "Snooze 10 min", options: []),
                UNNotificationAction(identifier: "reminder-dismiss", title: "Dismiss", options: []),
            ],
            intentIdentifiers: [])
        center.setNotificationCategories([guidance, alert, legacyReminder])
    }
}

final class ReminderNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = ReminderNotificationDelegate()
    private func announce(_ notification: UNNotification, open: Bool, actionIdentifier: String? = nil) {
        guard let cookId = notification.request.content.userInfo["cookId"] as? String else { return }
        var payload: [String: Any] = [
            "cookId": cookId,
            "occurrenceId": notification.request.content.userInfo["occurrenceId"] as? String ?? "",
            "at": Double(Int64(notification.date.timeIntervalSince1970 * 1000)),
            "open": open,
        ]
        // #183: the cook-support kinds ride the same announcement; the
        // receiver routes by kind + the specific record involved.
        for key in ["kind", "stepId", "alertId", "reminderId"] {
            if let value = notification.request.content.userInfo[key] as? String { payload[key] = value }
        }
        if let occurrence = notification.request.content.userInfo["occurrence"] as? Int { payload["occurrence"] = occurrence }
        if let actionIdentifier { payload["actionIdentifier"] = actionIdentifier }
        Task { @MainActor in NotificationCenter.default.post(name: Notification.Name("PitTechReminderCook"), object: payload) }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { announce(notification, open: false); completionHandler([.banner, .sound]) }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) { announce(response.notification, open: response.actionIdentifier == UNNotificationDefaultActionIdentifier, actionIdentifier: response.actionIdentifier); completionHandler() }
}
