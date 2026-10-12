import SwiftUI
import UIKit

struct CookDetailView: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var section = "Live"
    @State private var draft: CookNoteDraft?
    @State private var showingDish = false
    @State private var showingEditCook = false
    @State private var focused: String?
    @State private var error: String?
    @State private var adopting = false
    @State private var foodOnTime = Date.now
    @State private var finishing = false
    @State private var completionChoices = false
    private var cook: CookRecord? { store.library.cooks.first { $0.id == cookId } }
    private var readings: [TemperatureSample] { store.readings(for: cookId) }
    private var events: [TimelineEntry] { store.events(for: cookId) }

    var body: some View {
        Group {
            if let cook {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 18) {
                            Text(cook.title).font(.largeTitle.bold())
                            Text(cook.isActive ? (store.timingMode(cookId) == "explicit_phases" ? store.phaseSummary(cookId) : "Active · legacy timing") : "Finished").font(.headline).foregroundStyle(PitTechStyle.accent)
                            HStack { Button("Edit cook") { showingEditCook = true }; Button("Add dish") { showingDish = true } }.buttonStyle(.bordered)
                            shortcutBar
                            if let saved = store.noteDrafts.first(where: { $0.cookId == cookId }) {
                                Button("Resume saved note") { draft = saved }.buttonStyle(.bordered)
                            }
                            Picker("Cook section", selection: $section) { Text("Live").tag("Live"); Text("Timeline").tag("Timeline"); Text("Charts").tag("Charts") }.pickerStyle(.segmented)
                            switch section {
                            case "Timeline": timelineContent
                            case "Charts": CookTemperatureChart(cookId: cookId) { event in section = "Timeline"; focused = event.id }
                            default: liveContent(cook)
                            }
                            if let error { Text(error).foregroundStyle(.red) }
                            if store.deletedNote != nil { Button("Undo deleted note") { do { try store.undoDeleteNote() } catch { self.error = error.localizedDescription } } }
                            if cook.isActive {
                                Button(cook.status == "paused" ? "Resume meal" : "Pause meal") { store.setMealPaused(cookId, paused: cook.status != "paused") }.buttonStyle(.bordered)
                                Button("Finish meal", role: .destructive) { finishing = true }.buttonStyle(.bordered).frame(minHeight: 48).accessibilityIdentifier("finish-meal")
                            }
                        }.padding(20)
                    }
                    .onChange(of: focused) { _, id in if let id { DispatchQueue.main.async { withAnimation { proxy.scrollTo(id, anchor: .top) } } } }
                }
                .background(PitTechStyle.canvas.ignoresSafeArea())
                .navigationTitle("Meal").navigationBarTitleDisplayMode(.inline)
                .alert("Finish meal?", isPresented: $finishing) {
                    Button("Finish meal", role: .destructive) { store.finishCook(cookId); if store.saveError == nil { completionChoices = true } }
                    Button("Cancel", role: .cancel) { }
                } message: {
                    let incomplete = store.phases(cookId).filter { $0.phase != "done" }.map(\.name)
                    Text(incomplete.isEmpty ? "Finish this meal? You can add notes and results afterward." : "No completion action recorded for \(incomplete.joined(separator: ", ")). You can still finish; physical actions stay as recorded.")
                }
                .sheet(isPresented: $completionChoices) {
                    NavigationStack { ScrollView { VStack(alignment: .leading, spacing: 14) {
                        Text("Meal finished").font(.title2)
                        Button("View meal summary") { section = "Timeline"; completionChoices = false }
                        MealRecipeActions(cookId: cookId, initiallyExpanded: true)
                        Button("Leave without saving") { completionChoices = false }
                    }.padding(20) }.navigationTitle("Meal finished") }
                }
                .sheet(item: $draft) { CookNoteComposer(draft: $0) }
                .sheet(isPresented: $showingDish) { AddDishSheet(cookId: cookId) }
                .sheet(isPresented: $showingEditCook) { EditCookSheet(cook: cook) }
                .sheet(isPresented: $adopting) {
                    NavigationStack { Form {
                        Text("Use actual Food on timing instead of the saved legacy timer. Original session times remain saved.")
                        if !store.library.actions(for: cookId).contains(where: { $0.action == "food_on" }) { DatePicker("Actual Food on", selection: $foodOnTime, in: ...Date.now) }
                        Button("Use Food on timing") { do { try store.adoptTiming(cookId, foodOn: foodOnTime); adopting = false } catch { self.error = error.localizedDescription } }
                        Button("Keep legacy timer") { adopting = false }
                    }.navigationTitle("Timing basis") }
                }
            } else { ContentUnavailableView("Cook not found", systemImage: "exclamationmark.triangle") }
        }
    }
    private var shortcutBar: some View {
        VStack(alignment: .leading) {
            HStack {
                Button { open() } label: { Label("Log", systemImage: "note.text") }
                Button { open(category: "temperature") } label: { Label("Temp", systemImage: "thermometer") }
                Button { open(category: "photo") } label: { Label("Photo", systemImage: "camera") }
            }.buttonStyle(.bordered)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack { ForEach(["food_on", "spritz", "wrap", "remove", "rest_start", "hold_start", "dish_done"], id: \.self) { action in
                    Button(CookStore.actionLabel(action)) { open(category: "action", action: action) }.buttonStyle(.bordered)
                } }
            }
        }
    }
    private func open(category: String = "general", action: String? = nil, dishId: String? = nil) {
        let scope = dishId ?? (store.dishRecords(cookId).count == 1 ? store.dishRecords(cookId).first?.id : nil)
        draft = CookNoteDraft(cookId: cookId, dishId: scope, metadata: CookNoteMetadata(category: category, action: action), title: action.map { CookStore.actionLabel($0) } ?? "",
                              temperatures: category == "temperature" ? [NoteTemperatureDraft(unit: "°" + store.temperatureUnit, dishId: scope)] : [])
    }
    private func liveContent(_ cook: CookRecord) -> some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: 12) {
                MealRemindersLink(cookId: cookId)
                MealRecipeActions(cookId: cookId)
                MealActivityTimers(cookId: cookId)
                Text(store.timingMode(cookId) == "explicit_phases" ? store.phaseSummary(cookId) : "Active · legacy timing").font(.title2.bold())
                if store.timingMode(cookId) == "legacy_preserved" {
                    Text("Legacy timer · original session start retained").font(.caption)
                    Text("Elapsed \(duration(max(0, context.date.timeIntervalSince(cook.startedAt)) * 1_000))")
                    Button("Use actual Food on timing") { foodOnTime = cook.startedAt; adopting = true }
                }
                if cook.isActive { CookPrepChecklist(cookId: cookId) }
                ForEach(store.dishRecords(cookId)) { dish in MealDishCard(cookId: cookId, dish: dish, now: context.date) { category, action in open(category: category, action: action, dishId: dish.id) } }
                if cook.isActive { Button("Log preheat started") { open(category: "action", action: "preheat_start") }; Text("Preheat can overlap prep. Cooking starts at actual Food on.").font(.caption).foregroundStyle(.secondary) }
                if let notes = cook.notes, !notes.isEmpty { Text(notes).font(.subheadline) }
                Text("Session created \(cook.startedAt.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(.secondary)
                ForEach(readings.suffix(4).reversed()) { ReadingCard(reading: $0) }
            }
        }
    }
    private var timelineContent: some View {
        let linked = Set(store.library.logReadingLinks.compactMap { $0.objectValue?["readingId"]?.stringValue })
        return VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Timeline", subtitle: "Complete notes, newest activity first.")
            ForEach(events) { event in
                CookNoteCard(event: event, cookId: cookId, focused: focused == event.id, onEdit: {
                    do { draft = try store.loadNote(event.id) } catch { self.error = error.localizedDescription }
                }, onDelete: { do { try store.deleteNote(event.id) } catch { self.error = error.localizedDescription } }).id(event.id)
            }
            ForEach(readings.filter { !linked.contains($0.id) }.reversed()) { ReadingCard(reading: $0) }
        }
    }
    private func duration(_ millis: Double) -> String { "\(Int(millis / 3_600_000))h \(Int(millis / 60_000) % 60)m" }
}

private struct CookNoteCard: View {
    @Environment(CookStore.self) private var store
    let event: TimelineEntry; let cookId: String; let focused: Bool
    var onEdit: () -> Void; var onDelete: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(event.metadata == nil ? event.eventType.replacingOccurrences(of: "_", with: " ").capitalized : "Note").font(.caption.bold())
                Spacer()
                if event.source == "manual" { Menu { Button("Edit note", action: onEdit); Button("Delete note", role: .destructive, action: onDelete) } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) } }
            }
            Text(event.title).font(.headline)
            if !event.details.isEmpty { Text(event.details).font(.subheadline) }
            if let action = event.metadata?.action { Text(CookStore.actionLabel(action)).font(.caption.bold()) }
            let ids = Set(store.library.logReadingLinks.filter { $0.objectValue?["eventId"]?.stringValue == event.id }.compactMap { $0.objectValue?["readingId"]?.stringValue })
            ForEach(store.readings(for: cookId).filter { ids.contains($0.id) }) { r in Text("\(r.probeName): \(r.value.formatted()) \(r.unit)").font(.subheadline.monospacedDigit()) }
            ForEach(store.library.photos.filter { $0.objectValue?["eventId"]?.stringValue == event.id }.indices, id: \.self) { index in
                let photos = store.library.photos.filter { $0.objectValue?["eventId"]?.stringValue == event.id }
                if let o = photos[index].objectValue, let path = o["archivePath"]?.stringValue, let image = store.photoPreview(path) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 200).accessibilityLabel(o["caption"]?.stringValue ?? "Cook photo")
                    if let caption = o["caption"]?.stringValue, !caption.isEmpty { Text(caption).font(.caption) }
                }
            }
            Text(event.occurredAt.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(focused ? PitTechStyle.accent : .clear, lineWidth: 2))
    }
}

struct ReadingCard: View {
    let reading: TemperatureSample
    var body: some View {
        HStack { VStack(alignment: .leading, spacing: 4) {
            Text(reading.probeName).font(.headline)
            Text(reading.measuredAt.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
        }; Spacer(); Text("\(reading.value.formatted()) \(reading.unit)").font(.title3.monospacedDigit()) }
            .padding(14).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct AddDishSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var name = ""
    var body: some View { NavigationStack { Form { TextField("Dish name", text: $name) }.navigationTitle("Add dish").toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) { Button("Add") { store.addDish(cookId: cookId, name: name); dismiss() }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
    } } }
}
struct EditCookSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cook: CookRecord
    @State private var title: String; @State private var notes: String
    init(cook: CookRecord) { self.cook = cook; _title = State(initialValue: cook.title); _notes = State(initialValue: cook.notes ?? "") }
    var body: some View { NavigationStack { Form { TextField("Cook name", text: $title); TextField("Notes", text: $notes, axis: .vertical).lineLimit(2...8) }.navigationTitle("Edit cook").toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) { Button("Save") { store.updateCook(cook.id, title: title, notes: notes); dismiss() } }
    } } }
}
