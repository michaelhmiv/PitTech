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

// PL-08: the per-dish DishVerdictSheet that used to live here was the light
// half of iOS's split results handling. It is replaced by the one unified
// results editor, CookResultsSheet in CookResults.swift — the dish card's
// "Results & cook again" button opens that sheet focused on the dish. The
// store functions above stay: they are the tested data-layer path the
// Cook Pack and sharing writers use.
