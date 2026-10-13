import SwiftUI

struct MealDishCard: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    let dish: NoteDishRecord
    let now: Date
    let onLog: (String, String?) -> Void
    @State private var expanded = false
    private var phase: NativeDishPhase? { store.timingMode(cookId) == "explicit_phases" ? store.phases(cookId, now: now).first { $0.id == dish.id } : nil }
    private var last: TimelineEntry? {
        let ids = Set(store.library.events.compactMap(\.objectValue).filter { o in
            guard o["cookId"]?.stringValue == cookId else { return false }
            let metadata = o["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) }
            return o["dishId"]?.stringValue == dish.id || metadata?.dishIds.contains(dish.id) == true
        }.compactMap { $0["id"]?.stringValue })
        return store.events(for: cookId).filter { ids.contains($0.id) }.max { $0.occurredAt < $1.occurredAt }
    }
    private var method: String? {
        store.library.events.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && ($0["dishId"]?.stringValue == dish.id || $0["dishId"]?.stringValue == nil) }.sorted { ($0["occurredAtUtcMillis"]?.numberValue ?? 0) > ($1["occurredAtUtcMillis"]?.numberValue ?? 0) }.compactMap { row -> String? in
            let metadata = row["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) }
            guard metadata?.dishIds.isEmpty != false || metadata?.dishIds.contains(dish.id) == true else { return nil }
            return metadata?.method ?? (metadata?.action == "food_on" ? "smoker" : nil)
        }.first
    }
    private var reading: TemperatureSample? { store.readings(for: cookId).filter { $0.dishId == dish.id && $0.qualityStatus == "valid" && $0.value.isFinite && ["°F", "°C"].contains($0.unit) && ["food", "food_probe", "internal_temperature"].contains($0.measurementType) }.max { $0.measuredAt < $1.measuredAt } }
    private var telemetryReadings: [TemperatureSample] {
        let probes = store.library.probes.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["assignedDishId"]?.stringValue == dish.id && $0["measurementType"]?.stringValue == "food_probe" && ($0["source"]?.stringValue ?? "").hasPrefix("telemetry:") }
        return probes.compactMap { probe in
            let id = probe["id"]?.stringValue
            let since = store.library.probeAssignments.compactMap(\.objectValue).filter { $0["probeId"]?.stringValue == id && $0["endedAtUtcMillis"]?.numberValue == nil }.compactMap { $0["startedAtUtcMillis"]?.numberValue }.max() ?? 0
            return store.readings(for: cookId).filter { $0.probeId == id && $0.dishId == dish.id && $0.qualityStatus == "valid" && $0.value.isFinite && $0.measuredAt.timeIntervalSince1970 * 1000 >= since }.max { $0.measuredAt < $1.measuredAt }
        }
    }
    private func isLive(_ sample: TemperatureSample) -> Bool {
        let age = now.timeIntervalSince(sample.measuredAt) * 1000
        return store.library.cooks.first(where: { $0.id == cookId })?.status == "active" && sample.sourceDeviceId.map(store.telemetryLiveDevices.contains) == true && age >= 0 && age <= max(45_000, min(900_000, sample.samplingInterval * 3))
    }
    private var reminder: [String: JSONValue]? { store.library.reminderRules.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["dishId"]?.stringValue == dish.id && $0["enabled"]?.boolValue == true }.min { (store.reminderState($0["id"]?.stringValue ?? "")?["dueAtUtcMillis"]?.numberValue ?? Double.greatestFiniteMagnitude) < (store.reminderState($1["id"]?.stringValue ?? "")?["dueAtUtcMillis"]?.numberValue ?? Double.greatestFiniteMagnitude) } }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { expanded.toggle() } label: {
                VStack(alignment: .leading, spacing: 5) {
                    Text(dish.name).font(.headline)
                    Text((store.library.cooks.first { $0.id == cookId }?.isActive == false ? "Last recorded: " : "") + (phase.map { CookPhaseEngine.label($0.phase) } ?? "Status not recorded")).font(.subheadline)
                    if let method { Text("Method · " + (CookingMethod.labels[method] ?? method)).font(.caption) }
                    if let phase, phase.cooking > 0 { Text("Cooking \(Int(phase.cooking / 3_600_000))h \(Int(phase.cooking / 60_000) % 60)m").font(.caption.monospacedDigit()) }
                    if !telemetryReadings.isEmpty {
                        ForEach(telemetryReadings) { sample in
                            Text("\(isLive(sample) ? "Live" : "Last check") · \(CookChartMath.value(sample, unit: store.temperatureUnit), specifier: "%.1f") °\(store.temperatureUnit)").font(.title3)
                            Text("\(sample.probeName) · \(sample.measuredAt.formatted(date: .omitted, time: .shortened))").font(.caption)
                        }
                    } else if let reading {
                        Text("Last check · \(CookChartMath.value(reading, unit: store.temperatureUnit), specifier: "%.1f") °\(store.temperatureUnit)").font(.title3)
                        Text("\(reading.probeName) · \(reading.measuredAt.formatted(date: .omitted, time: .shortened))").font(.caption)
                    }
                    if let last { Text("\(last.title) · \(last.occurredAt.formatted(date: .omitted, time: .shortened))").font(.caption).lineLimit(2) }
                    if let reminder { Text("Reminder · \(reminder["title"]?.stringValue ?? "") · \(reminder["status"]?.stringValue ?? "")").font(.caption) }
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityIdentifier("food-card-" + dish.id)
            if expanded {
                MealActionRow { Button("Note") { onLog("general", nil) }; Button("Temp") { onLog("temperature", nil) }; Button("Photo") { onLog("photo", nil) } }.buttonStyle(.bordered)
                MealRemindersLink(cookId: cookId, dishId: dish.id)
                if store.recipeSource(dish.id) != nil { NavigationLink("Previous cook") { RecipeReferenceView(cookId: cookId, dishId: dish.id) } }
                DisclosureGroup("Preparation and actions") {
                    if let setup = store.library.dishes.first(where: { $0.objectValue?["id"]?.stringValue == dish.id })?.objectValue {
                        Text(setup["prepNotes"]?.stringValue ?? "")
                        ForEach(store.library.ingredients.compactMap(\.objectValue).filter { $0["dishId"]?.stringValue == dish.id }.indices, id: \.self) { index in
                            let ingredient = store.library.ingredients.compactMap(\.objectValue).filter { $0["dishId"]?.stringValue == dish.id }[index]
                            Text("\(ingredient["name"]?.stringValue ?? "") · \(ingredient["amountText"]?.stringValue ?? ingredient["amountValue"]?.numberValue.map { String($0) } ?? "") \(ingredient["amountUnit"]?.stringValue ?? "")").font(.caption)
                        }
                    }
                    if store.library.cooks.contains(where: { $0.id == cookId && $0.isActive }) { ForEach(["food_on", "spritz", "wrap", "remove", "rest_start", "hold_start", "dish_done"], id: \.self) { action in Button(CookStore.actionLabel(action)) { onLog("action", action) } } }
                }
            }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}
extension CookStore {
    var appearance: ColorScheme? {
        switch library.preferences?["themeMode"]?.stringValue?.uppercased() { case "LIGHT": .light; case "DARK": .dark; default: nil }
    }
    func setMealPaused(_ id: String, paused: Bool) {
        guard let index = library.cooks.firstIndex(where: { $0.id == id && $0.isActive }) else { return }
        let desired = paused ? "paused" : "active"
        guard library.cooks[index].status != desired else { return }
        let prior = library, at = Date.now.timeIntervalSince1970 * 1000
        library.events.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(id), "eventType": .string(paused ? "cook_paused" : "cook_resumed"), "title": .string(paused ? "Meal paused" : "Meal resumed"), "occurredAtUtcMillis": .number(at), "recordedAtUtcMillis": .number(at), "timeZoneId": .string(TimeZone.current.identifier), "source": .string("manual"), "createdAtUtcMillis": .number(at), "updatedAtUtcMillis": .number(at)]))
        library.cooks[index].status = paused ? "paused" : "active"; library.cooks[index].updatedAtUtcMillis = Int64(Date.now.timeIntervalSince1970 * 1000); save()
        if saveError != nil { library = prior }
    }
}
