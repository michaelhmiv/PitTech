import Foundation

struct NativeCookReference: Codable {
    var version = 1
    var dishId: String
    var sourceCookId: String
    var sourceDishId: String
    var currentProbe = ""
    var sourceProbe = ""
    var anchor = "food_on"
    var currentSeriesId: String?
    var sourceSeriesId: String?
}
@MainActor extension CookStore {
    func savedReference(_ id: String, dishId: String) -> NativeCookReference? {
        guard let payload = library.companionRecords.first(where: { $0.objectValue?["id"]?.stringValue == "reference:\(id):\(dishId)" })?.objectValue?["payload"]?.stringValue else { return nil }
        return try? JSONDecoder().decode(NativeCookReference.self, from: Data(payload.utf8))
    }
    func saveReference(_ id: String, reference: NativeCookReference) throws {
        guard reference.version == 1, ["food_on", "wrap"].contains(reference.anchor), dishRecords(id).contains(where: { $0.id == reference.dishId }), dishRecords(reference.sourceCookId).contains(where: { $0.id == reference.sourceDishId }), reference.sourceCookId != id else { throw CookNoteError.scope }
        let key = "reference:\(id):\(reference.dishId)", now = Date.now.timeIntervalSince1970 * 1_000, prior = library
        var record = library.companionRecords.first { $0.objectValue?["id"]?.stringValue == key }?.objectValue ?? ["id": .string(key), "kind": .string("reference"), "cookId": .string(id), "title": .string("Reference cook"), "createdAtUtcMillis": .number(now)]
        record["updatedAtUtcMillis"] = .number(now); record["payload"] = .string(String(data: try JSONEncoder().encode(reference), encoding: .utf8)!)
        library.companionRecords.removeAll { $0.objectValue?["id"]?.stringValue == key }; library.companionRecords.append(.object(record)); save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
}
