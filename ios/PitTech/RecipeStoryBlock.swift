import SwiftUI

/// Spread 06's story block (Flowing Slice 3, spec §4.3/§4.5): a recipe
/// opens with the story of the cook that earned it — provenance line,
/// the cook in four numbers, a sparkline of its curve with the door to
/// the full chart, its photos in the order they happened, and the
/// cook's own keep / change lessons — with Cook This Again as the
/// primary door. Rendered only when the §4.3 payload exists; a recipe
/// with no source cook never instantiates this view and its detail
/// renders exactly as before. Mirrors Android's RecipeStoryBlock.
struct RecipeStoryBlock: View {
    @Environment(CookStore.self) private var store
    let provenance: RecipeProvenance
    let onCookAgain: () -> Void
    /// Slice 5 (§4.4): plan-this beside Cook this again — the second
    /// door into the serve planner from a recipe's story.
    var onPlanThis: () -> Void = {}
    @State private var chartContext: LiveChartContext?

    /// "From your October 12, 2026 cook — Austin XL · 250° · Hickory ·
    /// 9.4 lb" — the date always carries its year (Michael's rule), and
    /// only the parts the cook actually recorded join the line.
    private var provenanceLine: String {
        var parts: [String] = []
        if let pitName = provenance.pitName { parts.append(pitName) }
        if let setpoint = provenance.setpointF { parts.append("\(Int(setpoint))°") }
        if let wood = provenance.wood { parts.append(wood) }
        if let weight = provenance.weightText { parts.append(weight) }
        let lead = "From your \(provenance.cookedDateText) cook"
        return parts.isEmpty ? lead : lead + " — " + parts.joined(separator: " · ")
    }

    /// Total / Stall / Wrapped at / Rest — a cell exists only for a
    /// number the source cook actually produced.
    private var stats: [(label: String, value: String)] {
        func duration(_ minutes: Double) -> String { "\(Int(minutes) / 60)H \(Int(minutes) % 60)M" }
        var cells: [(String, String)] = []
        if let total = provenance.totalMinutes { cells.append(("Total", duration(total))) }
        if let stall = provenance.stallMinutes { cells.append(("Stall", duration(stall))) }
        if let wrapped = provenance.wrappedAtF { cells.append(("Wrapped at", "\(Int(wrapped))°")) }
        if let rest = provenance.restMinutes { cells.append(("Rest", duration(rest))) }
        return cells
    }

    private func roleLabel(_ role: ProvenancePhotoRole) -> String {
        switch role {
        case .onPit: return "On the pit"
        case .atWrap: return "At the wrap"
        case .sliced: return "Sliced"
        case .other: return "Photo"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(provenanceLine).font(PitTechFont.bodyEmphasis).accessibilityIdentifier("recipe-story-line")
            NavigationLink("Open the cook") { CookDetailView(cookId: provenance.sourceCookId) }
                .accessibilityIdentifier("recipe-story-open-cook")
            if !stats.isEmpty {
                HStack(spacing: 8) { ForEach(stats, id: \.label) { cell in TurnInScoreCell(label: cell.label, value: cell.value) } }
            }
            if provenance.curve.count >= 2 {
                TurnInMetaLabel("The cook's curve")
                RecipeStorySparkline(points: provenance.curve)
                    .frame(height: 64)
                    .accessibilityIdentifier("recipe-story-curve")
                Button("Full chart") { chartContext = LiveChartContext(leadDishId: provenance.sourceDishId, etaLatestUtcMillis: nil) }
                    .accessibilityIdentifier("recipe-story-full-chart")
            }
            ForEach(Array(provenance.photos.prefix(3).enumerated()), id: \.offset) { _, photo in
                if let row = store.library.photos.first(where: { $0.objectValue?["id"]?.stringValue == photo.photoId })?.objectValue,
                   let path = row["archivePath"]?.stringValue, let image = store.photoPreview(path) {
                    VStack(alignment: .leading, spacing: 4) {
                        Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 180)
                            .accessibilityLabel(roleLabel(photo.role))
                        Text(roleLabel(photo.role)).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    }
                }
            }
            if let note = provenance.note, !note.isEmpty {
                Text(note).font(PitTechFont.body).italic()
            }
            if !provenance.keepChips.isEmpty {
                TurnInMetaLabel("Keep")
                TurnInFlowLayout(spacing: 8) { ForEach(provenance.keepChips, id: \.self) { chip in chipView(chip) } }
            }
            if !provenance.changeChips.isEmpty {
                TurnInMetaLabel("Change next time")
                TurnInFlowLayout(spacing: 8) { ForEach(provenance.changeChips, id: \.self) { chip in chipView(chip) } }
            }
            HStack(spacing: 10) {
                Button("Cook this again", action: onCookAgain)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .accessibilityIdentifier("recipe-story-cook-again")
                Button("Plan this", action: onPlanThis)
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .accessibilityIdentifier("recipe-story-plan-this")
            }
        }
        .accessibilityIdentifier("recipe-story")
        .sheet(item: $chartContext) { context in
            LiveChartView(cookId: provenance.sourceCookId, leadDishId: context.leadDishId, etaLatestUtcMillis: context.etaLatestUtcMillis)
        }
    }

    private func chipView(_ text: String) -> some View {
        Text(text)
            .font(PitTechFont.body)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 999))
            .overlay(RoundedRectangle(cornerRadius: 999).stroke(PitTechStyle.ruling, lineWidth: 1))
    }
}

/// The source cook's curve as a sparkline: the §4.3 payload's
/// downsampled points, normalized into the frame, stroked in ember.
private struct RecipeStorySparkline: View {
    let points: [ProvenanceCurvePoint]
    var body: some View {
        GeometryReader { geo in
            let minX = points.map(\.offsetMinutes).min() ?? 0, maxX = points.map(\.offsetMinutes).max() ?? 1
            let minY = points.map(\.fahrenheit).min() ?? 0, maxY = points.map(\.fahrenheit).max() ?? 1
            Path { path in
                for (index, point) in points.enumerated() {
                    let x = (point.offsetMinutes - minX) / max(1, maxX - minX) * geo.size.width
                    let y = geo.size.height - (point.fahrenheit - minY) / max(1, maxY - minY) * geo.size.height
                    if index == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
                }
            }
            .stroke(PitTechStyle.ember, lineWidth: 2)
        }
    }
}
