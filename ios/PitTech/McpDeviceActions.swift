import Foundation
import UIKit
import UserNotifications

/// MCP device actions (issue #114, §7) on iOS: this iPhone registers
/// itself with the account, opts into named classes of remote app
/// actions, and — when the user opens the app and checks — claims
/// queued requests from the assistant, performs them against the real
/// CookStore, and reports the truth back: a request counts as applied
/// only when this code posts that receipt. Nothing here can touch
/// grill hardware; the action enum the server accepts has no hardware
/// class. The device id and class opt-ins live in device-only
/// UserDefaults — never in the synced library or an export.

let mcpDeviceClasses = ["reminder", "timer", "guidance", "collection", "probe", "preferences"]

func mcpDeviceClassLabel(_ id: String) -> String {
    switch id {
    case "reminder": return "Reminders"
    case "timer": return "Timers"
    case "guidance": return "Guided cooking"
    case "collection": return "Temperature collection"
    case "probe": return "Probe assignments"
    case "preferences": return "Display & units"
    default: return id
    }
}

protocol McpDeviceIdentityBacking {
    var deviceId: String { get }
    func classes() -> Set<String>
    func setClass(_ id: String, _ enabled: Bool)
}

struct UserDefaultsMcpDeviceIdentityBacking: McpDeviceIdentityBacking {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    var deviceId: String {
        if let id = defaults.string(forKey: "mcp-device-id") { return id }
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: "mcp-device-id")
        return id
    }
    func classes() -> Set<String> { Set(mcpDeviceClasses.filter { defaults.bool(forKey: "mcp-device-class:\($0)") }) }
    func setClass(_ id: String, _ enabled: Bool) { defaults.set(enabled, forKey: "mcp-device-class:\(id)") }
}

struct McpDeviceIdentity {
    private let backing: McpDeviceIdentityBacking
    init(defaults: UserDefaults = .standard) { backing = UserDefaultsMcpDeviceIdentityBacking(defaults: defaults) }
    init(backing: McpDeviceIdentityBacking) { self.backing = backing }
    var deviceId: String { backing.deviceId }
    func classes() -> Set<String> { backing.classes() }
    func setClass(_ id: String, _ enabled: Bool) { backing.setClass(id, enabled) }
}

struct McpDeviceCommand {
    let id: String
    let action: String
    let params: [String: Any]
    static func parse(_ o: [String: Any]) -> McpDeviceCommand? {
        guard let id = o["id"] as? String, let action = o["action"] as? String else { return nil }
        return McpDeviceCommand(id: id, action: action, params: (o["params"] as? [String: Any]) ?? [:])
    }
}

struct DeviceActionReceipt {
    let outcome: String // applied | needs_attention | failed
    var detail: String? = nil
    var result: [String: Any]? = nil
}

struct DeviceActionNeedsAttention: Error { let message: String }

struct ReminderRuleInfo { let cookId: String; let enabled: Bool; let status: String }

/// The native capabilities a device action can use — one seam so the
/// dispatch logic is unit-testable without a live store.
protocol DeviceActionPorts {
    func reminderRule(_ ruleId: String) async -> ReminderRuleInfo?
    /// action is the reminder engine's own vocabulary: toggle | snooze | complete.
    func reminderChange(_ ruleId: String, _ action: String) async throws
    func reminderReconcile(_ cookId: String) async
    func notificationsEnabled() async -> Bool
    func candidateCookIds() async -> [String]
    func cookStatus(_ cookId: String) async -> String?
    func startTimer(_ cookId: String, _ title: String, _ durationMillis: Int64) async throws -> String
    func stopTimer(_ timerId: String) async -> Bool
    func activeRecordingCookId() async -> String?
    func pausedRecordingCookId() async -> String?
    func pauseCollection(_ cookId: String) async throws
    func resumeCollection(_ cookId: String) async throws
    func setCollectionCadence(_ cookId: String, _ intervalSeconds: Int64) async throws
    func dishCookId(_ dishId: String) async -> String?
    func assignProbe(_ probeId: String, _ cookId: String, _ dishId: String?) async throws
    func unassignProbe(_ probeId: String) async -> Bool
    func applyPreferences(_ values: [String: String]) async throws
    /// nil = no such playbook on this phone; true/false = guided phases on/off for the meal.
    func playbookGuidanceActive(_ playbookId: String, _ mealId: String) async -> Bool?
}

struct DeviceActionDispatcher {
    let ports: DeviceActionPorts
    func execute(_ command: McpDeviceCommand) async -> DeviceActionReceipt {
        let p = command.params
        func string(_ key: String) -> String? { p[key] as? String }
        func int(_ key: String) -> Int64? { (p[key] as? NSNumber)?.int64Value }
        do {
            switch command.action {
            case "reminder.activate":
                guard let ruleId = string("ruleId"), let rule = await ports.reminderRule(ruleId) else { return notOnPhone("That reminder") }
                if !rule.enabled { try await ports.reminderChange(ruleId, "toggle"); await ports.reminderReconcile(rule.cookId) }
                if await !ports.notificationsEnabled() {
                    return DeviceActionReceipt(outcome: "needs_attention",
                        detail: "The reminder is on, but notifications are off for PitTech — turn them on so it can alert you.",
                        result: ["enabled": true])
                }
                return DeviceActionReceipt(outcome: "applied", result: ["enabled": true])
            case "reminder.cancel":
                guard let ruleId = string("ruleId"), let rule = await ports.reminderRule(ruleId) else { return notOnPhone("That reminder") }
                if rule.enabled { try await ports.reminderChange(ruleId, "toggle"); await ports.reminderReconcile(rule.cookId) }
                return DeviceActionReceipt(outcome: "applied", result: ["enabled": false])
            case "reminder.snooze":
                guard let ruleId = string("ruleId"), let rule = await ports.reminderRule(ruleId) else { return notOnPhone("That reminder") }
                let requested = int("minutes") ?? 10
                try await ports.reminderChange(ruleId, "snooze"); await ports.reminderReconcile(rule.cookId)
                // The app's snooze is its standard 10 minutes; the
                // receipt says what actually happened, not what was asked.
                return DeviceActionReceipt(outcome: "applied", result: ["snoozedMinutes": 10, "requestedMinutes": requested])
            case "reminder.acknowledge":
                guard let ruleId = string("ruleId"), let rule = await ports.reminderRule(ruleId) else { return notOnPhone("That reminder") }
                if rule.status != "completed" { try await ports.reminderChange(ruleId, "complete"); await ports.reminderReconcile(rule.cookId) }
                return DeviceActionReceipt(outcome: "applied")
            case "timer.start":
                let explicit = string("mealId")
                let candidates = await ports.candidateCookIds()
                guard let cookId = explicit ?? (candidates.count == 1 ? candidates[0] : nil) else {
                    return DeviceActionReceipt(outcome: "needs_attention",
                        detail: explicit == nil ? "No meal was named and this phone doesn't have exactly one meal in progress — name the meal, or start the timer in the app." : "That meal isn't on this phone.")
                }
                if await ports.cookStatus(cookId) == "completed" {
                    return DeviceActionReceipt(outcome: "needs_attention", detail: "That meal is finished — timers can't be added to it.")
                }
                guard let label = string("label"), let minutes = int("durationMinutes") else {
                    return DeviceActionReceipt(outcome: "failed", detail: "The request was missing its label or duration.")
                }
                let timerId = try await ports.startTimer(cookId, label, minutes * 60_000)
                return DeviceActionReceipt(outcome: "applied", result: ["timerId": timerId])
            case "timer.stop":
                guard let timerId = string("timerId"), await ports.stopTimer(timerId) else {
                    return DeviceActionReceipt(outcome: "needs_attention", detail: "That timer isn't on this phone.")
                }
                return DeviceActionReceipt(outcome: "applied")
            case "guidance.apply_playbook":
                guard let playbookId = string("playbookId"), let mealId = string("mealId") else {
                    return DeviceActionReceipt(outcome: "failed", detail: "The request was missing its playbook or meal.")
                }
                switch await ports.playbookGuidanceActive(playbookId, mealId) {
                case nil: return DeviceActionReceipt(outcome: "needs_attention", detail: "That playbook isn't on this phone yet — sync, then ask again.")
                case true: return DeviceActionReceipt(outcome: "applied", result: ["guidance": "active"])
                default: return DeviceActionReceipt(outcome: "needs_attention", detail: "Guided phases are off for this meal — open the playbook in PitTech and turn on guided phases to apply it.")
                }
            case "collection.pause":
                guard let cookId = await ports.activeRecordingCookId() else {
                    return DeviceActionReceipt(outcome: "needs_attention", detail: "No temperature collection is running on this phone.")
                }
                try await ports.pauseCollection(cookId); return DeviceActionReceipt(outcome: "applied")
            case "collection.resume":
                guard let cookId = await ports.pausedRecordingCookId() else {
                    return DeviceActionReceipt(outcome: "needs_attention", detail: "No paused temperature collection to resume on this phone.")
                }
                try await ports.resumeCollection(cookId); return DeviceActionReceipt(outcome: "applied")
            case "collection.set_cadence":
                guard let cookId = await ports.activeRecordingCookId() else {
                    return DeviceActionReceipt(outcome: "needs_attention", detail: "No temperature collection is running on this phone.")
                }
                guard let seconds = int("intervalSeconds") else {
                    return DeviceActionReceipt(outcome: "failed", detail: "The request was missing its interval.")
                }
                try await ports.setCollectionCadence(cookId, seconds)
                return DeviceActionReceipt(outcome: "applied", result: ["intervalSeconds": seconds])
            case "probe.assign":
                guard let dishId = string("dishId"), let probeId = string("probeId") else {
                    return DeviceActionReceipt(outcome: "failed", detail: "The request was missing its probe or dish.")
                }
                guard let cookId = await ports.dishCookId(dishId) else {
                    return DeviceActionReceipt(outcome: "needs_attention", detail: "That dish isn't on this phone.")
                }
                try await ports.assignProbe(probeId, cookId, dishId)
                return DeviceActionReceipt(outcome: "applied")
            case "probe.unassign":
                guard let probeId = string("probeId") else {
                    return DeviceActionReceipt(outcome: "failed", detail: "The request was missing its probe.")
                }
                _ = await ports.unassignProbe(probeId)
                return DeviceActionReceipt(outcome: "applied", result: ["assigned": false])
            case "preferences.update":
                var values: [String: String] = [:]
                if let v = string("themeMode") { values["themeMode"] = v }
                if let v = string("temperatureUnit") { values["temperatureUnit"] = v }
                if let v = string("weightUnit") { values["weightUnit"] = v }
                try await ports.applyPreferences(values)
                return DeviceActionReceipt(outcome: "applied", result: ["effective": "next app refresh"])
            default:
                return DeviceActionReceipt(outcome: "failed", detail: "This phone's PitTech doesn't know that action.")
            }
        } catch let attention as DeviceActionNeedsAttention {
            return DeviceActionReceipt(outcome: "needs_attention", detail: attention.message)
        } catch {
            return DeviceActionReceipt(outcome: "needs_attention", detail: "That action couldn't be completed on this phone.")
        }
    }
    private func notOnPhone(_ what: String) -> DeviceActionReceipt {
        DeviceActionReceipt(outcome: "needs_attention", detail: "\(what) isn't on this phone yet — sync, then ask again.")
    }
}

/// The production ports: every call lands on the app's real store —
/// the same mutations the UI performs.
@MainActor final class CookStoreDeviceActionPorts: DeviceActionPorts {
    private let store: CookStore
    init(store: CookStore) { self.store = store }
    func reminderRule(_ ruleId: String) async -> ReminderRuleInfo? {
        guard let rule = store.reminderRule(ruleId), let cookId = rule["cookId"]?.stringValue else { return nil }
        return ReminderRuleInfo(cookId: cookId, enabled: rule["enabled"]?.boolValue ?? false, status: rule["status"]?.stringValue ?? "")
    }
    func reminderChange(_ ruleId: String, _ action: String) async throws { try store.changeReminder(ruleId, action: action) }
    func reminderReconcile(_ cookId: String) async { store.submitReminderSchedules(store.prepareReminderSchedules()) }
    func notificationsEnabled() async -> Bool {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional || status == .ephemeral
    }
    func candidateCookIds() async -> [String] { store.library.cooks.filter { $0.isActive }.map { $0.id } }
    func cookStatus(_ cookId: String) async -> String? { store.library.cooks.first { $0.id == cookId }?.status }
    func startTimer(_ cookId: String, _ title: String, _ durationMillis: Int64) async throws -> String {
        try store.startActivityTimer(cookId: cookId, dishId: nil, title: title, method: nil, target: Double(durationMillis))
        let timer = store.library.activityTimers.compactMap { $0.objectValue }
            .filter { $0["cookId"]?.stringValue == cookId && $0["title"]?.stringValue == title && $0["status"]?.stringValue == "running" }
            .max { ($0["startedAtUtcMillis"]?.numberValue ?? 0) < ($1["startedAtUtcMillis"]?.numberValue ?? 0) }
        return timer?["id"]?.stringValue ?? ""
    }
    func stopTimer(_ timerId: String) async -> Bool {
        guard store.library.activityTimers.contains(where: { $0.objectValue?["id"]?.stringValue == timerId }) else { return false }
        do { _ = try store.stopActivityTimer(timerId); return true } catch { return false }
    }
    // iOS observes telemetry; it does not run a recording engine, so
    // collection control honestly reports that the recording device
    // owns it (§7: needs_attention is a designed outcome, not a gap).
    func activeRecordingCookId() async -> String? { nil }
    func pausedRecordingCookId() async -> String? { nil }
    func pauseCollection(_ cookId: String) async throws { throw DeviceActionNeedsAttention(message: "Collection runs on the recording device — pause it there.") }
    func resumeCollection(_ cookId: String) async throws { throw DeviceActionNeedsAttention(message: "Collection runs on the recording device — resume it there.") }
    func setCollectionCadence(_ cookId: String, _ intervalSeconds: Int64) async throws { throw DeviceActionNeedsAttention(message: "Collection runs on the recording device — change its cadence there.") }
    func dishCookId(_ dishId: String) async -> String? {
        store.library.dishes.first { $0.objectValue?["id"]?.stringValue == dishId }?.objectValue?["cookId"]?.stringValue
    }
    func assignProbe(_ probeId: String, _ cookId: String, _ dishId: String?) async throws {
        guard let index = store.library.probes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == probeId }) else {
            throw DeviceActionNeedsAttention(message: "That probe isn't on this phone.")
        }
        let now = Double(Int64(Date().timeIntervalSince1970 * 1000))
        let prior = store.library
        store.library.probeAssignments = store.library.probeAssignments.map { row in
            guard var o = row.objectValue, o["probeId"]?.stringValue == probeId, o["endedAtUtcMillis"]?.numberValue == nil else { return row }
            o["endedAtUtcMillis"] = .number(now); return .object(o)
        }
        store.library.probeAssignments.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId),
            "probeId": .string(probeId), "dishId": dishId.map(JSONValue.string) ?? .null, "startedAtUtcMillis": .number(now)]))
        if var probe = store.library.probes[index].objectValue {
            probe["assignedDishId"] = dishId.map(JSONValue.string) ?? .null
            store.library.probes[index] = .object(probe)
        }
        store.save()
        if store.saveError != nil { store.library = prior; throw DeviceActionNeedsAttention(message: "The assignment couldn't be saved on this phone.") }
    }
    func unassignProbe(_ probeId: String) async -> Bool {
        guard let probe = store.library.probes.first(where: { $0.objectValue?["id"]?.stringValue == probeId }),
              let cookId = probe.objectValue?["cookId"]?.stringValue,
              store.library.probeAssignments.contains(where: { $0.objectValue?["probeId"]?.stringValue == probeId && $0.objectValue?["endedAtUtcMillis"]?.numberValue == nil })
        else { return false }
        do { try await assignProbe(probeId, cookId, nil); return true } catch { return false }
    }
    func applyPreferences(_ values: [String: String]) async throws {
        if store.library.preferences == nil { store.library.preferences = [:] }
        for (key, value) in values { store.library.preferences?[key] = .string(value) }
        store.save()
        if store.saveError != nil { throw DeviceActionNeedsAttention(message: "The preference couldn't be saved on this phone.") }
    }
    func playbookGuidanceActive(_ playbookId: String, _ mealId: String) async -> Bool? {
        guard store.library.companionRecords.contains(where: { $0.objectValue?["id"]?.stringValue == playbookId && $0.objectValue?["kind"]?.stringValue == "playbook" }) else { return nil }
        return store.timingMode(mealId) == "explicit_phases"
    }
}

@MainActor @Observable final class NativeMcpDeviceActions {
    private(set) var registered = false
    private(set) var available = true
    private(set) var classes: Set<String>
    private(set) var busy = false
    private(set) var message: String?
    private(set) var lastCheckSummary: String?

    private let account: NativeAccountCoordinator
    private let identity: McpDeviceIdentity
    private let dispatcher: DeviceActionDispatcher
    private let api: NativeMcpAPI
    private let deviceName: () -> String
    init(account: NativeAccountCoordinator, store: CookStore, api: NativeMcpAPI = NativeMcpClient(),
         identity: McpDeviceIdentity = McpDeviceIdentity(), deviceName: @escaping () -> String = { UIDevice.current.model }) {
        self.account = account
        self.identity = identity
        self.dispatcher = DeviceActionDispatcher(ports: CookStoreDeviceActionPorts(store: store))
        self.api = api
        self.deviceName = deviceName
        self.classes = identity.classes()
    }
    /// Test seam: drive the manager with fake ports and identity.
    init(account: NativeAccountCoordinator, dispatcher: DeviceActionDispatcher, api: NativeMcpAPI,
         identity: McpDeviceIdentity, deviceName: @escaping () -> String) {
        self.account = account
        self.identity = identity
        self.dispatcher = dispatcher
        self.api = api
        self.deviceName = deviceName
        self.classes = identity.classes()
    }

    private func perform(_ operation: () async throws -> Void) async {
        if busy { return }
        busy = true; message = nil
        do { try await operation() }
        catch let error as NativeAccountError {
            if case .status(401) = error { message = "Sign in again to manage device actions." }
            else if case .status(404) = error { available = false }
            else if case .network = error { message = "Couldn't reach PitTech. Nothing was changed." }
            else { message = "Something went wrong. Nothing was changed." }
        } catch is CancellationError { message = "Request interrupted. Try again." }
        catch { message = "Something went wrong. Nothing was changed." }
        busy = false
    }

    /// Registers (or re-registers with the current opt-ins), then
    /// checks for waiting requests. Safe whenever the account screen
    /// shows: registration is an idempotent upsert.
    func refresh() async {
        await perform {
            guard account.signedIn else { return }
            try await registerLocked()
            try await checkLocked()
        }
    }

    func setClass(_ id: String, _ enabled: Bool) {
        identity.setClass(id, enabled)
        classes = identity.classes()
        if account.signedIn { Task { await perform { try await registerLocked() } } }
    }

    func checkNow() async {
        await perform {
            guard account.signedIn else { return }
            if !registered { try await registerLocked() }
            try await checkLocked()
        }
    }

    func unregister() async {
        await perform {
            let token = try await account.access()
            _ = try await api.call("DELETE", "/v1/me/mcp/devices/\(identity.deviceId)", body: nil, access: token)
            registered = false
            message = "This phone no longer takes assistant requests. Waiting requests for it were canceled."
        }
    }

    private func registerLocked() async throws {
        let token = try await account.access()
        let body: [String: Any] = ["deviceId": identity.deviceId, "name": deviceName(), "platform": "ios",
                                   "actionClasses": Array(identity.classes())]
        _ = try await api.call("POST", "/v1/me/mcp/devices", body: body, access: token)
        registered = true; available = true; classes = identity.classes()
    }

    private func checkLocked() async throws {
        let token = try await account.access()
        let data = try await api.call("POST", "/v1/me/mcp/devices/\(identity.deviceId)/claim", body: [:], access: token)
        let reply = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let commands = ((reply?["commands"] as? [[String: Any]]) ?? []).compactMap(McpDeviceCommand.parse)
        var applied = 0; var attention = 0
        for command in commands {
            let receipt = await dispatcher.execute(command)
            if receipt.outcome == "applied" { applied += 1 } else { attention += 1 }
            var body: [String: Any] = ["commandId": command.id, "outcome": receipt.outcome]
            if let detail = receipt.detail { body["detail"] = detail }
            if let result = receipt.result { body["result"] = result }
            _ = try? await api.call("POST", "/v1/me/mcp/devices/\(identity.deviceId)/receipt", body: body, access: token)
        }
        lastCheckSummary = commands.isEmpty ? "No waiting requests."
            : attention == 0 ? "Handled \(commands.count) request\(commands.count == 1 ? "" : "s")."
            : "Handled \(applied), \(attention) need\(attention == 1 ? "s" : "") your attention — the assistant can see the details."
    }
}
