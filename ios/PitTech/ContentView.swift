import SwiftUI
import UserNotifications

struct ContentView: View {
    @Environment(CookStore.self) private var store
    @Environment(NativePublicCoordinator.self) private var community
    @Environment(NativeRelayCoordinator.self) private var relay
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(\.scenePhase) private var scenePhase
    @State private var telemetry = NativeTelemetryRuntime()
    @State private var connectivity = ConnectivityMonitor()
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

    // Every root sheet and alert lives in RootPresentationModifier:
    // attaching them here pushed this modifier chain past the compiler's
    // type-checking budget (which is why tabRoot is extracted at all).

    private var tabRoot: some View {
        // PL-23 (DD-8): the offline banner sits above the tabs on every
        // tab while the device has no connection.
        VStack(spacing: 0) {
            if !connectivity.isOnline { OfflineBannerView() }
            tabView
        }
    }

    private var tabView: some View {
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
        .onChange(of: store.recipesTabRequest) { _, _ in selectedTab = "recipes" }
        .environment(telemetry)
        .tint(PitTechStyle.emberText)
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
        .modifier(RootPresentationModifier(telemetry: telemetry))
    }
}

/// Every root sheet and alert, attached as one modifier so ContentView's
/// own modifier chain stays inside the compiler's type-checking budget
/// (the chain was already at the edge; Slice 9's additions tipped it over
/// twice before this extraction).
private struct RootPresentationModifier: ViewModifier {
    @Environment(CookStore.self) private var store
    @Environment(NativeRelayCoordinator.self) private var relay
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(ProSubscriptionCoordinator.self) private var pro
    let telemetry: NativeTelemetryRuntime

    private var archiveIntakeBinding: Binding<NativeArchiveIntake?> {
        Binding(get: { store.archiveIntake }, set: { if $0 == nil { store.discardArchiveIntake() } })
    }

    private var publicRecipeStartBinding: Binding<PublicRecipeStart?> {
        Binding(get: { store.publicRecipeStart }, set: { store.publicRecipeStart = $0 })
    }

    func body(content: Content) -> some View {
        content
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
                if let start = store.pendingPublicRecipeStart {
                    // PL-19: a "Cook this" save promotes to the Start Cook
                    // review sheet instead of opening a live cook directly.
                    store.pendingPublicRecipeStart = nil; store.publicRecipeStart = start; store.archiveCompletionMessage = nil
                } else if let destination = store.publicCookDestination {
                    store.publicCookDestination = nil; store.notificationCookId = destination; store.archiveCompletionMessage = nil
                } else { store.archiveMessage = store.archiveCompletionMessage; store.archiveCompletionMessage = nil }
            }) { source in NativeArchiveImportView(source: source).modifier(ScreenshotTextSize()) }
            .sheet(item: publicRecipeStartBinding, onDismiss: {
                if let id = store.pendingStartedCookId {
                    store.pendingStartedCookId = nil; store.notificationCookId = id
                }
            }) { start in
                // PL-10 (#172): the Cook Pack start lands on the same
                // unified Start Cook review as every other start path.
                StartCookSheet(
                    initialSetup: (try? store.recipeStartSetup(title: start.title, revisionIds: start.revisionIds, sourceLabel: "From a shared Cook Pack · the recipes are saved in your Recipes"))
                        ?? CookSetupDraft(sourceLabel: "From a shared Cook Pack · the recipes are saved in your Recipes", title: start.title),
                    onStarted: { store.pendingStartedCookId = $0 }
                ).modifier(ScreenshotTextSize())
            }
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
    @State private var startSetup: CookSetupDraft?
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
                    // TURN-IN (Slice 1): no page-title header — the
                    // selected Meals tab carries location (BRIEF: headers
                    // are wasted space). The DD-1 lockup above stays.
                    if isLibraryEmpty {
                        // First-run empty Meals (PL-13 parity with Android):
                        // lockup → headline → promise → Start meal → Restore.
                        VStack(alignment: .leading, spacing: 10) {
                            TurnInBannerText("Your cook log is ready", font: PitTechFont.bannerTitle)
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
                                TurnInMetaLabel("Also cooking")
                                ForEach(store.activeCooks.dropFirst()) { cook in
                                    NavigationLink(value: cook.id) { CookCard(cook: cook, dishes: store.dishes(for: cook.id)) }
                                        .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                    Button { startSetup = .blank(temperatureUnit: "°\(store.temperatureUnit)", weightUnit: store.weightUnit) } label: {
                        Label("Start meal", systemImage: "plus")
                            .font(PitTechFont.bodyEmphasis)
                            .foregroundStyle(PitTechStyle.onAccent)
                            .frame(maxWidth: .infinity, minHeight: 54)
                            .background(PitTechStyle.primaryFill, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)

                    if let draft = store.setupDraft {
                        // PL-13: the kept setup is one tap from Meals,
                        // never a surprise — same card as Android.
                        VStack(alignment: .leading, spacing: 6) {
                            TurnInBannerText("Cook setup draft")
                            Text((draft.title.isEmpty ? "Untitled setup" : draft.title) + (draft.sourceLabel.map { " · \($0)" } ?? ""))
                                .font(.subheadline).foregroundStyle(.secondary)
                            HStack(spacing: 8) {
                                Button("Resume setup") { startSetup = draft }
                                    .buttonStyle(.borderedProminent)
                                    .accessibilityIdentifier("resume-setup")
                                Button("Discard setup", role: .destructive) { store.clearSetupDraft() }
                                    .accessibilityIdentifier("discard-setup")
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("setup-draft-card")
                    }

                    if isLibraryEmpty {
                        Button { showingRestore = true } label: {
                            Text("Restore from backup")
                                .frame(maxWidth: .infinity, minHeight: 50)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("empty-restore-backup")
                    } else {
                        VStack(alignment: .leading, spacing: 10) {
                            TurnInMetaLabel("Cook log")
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
            .sheet(item: $startSetup) { setup in StartCookSheet(initialSetup: setup, onStarted: { mealPath.append($0) }) }
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
        // TURN-IN (Slice 1): the d01 live lane — token surface with ruling
        // border and an ember edge bar, banner cook name, a filled
        // LIVE/PAUSED pill, the big reading in the scoreboard face, and
        // the detail line in mono. Replaces the hardcoded Night Service
        // cream/ink treatment.
        HStack(spacing: 0) {
            Rectangle().fill(PitTechStyle.ember).frame(width: 4)
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top) {
                    TurnInBannerText(cook.title)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    Text(cook.status == "paused" ? "PAUSED" : "LIVE")
                        .font(PitTechFont.monoMeta)
                        .foregroundStyle(PitTechStyle.onAccent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(cook.status == "paused" ? PitTechStyle.marigold : PitTechStyle.ember, in: RoundedRectangle(cornerRadius: 8))
                }
                // Deliberately NOT ticking TimelineViews: a perpetual timer on the
                // launch screen keeps the app from ever idling (it broke the UI-test
                // shards and costs battery for a secondary readout). Values refresh
                // whenever Meals re-renders on store changes; the ticking clock
                // lives on the cook's Live screen, as before.
                if let pit = latestPit {
                    Text(tempText(pit))
                        .font(PitTechFont.scoreboard)
                        .foregroundStyle(PitTechStyle.ink)
                } else {
                    Text(elapsed(at: Date.now))
                        .font(PitTechFont.scoreboard)
                        .foregroundStyle(PitTechStyle.ink)
                }
                Text([
                    latestFood.map { "Food \(tempText($0))" },
                    "Elapsed \(elapsed(at: Date.now))",
                    cook.initialSetpointValue.map { "Pit set \(String(format: "%.0f", $0))\(cook.initialSetpointUnit ?? "")" },
                    cook.smokerName,
                ].compactMap { $0 }.joined(separator: " · "))
                .font(PitTechFont.monoMeta)
                .foregroundStyle(PitTechStyle.inkMuted)
            }
            .padding(16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PitTechStyle.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(PitTechStyle.ruling, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
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
                TurnInBannerText(cook.title)
                    .fixedSize(horizontal: false, vertical: true)
                Text(dishes.isEmpty ? cook.startedAt.formatted(date: .abbreviated, time: .shortened) : dishes.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(.secondary)
                    .lineLimit(textSize.isAccessibilitySize ? nil : 2)
                // TURN-IN d01: completed lanes carry the verdict stars.
                // (Android's home data has no results relation yet — the
                // lane stars are the one platform delta, flagged in the PR.)
                if !cook.isActive, let rating = store.cookResults(cookId: cook.id, dishId: nil).overallRating {
                    TurnInStars(rating: rating)
                }
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

/// PL-10 / PL-13 (#172): the one Start Cook review for every iOS start
/// path — blank, Recipes, meal combinations, and shared Cook Packs all
/// land here with the same fields in the same order as Android's Start
/// Cook review (Source · Current stage · Cook name · Dishes · Smoker ·
/// Starting setpoint · Cook notes · More cook details). Nothing is
/// cooking until "Begin preparation" is tapped, and leaving a changed
/// setup keeps it as a draft on this phone, exactly like Android.
struct StartCookSheet: View {
    var onStarted: (String) -> Void = { _ in }
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var setup: CookSetupDraft
    @State private var initialSetup: CookSetupDraft
    @State private var confirmLeave = false
    @State private var error: String?
    @State private var newDishName = ""
    @State private var newDishFoodType = "Other"

    init(initialSetup: CookSetupDraft, onStarted: @escaping (String) -> Void = { _ in }) {
        self.onStarted = onStarted
        _setup = State(initialValue: initialSetup)
        _initialSetup = State(initialValue: initialSetup)
    }

    private var dirty: Bool { setup != initialSetup || !newDishName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                if let label = setup.sourceLabel {
                    Section("Source") {
                        Text(label).accessibilityIdentifier("cook-source")
                        Text("Nothing is cooking yet — review the setup, then begin preparation when you're ready.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("Current stage") {
                    Toggle("Already cooking", isOn: $setup.alreadyCooking)
                    if setup.alreadyCooking { DatePicker("Actual Food on", selection: $setup.foodOn, in: ...Date.now) }
                    else { Text("Preparing until you log Food on.") }
                }
                Section("Cook name") {
                    TextField("Cook name", text: $setup.title).accessibilityIdentifier("meal-title")
                }
                Section("Dishes") {
                    ForEach($setup.dishes) { $dish in
                        if dish.revisionId != nil {
                            // Sourced dishes are reviewed as they will be
                            // attached — and removable — not re-typed.
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(dish.name)
                                    if !dish.detailLine.isEmpty { Text(dish.detailLine).font(.footnote).foregroundStyle(.secondary) }
                                }
                            } icon: { Image(systemName: "book.closed") }
                        } else {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(dish.name)
                                if !dish.detailLine.isEmpty { Text(dish.detailLine).font(.footnote).foregroundStyle(.secondary) }
                            }
                        }
                    }
                    .onDelete { setup.dishes.remove(atOffsets: $0) }
                    TextField("Dish name", text: $newDishName).accessibilityIdentifier("cook-dish-name")
                    Picker("Food type", selection: $newDishFoodType) {
                        ForEach(cookSetupFoodTypes, id: \.self) { Text($0).tag($0) }
                    }.accessibilityIdentifier("cook-dish-food-type")
                    Button("Add dish") {
                        let name = newDishName.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !name.isEmpty else { return }
                        setup.dishes.append(CookSetupDish(name: name, foodType: newDishFoodType))
                        newDishName = ""
                    }.disabled(newDishName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("cook-add-dish")
                }
                Section("Smoker") {
                    TextField("Smoker or grill (optional)", text: $setup.smokerName).accessibilityIdentifier("cook-smoker")
                }
                Section("Starting setpoint") {
                    TextField("Starting setpoint (optional)", text: $setup.setpointText)
                        .keyboardType(.decimalPad).accessibilityIdentifier("cook-setpoint")
                    Picker("Unit", selection: $setup.setpointUnit) {
                        Text("°F").tag("°F"); Text("°C").tag("°C")
                    }.pickerStyle(.segmented)
                }
                Section("Cook notes") {
                    TextField("Cook notes (optional)", text: $setup.notes, axis: .vertical).accessibilityIdentifier("cook-notes")
                }
                Section("More cook details") {
                    TextField("Fuel type", text: $setup.fuelType).accessibilityIdentifier("cook-fuel")
                    TextField("Wood or pellet blend", text: $setup.woodBlend).accessibilityIdentifier("cook-wood-blend")
                    TextField("Outdoor temperature", text: $setup.outdoorTemperatureText)
                        .keyboardType(.decimalPad).accessibilityIdentifier("cook-outdoor-temperature")
                    Picker("Outdoor unit", selection: $setup.outdoorTemperatureUnit) {
                        Text("°F").tag("°F"); Text("°C").tag("°C")
                    }.pickerStyle(.segmented)
                    TextField("Weather", text: $setup.weather).accessibilityIdentifier("cook-weather")
                    TextField("Wind or exposure", text: $setup.wind).accessibilityIdentifier("cook-wind")
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
                Section {
                    Text("You can start with manual logging and add temperature readings later.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .scrollContentBackground(.hidden)
            .background(PitTechStyle.canvas.ignoresSafeArea())
            .navigationTitle(setup.sourceLabel == nil ? "Start meal" : "Review meal setup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { if dirty { confirmLeave = true } else { dismiss() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Begin preparation") {
                        do {
                            let id = try store.startCook(setup: setup)
                            onStarted(id)
                            dismiss()
                        } catch { self.error = error.localizedDescription }
                    }.accessibilityIdentifier("start-meal-save")
                }
            }
            // PL-13: edits are kept as a draft on this phone as they
            // happen — the same autosave Android's review does — so
            // leaving by any route never loses the setup.
            .onChange(of: setup) { _, current in
                if current != initialSetup { store.keepSetupDraft(current) }
            }
            .confirmationDialog("Leave this cook setup?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Keep draft") { dismiss() }.accessibilityIdentifier("setup-keep-draft")
                Button("Discard setup", role: .destructive) { store.clearSetupDraft(); dismiss() }.accessibilityIdentifier("setup-discard")
                Button("Keep editing", role: .cancel) { }
            } message: {
                Text("Your setup is kept as a draft on this phone. Resume it any time from Meals.")
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
                    // PL-21 (DD-3): the Focus List header is a compact stat
                    // strip — the same five figures the card grid carried,
                    // none dropped, at a fraction of the height.
                    let durations = store.completedCooks.compactMap { cook -> Double? in
                        guard let end = cook.endedAtUtcMillis, end >= cook.startedAtUtcMillis else { return nil }
                        return Double(end - cook.startedAtUtcMillis) / 3_600_000
                    }
                    HStack(alignment: .top, spacing: 16) {
                        InsightsStat(value: "\(store.completedCooks.count)", label: "Finished cooks")
                        InsightsStat(value: "\(store.activeCooks.count)", label: "Active now")
                        InsightsStat(value: "\(store.library.readings.count)", label: "Temperature readings")
                        InsightsStat(value: "\(store.library.events.count)", label: "Timeline notes")
                        if let average = durations.average {
                            InsightsStat(value: average < 24 ? "\(average.formatted(.number.precision(.fractionLength(1)))) hr" : "\((average / 24).formatted(.number.precision(.fractionLength(1)))) days", label: "Average session time")
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if !store.completedCooks.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("RECENTLY FINISHED").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                            // PL-21: recently-finished cards open the cook,
                            // like the Meals history cards do.
                            ForEach(store.completedCooks.prefix(5)) { cook in
                                NavigationLink(value: cook.id) { CookCard(cook: cook, dishes: store.dishes(for: cook.id)) }
                                    .buttonStyle(.plain)
                            }
                        }
                    } else {
                        // PL-25: the Insights empty is the shared StateCopy wording.
                        ContentUnavailableView(EmptyStateCopy.insightsTitle, systemImage: "chart.xyaxis.line", description: Text(EmptyStateCopy.insightsBody))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 18)
                    }
                    // PL-21 design half: the iOS Insights job set (search,
                    // filters, comparisons, statistics) is parity issue
                    // #185 — until it lands, the gap is stated honestly
                    // instead of the surface pretending to be complete.
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Search, filters, comparisons and statistics aren't available in Insights on iOS yet.")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Text("Your full cook history is in Meals.")
                            .font(.subheadline).foregroundStyle(.secondary)
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
            .navigationDestination(for: String.self) { CookDetailView(cookId: $0) }
        }
    }
}

/// One figure in the Insights Focus List header strip (PL-21 / DD-3).
private struct InsightsStat: View {
    let value: String
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.weight(.semibold)).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct SettingsView: View {
    @Environment(CookStore.self) private var store
    @Environment(AdsConsentManager.self) private var adsConsent
    @State private var showingExport = false
    @State private var showingImport = false
    @State private var exportDocument = PitTechArchiveDocument(data: Data())
    @State private var archiveError: String?
    @State private var notificationsAllowed = false

    var body: some View {
        Group {
            Form {
                // PL-26 (#172): the Settings section order is the shared
                // cross-platform order (SettingsCopy.sectionOrder):
                // Appearance, Units, Cook notifications, Your data,
                // Feedback, Ads and privacy, About — same contents per
                // section on both platforms, platform mechanics aside.
                Section("Appearance") {
                    // TURN-IN (BUILD-SPEC §3.4, copy delta §1.1.1): the working
                    // Dark / Light / System control designed in Area 06 frame
                    // f01 replaces the #172 informational row. The selection
                    // shown is the resolved appearance (PitTechAppearance),
                    // so a library carrying the old factory seed reads Dark.
                    Picker("Appearance", selection: Binding(
                        get: { PitTechAppearance.selectionTag(store.library.preferences?["themeMode"]?.stringValue) },
                        set: { store.setPreference("themeMode", value: $0) }
                    )) {
                        Text("Dark").tag("DARK")
                        Text("Light").tag("LIGHT")
                        Text("System").tag("SYSTEM")
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("appearance-picker")
                    Text("Dark is the default — light is the same layout.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Section("Units") {
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
                    Text("Changing a preferred unit affects new entries. Existing records keep the unit in which you entered them.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Section("Cook notifications") {
                    Text("Guidance reminders and monitoring alerts have separate sound settings. Turn on alerts from a cook when you want to use them.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(notificationsAllowed ? "Notifications allowed" : "Notifications are disabled")
                    Link("Notification settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                }
                Section("Your data") {
                    Text(SettingsCopy.dataStaysLine)
                        .font(.subheadline)
                    Text(SettingsCopy.fullBackupScope)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Button("Export cook archive") {
                        do {
                            exportDocument = PitTechArchiveDocument(data: try store.exportArchive())
                            showingExport = true
                        } catch {
                            archiveError = ErrorCopy.saveChangeFailed
                        }
                    }
                    Button("Restore cook archive") { showingImport = true }
                    NativeDataExportButtons()
                }
                Section("Feedback") {
                    Text("Tell us about a problem or suggest a feature without needing a GitHub account. Cook records and photos are never included.")
                        .font(.subheadline)
                    NavigationLink("Feedback & feature ideas") { NativeFeedbackView() }
                }
                Section("Ads and privacy") {
                    Link("Privacy policy", destination: URL(string: "https://pittech-privacy-production.up.railway.app")!)
                    if adsConsent.isPrivacyOptionsRequired {
                        Button("Privacy choices") {
                            Task { await adsConsent.presentPrivacyOptions() }
                        }
                    }
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    Link("Contact support", destination: URL(string: "mailto:sportfolioholdings@gmail.com")!)
                    NavigationLink("Third-party telemetry notices") {
                        ScrollView { Text((Bundle.main.url(forResource: "TelemetryNotices", withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) }) ?? "No telemetry dependency notices in this build.").font(.caption).padding() }.navigationTitle("Third-party notices")
                    }
                }
            }
            .navigationTitle("Settings")
            .task { notificationsAllowed = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .authorized }
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
