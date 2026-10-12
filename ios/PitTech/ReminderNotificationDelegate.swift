import Foundation
import UserNotifications

final class ReminderNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = ReminderNotificationDelegate()
    private func announce(_ notification: UNNotification, open: Bool) {
        guard let cookId = notification.request.content.userInfo["cookId"] as? String else { return }
        let occurrence = notification.request.content.userInfo["occurrenceId"] as? String ?? ""
        let timestamp = Double(Int64(notification.date.timeIntervalSince1970 * 1000))
        Task { @MainActor in NotificationCenter.default.post(name: Notification.Name("PitTechReminderCook"), object: ["cookId": cookId, "occurrenceId": occurrence, "at": timestamp, "open": open] as [String: Any]) }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { announce(notification, open: false); completionHandler([.banner, .sound]) }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) { announce(response.notification, open: true); completionHandler() }
}
