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
                .modifier(ScreenshotTextSize())
                .task {
                    UNUserNotificationCenter.current().delegate = ReminderNotificationDelegate.shared
                    await store.refreshReminderDeliveryHistory()
                    store.save()
                    #if DEBUG
                    if !CommandLine.arguments.contains("--ui-test-reset") && ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil { AdsConsentManager.shared.gatherConsent() }
                    #else
                    AdsConsentManager.shared.gatherConsent()
                    #endif
                }
        }
    }
}
