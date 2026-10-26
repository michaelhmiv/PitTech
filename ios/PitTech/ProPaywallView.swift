import SwiftUI

// MARK: - Gate host

/// One sheet for every Pro entry point. The destination follows the verified
/// entitlement state; Live and Finish flows never present this host.
struct ProPaywallHost: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(AdsConsentManager.self) private var adsConsent
    let entry: ProPlacement

    var body: some View {
        let destination = proDestination(
            signedIn: pro.signedIn,
            entitlement: pro.entitlement,
            serverState: pro.serverState,
            previouslyEntitled: adsConsent.previouslyEntitled
        )
        Group {
            switch destination {
            case .loading:
                ProLoadingView()
            case .paywall:
                ProPaywallView(entry: entry)
            case .pro:
                switch entry {
                case .analysis:
                    ProPersonalAnalysisView(entry: entry)
                case .curves:
                    ProAdvancedCurvesView(entry: entry)
                case .benchmarks:
                    CommunityBenchmarksView(entry: entry)
                case .themes:
                    ProCustomizationView(entry: entry)
                case .more:
                    ProMoreHubView(entry: entry)
                }
            case .expiredFallback:
                ProExpiredFallbackView(entry: entry)
            }
        }
    }
}

/// A contextual Pro entry row for Insights and share-card themes.
struct ProEntryRow: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro
    let entry: ProPlacement

    var body: some View {
        // TURN-IN (Slice 2, Area 02 "Go deeper" lane; the same rows are
        // Area 07's canonical entries and Slice 7 reconciles their
        // destinations): marigold ◆ marker, banner-face name, PRO tag
        // chip, factual preview — on the laminated card.
        Button {
            pro.openPro(entry)
        } label: {
            HStack(spacing: 14) {
                Text("◆").font(PitTechFont.body).foregroundStyle(PitTechStyle.marigold).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        TurnInBannerText(entry.title)
                        TurnInProTag()
                    }
                    Text(entry.preview).font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(PitTechStyle.supporting).accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .padding(18)
            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
        }
        .accessibilityIdentifier("pro-entry-\(entry.rawValue)")
    }
}

// MARK: - Loading (neutral: no paywall flash, no ads)

struct ProLoadingView: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    ProgressView("Checking PitTech Pro status…")
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("Your cooks, exports, and free tools stay available while this loads.")
                        .font(.subheadline)
                        .foregroundStyle(PitTechStyle.supporting)
                    Button("Check again") { Task { await pro.reconcile() } }
                        .buttonStyle(.bordered)
                        .frame(minHeight: 44)
                }
                .padding(20)
            }
            .accessibilityIdentifier("pro-loading")
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("PitTech Pro")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { pro.closePaywall() }.accessibilityIdentifier("pro-close")
                }
            }
        }
    }
}

// MARK: - Paywall

struct ProPaywallView: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(NativeAccountCoordinator.self) private var account
    let entry: ProPlacement
    @State private var confirmingRelease = false

    private var product: ProStoreProduct? {
        if case .ready(let product) = pro.productState { return product }
        return nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    SectionTitle(title: "PitTech Pro", subtitle: entry.preview)
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(proPreviewBullets(entry: entry), id: \.self) { bullet in
                            Label(bullet, systemImage: "checkmark.circle")
                                .font(.subheadline)
                                .foregroundStyle(PitTechStyle.supporting)
                        }
                    }
                    .padding(18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))

                    if !proShowsPurchaseOffer(entitlement: pro.entitlement) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("You're covered by PitTech Pro on this account.")
                                .font(.headline)
                            Text("There's no second subscription to buy. Manage or cancel anytime in the App Store.")
                                .font(.subheadline)
                                .foregroundStyle(PitTechStyle.supporting)
                        }
                        .accessibilityIdentifier("pro-entitled-state")
                    } else if let billing = proBillingText(product: product) {
                        Text(billing)
                            .font(.headline)
                            .accessibilityIdentifier("pro-price")
                        Text("Payment is charged to your Apple Account after PitTech verifies the purchase. Subscriptions are managed in the App Store, not in PitTech.")
                            .font(.caption)
                            .foregroundStyle(PitTechStyle.supporting)
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("PitTech Pro isn't available on this build yet.")
                                .font(.subheadline)
                                .foregroundStyle(PitTechStyle.supporting)
                                .accessibilityIdentifier("pro-product-unavailable")
                            // PL-17: the unavailable state is not a dead
                            // end — the user can re-check the store product
                            // and their entitlement without leaving the gate.
                            Button("Check again") { Task { await pro.loadProduct(); await pro.reconcile() } }
                                .buttonStyle(.bordered)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("pro-check-again")
                        }
                    }

                    if !pro.signedIn {
                        Text(pro.accountsEnabled
                            ? "Sign in to subscribe with your PitTech account. Purchases are bound to the verified account."
                            : "Accounts arrive with community recipes and private sync. Subscribing needs a PitTech account first.")
                            .font(.subheadline)
                            .accessibilityIdentifier("pro-signin-required")
                        if pro.accountsEnabled {
                            // Slice 8 (PL-15 / DD-10): sign-in is a detour —
                            // the gate parks this destination, the account
                            // sheet takes over, and closing it (signed in
                            // or cancelled) lands back here.
                            Button("Sign in") { pro.beginSignInDetour() }
                                .buttonStyle(.bordered)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("pro-signin")
                        }
                    }

                    if proShowsPurchaseOffer(entitlement: pro.entitlement),
                       proBillingText(product: product) != nil,
                       pro.signedIn {
                        Button {
                            Task { await pro.subscribe() }
                        } label: {
                            Text(pro.busy ? "Working…" : "Subscribe")
                                .frame(maxWidth: .infinity, minHeight: 54)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(pro.busy)
                        .accessibilityIdentifier("pro-subscribe")
                        .accessibilityLabel(proSubscribeAccessibilityLabel(product: product) ?? "Subscribe to PitTech Pro")
                    }

                    if pro.isStorePending || pro.purchaseState == .pendingStore {
                        Text("Your purchase is pending with the App Store. Pro unlocks after the store confirms it; nothing is charged by PitTech.")
                            .font(.subheadline)
                            .accessibilityIdentifier("pro-pending")
                    }
                    if case .conflict(let recovery) = pro.purchaseState {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(recovery).font(.subheadline)
                            Button("Check again") { Task { await pro.reconcile() } }
                                .buttonStyle(.bordered)
                                .frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("pro-conflict")
                    }
                    if case .failed(let message) = pro.purchaseState {
                        Text(message)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("pro-error")
                    }
                    if let notice = pro.notice {
                        Text(notice)
                            .font(.subheadline)
                            .accessibilityIdentifier("pro-notice")
                    }

                    Button("Restore purchases") { Task { await pro.restore() } }
                        .buttonStyle(.bordered)
                        .frame(minHeight: 44)
                        .disabled(pro.busy || !pro.signedIn)
                        .accessibilityIdentifier("pro-restore")
                    if !pro.signedIn {
                        Text("Restoring needs the PitTech account your purchase is bound to.")
                            .font(.caption)
                            .foregroundStyle(PitTechStyle.supporting)
                    }

                    Link("Manage subscription", destination: URL(string: "https://apps.apple.com/account/subscriptions")!)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("pro-manage")
                    HStack(spacing: 24) {
                        Link("Privacy policy", destination: URL(string: "https://pittech-privacy-production.up.railway.app")!)
                            .accessibilityIdentifier("pro-privacy")
                        Link("Terms of use", destination: URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!)
                            .accessibilityIdentifier("pro-terms")
                    }
                    .frame(minHeight: 44)

                    if pro.lastTransaction != nil, pro.signedIn {
                        Button("Release purchase from this account", role: .destructive) { confirmingRelease = true }
                            .frame(minHeight: 44)
                            .accessibilityIdentifier("pro-release")
                            .confirmationDialog("Release this purchase?", isPresented: $confirmingRelease, titleVisibility: .visible) {
                                Button("Release purchase", role: .destructive) { Task { await pro.releasePurchase() } }
                                    .accessibilityIdentifier("pro-release-confirm")
                            } message: {
                                Text("Pro on this account ends immediately. Another account can claim the purchase through a fresh verification.")
                            }
                    }
                }
                .padding(20)
            }
            .accessibilityIdentifier("pro-paywall")
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("PitTech Pro")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { pro.closePaywall() }.accessibilityIdentifier("pro-paywall-close")
                }
            }
        }
    }
}

func proPreviewBullets(entry: ProPlacement) -> [String] {
    switch entry {
    case .analysis:
        return [
            "Compare your recorded attempts side by side",
            "Find the cooks you rated best",
            "See timing shifts across your cuts and weights",
        ]
    case .curves:
        return [
            "Align recorded cooks at Food on and Wrap",
            "Choose which measured sources to compare",
            "Only real measurements — gaps stay visible",
        ]
    case .benchmarks:
        return [
            "Typical heating times with sample counts",
            "Temperature percentile bands from many cooks",
            "Wrap and spritz shares, prevalence only",
        ]
    case .themes:
        return [
            "Pro share-card themes",
            "Accessible chart palettes",
            "App icon variants",
        ]
    case .more:
        return [
            "No ads, ever",
            "Personal analysis across your recorded cooks",
            "Advanced curves with Food on and Wrap alignment",
            "Pro themes and app icons",
        ]
    }
}

// MARK: - Expired fallback (free capabilities intact, nothing destroyed)

struct ProExpiredFallbackView: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro
    let entry: ProPlacement
    @State private var resubscribing = false

    var body: some View {
        Group {
            if resubscribing {
                ProPaywallView(entry: entry)
            } else {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            SectionTitle(title: entry.title, subtitle: entry.preview)
                            Text("PitTech Pro ended on this account.")
                                .font(.headline)
                            Text("Your recorded cooks, exported files, and free tools keep working. Previously saved exports stay where you left them — nothing was deleted.")
                                .font(.subheadline)
                                .foregroundStyle(PitTechStyle.supporting)
                            Button("See PitTech Pro") { resubscribing = true }
                                .buttonStyle(.borderedProminent)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("pro-resubscribe")
                            Button("Check again") { Task { await pro.reconcile() } }
                                .buttonStyle(.bordered)
                                .frame(minHeight: 44)
                        }
                        .padding(20)
                    }
                    .accessibilityIdentifier("pro-expired-fallback")
                    .background(PitTechStyle.canvas.ignoresSafeArea())
                    .navigationTitle(entry.title)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close") { pro.closePaywall() }.accessibilityIdentifier("pro-close")
                        }
                    }
                }
            }
        }
    }
}
