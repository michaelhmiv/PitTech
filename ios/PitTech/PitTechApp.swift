import SwiftUI

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
                    AdsConsentManager.shared.gatherConsent()
                }
        }
    }
}
