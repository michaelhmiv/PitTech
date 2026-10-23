import SwiftUI
import UserNotifications

@main
struct PitTechApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var store = CookStore()
    @State private var relay = NativeRelayCoordinator()
    @State private var community = NativePublicCoordinator.forApp()
    @State private var communityControls = NativeCommunityControls.forApp()
    #if DEBUG
    @State private var account = NativeAccountUITestSupport.make()
    #else
    @State private var account = NativeAccountCoordinator(cleanup: NativeAccountLocalCleanup.run)
    #endif
    @State private var mcp: NativeMcpConnections
    @State private var sync: NativePrivateSyncCoordinator
    @State private var pro: ProSubscriptionCoordinator
    @State private var benchmarks: CommunityBenchmarksLoader
    @State private var customization: ProCustomizationStore
    init() {
        let account = _account.wrappedValue
        _pro = State(initialValue: ProSubscriptionCoordinator.forApp(account: account))
        _benchmarks = State(initialValue: CommunityBenchmarksLoader.forApp(account: account, pro: _pro.wrappedValue))
        _customization = State(initialValue: ProCustomizationStore.forApp(store: _store.wrappedValue))
        _mcp = State(initialValue: NativeMcpConnections(account: account))
        _sync = State(initialValue: NativePrivateSyncCoordinator.forApp(store: _store.wrappedValue, account: account))
    }

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
                .environment(mcp)
                .environment(sync)
                .environment(pro)
                .environment(benchmarks)
                .environment(customization)
                .environment(AdsConsentManager.shared)
                .onChange(of: account.stateUserId) { _, _ in community.controlsChanged() }
                .onChange(of: account.stateUserId) { _, _ in sync.refresh() }
                .onChange(of: scenePhase) { _, phase in if phase == .active { sync.resumeAutomaticSync() } else { sync.pauseAutomaticSync() } }
                .onChange(of: account.stateUserId) { _, _ in Task { await pro.refresh() } }
                .onChange(of: account.deletionRecord?.receipt?.deletionId) { _, _ in communityControls.reload(); community.controlsChanged() }
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
                    store.onLibrarySaved = { sync.requestAutomaticSync() }
                    store.onBeforeLocalJournalRemoval = { try sync.detachLibrary() }
                    if scenePhase == .active { sync.resumeAutomaticSync() }
                    account.removeJournal = { try store.removeLocalJournal() }
                    pro.start()
                    communityControls.reload()
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
