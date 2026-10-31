import SwiftUI
import UIKit
import UserNotifications

/// Flowing Slice 3, E4 (spec §5): a finished cook opens on its story,
/// and the one-time "Protect your cook log?" prompt's eligibility —
/// the cook was ALREADY finished when it was opened (an in-session
/// finish keeps the completion-choices flow), the prompt has never
/// been shown, and this device has never synced with automatic sync
/// off. Pure and matrix-tested (FlowingE4LogicTests); mirrors Android.
enum FlowingE4 {
    static func initialCookSection(status: String) -> String { status == "completed" ? "Timeline" : "Live" }
    static func shouldPromptProtectLog(status: String, promptShown: Bool, lastSyncedAt: Int64, automaticSync: Bool) -> Bool {
        status == "completed" && !promptShown && lastSyncedAt <= 0 && !automaticSync
    }
}

/// Per-dish anti-jitter smoothers for the Live board's ETA (Slice 1's
/// PaceRangeSmoother is a class; one instance per cook+dish, held across
/// the board's one-second ticks without touching SwiftUI state).
private final class LivePaceSmoothers { var byDish: [String: PaceRangeSmoother] = [:] }

struct CookDetailView: View {
    @Environment(CookStore.self) private var store
    @Environment(NativeTelemetryRuntime.self) private var telemetry
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(NativePrivateSyncCoordinator.self) private var sync
    let cookId: String
    @State private var section = "Live"
    /// E4: the opening section and the protect prompt's eligibility are
    /// latched from the cook's status when the screen first opens.
    @State private var sectionLatched = false
    @State private var protectEligibleAtOpen = false
    @State private var protectPromptShown = UserDefaults.standard.bool(forKey: "protect-log-prompt-shown")
    @State private var paceSmoothers = LivePaceSmoothers()
    @State private var chartContext: LiveChartContext?
    @State private var notificationStatus: UNAuthorizationStatus?
    @State private var overnightTestSent = false
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
    /// One pushed sub-surface at a time: two stacked
    /// navigationDestination(isPresented:) modifiers on one view can
    /// silently starve the second, so Reminders and Alerts (#183)
    /// share a single route-driven destination.
    @State private var detailRoute: String?
    @State private var alertFocusId: String?
    @State private var legacyCheckIn: LegacyReminder?
    @State private var showingResults = false
    @State private var reminderCheckInRuleId: String?
    @ScaledMetric(relativeTo: .caption) private var quickBarHeight: CGFloat = 52
    @State private var sharingDraftId: String?
    private var cook: CookRecord? { store.library.cooks.first { $0.id == cookId } }
    private var readings: [TemperatureSample] { store.readings(for: cookId) }
    private var events: [TimelineEntry] { store.events(for: cookId) }

    // MARK: - Flowing Slice 2: pace, verdict, ETA

    private func fahrenheit(_ value: Double, _ unit: String) -> Double { unit.contains("C") ? value * 9 / 5 + 32 : value }
    private func displayTemp(_ value: Double, _ unit: String) -> Double {
        let f = fahrenheit(value, unit)
        return store.temperatureUnit == "C" ? (f - 32) * 5 / 9 : f
    }
    private func clockTime(_ millis: Int64) -> String { Date(timeIntervalSince1970: Double(millis) / 1_000).formatted(date: .omitted, time: .shortened) }
    /// A clock range with a shared meridiem stated once — "5:35–6:05 PM", the book's ETA shape.
    private func clockRange(_ startMillis: Int64, _ endMillis: Int64) -> String {
        let start = clockTime(startMillis), end = clockTime(endMillis)
        let startMeridiem = start.suffix(2), endMeridiem = end.suffix(2)
        if startMeridiem == endMeridiem, startMeridiem == "AM" || startMeridiem == "PM" {
            return start.dropLast(2).trimmingCharacters(in: .whitespaces) + "–" + end
        }
        return "\(start)–\(end)"
    }
    private func minutesDuration(_ minutes: Double) -> String {
        let total = Int(minutes)
        return total >= 60 ? "\(total / 60)h \(total % 60)m" : total >= 1 ? "\(total)m" : "<1m"
    }

    private struct LiveGuidanceSnapshot {
        let verdict: LiveVerdict?
        let eta: LiveEta
        let etaLatestUtcMillis: Int64?
        let leadDishId: String?
    }

    /// The Slice 1 pace engine's first iOS consumer: assess the lead dish,
    /// publish through the anti-jitter smoother, and merge the verdict —
    /// the same pipeline as Android's Live board.
    private func liveGuidance(_ cook: CookRecord, now: Date) -> LiveGuidanceSnapshot {
        let nowMillis = Int64(now.timeIntervalSince1970 * 1_000)
        let lead = store.dishRecords(cookId).first
        var assessment: PaceAssessment?
        var displayFinish: FinishEstimate?
        if let lead {
            let series = readings
                .filter { $0.dishId == lead.id && $0.qualityStatus == "valid" && ["food", "food_probe"].contains($0.measurementType) }
                .map { PaceReading(atUtcMillis: Int64($0.measuredAt.timeIntervalSince1970 * 1_000), fahrenheit: fahrenheit($0.value, $0.unit)) }
                .sorted { $0.atUtcMillis < $1.atUtcMillis }
            let targetRows = store.library.targets.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["targetType"]?.stringValue == "personal_finish" }
            let targetRow = targetRows.first { $0["dishId"]?.stringValue == lead.id } ?? targetRows.first { $0["dishId"] == nil }
            if let targetValue = targetRow?["value"]?.numberValue, let targetUnit = targetRow?["unit"]?.stringValue {
                let cut = store.library.dishes.compactMap(\.objectValue).first { $0["id"]?.stringValue == lead.id }?["cut"]?.stringValue
                let history = LiveGuidance.historyFor(archive: store.insightsArchive(), currentCookId: cookId, currentDishId: lead.id, cut: cut)
                let foodOn = store.library.actions(for: cookId).filter { $0.action == "food_on" && ($0.dishIds.isEmpty || $0.dishIds.contains(lead.id)) }.map { Int64($0.at) }.min() ?? cook.startedAtUtcMillis
                let result = CookPaceEngine.assess(readings: series, targetF: fahrenheit(targetValue, targetUnit), nowUtcMillis: nowMillis, foodOnUtcMillis: foodOn, history: history)
                assessment = result
                let key = "\(cookId)/\(lead.id)"
                let smoother = paceSmoothers.byDish[key] ?? { let created = PaceRangeSmoother(); paceSmoothers.byDish[key] = created; return created }()
                if case .ranged(let range) = result.finish, let published = smoother.publish(range, nowUtcMillis: nowMillis) {
                    displayFinish = .ranged(published)
                } else {
                    displayFinish = result.finish
                }
            }
        }
        // Slice 5: a started serve plan gives PlanOnly cooks their real
        // planning window (the lead dish's, updated by its own events).
        let planWindow: (Int64, Int64)? = {
            guard let lead, let goal = store.serveGoal(cookId: cookId) else { return nil }
            let dishIndex = store.dishRecords(cookId).firstIndex { $0.id == lead.id } ?? 0
            guard let schedule = goal.schedules.first(where: { $0.dishIndex == dishIndex }) else { return nil }
            let window = ServeTimePlanner.updatedReadyWindow(
                plan: goal, schedule: schedule,
                events: store.planEvents(cookId: cookId, dishId: lead.id)
            )
            return (window.earliest, window.latest)
        }()
        let eta = displayFinish.map { LiveGuidance.eta(finish: $0, planWindow: planWindow, formatRange: clockRange) } ?? .none
        var etaLatest: Int64?
        if case .ranged(let range)? = displayFinish { etaLatest = range.latestUtcMillis }
        // The verdict leads only when something stands behind it (a dish
        // assessed, or a reminder pending); paused cooks carry their
        // answer in the paused strip already.
        var verdict: LiveVerdict?
        if cook.isActive, cook.status != "paused", assessment != nil || !pendingReminders.isEmpty {
            verdict = LiveGuidance.verdict(
                needsAttention: store.cookAlerts(cookId: cookId).count { $0.state.active },
                stall: assessment?.stall ?? .unknown,
                finish: displayFinish ?? .planOnly("not enough readings yet"),
                nextReminder: pendingReminders.first.map { (title: $0.title, dueUtcMillis: Int64($0.due)) },
                nowUtcMillis: nowMillis,
                eta: eta,
                formatTime: clockTime,
                formatDuration: minutesDuration)
        }
        return LiveGuidanceSnapshot(verdict: verdict, eta: eta, etaLatestUtcMillis: etaLatest, leadDishId: lead?.id)
    }

    private func etaTitle(_ eta: LiveEta) -> String {
        switch eta {
        case .ranged(let text, _), .plan(let text, _): return text
        case .withheld: return "No finish range yet"
        case .none: return ""
        }
    }
    private func etaBasis(_ eta: LiveEta) -> String {
        switch eta {
        case .ranged(_, let basis), .plan(_, let basis): return basis
        case .withheld(let reason): return reason
        case .none: return ""
        }
    }

    /// The stepper's current value in display degrees: latest setpoint
    /// reading, then the latest stepped target, then the initial setpoint.
    private func currentSetpointDisplay(_ cook: CookRecord) -> Double? {
        // Whichever setpoint statement is LATEST wins — a stepped target
        // (the app's own history) outranks an older device reading, and a
        // newer device reading outranks an older step; the cook's initial
        // setpoint is the base when neither exists.
        let setpoint = readings.filter({ $0.measurementType == "setpoint" }).max(by: { $0.measuredAt < $1.measuredAt })
        let historyRow = store.library.targetHistory.compactMap(\.objectValue)
            .filter({ $0["cookId"]?.stringValue == cookId && $0["targetType"]?.stringValue == "setpoint" })
            .max(by: { ($0["effectiveAt"]?.numberValue ?? 0) < ($1["effectiveAt"]?.numberValue ?? 0) })
        if let historyRow, let value = historyRow["value"]?.numberValue, let unit = historyRow["unit"]?.stringValue,
           setpoint == nil || (historyRow["effectiveAt"]?.numberValue ?? 0) >= setpoint!.measuredAt.timeIntervalSince1970 * 1000 {
            return displayTemp(value, unit)
        }
        if let setpoint { return displayTemp(setpoint.value, setpoint.unit) }
        if let initial = cook.initialSetpointValue { return displayTemp(initial, cook.initialSetpointUnit ?? "°\(store.temperatureUnit)") }
        return nil
    }
    private func currentSetpointText(_ cook: CookRecord) -> String {
        guard let value = currentSetpointDisplay(cook) else { return "—" }
        return "\(value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(value)) : String(format: "%.1f", value))°"
    }
    private func stepPitTarget(_ cook: CookRecord, by delta: Double) {
        guard let current = currentSetpointDisplay(cook) else { return }
        store.setPitTarget(cookId: cookId, value: current + delta, unit: "°\(store.temperatureUnit)")
    }

    // MARK: - Flowing Slice 2 (E3): the Overnight panel

    private var overnightSummary: String {
        switch notificationStatus {
        case .authorized, .provisional: return "Notifications on"
        case .denied: return "Notifications off"
        default: return "Check the net before the night"
        }
    }

    @ViewBuilder private func overnightPanel(_ cook: CookRecord) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            overnightRow(
                ok: notificationStatus == .authorized || notificationStatus == .provisional,
                label: "Notifications",
                detail: notificationStatus == .denied ? "Off — no reminder or test can wake you until notifications are on" : notificationStatus == nil ? "Checking…" : "On — reminders can reach this phone")
            overnightRow(
                ok: readings.last != nil,
                label: "Watching the pit",
                detail: readings.last.map { "Last reading \($0.measuredAt.formatted(date: .omitted, time: .shortened)) · \($0.source)" } ?? "No readings yet — hand-logged entries are all the net has")
            // #183 (E1/E3): the missing-readings alert is part of the
            // net now — provisioned by default when a source is
            // attached, toggleable here like Android's overnight panel.
            if let missing = store.cookAlerts(cookId: cookId).first(where: { $0.rule.type == "missing" }) {
                overnightRow(
                    ok: missing.rule.enabled,
                    label: "Missing readings alert",
                    detail: missing.rule.enabled ? "On — watching for a silent source" : "Off — silence will not wake you")
                Button(missing.rule.enabled ? "Turn off missing-readings alert" : "Turn on missing-readings alert") {
                    store.changeCookAlert(id: missing.id, action: "toggle")
                }
                .frame(minHeight: 44)
                .accessibilityIdentifier("overnight-missing-toggle")
            }
            overnightRow(
                ok: !pendingReminders.isEmpty,
                label: "Next reminder",
                detail: pendingReminders.first.map { "\($0.title) · \(clockTime(Int64($0.due)))" } ?? "None set — nothing is scheduled to check in")
            Button("Send test alert") { OvernightTestAlert.post(cookId: cookId, cookTitle: cook.title); overnightTestSent = true }
                .buttonStyle(.borderedProminent).frame(minHeight: 44).accessibilityIdentifier("overnight-test-alert")
            if overnightTestSent {
                Text("Test alert sent. If it didn't buzz this phone, check notification settings before you trust the night to it.")
                    .font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted).accessibilityIdentifier("overnight-test-sent")
            }
        }
    }

    @ViewBuilder private func overnightRow(ok: Bool, label: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .foregroundStyle(ok ? PitTechStyle.ember : PitTechStyle.marigold)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(PitTechFont.bodyEmphasis)
                Text(detail).font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
            }
        }
    }

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
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { showingDevices = true } label: { Label("Devices", systemImage: "thermometer") }.accessibilityIdentifier("meal-devices")
                    }
                    // Flowing Slice 2 (1.6): Pause and Finish live in the
                    // cook menu now — consequential, infrequent, and out of
                    // the scroll path (the placard and the bottom button
                    // are retired).
                    if cook.isActive {
                        ToolbarItem(placement: .topBarTrailing) {
                            Menu {
                                Button(cook.status == "paused" ? "Resume meal" : "Pause meal") { store.setMealPaused(cookId, paused: cook.status != "paused") }.accessibilityIdentifier("pause-meal")
                                Button("Finish meal", role: .destructive) { finishing = true }.accessibilityIdentifier("finish-meal")
                            } label: { Label("Cook menu", systemImage: "ellipsis.circle") }.accessibilityIdentifier("cook-menu")
                        }
                    }
                }
                .task { notificationStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }
                // PL-20 (#184): this cook is the open one — crash recovery
                // returns here. Device-local diagnostics state only.
                .task { CrashDiagnostics.shared.recordOpenCook(cookId) }
                .onAppear { latchInitialSection() }
                .safeAreaInset(edge: .bottom) { shortcutBar.padding(.horizontal, 16).padding(.vertical, 10).background(PitTechStyle.surface) }
                .navigationDestination(isPresented: Binding(get: { detailRoute != nil }, set: { if !$0 { detailRoute = nil } })) {
                    if detailRoute == "alerts" {
                        CookAlertsView(cookId: cookId, focusAlertId: alertFocusId)
                    } else {
                        MealRemindersView(cookId: cookId, checkInRuleId: reminderCheckInRuleId)
                    }
                }
                .sheet(item: $legacyCheckIn) { reminder in LegacyReminderCheckInView(reminder: reminder) }
                .task {
                    // PL-02: a reminder notification tap opens that reminder's
                    // check-in (the Reminders surface, scrolled to its Due
                    // check-in section), not just the cook.
                    if let ruleId = store.notificationReminderRuleId, store.reminderRule(ruleId)?["cookId"]?.stringValue == cookId {
                        reminderCheckInRuleId = ruleId
                        detailRoute = "reminders"
                        store.notificationReminderRuleId = nil
                    }
                    // PL-03 (#183): an alert tap opens the Alerts surface
                    // at that rule, not just the cook.
                    if let alertId = store.notificationAlertId, store.alertEntry(alertId)?.cookId == cookId {
                        alertFocusId = alertId
                        detailRoute = "alerts"
                        store.notificationAlertId = nil
                    }
                    // #183: a legacy reminder tap opens its "How did it
                    // go?" check-in while the reminder is still pending.
                    if let reminderId = store.notificationLegacyReminderId,
                       let reminder = store.legacyReminder(reminderId), reminder.cookId == cookId, reminder.status == "pending" {
                        legacyCheckIn = reminder
                        store.notificationLegacyReminderId = nil
                    }
                }
                // #183: while Live is open, the monitors re-evaluate on
                // a 15-second cadence (Android's guidance card ticker):
                // save() runs the alert/guidance reconciliations.
                .task {
                    while !Task.isCancelled {
                        try? await Task.sleep(for: .seconds(15))
                        if !Task.isCancelled { store.save() }
                    }
                }
                .sheet(isPresented: $showingDevices) { NativeTelemetryConnectionsView(runtime: telemetry) }
                .sheet(item: $chartContext) { context in LiveChartView(cookId: cookId, leadDishId: context.leadDishId, etaLatestUtcMillis: context.etaLatestUtcMillis) }
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
    /// E4: derive the opening section and the protect prompt's
    /// eligibility from the cook's status at first open, once. A cook
    /// finished in-session never passes through here again, so it
    /// keeps the completion-choices flow.
    private func latchInitialSection() {
        guard !sectionLatched, let cook else { return }
        sectionLatched = true
        section = FlowingE4.initialCookSection(status: cook.status)
        protectEligibleAtOpen = cook.status == "completed"
    }
    private func latchProtectPrompt() {
        protectPromptShown = true
        UserDefaults.standard.set(true, forKey: "protect-log-prompt-shown")
    }
    private var shortcutBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                Button { open() } label: { Label("Log", systemImage: "note.text").frame(minHeight: 44) }.accessibilityIdentifier("meal-log")
                Button { open(category: "temperature") } label: { Label("Temp", systemImage: "thermometer").frame(minHeight: 44) }
                Button { open(category: "photo") } label: { Label("Photo", systemImage: "camera").frame(minHeight: 44) }
                Button { detailRoute = "reminders" } label: { Label("Reminders", systemImage: "bell").frame(minHeight: 44) }
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
                // 1.1: the verdict line — reminders and pace merged into
                // the one answer at the top; sources stay underneath.
                if let verdict = liveGuidance(cook, now: context.date).verdict {
                    TurnInCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(verdict.headline).font(PitTechFont.bodyEmphasis).accessibilityIdentifier("live-verdict-headline")
                            if let detail = verdict.detail { Text(detail).font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted).accessibilityIdentifier("live-verdict-detail") }
                        }
                    }
                }
                // PL-03 (#183): the alerts strip — named acknowledged
                // rules and the needs-attention count, on Live, without
                // opening anything. Silent when the cook has no rules.
                if let strip = store.alertStripSummary(cookId: cookId) {
                    Text(strip)
                        .font(PitTechFont.monoMeta)
                        .foregroundStyle(PitTechStyle.inkMuted)
                        .accessibilityIdentifier("live-alert-strip")
                }
                liveZone(cook, now: context.date)
                // 1.3: the chart is the hero and a door — the same chart
                // engine, inert here; the whole card opens the full chart.
                if !readings.isEmpty {
                    ZStack {
                        CookTemperatureChart(cookId: cookId, onMarker: { _ in }, heroMode: true).allowsHitTesting(false)
                        Rectangle().fill(Color.clear).contentShape(Rectangle())
                            .onTapGesture {
                                let guidance = liveGuidance(cook, now: context.date)
                                chartContext = LiveChartContext(leadDishId: guidance.leadDishId, etaLatestUtcMillis: guidance.etaLatestUtcMillis)
                            }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Full chart")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityIdentifier("live-chart-hero")
                }
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
                        // #183: the guidance plan's next-action card —
                        // renders only when the cook has a plan.
                        CookGuidanceCard(cookId: cookId, now: context.date)
                        if let goal = store.serveGoal(cookId: cookId) {
                            ActiveServeGoalView(cookId: cookId, plan: goal)
                        }
                        MealRecipeActions(cookId: cookId)
                        RecipeGuideMilestones(cookId: cookId, compact: true) { section = "Timeline" }
                    }
                }
                LivePanel(id: "reminders", title: "Reminders", summary: remindersSummary) {
                    Button("Meal reminders") { detailRoute = "reminders" }
                }
                // #183: the Cook Alerts panel — Android's Live alerts
                // surface; the strip above carries the live state.
                LivePanel(id: "alerts", title: "Alerts", summary: store.alertStripSummary(cookId: cookId)) {
                    Button("Cook alerts") { detailRoute = "alerts" }
                        .accessibilityIdentifier("live-cook-alerts")
                }
                if cook.isActive {
                    LivePanel(id: "checklist", title: "Checklist") { CookPrepChecklist(cookId: cookId) }
                }
                LivePanel(id: "timers", title: "Timers") { MealActivityTimers(cookId: cookId) }
                if cook.isActive {
                    LivePanel(id: "overnight", title: "Overnight", summary: overnightSummary) { overnightPanel(cook) }
                }
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
                // TURN-IN (Slice 1): the board's action placard — Log
                // note is the primary placard (d12). Flowing Slice 2
                // (1.6): Pause/Resume moved to the cook menu; the bottom
                // shortcut bar keeps Log/Temp/Photo/Reminders.
                if cook.isActive {
                    TurnInPlacard(systemImage: "note.text", label: "Log note", primary: true) { open() }
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
        // Flowing Slice 2: the lead dish pairs with the pit (below), and
        // the zone carries the living ETA + quick-adjust row.
        let leadDish = dishes.first
        let leadLatest = leadDish.flatMap { dish in readings.filter { $0.dishId == dish.id }.max { $0.measuredAt < $1.measuredAt } }
        let leadTarget = leadDish.flatMap { dish in
            store.library.targets.compactMap(\.objectValue).first { $0["cookId"]?.stringValue == cookId && $0["dishId"]?.stringValue == dish.id && $0["targetType"]?.stringValue == "personal_finish" }
                ?? targets.first { $0["targetType"]?.stringValue == "personal_finish" }
        }
        let leadTrend: (String, Color)? = leadDish.flatMap { dish -> (String, Color)? in
            let history = readings.filter { $0.dishId == dish.id }.sorted { $0.measuredAt < $1.measuredAt }
            guard history.count >= 2 else { return nil }
            let delta = history[history.count - 1].value - history[history.count - 2].value
            if delta > 1 { return ("▲ Climb", PitTechStyle.marigold) }
            if delta < -1 { return ("▼ Drop", PitTechStyle.cobalt) }
            return ("Hold", PitTechStyle.ember)
        }
        let steppedSetpoint: String? = store.library.targetHistory.compactMap(\.objectValue)
            .filter { $0["cookId"]?.stringValue == cookId && $0["targetType"]?.stringValue == "setpoint" }
            .max { ($0["effectiveAt"]?.numberValue ?? 0) < ($1["effectiveAt"]?.numberValue ?? 0) }
            .flatMap { row -> String? in
                guard let value = row["value"]?.numberValue, let unit = row["unit"]?.stringValue else { return nil }
                let display = displayTemp(value, unit)
                return "\(display.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(display)) : String(format: "%.1f", display))°\(store.temperatureUnit)"
            }
        let guidance = liveGuidance(cook, now: now)
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
            // Pit and probe side by side (Slice 0 audit parity): each
            // column carries its label and trend, the scoreboard number,
            // and its reference line (SET / TARGET) underneath.
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        TurnInMetaLabel("Pit")
                        if let trend { TurnInTrendChip(text: trend.0, fill: trend.1) }
                    }
                    if let pit {
                        Text(tempText(pit)).font(PitTechFont.scoreboard).foregroundStyle(PitTechStyle.ink)
                    } else {
                        Text("No reading yet").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                    }
                    // SET chain: latest setpoint reading, then the latest
                    // stepped target from target history, then initial.
                    if let setpoint {
                        Text("SET \(tempText(setpoint))").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    } else if let steppedSetpoint {
                        Text("SET \(steppedSetpoint)").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    } else if let initial = cook.initialSetpointValue {
                        Text("SET \(initial.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(initial)) : String(format: "%.1f", initial))\(cook.initialSetpointUnit ?? "")").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let leadDish {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            TurnInMetaLabel("Probe · \(leadDish.name)")
                            if let leadTrend { TurnInTrendChip(text: leadTrend.0, fill: leadTrend.1) }
                        }
                        if let leadLatest {
                            Text(tempText(leadLatest)).font(PitTechFont.scoreboard).foregroundStyle(PitTechStyle.ink)
                        } else {
                            Text("No reading yet").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                        }
                        if let leadTarget, let targetValue = leadTarget["value"]?.numberValue {
                            Text("TARGET \(targetValue.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(targetValue)) : String(format: "%.1f", targetValue))\(leadTarget["unit"]?.stringValue ?? "°")")
                                .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            // The lead dish's probe progress (d12): bar toward its finish
            // target with the degrees to go.
            if let leadLatest, let leadTarget, let targetValue = leadTarget["value"]?.numberValue, targetValue > 0 {
                ProgressView(value: min(leadLatest.value, targetValue), total: targetValue).tint(PitTechStyle.ember)
                Text("TARGET \(targetValue.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(targetValue)) : String(format: "%.1f", targetValue))\(leadTarget["unit"]?.stringValue ?? leadLatest.unit) · \(Int(max(0, targetValue - leadLatest.value).rounded()))° TO GO")
                    .font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            }
            // 1.2: the living ETA — a range only ever with its basis.
            if guidance.eta != .none {
                VStack(alignment: .leading, spacing: 2) {
                    Text(etaTitle(guidance.eta)).font(PitTechFont.bodyEmphasis).accessibilityIdentifier("live-eta-range")
                    Text(etaBasis(guidance.eta)).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted).accessibilityIdentifier("live-eta-basis")
                }
            }
            // 1.5: quick-adjust — the pit target steps in the app's own
            // target history (never a device write), and a one-tap
            // conditions log for the moment the weather turns.
            if cook.isActive {
                HStack(spacing: 8) {
                    TurnInMetaLabel("Pit target")
                    Button { stepPitTarget(cook, by: -5) } label: { Image(systemName: "minus").frame(width: 32, height: 32) }.accessibilityIdentifier("pit-target-minus")
                    Text(currentSetpointText(cook)).font(PitTechFont.monoReadout).accessibilityIdentifier("pit-target-value")
                    Button { stepPitTarget(cook, by: 5) } label: { Image(systemName: "plus").frame(width: 32, height: 32) }.accessibilityIdentifier("pit-target-plus")
                    Spacer()
                    Button("Conditions changed") { store.addEvent(cookId: cookId, title: "Conditions changed", details: "") }.accessibilityIdentifier("live-conditions-changed")
                }
            }
            // The remaining dishes keep their compact rows.
            ForEach(Array(dishes.dropFirst().enumerated()), id: \.element.id) { _, dish in
                let latest = readings.filter { $0.dishId == dish.id }.max { $0.measuredAt < $1.measuredAt }
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        TurnInBannerText(dish.name)
                        Spacer()
                        Text(latest.map(tempText) ?? "No reading yet").font(PitTechFont.monoReadout).foregroundStyle(PitTechStyle.ink)
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
        // Contain, not override: stamping the card's identifier without
        // an explicit children treatment lets it swallow every
        // descendant identifier inside the zone (the ETA, the stepper,
        // the elapsed readout all answered to "live-zone" instead).
        .accessibilityElement(children: .contain)
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
    /// Enabled rules for this cook whose current occurrence is still
    /// pending, merged with the cook's pending legacy reminders (#183) —
    /// one "next reminder" answer, like Android's overnight panel.
    private var pendingReminders: [(title: String, due: Double)] {
        let rules = store.library.reminderRules.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["enabled"]?.boolValue == true }
        let fromRules = rules.compactMap { rule -> (title: String, due: Double)? in
            guard let id = rule["id"]?.stringValue, let state = store.reminderState(id),
                  state["status"]?.stringValue == "pending", let due = state["dueAtUtcMillis"]?.numberValue else { return nil }
            return (rule["title"]?.stringValue ?? "Reminder", due)
        }
        let fromLegacy = store.legacyReminders(cookId: cookId)
            .filter { $0.status == "pending" }
            .map { (title: $0.title, due: Double($0.dueAtUtcMillis)) }
        return (fromRules + fromLegacy).sorted { $0.due < $1.due }
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
                Button("Reminders") { detailRoute = "reminders" }
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
            // Flowing Slice 3 (E4): the one-time protect-your-log
            // prompt leads the finished cook's story — eligible only
            // when the cook was already finished at open, the prompt
            // has never been shown, and this device has never synced
            // with automatic sync off. Either action latches it.
            if protectEligibleAtOpen, let current = cook,
               FlowingE4.shouldPromptProtectLog(status: current.status, promptShown: protectPromptShown, lastSyncedAt: sync.presentation.lastSyncedAt, automaticSync: sync.presentation.automatic) {
                TurnInCard {
                    VStack(alignment: .leading, spacing: 8) {
                        TurnInBannerText("Protect your cook log?")
                        Text("This cook lives only on this phone right now. Sync keeps an encrypted backup of this journal for signed-in cooks. It never publishes anything.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted)
                        Button("Protect my cook log") { latchProtectPrompt(); account.showingAccount = true }.buttonStyle(.borderedProminent).frame(minHeight: 44)
                        Button("Not now") { latchProtectPrompt() }.buttonStyle(.bordered).frame(minHeight: 44)
                    }
                }.accessibilityIdentifier("protect-log-prompt")
            }
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
