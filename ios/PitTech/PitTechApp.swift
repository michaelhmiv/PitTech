import SwiftUI

@main
struct PitTechApp: App {
    @State private var store = CookStore()
    @State private var grillirG = GrillirGMonitor()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(store)
                .environment(grillirG)
                .environment(AdsConsentManager.shared)
                .tint(PitTechStyle.accent)
                .task {
                    AdsConsentManager.shared.gatherConsent()
                    await grillirG.restore()
                }
        }
    }
}
