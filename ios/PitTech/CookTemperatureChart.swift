import SwiftUI
import Charts

private struct TargetLine: Identifiable {
    let id: String; let start: Double; let end: Double; let value: Double; let label: String; let historical: Bool
}
struct CookTemperatureChart: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    var onMarker: (TimelineEntry) -> Void
    @State private var clockMode = false
    @State private var hidden: Set<String> = []
    @State private var window = 240.0
    @State private var scrollStart = 0.0
    @State private var follow = true
    @State private var selected: Double?
    @State private var markerFilter = "All"
    @State private var comparisonCook = ""
    @State private var comparisonChannel = ""
    @State private var currentChannel = ""
    @State private var alignment = "food_on"
    @State private var currentDish = ""
    @State private var sourceDish = ""
    @State private var referenceMessage: String?
    @State private var guideData: [NativeAlignedGuide] = []
    @State private var referenceTimes: [String: Double] = [:]
    @State private var referenceMatches: [String: String] = [:]
    @State private var referenceSeriesMatches: [String: String] = [:]
    private var cook: CookRecord? { store.library.cooks.first { $0.id == cookId } }
    private var samples: [TemperatureSample] { store.readings(for: cookId) }
    private var sourceSamples: [TemperatureSample] { store.readings(for: comparisonCook) }
    private var channels: [String] { Array(Set(samples.filter { eligible($0, dish: currentDish) }.map(\.seriesId))).sorted() }
    private var sourceChannels: [String] { Array(Set(sourceSamples.filter { eligible($0, dish: sourceDish) }.map(\.seriesId))).sorted() }
    private func scopedEvents(_ id: String, dish: String) -> [TimelineEntry] {
        store.library.events.filter { row in
            guard let o = row.objectValue, o["cookId"]?.stringValue == id else { return false }
            if dish.isEmpty { return true }
            let metadata = o["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) }
            return o["dishId"]?.stringValue == dish || metadata?.dishIds.contains(dish) == true || o["dishId"]?.stringValue == nil && metadata?.dishIds.isEmpty != false && ["food_on", "wrap", "remove", "rest_start", "hold_start", "dish_done"].contains(metadata?.action ?? o["eventType"]?.stringValue ?? "")
        }.compactMap(TimelineEntry.init(json:))
    }
    private var events: [TimelineEntry] { comparing ? scopedEvents(cookId, dish: currentDish) + scopedEvents(comparisonCook, dish: sourceDish) : store.events(for: cookId) }
    private func previous(_ event: TimelineEntry) -> Bool { store.library.events.contains { $0.objectValue?["id"]?.stringValue == event.id && $0.objectValue?["cookId"]?.stringValue == comparisonCook } }
    private func markerX(_ event: TimelineEntry) -> Double { x(event.occurredAt, reference: comparing && previous(event)) }
    @State private var showingOriginal = false
    private func openMarker(_ event: TimelineEntry) { if comparing && previous(event) { showingOriginal = true } else { onMarker(event) } }
    private var unit: String { store.temperatureUnit }
    private var comparing: Bool { !comparisonCook.isEmpty && !currentChannel.isEmpty && !comparisonChannel.isEmpty && comparisonAnchor != nil && currentAnchor != nil && compatibleChannels }
    private var compatibleChannels: Bool {
        guard let current = samples.first(where: { $0.seriesId == currentChannel }), let source = sourceSamples.first(where: { $0.seriesId == comparisonChannel }) else { return false }
        func pit(_ sample: TemperatureSample) -> Bool { ["pit", "pit_ambient", "ambient_temperature", "setpoint"].contains(sample.measurementType) }
        return pit(current) == pit(source)
    }
    private func actionAnchor(_ id: String, channel: String, rows: [TemperatureSample], action: String) -> Date? {
        let selectedDish = id == cookId ? currentDish : sourceDish
        let dish = selectedDish.isEmpty ? rows.first { $0.seriesId == channel }?.dishId : selectedDish
        return store.library.actions(for: id).filter { $0.action == action && ($0.dishIds.isEmpty || dish.map($0.dishIds.contains) == true) }.min(by: { $0.at < $1.at }).map { Date(timeIntervalSince1970: $0.at / 1_000) }
    }
    private var currentAnchor: Date? { actionAnchor(cookId, channel: currentChannel, rows: samples, action: alignment) }
    private var comparisonAnchor: Date? { actionAnchor(comparisonCook, channel: comparisonChannel, rows: sourceSamples, action: alignment) }
    private var defaultAnchor: Date {
        if store.timingMode(cookId) == "explicit_phases", let first = store.library.actions(for: cookId).filter({ $0.action == "food_on" }).min(by: { $0.at < $1.at }) { return Date(timeIntervalSince1970: first.at / 1_000) }
        return cook?.startedAt ?? .now
    }
    private func x(_ date: Date, reference: Bool = false) -> Double {
        if clockMode && !comparing { return date.timeIntervalSince1970 / 60 }
        let anchor = comparing ? (reference ? comparisonAnchor! : currentAnchor!) : defaultAnchor
        return date.timeIntervalSince(anchor) / 60
    }
    private var guideRefreshKey: [String] {
        store.library.companionRecords.compactMap { value in
            guard let row = value.objectValue, row["cookId"]?.stringValue == cookId, ["recipe_attempt", "guide_presentation"].contains(row["kind"]?.stringValue ?? "") else { return nil }
            return "\(row["id"]?.stringValue ?? ""):\(row["updated"]?.numberValue ?? 0):\(row["payload"]?.objectValue?["guide"]?.objectValue?["revisionId"]?.stringValue ?? "")"
        } + store.library.actions(for: cookId).filter { $0.action == "food_on" }.map { "\($0.id):\($0.at):\($0.dishIds.joined(separator: ","))" }
    }
    private func refreshGuides() {
        guideData = store.alignedGuides(cookId)
        referenceTimes = guideData.reduce(into: [:]) { $0.merge($1.originalTimes, uniquingKeysWith: { a, _ in a }) }
        referenceMatches = guideData.reduce(into: [:]) { $0.merge($1.matches, uniquingKeysWith: { a, _ in a }) }
        referenceSeriesMatches = [:]
        for sample in guideData.flatMap(\.readings) { if let matched = referenceMatches[sample.id] { referenceSeriesMatches[sample.seriesId] = matched } }
    }
    private var plottedSamples: [TemperatureSample] { (comparing ? samples.filter { $0.seriesId == currentChannel } + sourceSamples.filter { $0.seriesId == comparisonChannel } : samples) + guideData.flatMap(\.readings) }
    private func plotX(_ sample: TemperatureSample) -> Double { x(sample.measuredAt, reference: sample.cookId != cookId && !sample.id.hasPrefix("guide:")) }
    private var gaps: [Date] { (store.events(for: cookId) + store.events(for: comparisonCook)).filter { $0.eventType == "connection_gap" }.map(\.occurredAt) }
    private var segments: [[TemperatureSample]] { Dictionary(grouping: plottedSamples, by: \.cookId).flatMap { id, rows in
        let gaps = id.hasPrefix("guide:") ? guideData.filter { $0.readings.first?.cookId == id }.flatMap(\.gaps) : store.events(for: id).filter { $0.eventType == "connection_gap" }.map(\.occurredAt)
        return CookChartMath.segments(rows, gaps: gaps)
    } }
    private var points: [NativePlotPoint] { segments.filter { !hidden.contains($0[0].seriesId) }.flatMap { rows in CookChartMath.reduced(rows).map { NativePlotPoint(id: $0.id, reading: $0, segmentId: rows[0].id, segmentSize: rows.count) } } }
    private var fullStart: Double { min((plottedSamples.map(plotX) + events.map { markerX($0) }).min() ?? 0, x(cook?.startedAt ?? .now)) }
    private var fullEnd: Double { max(fullStart + 1, (plottedSamples.map(plotX) + events.map { markerX($0) }).max() ?? fullStart + 1) }
    private var span: Double { min(max(1, window), fullEnd - fullStart) }
    private var visibleStart: Double { min(max(fullStart, scrollStart), fullEnd - span) }
    private var yRange: ClosedRange<Double> {
        let values = points.map { CookChartMath.value($0.reading, unit: unit) } + targets.map(\.value)
        let low = floor(((values.min() ?? 0) - 5) / 10) * 10, high = ceil(((values.max() ?? (unit == "C" ? 150 : 300)) + 5) / 10) * 10
        return low...max(low + 10, high)
    }
    private var markers: [TimelineEntry] {
        events.filter { event in
            switch markerFilter {
            case "Photos": return store.library.photos.contains { $0.objectValue?["eventId"]?.stringValue == event.id } || event.eventType == "photo"
            case "Actions": return event.metadata?.action != nil || CookNoteMetadata.actions.contains(event.eventType)
            case "Notes": return event.eventType == "note"
            default: return true
            }
        }
    }
    private var clusters: [[TimelineEntry]] { CookChartMath.clusters(markers, x: { markerX($0) }, start: visibleStart, span: span) }
    private var targets: [TargetLine] {
        func eligibleTarget(_ row: JSONValue) -> Bool {
            guard let o = row.objectValue, let id = o["cookId"]?.stringValue else { return false }
            if !comparing { return id == cookId }
            return id == cookId && (o["dishId"]?.stringValue == currentDish || o["dishId"]?.stringValue == nil) || id == comparisonCook && (o["dishId"]?.stringValue == sourceDish || o["dishId"]?.stringValue == nil)
        }
        let rows = store.library.targets.filter(eligibleTarget)
        var lines = rows.compactMap { row -> TargetLine? in
            guard let o = row.objectValue, let id = o["id"]?.stringValue, let value = o["value"]?.numberValue, let rawUnit = o["unit"]?.stringValue, value.isFinite else { return nil }
            return TargetLine(id: id, start: fullStart, end: fullEnd, value: normalized(value, rawUnit), label: (o["cookId"]?.stringValue == comparisonCook ? "Previous cook · " : "This cook · ") + (o["targetType"]?.stringValue ?? "Target"), historical: false)
        }
        let history = store.library.targetHistory.filter { eligibleTarget($0) && $0.objectValue?["targetType"]?.stringValue?.hasPrefix("removed_dish_") != true }
        for row in history {
            guard let o = row.objectValue, let id = o["id"]?.stringValue, let value = o["value"]?.numberValue, let rawUnit = o["unit"]?.stringValue, let at = o["effectiveAt"]?.numberValue else { continue }
            let next = history.compactMap { other -> Double? in guard let v = other.objectValue, v["cookId"] == o["cookId"], v["dishId"] == o["dishId"], v["targetType"] == o["targetType"], let time = v["effectiveAt"]?.numberValue, time > at else { return nil }; return time }.min()
            lines.append(TargetLine(id: id, start: x(Date(timeIntervalSince1970: at / 1_000), reference: o["cookId"]?.stringValue == comparisonCook), end: next.map { x(Date(timeIntervalSince1970: $0 / 1_000), reference: o["cookId"]?.stringValue == comparisonCook) } ?? fullEnd, value: normalized(value, rawUnit), label: (o["cookId"]?.stringValue == comparisonCook ? "Previous cook · " : "This cook · ") + (o["targetType"]?.stringValue ?? "Target history"), historical: true))
        }
        return lines
    }
    private func normalized(_ value: Double, _ rawUnit: String) -> Double { let f = rawUnit.contains("C") ? value * 9 / 5 + 32 : value; return unit == "C" ? (f - 32) * 5 / 9 : f }
    private func color(_ id: String) -> Color {
        let key = referenceSeriesMatches[id] ?? id
        let colors: [Color] = [.orange, .blue, .purple, .green, .pink, .teal]; return colors[key.utf8.reduce(0) { ($0 * 31 + Int($1)) % 997 } % colors.count]
    }
    private func channelLabel(_ id: String, rows: [TemperatureSample]) -> String {
        guard let r = rows.last(where: { $0.seriesId == id }) else { return "Choose channel" }
        let dish = store.dishRecords(r.cookId).first { $0.id == r.dishId }?.name
        return [r.probeName, dish, r.measurementType, r.source, String((r.probeId ?? r.id).suffix(6))].compactMap { $0 }.joined(separator: " · ")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Temperature (°\(unit))").font(.title2.bold()).accessibilityIdentifier("chart-y-axis")
            controls
            if store.guideState(cookId).visible && guideData.flatMap(\.readings).isEmpty { Text("Guide curves wait for actual Food on. Sources without temperatures show instructions only.").font(.caption) }
            temperaturePlot
            Text("Touch and hold the chart to scrub; swipe to scroll. The slider also selects recorded temperatures.").font(.caption).foregroundStyle(.secondary)
            Text(comparing ? "Time since \(CookStore.actionLabel(alignment)) (min)" : clockMode ? "Clock time · local date and time" : store.timingMode(cookId) == "explicit_phases" && store.library.actions(for: cookId).contains(where: { $0.action == "food_on" }) ? "Time since Food on (min) · negative time is preparation" : "Time since session start (min)").font(.caption).accessibilityIdentifier("chart-x-axis")
            channelControls
            if !referenceTimes.isEmpty { Text("Author curves are dashed. Manual source measurements remain individual points. Each guide is aligned to that dish's actual Food on.").font(.caption).accessibilityIdentifier("guide-chart-legend") }
            if let selected { selectionDetails(selected) }
            Slider(value: Binding(get: { selected ?? visibleStart }, set: { selected = $0 }), in: visibleStart...max(visibleStart + 0.01, visibleStart + span)).accessibilityLabel("Scrub recorded temperatures")
            phaseBands
            Picker("Annotations", selection: $markerFilter) { ForEach(["All", "Notes", "Photos", "Actions"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
            ForEach(clusters.indices, id: \.self) { index in
                let group = clusters[index]
                if group.count > 1 { Text("\(group.count) entries near \(group[0].occurredAt.formatted(date: .abbreviated, time: .shortened))").font(.caption.bold()) }
                ForEach(group) { event in Button { openMarker(event) } label: { Label("\(event.occurredAt.formatted(date: .omitted, time: .shortened)) · \(event.title)", systemImage: "note.text") }.accessibilityIdentifier("chart-marker-" + event.id) }
            }
            ForEach(targets) { target in Text("\(target.label): \(target.value) °\(unit)").font(.caption) }
            if !targets.isEmpty { Text("Dashed lines show current/reference targets. Solid lines show recorded target history from its effective time.").font(.caption).foregroundStyle(.secondary) }
            comparisonControls
        }.sheet(isPresented: $showingOriginal) { NavigationStack { CookDetailView(cookId: comparisonCook) } }
        .onAppear { currentDish = store.dishRecords(cookId).first?.id ?? ""; refreshGuides(); restoreReference(); reset() }
        .onChange(of: plottedSamples.last?.id) { _, _ in if follow { scrollStart = fullEnd - span } }
        .onChange(of: clockMode) { _, _ in reset() }
        .onChange(of: comparisonCook) { _, _ in if !sourceChannels.contains(comparisonChannel) { comparisonChannel = "" }; if !store.dishRecords(comparisonCook).contains(where: { $0.id == sourceDish }) { sourceDish = store.dishRecords(comparisonCook).first?.id ?? "" }; reset() }
        .onChange(of: currentDish) { _, _ in if !channels.contains(currentChannel) { currentChannel = "" }; restoreReference() }
        .onChange(of: sourceDish) { _, _ in if !sourceChannels.contains(comparisonChannel) { comparisonChannel = "" } }
        .onChange(of: comparing) { _, _ in reset() }
        .onChange(of: guideRefreshKey) { _, _ in refreshGuides() }
        .onChange(of: store.guideState(cookId)) { _, _ in refreshGuides(); reset() }
        .onChange(of: samples.last?.seriesId) { _, _ in refreshGuides() }
    }
    private var controls: some View {
        ScrollView(.horizontal, showsIndicators: false) { HStack {
            Toggle("Clock time", isOn: $clockMode).disabled(comparing)
            Toggle("Follow latest", isOn: $follow).onChange(of: follow) { _, value in if value { scrollStart = fullEnd - span } }
            Button("Zoom +") { window = max(1, span / 2); if follow { scrollStart = fullEnd - span } }
            Button("Zoom −") { window = min(fullEnd - fullStart, span * 2); if follow { scrollStart = fullEnd - span } }
            Button("Earlier") { follow = false; scrollStart = max(fullStart, visibleStart - span / 2) }
            Button("Later") { follow = false; scrollStart = min(fullEnd - span, visibleStart + span / 2) }
            Button("Reset", action: reset)
        }.buttonStyle(.bordered).toggleStyle(.button) }
    }
    private var temperaturePlot: some View {
        Chart {
            ForEach(points) { point in
                let r = point.reading
                if r.source == "manual" || point.segmentSize == 1 {
                    PointMark(x: .value("Time", plotX(r)), y: .value("Temperature", CookChartMath.value(r, unit: unit))).foregroundStyle(color(r.seriesId))
                } else {
                    LineMark(x: .value("Time", plotX(r)), y: .value("Temperature", CookChartMath.value(r, unit: unit)), series: .value("Segment", point.segmentId)).foregroundStyle(color(r.seriesId)).lineStyle(StrokeStyle(lineWidth: 2, dash: r.id.hasPrefix("guide:") ? [7, 5] : []))
                }
            }
            ForEach(targets) { target in
                RuleMark(xStart: .value("Effective time", target.start), xEnd: .value("Until", target.end), y: .value("Target", target.value)).foregroundStyle(.secondary.opacity(0.65)).lineStyle(StrokeStyle(lineWidth: 1, dash: target.historical ? [] : [5, 4]))
            }
            ForEach(clusters.indices, id: \.self) { index in
                let group = clusters[index]
                RuleMark(x: .value("Note time", markerX(group[0]))).foregroundStyle(.purple.opacity(0.6)).lineStyle(StrokeStyle(lineWidth: 1, dash: [2, 3]))
                    .annotation(position: .top) { Button(group.count > 1 ? "\(group.count) notes" : group[0].title) { openMarker(group[0]) }.font(.caption2).lineLimit(1) }
            }
        }.chartXScale(domain: fullStart...fullEnd).chartYScale(domain: yRange)
            .chartXAxis { AxisMarks(values: .automatic(desiredCount: 5)) { value in AxisGridLine(); AxisTick(); AxisValueLabel { if let number = value.as(Double.self) { Text(clockMode && !comparing ? Date(timeIntervalSince1970: number * 60).formatted(date: .abbreviated, time: .shortened) : number.formatted(.number.precision(.fractionLength(0...1)))) } } } }
            .chartYAxis { AxisMarks(position: .leading, values: .automatic(desiredCount: 5)) { value in AxisGridLine(); AxisTick(); AxisValueLabel { if let number = value.as(Double.self) { Text(number.formatted(.number.precision(.fractionLength(0)))) } } } }
            .chartXSelection(value: $selected).chartScrollableAxes(.horizontal).chartXVisibleDomain(length: span).chartScrollPosition(x: $scrollStart)
            .chartGesture { chart in
                // A page swipe can begin immediately. Selection starts only after an explicit hold.
                LongPressGesture(minimumDuration: 0.3)
                    .sequenced(before: DragGesture(minimumDistance: 0))
                    .onChanged { phase in
                        if case .second(true, let drag?) = phase { chart.selectXValue(at: drag.location.x) }
                    }
            }
            .frame(height: 300).accessibilityLabel("Time and temperature axes, recorded samples with gaps, targets and cook notes").accessibilityIdentifier("temperature-plot")
    }
    private var channelControls: some View {
        VStack(alignment: .leading) {
            ForEach(Array(Set(plottedSamples.map(\.seriesId))).sorted(), id: \.self) { id in
                HStack {
                    Button { if hidden.contains(id) { hidden.remove(id) } else { hidden.insert(id) } } label: { Label(channelLabel(id, rows: plottedSamples), systemImage: hidden.contains(id) ? "eye.slash" : "eye").foregroundStyle(color(id)) }
                    Spacer(); Button("Only") { hidden = Set(plottedSamples.map(\.seriesId)).subtracting([id]) }
                }
                let latest = segments.filter { $0[0].seriesId == id }.max(by: { $0.last!.measuredAt < $1.last!.measuredAt }) ?? []
                if !hidden.contains(id), let slope = CookChartMath.slope(Array(latest.suffix(60)), unit: unit) { Text("Recorded trend \(slope.formatted(.number.precision(.fractionLength(2)))) °\(unit)/min" + (abs(slope) < (unit == "C" ? 0.055 : 0.1) ? " · flat over this recorded interval" : "")).font(.caption) }
            }
            if !hidden.isEmpty { Button("Show all channels") { hidden = [] } }
        }
    }
    private func selectionDetails(_ at: Double) -> some View {
        VStack(alignment: .leading) {
            ForEach(Array(Set(plottedSamples.map(\.seriesId))).sorted().filter { !hidden.contains($0) }, id: \.self) { id in
                if let r = plottedSamples.filter({ $0.seriesId == id && $0.qualityStatus == "valid" }).min(by: { abs(plotX($0) - at) < abs(plotX($1) - at) }) {
                    Text("\(channelLabel(id, rows: plottedSamples)): \(CookChartMath.value(r, unit: unit).formatted()) °\(unit) · recorded \(String(r.value)) \(r.unit) · \(r.measuredAt.formatted(date: .abbreviated, time: .standard)) · \(r.source)").font(.caption)
                    if let original = referenceTimes[r.id] { Text("Author source time · " + RecipeReferenceTimeline.time(original)).font(.caption) }
                    if let matched = referenceMatches[r.id] { Text("Matched to " + channelLabel(matched, rows: samples)).font(.caption) }
                    if ["receipt_time", "receipt", "cloud_receipt"].contains(r.timestampBasis) { Text("Receipt time · device sample age unknown").font(.caption).foregroundStyle(.secondary) }
                }
            }
        }.padding(10).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
    }
    private var phaseBands: some View {
        VStack(alignment: .leading) {
            if store.timingMode(cookId) == "explicit_phases" {
                ForEach(store.phases(cookId)) { dish in
                    Text(dish.name + " · physical phases").font(.caption.bold())
                    Chart(dish.spans) { span in RectangleMark(xStart: .value("Start", x(Date(timeIntervalSince1970: span.start / 1_000))), xEnd: .value("End", x(Date(timeIntervalSince1970: span.end / 1_000))), yStart: .value("Lane", 0), yEnd: .value("Lane", 1)).foregroundStyle(color(span.phase).opacity(0.35)) }
                        .chartXScale(domain: visibleStart...(visibleStart + self.span)).chartXAxis(.hidden).chartYAxis(.hidden).frame(height: 18)
                    Text(dish.spans.map { CookPhaseEngine.label($0.phase) }.reduce(into: [String]()) { if $0.last != $1 { $0.append($1) } }.joined(separator: " → ")).font(.caption2)
                }
            }
        }
    }
    private var comparisonControls: some View {
        VStack(alignment: .leading) {
            Text("Compare a prior cook").font(.headline)
            Picker("Current dish", selection: $currentDish) { Text("Whole cook").tag(""); ForEach(store.dishRecords(cookId)) { Text($0.name).tag($0.id) } }
            Picker("Reference cook", selection: $comparisonCook) { Text("None").tag(""); ForEach(store.completedCooks.filter { $0.id != cookId }) { Text($0.title).tag($0.id) } }
            if !comparisonCook.isEmpty {
                Picker("Reference dish", selection: $sourceDish) { Text("Whole cook").tag(""); ForEach(store.dishRecords(comparisonCook)) { Text($0.name).tag($0.id) } }
                Picker("Current channel", selection: $currentChannel) { Text("Choose channel").tag(""); ForEach(channels, id: \.self) { Text(channelLabel($0, rows: samples)).tag($0) } }
                Picker("Reference channel", selection: $comparisonChannel) { Text("Choose channel").tag(""); ForEach(sourceChannels, id: \.self) { Text(channelLabel($0, rows: sourceSamples)).tag($0) } }
                Picker("Align at", selection: $alignment) { Text("Food on").tag("food_on"); Text("Wrap").tag("wrap") }
                if !comparing { Text("Choose matching food or pit channels and log the selected action for both dish scopes to align recorded curves.").font(.caption) }
                else {
                    Text("Current and reference · minutes since \(CookStore.actionLabel(alignment)) · separate stable channels").font(.caption)
                    if !currentDish.isEmpty && !sourceDish.isEmpty { Button("Use this reference") {
                        do { try store.saveReference(cookId, reference: NativeCookReference(dishId: currentDish, sourceCookId: comparisonCook, sourceDishId: sourceDish, currentProbe: samples.first { $0.seriesId == currentChannel }?.probeName ?? "", sourceProbe: sourceSamples.first { $0.seriesId == comparisonChannel }?.probeName ?? "", anchor: alignment, currentSeriesId: currentChannel, sourceSeriesId: comparisonChannel)); referenceMessage = "Reference saved." }
                        catch { referenceMessage = error.localizedDescription }
                    } }
                }
                if let referenceMessage { Text(referenceMessage).font(.caption) }
            }
        }
    }
    private func eligible(_ sample: TemperatureSample, dish: String) -> Bool { dish.isEmpty || sample.dishId == dish || sample.dishId == nil && ["pit", "pit_ambient", "ambient_temperature", "setpoint"].contains(sample.measurementType) }
    private func restoreReference() {
        guard let ref = store.savedReference(cookId, dishId: currentDish) else { return }
        comparisonCook = ref.sourceCookId; sourceDish = ref.sourceDishId; alignment = ref.anchor
        let current = samples.filter { eligible($0, dish: currentDish) }, source = store.readings(for: ref.sourceCookId).filter { eligible($0, dish: sourceDish) }
        func key(_ preferred: String?, _ name: String, _ rows: [TemperatureSample]) -> String {
            if let preferred, rows.contains(where: { $0.seriesId == preferred }) { return preferred }
            let ids = Set(rows.filter { $0.probeName == name }.map(\.seriesId)); return ids.count == 1 ? ids.first! : ""
        }
        currentChannel = key(ref.currentSeriesId, ref.currentProbe, current); comparisonChannel = key(ref.sourceSeriesId, ref.sourceProbe, source)
        if ref.currentSeriesId == nil && ref.currentProbe.isEmpty { currentChannel = current.first { $0.dishId == currentDish }?.seriesId ?? "" }
        if ref.sourceSeriesId == nil && ref.sourceProbe.isEmpty { comparisonChannel = source.first { $0.dishId == sourceDish }?.seriesId ?? "" }
    }
    private func reset() { hidden = []; selected = nil; follow = true; window = max(1, fullEnd - fullStart); scrollStart = fullStart }
}
