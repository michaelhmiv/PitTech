import SwiftUI
import Charts

struct NativePublicStoryView: View {
    @Environment(CookStore.self) private var store
    let story: [String: JSONValue]
    @State private var selected = ""
    private var channels: [[String: JSONValue]] { (story["channels"]?.arrayValue ?? []).compactMap(\.objectValue) }
    private var channel: [String: JSONValue] { channels.first { $0["id"]?.stringValue == selected } ?? channels.first ?? [:] }
    private var samples: [[String: JSONValue]] { (story["measurements"]?.arrayValue ?? []).compactMap(\.objectValue).filter { $0["channelId"]?.stringValue == channel["id"]?.stringValue }.sorted { ($0["offsetMillis"]?.numberValue ?? 0) < ($1["offsetMillis"]?.numberValue ?? 0) } }
    private var points: [[String: JSONValue]] {
        let all = samples, stride = max(1, Int(ceil(Double(all.count) / 1000)))
        let minimum = all.indices.min { (all[$0]["valueCelsius"]?.numberValue ?? 0) < (all[$1]["valueCelsius"]?.numberValue ?? 0) }
        let maximum = all.indices.max { (all[$0]["valueCelsius"]?.numberValue ?? 0) < (all[$1]["valueCelsius"]?.numberValue ?? 0) }
        return all.enumerated().filter { $0.offset % stride == 0 || $0.offset == all.count - 1 || $0.offset == minimum || $0.offset == maximum }.map(\.element)
    }
    private func time(_ offset: Double) -> String { RecipeReferenceTimeline.relative(offset - (story["foodOnOffsetMillis"]?.numberValue ?? 0)) }
    private func valueDescription(_ point: [String: JSONValue]) -> String {
        let celsius = point["valueCelsius"]?.numberValue ?? 0
        let value = store.temperatureUnit == "C" ? celsius : celsius * 9 / 5 + 32
        let unit = store.temperatureUnit == "C" ? " °C · " : " °F · "
        let quality = point["quality"]?.stringValue ?? "recorded"
        return time(point["offsetMillis"]?.numberValue ?? 0) + " · " + String(format: "%.1f", value) + unit + quality
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Recorded cook story").font(.title3.bold())
            if story["foodOnOffsetMillis"]?.numberValue == nil { Text("Food on anchor wasn't published. Times use the shared timeline.").font(.caption) }
            if !channels.isEmpty {
                Picker("Reference channel", selection: Binding(get: { channel["id"]?.stringValue ?? "" }, set: { selected = $0 })) {
                    ForEach(channels.indices, id: \.self) { index in
                        let c = channels[index]
                        Text((c["label"]?.stringValue ?? "Channel") + " · " + (c["role"]?.stringValue ?? "") + " · channel \(index + 1)").tag(c["id"]?.stringValue ?? "")
                    }
                }
                if !points.isEmpty {
                    NativePublicSampleChart(points: points, anchor: story["foodOnOffsetMillis"]?.numberValue, unit: store.temperatureUnit)
                    Text("\(samples.count) recorded samples. Points show measurements; gaps aren't filled.").font(.caption)
                    DisclosureGroup("View recorded values") {
                        ForEach(points.indices, id: \.self) { index in
                            Text(valueDescription(points[index]))
                        }
                    }
                    if points.count < samples.count { Text("Showing \(points.count) original points for readability.").font(.caption) }
                } else { Text("No measurements were published for this channel.") }
            }
            ForEach((story["milestones"]?.arrayValue ?? []).indices, id: \.self) { index in
                let row = story["milestones"]?.arrayValue?[index].objectValue ?? [:]
                Text(time(row["offsetMillis"]?.numberValue ?? 0) + " · " + (row["text"]?.stringValue ?? row["action"]?.stringValue ?? "Recorded action"))
            }
            if let off = story["foodOffOffsetMillis"]?.numberValue { Text("Recorded Food off · " + time(off)).font(.caption) }
            if let verdict = story["verdict"]?.objectValue {
                if let rating = verdict["rating"]?.numberValue { Text("Recorded rating · \(rating, specifier: "%.0f") / 5") }
                if let again = verdict["cookAgain"]?.boolValue { Text(again ? "Would cook again" : "Would change next time") }
                if let notes = verdict["notes"]?.stringValue, !notes.isEmpty { Text(notes) }
            }
        }
    }
}
struct NativePublicAdditionalPhoto: View {
    @Environment(NativePublicCoordinator.self) private var community
    let detail: NativePublicDetail
    let asset: NativePublicDetail.Media
    let caption: String
    @State private var bytes: Data?
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let bytes, let image = UIImage(data: bytes) { Image(uiImage: image).resizable().scaledToFit().accessibilityLabel(caption.isEmpty ? "Selected recipe photograph" : caption) }
            else { Text("Photograph unavailable") }
            if !caption.isEmpty { Text(caption).font(.caption) }
        }.task(id: asset.sha256) { bytes = try? await community.image(detail, asset: asset) }
    }
}

private struct NativePublicSampleChart: View {
    let points: [[String: JSONValue]]
    let anchor: Double?
    let unit: String
    private struct Sample: Identifiable {
        let id: Int
        let hours: Double
        let temperature: Double
        let source: String
    }
    private var samples: [Sample] {
        points.enumerated().map { index, point in
            let celsius = point["valueCelsius"]?.numberValue ?? 0
            let offset = point["offsetMillis"]?.numberValue ?? 0
            let temperature = unit == "C" ? celsius : celsius * 9 / 5 + 32
            return Sample(id: index, hours: (offset - (anchor ?? 0)) / 3_600_000,
                          temperature: temperature, source: point["quality"]?.stringValue == "manual" ? "Manual" : "Recorded")
        }
    }
    var body: some View {
        Chart(samples) { sample in
            PointMark(x: .value("Hours", sample.hours), y: .value("Temperature", sample.temperature))
                .symbol(by: .value("Source", sample.source))
        }
        .frame(height: 220)
        .chartXAxisLabel(anchor == nil ? "Shared timeline · hours" : "Hours from Food on")
        .chartYAxisLabel(unit == "C" ? "°C" : "°F")
    }
}
