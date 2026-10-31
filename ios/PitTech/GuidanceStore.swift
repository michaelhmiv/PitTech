import Foundation
import UserNotifications

/// Cook guidance operations (issue #183, iOS half): Android's
/// CookCompanionRepository plan operations + CookPlanEngine driving,
/// over the shared companion payload. Until this file, iOS could not
/// read a plan at all — plans arrived by sync and sat inert.
@MainActor extension CookStore {
    private func planCompanionRecord(_ id: String) -> [String: JSONValue]? {
        library.companionRecords.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue
    }

    private func putPlanCompanion(_ record: [String: JSONValue]) {
        guard let id = record["id"]?.stringValue else { return }
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == id }
        library.companionRecords.append(.object(record))
    }

    private func writePlan(cookId: String, plan: CookPlan) throws {
        guard var record = planCompanionRecord("plan:" + cookId) else { return }
        record["payload"] = .string(try CookPlanCodec.encode(plan))
        record["updatedAtUtcMillis"] = .number(Date.now.timeIntervalSince1970 * 1000)
        putPlanCompanion(record)
    }

    func guidancePlan(cookId: String) -> CookPlan? {
        guard let record = planCompanionRecord("plan:" + cookId), record["kind"]?.stringValue == "plan",
              let payload = record["payload"]?.stringValue else { return nil }
        return try? CookPlanCodec.decode(payload)
    }

    func guidanceEvents(cookId: String) -> [GuidanceEvent] {
        library.events.compactMap { row in
            guard let o = row.objectValue, o["cookId"]?.stringValue == cookId,
                  let id = o["id"]?.stringValue, let at = o["occurredAtUtcMillis"]?.numberValue else { return nil }
            let metadata = o["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) }
            let raw = o["eventType"]?.stringValue ?? "note"
            let action = metadata?.action ?? ["meat_on": "food_on", "rest": "rest_start"][raw] ?? raw
            let scopes = metadata?.dishIds.isEmpty == false ? metadata!.dishIds : o["dishId"]?.stringValue.map { [$0] } ?? []
            return GuidanceEvent(id: id, action: action, dishId: o["dishId"]?.stringValue, dishIds: scopes, at: Int64(at))
        }
    }

    func guidanceReadings(cookId: String) -> [GuidanceReading] {
        readings(for: cookId).map {
            GuidanceReading(dishId: $0.dishId, fahrenheit: CookPlanEngine.fahrenheit($0.value, $0.unit), at: Int64($0.measuredAt.timeIntervalSince1970 * 1000), valid: $0.qualityStatus == "valid", type: $0.measurementType, probeName: $0.probeName)
        }
    }

    func evaluatedGuidance(cookId: String, now: Int64 = Int64(Date.now.timeIntervalSince1970 * 1000)) -> [EvaluatedStep] {
        guard let plan = guidancePlan(cookId: cookId) else { return [] }
        return CookPlanEngine.evaluate(plan: plan, events: guidanceEvents(cookId: cookId), readings: guidanceReadings(cookId: cookId), now: now)
    }

    /// The next step the card leads with: the first ready one, else the
    /// soonest pending one that is not waiting on a stage or anchor —
    /// Android's CookGuidanceCard selection, verbatim.
    func nextGuidanceStep(cookId: String, now: Int64 = Int64(Date.now.timeIntervalSince1970 * 1000)) -> EvaluatedStep? {
        let evaluated = evaluatedGuidance(cookId: cookId, now: now)
        return evaluated.first { $0.ready }
            ?? evaluated.filter { $0.progress.status == "pending" && $0.waitingFor == nil }.min { ($0.dueAt ?? Int64.max) < ($1.dueAt ?? Int64.max) }
    }

    /// One-tap step completion, Android's completeStep: guards on the
    /// cook being unfinished and the occurrence still pending, writes
    /// the step's own timeline event (its action in the note metadata),
    /// then satisfies the plan. `rest` is the remove-step variant that
    /// also starts the dish's rest.
    func completeGuidanceStep(cookId: String, evaluated: EvaluatedStep, rest: Bool = false) throws {
        guard let cook = library.cooks.first(where: { $0.id == cookId }), cook.status != "completed" else { return }
        guard let plan = guidancePlan(cookId: cookId) else { return }
        let step = evaluated.step
        let progress = plan.progress[step.id] ?? StepProgress()
        guard progress.status == "pending", progress.occurrence == evaluated.progress.occurrence else { return }
        let now = Date.now.timeIntervalSince1970 * 1000
        let eventId = UUID().uuidString.lowercased()
        var next = library
        var event: [String: JSONValue] = [
            "id": .string(eventId), "cookId": .string(cookId),
            "recordedAtUtcMillis": .number(now), "createdAtUtcMillis": .number(now),
            "timeZoneId": .string(TimeZone.current.identifier), "source": .string("manual"),
        ]
        event["eventType"] = .string("note")
        event["title"] = .string(step.title)
        event["details"] = .string(step.instructions)
        event["occurredAtUtcMillis"] = .number(now)
        event["updatedAtUtcMillis"] = .number(now)
        event["dishId"] = evaluated.dishId.map(JSONValue.string) ?? .null
        event["noteMetadataJson"] = .string(CookNoteMetadata(category: "action", action: step.action).json)
        next.events.append(.object(event))
        if CookPhaseEngine.actions.contains(step.action), timingMode(cookId) == "explicit_phases" {
            try CookPhaseEngine.validate(next.actions(for: cookId), dishIds: dishRecords(cookId).map(\.id))
        }
        let guidanceEvent = GuidanceEvent(id: eventId, action: step.action, dishId: evaluated.dishId, at: Int64(now))
        let satisfied = CookPlanEngine.satisfy(plan: plan, step: step, event: guidanceEvent)
        if var record = next.companionRecords.first(where: { $0.objectValue?["id"]?.stringValue == "plan:" + cookId })?.objectValue {
            record["payload"] = .string(try CookPlanCodec.encode(satisfied))
            record["updatedAtUtcMillis"] = .number(now)
            next.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == "plan:" + cookId }
            next.companionRecords.append(.object(record))
        }
        try next.validateCookExperience()
        library = next
        save()
        if saveError != nil { throw CocoaError(.fileWriteUnknown) }
        if rest, step.action == "remove", let dishId = evaluated.dishId {
            try recordDishAction(cookId: cookId, dishId: dishId, action: "rest_start")
        }
    }

    func snoozeGuidanceStep(cookId: String, evaluated: EvaluatedStep) throws {
        guard var plan = guidancePlan(cookId: cookId) else { return }
        let progress = plan.progress[evaluated.step.id] ?? StepProgress()
        guard progress.status == "pending", progress.occurrence == evaluated.progress.occurrence else { return }
        var updated = progress
        updated.snoozedUntil = Int64(Date.now.timeIntervalSince1970 * 1000) + 600_000
        updated.notifiedOccurrence = nil
        plan.progress[evaluated.step.id] = updated
        try writePlan(cookId: cookId, plan: plan)
        save()
    }

    /// Skip is plan progress, never a physical timeline event; the
    /// optional anchor — using now for later steps — is explicit.
    func skipGuidanceStep(cookId: String, stepId: String, useNowAsAnchor: Bool) throws {
        guard var plan = guidancePlan(cookId: cookId) else { return }
        let progress = plan.progress[stepId] ?? StepProgress()
        guard progress.status == "pending" else { return }
        var updated = progress
        updated.status = "skipped"
        updated.skippedAnchorAt = useNowAsAnchor ? Int64(Date.now.timeIntervalSince1970 * 1000) : nil
        plan.progress[stepId] = updated
        try writePlan(cookId: cookId, plan: plan)
        save()
    }

    func setGuidancePaused(cookId: String, paused: Bool) throws {
        guard var plan = guidancePlan(cookId: cookId) else { return }
        plan.paused = paused
        try writePlan(cookId: cookId, plan: plan)
        save()
    }

    /// Android's onEvent: an existing quick action satisfies a matching
    /// planned occurrence in the same breath. Called from saveNote —
    /// the one funnel every recorded action passes through.
    func satisfyGuidanceFromEvent(_ eventId: String) {
        guard let event = library.events.first(where: { $0.objectValue?["id"]?.stringValue == eventId })?.objectValue,
              let cookId = event["cookId"]?.stringValue,
              var plan = guidancePlan(cookId: cookId),
              let cookAction = library.actions(for: cookId).first(where: { $0.id == eventId }) else { return }
        let eventDishId = event["dishId"]?.stringValue
        let scopes = cookAction.dishIds
        let guidanceEvent = GuidanceEvent(id: eventId, action: cookAction.action, dishId: eventDishId, dishIds: scopes, at: Int64(cookAction.at))
        var seenDishIndexes: Set<Int?> = []
        let matches = plan.book.steps.filter { step in
            guard step.action == cookAction.action else { return false }
            let stepDishId = step.dishIndex.flatMap { plan.dishIds.indices.contains($0) ? plan.dishIds[$0] : nil }
            let scopeMatches: Bool
            if !scopes.isEmpty { scopeMatches = stepDishId.map { scopes.contains($0) } ?? false }
            else { scopeMatches = stepDishId == eventDishId || eventDishId == nil || (step.dishIndex == nil && plan.dishIds.count == 1) }
            let progress = plan.progress[step.id] ?? StepProgress()
            guard scopeMatches, progress.status == "pending", !progress.eventIds.contains(eventId) else { return false }
            guard !seenDishIndexes.contains(step.dishIndex) else { return false }
            seenDishIndexes.insert(step.dishIndex)
            return true
        }
        guard !matches.isEmpty else { return }
        for step in matches { plan = CookPlanEngine.satisfy(plan: plan, step: step, event: guidanceEvent) }
        try? writePlan(cookId: cookId, plan: plan)
        save()
    }

    // MARK: - Notifications (the Done path outside the app)

    struct GuidanceReconciliation {
        var posts: [(id: String, cookId: String, stepId: String, occurrence: Int, title: String, body: String)] = []
        var schedules: [(id: String, cookId: String, stepId: String, occurrence: Int, title: String, body: String, at: Int64)] = []
        var cancellations: [String] = []
    }

    /// Runs inside save(), like the reminder reconciliation: ready steps
    /// notify once per occurrence (marked in the plan itself), future
    /// dues are scheduled, and anything no longer pending is cancelled.
    func prepareGuidanceSchedules(now: Double = Date.now.timeIntervalSince1970 * 1000) -> GuidanceReconciliation {
        var result = GuidanceReconciliation()
        let nowMillis = Int64(now)
        for row in library.companionRecords {
            guard let record = row.objectValue, record["kind"]?.stringValue == "plan",
                  let cookId = record["cookId"]?.stringValue,
                  let payload = record["payload"]?.stringValue,
                  var plan = try? CookPlanCodec.decode(payload) else { continue }
            let completed = library.cooks.first { $0.id == cookId }?.status == "completed"
            let evaluated = CookPlanEngine.evaluate(plan: plan, events: guidanceEvents(cookId: cookId), readings: guidanceReadings(cookId: cookId), now: nowMillis)
            var planChanged = false
            for e in evaluated {
                let identifier = "cook-guidance:\(cookId):\(e.step.id)"
                if plan.paused || completed || e.progress.status != "pending" {
                    result.cancellations.append(identifier)
                    continue
                }
                if e.ready, e.step.trigger != "manual", e.progress.notifiedOccurrence != e.progress.occurrence {
                    var progress = plan.progress[e.step.id] ?? e.progress
                    progress.notifiedOccurrence = e.progress.occurrence
                    plan.progress[e.step.id] = progress
                    planChanged = true
                    result.posts.append((id: identifier, cookId: cookId, stepId: e.step.id, occurrence: e.progress.occurrence, title: e.step.title, body: e.step.instructions.isEmpty ? "Check your food, then confirm what you did." : e.step.instructions))
                }
                if let due = e.dueAt, due > nowMillis {
                    result.schedules.append((id: identifier, cookId: cookId, stepId: e.step.id, occurrence: e.progress.occurrence, title: e.step.title, body: e.step.instructions.isEmpty ? "Check your food, then confirm what you did." : e.step.instructions, at: due))
                } else if !e.ready {
                    result.cancellations.append(identifier)
                }
            }
            if planChanged { try? writePlan(cookId: cookId, plan: plan) }
        }
        return result
    }

    func submitGuidanceSchedules(_ reconciliation: GuidanceReconciliation) {
        guard !reconciliation.posts.isEmpty || !reconciliation.schedules.isEmpty || !reconciliation.cancellations.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: reconciliation.cancellations)
        center.removeDeliveredNotifications(withIdentifiers: reconciliation.cancellations)
        for post in reconciliation.posts {
            let content = UNMutableNotificationContent()
            content.title = post.title
            content.body = post.body
            content.sound = .default
            content.categoryIdentifier = "pittech-guidance"
            content.userInfo = ["cookId": post.cookId, "stepId": post.stepId, "occurrence": post.occurrence, "kind": "guidance"]
            center.add(UNNotificationRequest(identifier: post.id, content: content, trigger: nil))
        }
        for schedule in reconciliation.schedules {
            let content = UNMutableNotificationContent()
            content.title = schedule.title
            content.body = schedule.body
            content.sound = .default
            content.categoryIdentifier = "pittech-guidance"
            content.userInfo = ["cookId": schedule.cookId, "stepId": schedule.stepId, "occurrence": schedule.occurrence, "kind": "guidance"]
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, Double(schedule.at - Int64(Date.now.timeIntervalSince1970 * 1000)) / 1000), repeats: false)
            center.add(UNNotificationRequest(identifier: schedule.id, content: content, trigger: trigger))
        }
    }

    /// A notification's Done / Snooze, applied against current state —
    /// the occurrence guard inside complete/snooze makes a stale action
    /// a no-op, exactly like Android's receiver re-checking the plan.
    func applyGuidanceNotificationAction(cookId: String, stepId: String, occurrence: Int, action: String) {
        guard let evaluated = evaluatedGuidance(cookId: cookId).first(where: { $0.step.id == stepId }) else { return }
        do {
            if action == "done" { try completeGuidanceStep(cookId: cookId, evaluated: evaluated) }
            else if action == "snooze" { try snoozeGuidanceStep(cookId: cookId, evaluated: evaluated) }
        } catch { /* a stale or invalid action changes nothing */ }
    }
}
