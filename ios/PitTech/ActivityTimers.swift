import Foundation
import SwiftUI

extension PitTechLibrary {
    func validateActivityTimers() throws {
        var ids = Set<String>(), noteIds = Set<String>()
        for row in activityTimers {
            guard let o = row.objectValue, let id = o["id"]?.stringValue, ids.insert(id).inserted,
                  let noteId = o["noteId"]?.stringValue, noteIds.insert(noteId).inserted,
                  let cookId = o["cookId"]?.stringValue, cooks.contains(where: { $0.id == cookId }),
                  let title = o["title"]?.stringValue, !title.isEmpty, let start = o["startedAtUtcMillis"]?.numberValue, start > 0,
                  ["running", "stopped", "logged", "cancelled", "inactive"].contains(o["status"]?.stringValue ?? ""),
                  o["method"]?.stringValue == nil || CookingMethod.labels[o["method"]!.stringValue!] != nil else { throw PitTechArchiveError.invalidArchive }
            if let end = o["stoppedAtUtcMillis"]?.numberValue, end < start { throw PitTechArchiveError.invalidArchive }
            if let duration = o["desiredDurationMillis"]?.numberValue, !(1...2_592_000_000).contains(duration) { throw PitTechArchiveError.invalidArchive }
            if let dishId = o["dishId"]?.stringValue, !dishes.contains(where: { $0.objectValue?["id"]?.stringValue == dishId && $0.objectValue?["cookId"]?.stringValue == cookId }) { throw PitTechArchiveError.invalidArchive }
            if events.contains(where: { $0.objectValue?["id"]?.stringValue == noteId && $0.objectValue?["cookId"]?.stringValue != cookId }) { throw PitTechArchiveError.invalidArchive }
        }
    }
}

extension CookStore {
    func startActivityTimer(cookId: String, dishId: String?, title: String, method: String?, target: Double?) throws {
        guard library.cooks.contains(where: { $0.id == cookId && $0.isActive }), !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw CookNoteError.invalid }
        let prior = library
        library.activityTimers.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId), "dishId": dishId.map(JSONValue.string) ?? .null,
            "title": .string(title), "method": method.map(JSONValue.string) ?? .null, "startedAtUtcMillis": .number(Double(Int64(Date.now.timeIntervalSince1970 * 1000))),
            "noteId": .string(UUID().uuidString.lowercased()), "status": .string("running"), "desiredDurationMillis": target.map(JSONValue.number) ?? .null]))
        do { try library.validateActivityTimers(); save(); if saveError != nil { throw CocoaError(.fileWriteUnknown) } }
        catch { library = prior; throw error }
    }
    func stopActivityTimer(_ id: String, at: Double = Date.now.timeIntervalSince1970 * 1000) throws -> CookNoteDraft {
        guard let index = library.activityTimers.firstIndex(where: { $0.objectValue?["id"]?.stringValue == id }), var o = library.activityTimers[index].objectValue,
              ["running", "stopped"].contains(o["status"]?.stringValue ?? ""), let start = o["startedAtUtcMillis"]?.numberValue,
              let cookId = o["cookId"]?.stringValue, let noteId = o["noteId"]?.stringValue else { throw CookNoteError.invalid }
        let end = Double(Int64(o["stoppedAtUtcMillis"]?.numberValue ?? at))
        guard end >= start else { throw CookNoteError.invalid }
        let prior = library
        o["stoppedAtUtcMillis"] = .number(end); o["status"] = .string("stopped")
        library.activityTimers[index] = .object(o); save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
        return CookNoteDraft(id: noteId, cookId: cookId, dishId: o["dishId"]?.stringValue,
            metadata: CookNoteMetadata(version: 2, method: o["method"]?.stringValue, durationMillis: end - start, startedAtUtcMillis: start, endedAtUtcMillis: end),
            title: o["title"]?.stringValue ?? "Task", occurredAt: end)
    }
    func dismissActivityTimer(_ id: String) throws {
        let prior = library
        library.activityTimers = library.activityTimers.map { row in guard var o = row.objectValue, o["id"]?.stringValue == id else { return row }; o["status"] = .string("cancelled"); return .object(o) }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
}

struct MealActivityTimers: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var expanded = false
    @State private var title = ""
    @State private var method = ""
    @State private var dishId = ""
    @State private var target = ""
    @State private var draft: CookNoteDraft?
    @State private var error: String?
    private var timers: [JSONValue] { store.library.activityTimers.filter { $0.objectValue?["cookId"]?.stringValue == cookId && ["running", "stopped"].contains($0.objectValue?["status"]?.stringValue ?? "") } }
    var body: some View {
        DisclosureGroup("Task timers", isExpanded: $expanded) {
            ForEach(timers.compactMap { $0.objectValue?["id"]?.stringValue }, id: \.self) { id in
                if let o = timers.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue, let start = o["startedAtUtcMillis"]?.numberValue {
                    TurnInBannerText(o["title"]?.stringValue ?? "Task")
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let elapsed = max(0, (o["stoppedAtUtcMillis"]?.numberValue ?? context.date.timeIntervalSince1970 * 1000) - start) / 1000
                        Text("\(Int(elapsed) / 60)m \(Int(elapsed) % 60)s").font(PitTechFont.monoReadout)
                    }
                    HStack {
                        Button(o["status"]?.stringValue == "running" ? "Stop & save duration" : "Save duration") { do { draft = try store.stopActivityTimer(id) } catch { self.error = error.localizedDescription } }
                        Button("Dismiss") { do { try store.dismissActivityTimer(id) } catch { self.error = error.localizedDescription } }
                    }.buttonStyle(.bordered)
                }
            }
            if store.library.cooks.first(where: { $0.id == cookId })?.isActive == true {
                TextField("Task name", text: $title)
                Picker("For", selection: $dishId) { Text("Whole meal").tag(""); ForEach(store.dishRecords(cookId)) { Text($0.name).tag($0.id) } }
                Picker("Method", selection: $method) { Text("Not set").tag(""); ForEach(CookingMethod.order, id: \.self) { Text(CookingMethod.labels[$0]!).tag($0) } }
                TextField("Target minutes (optional)", text: $target).keyboardType(.decimalPad)
                Button("Start timer") { do { try store.startActivityTimer(cookId: cookId, dishId: dishId.isEmpty ? nil : dishId, title: title, method: method.isEmpty ? nil : method, target: Double(target).map { $0 * 60_000 }); title = ""; target = "" } catch { self.error = error.localizedDescription } }
                    .disabled(title.isEmpty || !target.isEmpty && Double(target).map { $0 > 0 && $0 <= 43200 } != true)
            }
            if let error { Text(error).foregroundStyle(.red) }
        }.sheet(item: $draft) { CookNoteComposer(draft: $0) }
    }
}
