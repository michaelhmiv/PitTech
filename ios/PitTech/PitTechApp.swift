import SwiftUI
import UserNotifications

@main
struct PitTechApp: App {
    @State private var store = CookStore()
    @State private var relay = NativeRelayCoordinator()
    @State private var community = NativePublicCoordinator.forApp()
    #if DEBUG
    @State private var account = NativeAccountUITestSupport.make()
    #else
    @State private var account = NativeAccountCoordinator()
    #endif

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(store)
                .environment(relay)
                .environment(community)
                .environment(account)
                .environment(AdsConsentManager.shared)
                .tint(PitTechStyle.accent)
                .modifier(ScreenshotTextSize())
                .onOpenURL { url in
                    if url.isFileURL { Task { await store.receiveArchive(at: url) } }
                    else if community.receivePublic(url) { }
                    else if url.path == "/auth" { account.receive(url) }
                    else if (try? NativeRelayProtocol.code(url.absoluteString)) != nil { relay.receive(url) }
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL {
                        if community.receivePublic(url) { }
                        else if url.path == "/auth" { account.receive(url) }
                        else if (try? NativeRelayProtocol.code(url.absoluteString)) != nil { relay.receive(url) }
                    }
                }
                .task {
                    UNUserNotificationCenter.current().delegate = ReminderNotificationDelegate.shared
                    await store.refreshReminderDeliveryHistory()
                    store.save()
                    #if DEBUG
                    if !CommandLine.arguments.contains("--ui-test-reset") && !CommandLine.arguments.contains("--screenshot-no-ads") && ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil { AdsConsentManager.shared.gatherConsent() }
                    #else
                    AdsConsentManager.shared.gatherConsent()
                    #endif
                }
        }
    }
}
