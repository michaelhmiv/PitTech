import SwiftUI
import UserNotifications

@main
struct PitTechApp: App {
    @State private var store = CookStore()
    @State private var relay = NativeRelayCoordinator()
    @State private var community = NativePublicCoordinator.forApp()
    @State private var communityControls = NativeCommunityControls.forApp()
    #if DEBUG
    @State private var account = NativeAccountUITestSupport.make()
    #else
    @State private var account = NativeAccountCoordinator()
    #endif

    var body: some Scene {
        WindowGroup {
            Group {
                #if DEBUG
                if NativePublicationUITestSupport.active { NativePublicationUITestHost() } else { ContentView() }
                #else
                ContentView()
                #endif
            }
                .environment(store)
                .environment(relay)
                .environment(community)
                .environment(communityControls)
                .environment(account)
                .environment(AdsConsentManager.shared)
                .onChange(of: account.stateUserId) { _, _ in community.controlsChanged() }
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
