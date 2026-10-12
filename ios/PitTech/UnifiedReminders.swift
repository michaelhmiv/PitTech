import Foundation
import UserNotifications

struct NativeReminderSchedule { let id: String; let occurrenceId: String; let cookId: String; let title: String; let due: Double; let historical: Bool }
struct NativeReminderReconciliation { var schedules: [NativeReminderSchedule] = []; var cancellations: [String] = [] }
enum NativeReminderEngine {
    static func configuration(_ raw: String) throws -> [String: JSONValue] {
        guard let o = try JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8)).objectValue, o["version"]?.numberValue == 1 else { throw CookNoteError.invalid }
        let type = o["type"]?.stringValue ?? ""
        func number(_ key: String, range: ClosedRange<Double>) -> Bool { o[key]?.numberValue.map { $0.isFinite && range.contains($0) && $0.rounded() == $0 } == true }
        switch type {
        case "duration": guard number("startedAt", range: 1...Double(Int64.max)), number("delayMillis", range: 1...2_592_000_000) else { throw CookNoteError.invalid }
        case "clock": guard number("at", range: 1...Double(Int64.max)) else { throw CookNoteError.invalid }
        case "action": guard CookNoteMetadata.actions.contains(o["action"]?.stringValue ?? ""), number("offsetMillis", range: -2_592_000_000...2_592_000_000) else { throw CookNoteError.invalid }
        case "historical": guard ["food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done"].contains(o["anchorAction"]?.stringValue ?? ""), number("offsetMillis", range: -2_592_000_000...2_592_000_000) else { throw CookNoteError.invalid }
        case "probe":
            guard o["seriesId"]?.stringValue?.isEmpty == false, ["food", "food_probe", "internal_temperature", "pit", "pit_ambient", "ambient_temperature"].contains(o["measurementType"]?.stringValue ?? ""), ["above", "below"].contains(o["direction"]?.stringValue ?? ""), o["thresholdF"]?.numberValue?.isFinite == true, (-100...1000).contains(o["thresholdF"]!.numberValue!), number("sustainMillis", range: 0...3_600_000), o["hysteresisF"]?.numberValue.map({ (0.5...50).contains($0) }) == true else { throw CookNoteError.invalid }
            if let interval = o["repeatMillis"]?.numberValue, !interval.isFinite || interval < 300_000 { throw CookNoteError.invalid }
        default: throw CookNoteError.invalid
        }
        return o
    }
    static func due(_ config: [String: JSONValue], dishId: String?, actions: [CookAction]) -> Double? {
        switch config["type"]?.stringValue {
        case "duration": return (config["startedAt"]?.numberValue ?? 0) + (config["delayMillis"]?.numberValue ?? 0)
        case "clock": return config["at"]?.numberValue
        case "action", "historical":
            let action = config["action"]?.stringValue ?? config["anchorAction"]?.stringValue
            let eventId = config["eventId"]?.stringValue
            return actions.filter { $0.action == action && ($0.dishIds.isEmpty || dishId.map($0.dishIds.contains) == true) && (eventId == nil || $0.id == eventId) }.map(\.at).min().map { Double(Int64($0)) + (config["offsetMillis"]?.numberValue ?? 0) }
        default: return nil
        }
    }
}
extension PitTechLibrary {
    func validateReminderRules() throws {
        var ids = Set<String>(), occurrenceIds = Set<String>(), keys = Set<String>()
        for row in reminderRules {
            guard let o = row.objectValue, let id = o["id"]?.stringValue, ids.insert(id).inserted, o["schemaVersion"]?.numberValue == 1,
                  let cookId = o["cookId"]?.stringValue, cooks.contains(where: { $0.id == cookId }), o["title"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false,
                  ["pending", "scheduled", "waiting", "due", "inactive", "completed", "cancelled"].contains(o["status"]?.stringValue ?? ""), o["enabled"]?.boolValue != nil,
                  let created = o["createdAtUtcMillis"]?.numberValue, created > 0, let updated = o["updatedAtUtcMillis"]?.numberValue, updated >= created,
                  let raw = o["triggerConfig"]?.stringValue else { throw PitTechArchiveError.invalidArchive }
            let config = try NativeReminderEngine.configuration(raw)
            guard config["type"]?.stringValue == o["kind"]?.stringValue else { throw PitTechArchiveError.invalidArchive }
            if let dish = o["dishId"]?.stringValue, !dishes.contains(where: { $0.objectValue?["id"]?.stringValue == dish && $0.objectValue?["cookId"]?.stringValue == cookId }) { throw PitTechArchiveError.invalidArchive }
        }
        for row in reminderOccurrences {
            guard let o = row.objectValue, let id = o["id"]?.stringValue, occurrenceIds.insert(id).inserted, let ruleId = o["ruleId"]?.stringValue, ids.contains(ruleId),
                  let sequence = o["sequence"]?.numberValue, sequence >= 0, sequence.rounded() == sequence, keys.insert("\(ruleId):\(sequence)").inserted, o["active"]?.boolValue != nil,
                  ["pending", "scheduled", "waiting", "due", "inactive", "completed", "cancelled", "queued", "delivered", "snoozed", "skipped", "acknowledged"].contains(o["status"]?.stringValue ?? "") else { throw PitTechArchiveError.invalidArchive }
            for key in ["dueAtUtcMillis", "deliveredAtUtcMillis", "acknowledgedAtUtcMillis", "snoozedUntilUtcMillis", "badSinceUtcMillis", "lastSampleAtUtcMillis"] {
                if let value = o[key]?.numberValue, !value.isFinite || value <= 0 || value.rounded() != value { throw PitTechArchiveError.invalidArchive }
            }
            if let eventId = o["actionEventId"]?.stringValue {
                let cookId = reminderRules.first { $0.objectValue?["id"]?.stringValue == ruleId }?.objectValue?["cookId"]?.stringValue
                guard events.contains(where: { $0.objectValue?["id"]?.stringValue == eventId && $0.objectValue?["cookId"]?.stringValue == cookId }) else { throw PitTechArchiveError.invalidArchive }
            }
        }
    }
}
@MainActor extension CookStore {
    func reminderRule(_ id: String) -> [String: JSONValue]? { library.reminderRules.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue }
    func reminderState(_ id: String) -> [String: JSONValue]? { library.reminderOccurrences.first { $0.objectValue?["ruleId"]?.stringValue == id && $0.objectValue?["sequence"]?.numberValue == 0 }?.objectValue }
    private func putOccurrence(_ value: [String: JSONValue]) {
        library.reminderOccurrences.removeAll { $0.objectValue?["id"] == value["id"] }; library.reminderOccurrences.append(.object(value))
    }
    func saveReminderRule(cookId: String, dishId: String?, title: String, config: [String: JSONValue], id: String = UUID().uuidString.lowercased(), revisionId: String? = nil, milestoneId: String? = nil) throws {
        guard library.cooks.contains(where: { $0.id == cookId && $0.isActive }), !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw CookNoteError.invalid }
        let raw = try recipeJSON(config); _ = try NativeReminderEngine.configuration(raw)
        guard config["type"]?.stringValue != "probe" else { throw RecipeError.unavailable }
        let prior = library, previous = reminderRule(id), now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        if let previous, previous["cookId"]?.stringValue != cookId { throw CookNoteError.scope }
        library.reminderRules.removeAll { $0.objectValue?["id"]?.stringValue == id }
        library.reminderRules.append(.object(["id": .string(id), "cookId": .string(cookId), "dishId": dishId.map(JSONValue.string) ?? .null, "title": .string(title), "kind": config["type"]!, "triggerConfig": .string(raw), "sourceRecipeRevisionId": revisionId.map(JSONValue.string) ?? previous?["sourceRecipeRevisionId"] ?? .null, "sourceMilestoneId": milestoneId.map(JSONValue.string) ?? previous?["sourceMilestoneId"] ?? .null, "enabled": .bool(true), "status": .string("pending"), "schemaVersion": .number(1), "createdAtUtcMillis": previous?["createdAtUtcMillis"] ?? .number(now), "updatedAtUtcMillis": .number(now)]))
        if previous != nil { library.reminderOccurrences = library.reminderOccurrences.map { row in
            guard var o = row.objectValue, o["ruleId"]?.stringValue == id, (o["sequence"]?.numberValue ?? 0) > 0, ["queued", "scheduled", "delivered"].contains(o["status"]?.stringValue ?? "") else { return row }; o["status"] = .string("cancelled"); return .object(o)
        } }
        putOccurrence(["id": .string("state:" + id), "ruleId": .string(id), "sequence": .number(0), "status": .string("pending"), "active": .bool(false)])
        do { try library.validateReminderRules(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) } } catch { library = prior; throw error }
    }
    func changeReminder(_ id: String, action: String) throws {
        guard let index = library.reminderRules.firstIndex(where: { $0.objectValue?["id"]?.stringValue == id }), var rule = library.reminderRules[index].objectValue else { throw RecipeError.unavailable }
        let prior = library, now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        var state = reminderState(id) ?? ["id": .string("state:" + id), "ruleId": .string(id), "sequence": .number(0), "active": .bool(false)]
        switch action {
        case "snooze": state["snoozedUntilUtcMillis"] = .number(now + 600_000); state["deliveredAtUtcMillis"] = .null; state["status"] = .string("pending"); rule["enabled"] = .bool(true); rule["status"] = .string("pending")
        case "complete", "skip": rule["enabled"] = .bool(false); rule["status"] = .string("completed"); state["status"] = .string("completed"); state["acknowledgedAtUtcMillis"] = .number(now)
        case "toggle":
            guard rule["kind"]?.stringValue != "probe" else { throw RecipeError.unavailable }
            let enabled = !(rule["enabled"]?.boolValue ?? false); rule["enabled"] = .bool(enabled); rule["status"] = .string(enabled ? "pending" : "inactive")
            state["status"] = .string(enabled ? "pending" : "inactive"); state["deliveredAtUtcMillis"] = .null; state["snoozedUntilUtcMillis"] = .null; state["acknowledgedAtUtcMillis"] = .null
        case "remove":
            let ids = library.reminderOccurrences.compactMap(\.objectValue).filter { $0["ruleId"]?.stringValue == id && ($0["sequence"]?.numberValue ?? 0) > 0 }.compactMap { $0["id"]?.stringValue }.map { "meal-reminder:" + $0 }
            library.reminderRules.remove(at: index); library.reminderOccurrences.removeAll { $0.objectValue?["ruleId"]?.stringValue == id }; save()
            if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
            submitReminderSchedules(NativeReminderReconciliation(cancellations: ids))
            return
        default: throw CookNoteError.invalid
        }
        rule["updatedAtUtcMillis"] = .number(now); library.reminderRules[index] = .object(rule); putOccurrence(state)
        if action == "snooze" || action == "complete" || action == "skip" {
            if let last = library.reminderOccurrences.enumerated().filter({ $0.element.objectValue?["ruleId"]?.stringValue == id && ($0.element.objectValue?["sequence"]?.numberValue ?? 0) > 0 }).max(by: { ($0.element.objectValue?["sequence"]?.numberValue ?? 0) < ($1.element.objectValue?["sequence"]?.numberValue ?? 0) }), var receipt = last.element.objectValue {
                receipt["status"] = .string(action == "snooze" ? "snoozed" : action == "skip" ? "skipped" : "acknowledged"); receipt["acknowledgedAtUtcMillis"] = action == "snooze" ? .null : .number(now); library.reminderOccurrences[last.offset] = .object(receipt)
            }
        }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
    func confirmReminderAction(_ id: String, action: String) throws -> String {
        guard let rule = reminderRule(id), let cookId = rule["cookId"]?.stringValue, ["food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done"].contains(action) else { throw CookNoteError.invalid }
        let receipt = library.reminderOccurrences.compactMap(\.objectValue).filter { $0["ruleId"]?.stringValue == id }.max { ($0["sequence"]?.numberValue ?? 0) < ($1["sequence"]?.numberValue ?? 0) }
        let eventId = receipt?["actionEventId"]?.stringValue ?? "reminder-action-\(id)-\(Int(receipt?["sequence"]?.numberValue ?? 0))"
        if !library.events.contains(where: { $0.objectValue?["id"]?.stringValue == eventId }) { try saveNote(CookNoteDraft(id: eventId, cookId: cookId, dishId: rule["dishId"]?.stringValue, metadata: CookNoteMetadata(category: "action", action: action), title: Self.actionLabel(action))) }
        if var receipt { receipt["actionEventId"] = .string(eventId); putOccurrence(receipt) }
        try changeReminder(id, action: "complete"); return eventId
    }
    func prepareReminderSchedules(now: Double = Double(Int64(Date.now.timeIntervalSince1970 * 1000))) -> NativeReminderReconciliation {
        var result = NativeReminderReconciliation()
        for index in library.reminderRules.indices {
            guard var rule = library.reminderRules[index].objectValue, let id = rule["id"]?.stringValue, let cookId = rule["cookId"]?.stringValue, let raw = rule["triggerConfig"]?.stringValue, let config = try? NativeReminderEngine.configuration(raw) else { continue }
            let rows = library.reminderOccurrences.compactMap(\.objectValue).filter { $0["ruleId"]?.stringValue == id && ($0["sequence"]?.numberValue ?? 0) > 0 }
            let allIdentifiers = rows.compactMap { $0["id"]?.stringValue }.map { "meal-reminder:" + $0 }
            guard rule["enabled"]?.boolValue == true, library.cooks.contains(where: { $0.id == cookId && $0.isActive }) else {
                result.cancellations += allIdentifiers
                if rule["enabled"]?.boolValue == true { rule["enabled"] = .bool(false); rule["status"] = .string("cancelled"); library.reminderRules[index] = .object(rule) }
                continue
            }
            guard config["type"]?.stringValue != "probe" else { result.cancellations += allIdentifiers; rule["status"] = .string("waiting"); library.reminderRules[index] = .object(rule); continue }
            var state = reminderState(id) ?? ["id": .string("state:" + id), "ruleId": .string(id), "sequence": .number(0), "status": .string("pending"), "active": .bool(false)]
            if state["deliveredAtUtcMillis"]?.numberValue != nil && state["snoozedUntilUtcMillis"]?.numberValue == nil { rule["status"] = .string("due"); library.reminderRules[index] = .object(rule); continue }
            guard let due = state["snoozedUntilUtcMillis"]?.numberValue ?? NativeReminderEngine.due(config, dishId: rule["dishId"]?.stringValue, actions: library.actions(for: cookId)) else {
                rule["status"] = .string("waiting"); state["status"] = .string("waiting"); state["dueAtUtcMillis"] = .null; putOccurrence(state); library.reminderRules[index] = .object(rule); result.cancellations += allIdentifiers; continue
            }
            var occurrence = rows.filter { $0["status"]?.stringValue == "scheduled" }.max { ($0["sequence"]?.numberValue ?? 0) < ($1["sequence"]?.numberValue ?? 0) }
            // An elapsed persisted schedule is left to the OS; relaunch does not deliver it again.
            if due <= now && occurrence != nil && state["status"]?.stringValue == "scheduled" && state["dueAtUtcMillis"]?.numberValue == due { rule["status"] = .string("due"); library.reminderRules[index] = .object(rule); continue }
            if occurrence == nil {
                let sequence = (rows.compactMap { $0["sequence"]?.numberValue }.max() ?? 0) + 1
                occurrence = ["id": .string("\(id):\(Int(sequence))"), "ruleId": .string(id), "sequence": .number(sequence), "status": .string("scheduled"), "active": .bool(false)]
            }
            occurrence!["dueAtUtcMillis"] = .number(due); putOccurrence(occurrence!)
            state["dueAtUtcMillis"] = .number(due); state["status"] = .string("scheduled"); putOccurrence(state)
            rule["status"] = .string("scheduled"); library.reminderRules[index] = .object(rule)
            let occurrenceId = occurrence!["id"]!.stringValue!
            result.schedules.append(.init(id: "meal-reminder:" + occurrenceId, occurrenceId: occurrenceId, cookId: cookId, title: rule["title"]?.stringValue ?? "Check the food", due: due, historical: rule["kind"]?.stringValue == "historical"))
            result.cancellations += allIdentifiers.filter { $0 != "meal-reminder:" + occurrenceId }
        }
        return result
    }
    func submitReminderSchedules(_ reconciliation: NativeReminderReconciliation) {
        let previousTask = reminderScheduleTask
        reminderScheduleTask = Task {
            await previousTask?.value
            let center = UNUserNotificationCenter.current()
            center.removePendingNotificationRequests(withIdentifiers: reconciliation.cancellations)
            center.removeDeliveredNotifications(withIdentifiers: reconciliation.cancellations)
            for schedule in reconciliation.schedules {
                guard library.reminderRules.contains(where: { $0.objectValue?["cookId"]?.stringValue == schedule.cookId && $0.objectValue?["enabled"]?.boolValue == true && library.reminderOccurrences.contains(where: { $0.objectValue?["id"]?.stringValue == schedule.occurrenceId && $0.objectValue?["dueAtUtcMillis"]?.numberValue == schedule.due }) }) else { continue }
                let content = UNMutableNotificationContent(); content.title = schedule.title
                content.body = schedule.historical ? "Last time this milestone happened around this point. Check your food." : "Check your food. Dismissing this reminder does not record an action."
                content.sound = .default; content.userInfo = ["cookId": schedule.cookId, "occurrenceId": schedule.occurrenceId]
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, (schedule.due - Date.now.timeIntervalSince1970 * 1000) / 1000), repeats: false)
                do { try await center.add(UNNotificationRequest(identifier: schedule.id, content: content, trigger: trigger)) } catch { reminderMessage = "Reminder saved. Notifications are unavailable; enable them in Settings." }
            }
        }
    }
    func recordReminderDelivery(_ id: String, at: Double) {
        guard let index = library.reminderOccurrences.firstIndex(where: { $0.objectValue?["id"]?.stringValue == id }), var row = library.reminderOccurrences[index].objectValue, ["scheduled", "queued"].contains(row["status"]?.stringValue ?? ""), let ruleId = row["ruleId"]?.stringValue, var state = reminderState(ruleId) else { return }
        let timestamp = Double(Int64(at)); row["status"] = .string("delivered"); row["deliveredAtUtcMillis"] = .number(timestamp); library.reminderOccurrences[index] = .object(row)
        state["deliveredAtUtcMillis"] = .number(timestamp); state["snoozedUntilUtcMillis"] = .null; putOccurrence(state); save()
    }
    func refreshReminderDeliveryHistory() async {
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
        var changed = false
        for notification in delivered {
            guard let id = notification.request.content.userInfo["occurrenceId"] as? String, let index = library.reminderOccurrences.firstIndex(where: { $0.objectValue?["id"]?.stringValue == id }), var row = library.reminderOccurrences[index].objectValue, row["status"]?.stringValue == "scheduled", let ruleId = row["ruleId"]?.stringValue, var state = reminderState(ruleId) else { continue }
            let at = Double(Int64(notification.date.timeIntervalSince1970 * 1000)); row["status"] = .string("delivered"); row["deliveredAtUtcMillis"] = .number(at); library.reminderOccurrences[index] = .object(row)
            state["deliveredAtUtcMillis"] = .number(at); state["snoozedUntilUtcMillis"] = .null; putOccurrence(state); changed = true
        }
        if changed { save() }
    }
}
