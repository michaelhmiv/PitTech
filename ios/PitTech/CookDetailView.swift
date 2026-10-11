import Charts
import SwiftUI

struct CookDetailView: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var section = "Live"
    @State private var showingReading = false
    @State private var showingNote = false
    @State private var showingDish = false
    @State private var showingEditCook = false

    private var cook: CookRecord? { store.library.cooks.first(where: { $0.id == cookId }) }
    private var readings: [TemperatureSample] { store.readings(for: cookId) }
    private var events: [TimelineEntry] { store.events(for: cookId) }

    var body: some View {
        Group {
            if let cook {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(cook.title).font(.largeTitle.bold())
                            Label(cook.isActive ? "Cook in progress" : "Finished cook", systemImage: cook.isActive ? "flame.fill" : "checkmark.circle.fill")
                                .font(.subheadline).foregroundStyle(cook.isActive ? PitTechStyle.accent : PitTechStyle.sage)
                            if !store.dishes(for: cook.id).isEmpty {
                                Text(store.dishes(for: cook.id).joined(separator: " · ")).font(.subheadline).foregroundStyle(.secondary)
                            }
                            HStack {
                                Button("Edit cook") { showingEditCook = true }.buttonStyle(.bordered)
                                Button("Add dish") { showingDish = true }.buttonStyle(.bordered)
                            }
                        }
                        Picker("Cook section", selection: $section) {
                            Text("Live").tag("Live")
                            Text("Timeline").tag("Timeline")
                            Text("Charts").tag("Charts")
                        }
                        .pickerStyle(.segmented)
                        switch section {
                        case "Timeline": timelineContent
                        case "Charts": chartsContent
                        default: liveContent(cook)
                        }
                        if cook.isActive {
                            Button(role: .destructive) { store.finishCook(cook.id) } label: {
                                Label("Finish cook", systemImage: "checkmark.circle")
                                    .frame(maxWidth: .infinity, minHeight: 48)
                            }
                            .buttonStyle(.bordered)
                            .padding(.top, 8)
                        }
                    }
                    .padding(20)
                }
                .background(PitTechStyle.canvas.ignoresSafeArea())
                .navigationTitle("Cook")
                .navigationBarTitleDisplayMode(.inline)
                .sheet(isPresented: $showingReading) { AddReadingSheet(cookId: cook.id) }
                .sheet(isPresented: $showingNote) { AddNoteSheet(cookId: cook.id) }
                .sheet(isPresented: $showingDish) { AddDishSheet(cookId: cook.id) }
                .sheet(isPresented: $showingEditCook) { EditCookSheet(cook: cook) }
            } else {
                ContentUnavailableView("Cook not found", systemImage: "exclamationmark.triangle")
            }
        }
    }

    private func liveContent(_ cook: CookRecord) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionTitle(title: "Live readings", subtitle: "Manual entries and read-only controller samples.")
            if readings.isEmpty {
                ContentUnavailableView("No readings yet", systemImage: "thermometer.medium", description: Text("Add a manual temperature or connect a read-only grill monitor."))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            } else {
                ForEach(readings.reversed()) { reading in ReadingCard(reading: reading) }
            }
            if let notes = cook.notes, !notes.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("COOK NOTES").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                    Text(notes).font(.subheadline)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.white, in: RoundedRectangle(cornerRadius: 14))
            }
            HStack {
                Button { showingReading = true } label: { Label("Add temperature", systemImage: "thermometer") }
                    .buttonStyle(.borderedProminent)
                Button { showingNote = true } label: { Label("Add note", systemImage: "note.text") }
                    .buttonStyle(.bordered)
            }
            Text("Started \(cook.startedAt.formatted(date: .abbreviated, time: .shortened))")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private var timelineContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Timeline", subtitle: "Newest activity first.")
            let timeline = events.map { CookTimelineRow(id: $0.id, title: $0.title, detail: $0.details, date: $0.occurredAt, source: $0.source) }
                + readings.map { CookTimelineRow(id: $0.id, title: "\($0.probeName): \($0.value.formatted())°\($0.unit)", detail: "\($0.qualityStatus.capitalized) · \($0.source)", date: $0.measuredAt, source: $0.source) }
            if timeline.isEmpty {
                ContentUnavailableView("No timeline entries", systemImage: "list.bullet.rectangle", description: Text("Add a note or temperature to record the cook."))
                    .frame(maxWidth: .infinity).padding(.vertical, 18)
            } else {
                ForEach(timeline.sorted { $0.date > $1.date }) { item in
                    HStack(alignment: .top, spacing: 12) {
                        Circle().fill(PitTechStyle.accent).frame(width: 9, height: 9).padding(.top, 6)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.title).font(.subheadline.weight(.semibold))
                            if !item.detail.isEmpty { Text(item.detail).font(.caption).foregroundStyle(.secondary) }
                            Text(item.date.formatted(date: .abbreviated, time: .shortened)).font(.caption2).foregroundStyle(.tertiary)
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(.white, in: RoundedRectangle(cornerRadius: 14))
                }
            }
            Button { showingNote = true } label: { Label("Add timeline note", systemImage: "plus") }
                .buttonStyle(.bordered)
        }
    }

    private var chartsContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionTitle(title: "Temperature chart", subtitle: "Recorded values only; gaps stay visible.")
            if readings.isEmpty {
                ContentUnavailableView("No readings to chart", systemImage: "chart.xyaxis.line")
                    .frame(maxWidth: .infinity).padding(.vertical, 18)
            } else {
                Chart(readings) { reading in
                    LineMark(x: .value("Time", reading.measuredAt), y: .value("Temperature", reading.value))
                        .foregroundStyle(by: .value("Probe", reading.probeName))
                        .interpolationMethod(.linear)
                    PointMark(x: .value("Time", reading.measuredAt), y: .value("Temperature", reading.value))
                        .foregroundStyle(by: .value("Probe", reading.probeName))
                }
                .chartYAxisLabel("Temperature")
                .frame(height: 260)
                .padding(14)
                .background(.white, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }
}

struct ReadingCard: View {
    let reading: TemperatureSample

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(reading.probeName).font(.headline)
                Text("\(reading.source.capitalized) · \(reading.measuredAt.formatted(date: .omitted, time: .shortened))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text("\(reading.value.formatted())°\(reading.unit)")
                .font(.title2.weight(.bold).monospacedDigit())
                .foregroundStyle(reading.qualityStatus == "valid" ? PitTechStyle.ink : .secondary)
        }
        .padding(16)
        .background(.white, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct CookTimelineRow: Identifiable {
    let id: String
    let title: String
    let detail: String
    let date: Date
    let source: String
}

struct AddReadingSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var probeName = "Pit"
    @State private var value = ""
    @State private var measurementType = "pit"

    var body: some View {
        NavigationStack {
            Form {
                TextField("Probe name", text: $probeName)
                Picker("Reading", selection: $measurementType) {
                    Text("Grill / pit").tag("pit")
                    Text("Food probe").tag("food")
                }
                TextField("Temperature", text: $value).keyboardType(.decimalPad)
                Text("°\(store.temperatureUnit)")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .navigationTitle("Add temperature")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let number = Double(value), number.isFinite else { return }
                        store.addReading(cookId: cookId, probeName: probeName, value: number, unit: store.temperatureUnit, measurementType: measurementType)
                        dismiss()
                    }
                    .disabled(Double(value)?.isFinite != true)
                }
            }
        }
    }
}

struct AddNoteSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var title = "Cook note"
    @State private var details = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Title", text: $title)
                TextField("Note", text: $details, axis: .vertical).lineLimit(3...8)
            }
            .navigationTitle("Add timeline note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { store.addEvent(cookId: cookId, title: title, details: details); dismiss() }
                }
            }
        }
    }
}

struct AddDishSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Dish name", text: $name)
            }
            .navigationTitle("Add dish")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { store.addDish(cookId: cookId, name: name); dismiss() }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

struct EditCookSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cook: CookRecord
    @State private var title: String
    @State private var notes: String

    init(cook: CookRecord) {
        self.cook = cook
        _title = State(initialValue: cook.title)
        _notes = State(initialValue: cook.notes ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Cook name", text: $title)
                Section("Notes") {
                    TextField("Add details to remember", text: $notes, axis: .vertical).lineLimit(3...8)
                }
            }
            .navigationTitle("Edit cook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { store.updateCook(cook.id, title: title, notes: notes); dismiss() }
                }
            }
        }
    }
}
