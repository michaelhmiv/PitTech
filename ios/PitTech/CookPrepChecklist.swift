import SwiftUI

struct NativeChecklistItem: Codable, Identifiable {
    var id = UUID().uuidString.lowercased()
    var text: String
    var category = "supplies"
    var done = false
}
struct NativePrepChecklist: Codable {
    var version = 1
    var items: [NativeChecklistItem]
}
@MainActor extension CookStore {
    func prepChecklist(_ id: String) -> NativePrepChecklist? {
        guard let payload = library.companionRecords.first(where: { $0.objectValue?["id"]?.stringValue == "checklist:" + id })?.objectValue?["payload"]?.stringValue else { return nil }
        return try? JSONDecoder().decode(NativePrepChecklist.self, from: Data(payload.utf8))
    }
    func createPrepChecklist(_ id: String) {
        guard library.cooks.contains(where: { $0.id == id }), !library.companionRecords.contains(where: { $0.objectValue?["id"]?.stringValue == "checklist:" + id }) else { return }
        let ingredients = library.ingredients.filter { $0.objectValue?["cookId"]?.stringValue == id }.compactMap { $0.objectValue?["name"]?.stringValue }.map { NativeChecklistItem(text: $0, category: "ingredients") }
        let prep = library.dishes.filter { $0.objectValue?["cookId"]?.stringValue == id }.compactMap { row -> NativeChecklistItem? in
            guard let o = row.objectValue, let notes = o["prepNotes"]?.stringValue, !notes.isEmpty else { return nil }
            return NativeChecklistItem(text: (o["name"]?.stringValue ?? "Dish") + ": " + notes, category: "prep")
        }
        let items = ingredients + ["Fuel", "Thermometer", "Heat-resistant gloves", "Wrapping material (if using)"].map { NativeChecklistItem(text: $0) } + prep
        try? savePrepChecklist(id, NativePrepChecklist(items: items))
    }
    func savePrepChecklist(_ id: String, _ checklist: NativePrepChecklist) throws {
        guard checklist.version == 1, checklist.items.count <= 200, Set(checklist.items.map(\.id)).count == checklist.items.count, checklist.items.allSatisfy({ !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw CookNoteError.invalid }
        let key = "checklist:" + id, now = Date.now.timeIntervalSince1970 * 1_000
        let prior = library
        var record = library.companionRecords.first { $0.objectValue?["id"]?.stringValue == key }?.objectValue ?? ["id": .string(key), "kind": .string("checklist"), "cookId": .string(id), "title": .string("Prep checklist"), "createdAtUtcMillis": .number(now)]
        record["updatedAtUtcMillis"] = .number(now)
        record["payload"] = .string(String(data: try JSONEncoder().encode(checklist), encoding: .utf8)!)
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == key }; library.companionRecords.append(.object(record)); save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
}
struct CookPrepChecklist: View {
    @Environment(CookStore.self) private var store
    let cookId: String
    @State private var text = ""
    @State private var error: String?
    var body: some View {
        VStack(alignment: .leading) {
            Text("Preparation checklist").font(.headline)
            if let checklist = store.prepChecklist(cookId) {
                ForEach(checklist.items) { item in
                    Toggle(item.text, isOn: Binding(get: { item.done }, set: { value in
                        var next = checklist
                        if let index = next.items.firstIndex(where: { $0.id == item.id }) { next.items[index].done = value; save(next) }
                    }))
                }
                HStack { TextField("Add preparation item", text: $text); Button("Add") { var next = checklist; next.items.append(NativeChecklistItem(text: text)); save(next); text = "" }.disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }.padding(14).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14)).onAppear { store.createPrepChecklist(cookId) }
    }
    private func save(_ checklist: NativePrepChecklist) { do { try store.savePrepChecklist(cookId, checklist) } catch { self.error = error.localizedDescription } }
}
