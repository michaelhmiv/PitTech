import Foundation
import UserNotifications

/// Cook Alerts operations (issue #183, iOS half): Android's
/// CookAlertRepository over the shared companion payload — save/change,
/// the E1 missing-readings default, and the reconcile that evaluates
/// every rule, persists its state, and drives notifications.
@MainActor extension CookStore {
    struct CookAlertEntry {
        var record: [String: JSONValue]
        var rule: CookAlertRule
        var state: CookAlertState
        var id: String { record["id"]?.stringValue ?? "" }
        var title: String { record["title"]?.stringValue ?? "" }
        var cookId: String { record["cookId"]?.stringValue ?? "" }
    }

    func cookAlerts(cookId: String) -> [CookAlertEntry] {
        library.companionRecords.compactMap { row in
            guard let record = row.objectValue, record["kind"]?.stringValue == "alert",
                  record["cookId"]?.stringValue == cookId,
                  let payload = record["payload"]?.stringValue,
                  let (rule, state) = try? CookAlertEngine.decode(payload) else { return nil }
            return CookAlertEntry(record: record, rule: rule, state: state)
        }
    }

    func alertEntry(_ id: String) -> CookAlertEntry? {
        guard let record = library.companionRecords.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue,
              record["kind"]?.stringValue == "alert",
              let payload = record["payload"]?.stringValue,
              let (rule, state) = try? CookAlertEngine.decode(payload) else { return nil }
        return CookAlertEntry(record: record, rule: rule, state: state)
    }

    private func putAlertRecord(_ record: [String: JSONValue]) {
        guard let id = record["id"]?.stringValue else { return }
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == id }
        library.companionRecords.append(.object(record))
    }

    func saveCookAlert(cookId: String, title: String, rule: CookAlertRule) throws {
        guard library.cooks.first(where: { $0.id == cookId })?.status != "completed" else {
            throw CookAlertError.invalid("This cook is finished.")
        }
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CookAlertError.invalid("Give the alert a name.")
        }
        guard rule.dishId == nil || dishRecords(cookId).contains(where: { $0.id == rule.dishId }) else {
            throw CookAlertError.invalid("Choose a dish in this cook.")
        }
        let recordId = "alert:" + rule.id
        let now = Date.now.timeIntervalSince1970 * 1000
        let existing = library.companionRecords.first { $0.objectValue?["id"]?.stringValue == recordId }?.objectValue
        // Android's save encodes the rule with a fresh state — editing a
        // rule restarts its watching from a clean slate.
        let record: [String: JSONValue] = [
            "id": .string(recordId), "kind": .string("alert"), "cookId": .string(cookId),
            "title": .string(title.trimmingCharacters(in: .whitespacesAndNewlines)),
            "payload": .string(try CookAlertEngine.encode(rule)),
            "createdAtUtcMillis": .number(existing?["createdAtUtcMillis"]?.numberValue ?? now),
            "updatedAtUtcMillis": .number(now),
        ]
        putAlertRecord(record)
        save()
    }

    /// Android's change actions: acknowledge, snooze, lid (pit
    /// suppression), toggle. Anything else leaves the record alone.
    func changeCookAlert(id: String, action: String) {
        guard var entry = alertEntry(id) else { return }
        var state = entry.state
        var rule = entry.rule
        switch action {
        case "acknowledge": state.acknowledged = true
        case "snooze": state.snoozedUntil = Int64(Date.now.timeIntervalSince1970 * 1000) + 600_000; state.acknowledged = false; state.notifiedCount = 0
        case "lid": state.suppressedUntil = Int64(Date.now.timeIntervalSince1970 * 1000) + 600_000; state.badSince = nil
        case "toggle": rule.enabled = !rule.enabled
        default: return
        }
        entry.record["payload"] = (try? CookAlertEngine.encode(rule, state)).map(JSONValue.string)
        entry.record["updatedAtUtcMillis"] = .number(Date.now.timeIntervalSince1970 * 1000)
        putAlertRecord(entry.record)
        save()
    }

    /// Flowing E1: missing-readings alerts are DEFAULT-ON for any
    /// attached source. Provisioned only when a source is actually
    /// attached and no missing rule exists for the cook; an existing
    /// rule (including a disabled one) is respected, never duplicated.
    /// iOS attachment evidence: a live recording row, or telemetry
    /// readings already flowing for the cook (iOS has no recording
    /// service of its own; readings are the attachment).
    func ensureMissingReadingsDefault(cookId: String) {
        guard library.cooks.first(where: { $0.id == cookId })?.status != "completed" else { return }
        if cookAlerts(cookId: cookId).contains(where: { $0.rule.type == "missing" }) { return }
        let recording = alertRecording(cookId: cookId)
        let attached = (recording != nil && recording?["status"]?.stringValue != "stopped")
            || library.readings.contains { $0.objectValue?["cookId"]?.stringValue == cookId && $0.objectValue?["source"]?.stringValue != "manual" }
        guard attached else { return }
        try? saveCookAlert(cookId: cookId, title: "Missing readings", rule: CookAlertRule(type: "missing"))
    }

    private func alertRecording(cookId: String) -> [String: JSONValue]? {
        library.recordings.first { $0.objectValue?["cookId"]?.stringValue == cookId }?.objectValue
    }

    // MARK: - Reconcile (inside save(), like reminders)

    struct CookAlertReconciliation {
        var posts: [(id: String, title: String, body: String, cookId: String, alertId: String)] = []
        var schedules: [(id: String, title: String, body: String, cookId: String, alertId: String, at: Int64)] = []
        var cancellations: [String] = []
        var deliveredCancellations: [String] = []
    }

    func prepareCookAlertSchedules(now: Double = Date.now.timeIntervalSince1970 * 1000) -> CookAlertReconciliation {
        var result = CookAlertReconciliation()
        let nowMillis = Int64(now)
        // E1 provisioning first, so a fresh default evaluates in the
        // same pass, as Android's reconcile does.
        for cook in library.cooks where cook.status != "completed" {
            ensureMissingReadingsDefaultInPlace(cookId: cook.id)
        }
        let alertRecords = library.companionRecords.compactMap { $0.objectValue }.filter { $0["kind"]?.stringValue == "alert" }
        for record in alertRecords {
            guard let recordId = record["id"]?.stringValue, let cookId = record["cookId"]?.stringValue,
                  let payload = record["payload"]?.stringValue,
                  let (rule, state) = try? CookAlertEngine.decode(payload) else { continue }
            let identifier = "cook-alert:" + recordId
            let cook = library.cooks.first { $0.id == cookId }
            let recording = alertRecording(cookId: cookId)
            let events = guidanceEvents(cookId: cookId)
            let rows = library.readings.compactMap { $0.objectValue }.filter { $0["cookId"]?.stringValue == cookId }
            let telemetryDeviceIds = Set(rows.filter { $0["source"]?.stringValue != "manual" }.compactMap { $0["sourceDeviceId"]?.stringValue })
            let telemetryReceipt = rows.filter { telemetryDeviceIds.contains($0["sourceDeviceId"]?.stringValue ?? "") }.compactMap { $0["recordedAtUtcMillis"]?.numberValue }.max().map(Int64.init)
            let lastReceipt = [recording?["lastReceivedAtUtcMillis"]?.numberValue, recording?["resumedAtUtcMillis"]?.numberValue, telemetryReceipt.map(Double.init)].compactMap { $0 }.max().map(Int64.init)
            let cloudRunning = recording?["status"]?.stringValue == "recording" && recording?["samplingMode"]?.stringValue != "on_log"
            let hasRecording = recording != nil && recording?["status"]?.stringValue != "stopped"
            let anySourceRunning = cloudRunning || !telemetryDeviceIds.isEmpty
            // The sample: the newest reading this rule watches.
            let reading = rows.filter { r in
                (rule.dishId == nil || r["dishId"]?.stringValue == rule.dishId)
                    && (rule.probeName.isEmpty || r["probeName"]?.stringValue == rule.probeName)
                    && (rule.type == "target" ? ["food", "food_probe"].contains(r["measurementType"]?.stringValue ?? "")
                        : rule.type == "pit_range" ? ["pit", "pit_ambient"].contains(r["measurementType"]?.stringValue ?? "")
                        : true)
                    && (rule.type != "target" || (r["measuredAtUtcMillis"]?.numberValue ?? 0) >= Double(events.filter { $0.action == "food_on" && ($0.dishId == nil || $0.dishId == r["dishId"]?.stringValue) }.map(\.at).max() ?? Int64.max))
                    && (r["source"]?.stringValue != "controller_cloud" || (cloudRunning && r["sourceDeviceId"]?.stringValue == recording?["deviceId"]?.stringValue && (r["measuredAtUtcMillis"]?.numberValue ?? 0) >= (recording?["resumedAtUtcMillis"]?.numberValue ?? 0)))
            }.max { ($0["measuredAtUtcMillis"]?.numberValue ?? 0) < ($1["measuredAtUtcMillis"]?.numberValue ?? 0) }
            let sample = reading.map { r -> AlertSample in
                let isCloud = r["source"]?.stringValue == "controller_cloud"
                let interval = Int64(r["samplingIntervalMillis"]?.numberValue ?? 0)
                return AlertSample(
                    fahrenheit: CookPlanEngine.fahrenheit(r["value"]?.numberValue ?? 0, r["unit"]?.stringValue ?? "°F"),
                    at: Int64(r["measuredAtUtcMillis"]?.numberValue ?? 0),
                    receiptAt: Int64(r["recordedAtUtcMillis"]?.numberValue ?? 0),
                    valid: r["qualityStatus"]?.stringValue == "valid" && (!isCloud || recording?["gapStartedAtUtcMillis"] == nil || recording?["gapStartedAtUtcMillis"] == .null),
                    freshnessMillis: isCloud ? (interval == 0 ? 15_000 : max(45_000, interval + 30_000)) : 330_000,
                    ageKnown: r["timestampBasis"]?.stringValue != "cloud_receipt")
            }
            let stageDish = rule.dishId ?? reading?["dishId"]?.stringValue
            let stage: String
            if let stageDish {
                stage = CookPlanEngine.stage(events: events, dishId: stageDish)
            } else {
                let stages = dishRecords(cookId).map { CookPlanEngine.stage(events: events, dishId: $0.id) }
                stage = Set(stages).count == 1 ? (stages.first ?? "cooking") : "mixed"
            }
            let monitor = library.companionRecords.first { $0.objectValue?["id"]?.stringValue == "monitor-status:" + cookId }?.objectValue
            let monitorPayload = monitor?["payload"]?.stringValue.flatMap { try? JSONDecoder().decode(JSONValue.self, from: Data($0.utf8)) }?.objectValue
            let offline = monitorPayload.flatMap { m -> Bool? in
                guard let at = m["at"]?.numberValue, nowMillis - Int64(at) >= 0, nowMillis - Int64(at) <= 330_000 else { return nil }
                return (m["online"]?.numberValue ?? 0) != 0
            }
            let running = (cook?.status != "completed") && (["missing", "offline"].contains(rule.type) ? anySourceRunning : (reading?["source"]?.stringValue == "manual" || !hasRecording || cloudRunning))
            let evaluation = CookAlertEngine.evaluate(rule: rule, previous: state, sample: sample, lastReceipt: lastReceipt, reportedOffline: offline, stage: stage, now: nowMillis, running: running)
            let nextState = evaluation.notify
                ? CookAlertState(active: evaluation.state.active, badSince: evaluation.state.badSince, lastSampleAt: evaluation.state.lastSampleAt, lastNotifiedAt: nowMillis, snoozedUntil: evaluation.state.snoozedUntil, suppressedUntil: evaluation.state.suppressedUntil, acknowledged: evaluation.state.acknowledged, notifiedCount: state.notifiedCount + 1)
                : evaluation.state
            if let encoded = try? CookAlertEngine.encode(rule, nextState), encoded != payload {
                var updated = record
                updated["payload"] = .string(encoded)
                updated["updatedAtUtcMillis"] = .number(now)
                putAlertRecord(updated)
            }
            if evaluation.notify {
                result.posts.append((id: identifier, title: record["title"]?.stringValue ?? "Cook alert", body: evaluation.detail, cookId: cookId, alertId: recordId))
            }
            if !evaluation.state.active || !rule.enabled || cook?.status == "completed" {
                result.deliveredCancellations.append(identifier)
            }
            let wake = evaluation.notify ? nowMillis + rule.repeatMillis : evaluation.nextWake
            if let wake, wake > nowMillis {
                let body = rule.type == "missing"
                    ? "No accepted reading for \((wake - (lastReceipt ?? wake)) / 60_000) min"
                    : evaluation.detail
                result.schedules.append((id: identifier, title: record["title"]?.stringValue ?? "Cook alert", body: body, cookId: cookId, alertId: recordId, at: wake))
            } else if !evaluation.notify {
                result.cancellations.append(identifier)
            }
        }
        return result
    }

    /// The in-place half of E1 provisioning used inside the reconcile
    /// (saveCookAlert's save() would recurse into this prepare).
    private func ensureMissingReadingsDefaultInPlace(cookId: String) {
        guard library.cooks.first(where: { $0.id == cookId })?.status != "completed" else { return }
        if cookAlerts(cookId: cookId).contains(where: { $0.rule.type == "missing" }) { return }
        let recording = alertRecording(cookId: cookId)
        let attached = (recording != nil && recording?["status"]?.stringValue != "stopped")
            || library.readings.contains { $0.objectValue?["cookId"]?.stringValue == cookId && $0.objectValue?["source"]?.stringValue != "manual" }
        guard attached else { return }
        let rule = CookAlertRule(type: "missing")
        let now = Date.now.timeIntervalSince1970 * 1000
        guard let payload = try? CookAlertEngine.encode(rule) else { return }
        putAlertRecord([
            "id": .string("alert:" + rule.id), "kind": .string("alert"), "cookId": .string(cookId),
            "title": .string("Missing readings"), "payload": .string(payload),
            "createdAtUtcMillis": .number(now), "updatedAtUtcMillis": .number(now),
        ])
    }

    func submitCookAlertSchedules(_ reconciliation: CookAlertReconciliation) {
        guard !reconciliation.posts.isEmpty || !reconciliation.schedules.isEmpty || !reconciliation.cancellations.isEmpty || !reconciliation.deliveredCancellations.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: reconciliation.cancellations)
        center.removeDeliveredNotifications(withIdentifiers: reconciliation.deliveredCancellations)
        for post in reconciliation.posts {
            let content = UNMutableNotificationContent()
            content.title = post.title
            content.body = post.body
            content.sound = .default
            content.categoryIdentifier = "pittech-cook-alert"
            content.userInfo = ["cookId": post.cookId, "alertId": post.alertId, "kind": "alert"]
            center.add(UNNotificationRequest(identifier: post.id, content: content, trigger: nil))
        }
        for schedule in reconciliation.schedules {
            let content = UNMutableNotificationContent()
            content.title = schedule.title
            content.body = schedule.body
            content.sound = .default
            content.categoryIdentifier = "pittech-cook-alert"
            content.userInfo = ["cookId": schedule.cookId, "alertId": schedule.alertId, "kind": "alert"]
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, Double(schedule.at - Int64(Date.now.timeIntervalSince1970 * 1000)) / 1000), repeats: false)
            center.add(UNNotificationRequest(identifier: schedule.id, content: content, trigger: trigger))
        }
    }

    /// A notification's Acknowledge / Snooze, applied against current
    /// state, then re-evaluated by the save it triggers.
    func applyAlertNotificationAction(alertId: String, action: String) {
        changeCookAlert(id: alertId, action: action)
    }

    func alertStripSummary(cookId: String) -> String? {
        CookAlertEngine.stripSummary(alerts: cookAlerts(cookId: cookId).map { (title: $0.title, state: $0.state) })
    }
}
