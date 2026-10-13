import Foundation

// Foreground-only condition evaluation. Historical or disconnected readings never
// become current simply because a timer elapsed or an archive was imported.
enum NativeProbeReminder {
    static func due(config: [String: JSONValue], sample: TemperatureSample?, eligible: Bool, now: Double, state: inout [String: JSONValue]) -> Double? {
        guard eligible, let sample, sample.qualityStatus == "valid", sample.value.isFinite,
              ["°F", "°C"].contains(sample.unit), sample.source.hasPrefix("telemetry:"),
              sample.measuredAt.timeIntervalSince1970 * 1000 <= now,
              now - sample.measuredAt.timeIntervalSince1970 * 1000 <= max(45_000, min(900_000, sample.samplingInterval * 3)),
              let threshold = config["thresholdF"]?.numberValue else {
            state["badSinceUtcMillis"] = .null; state["lastSampleAtUtcMillis"] = .null; state["deliveredAtUtcMillis"] = .null
            return nil
        }
        let at = sample.measuredAt.timeIntervalSince1970 * 1000, above = config["direction"]?.stringValue == "above"
        let margin = config["hysteresisF"]?.numberValue ?? 2
        let active = state["active"]?.boolValue == true
        let cleared = above ? sample.fahrenheit < threshold - margin : sample.fahrenheit > threshold + margin
        if active && cleared { state["active"] = .bool(false); state["deliveredAtUtcMillis"] = .null; state["badSinceUtcMillis"] = .null }
        if state["active"]?.boolValue == true {
            if let interval = config["repeatMillis"]?.numberValue, let last = state["lastTriggerAt"]?.numberValue, now - last >= interval {
                state["lastTriggerAt"] = .number(now); state["deliveredAtUtcMillis"] = .null; return now
            }
            return state["dueAtUtcMillis"]?.numberValue
        }
        let crossed = above ? sample.fahrenheit >= threshold : sample.fahrenheit <= threshold
        guard crossed else { state["badSinceUtcMillis"] = .null; state["deliveredAtUtcMillis"] = .null; state["lastSampleAtUtcMillis"] = .number(at); return nil }
        if let last = state["lastSampleAtUtcMillis"]?.numberValue, at - last > max(45_000, sample.samplingInterval * 3) { state["badSinceUtcMillis"] = .null }
        state["lastSampleAtUtcMillis"] = .number(at)
        let since = state["badSinceUtcMillis"]?.numberValue ?? at; state["badSinceUtcMillis"] = .number(since)
        guard at - since >= (config["sustainMillis"]?.numberValue ?? 0) else { return nil }
        state["active"] = .bool(true); state["lastTriggerAt"] = .number(now); return now
    }
}
