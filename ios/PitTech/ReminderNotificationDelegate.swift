import Foundation
import UserNotifications

final class ReminderNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = ReminderNotificationDelegate()
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { completionHandler([.banner, .sound]) }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if let cookId = response.notification.request.content.userInfo["cookId"] as? String {
            Task { @MainActor in NotificationCenter.default.post(name: Notification.Name("PitTechReminderCook"), object: cookId) }
        }
        completionHandler()
    }
}
