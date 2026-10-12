import SwiftUI
import UserNotifications

private struct NativeReminderRequest: Identifiable {
    var id = UUID().uuidString
    var ruleId: String?
    var dishId: String?
    var title = "Check the food"
    var config: [String: JSONValue]?
    var revisionId: String?
    var milestoneId: String?
}
struct MealRemindersLink: View {
    let cookId: String
    var dishId: String? = nil
    var body: some View { NavigationLink { MealRemindersView(cookId: cookId, dishId: dishId) } label: { Label("Reminders", systemImage: "bell") }.buttonStyle(.bordered) }
}
struct MealRemindersView: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    var dishId: String?
    @State private var request: NativeReminderRequest?
    @State private var error: String?
    @State private var confirming: String?
    @State private var action = "wrap"
    private var rules: [[String: JSONValue]] { store.library.reminderRules.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && (dishId == nil || $0["dishId"]?.stringValue == dishId) } }
    private var active: Bool { store.library.cooks.contains { $0.id == cookId && $0.isActive } }
    var body: some View {
        List {
            Section {
                Text("Dismiss, skip and snooze leave the cooking timeline unchanged.")
                if active { Button("Add reminder") { request = NativeReminderRequest(dishId: dishId) }.accessibilityIdentifier("reminder-rule-add") }
                if let message = store.reminderMessage { Text(message).font(.caption) }
            }
            ForEach(rules.indices, id: \.self) { index in ruleSection(rules[index]) }
            if active { suggestions }
            if let error { Text(error).foregroundStyle(.red) }
        }.navigationTitle("Reminders").sheet(item: $request) { NativeReminderEditor(cookId: cookId, request: $0) }
            .task { await store.refreshReminderDeliveryHistory() }
            .sheet(isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } })) {
                NavigationStack { Form {
                    Text("Only confirm an action you performed. This creates one current-cook entry.")
                    Picker("Actual action", selection: $action) { ForEach(["food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done"], id: \.self) { Text(CookStore.actionLabel($0)).tag($0) } }
                    Button("Record action") { act { if let id = confirming { _ = try store.confirmReminderAction(id, action: action); confirming = nil } } }
                    Button("Cancel") { confirming = nil }
                }.navigationTitle("Record an actual action") }
            }
    }
    @ViewBuilder private func ruleSection(_ rule: [String: JSONValue]) -> some View {
        if let id = rule["id"]?.stringValue {
            Section(rule["title"]?.stringValue ?? "Reminder") {
                Text("\(store.dishRecords(cookId).first { $0.id == rule["dishId"]?.stringValue }?.name ?? "Meal") · \(rule["status"]?.stringValue ?? "")")
                if let due = store.reminderState(id)?["dueAtUtcMillis"]?.numberValue { Text("Due " + RecipeReferenceTimeline.time(due)) }
                if rule["kind"]?.stringValue == "probe" { Text("Temperature monitoring is unavailable on iOS. This configuration is retained for compatible monitoring on Android.").font(.caption) }
                else if active {
                    Button(rule["enabled"]?.boolValue == true ? "Disable" : "Enable") { act { try store.changeReminder(id, action: "toggle") } }
                    Button("Edit") { act {
                        let config = try NativeReminderEngine.configuration(rule["triggerConfig"]?.stringValue ?? "")
                        request = NativeReminderRequest(ruleId: id, dishId: rule["dishId"]?.stringValue, title: rule["title"]?.stringValue ?? "", config: config, revisionId: rule["sourceRecipeRevisionId"]?.stringValue, milestoneId: rule["sourceMilestoneId"]?.stringValue)
                    } }
                    if rule["enabled"]?.boolValue == true {
                        Button("Snooze 10 min") { act { try store.changeReminder(id, action: "snooze") } }
                        Button("Skip") { act { try store.changeReminder(id, action: "skip") } }
                        Button("Dismiss") { act { try store.changeReminder(id, action: "complete") } }
                        Button("Record an action…") { confirming = id }
                    }
                }
                Button("Remove", role: .destructive) { act { try store.changeReminder(id, action: "remove") } }
            }
        }
    }
    private var suggestions: some View {
        Section("Previous cook suggestions") {
            ForEach(store.dishRecords(cookId).filter { dishId == nil || $0.id == dishId }) { dish in
                if let revision = store.recipeSource(dish.id)?["recipeRevisionId"]?.stringValue, let snapshot = try? store.recipeSnapshot(revision) {
                    let events = snapshot.observations.compactMap(\.objectValue).filter { ["wrap", "remove", "rest_start", "hold_start", "dish_done"].contains($0["action"]?.stringValue ?? "") && $0["offsetMillis"]?.numberValue != nil }
                    ForEach(events.indices, id: \.self) { index in let event = events[index]
                        Button("\(dish.name) · last time \(event["title"]?.stringValue ?? "a milestone") at \(RecipeReferenceTimeline.relative(event["offsetMillis"]?.numberValue)) · enable…") {
                            request = NativeReminderRequest(dishId: dish.id, title: "Last time: \(event["title"]?.stringValue ?? "milestone"). Check \(dish.name).", config: ["version": .number(1), "type": .string("historical"), "anchorAction": .string("food_on"), "offsetMillis": event["offsetMillis"]!], revisionId: revision, milestoneId: event["sourceEventId"]?.stringValue)
                        }
                    }
                }
            }
        }
    }
    private func act(_ block: () throws -> Void) { do { try block(); error = nil } catch { self.error = error.localizedDescription } }
}
private struct NativeReminderEditor: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    let request: NativeReminderRequest
    @State private var title = ""
    @State private var dishId = ""
    @State private var kind = "duration"
    @State private var minutes = "10"
    @State private var clock = Date.now.addingTimeInterval(600)
    @State private var action = "food_on"
    @State private var eventId = ""
    @State private var busy = false
    @State private var error: String?
    @State private var initialized = false
    private var anchors: [CookAction] { store.library.actions(for: cookId).filter { $0.action == action && ($0.dishIds.isEmpty || $0.dishIds.contains(dishId)) } }
    private var configuration: [String: JSONValue]? {
        var value: [String: JSONValue] = ["version": .number(1), "type": .string(kind)]
        switch kind {
        case "duration", "action", "historical":
            guard let amount = Double(minutes), amount.isFinite else { return nil }
            let millis = (amount * 60_000).rounded()
            if kind == "duration" { value["startedAt"] = request.config?["startedAt"] ?? .number(Double(Int64(Date.now.timeIntervalSince1970 * 1000))); value["delayMillis"] = .number(millis) }
            else { value[kind == "action" ? "action" : "anchorAction"] = .string(action); value["offsetMillis"] = .number(millis); if kind == "action" && !eventId.isEmpty { value["eventId"] = .string(eventId) } }
        case "clock": value["at"] = .number(Double(Int64(clock.timeIntervalSince1970 * 1000)))
        default: return nil
        }
        guard let raw = try? recipeJSON(value), (try? NativeReminderEngine.configuration(raw)) != nil else { return nil }
        return value
    }
    var body: some View {
        NavigationStack { Form {
            TextField("Title", text: $title).accessibilityIdentifier("reminder-rule-title")
            Picker("Scope", selection: $dishId) { Text("Meal").tag(""); ForEach(store.dishRecords(cookId)) { Text($0.name).tag($0.id) } }
            Picker("Type", selection: $kind) {
                Text("After a duration").tag("duration"); Text("At a clock time").tag("clock"); Text("After an actual action").tag("action")
                if request.config?["type"]?.stringValue == "historical" { Text("Previous cook milestone").tag("historical") }
            }
            if kind == "clock" { DatePicker("Date and time", selection: $clock) }
            else {
                TextField(kind == "duration" ? "Minutes" : "Minutes after anchor (negative = earlier)", text: $minutes).keyboardType(.numbersAndPunctuation).accessibilityIdentifier("reminder-rule-minutes")
                if kind == "action" || kind == "historical" {
                    Picker("Actual anchor", selection: $action) { ForEach(Array(CookNoteMetadata.actions).sorted(), id: \.self) { Text(CookStore.actionLabel($0)).tag($0) } }.onChange(of: action) { _, _ in eventId = "" }
                    if kind == "action" { Picker("Action occurrence", selection: $eventId) { Text("First occurrence · wait if unrecorded").tag(""); ForEach(anchors) { Text("\(CookStore.actionLabel($0.action)) · \(RecipeReferenceTimeline.time($0.at))").tag($0.id) } } }
                    Text("Waits for the new attempt's actual action. Correcting its timestamp updates future reminders.").font(.caption)
                }
            }
            Text("Time reminders use local notifications. Continuous temperature monitoring is unavailable on iOS.").font(.caption)
            if let error { Text(error).foregroundStyle(.red) }
            Button("Save reminder") { save() }.disabled(busy || title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || configuration == nil).accessibilityIdentifier("reminder-rule-save")
        }.navigationTitle("Reminder").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) } }
            .onAppear { if !initialized { initialized = true; title = request.title; dishId = request.dishId ?? ""; kind = request.config?["type"]?.stringValue ?? "duration"; action = request.config?["action"]?.stringValue ?? request.config?["anchorAction"]?.stringValue ?? "food_on"; eventId = request.config?["eventId"]?.stringValue ?? ""; minutes = String((request.config?["delayMillis"]?.numberValue ?? request.config?["offsetMillis"]?.numberValue ?? 600_000) / 60_000); clock = Date(timeIntervalSince1970: (request.config?["at"]?.numberValue ?? Date.now.addingTimeInterval(600).timeIntervalSince1970 * 1000) / 1000) } }
        }
    }
    private func save() {
        guard !busy, let config = configuration else { return }; busy = true
        Task { do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            if !granted { store.reminderMessage = "Reminder saved. Enable notifications in Settings to receive alerts." }
            try store.saveReminderRule(cookId: cookId, dishId: dishId.isEmpty ? nil : dishId, title: title, config: config, id: request.ruleId ?? UUID().uuidString.lowercased(), revisionId: request.revisionId, milestoneId: request.milestoneId)
            dismiss()
        } catch { self.error = error.localizedDescription }; busy = false }
    }
}
