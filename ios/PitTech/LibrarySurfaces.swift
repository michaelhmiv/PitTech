import SwiftUI

struct MoreView: View {
    let runtime: NativeTelemetryRuntime
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    SectionTitle(title: "More", subtitle: "Everything around your cook.")
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Your cookbook stays yours").font(.title3.weight(.semibold))
                        Text("Cook, save recipes and share files without an account. Optional accounts and private sync arrive with community recipes.")
                            .font(.subheadline).foregroundStyle(PitTechStyle.supporting)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
                        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 18))
                    NavigationLink { NativeTelemetryConnectionsView(runtime: runtime, embedded: true) } label: {
                        MoreDestinationLabel(title: "Devices & connections", subtitle: "Read-only monitoring and saved equipment", symbol: "thermometer")
                    }.accessibilityIdentifier("more-devices")
                    NavigationLink { SettingsView() } label: {
                        MoreDestinationLabel(title: "Settings & support", subtitle: "Appearance, units, data and feedback", symbol: "gearshape")
                    }.accessibilityIdentifier("more-settings")
                }.padding(20)
            }.background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("More").navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct MoreDestinationLabel: View {
    let title: String
    let subtitle: String
    let symbol: String
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol).foregroundStyle(PitTechStyle.sage).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).foregroundStyle(PitTechStyle.ink)
                Text(subtitle).font(.subheadline).foregroundStyle(PitTechStyle.supporting)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(PitTechStyle.supporting).accessibilityHidden(true)
        }.frame(minHeight: 44).padding(18)
            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct DiscoveryWelcomeView: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Image(systemName: "safari").font(.system(size: 32)).foregroundStyle(PitTechStyle.sage)
                        .padding(18).background(PitTechStyle.sageFill, in: RoundedRectangle(cornerRadius: 18)).accessibilityHidden(true)
                    SectionTitle(title: "Find your next cook")
                    Text("Community recipes are being prepared for the next PitTech release. You'll be able to browse, save and cook them without an account.")
                        .foregroundStyle(PitTechStyle.supporting)
                    NavigationLink("Open your recipes") { SavedRecipesView() }.buttonStyle(.bordered).frame(minHeight: 44)
                }.padding(20)
            }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Discover").navigationBarTitleDisplayMode(.inline)
        }
    }
}
