import SwiftUI
import UserNotifications

@main
struct PitTechApp: App {
    @State private var store = CookStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(store)
                .environment(AdsConsentManager.shared)
                .tint(PitTechStyle.accent)
                .task {
                    UNUserNotificationCenter.current().delegate = ReminderNotificationDelegate.shared
                    await store.refreshReminderDeliveryHistory()
                    store.save()
                    AdsConsentManager.shared.gatherConsent()
                }
        }
    }
}
