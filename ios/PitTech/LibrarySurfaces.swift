import SwiftUI

struct MoreView: View {
    @Environment(NativeRelayCoordinator.self) private var relay
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(NativePublicCoordinator.self) private var community
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(NativePrivateSyncCoordinator.self) private var sync
    let runtime: NativeTelemetryRuntime
    /// PL-14 (#172): once signed in, the Account row carries the private
    /// sync status so it is visible without opening the sheet.
    private var accountSubtitle: String {
        guard account.signedIn else { return "Optional email sign-in and this device’s session" }
        let headline = SyncStatusCopy.row(status: sync.presentation.status, detail: sync.presentation.detail, lastSyncedAt: sync.presentation.lastSyncedAt, conflictCount: sync.presentation.conflicts.count, signedIn: true, enabled: sync.enabled).headline
        return "Private sync — \(headline)"
    }
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
                    if account.enabled {
                        Button { account.showingAccount = true } label: {
                            MoreDestinationLabel(title: "Account", subtitle: accountSubtitle, symbol: "person.crop.circle")
                        }.accessibilityIdentifier("more-account")
                        .onAppear { if account.signedIn { sync.refresh() } }
                    }
                    if community.enabled { NavigationLink { NativeCommunityControlsView() } label: { MoreDestinationLabel(title: "Community controls", subtitle: "Reports, saved requests and blocked publishers", symbol: "hand.raised") }.accessibilityIdentifier("more-community-controls") }
                    if account.enabled && community.enabled {
                        NavigationLink { NativeMyCookbookView() } label: { MoreDestinationLabel(title: "My cookbook", subtitle: "Public identity, publications and interrupted changes", symbol: "book.closed") }.accessibilityIdentifier("more-cookbook")
                    }
                    NavigationLink { SettingsView() } label: {
                        MoreDestinationLabel(title: "Settings & support", subtitle: "Appearance, units, data and feedback", symbol: "gearshape")
                    }.accessibilityIdentifier("more-settings")
                    Button { relay.showingImport = true; relay.message = nil } label: {
                        MoreDestinationLabel(title: "Import by code", subtitle: "Preview a shared Cook Pack without an account", symbol: "qrcode")
                    }.accessibilityIdentifier("more-relay-import")
                    NavigationLink { NativeSharedCodesView() } label: {
                        MoreDestinationLabel(title: "My shared codes", subtitle: "Renew or revoke codes created on this device", symbol: "square.and.arrow.up")
                    }.accessibilityIdentifier("more-relay-owned")
                    Button { pro.openPro(.more) } label: {
                        MoreDestinationLabel(title: "PitTech Pro", subtitle: "Ad-free cooking with deeper analysis", symbol: "sparkles")
                    }.accessibilityIdentifier("more-pro")
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

/// PL-30 (#172): the one shared "not available yet" pattern — icon tile,
/// title, message, an optional note about what works instead, and one
/// route onward. Every not-available surface on both platforms renders
/// through this shape with `NotAvailableCopy` strings.
struct NotAvailablePlaceholderView<Onward: View>: View {
    let systemImage: String
    let title: String
    let message: String
    var note: String? = nil
    let onwardLabel: String
    @ViewBuilder let onward: () -> Onward
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: systemImage).font(.system(size: 32)).foregroundStyle(PitTechStyle.sage)
                    .padding(18).background(PitTechStyle.sageFill, in: RoundedRectangle(cornerRadius: 18)).accessibilityHidden(true)
                SectionTitle(title: title)
                Text(message).foregroundStyle(PitTechStyle.supporting)
                if let note { Text(note).foregroundStyle(PitTechStyle.supporting) }
                NavigationLink(onwardLabel) { onward() }.buttonStyle(.bordered).frame(minHeight: 44)
            }.padding(20)
        }.background(PitTechStyle.canvas.ignoresSafeArea())
    }
}

struct DiscoveryWelcomeView: View {
    var body: some View {
        NavigationStack {
            // TURN-IN (slice 5, frame c05): the Discover placeholder wears
            // the dashed-card composition; the shared
            // NotAvailablePlaceholderView pattern is unchanged for its
            // other homes. Copy stays the NotAvailableCopy constants.
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    TurnInDashedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Image(systemName: "safari").font(.system(size: 32)).foregroundStyle(PitTechStyle.emberText)
                                .padding(18).background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 18)).accessibilityHidden(true)
                            TurnInBannerText(NotAvailableCopy.discoverTitle, font: PitTechFont.bannerTitle)
                            Text(NotAvailableCopy.discoverMessage).font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                        }
                    }
                    NavigationLink(NotAvailableCopy.discoverOnward) { SavedRecipesView() }.buttonStyle(.bordered).frame(minHeight: 44)
                }.padding(20)
            }.background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("Discover").navigationBarTitleDisplayMode(.inline)
        }
    }
}
