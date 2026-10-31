import SwiftUI
import UserNotifications

/// Cook alerts (issue #183, iOS half): Android's CookAlertTools — the
/// rule list with its watching / needs-attention / acknowledged / off
/// states and the editor behind Add/Edit. Reached from the cook's Live
/// Alerts panel; a notification tap lands here at the firing rule
/// (PL-03), highlighted and first.
struct CookAlertsView: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    var focusAlertId: String? = nil
    @State private var editing: CookAlertRule?
    @State private var editingTitle = ""
    @State private var isNewRule = false
    @State private var notice: String?
    @State private var error: String?
    @State private var notificationsDenied = false

    private var entries: [CookStore.CookAlertEntry] {
        let all = store.cookAlerts(cookId: cookId)
        guard let focusAlertId else { return all }
        return all.sorted { ($0.id == focusAlertId ? 0 : 1) < ($1.id == focusAlertId ? 0 : 1) }
    }
    private var cook: CookRecord? { store.library.cooks.first { $0.id == cookId } }
    private var monitorTargets: [(dishId: String?, value: Double, unit: String)] {
        store.library.targets.compactMap { row -> (dishId: String?, value: Double, unit: String)? in
            guard let target = row.objectValue, target["cookId"]?.stringValue == cookId,
                  target["targetType"]?.stringValue == "personal_finish",
                  let value = target["value"]?.numberValue, let unit = target["unit"]?.stringValue else { return nil }
            return (target["dishId"]?.stringValue, value, unit)
        }
    }

    var body: some View {
        List {
            Section {
                Text("Manual temperatures trigger checks when logged. Background sensor alerts need an attached grill with timed recording.")
                if notificationsDenied {
                    Button("Enable alert notifications") {
                        Task {
                            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                            notificationsDenied = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied
                        }
                    }
                }
                // PL-29: test feedback stays in-app — no real
                // HIGH-importance notification is posted for a test.
                Button("Send test alert") { notice = "Test alert — alert feedback appears here in the app. Real alerts post a notification when they fire." }
                    .accessibilityIdentifier("alerts-test-alert")
                if let notice { Text(notice).font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted) }
                if let error { Text(error).foregroundStyle(PitTechStyle.flag) }
            }
            ForEach(entries, id: \.id) { entry in
                VStack(alignment: .leading, spacing: 4) {
                    TurnInBannerText(entry.title)
                    Text(!entry.rule.enabled ? "Off" : entry.state.active ? "Needs attention" : "Watching")
                        .font(PitTechFont.monoMeta)
                        .foregroundStyle(entry.state.active ? PitTechStyle.flag : PitTechStyle.inkMuted)
                    if entry.state.acknowledged {
                        Text(entry.state.active ? "Acknowledged · condition has not recovered" : "Acknowledged")
                            .font(PitTechFont.body)
                    }
                    HStack {
                        Button("Edit") { editing = entry.rule; editingTitle = entry.title; isNewRule = false }
                        Button(entry.rule.enabled ? "Disable" : "Enable") { store.changeCookAlert(id: entry.id, action: "toggle") }
                    }
                    .buttonStyle(.borderless)
                    if entry.state.active {
                        HStack {
                            Button("Acknowledge") { store.changeCookAlert(id: entry.id, action: "acknowledge") }
                                .accessibilityIdentifier("alert-acknowledge-\(entry.id)")
                            Button("Snooze 10 min") { store.changeCookAlert(id: entry.id, action: "snooze") }
                        }
                        .buttonStyle(.borderless)
                    }
                    if entry.rule.type == "pit_range" {
                        Button("Lid open · suppress pit alarm 10 min") { store.changeCookAlert(id: entry.id, action: "lid") }
                            .buttonStyle(.borderless)
                    }
                }
                .padding(.vertical, 4)
                .listRowBackground(entry.id == focusAlertId ? PitTechStyle.surfaceRaised : PitTechStyle.surface)
                .accessibilityIdentifier("alert-row-\(entry.id)")
            }
            if cook?.status != "completed" {
                Section {
                    Button("Add alert") {
                        editing = CookAlertRule(dishId: store.dishRecords(cookId).first?.id)
                        editingTitle = ""
                        isNewRule = true
                    }
                    .accessibilityIdentifier("alert-add")
                    ForEach(Array(monitorTargets.enumerated()), id: \.offset) { _, target in
                        Button("Monitor target · \(target.value) \(target.unit)") {
                            editing = CookAlertRule(dishId: target.dishId, targetF: CookPlanEngine.fahrenheit(target.value, target.unit))
                            editingTitle = ""
                            isNewRule = true
                        }
                    }
                }
            }
        }
        .navigationTitle("Cook alerts")
        .scrollContentBackground(.hidden)
        .background(PitTechStyle.canvas.ignoresSafeArea())
        .task { notificationsDenied = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied }
        .sheet(isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            if let editing {
                CookAlertEditor(cookId: cookId, initial: editing, initialTitle: editingTitle, isNew: isNewRule) { editing = nil }
            }
        }
    }
}

/// The alert editor: name, watch type, dish/stage, thresholds + dwell,
/// probe, missing minutes — Android's field set, in the app editor's
/// boxed-field language.
struct CookAlertEditor: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    let initial: CookAlertRule
    let initialTitle: String
    let isNew: Bool
    let onClose: () -> Void
    @State private var rule: CookAlertRule
    @State private var title: String
    @State private var unit: String
    @State private var target = ""
    @State private var low = ""
    @State private var high = ""
    @State private var dwell = ""
    @State private var missing = ""
    @State private var error: String?

    init(cookId: String, initial: CookAlertRule, initialTitle: String, isNew: Bool, onClose: @escaping () -> Void) {
        self.cookId = cookId
        self.initial = initial
        self.initialTitle = initialTitle
        self.isNew = isNew
        self.onClose = onClose
        let preferred = "°F"
        _rule = State(initialValue: initial)
        _title = State(initialValue: initialTitle.isEmpty ? CookAlertEngine.label(for: initial.type) : initialTitle)
        _unit = State(initialValue: preferred)
        _target = State(initialValue: initial.targetF.map { Self.text($0, preferred) } ?? "")
        _low = State(initialValue: Self.text(initial.lowF ?? 200.0, preferred))
        _high = State(initialValue: Self.text(initial.highF ?? 300.0, preferred))
        _dwell = State(initialValue: "\(initial.dwellMillis / 60_000)")
        _missing = State(initialValue: "\(initial.missingMillis / 60_000)")
    }

    private static func text(_ fahrenheit: Double, _ unit: String) -> String {
        let value = unit == "°C" ? (fahrenheit - 32) * 5 / 9 : fahrenheit
        return value.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(value))" : String(format: "%.1f", value)
    }

    private func converted(_ text: String) -> Double? {
        text.isEmpty ? nil : Double(text).map { CookPlanEngine.fahrenheit($0, unit) }
    }

    private var probeNames: [String] {
        let fromProbes = store.library.probes.compactMap { $0.objectValue }.filter { $0["cookId"]?.stringValue == cookId }.compactMap { $0["name"]?.stringValue }
        let fromReadings = store.readings(for: cookId).map(\.probeName)
        return Array(Set(fromProbes + fromReadings)).sorted()
    }

    private var hasRecording: Bool {
        store.library.recordings.contains { $0.objectValue?["cookId"]?.stringValue == cookId }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Alert name", text: $title)
                        .accessibilityIdentifier("alert-editor-name")
                    Picker("Watch", selection: $rule.type) {
                        ForEach(CookAlertEngine.types, id: \.type) { Text($0.label).tag($0.type) }
                    }
                    .onChange(of: rule.type) { _, value in if value != "target" { rule.dishId = nil } }
                }
                if ["target", "pit_range"].contains(rule.type) {
                    Section {
                        Picker("Temperature unit", selection: $unit) {
                            Text("°F").tag("°F"); Text("°C").tag("°C")
                        }
                        .pickerStyle(.segmented)
                        .onChange(of: unit) { oldUnit, newUnit in
                            // Field text is in the old unit: convert to
                            // °F, then render in the new unit (Android).
                            target = Double(target).map { Self.text(CookPlanEngine.fahrenheit($0, oldUnit), newUnit) } ?? target
                            low = Double(low).map { Self.text(CookPlanEngine.fahrenheit($0, oldUnit), newUnit) } ?? low
                            high = Double(high).map { Self.text(CookPlanEngine.fahrenheit($0, oldUnit), newUnit) } ?? high
                        }
                    }
                }
                if rule.type == "target" {
                    Section {
                        Picker("Dish", selection: $rule.dishId) {
                            Text("Whole cook").tag(String?.none)
                            ForEach(store.dishRecords(cookId)) { dish in Text(dish.name).tag(Optional(dish.id)) }
                        }
                        Picker("During", selection: $rule.stage) {
                            Text("Cooking").tag("cooking"); Text("Resting").tag("resting"); Text("Holding").tag("holding")
                        }
                        HStack {
                            Text("Target \(unit)")
                            Spacer()
                            TextField("Target", text: $target)
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 100)
                                .accessibilityIdentifier("alert-editor-target")
                        }
                        Text("Reaching the target asks you to check. It does not mark the food done.")
                            .font(.caption)
                            .foregroundStyle(PitTechStyle.supporting)
                    }
                }
                if rule.type == "pit_range" {
                    Section {
                        HStack { Text("Low \(unit)"); Spacer(); TextField("Low", text: $low).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100) }
                        HStack { Text("High \(unit)"); Spacer(); TextField("High", text: $high).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100) }
                        HStack { Text("Sustained for minutes"); Spacer(); TextField("Minutes", text: $dwell).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 100) }
                    }
                }
                if ["target", "pit_range"].contains(rule.type) {
                    Section {
                        Picker("Probe", selection: $rule.probeName) {
                            Text("Any matching probe").tag("")
                            ForEach(probeNames, id: \.self) { Text($0).tag($0) }
                        }
                    }
                }
                if rule.type == "missing" {
                    Section {
                        HStack { Text("No readings for minutes"); Spacer(); TextField("Minutes", text: $missing).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 100) }
                    }
                }
                if ["missing", "offline"].contains(rule.type), !hasRecording {
                    Section {
                        Text("Attach a grill from Live before this connection alert can run.")
                            .font(.caption)
                            .foregroundStyle(PitTechStyle.supporting)
                    }
                }
                Section {
                    Text("Up to 3 alerts, at least 15 minutes apart. Acknowledge stops repeats until recovery; snooze leaves the condition visible.")
                        .font(.caption)
                        .foregroundStyle(PitTechStyle.supporting)
                    if let error { Text(error).foregroundStyle(PitTechStyle.flag) }
                }
            }
            .navigationTitle("Cook alert")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss(); onClose() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save alert") { save() }
                        .accessibilityIdentifier("alert-editor-save")
                }
            }
        }
    }

    private func save() {
        do {
            var saved = rule
            saved.targetF = converted(target)
            saved.lowF = converted(low)
            saved.highF = converted(high)
            guard let dwellMinutes = Int64(dwell) else { throw CookAlertError.invalid("Enter whole dwell minutes.") }
            guard let missingMinutes = Int64(missing) else { throw CookAlertError.invalid("Enter whole missing minutes.") }
            saved.dwellMillis = dwellMinutes * 60_000
            saved.missingMillis = missingMinutes * 60_000
            try CookAlertEngine.validate(saved)
            guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw CookAlertError.invalid("Give the alert a name.")
            }
            try store.saveCookAlert(cookId: cookId, title: title, rule: saved)
            dismiss()
            onClose()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
