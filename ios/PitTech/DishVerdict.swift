import SwiftUI

@MainActor extension CookStore {
    func dishVerdict(cookId: String, dishId: String) -> (rating: Double?, again: Bool?, notes: String) {
        let rows = library.results.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && $0["dishId"]?.stringValue == dishId }
        return ((rows.first { $0["resultType"]?.stringValue == "overall_rating" } ?? rows.first { $0["resultType"]?.stringValue == "overall" })?["numericValue"]?.numberValue,
            rows.first { $0["resultType"]?.stringValue == "cook_again" }?["numericValue"]?.numberValue.map { $0 == 1 },
            rows.first { $0["resultType"]?.stringValue == "result_notes" }?["textValue"]?.stringValue ?? "")
    }
    func saveDishVerdict(cookId: String, dishId: String, rating: Double?, again: Bool?, notes: String) throws {
        guard library.dishes.contains(where: { $0.objectValue?["id"]?.stringValue == dishId && $0.objectValue?["cookId"]?.stringValue == cookId }),
              rating.map({ $0.isFinite && (1...5).contains($0) }) != false else { throw PitTechArchiveError.invalidArchive }
        let prior = library, now = Date.now.timeIntervalSince1970 * 1000
        for (type, value) in [("overall_rating", rating), ("cook_again", again.map { $0 ? 1.0 : 0.0 })] {
            library.results.removeAll { $0.objectValue?["cookId"]?.stringValue == cookId && $0.objectValue?["dishId"]?.stringValue == dishId && ($0.objectValue?["resultType"]?.stringValue == type || type == "overall_rating" && $0.objectValue?["resultType"]?.stringValue == "overall") }
            if let value { library.results.append(.object(["id": .string("\(cookId):\(dishId):\(type)"), "cookId": .string(cookId), "dishId": .string(dishId), "resultType": .string(type), "numericValue": .number(value), "unit": type == "overall_rating" ? .string("stars") : .null, "recordedAtUtcMillis": .number(now)])) }
        }
        library.results.removeAll { $0.objectValue?["cookId"]?.stringValue == cookId && $0.objectValue?["dishId"]?.stringValue == dishId && $0.objectValue?["resultType"]?.stringValue == "result_notes" }
        if !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { library.results.append(.object(["id": .string("\(cookId):\(dishId):notes"), "cookId": .string(cookId), "dishId": .string(dishId), "resultType": .string("result_notes"), "textValue": .string(notes.trimmingCharacters(in: .whitespacesAndNewlines)), "recordedAtUtcMillis": .number(now)])) }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
}

struct DishVerdictSheet: View {
    @Environment(CookStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let cookId: String
    let dish: NoteDishRecord
    @State private var rating: Double?
    @State private var again: Bool?
    @State private var notes = ""
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section { Text("Results are optional and can be edited later. Skipping a rating is different from a poor result.").font(.subheadline) }
                Section("How did it turn out?") {
                    Picker("Rating", selection: $rating) { Text("Not rated").tag(Double?.none); ForEach(1...5, id: \.self) { Text("\($0) / 5").tag(Double?.some(Double($0))) }; if let rating, rating.rounded() != rating { Text("\(rating.formatted()) / 5").tag(Double?.some(rating)) } }
                    Picker("Cook again?", selection: $again) { Text("Not decided").tag(Bool?.none); Text("Yes").tag(Bool?.some(true)); Text("No").tag(Bool?.some(false)) }
                    TextEditor(text: $notes).frame(minHeight: 100).accessibilityLabel("Result notes")
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }.navigationTitle(dish.name).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Save") { do { try store.saveDishVerdict(cookId: cookId, dishId: dish.id, rating: rating, again: again, notes: notes); dismiss() } catch { self.error = error.localizedDescription } } }
                }
                .onAppear { let saved = store.dishVerdict(cookId: cookId, dishId: dish.id); rating = saved.rating; again = saved.again; notes = saved.notes }
        }
    }
}
