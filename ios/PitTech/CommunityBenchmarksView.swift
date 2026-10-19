import SwiftUI

// MARK: - Community benchmark cards (entitled destination of the benchmarks entry)

/// The benchmarks entry's entitled destination. Every other gate destination
/// (loading, paywall, expired fallback) renders through the shared Pro host, so
/// this view only handles recorded figures and their honest states.
struct CommunityBenchmarksView: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(CommunityBenchmarksLoader.self) private var benchmarks
    let entry: ProPlacement

    var body: some View {
        switch benchmarks.state {
        case .signedOut, .forbidden:
            // A stale entitled cache the server rejects converges through the
            // loader's one refresh, then lands on the same contextual paywall
            // the gate shows — with no figures decoded or rendered.
            ProPaywallView(entry: entry)
        default:
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        SectionTitle(title: entry.title, subtitle: entry.preview)
                        switch benchmarks.state {
                        case .loading:
                            ProgressView("Loading community benchmarks…")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .frame(minHeight: 44)
                        case .loaded(let snapshot):
                            ForEach(snapshot.cards, id: \.testTag) { card in
                                CommunityBenchmarkCard(card: card)
                            }
                            ForEach(snapshot.omitted, id: \.testTag) { omitted in
                                CommunityOmittedNote(omitted: omitted)
                            }
                        case .empty:
                            VStack(alignment: .leading, spacing: 8) {
                                Text("No community figures yet")
                                    .font(.headline)
                                Text("Benchmarks appear here when eligible published cooks are recorded. Your personal tools and Pro entries are unchanged.")
                                    .font(.subheadline)
                                    .foregroundStyle(PitTechStyle.supporting)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(18)
                            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                        case .unavailable:
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Community benchmarks are unavailable right now.")
                                    .font(.headline)
                                Text("Your cooks, exports, and free tools are unchanged.")
                                    .font(.subheadline)
                                    .foregroundStyle(PitTechStyle.supporting)
                                Button("Try again") { Task { await benchmarks.load() } }
                                    .buttonStyle(.bordered)
                                    .frame(minHeight: 44)
                                    .accessibilityIdentifier("community-retry")
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(18)
                            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                        case .signedOut, .forbidden:
                            EmptyView()
                        }
                    }
                    .padding(20)
                }
                .accessibilityIdentifier(benchmarksAccessibilityId)
                .background(PitTechStyle.canvas.ignoresSafeArea())
                .navigationTitle(entry.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { pro.closePaywall() }.accessibilityIdentifier("pro-close")
                    }
                }
            }
            .task { await benchmarks.load() }
            .onDisappear { benchmarks.reset() }
        }
    }

    private var benchmarksAccessibilityId: String {
        switch benchmarks.state {
        case .loaded: return "community-benchmarks"
        case .empty: return "community-empty"
        case .unavailable: return "community-unavailable"
        case .loading: return "community-loading"
        case .signedOut, .forbidden: return "community-benchmarks"
        }
    }
}

struct CommunityBenchmarkCard: View {
    let card: CommunityCardContent

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(card.title)
                .font(.headline)
                .foregroundStyle(PitTechStyle.ink)
            Text(card.bucketLine)
                .font(.subheadline)
                .foregroundStyle(PitTechStyle.supporting)
            ForEach(Array(card.lines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.subheadline)
                    .foregroundStyle(PitTechStyle.ink)
            }
            ForEach(Array(card.notes.enumerated()), id: \.offset) { _, note in
                Text(note)
                    .font(.caption)
                    .foregroundStyle(PitTechStyle.supporting)
            }
            Text(card.coverageLine)
                .font(.caption)
                .foregroundStyle(PitTechStyle.supporting)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityIdentifier(card.testTag)
    }
}

struct CommunityOmittedNote: View {
    let omitted: CommunityOmittedFigure

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(omitted.title)
                .font(.headline)
                .foregroundStyle(PitTechStyle.ink)
            Text(omitted.bucketLine)
                .font(.subheadline)
                .foregroundStyle(PitTechStyle.supporting)
            Text(omitted.note)
                .font(.caption)
                .foregroundStyle(PitTechStyle.supporting)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityIdentifier(omitted.testTag)
    }
}
