import Foundation

/// The cook guidance plan (issue #183, iOS half): Android's CookPlan +
/// CookPlanEngine, ported one for one. The plan lives in the companion
/// record `plan:<cookId>` (kind "plan") with the shared payload shape —
/// the playbook's own fields plus dishIds / playbookId / paused /
/// progress — so a plan written by either platform reads identically on
/// the other. Until this port, iOS validated the payload in the sync
/// codec but could not decode, evaluate, or complete a single step.

struct StepProgress: Equatable {
    var status: String = "pending"
    var eventIds: [String] = []
    var lastDoneAt: Int64? = nil
    var snoozedUntil: Int64? = nil
    var occurrence: Int = 0
    var notifiedOccurrence: Int? = nil
    var skippedAnchorAt: Int64? = nil
}

struct CookPlan: Equatable {
    var book: CookPlaybook
    var dishIds: [String]
    var playbookId: String? = nil
    var paused: Bool = false
    var progress: [String: StepProgress] = [:]
}

/// An action on the cook's timeline, resolved the way Android's
/// notePlanEvents resolves it: the event's own dish plus any dish scopes
/// its note metadata names.
struct GuidanceEvent: Equatable {
    var id: String
    var action: String
    var dishId: String?
    var dishIds: [String] = []
    var at: Int64
}

struct GuidanceReading: Equatable {
    var dishId: String?
    var fahrenheit: Double
    var at: Int64
    var valid: Bool = true
    var type: String = "food"
    var probeName: String = ""
}

struct EvaluatedStep: Equatable {
    var step: PlaybookStep
    var progress: StepProgress
    var dishId: String?
    var dueAt: Int64?
    var ready: Bool
    var waitingFor: String?
}

enum CookPlanError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { switch self { case .invalid(let message): message } }
}

enum CookPlanCodec {
    static func encode(_ plan: CookPlan) throws -> String {
        var object = try PlaybookCodec.encode(plan.book).objectValue ?? [:]
        object["dishIds"] = .array(plan.dishIds.map(JSONValue.string))
        if let playbookId = plan.playbookId { object["playbookId"] = .string(playbookId) }
        object["paused"] = .bool(plan.paused)
        var progress: [String: JSONValue] = [:]
        for (id, p) in plan.progress {
            progress[id] = .object([
                "status": .string(p.status),
                "eventIds": .array(p.eventIds.map(JSONValue.string)),
                "lastDoneAt": p.lastDoneAt.map { .number(Double($0)) } ?? .null,
                "snoozedUntil": p.snoozedUntil.map { .number(Double($0)) } ?? .null,
                "occurrence": .number(Double(p.occurrence)),
                "notifiedOccurrence": p.notifiedOccurrence.map { .number(Double($0)) } ?? .null,
                "skippedAnchorAt": p.skippedAnchorAt.map { .number(Double($0)) } ?? .null,
            ])
        }
        object["progress"] = .object(progress)
        return String(decoding: try JSONEncoder().encode(JSONValue.object(object)), as: UTF8.self)
    }

    static func decode(_ raw: String) throws -> CookPlan {
        guard let value = try? JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8)),
              let object = value.objectValue else {
            throw CookPlanError.invalid("The plan payload is not an object.")
        }
        let book = try PlaybookCodec.decode(object)
        let ids = (object["dishIds"]?.arrayValue ?? []).compactMap(\.stringValue)
        guard ids.count == book.draft.dishes.count, Set(ids).count == ids.count else {
            throw CookPlanError.invalid("The plan dish mapping is invalid.")
        }
        var progress: [String: StepProgress] = [:]
        for (id, rawProgress) in object["progress"]?.objectValue ?? [:] {
            guard let p = rawProgress.objectValue else { continue }
            progress[id] = StepProgress(
                status: p["status"]?.stringValue ?? "pending",
                eventIds: (p["eventIds"]?.arrayValue ?? []).compactMap(\.stringValue),
                lastDoneAt: p["lastDoneAt"]?.numberValue.map(Int64.init),
                snoozedUntil: p["snoozedUntil"]?.numberValue.map(Int64.init),
                occurrence: Int(p["occurrence"]?.numberValue ?? 0),
                notifiedOccurrence: p["notifiedOccurrence"]?.numberValue.map(Int.init),
                skippedAnchorAt: p["skippedAnchorAt"]?.numberValue.map(Int64.init))
        }
        guard progress.keys.allSatisfy({ id in book.steps.contains { $0.id == id } }),
              progress.values.allSatisfy({ ["pending", "done", "skipped"].contains($0.status) && $0.occurrence >= 0 }) else {
            throw CookPlanError.invalid("Invalid step progress.")
        }
        return CookPlan(book: book, dishIds: ids, playbookId: object["playbookId"]?.stringValue, paused: object["paused"]?.boolValue ?? false, progress: progress)
    }
}

/// Pure local guidance: actual timeline actions are the only anchors for
/// physical stages. A line-for-line port of Android's CookPlanEngine.
enum CookPlanEngine {
    static func fahrenheit(_ value: Double, _ unit: String) -> Double {
        unit == "°C" ? value * 9.0 / 5.0 + 32.0 : value
    }

    static func stage(events: [GuidanceEvent], dishId: String?) -> String {
        let scoped = events.filter { $0.dishId == nil || $0.dishId == dishId }
        if scoped.max(by: { $0.at < $1.at })?.action == "dish_done" { return "done" }
        guard let latest = scoped.filter({ ["food_on", "remove", "rest_start", "hold_start", "dish_done"].contains($0.action) }).max(by: { $0.at < $1.at }) else { return "prep" }
        switch latest.action {
        case "food_on": return "cooking"
        case "remove": return "removed"
        case "rest_start": return "resting"
        case "hold_start": return "holding"
        default: return "done"
        }
    }

    static func evaluate(plan: CookPlan, events: [GuidanceEvent], readings: [GuidanceReading], now: Int64) -> [EvaluatedStep] {
        plan.book.steps.map { s in
            let p = plan.progress[s.id] ?? StepProgress()
            let dishId = s.dishIndex.flatMap { plan.dishIds.indices.contains($0) ? plan.dishIds[$0] : nil }
            let scopeEvents = events.filter { $0.dishId == nil || $0.dishId == dishId || (dishId == nil && plan.dishIds.count == 1) }
            let currentStage: String
            if dishId == nil, !plan.dishIds.isEmpty {
                let stages = plan.dishIds.map { stage(events: events, dishId: $0) }
                currentStage = Set(stages).count == 1 ? stages[0] : "mixed"
            } else {
                currentStage = stage(events: events, dishId: dishId)
            }
            let anchor = s.trigger == "elapsed" ? "food_on" : s.anchor
            let anchorTime = scopeEvents.filter { $0.action == anchor }.map(\.at).max()
                ?? plan.book.steps.first { $0.action == anchor && $0.dishIndex == s.dishIndex }.flatMap { plan.progress[$0.id]?.skippedAnchorAt }
            let lastActual = p.eventIds.compactMap { id in events.first { $0.id == id }?.at }.max() ?? p.lastDoneAt
            let due: Int64?
            if let repeatMinutes = s.repeatEveryMinutes, let lastActual {
                due = lastActual + repeatMinutes * 60_000
            } else if s.trigger == "elapsed" || s.trigger == "after_action" {
                due = anchorTime.map { $0 + s.minutes * 60_000 }
            } else if s.trigger == "clock" {
                due = s.clockAtUtcMillis
            } else {
                due = nil
            }
            let eligibleStage: Bool
            switch s.action {
            case "food_on": eligibleStage = currentStage == "prep"
            case "rest_start": eligibleStage = ["cooking", "removed"].contains(currentStage)
            case "hold_start": eligibleStage = ["removed", "resting", "holding"].contains(currentStage)
            case "dish_done": eligibleStage = ["removed", "resting", "holding"].contains(currentStage)
            default: eligibleStage = currentStage == s.stage
            }
            let temperatureReady = readings.filter { reading in
                guard reading.valid, ["food", "food_probe"].contains(reading.type),
                      s.probeName.isEmpty || reading.probeName == s.probeName,
                      dishId == nil || reading.dishId == dishId,
                      now - reading.at >= 0, now - reading.at <= 330_000 else { return false }
                let foodOnAt = events.filter { $0.action == "food_on" && ($0.dishId == nil || $0.dishId == reading.dishId) }.map(\.at).max() ?? Int64.max
                return reading.at >= foodOnAt
            }.max(by: { $0.at < $1.at }).map { $0.fahrenheit >= (s.temperatureF ?? Double.greatestFiniteMagnitude) } ?? false
            let primaryReady: Bool
            switch s.trigger {
            case "manual": primaryReady = true
            case "temperature": primaryReady = (lastActual != nil && s.repeatEveryMinutes != nil) ? (due != nil && now >= due!) : temperatureReady
            default: primaryReady = due != nil && now >= due!
            }
            let ready = !plan.paused && p.status == "pending" && eligibleStage && primaryReady && (p.snoozedUntil == nil || now >= p.snoozedUntil!)
            let waitingFor: String?
            if !eligibleStage { waitingFor = "Waiting for \(s.stage)" }
            else if anchorTime == nil, s.trigger == "elapsed" || s.trigger == "after_action" { waitingFor = "Waiting for \(CookStore.actionLabel(anchor))" }
            else if s.trigger == "temperature", !temperatureReady, !(lastActual != nil && s.repeatEveryMinutes != nil) { waitingFor = "Waiting for a fresh temperature" }
            else { waitingFor = nil }
            return EvaluatedStep(step: s, progress: p, dishId: dishId, dueAt: p.snoozedUntil.map { max($0, due ?? $0) } ?? due, ready: ready, waitingFor: waitingFor)
        }
    }

    static func satisfy(plan: CookPlan, step: PlaybookStep, event: GuidanceEvent) -> CookPlan {
        let p = plan.progress[step.id] ?? StepProgress()
        guard p.status == "pending", !p.eventIds.contains(event.id) else { return plan }
        var next = plan
        next.progress[step.id] = StepProgress(
            status: step.repeatEveryMinutes == nil ? "done" : "pending",
            eventIds: p.eventIds + [event.id],
            lastDoneAt: event.at,
            snoozedUntil: nil,
            occurrence: p.occurrence + 1,
            notifiedOccurrence: p.notifiedOccurrence,
            skippedAnchorAt: p.skippedAnchorAt)
        return next
    }

    /// The plan sheet's step sublabel (Android's stepDescription).
    static func stepDescription(_ s: PlaybookStep) -> String {
        let base: String
        switch s.trigger {
        case "elapsed": base = "Check \(s.minutes) min after food goes on"
        case "after_action": base = "Check \(s.minutes) min after \(CookStore.actionLabel(s.anchor))"
        case "temperature": base = "Check at \(s.temperatureF.map { "\($0)" } ?? "") °F"
        case "clock": base = "Check at \(s.clockAtUtcMillis.map { RecipeReferenceTimeline.time(Double($0)) } ?? "")"
        default: base = "When ready"
        }
        return base + (s.repeatEveryMinutes.map { " · repeat every \($0) min" } ?? "")
    }
}
