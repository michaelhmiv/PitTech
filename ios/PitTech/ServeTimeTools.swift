import SwiftUI

/// The not-started badge every plan surface carries until its cook
/// starts (Flowing Slice 4 §3): a plan is a promise, not a recording.
struct NotStartedBadge: View {
    var identifier = "serve-plan-not-started"

    var body: some View {
        Text("Not started")
            .font(PitTechFont.metaLabel)
            .foregroundStyle(PitTechStyle.emberText)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(PitTechStyle.surfaceRaised, in: Capsule())
            .accessibilityIdentifier(identifier)
    }
}

/// The Start Cook review setup a planned start reviews: the plan's book,
/// carried whole (Android's previewScheduledCook).
enum ServePlanStart {
    static func setupDraft(for plan: ServePlan) -> CookSetupDraft {
        var setup = CookSetupDraft(
            sourceLabel: "Planned for \(PlanBoardFormat.dateTime(plan.serveAtUtcMillis))",
            title: plan.book.draft.title
        )
        setup.smokerName = plan.book.draft.smokerName
        setup.setpointText = plan.book.draft.setpointText
        setup.setpointUnit = plan.book.draft.temperatureUnit
        setup.notes = plan.book.draft.notes
        setup.fuelType = plan.book.draft.fuelType
        setup.woodBlend = plan.book.draft.woodBlend
        setup.dishes = plan.book.draft.dishes.map { dish in
            CookSetupDish(
                name: dish.name, foodType: dish.foodType, cut: dish.cut,
                weightText: dish.weightText, weightUnit: dish.weightUnit,
                rub: dish.rub.isEmpty ? nil : dish.rub
            )
        }
        return setup
    }
}

/// Meals' Upcoming section (Slice 4 §3): saved plans with their day
/// boards, the review-and-start door, and the planner's front door.
struct UpcomingPlansSection: View {
    @Environment(CookStore.self) private var store
    var onStarted: (String) -> Void = { _ in }
    @State private var startRequest: PlanStartRequest?
    @State private var error: String?

    private struct PlanStartRequest: Identifiable {
        let planId: String
        let setup: CookSetupDraft
        var id: String { planId }
    }

    var body: some View {
        let plans = store.upcomingServePlans()
        VStack(alignment: .leading, spacing: 12) {
            if !plans.isEmpty {
                TurnInMetaLabel("Upcoming")
                ForEach(plans, id: \.id) { entry in
                    planCard(entry.id, entry.plan)
                }
            }
            Button { store.newServePlan() } label: {
                Label("Plan a serving time", systemImage: "calendar.badge.clock")
                    .font(PitTechFont.bodyEmphasis)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("plan-serving-time")
            if let error {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
        }
        .sheet(item: $startRequest) { request in
            StartCookSheet(initialSetup: request.setup, onStarted: { cookId in
                do {
                    try store.attachServePlan(
                        request.planId, cookId: cookId,
                        orderedDishIds: store.dishRecords(cookId).map(\.id)
                    )
                } catch {
                    store.servePlanNotice = error.localizedDescription
                }
                onStarted(cookId)
            })
            .modifier(ScreenshotTextSize())
        }
    }

    private func planCard(_ id: String, _ plan: ServePlan) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("Upcoming · \(plan.book.name)")
                    .font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.ink)
                Spacer()
                NotStartedBadge(identifier: "upcoming-not-started")
            }
            Text(PlanBoardFormat.serveLine(plan))
                .font(.footnote).foregroundStyle(.secondary)
            PlanDayBoardView(plan: plan, timeline: PlanBoard.resolvedTimeline(plan: plan, chosenPitF: nil))
            HStack(spacing: 8) {
                Button("Review and start") {
                    startRequest = PlanStartRequest(planId: id, setup: ServePlanStart.setupDraft(for: plan))
                }
                .buttonStyle(.borderedProminent)
                Button("Adjust schedule") { store.openServePlanEditor(plan: plan, id: id) }
                    .buttonStyle(.bordered)
                Button("Delete plan", role: .destructive) {
                    do { try store.deleteServePlan(id) } catch { self.error = error.localizedDescription }
                }
                .buttonStyle(.bordered)
            }
            .font(.subheadline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }
}

/// A decimal-hours text field over a minutes value (the editor's
/// duration vocabulary, as on Android: "7.5" means 450 minutes).
private struct HoursField: View {
    let label: String
    @Binding var minutes: Int64

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            TextField(label, text: Binding(
                get: { Self.format(minutes) },
                set: { if let hours = Double($0) { minutes = Int64((hours * 60).rounded()) } }
            ))
            .keyboardType(.decimalPad)
            .multilineTextAlignment(.trailing)
            .frame(width: 80)
            Text("h").foregroundStyle(.secondary)
        }
    }

    static func format(_ minutes: Int64) -> String {
        let hours = Double(minutes) / 60
        return hours == hours.rounded() ? String(Int64(hours)) : String(format: "%.2f", hours).replacingOccurrences(of: "0+$", with: "", options: .regularExpression).replacingOccurrences(of: "\\.$", with: "", options: .regularExpression)
    }
}

/// The serve-plan editor (Slice 4 §3): serve hero, day board, the
/// serving-time fields (with the DST twice-occurring choice), per-dish
/// cards with duration editors and recorded ranges, recipe seeding, and
/// guided steps. Saving persists the stretched plan the board showed.
struct ServePlanEditorView: View {
    @Environment(CookStore.self) private var store
    let planId: String?
    @State private var plan: ServePlan
    @State private var chosenPitF: Double?
    @State private var dateText: String
    @State private var timeText: String
    @State private var zoneText: String
    @State private var laterOffset: Bool
    @State private var error: String?
    @State private var editingScheduleDish: Int?
    @State private var editingStep: PlaybookStep?
    @State private var showingSeedPicker = false

    init(plan: ServePlan, planId: String?, chosenPitF: Double?) {
        self.planId = planId
        _plan = State(initialValue: plan)
        _chosenPitF = State(initialValue: chosenPitF)
        _laterOffset = State(initialValue: false)
        let zone = TimeZone(identifier: plan.zoneId) ?? .current
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: Date(timeIntervalSince1970: Double(plan.serveAtUtcMillis) / 1000))
        _dateText = State(initialValue: String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0))
        _timeText = State(initialValue: String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0))
        _zoneText = State(initialValue: plan.zoneId)
    }

    private var timeline: PlanTimelineResult {
        PlanBoard.resolvedTimeline(plan: plan, chosenPitF: chosenPitF)
    }

    private var occursTwice: Bool {
        ServeTimePlanner.occursTwice(date: dateText, time: timeText, zoneId: zoneText)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Serve \(PlanBoardFormat.dateTime(plan.serveAtUtcMillis))")
                                .font(PitTechFont.bannerCard).foregroundStyle(PitTechStyle.ink)
                            Text(plan.zoneId).font(.footnote).foregroundStyle(.secondary)
                        }
                        Spacer()
                        NotStartedBadge()
                    }
                    PlanDayBoardView(plan: plan, timeline: timeline, chosenPitF: chosenPitF) { pit in
                        chosenPitF = pit
                    }
                }
                Section("Cook name") {
                    TextField("Cook name", text: Binding(
                        get: { plan.book.name },
                        set: { plan.book.name = $0; plan.book.draft.title = $0 }
                    ))
                }
                Section("Serving time") {
                    TextField("Date (yyyy-MM-dd)", text: $dateText)
                        .onChange(of: dateText) { _, _ in recomputeServeAt() }
                    TextField("Time (HH:mm)", text: $timeText)
                        .onChange(of: timeText) { _, _ in recomputeServeAt() }
                    TextField("Time zone", text: $zoneText)
                        .onChange(of: zoneText) { _, _ in recomputeServeAt() }
                    if occursTwice {
                        Picker("That clock time happens twice — pick one", selection: $laterOffset) {
                            Text("Earlier").tag(false)
                            Text("Later").tag(true)
                        }
                        .onChange(of: laterOffset) { _, _ in recomputeServeAt() }
                    }
                    Stepper("Buffer \(plan.bufferMinutes) min", value: $plan.bufferMinutes, in: 0 ... 1440, step: 5)
                }
                ForEach(Array(plan.book.draft.dishes.enumerated()), id: \.offset) { index, _ in
                    dishSection(index)
                }
                Section {
                    Button("Add dish or side") { addBlankDish() }
                    Button("Add from a recipe") { showingSeedPicker = true }
                        .accessibilityIdentifier("plan-add-from-recipe")
                }
                Section("Guided steps") {
                    ForEach(plan.book.steps) { step in
                        Button { editingStep = step } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(step.title.isEmpty ? step.action : step.title)
                                        .foregroundStyle(PitTechStyle.ink)
                                    Text(stepSummary(step)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    .onDelete { plan.book.steps.remove(atOffsets: $0) }
                    Button("Add step") {
                        editingStep = PlaybookStep(id: UUID().uuidString.lowercased(), action: "check_in", title: "")
                    }
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("Plan serving time")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { store.servePlanEditorPlan = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save plan") { save() }
                }
            }
            .sheet(isPresented: Binding(
                get: { editingScheduleDish != nil },
                set: { if !$0 { editingScheduleDish = nil } }
            )) {
                if let index = editingScheduleDish, plan.schedules.indices.contains(index) {
                    DishScheduleEditorView(
                        dish: plan.book.draft.dishes[index],
                        draft: plan.book.draft,
                        wrapped: plan.book.steps.contains { $0.action == "wrap" && $0.dishIndex == index },
                        evidence: store.planEvidence(),
                        schedule: Binding(
                            get: { plan.schedules[index] },
                            set: { plan.schedules[index] = $0 }
                        )
                    )
                    .modifier(ScreenshotTextSize())
                }
            }
            .sheet(item: $editingStep) { step in
                StepEditorView(step: step, dishes: plan.book.draft.dishes) { saved in
                    if let index = plan.book.steps.firstIndex(where: { $0.id == saved.id }) {
                        plan.book.steps[index] = saved
                    } else {
                        plan.book.steps.append(saved)
                    }
                    editingStep = nil
                }
                .modifier(ScreenshotTextSize())
            }
            .sheet(isPresented: $showingSeedPicker) {
                PlanSeedPickerView(options: store.planSeedOptions()) { lane in
                    var schedule = lane.schedule
                    schedule.dishIndex = plan.book.draft.dishes.count
                    plan.book.draft.dishes.append(lane.dish)
                    plan.schedules.append(schedule)
                    showingSeedPicker = false
                }
                .modifier(ScreenshotTextSize())
            }
        }
    }

    private func dishSection(_ index: Int) -> some View {
        Section {
            TextField("Dish name", text: Binding(
                get: { plan.book.draft.dishes[index].name },
                set: { plan.book.draft.dishes[index].name = $0 }
            ))
            .font(PitTechFont.bodyEmphasis)
            TextField("Food type", text: Binding(
                get: { plan.book.draft.dishes[index].foodType },
                set: { plan.book.draft.dishes[index].foodType = $0 }
            ))
            TextField("Cut", text: Binding(
                get: { plan.book.draft.dishes[index].cut },
                set: { plan.book.draft.dishes[index].cut = $0 }
            ))
            HStack {
                TextField("Weight", text: Binding(
                    get: { plan.book.draft.dishes[index].weightText },
                    set: { plan.book.draft.dishes[index].weightText = $0 }
                ))
                .keyboardType(.decimalPad)
                Picker("Unit", selection: Binding(
                    get: { plan.book.draft.dishes[index].weightUnit },
                    set: { plan.book.draft.dishes[index].weightUnit = $0 }
                )) {
                    ForEach(["lb", "oz", "g", "kg"], id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(width: 170)
            }
            TextField("Starting condition (optional)", text: Binding(
                get: { plan.book.draft.dishes[index].startingCondition ?? "" },
                set: { plan.book.draft.dishes[index].startingCondition = $0.isEmpty ? nil : $0 }
            ))
            Picker("Bone", selection: Binding(
                get: { plan.book.draft.dishes[index].boneIn.map { $0 ? 1 : 2 } ?? 0 },
                set: { plan.book.draft.dishes[index].boneIn = $0 == 0 ? nil : $0 == 1 }
            )) {
                Text("Unrecorded").tag(0)
                Text("Bone-in").tag(1)
                Text("No bone").tag(2)
            }
            TextField("Placement", text: Binding(
                get: { plan.book.draft.dishes[index].placement },
                set: { plan.book.draft.dishes[index].placement = $0 }
            ))
            TextField("Grade or source", text: Binding(
                get: { plan.book.draft.dishes[index].grade },
                set: { plan.book.draft.dishes[index].grade = $0 }
            ))
            TextField("Thickness", text: Binding(
                get: { plan.book.draft.dishes[index].thickness },
                set: { plan.book.draft.dishes[index].thickness = $0 }
            ))
            TextField("Prep notes", text: Binding(
                get: { plan.book.draft.dishes[index].prep },
                set: { plan.book.draft.dishes[index].prep = $0 }
            ))
            if let schedule = plan.schedules.first(where: { $0.dishIndex == index }) {
                VStack(alignment: .leading, spacing: 3) {
                    computedLines(index, schedule)
                    Text(schedule.evidenceNote).font(.caption).foregroundStyle(.secondary)
                }
            }
            Button("Durations") { editingScheduleDish = index }
            if plan.book.draft.dishes.count > 1 {
                Button("Remove dish", role: .destructive) { removeDish(index) }
            }
        } header: {
            Text("Dish \(index + 1)")
        }
    }

    private func computedLines(_ index: Int, _ schedule: DishSchedule) -> some View {
        let prep = plan.serveAtUtcMillis - (schedule.cookMaxMinutes + schedule.restMinutes + schedule.holdMinutes + plan.bufferMinutes + schedule.prepMinutes + schedule.preheatMinutes) * 60_000
        let preheat = plan.serveAtUtcMillis - (schedule.cookMaxMinutes + schedule.restMinutes + schedule.holdMinutes + plan.bufferMinutes + schedule.preheatMinutes) * 60_000
        let foodOn = ServeTimePlanner.foodOn(plan: plan, schedule: schedule)
        let ready = ServeTimePlanner.readyWindow(plan: plan, schedule: schedule)
        return VStack(alignment: .leading, spacing: 2) {
            Text("Prep \(PlanBoardFormat.clock(prep)) · Preheat \(PlanBoardFormat.clock(preheat))")
            Text("Food on \(PlanBoardFormat.clock(foodOn)) · Ready \(PlanBoardFormat.clock(ready.earliest))–\(PlanBoardFormat.clock(ready.latest))")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    private func stepSummary(_ step: PlaybookStep) -> String {
        let dish = step.dishIndex.map { plan.book.draft.dishes.indices.contains($0) ? plan.book.draft.dishes[$0].name : "All dishes" } ?? "All dishes"
        return "\(dish) · \(step.action) · \(step.trigger)"
    }

    private func recomputeServeAt() {
        if let instant = try? ServeTimePlanner.localGoal(date: dateText, time: timeText, zoneId: zoneText, laterOffset: laterOffset) {
            plan.serveAtUtcMillis = instant
            plan.zoneId = zoneText
        }
    }

    private func addBlankDish() {
        let index = plan.book.draft.dishes.count
        plan.book.draft.dishes.append(PlaybookDishDraft(name: "Side dish", foodType: "Other"))
        plan.schedules.append(DishSchedule(dishIndex: index, cookMinMinutes: 480, cookMaxMinutes: 600, evidenceNote: DishSchedule.unseededNote))
    }

    private func removeDish(_ index: Int) {
        plan.book.draft.dishes.remove(at: index)
        plan.book.steps = plan.book.steps.compactMap { step in
            guard let dish = step.dishIndex else { return step }
            if dish == index { return nil }
            var step = step
            if dish > index { step.dishIndex = dish - 1 }
            return step
        }
        plan.book.targets = plan.book.targets.compactMap { target in
            guard let dish = target.dishIndex else { return target }
            if dish == index { return nil }
            var target = target
            if dish > index { target.dishIndex = dish - 1 }
            return target
        }
        plan.schedules = plan.schedules.filter { $0.dishIndex != index }.map { schedule in
            var schedule = schedule
            if schedule.dishIndex > index { schedule.dishIndex -= 1 }
            return schedule
        }
    }

    private func save() {
        do {
            plan.serveAtUtcMillis = try ServeTimePlanner.localGoal(date: dateText, time: timeText, zoneId: zoneText, laterOffset: laterOffset)
            plan.zoneId = zoneText
            if plan.schedules.contains(where: { $0.evidenceNote == DishSchedule.unseededNote }) {
                throw ServePlanError.invalid("Review each dish's duration range.")
            }
            let resolved = PlanBoard.resolvedTimeline(plan: plan, chosenPitF: chosenPitF)
            let stretched = PlanBoard.stretchedPlan(plan, timeline: resolved)
            try store.saveServePlan(stretched, id: planId)
            store.servePlanNotice = "Plan saved — not started. Recording starts only when you start the cook."
            store.servePlanEditorPlan = nil
            store.servePlanEditorId = nil
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// One dish's duration editor: the range, the supporting stages, the pit
/// target, and the recorded range its own history offers.
struct DishScheduleEditorView: View {
    @Environment(\.dismiss) private var dismiss
    let dish: PlaybookDishDraft
    let draft: PlaybookCookDraft
    let wrapped: Bool
    let evidence: [DurationEvidence]
    @Binding var schedule: DishSchedule
    @State private var error: String?
    @State private var initialMin: Int64?
    @State private var initialMax: Int64?
    @State private var appliedMin: Int64?
    @State private var appliedMax: Int64?

    /// Android's gate + matcher: a schedule naming different equipment
    /// than the plan's smoker has no comparable history by definition.
    private var matches: [DurationEvidence] {
        if !schedule.method.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           schedule.method.lowercased() != draft.smokerName.lowercased() { return [] }
        return ServeTimePlanner.comparable(dish: dish, draft: draft, evidence: evidence, wrapped: wrapped)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Evidence") {
                    Text(schedule.evidenceNote).font(.footnote).foregroundStyle(.secondary)
                    if schedule.evidenceCount > 0 {
                        Text("Based on \(schedule.evidenceCount) recorded cook\(schedule.evidenceCount == 1 ? "" : "s").")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                if matches.isEmpty {
                    Section("Recorded range") {
                        Text("No comparable history. Enter a range you are comfortable planning around.")
                            .font(.footnote)
                    }
                } else {
                    Section("Recorded range") {
                        Text("\(matches.count) comparable recorded cooks · \(matches.map(\.durationMinutes).min() ?? 0)–\(matches.map(\.durationMinutes).max() ?? 0) min from Food on to Remove. Rest is separate.")
                            .font(.footnote)
                        if matches.count == 1 {
                            Text("One cook is a reference. Enter your own planning range.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        if matches.count >= 2 {
                            Button("Use recorded range") {
                                let low = matches.map(\.durationMinutes).min() ?? schedule.cookMinMinutes
                                let high = matches.map(\.durationMinutes).max() ?? schedule.cookMaxMinutes
                                schedule.cookMinMinutes = low
                                schedule.cookMaxMinutes = high
                                schedule.evidenceCount = matches.count
                                schedule.evidenceNote = "\(matches.count) recorded cooks with similar cut, smoker, size, start, setpoint and wrap"
                                appliedMin = low; appliedMax = high
                            }
                        }
                    }
                }
                Section("Durations") {
                    HoursField(label: "Cook, shortest", minutes: $schedule.cookMinMinutes)
                    HoursField(label: "Cook, longest", minutes: $schedule.cookMaxMinutes)
                    HoursField(label: "Prep", minutes: $schedule.prepMinutes)
                    HoursField(label: "Preheat", minutes: $schedule.preheatMinutes)
                    HoursField(label: "Rest", minutes: $schedule.restMinutes)
                    HoursField(label: "Hold", minutes: $schedule.holdMinutes)
                }
                Section("Pit") {
                    HStack {
                        Text("Target pit temp (°F)")
                        Spacer()
                        TextField("Optional", text: Binding(
                            get: { schedule.pitTemperatureF.map { PlanBoard.formatTemp($0).replacingOccurrences(of: "°", with: "") } ?? "" },
                            set: { schedule.pitTemperatureF = Double($0) }
                        ))
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                    }
                    TextField("Equipment", text: $schedule.method)
                }
                Section("Note") {
                    TextField("Evidence note", text: $schedule.evidenceNote, axis: .vertical)
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .onAppear {
                if initialMin == nil { initialMin = schedule.cookMinMinutes; initialMax = schedule.cookMaxMinutes }
            }
            .navigationTitle("Durations")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        var probe = schedule
                        probe.cookMinMinutes = max(1, probe.cookMinMinutes)
                        if probe.cookMaxMinutes < probe.cookMinMinutes { probe.cookMaxMinutes = probe.cookMinMinutes }
                        // Android's durations editor: typing your own
                        // durations discards the recorded count and note;
                        // the recorded range, once applied, stands. The
                        // unseeded sentinel never survives a save.
                        if let initialMin, let initialMax,
                           probe.cookMinMinutes != initialMin || probe.cookMaxMinutes != initialMax {
                            let matchesApplied = appliedMin.map { probe.cookMinMinutes == $0 && probe.cookMaxMinutes == appliedMax } ?? false
                            if !matchesApplied {
                                probe.evidenceCount = 0
                                probe.evidenceNote = "Your duration range"
                            }
                        }
                        if probe.evidenceNote == DishSchedule.unseededNote {
                            probe.evidenceNote = "Your duration range"
                        }
                        schedule = probe
                        dismiss()
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

/// One guided step's editor (the plan's steps are the Live guidance's
/// source; the wrap step is what the board times from).
struct StepEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var step: PlaybookStep
    let dishes: [PlaybookDishDraft]
    var onSave: (PlaybookStep) -> Void

    init(step: PlaybookStep, dishes: [PlaybookDishDraft], onSave: @escaping (PlaybookStep) -> Void) {
        _step = State(initialValue: step)
        self.dishes = dishes
        self.onSave = onSave
    }

    private static let actions = ["food_on", "spritz", "wrap", "check_in", "remove", "rest_start", "hold_start", "dish_done"]
    private static let triggers = ["manual", "elapsed", "after_action", "temperature", "clock"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Step") {
                    TextField("Title", text: $step.title)
                    Picker("Action", selection: $step.action) {
                        ForEach(Self.actions, id: \.self) { Text($0).tag($0) }
                    }
                    Picker("Dish", selection: Binding(
                        get: { step.dishIndex ?? -1 },
                        set: { step.dishIndex = $0 < 0 ? nil : $0 }
                    )) {
                        Text("All dishes").tag(-1)
                        ForEach(Array(dishes.enumerated()), id: \.offset) { index, dish in
                            Text(dish.name).tag(index)
                        }
                    }
                    TextField("Instructions", text: $step.instructions, axis: .vertical)
                }
                Section("Trigger") {
                    Picker("Trigger", selection: $step.trigger) {
                        ForEach(Self.triggers, id: \.self) { Text($0).tag($0) }
                    }
                    if step.trigger == "elapsed" || step.trigger == "after_action" {
                        Stepper("After \(step.minutes) min", value: $step.minutes, in: 0 ... 10080, step: 5)
                    }
                    if step.trigger == "temperature" {
                        HStack {
                            Text("At (°F)")
                            Spacer()
                            TextField("Temperature", text: Binding(
                                get: { step.temperatureF.map { String(Int64($0)) } ?? "" },
                                set: { step.temperatureF = Double($0) }
                            ))
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 80)
                        }
                        TextField("Probe name", text: $step.probeName)
                    }
                    TextField("Stage", text: $step.stage)
                }
            }
            .scrollContentBackground(.hidden)
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("Guided step")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save step") { onSave(step); dismiss() }
                }
            }
        }
    }
}

/// The "Add from a recipe" picker: each saved recipe with the lane its
/// history seeds, or the honest reason it can't seed one.
struct PlanSeedPickerView: View {
    @Environment(\.dismiss) private var dismiss
    let options: [PlanSeedOption]
    var onPick: (SeededPlanLane) -> Void

    var body: some View {
        NavigationStack {
            List {
                if options.isEmpty {
                    Text("No saved recipes yet.").foregroundStyle(.secondary)
                }
                ForEach(options) { option in
                    Button {
                        if let lane = option.lane { onPick(lane) }
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(option.title).font(PitTechFont.bodyEmphasis).foregroundStyle(PitTechStyle.ink)
                            Text(option.note).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(option.lane == nil)
                }
            }
            .scrollContentBackground(.hidden)
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("Add from a recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}
