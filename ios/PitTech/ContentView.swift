import SwiftUI

struct ContentView: View {
    @Environment(CookStore.self) private var store

    var body: some View {
        TabView {
            CookListView()
                .tabItem { Label("Cooks", systemImage: "flame") }
            InsightsView()
                .tabItem { Label("Insights", systemImage: "chart.xyaxis.line") }
            DevicesView()
                .tabItem { Label("Devices", systemImage: "dot.radiowaves.left.and.right") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .preferredColorScheme(.light)
        .background(PitTechStyle.canvas)
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

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    SectionTitle(title: "Cooks", subtitle: "Keep the whole cook in one place.")
                    Button { showingStart = true } label: {
                        Label("Start a cook", systemImage: "plus.circle.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 54)
                    }
                    .buttonStyle(.borderedProminent)

                    if !store.activeCooks.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("ACTIVE").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                            ForEach(store.activeCooks) { cook in
                                NavigationLink(value: cook.id) { CookCard(cook: cook, dishes: store.dishes(for: cook.id)) }
                                    .buttonStyle(.plain)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        Text("RECENT COOKS").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                        if store.completedCooks.isEmpty {
                            if store.activeCooks.isEmpty && store.completedCooks.count >= 4 && adsConsent.canRequestAds {
                                NativeAdCard()
                            }
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
            .sheet(isPresented: $showingStart) { StartCookSheet() }
        }
    }
}

struct CookCard: View {
    let cook: CookRecord
    let dishes: [String]

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: cook.isActive ? "flame.fill" : "checkmark.circle.fill")
                .font(.title2)
                .foregroundStyle(cook.isActive ? PitTechStyle.accent : PitTechStyle.sage)
                .frame(width: 42, height: 42)
                .background(.white, in: RoundedRectangle(cornerRadius: 13))
            VStack(alignment: .leading, spacing: 4) {
                Text(cook.title).font(.headline).foregroundStyle(.primary)
                Text(dishes.isEmpty ? cook.startedAt.formatted(date: .abbreviated, time: .shortened) : dishes.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer(minLength: 4)
            if cook.isActive { Text("LIVE").font(.caption2.weight(.bold)).foregroundStyle(PitTechStyle.accent) }
            Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(.tertiary)
        }
        .padding(14)
        .background(.white, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct StartCookSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var firstDish = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Cook") {
                    TextField("Cook name", text: $title)
                    TextField("First dish (optional)", text: $firstDish)
                }
                Section {
                    Text("You can start with manual logging and add grill readings later.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Start cook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Start") {
                        store.startCook(title: title, firstDish: firstDish)
                        dismiss()
                    }
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
                        MetricCard(title: "Average cook time", value: average < 24 ? "\(average.formatted(.number.precision(.fractionLength(1)))) hr" : "\((average / 24).formatted(.number.precision(.fractionLength(1)))) days", symbol: "clock")
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
        .background(.white, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct DevicesView: View {
    @Environment(GrillirGMonitor.self) private var monitor
    @State private var email = ""
    @State private var verificationCode = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("Read-only grill monitoring", systemImage: "eye")
                        .font(.headline)
                    Text("PitTech displays status and temperature readings. It does not send ignition, temperature, auger, or other controller-setting commands.")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Section("GrillirG") {
                    if monitor.isSignedIn {
                        if monitor.devices.isEmpty {
                            Text("No grills found on this account.").foregroundStyle(.secondary)
                        } else {
                            Picker("Grill", selection: Binding(
                                get: { monitor.selectedDeviceId ?? "" },
                                set: { value in Task { await monitor.selectDevice(value) } }
                            )) {
                                ForEach(monitor.devices) { device in
                                    Text(device.name).tag(device.id)
                                }
                            }
                            if let device = monitor.selectedDevice {
                                Text([device.brand, device.model, device.firmware.map { "Firmware \($0)" }].compactMap { $0 }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            if let snapshot = monitor.snapshot {
                                LabeledContent("Connection", value: connectionLabel(snapshot))
                                LabeledContent("Controller", value: runningLabel(snapshot))
                                temperatureRow("Grill", keys: ["furnaceTempMeasured"], snapshot: snapshot)
                                temperatureRow("Probe 1", keys: ["probeP1Measured"], snapshot: snapshot)
                                temperatureRow("Probe 2", keys: ["probeP2Measured"], snapshot: snapshot)
                                Text("Received from GrillirG \(snapshot.fetchedAt.formatted(date: .omitted, time: .shortened)) · \(snapshot.isStale ? "May be out of date" : "Current")")
                                    .font(.caption).foregroundStyle(snapshot.isStale ? .orange : .secondary)
                            } else {
                                Text("Refresh to read the selected grill's latest status and temperatures.")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                            Button {
                                Task { await monitor.refresh() }
                            } label: {
                                if monitor.isBusy { ProgressView("Checking GrillirG…") }
                                else { Label("Refresh readings", systemImage: "arrow.clockwise") }
                            }
                            .disabled(monitor.isBusy || monitor.selectedDeviceId == nil)
                        }
                        Button("Sign out of GrillirG", role: .destructive) { monitor.signOut() }
                    } else {
                        Text("Sign in to read the grills linked to your GrillirG account. PitTech sends your email and one-time code to GrillirG and stores the returned session in this device's Keychain.")
                            .font(.subheadline).foregroundStyle(.secondary)
                        TextField("GrillirG email", text: $email).textInputAutocapitalization(.never).keyboardType(.emailAddress).autocorrectionDisabled()
                        Button("Send sign-in code") { Task { await monitor.requestCode(email: email) } }
                            .disabled(monitor.isBusy || email.isEmpty)
                        SecureField("Six-digit code", text: $verificationCode).keyboardType(.numberPad)
                        Button("Sign in") { Task { await monitor.signIn(email: email, code: verificationCode) } }
                            .disabled(monitor.isBusy || verificationCode.count != 6)
                    }
                    if let notice = monitor.notice {
                        Text(notice).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("Experimental providers") {
                    ProviderRow(name: "Pit Boss / Louisiana", subtitle: "Read-only cloud monitoring · experimental on Android", experimental: true)
                    ProviderRow(name: "Traeger WiFIRE", subtitle: "Read-only cloud monitoring · experimental on Android", experimental: true)
                    Text("These two experimental providers are not connected in the current iOS build.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Devices")
            .task { await monitor.restore() }
        }
    }

    @ViewBuilder private func temperatureRow(_ title: String, keys: [String], snapshot: GrillirGSnapshot) -> some View {
        let value = keys.compactMap { snapshot.readings[$0] ?? snapshot.status[$0] }.first
        LabeledContent(title, value: value.map { "\($0.formatted()) \(snapshot.temperatureUnit)" } ?? "Not reported")
    }

    private func connectionLabel(_ snapshot: GrillirGSnapshot) -> String {
        let value = snapshot.status["onlineStatus"]
        if value == 0 { return "Online" }
        if value == 1 { return "Offline" }
        return "Not reported"
    }

    private func runningLabel(_ snapshot: GrillirGSnapshot) -> String {
        let value = snapshot.readings["runningStatus"] ?? snapshot.status["runningStatus"]
        if value == 1 { return "Starting" }
        if value == 2 { return "Cooking" }
        if value == 3 { return "Off" }
        if value == 4 { return "Shutting down" }
        if value == 5 { return "Controller reports an error" }
        return "Not reported"
    }
}

struct ProviderRow: View {
    let name: String
    let subtitle: String
    let experimental: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(name).font(.body.weight(.semibold))
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if experimental { Text("EXPERIMENTAL").font(.caption2.weight(.bold)).foregroundStyle(.secondary) }
        }
        .padding(.vertical, 4)
    }
}

struct SettingsView: View {
    @Environment(CookStore.self) private var store
    @Environment(AdsConsentManager.self) private var adsConsent
    @State private var showingExport = false
    @State private var showingImport = false
    @State private var showingImportConfirmation = false
    @State private var exportDocument = PitTechArchiveDocument(data: Data())
    @State private var pendingImportData: Data?
    @State private var pendingPreview: PitTechImportPreview?
    @State private var archiveError: String?

    var body: some View {
        NavigationStack {
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
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                }
            }
            .navigationTitle("Settings")
            .fileExporter(isPresented: $showingExport, document: exportDocument, contentType: .zip, defaultFilename: "PitTech-Cooks") { result in
                if case .failure = result { archiveError = "PitTech couldn't save the backup to that location." }
            }
            .fileImporter(isPresented: $showingImport, allowedContentTypes: [.zip], allowsMultipleSelection: false) { result in
                do {
                    guard let url = try result.get().first else { return }
                    let hasAccess = url.startAccessingSecurityScopedResource()
                    defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
                    let data = try Data(contentsOf: url, options: [.mappedIfSafe])
                    pendingPreview = try CookStore.previewArchive(data)
                    pendingImportData = data
                    showingImportConfirmation = true
                } catch {
                    archiveError = (error as? LocalizedError)?.errorDescription ?? "PitTech couldn't open that backup. Your existing library was left unchanged."
                }
            }
            .confirmationDialog("Restore cook archive?", isPresented: $showingImportConfirmation, titleVisibility: .visible) {
                Button("Restore \(pendingPreview?.cooks ?? 0) cooks", role: .destructive) {
                    do {
                        guard let pendingImportData else { return }
                        let result = try store.importArchive(pendingImportData)
                        archiveError = "Restored \(result.imported) cooks. Skipped \(result.skipped) duplicate cooks."
                    } catch {
                        archiveError = (error as? LocalizedError)?.errorDescription ?? "PitTech couldn't restore this backup. Your existing library was left unchanged."
                    }
                    pendingImportData = nil
                    pendingPreview = nil
                }
                Button("Cancel", role: .cancel) { pendingImportData = nil; pendingPreview = nil }
            } message: {
                if let pendingPreview {
                    Text("Schema \(pendingPreview.version) • \(pendingPreview.cooks) cooks • \(pendingPreview.dishes) dishes • \(pendingPreview.readings) readings. Matching cook IDs will be skipped.")
                }
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
