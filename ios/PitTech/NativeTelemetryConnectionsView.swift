import SwiftUI

/// PL-05 (#172): Devices is ONE sheet with a visible 3-step stepper —
/// Connect → Attach → Assign — identical from Cook Detail and from More.
/// Attach/assign edits live in the runtime as drafts, so dismissing the
/// sheet never throws them away; a successful attach consumes the draft.
struct NativeTelemetryConnectionsView: View {
    @Environment(CookStore.self) private var store
    @Bindable var runtime: NativeTelemetryRuntime
    var embedded = false
    @State private var step = 1
    @State private var stepTouched = false
    @State private var adding = false
    @State private var authorizing = false
    @State private var authRequest: NativeHAAuthRequest?
    @State private var authNotice: String?
    @State private var provider = NativeTelemetryProvider.home_assistant
    @State private var endpoint = ""
    @State private var deviceHint = ""
    @State private var user = ""
    @State private var secret = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        Group {
            if embedded { connectionsContent }
            else { NavigationStack { connectionsContent } }
        }
    }
    private var emptyDevicesText: String {
        NativeTelemetryDevicesPresentation.emptyDevicesText(
            discoveryInFlight: runtime.profiles.contains { runtime.connectingProfiles.contains($0.id) },
            discoverySettled: runtime.profiles.contains { runtime.discoverySettledProfiles.contains($0.id) })
    }
    private var connectionsContent: some View {
            List {
                Section {
                    // TURN-IN (`d08`): the board head — the app's own
                    // read-only intro plus the §1.1.3 contract line.
                    Text("Optional, read-only connections. Credentials stay in this device's Keychain and cooking telemetry stays in your local library.").font(PitTechFont.body)
                    Text("PitTech watches — it never changes a setting.").font(PitTechFont.body)
                    Text("Cloud monitoring runs while PitTech is open. iOS may suspend it when locked; supported Home Assistant history is recovered after returning.").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                }.listRowBackground(PitTechStyle.surface)
                Section {
                    Picker("Setup step", selection: Binding(get: { step }, set: { step = $0; stepTouched = true })) {
                        Text("Connect").tag(1); Text("Attach").tag(2); Text("Assign").tag(3)
                    }.pickerStyle(.segmented).accessibilityIdentifier("telemetry-stepper")
                }.listRowBackground(PitTechStyle.surface)
                if step == 1 {
                    ForEach(runtime.profiles) { profile in
                        // TURN-IN (`d08`): banner name over a mono state line.
                        Section {
                            Text(runtime.messages[profile.id] ?? "Saved connection").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                            Button("Forget connection", role: .destructive) { Task { do { try await runtime.remove(profile, store: store) } catch { self.error = error.localizedDescription } } }
                        } header: { TurnInBannerText(profile.provider.label + " · Beta") }
                            .listRowBackground(PitTechStyle.surface)
                    }
                    if runtime.profiles.isEmpty { Section { Text(EmptyStateCopy.connectionsEmpty).font(PitTechFont.body) }.listRowBackground(PitTechStyle.surface) }
                }
                if step == 2 {
                    if runtime.profiles.isEmpty { Section { Text("Connect a connection first (step 1), then attach it here.") }.listRowBackground(PitTechStyle.surface) }
                    ForEach(runtime.profiles) { profile in
                        // TURN-IN (`d21` + `d11`): banner name, mono state,
                        // the watching switches and sampling in one section.
                        Section {
                            Text(runtime.messages[profile.id] ?? "Saved connection").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                            ForEach(profile.sources.filter { profile.selected.contains($0.id) }) { source in VStack(alignment: .leading) { Text(source.label).font(PitTechFont.bodyEmphasis); Button("Detach from meal") { do { try runtime.detach(profile, source: source, store: store) } catch { self.error = error.localizedDescription } }; Text(runtime.messages[profile.id + ":" + source.id] ?? "Waiting for a reading").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) } }
                            Toggle("Monitor temperatures", isOn: Binding(get: { profile.monitoring }, set: { value in var p = profile; p.monitoring = value; try? runtime.update(p) })).disabled(profile.cookId == nil || profile.selected.isEmpty)
                            // PL-05: the dead toggle explains itself.
                            if profile.cookId == nil || profile.selected.isEmpty { Text("Attach this connection to the cook first").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
                            Toggle("Only collect when I log", isOn: Binding(get: { profile.onLogOnly }, set: { value in var p = profile; p.onLogOnly = value; try? runtime.update(p) }))
                            Picker("Sampling", selection: Binding(get: { profile.interval }, set: { value in var p = profile; p.interval = value; try? runtime.update(p) })) {
                                Text("15 seconds").tag(15_000.0); Text("30 seconds").tag(30_000.0); Text("1 minute").tag(60_000.0); Text("5 minutes").tag(300_000.0)
                            }
                        } header: { TurnInBannerText(profile.provider.label + " · Beta") }
                            .listRowBackground(PitTechStyle.surface)
                    }
                }
                if step == 3 {
                    if runtime.profiles.filter({ !$0.sources.isEmpty }).isEmpty {
                        Section { Text(emptyDevicesText).font(PitTechFont.body) }.listRowBackground(PitTechStyle.surface)
                    } else {
                        NativeTelemetryAssignSections(runtime: runtime)
                    }
                }
                Section { Button("Add temperature connection") { adding = true }.accessibilityIdentifier("telemetry-add") }.listRowBackground(PitTechStyle.surface)
                if let error { Section { TurnInStatusStrip(text: error, accent: PitTechStyle.flag) }.listRowBackground(PitTechStyle.surface) }
            }
            .scrollContentBackground(.hidden)
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .onAppear { if !stepTouched { step = NativeTelemetryDevicesPresentation.initialStep(hasProfiles: !runtime.profiles.isEmpty) } }
            .navigationTitle("Connections · Beta")
            .sheet(isPresented: $adding) { addView }
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
                        do { authRequest = try NativeHAAuthRequest(server: endpoint); authNotice = nil; authorizing = true }
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
                // PL-06: visible progress during the token exchange (`d15`).
                if busy && provider == .home_assistant { HStack { ProgressView(); Text("Connecting to Home Assistant…").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) } }
                // TURN-IN (`d16`): the failure renders in place as a flag
                // strip — the form, URL included, stays as it was.
                if let error { TurnInStatusStrip(text: error, accent: PitTechStyle.flag) }
            }
            .scrollContentBackground(.hidden)
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .sheet(isPresented: $authorizing) {
                if let request = authRequest {
                    NavigationStack {
                        VStack(spacing: 0) {
                            // PL-06: a blocked foreign navigation is
                            // surfaced here — the flow stays alive.
                            if let authNotice { TurnInStatusStrip(text: authNotice, accent: PitTechStyle.marigold).padding() }
                            NativeHAOAuthView(request: request, onBlockedNavigation: { url in
                                authNotice = "This sign-in only continues on your Home Assistant server. Navigation to \(url.host ?? "another site") was blocked."
                            }) { result in
                                authorizing = false
                                switch result {
                                // PL-06: failures render in this sheet with
                                // the server URL; the form stays intact.
                                case .failure(let failure): error = "Home Assistant sign-in failed. \(failure.localizedDescription) Server: \(endpoint)"
                                case .success(let code):
                                    busy = true
                                    Task {
                                        do { let token = try await NativeHASession.exchange(server: endpoint, code: code); try await runtime.connect(NativeTelemetryProfile(provider: .home_assistant, endpoint: endpoint), user: "", secret: token.encoded); adding = false; error = nil }
                                        catch { self.error = "Home Assistant sign-in could not finish. \(error.localizedDescription) Server: \(endpoint)" }
                                        busy = false
                                    }
                                }
                            }.frame(maxWidth: .infinity, maxHeight: .infinity)
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

/// PL-05 (#172): step 3 of the Devices sheet — device/meal/channel-role/dish
/// assignment, inline. Every edit writes the runtime-held draft.
struct NativeTelemetryAssignSections: View {
    @Environment(CookStore.self) private var store
    @Bindable var runtime: NativeTelemetryRuntime
    @State private var profileID = ""
    @State private var error: String?
    @State private var busy = false
    @State private var applied = false
    var body: some View {
        let assignable = runtime.profiles.filter { !$0.sources.isEmpty }
        if let profile = assignable.first(where: { $0.id == profileID }) ?? assignable.first {
            let draft = runtime.assignmentDrafts[profile.id] ?? NativeTelemetryAssignmentDraft.seed(profile: profile, store: store)
            if assignable.count > 1 {
                Section { Picker("Connection", selection: $profileID) { ForEach(assignable) { Text($0.provider.label).tag($0.id) } } }.listRowBackground(PitTechStyle.surface)
            }
            Section {
                Picker("Device", selection: Binding(get: { draft.sourceID }, set: { id in
                    var d = draft
                    if let source = profile.sources.first(where: { $0.id == id }) { d.select(source: source, cookID: draft.cookID, store: store) }
                    runtime.updateAssignmentDraft(d, for: profile.id)
                })) { Text("Choose device").tag(""); ForEach(profile.sources) { Text($0.label).tag($0.id) } }
                Picker("Active meal", selection: Binding(get: { draft.cookID }, set: { id in
                    var d = draft
                    if let source = profile.sources.first(where: { $0.id == draft.sourceID }) { d.select(source: source, cookID: id, store: store) } else { d.cookID = id }
                    runtime.updateAssignmentDraft(d, for: profile.id)
                })) { Text("Choose meal").tag(""); ForEach(store.activeCooks.filter { $0.status == "active" }) { Text($0.title).tag($0.id) } }
            }.listRowBackground(PitTechStyle.surface)
            if let source = profile.sources.first(where: { $0.id == draft.sourceID }) {
                ForEach(source.channels) { channel in
                    // TURN-IN (`d23`): one section per channel under its
                    // banner name; the setpoint stays a locked read-only
                    // row — displayed, never editable.
                    Section {
                        if channel.kind != .setpoint { Picker("Use", selection: Binding(get: { draft.roles[channel.id] ?? channel.kind }, set: { role in var d = draft; d.roles[channel.id] = role; runtime.updateAssignmentDraft(d, for: profile.id) })) { ForEach([NativeTelemetryKind.food, .chamber, .ambient, .other], id: \.self) { Text($0.label).tag($0) } } }
                        else { HStack(spacing: 6) { Text("🔒"); Text("Observed target · read-only").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) } }
                        if (draft.roles[channel.id] ?? channel.kind) == .food { Picker("Food", selection: Binding(get: { draft.assignments[channel.id] ?? "" }, set: { dish in var d = draft; d.assignments[channel.id] = dish; runtime.updateAssignmentDraft(d, for: profile.id) })) { Text("Unassigned").tag(""); ForEach(store.dishRecords(draft.cookID)) { Text($0.name).tag($0.id) } } }
                    } header: { TurnInBannerText(channel.name) }
                        .listRowBackground(PitTechStyle.surface)
                }
            }
            Section {
                Text("Edits are kept as a draft if you leave — they apply when you attach.").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                Button("Attach to meal") {
                    guard let source = profile.sources.first(where: { $0.id == draft.sourceID }) else { return }
                    var mapped = source; mapped.channels = mapped.channels.map { ch in var c = ch; c.kind = draft.roles[ch.id] ?? ch.kind; return c }
                    let foods = mapped.channels.filter { $0.kind == .food }.reduce(into: [String: String?]()) { out, c in out[c.id] = draft.assignments[c.id].flatMap { $0.isEmpty ? nil : $0 } }
                    busy = true
                    Task { do { try await runtime.attach(profile, source: mapped, cook: draft.cookID, assignments: foods, store: store); applied = true } catch { self.error = error.localizedDescription }; busy = false }
                }.disabled(draft.sourceID.isEmpty || draft.cookID.isEmpty || busy)
                if applied { Text("Attached. Readings from this device will be saved with the meal.").font(PitTechFont.body) }
                if let error { TurnInStatusStrip(text: error, accent: PitTechStyle.flag) }
            }.listRowBackground(PitTechStyle.surface)
            .onAppear { _ = runtime.assignmentDraft(for: profile, store: store) }
        }
    }
}
