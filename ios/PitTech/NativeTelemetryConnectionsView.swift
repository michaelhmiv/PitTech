import SwiftUI

struct NativeTelemetryConnectionsView: View {
    @Environment(CookStore.self) private var store
    @Bindable var runtime: NativeTelemetryRuntime
    @State private var adding = false
    @State private var provider = NativeTelemetryProvider.home_assistant
    @State private var endpoint = ""
    @State private var user = ""
    @State private var secret = ""
    @State private var busy = false
    @State private var error: String?
    @State private var mapping: NativeTelemetryProfile?
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Optional, read-only connections. Credentials stay in this device's Keychain and cooking telemetry stays in your local library.")
                    Text("Cloud monitoring runs while PitTech is open. iOS may suspend it when locked; supported Home Assistant history is recovered after returning.").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(runtime.profiles) { profile in
                    Section(profile.provider.label + " · Beta") {
                        Text(runtime.messages[profile.id] ?? "Saved connection")
                        ForEach(profile.sources.filter { profile.selected.contains($0.id) }) { source in Text(source.label) }
                        Button("Devices and food assignments") { mapping = profile }
                        Toggle("Monitor temperatures", isOn: Binding(get: { profile.monitoring }, set: { value in var p = profile; p.monitoring = value; try? runtime.update(p) })).disabled(profile.cookId == nil)
                        Toggle("Only collect when I log", isOn: Binding(get: { profile.onLogOnly }, set: { value in var p = profile; p.onLogOnly = value; try? runtime.update(p) }))
                        Picker("Sampling", selection: Binding(get: { profile.interval }, set: { value in var p = profile; p.interval = value; try? runtime.update(p) })) {
                            Text("15 seconds").tag(15_000.0); Text("30 seconds").tag(30_000.0); Text("1 minute").tag(60_000.0); Text("5 minutes").tag(300_000.0)
                        }
                        Button("Forget connection", role: .destructive) { do { try runtime.remove(profile, store: store) } catch { self.error = error.localizedDescription } }
                    }
                }
                Section { Button("Add temperature connection") { adding = true }.accessibilityIdentifier("telemetry-add") }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .navigationTitle("Connections · Beta")
            .sheet(isPresented: $adding) { addView }
            .sheet(item: $mapping) { profile in NativeTelemetryMappingView(profile: profile, runtime: runtime) }
        }
    }
    private var addView: some View {
        NavigationStack {
            Form {
                Picker("Connection", selection: $provider) { ForEach(NativeTelemetryProvider.allCases) { Text($0.label).tag($0) } }
                switch provider {
                case .home_assistant:
                    Text("Connect a Home Assistant server that you operate. The phone app alone is not a server.")
                    TextField("HTTPS server URL", text: $endpoint).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("Advanced Beta authorization: enter a long-lived access token from your Home Assistant profile.").font(.caption)
                    SecureField("Access token", text: $secret)
                case .meater, .fireboard:
                    if provider == .meater { Text("Your MEATER app or Block must stay connected to Bluetooth and MEATER Cloud. Missing history cannot be backfilled.") }
                    TextField("Account email or username", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Password (not saved)", text: $secret)
                    Text("Leave the account field empty to use a provider access token.").font(.caption)
                case .combustion:
                    Text("Keep the thermometer nearby. Connecting asks iOS for Bluetooth permission and uses Combustion's official SDK. Historical probe-log recovery and background hardware behavior need validation.")
                case .flame_boss:
                    Text("Flame Boss requires a verified TLS broker and private-topic permissions. The iOS transport is not cleared yet.")
                }
                Button(busy ? "Connecting…" : "Connect") {
                    let entered = secret; secret = ""; busy = true
                    let p = NativeTelemetryProfile(provider: provider, endpoint: endpoint)
                    Task { do { try await runtime.connect(p, user: user, secret: entered); adding = false; error = nil } catch { self.error = error.localizedDescription }; busy = false }
                }.disabled(busy || provider == .flame_boss || provider != .combustion && secret.isEmpty)
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("Connect · Beta")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { secret = ""; adding = false }.disabled(busy) } }
        }
    }
}

struct NativeTelemetryMappingView: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let profile: NativeTelemetryProfile
    @Bindable var runtime: NativeTelemetryRuntime
    @State private var sourceID = ""
    @State private var cookID = ""
    @State private var assignments: [String: String] = [:]
    @State private var roles: [String: NativeTelemetryKind] = [:]
    @State private var error: String?
    @State private var busy = false
    private var source: NativeTelemetrySource? { profile.sources.first { $0.id == sourceID } }
    var body: some View {
        NavigationStack {
            Form {
                Picker("Device", selection: $sourceID) { Text("Choose device").tag(""); ForEach(profile.sources) { Text($0.label).tag($0.id) } }
                Picker("Active meal", selection: $cookID) { Text("Choose meal").tag(""); ForEach(store.activeCooks.filter { $0.status == "active" }) { Text($0.title).tag($0.id) } }
                if let source {
                    ForEach(source.channels) { channel in
                        Section(channel.name) {
                            if channel.kind != .setpoint { Picker("Use", selection: Binding(get: { roles[channel.id] ?? channel.kind }, set: { roles[channel.id] = $0 })) { ForEach([NativeTelemetryKind.food, .chamber, .ambient], id: \.self) { Text($0.label).tag($0) } } }
                            else { Text("Observed target · read-only") }
                            if (roles[channel.id] ?? channel.kind) == .food { Picker("Food", selection: Binding(get: { assignments[channel.id] ?? "" }, set: { assignments[channel.id] = $0 })) { Text("Unassigned").tag(""); ForEach(store.dishRecords(cookID)) { Text($0.name).tag($0.id) } } }
                        }
                    }
                }
                if let error { Text(error).foregroundStyle(.red) }
                Button("Attach to meal") {
                    guard var mapped = source else { return }; mapped.channels = mapped.channels.map { ch in var c = ch; c.kind = roles[ch.id] ?? ch.kind; return c }
                    let foods = mapped.channels.filter { $0.kind == .food }.reduce(into: [String: String?]()) { out, c in out[c.id] = assignments[c.id].flatMap { $0.isEmpty ? nil : $0 } }
                    busy = true
                    Task { do { try await runtime.attach(profile, source: mapped, cook: cookID, assignments: foods, store: store); dismiss() } catch { self.error = error.localizedDescription }; busy = false }
                }.disabled(source == nil || cookID.isEmpty || busy)
            }
            .navigationTitle("Food assignments")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        }
    }
}
