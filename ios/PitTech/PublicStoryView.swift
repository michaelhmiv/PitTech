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
    private func spritzLabel(_ inputs: [String: JSONValue]) -> String {
        guard let spritz = inputs["spritz"]?.boolValue else { return CookInputVocabulary.notRecordedLabel }
        guard spritz else { return "No" }
        return "Yes" + (inputs["spritzMedium"]?.stringValue.map { " · " + $0 } ?? "")
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
            Text("Recorded story").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            TurnInBannerText("Recorded cook story")
            if story["foodOnOffsetMillis"]?.numberValue == nil { Text("Food on anchor wasn't published. Times use the shared timeline.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted) }
            if !channels.isEmpty {
                Picker("Reference channel", selection: Binding(get: { channel["id"]?.stringValue ?? "" }, set: { selected = $0 })) {
                    ForEach(channels.indices, id: \.self) { index in
                        let c = channels[index]
                        Text((c["label"]?.stringValue ?? "Channel") + " · " + (c["role"]?.stringValue ?? "") + " · channel \(index + 1)").tag(c["id"]?.stringValue ?? "")
                    }
                }
                if !points.isEmpty {
                    NativePublicSampleChart(points: points, anchor: story["foodOnOffsetMillis"]?.numberValue, unit: store.temperatureUnit)
                    Text("\(samples.count) recorded samples. Points show measurements; gaps aren't filled.").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                    DisclosureGroup("View recorded values") {
                        ForEach(points.indices, id: \.self) { index in
                            Text(valueDescription(points[index])).font(PitTechFont.monoMeta)
                        }
                    }
                    if points.count < samples.count { Text("Showing \(points.count) original points for readability.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted) }
                } else { Text("No measurements were published for this channel.") }
            }
            ForEach((story["milestones"]?.arrayValue ?? []).indices, id: \.self) { index in
                let row = story["milestones"]?.arrayValue?[index].objectValue ?? [:]
                HStack(alignment: .top, spacing: 10) {
                    Text(time(row["offsetMillis"]?.numberValue ?? 0)).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.emberText)
                    Text(row["text"]?.stringValue ?? row["action"]?.stringValue ?? "Recorded action").font(PitTechFont.body)
                }
            }
            if let off = story["foodOffOffsetMillis"]?.numberValue { Text("Recorded Food off · " + time(off)).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
            // Publishable Cooks (issue #250): the dish's recorded inputs,
            // lifted into the story at publish time. Only cook
            // publications carry the key; a null value means the cook
            // recorded no inputs, and fields read as not recorded —
            // never guessed.
            if let inputsValue = story["inputs"] {
                Text("Cook inputs").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                if let inputs = inputsValue.objectValue {
                    Text("Wrap · " + (inputs["wrapMaterial"]?.stringValue.flatMap { WrapMaterial.fromArchive($0)?.label } ?? CookInputVocabulary.notRecordedLabel)).font(PitTechFont.body)
                    Text("Rub · " + (inputs["rub"]?.stringValue ?? CookInputVocabulary.notRecordedLabel)).font(PitTechFont.body)
                    Text("Spritz · " + spritzLabel(inputs)).font(PitTechFont.body)
                } else {
                    Text(CookInputVocabulary.notRecordedLabel).font(PitTechFont.body)
                }
            }
            if let verdict = story["verdict"]?.objectValue {
                if let rating = verdict["rating"]?.numberValue {
                    HStack(spacing: 10) {
                        TurnInStars(rating: rating)
                        Text("Recorded rating · \(rating.formatted()) / 5").font(PitTechFont.body)
                    }
                }
                if let again = verdict["cookAgain"]?.boolValue { Text(again ? "Would cook again" : "Would change next time") }
                if let notes = verdict["notes"]?.stringValue, !notes.isEmpty { Text(notes) }
            }
        }
    }
}
/// Formatting shared by the two renderings of a published cook session
/// (the Discover detail page and the publisher's own preview), mirroring
/// Android's PublicSessionView.
enum PublicSessionFormat {
    static func temperature(value: Double, unit: String, readerUnit: String) -> String {
        let converted: Double = if unit == readerUnit { value } else if unit == "°C", readerUnit == "°F" { value * 9 / 5 + 32 } else if unit == "°F", readerUnit == "°C" { (value - 32) * 5 / 9 } else { value }
        let shown = ["°C", "°F"].contains(unit) ? readerUnit : unit
        return jsNumber(converted) + " " + shown
    }
    static func durationLabel(millis: Double) -> String {
        let minutes = Int64(millis) / 60_000
        return minutes >= 60 ? "\(minutes / 60)h \(minutes % 60)m" : "\(minutes)m"
    }
    /// JS `String(number)` shape: integral values carry no decimal point.
    static func jsNumber(_ value: Double) -> String {
        if value.isFinite, value == value.rounded(), abs(value) < 1e15 { return String(Int64(value)) }
        return String(value)
    }
    static func facts(session: [String: JSONValue], temperatureUnit: String) -> (facts: [String], weather: String?, notes: String?) {
        var facts: [String] = []
        if let smoker = session["smokerName"]?.stringValue { facts.append("Smoker · " + smoker) }
        if let setpoint = session["setpoint"]?.objectValue, let value = setpoint["value"]?.numberValue, let unit = setpoint["unit"]?.stringValue {
            facts.append("Ran at " + temperature(value: value, unit: unit, readerUnit: temperatureUnit))
        }
        if let fuel = session["fuelType"]?.stringValue { facts.append("Fuel · " + fuel) }
        if let wood = session["woodOrPelletBlend"]?.stringValue { facts.append("Wood · " + wood) }
        if let duration = session["durationMillis"]?.numberValue { facts.append("Cooked for " + durationLabel(millis: duration)) }
        if let serve = session["serveAtOffsetMillis"]?.numberValue { facts.append("Serve time " + RecipeReferenceTimeline.relative(serve)) }
        var weather: [String] = []
        if let outdoor = session["outdoorTemperatureValue"]?.numberValue {
            let unit = session["outdoorTemperatureUnit"]?.stringValue.flatMap { $0.isEmpty ? nil : $0 } ?? temperatureUnit
            weather.append(temperature(value: outdoor, unit: unit, readerUnit: temperatureUnit))
        }
        if let notes = session["weatherNotes"]?.stringValue, !notes.isEmpty { weather.append(notes) }
        if let wind = session["windNotes"]?.stringValue, !wind.isEmpty { weather.append(wind) }
        let notes = session["notes"]?.stringValue.flatMap { $0.isEmpty ? nil : $0 }
        return (facts, weather.isEmpty ? nil : weather.joined(separator: " · "), notes)
    }
}

/// Publishable Cooks (issue #250): the session header of a cook
/// publication — the event the dishes shared. Rendered from the
/// payload's session object on both the public detail page and the
/// publisher's own preview.
struct NativePublicSessionView: View {
    let session: [String: JSONValue]
    let temperatureUnit: String
    var body: some View {
        let rendered = PublicSessionFormat.facts(session: session, temperatureUnit: temperatureUnit)
        VStack(alignment: .leading, spacing: 6) {
            Text("The cook").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
            ForEach(rendered.facts.indices, id: \.self) { index in Text(rendered.facts[index]).font(PitTechFont.body) }
            if let weather = rendered.weather { Text(weather).font(PitTechFont.body) }
            if let notes = rendered.notes { Text(notes).font(PitTechFont.body) }
        }.accessibilityIdentifier("public-session")
    }
}

/// The shared timeline of a cook publication: every dish's milestones
/// placed on the one session clock (story anchor offset + milestone
/// offset). Events recorded once for the whole session appear in
/// several dishes' stories under the same milestone id and are shown
/// once, unlabeled; dish events carry their dish's name. View-only —
/// saving a cook forks its dishes as recipes; the session itself never
/// transfers (issue #250, Option A).
struct NativePublicSessionTimeline: View {
    let detail: NativePublicDetail
    private var merged: (rows: [(offset: Double, text: String, dish: String?)], unplaced: Bool) {
        var byId: [String: (offset: Double, text: String, dishes: Set<String>)] = [:]
        var order: [String] = []
        var unplaced = false
        for recipeValue in detail.recipes {
            guard let recipe = recipeValue.objectValue, let story = recipe["story"]?.objectValue else { continue }
            let milestones = (story["milestones"]?.arrayValue ?? []).compactMap(\.objectValue)
            guard let anchor = story["anchorOffsetMillis"]?.numberValue else { if !milestones.isEmpty { unplaced = true }; continue }
            for milestone in milestones {
                guard let id = milestone["id"]?.stringValue else { continue }
                let title = recipe["title"]?.stringValue ?? "Dish"
                if var existing = byId[id] { existing.dishes.insert(title); byId[id] = existing }
                else { byId[id] = (anchor + (milestone["offsetMillis"]?.numberValue ?? 0), milestone["text"]?.stringValue ?? "", [title]); order.append(id) }
            }
        }
        let rows = order.compactMap { byId[$0] }.map { (offset: $0.offset, text: $0.text, dish: $0.dishes.count == 1 ? $0.dishes.first : nil) }
            .sorted { ($0.offset, $0.dish ?? "", $0.text) < ($1.offset, $1.dish ?? "", $1.text) }
        return (rows, unplaced)
    }
    var body: some View {
        let merged = merged
        if !merged.rows.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Shared timeline").font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted)
                TurnInBannerText("One timeline for the whole cook")
                ForEach(merged.rows.indices, id: \.self) { index in
                    let row = merged.rows[index]
                    HStack(alignment: .top, spacing: 10) {
                        Text(RecipeReferenceTimeline.relative(row.offset)).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.emberText)
                        VStack(alignment: .leading, spacing: 2) {
                            if let dish = row.dish { Text(dish).font(PitTechFont.bodySmall).foregroundStyle(PitTechStyle.inkMuted) }
                            Text(row.text).font(PitTechFont.body)
                        }
                    }
                }
                if merged.unplaced { Text("One dish's story couldn't be placed on the session clock; its own timeline is in its story below.").font(PitTechFont.body).foregroundStyle(PitTechStyle.inkMuted) }
            }.accessibilityIdentifier("public-session-timeline")
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
            if !caption.isEmpty { Text(caption).font(PitTechFont.monoMeta).foregroundStyle(PitTechStyle.inkMuted) }
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
