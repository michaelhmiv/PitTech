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
                            TurnInBannerText(cook.title, font: PitTechFont.bannerTitle).fixedSize(horizontal: false, vertical: true)
                            Text(cook.isActive ? (store.timingMode(cookId) == "explicit_phases" ? store.phaseSummary(cookId) : "Active · legacy timing") : "Finished").font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.emberText)
                            // TURN-IN (Slice 2, Area 02 `cook-timeline`): a
                            // finished cook's identity line — smoker, date,
                            // and time on in mono, with its verdict stars.
                            if !cook.isActive && cook.status == "completed" {
                                HStack(spacing: 10) {
                                    Text([
                                        cook.smokerName.flatMap { $0.isEmpty ? nil : $0 },
                                        cook.startedAt.formatted(date: .abbreviated, time: .omitted),
                                        cook.endedAtUtcMillis.map { duration(Double($0 - cook.startedAtUtcMillis)) },
                                    ].compactMap { $0 }.joined(separator: " · ").uppercased())
                                        .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                                    Spacer()
                                    if let rating = store.cookResults(cookId: cookId, dishId: nil).overallRating {
                                        TurnInStars(rating: rating)
                                    }
                                }
                            }
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
                    // §1.1 permitted copy delta 2 (BUILD-SPEC): the reopen
                    // line joins the finish confirmation, both platforms.
                    Text((incomplete.isEmpty ? "Finish this meal? You can add notes and results afterward." : "No completion action recorded for \(incomplete.joined(separator: ", ")). You can still finish; physical actions stay as recorded.") + " Finishing files the cook to your log — you can reopen it later if you need to.")
                }
                .sheet(isPresented: $completionChoices) {
                    NavigationStack { ScrollView { VStack(alignment: .leading, spacing: 18) {
                        TurnInBannerText("Meal finished", font: PitTechFont.bannerTitle)
                        Text("Your recorded actions and readings are saved. Results and recipe saves are optional.").font(.subheadline).foregroundStyle(PitTechStyle.supporting)
                        // TURN-IN d32: the sheet leads with stat cells —
                        // total time and the recorded verdict — above the
                        // choices.
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 4) {
                                TurnInMetaLabel("Total time")
                                Text(duration(max(0, (cook.endedAtUtcMillis.map { Double($0) } ?? Date.now.timeIntervalSince1970 * 1_000) - Double(cook.startedAtUtcMillis)))).font(PitTechFont.monoReadout)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(PitTechStyle.ruling, lineWidth: 1))
                            if let rating = store.cookResults(cookId: cookId, dishId: nil).overallRating {
                                VStack(alignment: .leading, spacing: 4) {
                                    TurnInMetaLabel("Verdict")
                                    TurnInStars(rating: rating)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(12)
                                .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 12))
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(PitTechStyle.ruling, lineWidth: 1))
                            }
                        }
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
                milestoneRailCard(cook)
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
                // TURN-IN (Slice 1): the board's action placards — Log
                // note is the primary placard (d12); Pause/Resume keeps
                // its Live home (PL-04) as the second placard. The bottom
                // shortcut bar keeps Log/Temp/Photo/Reminders.
                if cook.isActive {
                    HStack(spacing: 10) {
                        TurnInPlacard(systemImage: "note.text", label: "Log note", primary: true) { open() }
                        TurnInPlacard(systemImage: cook.status == "paused" ? "play.fill" : "pause.fill", label: cook.status == "paused" ? "Resume meal" : "Pause meal", identifier: "pause-meal") { store.setMealPaused(cookId, paused: cook.status != "paused") }
                    }
                }
            }
        }
    }
    /// Live board scoreboard (PL-04, TURN-IN Slice 1 — the d12 flagship
    /// composition): elapsed in mono, the pit reading at scoreboard size
    /// with an honest trend chip from the last two readings, the setpoint
    /// as a mono SET line, a probe block per dish (the lead dish carries
    /// its finish-target progress), and the cook targets at the foot. The
    /// paused state leads the card as a marigold status strip (d14).
    private func liveZone(_ cook: CookRecord, now: Date) -> some View {
        let pitHistory = readings.filter { ["pit", "pit_ambient", "ambient_temperature"].contains($0.measurementType) }.sorted { $0.measuredAt < $1.measuredAt }
        let pit = pitHistory.last
        let setpoint = readings.filter { $0.measurementType == "setpoint" }.max { $0.measuredAt < $1.measuredAt }
        let targets = store.library.targets.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["dishId"] == nil }
        let trend: (String, Color)? = pitHistory.count >= 2 ? {
            let delta = pitHistory[pitHistory.count - 1].value - pitHistory[pitHistory.count - 2].value
            if delta > 1 { return ("▲ Climb", PitTechStyle.marigold) }
            if delta < -1 { return ("▼ Drop", PitTechStyle.cobalt) }
            return ("Hold", PitTechStyle.ember)
        }() : nil
        let dishes = store.dishRecords(cookId)
        return VStack(alignment: .leading, spacing: 10) {
            if cook.status == "paused" {
                HStack(spacing: 10) {
                    Rectangle().fill(PitTechStyle.marigold).frame(width: 4)
                    let pausedAt = events.filter { $0.eventType.contains("paus") }.max { $0.occurredAt < $1.occurredAt }
                    Text(pausedAt.map { "Paused at \($0.occurredAt.formatted(date: .omitted, time: .shortened)). Timers are held; readings still show." } ?? "Paused. Timers are held; readings still show.")
                        .font(PitTechFont.body)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
            }
            HStack {
                TurnInMetaLabel("Elapsed")
                Spacer()
                Text(duration(max(0, now.timeIntervalSince(cook.startedAt)) * 1_000)).font(PitTechFont.monoReadout).foregroundStyle(PitTechStyle.ink).accessibilityIdentifier("live-cook-elapsed")
            }
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    TurnInMetaLabel("Pit")
                    if let pit {
                        Text(tempText(pit)).font(PitTechFont.scoreboard).foregroundStyle(PitTechStyle.ink)
                    } else {
                        Text("No reading yet").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    }
                }
                Spacer()
                if let trend { TurnInTrendChip(text: trend.0, fill: trend.1) }
            }
            if let setpoint {
                Text("SET \(tempText(setpoint))").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            } else if let initial = cook.initialSetpointValue {
                Text("SET \(initial.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(initial)) : String(format: "%.1f", initial))\(cook.initialSetpointUnit ?? "")").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            }
            ForEach(Array(dishes.enumerated()), id: \.element.id) { index, dish in
                let latest = readings.filter { $0.dishId == dish.id }.max { $0.measuredAt < $1.measuredAt }
                let dishTarget = store.library.targets.compactMap(\.objectValue).first { $0["cookId"]?.stringValue == cookId && $0["dishId"]?.stringValue == dish.id && $0["targetType"]?.stringValue == "personal_finish" }
                    ?? targets.first { $0["targetType"]?.stringValue == "personal_finish" }
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        TurnInBannerText(dish.name)
                        Spacer()
                        Text(latest.map(tempText) ?? "No reading yet").font(PitTechFont.monoReadout).foregroundStyle(PitTechStyle.ink)
                    }
                    // The lead dish carries the probe progress (d12): bar
                    // toward its finish target with the degrees to go.
                    if index == 0, let latest, let dishTarget, let targetValue = dishTarget["value"]?.numberValue, targetValue > 0 {
                        ProgressView(value: min(latest.value, targetValue), total: targetValue).tint(PitTechStyle.ember)
                        Text("TARGET \(targetValue.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(targetValue)) : String(format: "%.1f", targetValue))\(dishTarget["unit"]?.stringValue ?? latest.unit) · \(Int(max(0, targetValue - latest.value).rounded()))° TO GO")
                            .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    }
                }
            }
            ForEach(Array(targets.enumerated()), id: \.offset) { _, target in
                let value = target["value"]?.numberValue ?? 0
                Text("Target · \((target["targetType"]?.stringValue ?? "").replacingOccurrences(of: "_", with: " ")): \(value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(value)) : String(format: "%.1f", value)) \(target["unit"]?.stringValue ?? "")").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
        .accessibilityIdentifier("live-zone")
    }

    /// Milestone rail card (d12): the cook's recorded phase actions in
    /// order — done in ember, the next one current in marigold. Shown only
    /// once at least one milestone is on the record.
    @ViewBuilder private func milestoneRailCard(_ cook: CookRecord) -> some View {
        let actions = store.library.actions(for: cookId)
        let milestones: [(label: String, key: String)] = [("Food on", "food_on"), ("Wrap", "wrap"), ("Rest", "rest_start"), ("Serve", "dish_done")]
        let firstPending = milestones.first { m in !actions.contains { $0.action == m.key } }
        if milestones.contains(where: { m in actions.contains { $0.action == m.key } }) {
            TurnInCard {
                VStack(alignment: .leading, spacing: 10) {
                    TurnInMetaLabel("The cook, in order")
                    TurnInMilestoneRail(steps: milestones.map { m in
                        let at = actions.first { $0.action == m.key }?.at
                        return TurnInRailStep(
                            label: m.label,
                            sublabel: at.map { Date(timeIntervalSince1970: $0 / 1_000).formatted(date: .omitted, time: .shortened) },
                            state: at != nil ? .done : (m.key == firstPending?.key ? .current : .upcoming)
                        )
                    })
                }
            }
        }
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
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(PitTechStyle.ruling, lineWidth: 1))
        }
    }
    private var timelineContent: some View {
        let linked = Set(store.library.logReadingLinks.compactMap { $0.objectValue?["readingId"]?.stringValue })
        return VStack(alignment: .leading, spacing: 12) {
            // TURN-IN (Slice 1): no in-tab title header — the segmented
            // control already says Timeline (same call as Android's kept
            // "Cook timeline" line, which is test-asserted there only).
            // PL-08: saved results render read-only on the cook's summary.
            if store.hasAnyDisplayableResults(cookId: cookId) { CookResultsCard(cookId: cookId) }
            RecipeGuideMilestones(cookId: cookId)
            // Slice 7 (PL-13, DD-8): the Timeline teaches when it is empty —
            // one wording with Android, plus the action that creates an entry.
            // (Android's filter variant has no iOS counterpart; iOS has no
            // timeline filters.)
            if events.isEmpty && readings.filter({ !linked.contains($0.id) }).isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    TurnInBannerText(EmptyStateCopy.timelineTitle, font: PitTechFont.bannerTitle)
                    Text(EmptyStateCopy.timelineBody).foregroundStyle(PitTechStyle.supporting)
                    Button("Add a note") { open() }.buttonStyle(.bordered).frame(minHeight: 48).accessibilityIdentifier("timeline-empty-add-note")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 14))
                .accessibilityIdentifier("timeline-empty")
            }
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
                // TURN-IN (Slice 2, Area 02 `cook-timeline`): the entry's
                // kind rides as a chip — ember for recorded actions,
                // cobalt for readings, the raised surface otherwise.
                Text(event.metadata == nil ? event.eventType.replacingOccurrences(of: "_", with: " ").capitalized : "Note")
                    .font(PitTechFont.monoMeta)
                    .foregroundStyle(event.metadata?.action != nil ? PitTechStyle.onAccent : PitTechStyle.ink)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(event.metadata?.action != nil ? PitTechStyle.ember : PitTechStyle.surfaceRaised, in: RoundedRectangle(cornerRadius: 6))
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
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(focused ? PitTechStyle.emberText : PitTechStyle.ruling, lineWidth: focused ? 2 : 1))
    }
}

struct ReadingCard: View {
    let reading: TemperatureSample
    var onEdit: (() -> Void)? = nil
    var body: some View {
        HStack { VStack(alignment: .leading, spacing: 4) {
            Text(reading.probeName).font(.headline)
            Text(reading.measuredAt.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
        }; Spacer(); Text("\(reading.value.formatted()) \(reading.unit)").font(PitTechFont.monoReadout).foregroundStyle(PitTechStyle.ink)
            if let onEdit { Menu { Button("Edit reading", action: onEdit) } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) } }
        }
            .padding(14).background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(PitTechStyle.ruling, lineWidth: 1))
    }
}

struct AddDishSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var name = ""
    var body: some View { NavigationStack { Form { TextField("Dish name", text: $name) }.scrollContentBackground(.hidden).background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Add dish").toolbar {
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
    var body: some View { NavigationStack { Form { TextField("Cook name", text: $title); TextField("Notes", text: $notes, axis: .vertical).lineLimit(2...8) }.scrollContentBackground(.hidden).background(PitTechStyle.canvas.ignoresSafeArea()).navigationTitle("Edit cook").toolbar {
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
                        TurnInBannerText(title)
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
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
    }
}
