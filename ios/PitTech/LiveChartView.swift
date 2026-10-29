import SwiftUI

/// What the Live board hands the full-screen chart: the lead dish whose
/// probe the pace follows, and the stated finish range's latest edge
/// (the shaded hours ahead run to it).
struct LiveChartContext: Identifiable {
    let id = UUID()
    let leadDishId: String?
    let etaLatestUtcMillis: Int64?
}

/// Flowing Slice 2, Spread 02: the full-screen chart. It hosts the SAME
/// CookTemperatureChart engine the Charts tab runs, with the live extras
/// switched on — stall band, shaded hours ahead, the same-cut ghost
/// driven through the chart's own comparison machinery — plus the
/// pinned readout, range presets, and event strip the spread calls for.
struct LiveChartView: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    let leadDishId: String?
    let etaLatestUtcMillis: Int64?

    @State private var scrubMinutes: Double?
    @State private var presetKey = 0
    @State private var presetSpan: Double?
    @State private var presetStart: Double?
    @State private var presetFollow = true

    private var cook: CookRecord? { store.library.cooks.first { $0.id == cookId } }
    private var readings: [TemperatureSample] { store.readings(for: cookId) }

    /// The readout's anchor: the lead dish's food-on, else the cook's
    /// start — the same basis the chart's elapsed axis uses.
    private var anchorMillis: Int64 {
        if let leadDishId, let foodOn = store.library.actions(for: cookId)
            .filter({ $0.action == "food_on" && ($0.dishIds.isEmpty || $0.dishIds.contains(leadDishId)) })
            .map({ Int64($0.at) }).min() { return foodOn }
        if let first = store.library.actions(for: cookId).filter({ $0.action == "food_on" }).map({ Int64($0.at) }).min() { return first }
        return cook?.startedAtUtcMillis ?? 0
    }

    private func series(dishId: String?, from rows: [TemperatureSample], anchor: Int64, types: Set<String>) -> [PaceReading] {
        rows.filter { (dishId == nil || $0.dishId == dishId) && types.contains($0.measurementType) && $0.qualityStatus == "valid" }
            .map { PaceReading(atUtcMillis: Int64($0.measuredAt.timeIntervalSince1970 * 1_000) - anchor, fahrenheit: $0.unit.contains("C") ? $0.value * 9 / 5 + 32 : $0.value) }
            .sorted { $0.atUtcMillis < $1.atUtcMillis }
    }

    private var probeSeries: [PaceReading] { series(dishId: leadDishId, from: readings, anchor: anchorMillis, types: ["food", "food_probe"]) }
    private var pitSeries: [PaceReading] { series(dishId: nil, from: readings, anchor: anchorMillis, types: ["pit", "pit_ambient", "ambient_temperature"]) }

    private var ghostInfo: (cookId: String, dishId: String, label: String, series: [PaceReading])? {
        guard let leadDishId else { return nil }
        let cut = store.library.dishes.compactMap(\.objectValue).first { $0["id"]?.stringValue == leadDishId }?["cut"]?.stringValue
        let archive = store.insightsArchive()
        guard let ghost = LiveGuidance.ghostCook(archive: archive, currentCookId: cookId, cut: cut),
              let ghostDish = archive.dishes.first(where: { $0.cookId == ghost.id && $0.cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() })
        else { return nil }
        let ghostAnchor = archive.events.filter { $0.cookId == ghost.id && $0.action == "food_on" && ($0.dishIds.isEmpty || $0.dishIds.contains(ghostDish.id)) }.map { Int64($0.at) }.min()
            ?? Int64(ghost.startedAtUtcMillis)
        let rows = store.readings(for: ghost.id)
        return (ghost.id, ghostDish.id, "last " + (cut?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? "cook"),
                series(dishId: ghostDish.id, from: rows, anchor: ghostAnchor, types: ["food", "food_probe"]))
    }

    private func display(_ fahrenheit: Double) -> Double { store.temperatureUnit == "C" ? (fahrenheit - 32) * 5 / 9 : fahrenheit }
    private func elapsedLabel(_ minutes: Double) -> String {
        let total = Int(max(0, minutes))
        return total >= 60 ? "\(total / 60)h \(total % 60)m in" : "\(total)m in"
    }
    private func applyPreset(span: Double?, start: Double?, follow: Bool) {
        presetSpan = span; presetStart = start; presetFollow = follow; presetKey += 1
        if follow { scrubMinutes = nil }
    }

    var body: some View {
        let probe = probeSeries
        let pit = pitSeries
        let ghost = ghostInfo
        let latestMinutes = Double(max(probe.last?.atUtcMillis ?? 0, pit.last?.atUtcMillis ?? 0)) / 60_000.0
        let nowMinutes = Double(Int64(Date.now.timeIntervalSince1970 * 1_000) - anchorMillis) / 60_000.0
        let cursor = scrubMinutes ?? latestMinutes
        let probeAt = LiveGuidance.interpolate(probe, elapsedMinutes: cursor)
        let pitAt = LiveGuidance.interpolate(pit, elapsedMinutes: cursor)
        let spans = CookPaceEngine.stallSpans(probe)
        let inStall = spans.contains { cursor * 60_000 >= Double($0.startUtcMillis) && cursor * 60_000 <= Double($0.endUtcMillis) }
        let ghostDelta = ghost.map { LiveGuidance.ghostDeltaMinutes(current: probe, ghost: $0.series, cursorElapsedMinutes: cursor) } ?? nil
        let stripEvents: [(label: String, atMinutes: Double)] = {
            var items: [(String, Double)] = store.library.actions(for: cookId)
                .filter { (leadDishId == nil || $0.dishIds.isEmpty || $0.dishIds.contains(leadDishId!)) && ["food_on", "spritz", "wrap", "remove", "rest_start", "hold_start"].contains($0.action) }
                .map { action in
                    let label = switch action.action { case "food_on": "Food on"; case "spritz": "Spritz"; case "wrap": "Wrap"; case "remove": "Off"; case "rest_start": "Rest"; default: "Hold" }
                    return (label, (Double(Int64(action.at)) - Double(anchorMillis)) / 60_000.0)
                }
            items += store.events(for: cookId).filter { $0.eventType == "note" && $0.title == "Conditions changed" }
                .map { ("Conditions changed", ($0.occurredAt.timeIntervalSince1970 * 1_000 - Double(anchorMillis)) / 60_000.0) }
            return items.sorted { $0.1 < $1.1 }
        }()
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    // 2.2: the readout is pinned at the top and recomputes at the cursor.
                    TurnInCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(Date(timeIntervalSince1970: Double(anchorMillis) / 1_000 + cursor * 60).formatted(date: .omitted, time: .shortened) + " · " + elapsedLabel(cursor) + (scrubMinutes == nil ? " · latest" : ""))
                                .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                            Text([
                                probeAt.map { "Probe \(Int(display($0).rounded()))°" },
                                pitAt.map { "Pit \(Int(display($0).rounded()))°" },
                                inStall ? "In the stall band" : nil,
                            ].compactMap { $0 }.joined(separator: " · ").ifEmpty("No readings at this point yet"))
                                .font(PitTechFont.bodyEmphasis)
                            if let ghost {
                                Text(ghostDelta.map { delta in
                                    delta == 0 ? "Even with \(ghost.label) at this point"
                                        : delta > 0 ? "+\(delta) min vs \(ghost.label) at this point"
                                        : "\(delta) min vs \(ghost.label) at this point"
                                } ?? "No comparison with \(ghost.label) at this point")
                                    .font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                                    .accessibilityIdentifier("live-chart-ghost-delta")
                            }
                        }
                    }
                    // Contain: the card's identifier must not swallow the
                    // ghost-delta child's own identifier (the live-zone
                    // card had the same defect — see CookDetailView).
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("live-chart-readout")
                    // 2.5: range presets over the same axis.
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            Button("Whole cook") { applyPreset(span: nil, start: nil, follow: true) }.buttonStyle(.bordered).accessibilityIdentifier("live-chart-preset-whole")
                            Button("Last 2h") { applyPreset(span: 120, start: max(0, nowMinutes - 120), follow: false) }.buttonStyle(.bordered).accessibilityIdentifier("live-chart-preset-last2h")
                            Button("Stall") {
                                if let span = spans.first { applyPreset(span: max(span.durationMinutes, 30), start: Double(span.startUtcMillis) / 60_000.0, follow: false) }
                            }.buttonStyle(.bordered).disabled(spans.isEmpty).accessibilityIdentifier("live-chart-preset-stall")
                            Button("Since wrap") {
                                if let wrap = stripEvents.first(where: { $0.label == "Wrap" }) { applyPreset(span: max(nowMinutes - wrap.atMinutes, 30), start: wrap.atMinutes, follow: false) }
                            }.buttonStyle(.bordered).disabled(!stripEvents.contains { $0.label == "Wrap" }).accessibilityIdentifier("live-chart-preset-wrap")
                        }
                    }
                    // 2.5: the event strip shares the axis — tapping an event moves the window to it.
                    if !stripEvents.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(Array(stripEvents.enumerated()), id: \.offset) { _, event in
                                    Button("\(event.label) · \(elapsedLabel(event.atMinutes).replacingOccurrences(of: " in", with: ""))") {
                                        applyPreset(span: 120, start: max(0, event.atMinutes), follow: false)
                                    }.buttonStyle(.bordered)
                                }
                            }
                        }.accessibilityIdentifier("live-chart-events")
                    }
                    CookTemperatureChart(
                        cookId: cookId,
                        onMarker: { _ in },
                        liveExtras: true,
                        ghost: ghost.map { (currentDishId: leadDishId ?? "", cookId: $0.cookId, dishId: $0.dishId) },
                        etaLatestMinutes: etaLatestUtcMillis.map { Double($0 - anchorMillis) / 60_000.0 },
                        onScrubChange: { scrubMinutes = $0 },
                        presetKey: presetKey,
                        presetSpanMinutes: presetSpan,
                        presetStartMinutes: presetStart,
                        presetFollowLatest: presetFollow)
                    Text(ghost.map { "The second cook is \($0.label), aligned at food-on. The grey hours ahead run to the end of the stated finish range." }
                        ?? "The grey hours ahead run to the end of the stated finish range, when a range is stated.")
                        .font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                }
                .padding(20)
            }
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle(cook.map { "Chart · \($0.title)" } ?? "Chart")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.accessibilityIdentifier("live-chart-close") } }
        }
    }
}

private extension String {
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}
