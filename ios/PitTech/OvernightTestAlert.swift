import Foundation
import UserNotifications

/// Flowing Slice 2 (E3): the overnight panel's test alert — a REAL
/// notification, because rehearsing the wake-up is the entire point: if
/// this does not buzz the phone on the nightstand, a 3 AM stall alert
/// will not either. It carries no actions and no category — there is
/// nothing to swallow — and its identifier is distinct from every
/// reminder's, so it never collides with a real firing. The request
/// construction is a pure function so tests can prove what will post.
enum OvernightTestAlert {
    static func identifier(cookId: String) -> String { "overnight-test-\(cookId)" }

    static func request(cookId: String, cookTitle: String) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = "Test alert — \(cookTitle)"
        content.body = "This is the sound and buzz your overnight alerts use. If this reached you, the safety net can wake you."
        content.sound = .default
        // A nil trigger posts immediately — the rehearsal happens now,
        // while the cook is watching for it.
        return UNNotificationRequest(identifier: identifier(cookId: cookId), content: content, trigger: nil)
    }

    static func post(cookId: String, cookTitle: String) {
        UNUserNotificationCenter.current().add(request(cookId: cookId, cookTitle: cookTitle))
    }
}
