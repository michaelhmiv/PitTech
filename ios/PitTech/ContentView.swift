import SwiftUI

struct ContentView: View {
    @Environment(CookStore.self) private var store
    @Environment(\.scenePhase) private var scenePhase
    @State private var telemetry = NativeTelemetryRuntime()

    var body: some View {
        TabView {
            CookListView()
                .tabItem { Label("Meals", systemImage: "flame") }
            NavigationStack { SavedRecipesView() }.tabItem { Label("Recipes", systemImage: "book") }
            DiscoveryWelcomeView().tabItem { Label("Discover", systemImage: "safari") }
            InsightsView()
                .tabItem { Label("Insights", systemImage: "chart.xyaxis.line") }
            MoreView(runtime: telemetry).tabItem { Label("More", systemImage: "ellipsis") }
        }
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
            if payload["open"] as? Bool == true { store.notificationCookId = payload["cookId"] as? String }
        } }
        .sheet(isPresented: Binding(get: { store.notificationCookId != nil }, set: { if !$0 { store.notificationCookId = nil } })) { if let id = store.notificationCookId { NavigationStack { CookDetailView(cookId: id) } } }
        .sheet(item: Binding(get: { store.archiveIntake }, set: { if $0 == nil { store.discardArchiveIntake() } }), onDismiss: {
            store.archiveMessage = store.archiveCompletionMessage; store.archiveCompletionMessage = nil
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
    @State private var showingStart = false
    @State private var mealPath: [String] = []

    var body: some View {
        NavigationStack(path: $mealPath) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    SectionTitle(title: "Meals", subtitle: "Keep each meal in one place.")
                    if !store.activeCooks.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("ACTIVE").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                            ForEach(store.activeCooks) { cook in
                                NavigationLink(value: cook.id) { CookCard(cook: cook, dishes: store.dishes(for: cook.id)) }
                                    .buttonStyle(.plain)
                            }
                        }
                    }
                    Button { showingStart = true } label: {
                        Label("Start meal", systemImage: "plus")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 54)
                    }
                    .buttonStyle(.borderedProminent)

                    VStack(alignment: .leading, spacing: 10) {
                        Text("COMPLETED MEALS").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                        if store.completedCooks.isEmpty {
                            ContentUnavailableView("No finished cooks yet", systemImage: "book.closed", description: Text("Your cook history will show up here when you finish a cook."))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 20)
                        } else {
                            if store.activeCooks.isEmpty && store.completedCooks.count >= 4 && adsConsent.canRequestAds {
                                NativeAdCard()
                            }
                            ForEach(store.completedCooks) { cook in
                                NavigationLink(value: cook.id) { CookCard(cook: cook, dishes: store.dishes(for: cook.id)) }
                                    .buttonStyle(.plain)
                            }
                        }
                    }
                }
                .padding(20)
            }
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationDestination(for: String.self) { CookDetailView(cookId: $0) }
            .sheet(isPresented: $showingStart) { StartCookSheet(onStarted: { mealPath.append($0) }) }
        }
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
                    Picker("Appearance", selection: Binding(get: { store.library.preferences?["themeMode"]?.stringValue ?? "SYSTEM" }, set: { store.setPreference("themeMode", value: $0) })) {
                        Text("System").tag("SYSTEM"); Text("Light").tag("LIGHT"); Text("Dark").tag("DARK")
                    }
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
