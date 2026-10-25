import SwiftUI
import UIKit

struct CookDetailView: View {
    @Environment(CookStore.self) private var store
    @Environment(NativeTelemetryRuntime.self) private var telemetry
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
    @State private var showingDevices = false
    @State private var showingReminders = false
    @State private var showingResults = false
    @State private var reminderCheckInRuleId: String?
    @ScaledMetric(relativeTo: .caption) private var quickBarHeight: CGFloat = 52
    @State private var sharingDraftId: String?
    private var cook: CookRecord? { store.library.cooks.first { $0.id == cookId } }
    private var readings: [TemperatureSample] { store.readings(for: cookId) }
    private var events: [TimelineEntry] { store.events(for: cookId) }

    var body: some View {
        Group {
            if let cook {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 18) {
                            Text(cook.title).font(.title.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                            Text(cook.isActive ? (store.timingMode(cookId) == "explicit_phases" ? store.phaseSummary(cookId) : "Active · legacy timing") : "Finished").font(.headline).foregroundStyle(PitTechStyle.accent)
                            MealActionRow { Button("Edit cook") { showingEditCook = true }; Button("Add dish") { showingDish = true } }.buttonStyle(.bordered)
                            if !store.dishRecords(cookId).isEmpty {
                                Button("Share meal") { do { sharingDraftId = try store.createSharingDraft(cookId: cookId, dishIds: Set(store.dishRecords(cookId).map(\.id))) } catch { self.error = error.localizedDescription } }.accessibilityIdentifier("meal-sharing-draft")
                            }
                            if let saved = store.noteDrafts.first(where: { $0.cookId == cookId }) {
                                Button("Resume saved note") { draft = saved }.buttonStyle(.bordered)
                            }
                            Picker("Cook section", selection: $section) { Text("Live").tag("Live"); Text("Timeline").tag("Timeline"); Text("Charts").tag("Charts") }.pickerStyle(.segmented)
                            RecipeGuideControl(cookId: cookId)
                            switch section {
                            case "Timeline": timelineContent
                            case "Charts": CookTemperatureChart(cookId: cookId) { event in section = "Timeline"; focused = event.id }
                            default: liveContent(cook)
                            }
                            if let error { Text(error).foregroundStyle(.red) }
                            if store.deletedNote != nil { Button("Undo deleted note") { do { try store.undoDeleteNote() } catch { self.error = error.localizedDescription } } }
                            if cook.isActive {
                                Button("Finish meal", role: .destructive) { finishing = true }.buttonStyle(.bordered).frame(minHeight: 48).accessibilityIdentifier("finish-meal")
                            }
                            if !cook.isActive && cook.status == "completed" {
                                Button("Undo finish") { store.reopenCook(cookId) }.buttonStyle(.bordered).frame(minHeight: 48).accessibilityIdentifier("undo-finish")
                            }
                        }.padding(20)
                    }
                    .accessibilityIdentifier("cook-content")
                    .onChange(of: focused) { _, id in if let id { DispatchQueue.main.async { withAnimation { proxy.scrollTo(id, anchor: .top) } } } }
                }
                .background(PitTechStyle.canvas.ignoresSafeArea())
                .navigationTitle("Meal").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarTrailing) {
                    Button { showingDevices = true } label: { Label("Devices", systemImage: "thermometer") }.accessibilityIdentifier("meal-devices")
                } }
                .safeAreaInset(edge: .bottom) { shortcutBar.padding(.horizontal, 16).padding(.vertical, 10).background(PitTechStyle.surface) }
                .navigationDestination(isPresented: $showingReminders) { MealRemindersView(cookId: cookId, checkInRuleId: reminderCheckInRuleId) }
                .task {
                    // PL-02: a reminder notification tap opens that reminder's
                    // check-in (the Reminders surface, scrolled to its Due
                    // check-in section), not just the cook.
                    if let ruleId = store.notificationReminderRuleId, store.reminderRule(ruleId)?["cookId"]?.stringValue == cookId {
                        reminderCheckInRuleId = ruleId
                        showingReminders = true
                        store.notificationReminderRuleId = nil
                    }
                }
                .sheet(isPresented: $showingDevices) { NativeTelemetryConnectionsView(runtime: telemetry) }
                .sheet(isPresented: $showingResults) { CookResultsSheet(cookId: cookId) }
                .sheet(isPresented: Binding(get: { sharingDraftId != nil }, set: { if !$0 { sharingDraftId = nil } })) {
                    if let sharingDraftId { NavigationStack { SharingDraftView(id: sharingDraftId) }.modifier(ScreenshotTextSize()) }
                }
                .alert("Finish meal?", isPresented: $finishing) {
                    Button("Finish meal", role: .destructive) { store.finishCook(cookId); if store.saveError == nil { completionChoices = true } }
                    Button("Cancel", role: .cancel) { }
                } message: {
                    let incomplete = store.phases(cookId).filter { $0.phase != "done" }.map(\.name)
                    Text(incomplete.isEmpty ? "Finish this meal? You can add notes and results afterward." : "No completion action recorded for \(incomplete.joined(separator: ", ")). You can still finish; physical actions stay as recorded.")
                }
                .sheet(isPresented: $completionChoices) {
                    NavigationStack { ScrollView { VStack(alignment: .leading, spacing: 18) {
                        Text("Meal finished").font(.title2.weight(.semibold))
                        Text("Your recorded actions and readings are saved. Results and recipe saves are optional.").font(.subheadline).foregroundStyle(PitTechStyle.supporting)
                        // PL-08 / DD-5: the sheet finish creates LEADS with
                        // the quick verdict, then the recipe saves, then
                        // Leave — the same order as Android.
                        CompletionVerdictSection(cookId: cookId)
                        MealRecipeActions(cookId: cookId)
                        Button("View meal summary") { section = "Timeline"; completionChoices = false }
                        Button("Leave without saving") { completionChoices = false }
                    }.padding(20) }.background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Summary").navigationBarTitleDisplayMode(.inline) }
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
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                Button { open() } label: { Label("Log", systemImage: "note.text").frame(minHeight: 44) }.accessibilityIdentifier("meal-log")
                Button { open(category: "temperature") } label: { Label("Temp", systemImage: "thermometer").frame(minHeight: 44) }
                Button { open(category: "photo") } label: { Label("Photo", systemImage: "camera").frame(minHeight: 44) }
                Button { showingReminders = true } label: { Label("Reminders", systemImage: "bell").frame(minHeight: 44) }
            }.font(.caption).buttonStyle(.bordered).fixedSize(horizontal: true, vertical: false).frame(minHeight: 44)
        }.frame(height: quickBarHeight)
    }
    private func open(category: String = "general", action: String? = nil, dishId: String? = nil) {
        let scope = dishId ?? (store.dishRecords(cookId).count == 1 ? store.dishRecords(cookId).first?.id : nil)
        draft = CookNoteDraft(cookId: cookId, dishId: scope, metadata: CookNoteMetadata(category: category, action: action), title: action.map { CookStore.actionLabel($0) } ?? "",
                              temperatures: category == "temperature" ? [NoteTemperatureDraft(unit: "°" + store.temperatureUnit, dishId: scope)] : [])
    }
    private func liveContent(_ cook: CookRecord) -> some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: 12) {
                liveZone(cook, now: context.date)
                nextActionStrip
                if store.timingMode(cookId) == "legacy_preserved" {
                    Text("Legacy timer · original session start retained").font(.caption)
                    Button("Use actual Food on timing") { foodOnTime = cook.startedAt; adopting = true }
                }
                ForEach(store.dishRecords(cookId)) { dish in MealDishCard(cookId: cookId, dish: dish, now: context.date) { category, action in open(category: category, action: action, dishId: dish.id) } }
                if cook.isActive { Button("Log preheat started") { open(category: "action", action: "preheat_start") }; Text("Preheat can overlap prep. Cooking starts at actual Food on.").font(.caption).foregroundStyle(.secondary) }
                LivePanel(id: "guidance", title: "Guidance", summary: store.timingMode(cookId) == "explicit_phases" ? store.phaseSummary(cookId) : "Legacy timing") {
                    VStack(alignment: .leading, spacing: 12) {
                        MealRecipeActions(cookId: cookId)
                        RecipeGuideMilestones(cookId: cookId, compact: true) { section = "Timeline" }
                    }
                }
                LivePanel(id: "reminders", title: "Reminders", summary: remindersSummary) {
                    Button("Meal reminders") { showingReminders = true }
                }
                if cook.isActive {
                    LivePanel(id: "checklist", title: "Checklist") { CookPrepChecklist(cookId: cookId) }
                }
                LivePanel(id: "timers", title: "Timers") { MealActivityTimers(cookId: cookId) }
                LivePanel(id: "results", title: "Results", summary: store.hasAnyDisplayableResults(cookId: cookId) ? "Recorded" : "Not recorded yet") {
                    Button(cook.isActive ? "Record results" : "Edit cook results") { showingResults = true }.accessibilityIdentifier("live-record-results")
                }
                LivePanel(id: "readings", title: "Latest readings") {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(readings.suffix(4).reversed()) { ReadingCard(reading: $0) }
                    }
                }
                LivePanel(id: "details", title: "Cook details", summary: "Session created \(cook.startedAt.formatted(date: .abbreviated, time: .shortened))", initiallyExpanded: false) {
                    VStack(alignment: .leading, spacing: 6) {
                        if let notes = cook.notes, !notes.isEmpty { Text(notes).font(.subheadline) }
                        Text("Session created \(cook.startedAt.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(.secondary)
                    }
                }
                // Pause lives on Live now (PL-04) — a visible meal action,
                // not part of the below-switch bucket shared by all sections.
                if cook.isActive {
                    Button(cook.status == "paused" ? "Resume meal" : "Pause meal") { store.setMealPaused(cookId, paused: cook.status != "paused") }.buttonStyle(.bordered).accessibilityIdentifier("pause-meal")
                }
            }
        }
    }
    /// Live Zone poster (PL-04): elapsed, pit and setpoint, the latest
    /// temperature for every dish, and cook targets — the cook at a glance.
    private func liveZone(_ cook: CookRecord, now: Date) -> some View {
        let pit = readings.filter { ["pit", "pit_ambient", "ambient_temperature"].contains($0.measurementType) }.max { $0.measuredAt < $1.measuredAt }
        let setpoint = readings.filter { $0.measurementType == "setpoint" }.max { $0.measuredAt < $1.measuredAt }
        let targets = store.library.targets.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["dishId"] == nil }
        return VStack(alignment: .leading, spacing: 6) {
            Text(cook.status == "paused" ? "PAUSED" : "LIVE COOK").font(.subheadline.weight(.semibold)).foregroundStyle(PitTechStyle.supporting)
            Text("Elapsed \(duration(max(0, now.timeIntervalSince(cook.startedAt)) * 1_000))").font(.title2.bold()).accessibilityIdentifier("live-cook-elapsed")
            if let pit { HStack { Text("Pit").font(.headline); Spacer(); Text(tempText(pit)).font(.title3.monospacedDigit()) } }
            if let setpoint { HStack { Text("Setpoint").font(.headline); Spacer(); Text(tempText(setpoint)).font(.title3.monospacedDigit()) } }
            ForEach(store.dishRecords(cookId)) { dish in
                let latest = readings.filter { $0.dishId == dish.id }.max { $0.measuredAt < $1.measuredAt }
                HStack { Text(dish.name).font(.headline); Spacer(); Text(latest.map(tempText) ?? "No reading yet").font(.title3.monospacedDigit()) }
            }
            ForEach(Array(targets.enumerated()), id: \.offset) { _, target in
                let value = target["value"]?.numberValue ?? 0
                Text("Target · \((target["targetType"]?.stringValue ?? "").replacingOccurrences(of: "_", with: " ")): \(value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(value)) : String(format: "%.1f", value)) \(target["unit"]?.stringValue ?? "")").font(.caption).foregroundStyle(PitTechStyle.supporting)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityIdentifier("live-zone")
    }
    private func tempText(_ sample: TemperatureSample) -> String {
        sample.value.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(sample.value))\(sample.unit)" : String(format: "%.1f%@", sample.value, sample.unit)
    }
    /// Enabled rules for this cook whose current occurrence is still pending.
    private var pendingReminders: [(title: String, due: Double)] {
        let rules = store.library.reminderRules.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["enabled"]?.boolValue == true }
        return rules.compactMap { rule in
            guard let id = rule["id"]?.stringValue, let state = store.reminderState(id),
                  state["status"]?.stringValue == "pending", let due = state["dueAtUtcMillis"]?.numberValue else { return nil }
            return (rule["title"]?.stringValue ?? "Reminder", due)
        }.sorted { $0.due < $1.due }
    }
    private var remindersSummary: String {
        pendingReminders.isEmpty ? "None pending" : "\(pendingReminders.count) pending · next \(RecipeReferenceTimeline.time(pendingReminders[0].due))"
    }
    /// Next-action strip: the earliest pending reminder, never inside a
    /// collapsed panel (DD-2).
    @ViewBuilder private var nextActionStrip: some View {
        if let next = pendingReminders.first {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(next.title).font(.headline)
                    Text("Due \(RecipeReferenceTimeline.time(next.due))").font(.caption).foregroundStyle(PitTechStyle.supporting)
                }
                Spacer()
                Button("Reminders") { showingReminders = true }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 14))
        }
    }
    private var timelineContent: some View {
        let linked = Set(store.library.logReadingLinks.compactMap { $0.objectValue?["readingId"]?.stringValue })
        return VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Timeline", subtitle: "Complete notes, newest activity first.")
            // PL-08: saved results render read-only on the cook's summary.
            if store.hasAnyDisplayableResults(cookId: cookId) { CookResultsCard(cookId: cookId) }
            RecipeGuideMilestones(cookId: cookId)
            ForEach(events) { event in
                CookNoteCard(event: event, cookId: cookId, focused: focused == event.id, onEdit: {
                    do { draft = try store.loadNote(event.id) } catch { self.error = error.localizedDescription }
                }, onDelete: { do { try store.deleteNote(event.id) } catch { self.error = error.localizedDescription } }).id(event.id)
            }
            ForEach(readings.filter { !linked.contains($0.id) }.reversed()) { reading in
                // PL-07: a standalone manual reading edits in the note
                // composer (the same job as Android); device readings stay
                // display-only, exactly as automatic samples are immutable.
                ReadingCard(reading: reading, onEdit: reading.source == "manual" ? { draft = store.readingDraft(reading) } : nil)
            }
        }
    }
    private func duration(_ millis: Double) -> String { "\(Int(millis / 3_600_000))h \(Int(millis / 60_000) % 60)m" }
}

struct MealActionRow<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        let layout = textSize >= .xxxLarge ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 8))
        layout { content }
    }
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
    var onEdit: (() -> Void)? = nil
    var body: some View {
        HStack { VStack(alignment: .leading, spacing: 4) {
            Text(reading.probeName).font(.headline)
            Text(reading.measuredAt.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
        }; Spacer(); Text("\(reading.value.formatted()) \(reading.unit)").font(.title3.monospacedDigit())
            if let onEdit { Menu { Button("Edit reading", action: onEdit) } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) } }
        }
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

/// One Live-tab panel (PL-04): a titled card whose header always shows the
/// panel's live state summary, expanded or collapsed (DD-2). Open/closed
/// state is kept per panel for the lifetime of the cook detail.
private struct LivePanel<Content: View>: View {
    let panelID: String
    let title: String
    let summary: String?
    @State private var expanded: Bool
    private let content: Content
    init(id: String, title: String, summary: String? = nil, initiallyExpanded: Bool = true, @ViewBuilder content: () -> Content) {
        self.panelID = id
        self.title = title
        self.summary = summary
        self._expanded = State(initialValue: initiallyExpanded)
        self.content = content()
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button { withAnimation { expanded.toggle() } } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.headline).foregroundStyle(PitTechStyle.ink)
                        if let summary { Text(summary).font(.caption).foregroundStyle(PitTechStyle.supporting) }
                    }
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").foregroundStyle(PitTechStyle.supporting)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("live-panel-\(panelID)")
            if expanded { content }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}
