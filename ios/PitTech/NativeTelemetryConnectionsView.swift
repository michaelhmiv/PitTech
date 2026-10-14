import SwiftUI

struct NativeTelemetryConnectionsView: View {
    @Environment(CookStore.self) private var store
    @Bindable var runtime: NativeTelemetryRuntime
    var embedded = false
    @State private var adding = false
    @State private var authorizing = false
    @State private var authRequest: NativeHAAuthRequest?
    @State private var provider = NativeTelemetryProvider.home_assistant
    @State private var endpoint = ""
    @State private var deviceHint = ""
    @State private var user = ""
    @State private var secret = ""
    @State private var busy = false
    @State private var error: String?
    @State private var mapping: NativeTelemetryProfile?
    var body: some View {
        Group {
            if embedded { connectionsContent }
            else { NavigationStack { connectionsContent } }
        }
    }
    private var connectionsContent: some View {
            List {
                Section {
                    Text("Optional, read-only connections. Credentials stay in this device's Keychain and cooking telemetry stays in your local library.")
                    Text("Cloud monitoring runs while PitTech is open. iOS may suspend it when locked; supported Home Assistant history is recovered after returning.").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(runtime.profiles) { profile in
                    Section(profile.provider.label + " · Beta") {
                        Text(runtime.messages[profile.id] ?? "Saved connection")
                        ForEach(profile.sources.filter { profile.selected.contains($0.id) }) { source in VStack(alignment: .leading) { Text(source.label); Button("Detach from meal") { do { try runtime.detach(profile, source: source, store: store) } catch { self.error = error.localizedDescription } }; Text(runtime.messages[profile.id + ":" + source.id] ?? "Waiting for a reading").font(.caption).foregroundStyle(.secondary) } }
                        Button("Devices and food assignments") { mapping = profile }
                        Toggle("Monitor temperatures", isOn: Binding(get: { profile.monitoring }, set: { value in var p = profile; p.monitoring = value; try? runtime.update(p) })).disabled(profile.cookId == nil || profile.selected.isEmpty)
                        Toggle("Only collect when I log", isOn: Binding(get: { profile.onLogOnly }, set: { value in var p = profile; p.onLogOnly = value; try? runtime.update(p) }))
                        Picker("Sampling", selection: Binding(get: { profile.interval }, set: { value in var p = profile; p.interval = value; try? runtime.update(p) })) {
                            Text("15 seconds").tag(15_000.0); Text("30 seconds").tag(30_000.0); Text("1 minute").tag(60_000.0); Text("5 minutes").tag(300_000.0)
                        }
                        Button("Forget connection", role: .destructive) { Task { do { try await runtime.remove(profile, store: store) } catch { self.error = error.localizedDescription } } }
                    }
                }
                Section { Button("Add temperature connection") { adding = true }.accessibilityIdentifier("telemetry-add") }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .navigationTitle("Connections · Beta")
            .sheet(isPresented: $adding) { addView }
            .sheet(item: $mapping) { profile in NativeTelemetryMappingView(profile: profile, runtime: runtime) }
    }
    private var addView: some View {
        NavigationStack {
            Form {
                Picker("Connection", selection: $provider) { ForEach(NativeTelemetryProvider.allCases) { Text($0.label).tag($0) } }
                switch provider {
                case .home_assistant:
                    Text("Connect a Home Assistant server that you operate. The phone app alone is not a server.")
                    TextField("HTTPS server URL", text: $endpoint).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("telemetry-ha-server")
                    Button("Sign in to Home Assistant") {
                        do { authRequest = try NativeHAAuthRequest(server: endpoint); authorizing = true }
                        catch { self.error = error.localizedDescription }
                    }
                    .disabled(busy || (try? NativeTelemetryEndpoint.homeAssistant(endpoint)) == nil)
                    .accessibilityIdentifier("telemetry-ha-authorize")
                    Text("Advanced Beta authorization: enter a long-lived access token from your Home Assistant profile.").font(.caption)
                    SecureField("Access token", text: $secret)
                case .meater, .fireboard:
                    if provider == .meater { Text("Your MEATER app or Block must stay connected to Bluetooth and MEATER Cloud. Missing history cannot be backfilled.") }
                    TextField("Account email or username", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Password (not saved)", text: $secret)
                    if provider == .fireboard { TextField("Optional numeric session ID for history", text: $deviceHint).keyboardType(.numberPad) }
                    Text("Leave the account field empty to use a provider access token.").font(.caption)
                case .combustion:
                    Text("Keep the thermometer nearby. Connecting asks iOS for Bluetooth permission and uses Combustion's official SDK. This Beta records live temperatures; readings missed while disconnected cannot be recovered. Repeater and background hardware behavior have not been verified.")
                case .flame_boss:
                    Text("Requires a manufacturer-confirmed TLS broker and a public cook. Private topics are not validated.")
                    TextField("mqtts://manufacturer-host:port", text: $endpoint).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Device ID", text: $deviceHint).keyboardType(.numberPad)
                    TextField("Account email", text: $user).textInputAutocapitalization(.never)
                    SecureField("Password (not saved)", text: $secret)
                }
                Button(busy ? "Connecting…" : "Connect") {
                    let entered = secret; secret = ""; busy = true
                    let p = NativeTelemetryProfile(provider: provider, endpoint: endpoint, deviceHint: deviceHint)
                    Task { do { try await runtime.connect(p, user: user, secret: entered); adding = false; error = nil } catch { self.error = error.localizedDescription }; busy = false }
                }.disabled(busy || provider != .combustion && secret.isEmpty).accessibilityIdentifier("telemetry-connect")
                if let error { Text(error).foregroundStyle(.red) }
            }
            .sheet(isPresented: $authorizing) {
                if let request = authRequest {
                    NavigationStack {
                        NativeHAOAuthView(request: request) { result in
                            authorizing = false
                            switch result {
                            case .failure(let failure): error = failure.localizedDescription
                            case .success(let code):
                                busy = true
                                Task {
                                    do { let token = try await NativeHASession.exchange(server: endpoint, code: code); try await runtime.connect(NativeTelemetryProfile(provider: .home_assistant, endpoint: endpoint), user: "", secret: token.encoded); adding = false; error = nil }
                                    catch { self.error = error.localizedDescription }
                                    busy = false
                                }
                            }
                        }.navigationTitle("Home Assistant")
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { authorizing = false } } }
                    }
                }
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
    private func restoreAssignments() {
        assignments = [:]; roles = [:]
        guard let source else { return }
        for channel in source.channels {
            let id = NativeTelemetryIdentity.probe(cookID, source, channel.id)
            if let probe = store.library.probes.compactMap(\.objectValue).first(where: { $0["id"]?.stringValue == id }) { assignments[channel.id] = probe["assignedDishId"]?.stringValue ?? "" }
        }
    }
    private var source: NativeTelemetrySource? { profile.sources.first { $0.id == sourceID } }
    var body: some View {
        NavigationStack {
            Form {
                Picker("Device", selection: $sourceID) { Text("Choose device").tag(""); ForEach(profile.sources) { Text($0.label).tag($0.id) } }
                Picker("Active meal", selection: $cookID) { Text("Choose meal").tag(""); ForEach(store.activeCooks.filter { $0.status == "active" }) { Text($0.title).tag($0.id) } }
                if let source {
                    ForEach(source.channels) { channel in
                        Section(channel.name) {
                            if channel.kind != .setpoint { Picker("Use", selection: Binding(get: { roles[channel.id] ?? channel.kind }, set: { roles[channel.id] = $0 })) { ForEach([NativeTelemetryKind.food, .chamber, .ambient, .other], id: \.self) { Text($0.label).tag($0) } } }
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
            .onAppear { cookID = store.activeCooks.contains { $0.id == profile.cookId && $0.status == "active" } ? profile.cookId ?? "" : "" }
            .onChange(of: sourceID) { _, _ in restoreAssignments() }
            .onChange(of: cookID) { _, _ in restoreAssignments() }
            .navigationTitle("Food assignments")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        }
    }
}
