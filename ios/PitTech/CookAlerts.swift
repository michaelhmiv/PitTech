import Foundation

/// Cook Alerts (issue #183, iOS half): Android's CookAlertRule /
/// CookAlertState / CookAlertEngine, ported one for one. Alert
/// definitions live in the companion record `alert:<ruleId>` (kind
/// "alert") with the shared payload shape, so a rule written by either
/// platform evaluates identically on the other. Until this port, iOS
/// validated the payload in the sync codec but had no alerts at all —
/// the Live overnight panel said so in as many words.

struct CookAlertRule: Equatable {
    var id: String = UUID().uuidString.lowercased()
    var type: String = "target"
    var dishId: String? = nil
    var probeName: String = ""
    var targetF: Double? = nil
    var lowF: Double? = nil
    var highF: Double? = nil
    var dwellMillis: Int64 = 120_000
    var missingMillis: Int64 = 180_000
    var recoveryF: Double = 5.0
    var repeatMillis: Int64 = 900_000
    var enabled: Bool = true
    var stage: String = "cooking"
}

struct CookAlertState: Equatable {
    var active: Bool = false
    var badSince: Int64? = nil
    var lastSampleAt: Int64? = nil
    var lastNotifiedAt: Int64? = nil
    var snoozedUntil: Int64? = nil
    var suppressedUntil: Int64? = nil
    var acknowledged: Bool = false
    var notifiedCount: Int = 0
}

struct AlertSample: Equatable {
    var fahrenheit: Double
    var at: Int64
    var receiptAt: Int64
    var valid: Bool
    var freshnessMillis: Int64 = 180_000
    var ageKnown: Bool = true
}

struct AlertEvaluation {
    var state: CookAlertState
    var notify: Bool
    var nextWake: Int64?
    var detail: String
}

enum CookAlertError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { switch self { case .invalid(let message): message } }
}

enum CookAlertEngine {
    /// Display order matches Android's linked map.
    static let types: [(type: String, label: String)] = [
        ("target", "Food target"), ("pit_range", "Pit range"), ("missing", "Missing readings"), ("offline", "Reported offline"),
    ]
    static func label(for type: String) -> String { types.first { $0.type == type }?.label ?? type }

    static func validate(_ rule: CookAlertRule) throws {
        guard types.contains(where: { $0.type == rule.type }), ["cooking", "resting", "holding"].contains(rule.stage) else {
            throw CookAlertError.invalid("Choose an alert type and stage.")
        }
        guard (0...3_600_000).contains(rule.dwellMillis), (60_000...3_600_000).contains(rule.missingMillis),
              rule.repeatMillis >= 300_000, (0.0...50.0).contains(rule.recoveryF) else {
            throw CookAlertError.invalid("Choose valid alert intervals.")
        }
        if rule.type == "target" {
            guard let target = rule.targetF, target.isFinite, (32.0...600.0).contains(target) else {
                throw CookAlertError.invalid("Enter a food target.")
            }
        }
        if rule.type == "pit_range" {
            guard let low = rule.lowF, let high = rule.highF, low.isFinite, high.isFinite, low < high else {
                throw CookAlertError.invalid("Low temperature must be below high temperature.")
            }
        }
    }

    static func encode(_ rule: CookAlertRule, _ state: CookAlertState = CookAlertState()) throws -> String {
        try validate(rule)
        let object: [String: JSONValue] = [
            "version": .number(1), "id": .string(rule.id), "type": .string(rule.type),
            "dishId": rule.dishId.map(JSONValue.string) ?? .null,
            "probeName": .string(rule.probeName),
            "targetF": rule.targetF.map(JSONValue.number) ?? .null,
            "lowF": rule.lowF.map(JSONValue.number) ?? .null,
            "highF": rule.highF.map(JSONValue.number) ?? .null,
            "dwell": .number(Double(rule.dwellMillis)), "missing": .number(Double(rule.missingMillis)),
            "recoveryF": .number(rule.recoveryF), "repeat": .number(Double(rule.repeatMillis)),
            "enabled": .bool(rule.enabled), "stage": .string(rule.stage),
            "state": .object([
                "active": .bool(state.active),
                "badSince": state.badSince.map { .number(Double($0)) } ?? .null,
                "lastSampleAt": state.lastSampleAt.map { .number(Double($0)) } ?? .null,
                "lastNotifiedAt": state.lastNotifiedAt.map { .number(Double($0)) } ?? .null,
                "snoozedUntil": state.snoozedUntil.map { .number(Double($0)) } ?? .null,
                "suppressedUntil": state.suppressedUntil.map { .number(Double($0)) } ?? .null,
                "acknowledged": .bool(state.acknowledged),
                "notifiedCount": .number(Double(state.notifiedCount)),
            ]),
        ]
        return String(decoding: try JSONEncoder().encode(JSONValue.object(object)), as: UTF8.self)
    }

    static func decode(_ raw: String) throws -> (rule: CookAlertRule, state: CookAlertState) {
        guard let value = try? JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8)),
              let o = value.objectValue, o["version"]?.numberValue == 1,
              let id = o["id"]?.stringValue, let type = o["type"]?.stringValue else {
            throw CookAlertError.invalid("Unsupported alert version.")
        }
        func nLong(_ key: String) -> Int64? { o[key]?.numberValue.map(Int64.init) }
        func nDouble(_ key: String) -> Double? { o[key]?.numberValue }
        let rule = CookAlertRule(
            id: id, type: type, dishId: o["dishId"]?.stringValue,
            probeName: o["probeName"]?.stringValue ?? "",
            targetF: nDouble("targetF"), lowF: nDouble("lowF"), highF: nDouble("highF"),
            dwellMillis: nLong("dwell") ?? 120_000, missingMillis: nLong("missing") ?? 180_000,
            recoveryF: nDouble("recoveryF") ?? 5.0, repeatMillis: nLong("repeat") ?? 900_000,
            enabled: o["enabled"]?.boolValue ?? true, stage: o["stage"]?.stringValue ?? "cooking")
        try validate(rule)
        let s = o["state"]?.objectValue ?? [:]
        func sLong(_ key: String) -> Int64? { s[key]?.numberValue.map(Int64.init) }
        let state = CookAlertState(
            active: s["active"]?.boolValue ?? false,
            badSince: sLong("badSince"), lastSampleAt: sLong("lastSampleAt"),
            lastNotifiedAt: sLong("lastNotifiedAt"), snoozedUntil: sLong("snoozedUntil"),
            suppressedUntil: sLong("suppressedUntil"),
            acknowledged: s["acknowledged"]?.boolValue ?? false,
            notifiedCount: Int(s["notifiedCount"]?.numberValue ?? 0))
        return (rule, state)
    }

    static func evaluate(rule: CookAlertRule, previous: CookAlertState, sample: AlertSample?, lastReceipt: Int64?, reportedOffline: Bool?, stage: String, now: Int64, running: Bool = true) -> AlertEvaluation {
        if !rule.enabled || !running || (rule.type == "target" && stage != rule.stage) {
            return AlertEvaluation(state: CookAlertState(), notify: false, nextWake: nil, detail: "Inactive for this stage")
        }
        if rule.type == "pit_range", let suppressed = previous.suppressedUntil, now < suppressed {
            var state = previous; state.badSince = nil; state.lastSampleAt = nil
            return AlertEvaluation(state: state, notify: false, nextWake: suppressed, detail: "Lid-open suppression")
        }
        let fresh = sample.map { $0.valid && $0.fahrenheit.isFinite && (0...$0.freshnessMillis).contains(now - $0.receiptAt) && (!$0.ageKnown || (0...$0.freshnessMillis).contains(now - $0.at)) } ?? false
        if ["target", "pit_range"].contains(rule.type), !fresh {
            var state = previous; state.badSince = nil; state.lastSampleAt = nil
            return AlertEvaluation(state: state, notify: false, nextWake: nil, detail: "Waiting for a fresh valid reading")
        }
        let bad: Bool
        switch rule.type {
        case "target": bad = sample!.fahrenheit >= rule.targetF! - (previous.active ? rule.recoveryF : 0.0)
        case "pit_range": bad = previous.active
            ? sample!.fahrenheit < rule.lowF! + rule.recoveryF || sample!.fahrenheit > rule.highF! - rule.recoveryF
            : sample!.fahrenheit < rule.lowF! || sample!.fahrenheit > rule.highF!
        case "missing": bad = lastReceipt.map { now - $0 >= rule.missingMillis } ?? false
        case "offline": bad = reportedOffline == true
        default: bad = false
        }
        if !bad {
            let state = CookAlertState(snoozedUntil: previous.snoozedUntil, suppressedUntil: previous.suppressedUntil)
            let wake = rule.type == "missing" ? lastReceipt.map { $0 + rule.missingMillis }.flatMap { $0 > now ? $0 : nil } : nil
            return AlertEvaluation(state: state, notify: false, nextWake: wake, detail: "Within limits")
        }
        // Continuity is proved only by accepted samples. A timer cannot
        // extend a pit dwell on one old value.
        let gap = previous.lastSampleAt.map { last in sample.map { $0.at - last > $0.freshnessMillis } ?? false } ?? false
        let since = gap ? now : (previous.badSince ?? now)
        let latestSampleAt = sample?.at ?? previous.lastSampleAt
        let sustained = rule.type != "pit_range" || (latestSampleAt.map { $0 - since >= rule.dwellMillis } ?? false)
        let active = (previous.active && !gap) || sustained
        let repeatsDue = previous.lastNotifiedAt.map { now - $0 >= rule.repeatMillis } ?? true
        let notify = active && repeatsDue && (previous.snoozedUntil.map { now >= $0 } ?? true) && !previous.acknowledged && previous.notifiedCount < 3
        var state = previous
        state.active = active; state.badSince = since; state.lastSampleAt = latestSampleAt
        let nextWake: Int64?
        if let snoozed = previous.snoozedUntil, snoozed > now { nextWake = snoozed }
        else if active, !previous.acknowledged, previous.notifiedCount < 3 { nextWake = (previous.lastNotifiedAt ?? now) + rule.repeatMillis }
        else { nextWake = nil }
        let detail: String
        switch rule.type {
        case "missing": detail = "No accepted reading for \((now - (lastReceipt ?? now)) / 60_000) min"
        case "offline": detail = "The controller service reports the grill offline"
        default: detail = "\(sample!.fahrenheit) °F" + (sample!.ageKnown == false ? " · cloud receipt is recent; sensor age is unknown" : "")
        }
        return AlertEvaluation(state: state, notify: notify, nextWake: nextWake, detail: detail)
    }

    /// PL-03: the Live strip line for a cook's alert rules. Null when the
    /// cook has no rules, so Live stays silent instead of showing an
    /// empty alerts row. Acknowledged rules are named on the strip.
    static func stripSummary(alerts: [(title: String, state: CookAlertState)]) -> String? {
        if alerts.isEmpty { return nil }
        let attention = alerts.count { $0.state.active }
        let acknowledged = alerts.filter { $0.state.acknowledged }.map(\.title)
        let base = attention > 0 ? "Alerts · \(attention) need\(attention == 1 ? "s" : "") attention" : "Alerts · all clear"
        return acknowledged.isEmpty ? base : base + " · Acknowledged: " + acknowledged.joined(separator: ", ")
    }
}
