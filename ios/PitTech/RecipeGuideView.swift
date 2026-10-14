import SwiftUI

struct RecipeGuideControl: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var choosing = false
    @State private var error: String?
    private var state: GuidePresentation { store.guideState(cookId) }
    private var activeReminders: Bool { store.library.reminderRules.contains { $0.objectValue?["cookId"]?.stringValue == cookId && $0.objectValue?["enabled"]?.boolValue == true } }
    var body: some View {
        if store.dishRecords(cookId).contains(where: { store.recipeSource($0.id) != nil }) {
            VStack(alignment: .leading, spacing: 8) {
                Toggle("Show guide", isOn: Binding(get: { state.visible }, set: { value in
                    var copy = state; copy.visible = value
                    do { try store.saveGuideState(cookId, copy) } catch { self.error = error.localizedDescription }
                })).frame(minHeight: 44).accessibilityIdentifier("show-guide")
                if !state.visible && activeReminders { Text("Reminders active · use Reminders to change them").font(.caption).accessibilityIdentifier("guide-reminders-active") }
                if state.visible { Button("Guide dishes & channels") { choosing = true }.accessibilityIdentifier("guide-scope") }
                if let error { Text(error).foregroundStyle(.red) }
            }.task { do { try store.ensureGuides(cookId) } catch { self.error = error.localizedDescription } }
                .sheet(isPresented: $choosing) { NavigationStack { RecipeGuideScope(cookId: cookId) }.modifier(ScreenshotTextSize()) }
        }
    }
}
private struct RecipeGuideScope: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var error: String?
    private var state: GuidePresentation { store.guideState(cookId) }
    private func save(_ value: GuidePresentation) { do { try store.saveGuideState(cookId, value) } catch { self.error = error.localizedDescription } }
    private func channels(_ guide: NativeDishGuide) -> [[String: JSONValue]] {
        var seen = Set<String>()
        return (guide.guide.story.objectValue?["readings"]?.arrayValue ?? []).compactMap(\.objectValue).filter { seen.insert(RecipeGuideProjection.channel(guide.id, $0)).inserted }
    }
    private func eligible(_ dish: String, role: String) -> [TemperatureSample] {
        var seen = Set<String>()
        return store.readings(for: cookId).filter { RecipeGuideProjection.role($0.measurementType) == role && ($0.dishId == dish || $0.dishId == nil && role != "food") && seen.insert($0.seriesId).inserted }
    }
    var body: some View {
        Form {
            Text("Recorded facts and instructions stay separate from your actual log. Different weights or methods are not used to predict finish time.")
            ForEach(store.guides(cookId)) { item in
                Section(item.name) {
                    Toggle("Include " + item.name, isOn: Binding(get: { state.dishIds == nil || state.dishIds!.contains(item.id) }, set: { value in
                        var copy = state; var selected = Set(copy.dishIds ?? store.guides(cookId).map(\.id))
                        if value { selected.insert(item.id) } else { selected.remove(item.id) }; copy.dishIds = selected.sorted(); save(copy)
                    })).accessibilityIdentifier("guide-dish-" + item.id)
                    ForEach(Array(channels(item).enumerated()), id: \.offset) { _, row in
                        let key = RecipeGuideProjection.channel(item.id, row), role = RecipeGuideProjection.role(row["measurementType"]?.stringValue ?? "food")
                        Picker("Author · \(row["probeName"]?.stringValue ?? "Channel") · \(role)", selection: Binding(get: { state.channels[key] ?? "" }, set: { value in
                            var copy = state; if value.isEmpty { copy.channels.removeValue(forKey: key) } else { copy.channels[key] = value }; save(copy)
                        })) {
                            Text("Separate reference channel").tag("")
                            ForEach(eligible(item.id, role: role), id: \.seriesId) { reading in
                                Text("\(reading.probeName) · \(reading.measurementType) · \((reading.probeId ?? reading.id).suffix(6))").tag(reading.seriesId)
                            }
                        }
                    }
                }
            }
            if let error { Text(error).foregroundStyle(.red) }
        }.navigationTitle("Guide dishes & channels").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
    }
}
struct RecipeGuideMilestones: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    var compact = false
    var onFullGuide: () -> Void = {}
    private var state: GuidePresentation { store.guideState(cookId) }
    var body: some View {
        if state.visible {
            ForEach(store.guides(cookId).filter { state.dishIds == nil || state.dishIds!.contains($0.id) }) { guide in
                RecipeDishGuide(cookId: cookId, item: guide, compact: compact, onFullGuide: onFullGuide)
            }
        }
    }
}
private struct RecipeDishGuide: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    let item: NativeDishGuide
    let compact: Bool
    var onFullGuide: () -> Void
    @State private var count = 50
    private var events: [JSONValue] { RecipeReferenceTimeline.events(item.guide.snapshot) }
    private var anchor: Double? { store.guideAnchor(cookId, dishId: item.id) }
    private var differs: Bool { item.working.draft != item.guide.snapshot.draft || item.working.instructions != item.guide.snapshot.instructions }
    private func paired(_ source: JSONValue, index: Int) -> String {
        guard let action = source.objectValue?["action"]?.stringValue else { return "Your cook · no matching action recorded" }
        let ordinal = events.prefix(index).filter { $0.objectValue?["action"]?.stringValue == action }.count
        let actual = store.library.actions(for: cookId).filter { $0.action == action && ($0.dishIds.isEmpty || $0.dishIds.contains(item.id)) }.sorted { $0.at < $1.at }
        guard ordinal < actual.count else { return "Your cook · no matching action recorded" }
        return "Your cook · \(CookStore.actionLabel(action)) · \(RecipeReferenceTimeline.relative(anchor.map { actual[ordinal].at - $0 }))"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Guide · " + item.name).font(.headline).accessibilityIdentifier("guide-heading-" + item.id)
            if differs { Text("Your setup or instructions differ from the source. Recorded timing is context, not a finish prediction.").font(.caption) }
            DisclosureGroup("Instructions · \(item.working.instructions.count)") {
                ForEach(item.working.instructions.indices, id: \.self) { index in
                    let step = item.working.instructions[index].objectValue ?? [:]
                    Text("Instruction · \(step["title"]?.stringValue ?? "")\((step["details"]?.stringValue ?? "").isEmpty ? "" : ": " + (step["details"]?.stringValue ?? ""))").font(.subheadline)
                }
            }
            if anchor == nil { Text("Record actual Food on for this dish to align the guide. Preparation offsets are retained.").font(.caption) }
            ForEach(Array(events.prefix(compact ? 3 : count).enumerated()), id: \.offset) { index, event in
                let row = event.objectValue ?? [:]
                VStack(alignment: .leading, spacing: 4) {
                    Text("Author · \(row["title"]?.stringValue ?? "Note") · \(RecipeReferenceTimeline.relative(row["offsetMillis"]?.numberValue))").font(.subheadline)
                    Text(paired(event, index: index)).font(.caption)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            if compact && events.count > 3 { Button("View all \(events.count) milestones in Timeline", action: onFullGuide) }
            if !compact && events.count > count { Button("Show more recorded milestones (\(events.count - count) remaining)") { count += 50 } }
            if events.isEmpty { Text("No recorded milestones. Available instructions still work.").font(.caption) }
            if (item.guide.story.objectValue?["readings"]?.arrayValue ?? []).isEmpty { Text("No source temperatures recorded. No reference curve is drawn.").font(.caption) }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 12)).accessibilityIdentifier("guide-reference-" + item.id)
    }
}
