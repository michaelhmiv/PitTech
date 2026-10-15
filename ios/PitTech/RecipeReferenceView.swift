import SwiftUI

struct RecipeReferenceLinks: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    var body: some View {
        ForEach(store.dishRecords(cookId).filter { store.recipeSource($0.id) != nil }) { dish in
            NavigationLink("Previous cook · " + dish.name) { RecipeReferenceView(cookId: cookId, dishId: dish.id) }
        }
    }
}
struct RecipeReferenceView: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    let dishId: String
    @State private var revisionId = ""
    @State private var actionsOnly = true
    private var source: [String: JSONValue]? { store.recipeSource(dishId) }
    private var versions: [[String: JSONValue]] { store.library.recipeRevisions.compactMap(\.objectValue).filter { $0["recipeId"] == source?["recipeId"] }.sorted { ($0["revisionNumber"]?.numberValue ?? 0) > ($1["revisionNumber"]?.numberValue ?? 0) } }
    private var snapshot: RecipeSnapshot? { try? store.recipeSnapshot(revisionId) }
    var body: some View {
        List {
            Section {
                Picker("Historical version", selection: $revisionId) { ForEach(versions.indices, id: \.self) { index in let row = versions[index]
                    if let id = row["id"]?.stringValue { Text("Version \(Int(row["revisionNumber"]?.numberValue ?? 0))\(id == source?["recipeRevisionId"]?.stringValue ? " · used in this meal" : "")\(store.recipe(row["recipeId"]?.stringValue ?? "")?["currentRevisionId"]?.stringValue == id ? " · preferred" : "")").tag(id) }
                } }
            }
            if let snapshot {
                Section("Previous cook · saved observations") {
                    if let rating = snapshot.rating { Text("Recorded rating: \(rating)") }
                    if !snapshot.resultNotes.isEmpty { Text(snapshot.resultNotes) }
                    ForEach(snapshot.targets.indices, id: \.self) { index in let target = snapshot.targets[index].objectValue ?? [:]
                        Text("Previous target · \(target["type"]?.stringValue ?? ""): \(target["value"]?.numberValue ?? 0) \(target["unit"]?.stringValue ?? "")")
                    }
                    ForEach(RecipeReferenceTimeline.heating(snapshot).indices, id: \.self) { index in let interval = RecipeReferenceTimeline.heating(snapshot)[index]
                        Text("Heating interval \(index + 1) · \(referenceTime(interval.startedAt, snapshot: snapshot)) → \(interval.endedAt.map { referenceTime($0, snapshot: snapshot) } ?? "end not recorded")")
                    }
                    Toggle("Major actions only", isOn: $actionsOnly)
                    let events = RecipeReferenceTimeline.events(snapshot).filter { !actionsOnly || CookNoteMetadata.actions.contains($0.objectValue?["action"]?.stringValue ?? "") }
                    if events.isEmpty { Text("No saved observations in this filter.") }
                    ForEach(events.indices, id: \.self) { index in HistoricalObservationView(event: events[index], relativeTiming: snapshot.publicSource?.relativeTiming == true, anchor: snapshot.smokingAnchorUtcMillis.map(Double.init)) }
                    RecipeAssetGallery(revisionId: revisionId)
                    if let original = store.recipeRevision(revisionId)?["sourceCookId"]?.stringValue, store.library.cooks.contains(where: { $0.id == original }) {
                        NavigationLink("Open original meal context") { CookDetailView(cookId: original) }
                    } else { Text("Original meal unavailable. Saved reference content remains available.").font(.caption) }
                }
                Section("This cook · actual actions") {
                    let actions = store.library.actions(for: cookId).filter { CookNoteMetadata.actions.contains($0.action) && ($0.dishIds.isEmpty || $0.dishIds.contains(dishId)) }
                    let anchor = actions.filter { $0.action == "food_on" }.map(\.at).min()
                    if actions.isEmpty { Text("No actions recorded for this dish yet.") }
                    ForEach(actions, id: \.id) { action in Text("\(CookStore.actionLabel(action.action)) · \(RecipeReferenceTimeline.relative(anchor.map { action.at - $0 }))") }
                    NavigationLink("Compare recorded temperature channels") { CookTemperatureChart(cookId: cookId) { _ in } }
                }
                Section("Other attempts") {
                    let dishIds = Set(store.library.dishRecipeSources.compactMap(\.objectValue).filter { $0["recipeId"] == source?["recipeId"] }.compactMap { $0["dishId"]?.stringValue })
                    let cookIds = Set(store.library.dishes.compactMap(\.objectValue).filter { dishIds.contains($0["id"]?.stringValue ?? "") }.compactMap { $0["cookId"]?.stringValue })
                    ForEach(store.library.cooks.filter { cookIds.contains($0.id) && $0.id != cookId }) { cook in
                        NavigationLink("\(cook.title) · \(cook.startedAt.formatted(date: .abbreviated, time: .omitted))") { CookDetailView(cookId: cook.id) }
                    }
                }
            }
        }.navigationTitle("Previous and this cook").onAppear { if revisionId.isEmpty { revisionId = source?["recipeRevisionId"]?.stringValue ?? "" } }
    }
}
private func referenceTime(_ at: Double, snapshot: RecipeSnapshot) -> String {
    snapshot.publicSource?.relativeTiming == true ? RecipeReferenceTimeline.relative(snapshot.smokingAnchorUtcMillis.map { at - Double($0) }) : RecipeReferenceTimeline.time(at)
}

private struct HistoricalObservationView: View {
    let event: JSONValue
    var relativeTiming = false
    var anchor: Double? = nil
    private func time(_ at: Double?, zone: String?) -> String { relativeTiming ? RecipeReferenceTimeline.relative(at.flatMap { at in anchor.map { at - $0 } }) : RecipeReferenceTimeline.time(at, zone: zone) }
    private var o: [String: JSONValue] { event.objectValue ?? [:] }
    var body: some View {
        DisclosureGroup("\(RecipeReferenceTimeline.relative(o["offsetMillis"]?.numberValue)) · \(o["title"]?.stringValue ?? "Note")") {
            Text(time(o["occurredAtUtcMillis"]?.numberValue, zone: o["timeZoneId"]?.stringValue))
            Text(o["details"]?.stringValue ?? "")
            if let metadata = o["noteMetadataJson"]?.stringValue.flatMap({ try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) }) {
                if let method = metadata.method { Text("Method · " + (CookingMethod.labels[method] ?? method)) }
                if let duration = metadata.durationMillis { Text("Recorded duration · \(duration / 60_000) min") }
                let ingredients = metadata.ingredients ?? []
                ForEach(ingredients.indices, id: \.self) { index in let ingredient = ingredients[index]; Text("\(ingredient.name) · \(ingredient.amount) \(ingredient.unit)") }
            }
            let readings = o["recordedTemperatures"]?.arrayValue ?? []
            ForEach(readings.indices, id: \.self) { index in let r = readings[index].objectValue ?? [:]
                Text("\(r["probeName"]?.stringValue ?? "") · \(r["measurementType"]?.stringValue ?? "") · \(r["value"]?.numberValue ?? 0) \(r["unit"]?.stringValue ?? "") · \(time(r["measuredAtUtcMillis"]?.numberValue, zone: r["timeZoneId"]?.stringValue))")
            }
        }
    }
}
