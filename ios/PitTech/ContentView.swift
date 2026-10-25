import SwiftUI

struct ContentView: View {
    @Environment(CookStore.self) private var store
    @Environment(NativePublicCoordinator.self) private var community
    @Environment(NativeRelayCoordinator.self) private var relay
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(\.scenePhase) private var scenePhase
    @State private var telemetry = NativeTelemetryRuntime()
    @State private var selectedTab = "meals"
    // First-run welcome (#187): first launch only, never in UI tests or
    // screenshot runs (fixture launches), identical content/order to Android.
    @AppStorage(WelcomeGate.seenKey) private var welcomeSeen = false
    @State private var mealsRestoreRequested = false
    @State private var showingWelcomeConnections = false

    private var showWelcome: Bool {
        // iOS has no crash-recovery surface today, so no crash can be pending
        // at launch here; the rule matches Android's regardless.
        WelcomeGate.shouldShow(seen: welcomeSeen, crashPendingAtLaunch: false, suppressedForTesting: WelcomeGate.suppressedForTesting)
    }

    var body: some View {
        Group {
            if showWelcome {
                WelcomeView(
                    onStartCooking: { welcomeSeen = true },
                    onRestoreBackup: { welcomeSeen = true; mealsRestoreRequested = true },
                    onConnectThermometer: { welcomeSeen = true; selectedTab = "more"; showingWelcomeConnections = true }
                )
            } else {
                tabRoot
            }
        }
        .sheet(isPresented: $showingWelcomeConnections) {
            NavigationStack { NativeTelemetryConnectionsView(runtime: telemetry, embedded: true) }
        }
    }

    /// Extracted so the TabView modifier chain stays type-checkable.
    private var archiveIntakeBinding: Binding<NativeArchiveIntake?> {
        Binding(get: { store.archiveIntake }, set: { if $0 == nil { store.discardArchiveIntake() } })
    }

    private var tabRoot: some View {
        TabView(selection: $selectedTab) {
            CookListView(restoreRequested: $mealsRestoreRequested)
                .tabItem { Label("Meals", systemImage: "flame") }.tag("meals")
            NavigationStack { SavedRecipesView() }.tabItem { Label("Recipes", systemImage: "book") }.tag("recipes")
            Group { if community.enabled { NativePublicDiscoveryView() } else { DiscoveryWelcomeView() } }.tabItem { Label("Discover", systemImage: "safari") }.tag("discover")
            InsightsView()
                .tabItem { Label("Insights", systemImage: "chart.xyaxis.line") }.tag("insights")
            MoreView(runtime: telemetry).tabItem { Label("More", systemImage: "ellipsis") }.tag("more")
        }
        .onChange(of: community.linkSequence) { _, _ in selectedTab = "discover" }
        .environment(telemetry)
        .tint(PitTechStyle.accent)
        .task {
            telemetry.foreground(scenePhase == .active, store: store)
        }
        .onChange(of: scenePhase) { _, phase in
            telemetry.foreground(phase == .active, store: store)
        }
        .onChange(of: store.library.cooks.map { $0.id + ":" + $0.status }) { _, _ in
            telemetry.foreground(scenePhase == .active, store: store)
        }
        .preferredColorScheme(store.appearance)
        .background(PitTechStyle.canvas)
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("PitTechReminderCook"))) { notification in if let payload = notification.object as? [String: Any] {
            if let id = payload["occurrenceId"] as? String, let at = payload["at"] as? Double { store.recordReminderDelivery(id, at: at) }
            if payload["open"] as? Bool == true {
                store.notificationCookId = payload["cookId"] as? String
                // PL-02: occurrence ids are "<ruleId>:<sequence>" — route the
                // tap to that rule's check-in.
                if let occurrence = payload["occurrenceId"] as? String, let cut = occurrence.lastIndex(of: ":") { store.notificationReminderRuleId = String(occurrence[..<cut]) }
            }
        } }
        .sheet(isPresented: Binding(get: { store.notificationCookId != nil }, set: { if !$0 { store.notificationCookId = nil } })) { if let id = store.notificationCookId { NavigationStack { CookDetailView(cookId: id) }.environment(telemetry) } }
        .sheet(isPresented: Binding(get: { relay.showingImport && store.archiveIntake == nil }, set: { relay.showingImport = $0 }), onDismiss: {
            if let file = relay.downloaded {
                relay.downloaded = nil
                Task { await store.receiveArchive(at: file, cookPackOnly: true); try? FileManager.default.removeItem(at: file) }
            }
        }) { NavigationStack { NativeRelayImportView() }.modifier(ScreenshotTextSize()) }
        .sheet(isPresented: Binding(get: { account.showingAccount }, set: { account.showingAccount = $0 }), onDismiss: {
            // Slice 8 (PL-15 / DD-10): the account sheet closing after a
            // paywall sign-in detour — completed or cancelled — reopens
            // the gate on the destination the user was headed to.
            pro.resumeSignInDetour()
        }) { NavigationStack { NativeAccountView() }.modifier(ScreenshotTextSize()) }
        .sheet(isPresented: Binding(get: { pro.presentedEntry != nil }, set: { if !$0 { pro.closePaywall() } }), onDismiss: {
            // Slice 8 (PL-15 / DD-10): a paywall dismissed for sign-in
            // hands straight over to the account sheet.
            if pro.consumeSignInDetour() { account.showingAccount = true }
        }) {
            if let entry = pro.presentedEntry { ProPaywallHost(entry: entry).modifier(ScreenshotTextSize()) }
        }
        .sheet(item: archiveIntakeBinding, onDismiss: {
            if let destination = store.publicCookDestination {
                store.publicCookDestination = nil; store.notificationCookId = destination; store.archiveCompletionMessage = nil
            } else { store.archiveMessage = store.archiveCompletionMessage; store.archiveCompletionMessage = nil }
        }) { source in NativeArchiveImportView(source: source).modifier(ScreenshotTextSize()) }
        .alert("Cook archive", isPresented: Binding(get: { store.archiveMessage != nil }, set: { if !$0 { store.archiveMessage = nil } })) {
            Button("OK", role: .cancel) { store.archiveMessage = nil }
        } message: { Text(store.archiveMessage ?? "") }
        .alert("Couldn't save", isPresented: Binding(
            get: { store.saveError != nil },
            set: { if !$0 { store.clearSaveError() } }
        )) {
            Button("OK", role: .cancel) { store.clearSaveError() }
        } message: {
            Text(store.saveError ?? "Please try again.")
        }
    }
}

struct CookListView: View {
    @Environment(CookStore.self) private var store
    @Environment(AdsConsentManager.self) private var adsConsent
    /// Set by the first-run welcome's "Restore from backup" (#187): opens the
    /// same restore file importer as the empty-Meals entry below.
    @Binding var restoreRequested: Bool
    @State private var showingStart = false
    @State private var showingRestore = false
    @State private var mealPath: [String] = []

    private var isLibraryEmpty: Bool { store.activeCooks.isEmpty && store.completedCooks.isEmpty }

    var body: some View {
        NavigationStack(path: $mealPath) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    // DD-1 lockup (#172): a small header mark only — never a hero.
                    HStack(spacing: 10) {
                        Image(systemName: "flame.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(PitTechStyle.accent)
                            .frame(width: 40, height: 40)
                            .background(PitTechStyle.accentFill, in: RoundedRectangle(cornerRadius: 11))
                            .accessibilityIdentifier("meals-lockup")
                        Text("PitTech").font(.headline)
                    }
                    .frame(height: 40)
                    SectionTitle(title: "Meals", subtitle: "Keep each meal in one place.")
                    if isLibraryEmpty {
                        // First-run empty Meals (PL-13 parity with Android):
                        // lockup → headline → promise → Start meal → Restore.
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Your cook log is ready").font(.title2.weight(.bold))
                            Text("Add the meat, preparation, photos, and notes you want to remember. You can log a cook with or without a connected grill.")
                            Text("Your cook records stay on this phone. No account is needed.")
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                        // The card is its own accessibility container: without
                        // this, SwiftUI combines the text-only stack into a
                        // single static-text element and the card identifier
                        // never surfaces as a distinct element (the flows-a
                        // journal-removal test waits on exactly that element).
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("meals-empty-card")
                    }
                    if let lead = store.activeCooks.first {
                        NavigationLink(value: lead.id) { LiveZoneCard(cook: lead) }
                            .buttonStyle(.plain)
                        if store.activeCooks.count > 1 {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("ALSO COOKING").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                                ForEach(store.activeCooks.dropFirst()) { cook in
                                    NavigationLink(value: cook.id) { CookCard(cook: cook, dishes: store.dishes(for: cook.id)) }
                                        .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                    Button { showingStart = true } label: {
                        Label("Start meal", systemImage: "plus")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 54)
                    }
                    .buttonStyle(.borderedProminent)

                    if isLibraryEmpty {
                        Button { showingRestore = true } label: {
                            Text("Restore from backup")
                                .frame(maxWidth: .infinity, minHeight: 50)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("empty-restore-backup")
                    } else {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("COMPLETED MEALS").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                            if store.completedCooks.isEmpty {
                                ContentUnavailableView("No finished cooks yet", systemImage: "book.closed", description: Text("Your cook history will show up here when you finish a cook."))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 20)
                            } else {
                                ForEach(Array(store.completedCooks.enumerated()), id: \.element.id) { index, cook in
                                    NavigationLink(value: cook.id) { CookCard(cook: cook, dishes: store.dishes(for: cook.id)) }
                                        .buttonStyle(.plain)
                                    if adsConsent.adsServingAllowed && index == min(2, store.completedCooks.count) - 1 {
                                        NativeAdCard()
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(20)
            }
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationDestination(for: String.self) { CookDetailView(cookId: $0) }
            .sheet(isPresented: $showingStart) { StartCookSheet(onStarted: { mealPath.append($0) }) }
            .onChange(of: restoreRequested) { _, requested in
                if requested { showingRestore = true; restoreRequested = false }
            }
            .fileImporter(isPresented: $showingRestore, allowedContentTypes: [.zip, .pittechCookPack], allowsMultipleSelection: false) { result in
                if let url = try? result.get().first {
                    Task { await store.receiveArchive(at: url) }
                }
            }
        }
    }
}

/// The Night Service Live Zone on Meals (#172): a high-contrast poster card for
/// the lead active cook — latest pit temperature (or elapsed) at poster size,
/// food temperature, elapsed and setpoint in plain view. Nothing (ads
/// included) ever renders inside or above it.
struct LiveZoneCard: View {
    @Environment(CookStore.self) private var store
    let cook: CookRecord

    private var readings: [TemperatureSample] { store.readings(for: cook.id) }
    private var latestPit: TemperatureSample? {
        readings.filter { ["pit", "pit_ambient", "ambient_temperature"].contains($0.measurementType) }.max { $0.measuredAt < $1.measuredAt }
    }
    private var latestFood: TemperatureSample? {
        readings.filter { $0.measurementType == "food_probe" }.max { $0.measuredAt < $1.measuredAt }
    }
    private func elapsed(at date: Date) -> String {
        let seconds = max(0, Int(date.timeIntervalSince1970) - Int(cook.startedAtUtcMillis / 1000))
        return String(format: "%d:%02d", seconds / 3600, (seconds % 3600) / 60)
    }
    private func tempText(_ sample: TemperatureSample) -> String {
        let value = sample.value
        return "\(value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(value)) : String(format: "%.1f", value))\(sample.unit)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(cook.status == "paused" ? "PAUSED" : "LIVE COOK")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color(red: 0.29, green: 0.32, blue: 0.27))
            Text(cook.title)
                .font(.title3.weight(.semibold))
            // Deliberately NOT ticking TimelineViews: a perpetual timer on the
            // launch screen keeps the app from ever idling (it broke the UI-test
            // shards and costs battery for a secondary readout). Values refresh
            // whenever Meals re-renders on store changes; the ticking clock
            // lives on the cook's Live screen, as before.
            if let pit = latestPit {
                Text(tempText(pit))
                    .font(.system(size: 52, weight: .bold))
            } else {
                Text(elapsed(at: Date.now))
                    .font(.system(size: 52, weight: .bold))
            }
            Text([
                latestFood.map { "Food \(tempText($0))" },
                "Elapsed \(elapsed(at: Date.now))",
                cook.initialSetpointValue.map { "Pit set \(String(format: "%.0f", $0))\(cook.initialSetpointUnit ?? "")" },
                cook.smokerName,
            ].compactMap { $0 }.joined(separator: " · "))
            .font(.subheadline)
            .foregroundStyle(Color(red: 0.29, green: 0.32, blue: 0.27))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Color(red: 0.949, green: 0.933, blue: 0.890), in: RoundedRectangle(cornerRadius: 16))
        .foregroundStyle(Color(red: 0.063, green: 0.078, blue: 0.059))
        .accessibilityIdentifier("meals-live-zone")
    }
}

struct CookCard: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dynamicTypeSize) private var textSize
    let cook: CookRecord
    let dishes: [String]

    private var statusText: String {
        store.timingMode(cook.id) == "explicit_phases" ? store.phaseSummary(cook.id) : "Active"
    }

    var body: some View {
        HStack(alignment: textSize.isAccessibilitySize ? .top : .center, spacing: 14) {
            Image(systemName: cook.isActive ? "flame.fill" : "checkmark.circle.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(cook.isActive ? PitTechStyle.accent : PitTechStyle.sage)
                .frame(width: 42, height: 42)
                .background(cook.isActive ? PitTechStyle.accentFill : PitTechStyle.sageFill, in: RoundedRectangle(cornerRadius: 13))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(cook.title).font(.headline).foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(dishes.isEmpty ? cook.startedAt.formatted(date: .abbreviated, time: .shortened) : dishes.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(.secondary)
                    .lineLimit(textSize.isAccessibilitySize ? nil : 2)
                if cook.isActive && textSize.isAccessibilitySize {
                    Text(statusText).font(.caption2.weight(.bold)).foregroundStyle(PitTechStyle.accent)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if cook.isActive && !textSize.isAccessibilitySize {
                Text(statusText).font(.caption2.weight(.bold)).foregroundStyle(PitTechStyle.accent).lineLimit(2)
            }
            Image(systemName: "chevron.right").font(.system(size: 14, weight: .bold)).foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .padding(14)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.border, lineWidth: 1))
    }
}

struct StartCookSheet: View {
    var onStarted: (String) -> Void = { _ in }
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var firstDish = ""
    @State private var alreadyCooking = false
    @State private var foodOn = Date.now

    var body: some View {
        NavigationStack {
            Form {
                Section("Current stage") {
                    Toggle("Already cooking", isOn: $alreadyCooking)
                    if alreadyCooking { DatePicker("Actual Food on", selection: $foodOn, in: ...Date.now) }
                    else { Text("Preparing until you log Food on.") }
                }
                Section("Meal") {
                    TextField("Meal name (optional)", text: $title).accessibilityIdentifier("meal-title")
                    TextField("First dish (optional)", text: $firstDish).accessibilityIdentifier("meal-first-dish")
                }
                Section {
                    Text("You can start with manual logging and add temperature readings later.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Start meal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Start") {
                        store.startCook(title: title, firstDish: firstDish, alreadyCooking: alreadyCooking, foodOn: foodOn)
                        if store.saveError == nil { if let id = store.activeCooks.first?.id { onStarted(id) }; dismiss() }
                    }.accessibilityIdentifier("start-meal-save")
                }
            }
        }
    }
}

struct InsightsView: View {
    @Environment(CookStore.self) private var store

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    SectionTitle(title: "Insights", subtitle: "Patterns from the cooks you record.")
                    HStack(spacing: 12) {
                        MetricCard(title: "Finished cooks", value: "\(store.completedCooks.count)", symbol: "checkmark.circle")
                        MetricCard(title: "Active now", value: "\(store.activeCooks.count)", symbol: "flame")
                    }
                    HStack(spacing: 12) {
                        MetricCard(title: "Temperature readings", value: "\(store.library.readings.count)", symbol: "thermometer.medium")
                        MetricCard(title: "Timeline notes", value: "\(store.library.events.count)", symbol: "text.bubble")
                    }
                    let durations = store.completedCooks.compactMap { cook -> Double? in
                        guard let end = cook.endedAtUtcMillis, end >= cook.startedAtUtcMillis else { return nil }
                        return Double(end - cook.startedAtUtcMillis) / 3_600_000
                    }
                    if let average = durations.average {
                        MetricCard(title: "Average session time", value: average < 24 ? "\(average.formatted(.number.precision(.fractionLength(1)))) hr" : "\((average / 24).formatted(.number.precision(.fractionLength(1)))) days", symbol: "clock")
                    }
                    if !store.completedCooks.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("RECENTLY FINISHED").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                            ForEach(store.completedCooks.prefix(5)) { cook in
                                CookCard(cook: cook, dishes: store.dishes(for: cook.id))
                            }
                        }
                    } else {
                        ContentUnavailableView("Keep logging to build insights", systemImage: "chart.xyaxis.line", description: Text("Your cook totals and average duration are calculated from records saved on this device."))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 18)
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        Text("DEEPER ANALYSIS").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                        ProEntryRow(entry: .analysis)
                        ProEntryRow(entry: .curves)
                        ProEntryRow(entry: .benchmarks)
                    }
                }
                .padding(20)
            }
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle("Insights")
        }
    }
}

struct MetricCard: View {
    let title: String
    let value: String
    let symbol: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: symbol).foregroundStyle(PitTechStyle.accent)
            Text(value).font(.largeTitle.bold())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct SettingsView: View {
    @Environment(CookStore.self) private var store
    @Environment(AdsConsentManager.self) private var adsConsent
    @State private var showingExport = false
    @State private var showingImport = false
    @State private var exportDocument = PitTechArchiveDocument(data: Data())
    @State private var archiveError: String?

    var body: some View {
        Group {
            Form {
                Section("Measurements") {
                    Picker("Temperature", selection: Binding(
                        get: { store.temperatureUnit },
                        set: { store.setPreference("temperatureUnit", value: $0) }
                    )) {
                        Text("Fahrenheit").tag("F")
                        Text("Celsius").tag("C")
                    }
                    Picker("Weight", selection: Binding(
                        get: { store.weightUnit },
                        set: { store.setPreference("weightUnit", value: $0) }
                    )) {
                        Text("Pounds").tag("lb")
                        Text("Kilograms").tag("kg")
                    }
                }
                Section("Appearance") {
                    // #172 (DD-7 rejected, owner ruling 2026-10-24): Night Service is
                    // the only appearance — stated honestly, not a dead picker.
                    LabeledContent("Appearance", value: "Night Service (dark)")
                        .accessibilityIdentifier("appearance-night-service")
                    Text("PitTech uses the Night Service dark appearance on every screen — built to stay readable at the smoker, day or night.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Section("Your data") {
                    Label("Cook data stays on this device unless you export or share it.", systemImage: "iphone.and.arrow.forward")
                        .font(.subheadline)
                    Button("Export cook archive") {
                        do {
                            exportDocument = PitTechArchiveDocument(data: try store.exportArchive())
                            showingExport = true
                        } catch {
                            archiveError = "PitTech couldn't create your backup. Check available storage and try again."
                        }
                    }
                    Button("Restore cook archive") { showingImport = true }
                    NativeDataExportButtons()
                    NavigationLink("Third-party telemetry notices") {
                        ScrollView { Text((Bundle.main.url(forResource: "TelemetryNotices", withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) }) ?? "No telemetry dependency notices in this build.").font(.caption).padding() }.navigationTitle("Third-party notices")
                    }
                    Link("Privacy policy", destination: URL(string: "https://pittech-privacy-production.up.railway.app")!)
                    Link("Contact support", destination: URL(string: "mailto:sportfolioholdings@gmail.com")!)
                }
                if adsConsent.isPrivacyOptionsRequired {
                    Section("Advertising") {
                        Button("Privacy choices") {
                            Task { await adsConsent.presentPrivacyOptions() }
                        }
                    }
                }
                Section("PitTech") {
                    NavigationLink("Feedback & feature ideas") { NativeFeedbackView() }
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                }
            }
            .navigationTitle("Settings")
            .fileExporter(isPresented: $showingExport, document: exportDocument, contentType: .zip, defaultFilename: "PitTech-Cooks") { result in
                if case .failure = result { archiveError = "PitTech couldn't save the backup to that location." }
            }
            .fileImporter(isPresented: $showingImport, allowedContentTypes: [.zip, .pittechCookPack], allowsMultipleSelection: false) { result in
                do {
                    guard let url = try result.get().first else { return }
                    Task { await store.receiveArchive(at: url) }
                } catch { archiveError = error.localizedDescription }
            }
            .alert("Cook archive", isPresented: Binding(get: { archiveError != nil }, set: { if !$0 { archiveError = nil } })) {
                Button("OK", role: .cancel) { archiveError = nil }
            } message: { Text(archiveError ?? "") }
        }
    }
}

private extension Array where Element == Double {
    var average: Double? { isEmpty ? nil : reduce(0, +) / Double(count) }
}
