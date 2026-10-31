import SwiftUI

// MARK: - Slice 18 Pro views (entitled destinations; the slice-16 gate owns access)

// These views render only when the Pro gate resolves to `.pro`. They read the
// local library plus the cached verified entitlement — never the network — so
// offline Pro access works. Every other gate destination (loading, paywall,
// expired fallback) renders through the shared Pro host.

// MARK: - Personal analysis (Insights entry)

struct ProPersonalAnalysisView: View {
    @Environment(CookStore.self) private var store
    @Environment(ProSubscriptionCoordinator.self) private var pro
    let entry: ProPlacement

    private var titles: [String: String] {
        Dictionary(uniqueKeysWithValues: store.library.cooks.map { ($0.id, $0.title) })
    }

    private var snapshot: ProPersonalAnalysis.Snapshot {
        let archive = store.insightsArchive()
        let report = InsightsCalculations.compute(archive, asOf: Date.now.timeIntervalSince1970 * 1000)
        return ProPersonalAnalysis.snapshot(report: report, titles: titles)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // TURN-IN (Slice 2, Area 02 `analysis`): the kicker
                    // names the destination; the preview line follows in
                    // the text face. Section headers and cards below take
                    // the banner face and the laminated card.
                    VStack(alignment: .leading, spacing: 6) {
                        TurnInMetaLabel("\(entry.title) · Pro")
                        Text(entry.preview)
                            .font(PitTechFont.body)
                            .foregroundStyle(PitTechStyle.inkMuted)
                    }
                    if snapshot.state == .empty {
                        TurnInCard {
                            VStack(alignment: .leading, spacing: 8) {
                                TurnInBannerText(snapshot.emptyTitle ?? ProPersonalAnalysis.emptyTitle)
                                Text(snapshot.emptyBody ?? ProPersonalAnalysis.emptyBody)
                                    .font(PitTechFont.body)
                                    .foregroundStyle(PitTechStyle.inkMuted)
                            }
                        }
                        .accessibilityIdentifier("pro-analysis-empty")
                    } else {
                        if let body = snapshot.emptyBody {
                            Text(body)
                                .font(.subheadline)
                                .foregroundStyle(PitTechStyle.supporting)
                                .accessibilityIdentifier("pro-analysis-sparse-note")
                        }
                        if let note = snapshot.comparisonNote {
                            TurnInCard {
                                Text(note)
                                    .font(PitTechFont.body)
                                    .foregroundStyle(PitTechStyle.ink)
                            }
                            .accessibilityIdentifier("pro-analysis-comparison-note")
                        } else {
                            VStack(alignment: .leading, spacing: 10) {
                                TurnInBannerText("How your cooks of a cut compare", font: PitTechFont.bannerTitle)
                                ForEach(snapshot.cutGroups, id: \.cut) { group in
                                    ProAnalysisCard(
                                        title: group.header,
                                        lines: group.lines,
                                        notes: [],
                                        coverage: group.coverage,
                                        testTag: "pro-analysis-cut"
                                    )
                                }
                            }
                        }
                        if let best = snapshot.best {
                            VStack(alignment: .leading, spacing: 10) {
                                TurnInBannerText("Your best-rated recorded attempt", font: PitTechFont.bannerTitle)
                                ProAnalysisCard(
                                    title: "Your declared ratings decide",
                                    lines: best.lines,
                                    notes: best.notes,
                                    coverage: best.coverage,
                                    testTag: "pro-analysis-best"
                                )
                            }
                        }
                        if let timing = snapshot.timing {
                            VStack(alignment: .leading, spacing: 10) {
                                TurnInBannerText("Timing across recorded cuts and weights", font: PitTechFont.bannerTitle)
                                ProAnalysisCard(
                                    title: "Recorded heating time by cut",
                                    lines: timing.lines,
                                    notes: [],
                                    coverage: timing.coverage,
                                    testTag: "pro-analysis-timing"
                                )
                            }
                        }
                        if let methods = snapshot.methods {
                            VStack(alignment: .leading, spacing: 10) {
                                TurnInBannerText("Wrap and rest differences", font: PitTechFont.bannerTitle)
                                ProAnalysisCard(
                                    title: "What each cook recorded",
                                    lines: methods.lines,
                                    notes: [],
                                    coverage: methods.coverage,
                                    testTag: "pro-analysis-methods"
                                )
                            }
                        }
                        if store.library.cooks.count >= 2 {
                            // Flowing Slice 6 (Spread 05): the compare grid —
                            // recorded inputs beside outcomes, PROJECTED
                            // totals tagged, one data-gated sentence.
                            ProCompareSection()
                        }
                        NavigationLink {
                            ProRecapBuilderView()
                        } label: {
                            TurnInCard {
                                HStack(spacing: 14) {
                                    Text("◆").font(PitTechFont.body).foregroundStyle(PitTechStyle.marigold).accessibilityHidden(true)
                                    VStack(alignment: .leading, spacing: 4) {
                                        TurnInBannerText("Year in Smoke")
                                        Text("Build a scoped, shareable recap from this library.")
                                            .font(PitTechFont.body)
                                            .foregroundStyle(PitTechStyle.inkMuted)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    Image(systemName: "chevron.right")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(PitTechStyle.supporting)
                                        .accessibilityHidden(true)
                                }
                                .frame(minHeight: 44)
                            }
                        }
                        .accessibilityIdentifier("pro-recap-entry")
                    }
                }
                .padding(20)
            }
            .accessibilityIdentifier("pro-analysis")
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

struct ProAnalysisCard: View {
    let title: String
    let lines: [String]
    let notes: [String]
    let coverage: String
    let testTag: String

    var body: some View {
        TurnInCard {
            VStack(alignment: .leading, spacing: 8) {
                TurnInBannerText(title)
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(PitTechFont.body)
                        .foregroundStyle(PitTechStyle.ink)
                }
                ForEach(Array(notes.enumerated()), id: \.offset) { _, note in
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(PitTechStyle.supporting)
                }
                Text(coverage)
                    .font(.caption)
                    .foregroundStyle(PitTechStyle.supporting)
            }
        }
        .accessibilityIdentifier(testTag)
    }
}

// MARK: - Compare cooks (Flowing Slice 6, Spread 05)

/// The aligned-compare surface's grid: pick two to four cooks — including
/// one still on the pit — and what each cook recorded doing (§4.2 inputs)
/// sits beside what happened (outcomes incl. the §4.1 stall). A cook from
/// before inputs were recorded says "Not recorded" — never a guess; a
/// running cook's total is its §4.1 projection, tagged PROJECTED with its
/// basis stated, never blended into the recorded facts. One generated
/// sentence teaches from the cook's own history when — and only when —
/// the recorded data supports it. All figures come from `CompareFacts`,
/// the mirror of Android's compare model.
struct ProCompareSection: View {
    @Environment(CookStore.self) private var store
    @State private var selected: Set<String> = []

    private var archive: InsightArchiveInput { store.insightsArchive() }

    private var cooks: [CompareCookInput] { store.library.cooks.map(CompareCookInput.init(record:)) }

    private var selectedCooks: [CompareCookInput] { cooks.filter { selected.contains($0.id) } }

    private var facts: [CompareCookFacts] {
        let archive = archive
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        return selectedCooks.map { CompareFacts.facts(cook: $0, archive: archive, nowUtcMillis: now) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            TurnInBannerText("Compare cooks", font: PitTechFont.bannerTitle)
            Text("Choose two to four cooks to compare — a cook still on the pit shows its projected total, tagged as such.")
                .font(.caption)
                .foregroundStyle(PitTechStyle.supporting)
            ForEach(cooks, id: \.id) { cook in
                Button {
                    if selected.contains(cook.id) {
                        selected.remove(cook.id)
                    } else if selected.count < 4 {
                        selected.insert(cook.id)
                    }
                } label: {
                    HStack {
                        Image(systemName: selected.contains(cook.id) ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(PitTechStyle.accent)
                            .accessibilityHidden(true)
                        Text(cook.title)
                            .font(.subheadline)
                            .foregroundStyle(PitTechStyle.ink)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if cook.status != "completed" {
                            Text("On the pit")
                                .font(.caption)
                                .foregroundStyle(PitTechStyle.supporting)
                        }
                    }
                    .frame(minHeight: 44)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 8)
                    .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                }
                .accessibilityIdentifier("compare-select-\(cook.id)")
            }
            if selectedCooks.count >= 2 {
                compareCard
            }
        }
    }

    private var compareCard: some View {
        let facts = facts
        return TurnInCard {
            VStack(alignment: .leading, spacing: 10) {
                // 5.4: one generated sentence, only when the recorded
                // history supports it; otherwise it is simply absent.
                if let sentence = CompareFacts.teachingSentence(cooks: cooks, archive: archive) {
                    Text(sentence)
                        .font(PitTechFont.body)
                        .foregroundStyle(PitTechStyle.ink)
                        .accessibilityIdentifier("compare-teaching-sentence")
                }
                ForEach(CompareFacts.gridRows(facts: facts), id: \.label) { row in
                    HStack(alignment: .top, spacing: 8) {
                        Text(row.label)
                            .font(PitTechFont.body)
                            .foregroundStyle(PitTechStyle.inkMuted)
                            .frame(width: 86, alignment: .leading)
                        ForEach(Array(row.cells.enumerated()), id: \.offset) { index, cell in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(cell.text)
                                    .font(PitTechFont.monoMeta)
                                    .foregroundStyle(PitTechStyle.ink)
                                // 5.3: the projected total wears its tag in
                                // the cell — never rendered as fact.
                                if cell.isProjected {
                                    Text("PROJECTED")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(PitTechStyle.marigold)
                                        .accessibilityIdentifier("compare-projected-tag-\(facts[index].cookId)")
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                // 5.3 + §4.1: a projected total always states its basis;
                // a withheld projection says why, in the engine's words.
                ForEach(facts.filter { $0.inProgress }, id: \.cookId) { fact in
                    if let projected = fact.projected {
                        Text("\(fact.title) total is projected \(projected.basisText).")
                            .font(.caption)
                            .foregroundStyle(PitTechStyle.supporting)
                            .accessibilityIdentifier("compare-projection-basis-\(fact.cookId)")
                    } else if fact.reachedTarget {
                        Text("\(fact.title) has reached its recorded target.")
                            .font(.caption)
                            .foregroundStyle(PitTechStyle.supporting)
                            .accessibilityIdentifier("compare-projection-basis-\(fact.cookId)")
                    } else if let reason = fact.projectionWithheldReason {
                        Text("No projection for \(fact.title) yet — \(reason).")
                            .font(.caption)
                            .foregroundStyle(PitTechStyle.supporting)
                            .accessibilityIdentifier("compare-projection-note-\(fact.cookId)")
                    }
                }
            }
        }
        .accessibilityIdentifier("compare-grid")
    }
}

// MARK: - Advanced curves (Insights entry)

struct ProAdvancedCurvesView: View {
    @Environment(CookStore.self) private var store
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(ProCustomizationStore.self) private var customization
    let entry: ProPlacement
    @State private var anchor = ProAdvancedCurves.Anchor.foodOn
    @State private var selected: Set<String> = []

    private var paletteColors: [String] {
        ProCustomization.effectivePalette(storedId: customization.paletteId, entitled: pro.entitlement == .entitled).colors
    }

    private var titles: [String: String] {
        Dictionary(uniqueKeysWithValues: store.library.cooks.map { ($0.id, $0.title) })
    }

    private var archive: InsightArchiveInput { store.insightsArchive() }

    private var report: InsightReport {
        InsightsCalculations.compute(archive, asOf: Date.now.timeIntervalSince1970 * 1000)
    }

    private var options: [ProAdvancedCurves.ChannelOption] {
        ProAdvancedCurves.channelOptions(report: report, archive: archive, titles: titles)
    }

    private var aligned: ProAdvancedCurves.AlignedSnapshot {
        ProAdvancedCurves.align(report: report, archive: archive, titles: titles, anchor: anchor, channelIds: selected)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 6) {
                        TurnInMetaLabel("\(entry.title) · Pro")
                        Text(entry.preview)
                            .font(PitTechFont.body)
                            .foregroundStyle(PitTechStyle.inkMuted)
                    }
                    if options.isEmpty {
                        TurnInCard {
                            VStack(alignment: .leading, spacing: 8) {
                                TurnInBannerText("No measured channels yet")
                                Text("Record temperatures with a cook to compare aligned curves here. Gaps stay visible; nothing is estimated.")
                                    .font(PitTechFont.body)
                                    .foregroundStyle(PitTechStyle.inkMuted)
                            }
                        }
                        .accessibilityIdentifier("pro-curves-empty")
                    } else {
                        VStack(alignment: .leading, spacing: 10) {
                            TurnInBannerText("Align at", font: PitTechFont.bannerTitle)
                            Picker("Align at", selection: $anchor) {
                                ForEach(ProAdvancedCurves.Anchor.allCases, id: \.self) { option in
                                    Text(option.title).tag(option)
                                }
                            }
                            .pickerStyle(.segmented)
                            .frame(minHeight: 44)
                            .accessibilityIdentifier("pro-curves-anchor")
                        }
                        VStack(alignment: .leading, spacing: 10) {
                            TurnInBannerText("Measured sources", font: PitTechFont.bannerTitle)
                            Text("Only real measurements — gaps stay visible.")
                                .font(.caption)
                                .foregroundStyle(PitTechStyle.supporting)
                            ForEach(options, id: \.channelId) { option in
                                Button {
                                    if selected.contains(option.channelId) {
                                        selected.remove(option.channelId)
                                    } else {
                                        selected.insert(option.channelId)
                                    }
                                } label: {
                                    HStack {
                                        Image(systemName: selected.contains(option.channelId) ? "checkmark.circle.fill" : "circle")
                                            .foregroundStyle(PitTechStyle.accent)
                                            .accessibilityHidden(true)
                                        Text(option.label)
                                            .font(.subheadline)
                                            .foregroundStyle(PitTechStyle.ink)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    .frame(minHeight: 44)
                                    .padding(.horizontal, 18)
                                    .padding(.vertical, 8)
                                    .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                                }
                                .accessibilityIdentifier(option.testTag)
                            }
                        }
                        if selected.isEmpty {
                            Text("Choose at least one measured source to see aligned readings.")
                                .font(.subheadline)
                                .foregroundStyle(PitTechStyle.supporting)
                                .accessibilityIdentifier("pro-curves-unselected")
                        } else {
                            ForEach(Array(aligned.series.enumerated()), id: \.element.channelId) { index, series in
                                TurnInCard {
                                    VStack(alignment: .leading, spacing: 8) {
                                        TurnInBannerText(series.label, color: Color(proHex: paletteColors[index % paletteColors.count]))
                                        ForEach(Array(series.points.prefix(40).enumerated()), id: \.offset) { _, point in
                                            Text(point.line)
                                                .font(PitTechFont.body)
                                                .foregroundStyle(PitTechStyle.ink)
                                        }
                                        if series.points.count > 40 {
                                            Text("+\(series.points.count - 40) more recorded points")
                                                .font(.caption)
                                                .foregroundStyle(PitTechStyle.supporting)
                                        }
                                        Text(series.coverage)
                                            .font(.caption)
                                            .foregroundStyle(PitTechStyle.supporting)
                                    }
                                }
                                .accessibilityIdentifier(series.testTag)
                            }
                            ForEach(Array(aligned.excluded.enumerated()), id: \.offset) { _, note in
                                Text(note)
                                    .font(.caption)
                                    .foregroundStyle(PitTechStyle.supporting)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(18)
                                    .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                                    .accessibilityIdentifier("pro-curves-excluded")
                            }
                        }
                    }
                }
                .padding(20)
            }
            .accessibilityIdentifier("pro-curves")
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

// MARK: - Year in Smoke recap builder (from analysis and the Pro hub)

struct ProRecapBuilderView: View {
    @Environment(CookStore.self) private var store
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(ProCustomizationStore.self) private var customization
    @State private var scope = ProRecap.Scope.all(cookIds: [], period: ProRecap.allPeriods)
    @State private var previewing = false
    @State private var rendered: Data?
    @State private var renderFailed = false
    @State private var initialized = false

    private var titles: [String: String] {
        Dictionary(uniqueKeysWithValues: store.library.cooks.map { ($0.id, $0.title) })
    }

    private var archive: InsightArchiveInput { store.insightsArchive() }

    private var report: InsightReport {
        InsightsCalculations.compute(archive, asOf: Date.now.timeIntervalSince1970 * 1000)
    }

    private var periods: [String] {
        [ProRecap.allPeriods] + ProRecap.availablePeriods(startedAt: Dictionary(uniqueKeysWithValues: archive.cooks.map { ($0.id, $0.startedAtUtcMillis) }))
    }

    private var periodCooks: [String] {
        ProRecap.cooksInPeriod(report: report, archive: archive, period: scope.period)
    }

    private var card: ProRecap.Card? {
        ProRecap.build(report: report, titles: titles, scope: scope, totalCookCount: report.cooks.count)
    }

    private var themeId: String {
        ProCustomization.effectiveTheme(storedId: customization.themeId, entitled: pro.entitlement == .entitled).id
    }

    var body: some View {
        Group {
            if previewing {
                recapPreview
            } else {
                recapScope
            }
        }
        .onAppear {
            guard !initialized else { return }
            initialized = true
            scope = ProRecap.Scope.all(cookIds: report.cooks.map(\.cookId), period: ProRecap.allPeriods)
        }
    }

    private var recapScope: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // TURN-IN (Slice 2, Area 02 `recap-scope`): kicker +
                // instruction line, banner section headers, laminated
                // cook rows; the scope controls themselves are unchanged.
                VStack(alignment: .leading, spacing: 6) {
                    TurnInMetaLabel("Year in Smoke")
                    Text("Choose the period, cooks, and sections. Nothing is shared until you say so.")
                        .font(PitTechFont.body)
                        .foregroundStyle(PitTechStyle.inkMuted)
                }
                VStack(alignment: .leading, spacing: 10) {
                    TurnInBannerText("Period", font: PitTechFont.bannerTitle)
                    Picker("Period", selection: Binding(
                        get: { scope.period },
                        set: { period in
                            scope.period = period
                            scope.includedCookIds = ProRecap.cooksInPeriod(report: report, archive: archive, period: period)
                        }
                    )) {
                        ForEach(periods, id: \.self) { period in
                            Text(period == ProRecap.allPeriods ? "All recorded cooks" : period).tag(period)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("pro-recap-period")
                }
                VStack(alignment: .leading, spacing: 10) {
                    TurnInBannerText("Cooks in this recap", font: PitTechFont.bannerTitle)
                    if periodCooks.isEmpty {
                        Text("No recorded cooks in this period.")
                            .font(.subheadline)
                            .foregroundStyle(PitTechStyle.supporting)
                    }
                    ForEach(periodCooks, id: \.self) { cookId in
                        Button {
                            if scope.includedCookIds.contains(cookId) {
                                scope.includedCookIds.removeAll { $0 == cookId }
                            } else {
                                scope.includedCookIds.append(cookId)
                            }
                        } label: {
                            HStack {
                                Image(systemName: scope.includedCookIds.contains(cookId) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(PitTechStyle.accent)
                                    .accessibilityHidden(true)
                                TurnInBannerText(titles[cookId] ?? cookId)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .frame(minHeight: 44)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 8)
                            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                            .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                        }
                        .accessibilityIdentifier("pro-recap-cook")
                    }
                }
                VStack(alignment: .leading, spacing: 10) {
                    TurnInBannerText("Sections", font: PitTechFont.bannerTitle)
                    Toggle("Recorded totals", isOn: Binding(get: { scope.showStats }, set: { scope.showStats = $0 }))
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("pro-recap-section-stats")
                    Toggle("Recorded milestones", isOn: Binding(get: { scope.showMilestones }, set: { scope.showMilestones = $0 }))
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("pro-recap-section-milestones")
                    Toggle("Best recorded rating", isOn: Binding(get: { scope.showBest }, set: { scope.showBest = $0 }))
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("pro-recap-section-best")
                    TextField("Recap note (optional)", text: Binding(get: { scope.note }, set: { scope.note = String($0.prefix(2000)) }), axis: .vertical)
                        .textFieldStyle(.plain)
                        .font(PitTechFont.body)
                        .padding(.horizontal, 12).padding(.vertical, 10)
                        .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(PitTechStyle.ruling, lineWidth: 1))
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("pro-recap-note")
                }
                if card == nil {
                    Text("Select at least one cook to preview this recap.")
                        .font(.subheadline)
                        .foregroundStyle(PitTechStyle.supporting)
                        .accessibilityIdentifier("pro-recap-needs-selection")
                }
                Button("Preview recap") {
                    previewing = true
                    renderFailed = false
                    rendered = nil
                    Task { await renderCard() }
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, minHeight: 54)
                .disabled(card == nil)
                .accessibilityIdentifier("pro-recap-preview-button")
            }
            .padding(20)
        }
        .accessibilityIdentifier("pro-recap-scope")
        .background(PitTechStyle.canvas.ignoresSafeArea())
        .navigationTitle("Year in Smoke")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var recapPreview: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    TurnInMetaLabel("Recap preview")
                    Text("This is exactly what sharing sends. Review it before anything leaves this device.")
                        .font(PitTechFont.body)
                        .foregroundStyle(PitTechStyle.inkMuted)
                }
                if let card {
                    if let rendered, let image = UIImage(data: rendered) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                            .accessibilityIdentifier("pro-recap-image")
                    } else if renderFailed {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("The recap image couldn't be rendered.")
                                .font(.headline)
                            Text(card.title)
                                .font(.subheadline)
                            Button("Try again") { Task { await renderCard() } }
                                .buttonStyle(.bordered)
                                .frame(minHeight: 44)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(18)
                        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                    } else {
                        ProgressView("Rendering recap…")
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .frame(minHeight: 44)
                    }
                    TurnInCard {
                        VStack(alignment: .leading, spacing: 8) {
                            TurnInBannerText(card.title)
                            ForEach(card.dishes, id: \.self) { dish in
                                Text(dish).font(PitTechFont.body)
                            }
                            ForEach(Array(card.stats.enumerated()), id: \.offset) { _, stat in
                                Text("\(stat.label): \(stat.value)").font(PitTechFont.monoReadout)
                            }
                            ForEach(Array(card.milestones.enumerated()), id: \.offset) { _, milestone in
                                Text("\(milestone.dish) · \(milestone.title)").font(PitTechFont.body)
                            }
                            if !card.note.isEmpty {
                                Text("“\(card.note)”").font(PitTechFont.body)
                            }
                            Text(card.coverageNote)
                                .font(.caption)
                                .foregroundStyle(PitTechStyle.supporting)
                            Text(card.privacyNote)
                                .font(.caption)
                                .foregroundStyle(PitTechStyle.supporting)
                        }
                    }
                    .accessibilityIdentifier("pro-recap-card")
                    if let rendered {
                        ShareLink(item: recapFile(rendered), preview: SharePreview(card.title))
                            .buttonStyle(.borderedProminent)
                            .frame(maxWidth: .infinity, minHeight: 54)
                            .accessibilityIdentifier("pro-recap-share")
                    }
                }
                Button("Back to scope") { previewing = false }
                    .buttonStyle(.bordered)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("pro-recap-back")
            }
            .padding(20)
        }
        .accessibilityIdentifier("pro-recap-preview")
        .background(PitTechStyle.canvas.ignoresSafeArea())
        .navigationTitle("Recap preview")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func renderCard() async {
        guard let card else { return }
        do {
            let paletteId = ProCustomization.effectivePalette(storedId: customization.paletteId, entitled: pro.entitlement == .entitled).id
            let content = ProRecap.shareCardContent(card, themeId: themeId, paletteId: paletteId)
            rendered = try NativeShareCardRenderer.render(PreparedNativeShareCard(content: content, photo: nil))
        } catch is CancellationError {
        } catch {
            renderFailed = true
        }
    }

    private func recapFile(_ data: Data) -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pittech-recap.png")
        try? data.write(to: url, options: .atomic)
        return url
    }
}

// MARK: - Pro customization (share-card entry)

/// The customization tiles' icon previews: one imageset per variant,
/// rendered from the same masters as the launcher icons themselves.
func iconPreviewName(_ variantId: String) -> String {
    switch variantId {
    case "ember": return "ProIconEmber"
    case "midnight": return "ProIconMidnight"
    default: return "ProIconClassic"
    }
}

struct ProCustomizationView: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(ProCustomizationStore.self) private var customization
    let entry: ProPlacement

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    SectionTitle(title: entry.title, subtitle: entry.preview)
                    Text("Preview every look before applying. Pro looks apply while PitTech Pro is active; without Pro, PitTech renders the readable Classic treatment and keeps your stored selections.")
                        .font(.subheadline)
                        .foregroundStyle(PitTechStyle.supporting)
                    themeSection
                    paletteSection
                    profileSection
                    iconSection
                }
                .padding(20)
            }
            .accessibilityIdentifier("pro-customization")
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

    private var themeSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            TurnInBannerText("Share-card themes")
            ForEach(ProCustomization.cardThemes, id: \.id) { theme in
                let previewed = customization.previewThemeId ?? customization.themeId
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        customization.previewThemeId = theme.id
                    } label: {
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 8)
                                .fill(Color(proHex: theme.background))
                                .frame(width: 56, height: 40)
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(proHex: theme.accent), lineWidth: 2))
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(theme.name)
                                    .font(.subheadline)
                                    .foregroundStyle(PitTechStyle.ink)
                                if theme.isPro {
                                    TurnInProTag()
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if customization.themeId == theme.id {
                                Text("Applied")
                                    .font(PitTechFont.monoMeta)
                                    .foregroundStyle(PitTechStyle.attentionText)
                                    .accessibilityIdentifier("pro-theme-applied-\(theme.id)")
                            }
                        }
                        .frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("pro-theme-\(theme.id)")
                    if previewed == theme.id {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 8) {
                                Text("Ink")
                                    .font(.caption)
                                    .foregroundStyle(Color(proHex: theme.ink))
                                Text("Supporting")
                                    .font(.caption)
                                    .foregroundStyle(Color(proHex: theme.supporting))
                                Text("Accent")
                                    .font(.caption)
                                    .foregroundStyle(Color(proHex: theme.accent))
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(proHex: theme.background), in: RoundedRectangle(cornerRadius: 12))
                            Button("Apply \(theme.name)") { customization.applyTheme(theme.id) }
                                .buttonStyle(.borderedProminent)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("pro-theme-apply-\(theme.id)")
                        }
                    }
                }
                .padding(16)
                .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
            }
        }
    }

    private var paletteSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            TurnInBannerText("Chart palettes")
            Text("Every palette is contrast-verified against its background.")
                .font(.caption)
                .foregroundStyle(PitTechStyle.supporting)
            ForEach(ProCustomization.chartPalettes, id: \.id) { palette in
                let previewed = customization.previewPaletteId ?? customization.paletteId
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        customization.previewPaletteId = palette.id
                    } label: {
                        HStack(spacing: 12) {
                            HStack(spacing: 4) {
                                ForEach(palette.colors, id: \.self) { hex in
                                    Circle()
                                        .fill(Color(proHex: hex))
                                        .frame(width: 20, height: 20)
                                }
                            }
                            .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(palette.name)
                                    .font(.subheadline)
                                    .foregroundStyle(PitTechStyle.ink)
                                if palette.isPro {
                                    TurnInProTag()
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if customization.paletteId == palette.id {
                                Text("Applied")
                                    .font(PitTechFont.monoMeta)
                                    .foregroundStyle(PitTechStyle.attentionText)
                                    .accessibilityIdentifier("pro-palette-applied-\(palette.id)")
                            }
                        }
                        .frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("pro-palette-\(palette.id)")
                    if previewed == palette.id {
                        Button("Apply \(palette.name)") { customization.applyPalette(palette.id) }
                            .buttonStyle(.borderedProminent)
                            .frame(minHeight: 44)
                            .accessibilityIdentifier("pro-palette-apply-\(palette.id)")
                    }
                }
                .padding(16)
                .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
            }
        }
    }

    private var profileSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            TurnInBannerText("Public profile presentation")
            Text("Restrained styling for named public cookbooks only. Anonymous publications never carry Pro styling or identity.")
                .font(.caption)
                .foregroundStyle(PitTechStyle.supporting)
            ForEach(ProCustomization.profileAccents, id: \.id) { accent in
                let previewed = customization.previewProfileAccentId ?? customization.profileAccentId
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        customization.previewProfileAccentId = accent.id
                    } label: {
                        HStack(spacing: 12) {
                            Circle()
                                .fill(Color(proHex: accent.color))
                                .frame(width: 20, height: 20)
                                .accessibilityHidden(true)
                            Text(accent.name)
                                .font(.subheadline)
                                .foregroundStyle(PitTechStyle.ink)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if customization.profileAccentId == accent.id {
                                Text("Applied")
                                    .font(PitTechFont.monoMeta)
                                    .foregroundStyle(PitTechStyle.attentionText)
                                    .accessibilityIdentifier("pro-profile-applied-\(accent.id)")
                            }
                        }
                        .frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("pro-profile-\(accent.id)")
                    if previewed == accent.id {
                        Button("Apply \(accent.name)") {
                            customization.applyProfile(accentId: accent.id, layout: customization.previewProfileLayout ?? customization.profileLayout)
                        }
                        .buttonStyle(.borderedProminent)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("pro-profile-apply-\(accent.id)")
                    }
                }
                .padding(16)
                .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
            }
            VStack(alignment: .leading, spacing: 8) {
                TurnInBannerText("Cookbook layout")
                Picker("Cookbook layout", selection: Binding(
                    get: { customization.previewProfileLayout ?? customization.profileLayout },
                    set: { customization.previewProfileLayout = $0 }
                )) {
                    Text("Standard").tag("standard")
                    Text("Compact").tag("compact")
                }
                .pickerStyle(.segmented)
                .frame(minHeight: 44)
                .accessibilityIdentifier("pro-profile-layout")
                Button("Apply layout") {
                    customization.applyProfile(accentId: customization.previewProfileAccentId ?? customization.profileAccentId, layout: customization.previewProfileLayout ?? customization.profileLayout)
                }
                .buttonStyle(.bordered)
                .frame(minHeight: 44)
                .accessibilityIdentifier("pro-profile-apply-layout")
            }
            .padding(16)
            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
        }
    }

    private var iconSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            TurnInBannerText("App icons")
            ForEach(ProCustomization.iconVariants, id: \.id) { variant in
                let previewed = customization.previewIconId ?? customization.iconId
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        customization.previewIconId = variant.id
                    } label: {
                        HStack(spacing: 12) {
                            // Slice 7: the tiles show the real icon art —
                            // the same compositions the launcher ships
                            // (primary + the two Pro alternate icon sets).
                            Image(iconPreviewName(variant.id))
                                .resizable()
                                .frame(width: 44, height: 44)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(variant.name)
                                    .font(.subheadline)
                                    .foregroundStyle(PitTechStyle.ink)
                                if variant.isPro {
                                    TurnInProTag()
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if customization.iconId == variant.id {
                                Text("Applied")
                                    .font(PitTechFont.monoMeta)
                                    .foregroundStyle(PitTechStyle.attentionText)
                                    .accessibilityIdentifier("pro-icon-applied-\(variant.id)")
                            }
                        }
                        .frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("pro-icon-\(variant.id)")
                    if previewed == variant.id {
                        Button("Apply \(variant.name)") { customization.applyIcon(variant.id) }
                            .buttonStyle(.borderedProminent)
                            .frame(minHeight: 44)
                            .accessibilityIdentifier("pro-icon-apply-\(variant.id)")
                    }
                }
                .padding(16)
                .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
            }
            if let notice = customization.iconNotice {
                // Frame `23-customization`: an icon-apply result rides
                // the shell as a flag strip, not a loose line of text.
                TurnInStatusStrip(text: notice, accent: PitTechStyle.flag)
                    .accessibilityIdentifier("pro-icon-notice")
            }
        }
    }
}

private extension Color {
    init(proHex hex: String) {
        let clean = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        let rgb = Int(clean, radix: 16) ?? 0
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255
        )
    }
}

// MARK: - Pro hub (More entry)

struct ProMoreHubView: View {
    @Environment(ProSubscriptionCoordinator.self) private var pro
    let entry: ProPlacement

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // TURN-IN (Slice 2, Area 02 `pro-hub`): the hub rows
                    // take the entry-row composition — ◆, banner name,
                    // PRO tag, preview, chevron — on laminated cards.
                    VStack(alignment: .leading, spacing: 6) {
                        TurnInMetaLabel("PitTech Pro")
                        Text(entry.preview)
                            .font(PitTechFont.body)
                            .foregroundStyle(PitTechStyle.inkMuted)
                    }
                    Text("Your Pro library: personal analysis, advanced curves, recaps, and customization — all from cooks recorded on this device.")
                        .font(.subheadline)
                        .foregroundStyle(PitTechStyle.supporting)
                    // TURN-IN (Slice 7, Area 07 frame `10-pro-hub` — A7 is
                    // canonical for Pro): the hub carries the subscription
                    // itself — status plus the same App Store manage
                    // destination the paywall's `pro-manage` opens. The hub
                    // only renders for an entitled account (the gate routes
                    // it), so the status line is a fact, not a promise.
                    TurnInCard {
                        VStack(alignment: .leading, spacing: 4) {
                            TurnInBannerText("Subscription")
                            Text("Active on this account")
                                .font(PitTechFont.metaLabel)
                                .tracking(PitTechFont.metaLabelTracking)
                                .foregroundStyle(PitTechStyle.inkMuted)
                            Link("Manage subscription", destination: URL(string: "https://apps.apple.com/account/subscriptions")!)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("pro-hub-manage")
                        }
                    }
                    ForEach(proHubEntries, id: \.entry) { item in
                        Button { pro.openPro(item.entry) } label: {
                            HStack(spacing: 14) {
                                Text("◆").font(PitTechFont.body).foregroundStyle(PitTechStyle.marigold).accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(spacing: 8) {
                                        TurnInBannerText(item.entry.title)
                                        TurnInProTag()
                                    }
                                    Text(item.entry.preview)
                                        .font(PitTechFont.body)
                                        .foregroundStyle(PitTechStyle.inkMuted)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(PitTechStyle.supporting)
                                    .accessibilityHidden(true)
                            }
                            .frame(minHeight: 44)
                            .padding(18)
                            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                            .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                        }
                        .accessibilityIdentifier("pro-hub-\(item.entry.rawValue)")
                    }
                    NavigationLink {
                        ProRecapBuilderView()
                    } label: {
                        HStack(spacing: 14) {
                            Text("◆").font(PitTechFont.body).foregroundStyle(PitTechStyle.marigold).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 4) {
                                TurnInBannerText("Year in Smoke")
                                Text("Build a scoped, shareable recap from this library.")
                                    .font(PitTechFont.body)
                                    .foregroundStyle(PitTechStyle.inkMuted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(PitTechStyle.supporting)
                                .accessibilityHidden(true)
                        }
                        .frame(minHeight: 44)
                        .padding(18)
                        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
                    }
                    .accessibilityIdentifier("pro-hub-recap")
                }
                .padding(20)
            }
            .accessibilityIdentifier("pro-more-hub")
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

    private struct HubEntry {
        let entry: ProPlacement
    }

    private var proHubEntries: [HubEntry] {
        [.init(entry: .analysis), .init(entry: .curves), .init(entry: .benchmarks), .init(entry: .themes)]
    }
}
