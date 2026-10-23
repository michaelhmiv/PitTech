import XCTest
@testable import PitTech

/// MCP device actions on iOS (issue #114, §7): the dispatcher's honest
/// outcomes against fake ports, and the manager's register → claim →
/// execute → receipt flow against a fake transport.
final class McpDeviceActionsTests: XCTestCase {
    private let deviceId = "11111111-2222-4333-8444-555555555555"
    private let cmdId = "22222222-3333-4444-8555-666666666666"

    private final class Ports: DeviceActionPorts {
        var rule: ReminderRuleInfo? = ReminderRuleInfo(cookId: "cook-1", enabled: false, status: "pending")
        var changes: [String] = []
        var notifications = true
        var candidates = ["cook-1"]
        var statuses = ["cook-1": "active"]
        var startedTimer: (String, String, Int64)? = nil
        var knownTimer = true
        var recordingCook: String? = "cook-1"
        var pausedCook: String? = nil
        var cadence: (String, Int64)? = nil
        var dishCook: String? = "cook-1"
        var assigned: (String, String, String?)? = nil
        var prefs: [String: String]? = nil
        var guidance: Bool? = true
        func reminderRule(_ ruleId: String) async -> ReminderRuleInfo? { rule }
        func reminderChange(_ ruleId: String, _ action: String) async throws { changes.append(action) }
        func reminderReconcile(_ cookId: String) async { }
        func notificationsEnabled() async -> Bool { notifications }
        func candidateCookIds() async -> [String] { candidates }
        func cookStatus(_ cookId: String) async -> String? { statuses[cookId] }
        func startTimer(_ cookId: String, _ title: String, _ durationMillis: Int64) async throws -> String {
            startedTimer = (cookId, title, durationMillis); return "timer-9"
        }
        func stopTimer(_ timerId: String) async -> Bool { knownTimer }
        func activeRecordingCookId() async -> String? { recordingCook }
        func pausedRecordingCookId() async -> String? { pausedCook }
        func pauseCollection(_ cookId: String) async throws { recordingCook = nil; pausedCook = cookId }
        func resumeCollection(_ cookId: String) async throws { pausedCook = nil; recordingCook = cookId }
        func setCollectionCadence(_ cookId: String, _ intervalSeconds: Int64) async throws { cadence = (cookId, intervalSeconds) }
        func dishCookId(_ dishId: String) async -> String? { dishCook }
        func assignProbe(_ probeId: String, _ cookId: String, _ dishId: String?) async throws { assigned = (probeId, cookId, dishId) }
        func unassignProbe(_ probeId: String) async -> Bool { true }
        func applyPreferences(_ values: [String: String]) async throws { prefs = values }
        func playbookGuidanceActive(_ playbookId: String, _ mealId: String) async -> Bool? { guidance }
    }

    private final class API: NativeMcpAPI {
        var calls: [(String, String, [String: Any]?)] = []
        var handler: (String, String) -> [String: Any] = { _, _ in [:] }
        func call(_ method: String, _ path: String, body: [String: Any]?, access: String?) async throws -> Data {
            XCTAssertEqual(access, String(repeating: "A", count: 43), "device routes always carry the native session")
            calls.append((method, path, body))
            return try JSONSerialization.data(withJSONObject: handler(method, path))
        }
    }
    private struct AccountAPI: NativeAccountAPI {
        func call(_ path: String, body: [String: String]?, access: String?) async throws -> Data { Data() }
    }
    private final class Memory: NativeAccountStorage {
        var value: NativeAccountState
        init(_ value: NativeAccountState) { self.value = value }
        func load() throws -> NativeAccountState { value }
        func save(_ value: NativeAccountState) throws { self.value = value }
    }
    private final class Identity: McpDeviceIdentityBacking {
        let deviceId: String
        var enabled: Set<String> = []
        init(deviceId: String) { self.deviceId = deviceId }
        func classes() -> Set<String> { enabled }
        func setClass(_ id: String, _ on: Bool) { if on { enabled.insert(id) } else { enabled.remove(id) } }
    }

    private func command(_ action: String, _ params: [String: Any] = [:]) -> McpDeviceCommand {
        McpDeviceCommand(id: cmdId, action: action, params: params)
    }

    func testActivateTogglesAndReportsNotificationsHonestly() async {
        let ports = Ports()
        let dispatcher = DeviceActionDispatcher(ports: ports)
        let receipt = await dispatcher.execute(command("reminder.activate", ["ruleId": "r1"]))
        XCTAssertEqual(receipt.outcome, "applied")
        XCTAssertEqual(ports.changes, ["toggle"])
        ports.rule = ReminderRuleInfo(cookId: "cook-1", enabled: true, status: "pending")
        let again = await dispatcher.execute(command("reminder.activate", ["ruleId": "r1"]))
        XCTAssertEqual(again.outcome, "applied")
        XCTAssertEqual(ports.changes, ["toggle"], "an enabled rule is not toggled off by activate")
        ports.notifications = false
        let attention = await dispatcher.execute(command("reminder.activate", ["ruleId": "r1"]))
        XCTAssertEqual(attention.outcome, "needs_attention")
        XCTAssertTrue(attention.detail?.contains("notifications are off") == true)
    }

    func testMissingRuleAmbiguousTimerAndSnoozeTruth() async {
        let ports = Ports(); ports.rule = nil
        let dispatcher = DeviceActionDispatcher(ports: ports)
        let missing = await dispatcher.execute(command("reminder.cancel", ["ruleId": "nope"]))
        XCTAssertEqual(missing.outcome, "needs_attention")
        ports.candidates = ["a", "b"]
        let ambiguous = await dispatcher.execute(command("timer.start", ["label": "Rest", "durationMinutes": 30]))
        XCTAssertEqual(ambiguous.outcome, "needs_attention")
        ports.candidates = ["cook-1"]
        let started = await dispatcher.execute(command("timer.start", ["label": "Rest", "durationMinutes": 30]))
        XCTAssertEqual(started.outcome, "applied")
        XCTAssertEqual(started.result?["timerId"] as? String, "timer-9")
        XCTAssertEqual(ports.startedTimer?.2, 30 * 60_000)
        ports.rule = ReminderRuleInfo(cookId: "cook-1", enabled: true, status: "pending")
        let snoozed = await dispatcher.execute(command("reminder.snooze", ["ruleId": "r1", "minutes": 45]))
        XCTAssertEqual(snoozed.outcome, "applied")
        XCTAssertEqual(snoozed.result?["snoozedMinutes"] as? Int, 10)
        XCTAssertEqual(snoozed.result?["requestedMinutes"] as? Int, 45)
        ports.statuses["cook-1"] = "completed"
        let finished = await dispatcher.execute(command("timer.start", ["label": "Rest", "durationMinutes": 30, "mealId": "cook-1"]))
        XCTAssertEqual(finished.outcome, "needs_attention")
    }

    func testCollectionGuidancePreferencesAndUnknownAction() async {
        let ports = Ports()
        let dispatcher = DeviceActionDispatcher(ports: ports)
        let cadence = await dispatcher.execute(command("collection.set_cadence", ["intervalSeconds": 120]))
        XCTAssertEqual(cadence.outcome, "applied")
        XCTAssertEqual(ports.cadence?.1, 120)
        _ = await dispatcher.execute(command("collection.pause"))
        let pausedAgain = await dispatcher.execute(command("collection.pause"))
        XCTAssertEqual(pausedAgain.outcome, "needs_attention")
        let resumed = await dispatcher.execute(command("collection.resume"))
        XCTAssertEqual(resumed.outcome, "applied")
        ports.guidance = false
        let guidance = await dispatcher.execute(command("guidance.apply_playbook", ["playbookId": "p1", "mealId": "cook-1"]))
        XCTAssertEqual(guidance.outcome, "needs_attention")
        let prefs = await dispatcher.execute(command("preferences.update", ["themeMode": "dark", "temperatureUnit": "°C"]))
        XCTAssertEqual(prefs.outcome, "applied")
        XCTAssertEqual(ports.prefs, ["themeMode": "dark", "temperatureUnit": "°C"])
        let unknown = await dispatcher.execute(command("grill.ignite"))
        XCTAssertEqual(unknown.outcome, "failed")
    }

    @MainActor func testManagerRegistersClaimsExecutesAndReportsReceipts() async {
        let api = API()
        api.handler = { _, path in
            if path == "/v1/me/mcp/devices" { return ["device": ["deviceId": self.deviceId]] }
            if path.hasSuffix("/claim") {
                return ["commands": [["id": self.cmdId, "action": "timer.start",
                                      "params": ["label": "Rest", "durationMinutes": 15]]]]
            }
            return ["resolved": true]
        }
        let session = NativeAccountSession(accessToken: String(repeating: "A", count: 43), refreshToken: String(repeating: "A", count: 43),
            accessExpiresAt: "2100-01-01T00:00:00.000Z", sessionExpiresAt: "2100-02-01T00:00:00.000Z",
            userId: "12345678-1234-4234-8234-123456789abc")
        let account = NativeAccountCoordinator(enabled: true, vault: Memory(NativeAccountState(session: session)), api: AccountAPI())
        let identity = Identity(deviceId: deviceId)
        identity.enabled = ["timer"]
        let manager = NativeMcpDeviceActions(account: account,
            dispatcher: DeviceActionDispatcher(ports: Ports()), api: api,
            identity: McpDeviceIdentity(backing: identity), deviceName: { "iPhone" })
        await manager.refresh()
        XCTAssertTrue(manager.registered)
        XCTAssertEqual(manager.classes, ["timer"])
        let register = api.calls.first { $0.1 == "/v1/me/mcp/devices" }
        XCTAssertEqual(register?.2?["deviceId"] as? String, deviceId)
        XCTAssertEqual(register?.2?["platform"] as? String, "ios")
        XCTAssertEqual((register?.2?["actionClasses"] as? [String])?.first, "timer")
        let receipt = api.calls.first { $0.1.hasSuffix("/receipt") }
        XCTAssertEqual(receipt?.2?["commandId"] as? String, cmdId)
        XCTAssertEqual(receipt?.2?["outcome"] as? String, "applied")
        XCTAssertEqual((receipt?.2?["result"] as? [String: Any])?["timerId"] as? String, "timer-9")
        XCTAssertEqual(manager.lastCheckSummary, "Handled 1 request.")
    }
}
