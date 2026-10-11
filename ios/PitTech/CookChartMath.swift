import Foundation

struct NativePlotPoint: Identifiable {
    let id: String
    let reading: TemperatureSample
    let segmentId: String
}
enum CookChartMath {
    static func value(_ sample: TemperatureSample, unit: String) -> Double { unit == "C" ? (sample.fahrenheit - 32) * 5 / 9 : sample.fahrenheit }
    static func segments(_ samples: [TemperatureSample], gaps: [Date] = []) -> [[TemperatureSample]] {
        Dictionary(grouping: samples, by: \.seriesId).values.flatMap { rows in
            var result: [[TemperatureSample]] = [], current: [TemperatureSample] = []
            func flush() { if !current.isEmpty { result.append(current) }; current = [] }
            for row in rows.sorted(by: { $0.measuredAt < $1.measuredAt }) {
                guard row.qualityStatus == "valid", row.value.isFinite, ["°F", "°C"].contains(row.unit) else { flush(); continue }
                if let previous = current.last, row.source != "manual" {
                    let window = max(45_000, max(row.samplingInterval, previous.samplingInterval) + 30_000) / 1_000
                    if row.samplingInterval == 0 || previous.samplingInterval == 0 || row.measuredAt.timeIntervalSince(previous.measuredAt) > window || gaps.contains(where: { $0 > previous.measuredAt && $0 <= row.measuredAt }) { flush() }
                }
                current.append(row)
            }
            flush(); return result
        }
    }
    static func reduced(_ rows: [TemperatureSample]) -> [TemperatureSample] {
        guard rows.count > 400 else { return rows }
        let size = (rows.count + 199) / 200
        var selected = [rows[0], rows[rows.count - 1]]
        for start in stride(from: 0, to: rows.count, by: size) {
            let bucket = rows[start..<min(start + size, rows.count)]
            if let low = bucket.min(by: { $0.fahrenheit < $1.fahrenheit }), let high = bucket.max(by: { $0.fahrenheit < $1.fahrenheit }) { selected += [low, high] }
        }
        var ids = Set<String>()
        return selected.sorted { $0.measuredAt < $1.measuredAt }.filter { ids.insert($0.id).inserted }
    }
    static func slope(_ rows: [TemperatureSample], unit: String) -> Double? {
        let rows = rows.filter { $0.qualityStatus == "valid" && $0.value.isFinite }.sorted { $0.measuredAt < $1.measuredAt }
        guard rows.count >= 3, let first = rows.first, let last = rows.last, last.measuredAt.timeIntervalSince(first.measuredAt) >= 600 else { return nil }
        if rows.contains(where: { $0.source == "manual" }) && (last.measuredAt.timeIntervalSince(first.measuredAt) > 1_800 || zip(rows, rows.dropFirst()).contains(where: { pair in pair.1.measuredAt.timeIntervalSince(pair.0.measuredAt) > 600 })) { return nil }
        let xs = rows.map { $0.measuredAt.timeIntervalSince(first.measuredAt) / 60 }, ys = rows.map { value($0, unit: unit) }
        let mx = xs.reduce(0, +) / Double(xs.count), my = ys.reduce(0, +) / Double(ys.count)
        let denominator = xs.reduce(0) { $0 + pow($1 - mx, 2) }
        guard denominator > 0 else { return nil }
        return zip(xs, ys).reduce(0) { $0 + ($1.0 - mx) * ($1.1 - my) } / denominator
    }
    static func clusters(_ events: [TimelineEntry], x: (TimelineEntry) -> Double, start: Double, span: Double) -> [[TimelineEntry]] {
        var groups: [[TimelineEntry]] = []
        for event in events.filter({ x($0) >= start && x($0) <= start + span }).sorted(by: { $0.occurredAt == $1.occurredAt ? $0.id < $1.id : $0.occurredAt < $1.occurredAt }) {
            if let last = groups.last, let first = last.first, (x(event) - x(first)) / max(span, 1) < 0.075 { groups[groups.count - 1].append(event) }
            else { groups.append([event]) }
        }
        return groups
    }
}
