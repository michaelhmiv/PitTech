import Foundation
import CryptoKit
import ImageIO
import UIKit
import UniformTypeIdentifiers

@MainActor extension CookStore {
    var draftsURL: URL { storageURL.deletingLastPathComponent().appendingPathComponent("note-drafts.json") }
    func persistDraft(_ draft: CookNoteDraft) throws {
        var next = noteDrafts.filter { $0.id != draft.id }; next.insert(draft, at: 0)
        try JSONEncoder().encode(next).write(to: draftsURL, options: .atomic)
        noteDrafts = next
    }
    func discardDraft(_ id: String) throws {
        let photos = noteDrafts.first { $0.id == id }?.photos ?? []
        let next = noteDrafts.filter { $0.id != id }
        try JSONEncoder().encode(next).write(to: draftsURL, options: .atomic)
        noteDrafts = next
        for photo in photos where !library.photos.contains(where: { $0.objectValue?["id"]?.stringValue == photo.id }) {
            try? FileManager.default.removeItem(at: photoURL(photo.path))
        }
    }
    func photoURL(_ path: String) -> URL { storageURL.deletingLastPathComponent().appendingPathComponent(path) }
    func photoPreview(_ path: String) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(photoURL(path) as CFURL, nil), let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 1_024] as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
    func stagePhoto(_ data: Data, capturedAt: Double? = nil) throws -> NotePhotoDraft {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), let typeId = CGImageSourceGetType(source), let type = UTType(typeId as String) else { throw CookNoteError.missingPhoto }
        let id = UUID().uuidString.lowercased(), ext = type.preferredFilenameExtension ?? "jpg"
        var actualCapture = capturedAt
        if actualCapture == nil, let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any], let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any], let date = exif[kCGImagePropertyExifDateTimeOriginal as String] as? String, let offset = exif[kCGImagePropertyExifOffsetTimeOriginal as String] as? String {
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy:MM:dd HH:mm:ss ZZZZZ"
            actualCapture = formatter.date(from: date + " " + offset).map { $0.timeIntervalSince1970 * 1_000 }
        }
        let photo = NotePhotoDraft(id: id, path: "attachments/\(id).\(ext)", mimeType: type.preferredMIMEType ?? "image/jpeg", capturedAt: actualCapture)
        try FileManager.default.createDirectory(at: photoURL("attachments"), withIntermediateDirectories: true)
        try data.write(to: photoURL(photo.path), options: .atomic)
        return photo
    }
    func loadNote(_ eventId: String) throws -> CookNoteDraft {
        guard let event = library.events.first(where: { $0.objectValue?["id"]?.stringValue == eventId })?.objectValue, let cookId = event["cookId"]?.stringValue else { throw CookNoteError.invalid }
        var draft = CookNoteDraft(id: eventId, cookId: cookId, dishId: event["dishId"]?.stringValue)
        if let raw = event["noteMetadataJson"]?.stringValue { draft.metadata = try JSONDecoder().decode(CookNoteMetadata.self, from: Data(raw.utf8)) }
        else { draft.metadata.action = library.actions(for: cookId).first { $0.id == eventId }?.action; if !CookNoteMetadata.actions.contains(draft.metadata.action ?? "") { draft.metadata.action = nil } }
        draft.title = event["title"]?.stringValue ?? ""; draft.description = event["details"]?.stringValue ?? ""; draft.occurredAt = event["occurredAtUtcMillis"]?.numberValue ?? draft.occurredAt
        let ids = Set(library.logReadingLinks.filter { $0.objectValue?["eventId"]?.stringValue == eventId }.compactMap { $0.objectValue?["readingId"]?.stringValue })
        draft.temperatures = library.readings.compactMap { row in
            guard let o = row.objectValue, let id = o["id"]?.stringValue, ids.contains(id), let value = o["value"]?.numberValue else { return nil }
            return NoteTemperatureDraft(id: id, probeName: o["probeName"]?.stringValue ?? "Food", measurementType: o["measurementType"]?.stringValue ?? "food", valueText: String(value), unit: "°" + (o["unit"]?.stringValue ?? "F").replacingOccurrences(of: "°", with: ""), measuredAt: o["measuredAtUtcMillis"]?.numberValue ?? draft.occurredAt, dishId: o["dishId"]?.stringValue, probeId: o["probeId"]?.stringValue)
        }
        draft.photos = library.photos.compactMap { row in
            guard let o = row.objectValue, o["eventId"]?.stringValue == eventId, let id = o["id"]?.stringValue, let path = o["archivePath"]?.stringValue else { return nil }
            return NotePhotoDraft(id: id, path: path, mimeType: o["mimeType"]?.stringValue ?? "image/jpeg", caption: o["caption"]?.stringValue ?? "", capturedAt: o["capturedAtUtcMillis"]?.numberValue)
        }
        return draft
    }
    func saveNote(_ draft: CookNoteDraft) throws {
        try draft.validate()
        guard let cook = library.cooks.first(where: { $0.id == draft.cookId }) else { throw CookNoteError.scope }
        let dishIds = dishRecords(draft.cookId).map(\.id)
        guard draft.metadata.dishIds.allSatisfy({ dishIds.contains($0) }), draft.dishId == nil || dishIds.contains(draft.dishId!) else { throw CookNoteError.scope }
        let previous = library.events.first { $0.objectValue?["id"]?.stringValue == draft.id }?.objectValue
        guard previous == nil || previous?["cookId"]?.stringValue == draft.cookId else { throw CookNoteError.scope }
        try persistDraft(draft)
        for photo in draft.photos { guard FileManager.default.fileExists(atPath: photoURL(photo.path).path) else { throw CookNoteError.missingPhoto } }
        let now = Date.now.timeIntervalSince1970 * 1_000
        var next = library
        let title = draft.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? (draft.description.isEmpty ? Self.actionLabel(draft.metadata.action) : String(draft.description.prefix(80))) : draft.title
        var event = previous ?? ["id": .string(draft.id), "cookId": .string(draft.cookId), "recordedAtUtcMillis": .number(now), "createdAtUtcMillis": .number(now), "timeZoneId": .string(TimeZone.current.identifier), "source": .string("manual")]
        event["eventType"] = .string("note"); event["title"] = .string(title); event["details"] = .string(draft.description)
        event["occurredAtUtcMillis"] = .number(draft.occurredAt); event["updatedAtUtcMillis"] = .number(now)
        event["dishId"] = draft.dishId.map(JSONValue.string) ?? .null; event["noteMetadataJson"] = .string(draft.metadata.json)
        next.events.removeAll { $0.objectValue?["id"]?.stringValue == draft.id }; next.events.append(.object(event))
        if draft.metadata.action != nil { guard draft.occurredAt <= Double(cook.endedAtUtcMillis ?? Int64(now + 60_000)) else { throw CookNoteError.order } }
        let priorAction = library.actions(for: draft.cookId).first { $0.id == draft.id }?.action
        if (CookPhaseEngine.actions.contains(draft.metadata.action ?? "") || CookPhaseEngine.actions.contains(priorAction ?? "")) && timingMode(draft.cookId) == "explicit_phases" {
            try CookPhaseEngine.validate(next.actions(for: draft.cookId), dishIds: dishIds)
        }
        let owned = Set(next.logReadingLinks.filter { $0.objectValue?["eventId"]?.stringValue == draft.id }.compactMap { $0.objectValue?["readingId"]?.stringValue })
        // All owned rows are replaced below; IDs and original receipt times remain stable.
        let priorReadings = Dictionary(uniqueKeysWithValues: next.readings.compactMap { row -> (String, [String: JSONValue])? in guard let o = row.objectValue, let id = o["id"]?.stringValue else { return nil }; return (id, o) })
        next.readings.removeAll { owned.contains($0.objectValue?["id"]?.stringValue ?? "") }
        next.logReadingLinks.removeAll { $0.objectValue?["eventId"]?.stringValue == draft.id }
        for t in draft.temperatures {
            let old = priorReadings[t.id]
            guard old == nil || owned.contains(t.id) && old?["cookId"]?.stringValue == draft.cookId && old?["source"]?.stringValue == "manual", t.dishId == nil || dishIds.contains(t.dishId!) else { throw CookNoteError.scope }
            let seed = "\(draft.cookId):manual:\(t.dishId ?? "whole"):\(t.measurementType):\(t.probeName.trimmingCharacters(in: .whitespacesAndNewlines))"
            let stableProbe = "manual-" + SHA256.hash(data: Data(seed.utf8)).map { String(format: "%02x", $0) }.joined()
            let probeId = old?["probeId"]?.stringValue ?? t.probeId ?? stableProbe
            if let probe = next.probes.first(where: { $0.objectValue?["id"]?.stringValue == probeId })?.objectValue {
                guard probe["cookId"]?.stringValue == draft.cookId else { throw CookNoteError.scope }
            } else { next.probes.append(.object(["id": .string(probeId), "cookId": .string(draft.cookId), "name": .string(t.probeName), "measurementType": .string(t.measurementType), "source": .string("manual"), "createdAtUtcMillis": .number(now)])) }
            next.readings.append(.object(["id": .string(t.id), "cookId": .string(draft.cookId), "dishId": t.dishId.map(JSONValue.string) ?? .null, "probeId": .string(probeId), "probeName": .string(t.probeName), "measurementType": .string(t.measurementType), "value": .number(Double(t.valueText)!), "unit": .string(t.unit), "measuredAtUtcMillis": .number(t.measuredAt), "recordedAtUtcMillis": old?["recordedAtUtcMillis"] ?? .number(now), "timeZoneId": .string(TimeZone.current.identifier), "source": .string("manual"), "qualityStatus": .string("valid"), "timestampBasis": .string("manual_entry"), "samplingIntervalMillis": .number(0)]))
            next.logReadingLinks.append(.object(["readingId": .string(t.id), "eventId": .string(draft.id)]))
        }
        next.photos = next.photos.map { row in guard var o = row.objectValue, o["eventId"]?.stringValue == draft.id, !draft.photos.contains(where: { $0.id == o["id"]?.stringValue }) else { return row }; o["eventId"] = .null; return .object(o) }
        for p in draft.photos {
            let old = next.photos.first { $0.objectValue?["id"]?.stringValue == p.id }?.objectValue
            guard old == nil || old?["cookId"]?.stringValue == draft.cookId && (old?["eventId"]?.stringValue == nil || old?["eventId"]?.stringValue == draft.id) else { throw CookNoteError.scope }
            var photo = old ?? ["id": .string(p.id), "cookId": .string(draft.cookId), "originalFileName": .string(URL(fileURLWithPath: p.path).lastPathComponent), "mimeType": .string(p.mimeType), "archivePath": .string(p.path), "addedAtUtcMillis": .number(now)]
            photo["eventId"] = .string(draft.id); photo["caption"] = .string(p.caption); photo["dishId"] = draft.dishId.map(JSONValue.string) ?? .null
            photo["capturedAtUtcMillis"] = p.capturedAt.map(JSONValue.number) ?? .null
            next.photos.removeAll { $0.objectValue?["id"]?.stringValue == p.id }; next.photos.append(.object(photo))
        }
        next.activityTimers = next.activityTimers.map { row in
            guard var o = row.objectValue, o["noteId"]?.stringValue == draft.id, o["cookId"]?.stringValue == draft.cookId, o["status"]?.stringValue == "stopped" else { return row }; o["status"] = .string("logged"); return .object(o)
        }
        try next.validateCookExperience()
        let prior = library; library = next; save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
        try discardDraft(draft.id)
    }
    func deleteNote(_ id: String) throws {
        guard let event = library.events.first(where: { $0.objectValue?["id"]?.stringValue == id }) else { return }
        let links = library.logReadingLinks.filter { $0.objectValue?["eventId"]?.stringValue == id }
        let owned = Set(links.compactMap { $0.objectValue?["readingId"]?.stringValue })
        let rows = library.readings.filter { owned.contains($0.objectValue?["id"]?.stringValue ?? "") }
        let photos = library.photos.filter { $0.objectValue?["eventId"]?.stringValue == id }
        let prior = library
        if let cookId = event.objectValue?["cookId"]?.stringValue, timingMode(cookId) == "explicit_phases" {
            try CookPhaseEngine.validate(library.actions(for: cookId).filter { $0.id != id }, dishIds: dishRecords(cookId).map(\.id))
        }
        library.events.removeAll { $0.objectValue?["id"]?.stringValue == id }
        library.logReadingLinks.removeAll { $0.objectValue?["eventId"]?.stringValue == id }
        library.readings.removeAll { owned.contains($0.objectValue?["id"]?.stringValue ?? "") }
        library.photos = library.photos.map { row in guard var o = row.objectValue, o["eventId"]?.stringValue == id else { return row }; o["eventId"] = .null; return .object(o) }
        save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
        deletedNote = DeletedNativeNote(event: event, readings: rows, links: links, photos: photos)
    }
    func undoDeleteNote() throws {
        guard let bundle = deletedNote, let id = bundle.event.objectValue?["id"]?.stringValue, let cookId = bundle.event.objectValue?["cookId"]?.stringValue else { return }
        guard !library.events.contains(where: { $0.objectValue?["id"]?.stringValue == id }), bundle.readings.allSatisfy({ row in !library.readings.contains(where: { $0.objectValue?["id"] == row.objectValue?["id"] }) }), bundle.photos.allSatisfy({ row in library.photos.contains(where: { $0.objectValue?["id"] == row.objectValue?["id"] && ($0.objectValue?["eventId"]?.stringValue == nil || $0.objectValue?["eventId"]?.stringValue == id) }) }) else { throw CookNoteError.scope }
        var candidate = library; candidate.events.append(bundle.event)
        if timingMode(cookId) == "explicit_phases" { try CookPhaseEngine.validate(candidate.actions(for: cookId), dishIds: dishRecords(cookId).map(\.id)) }
        let prior = library
        library.events.append(bundle.event); library.readings.append(contentsOf: bundle.readings); library.logReadingLinks.append(contentsOf: bundle.links)
        let ids = Set(bundle.photos.compactMap { $0.objectValue?["id"]?.stringValue })
        library.photos.removeAll { ids.contains($0.objectValue?["id"]?.stringValue ?? "") }; library.photos.append(contentsOf: bundle.photos)
        save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
        deletedNote = nil
    }
    func dishRecords(_ cookId: String) -> [NoteDishRecord] {
        library.dishes.compactMap { row in guard let o = row.objectValue, o["cookId"]?.stringValue == cookId, let id = o["id"]?.stringValue, let name = o["name"]?.stringValue else { return nil }; return NoteDishRecord(id: id, name: name) }
    }
    func timingMode(_ id: String) -> String { library.lifecycles.first { $0.objectValue?["cookId"]?.stringValue == id }?.objectValue?["timingMode"]?.stringValue ?? "legacy_preserved" }
    func phases(_ id: String, now: Date = .now) -> [NativeDishPhase] {
        guard let cook = library.cooks.first(where: { $0.id == id }) else { return [] }
        let dishes = dishRecords(id)
        return (dishes.isEmpty ? [NoteDishRecord(id: "whole", name: "Whole cook")] : dishes).map { CookPhaseEngine.dish(id: $0.id, name: $0.name, actions: library.actions(for: id), start: Double(cook.startedAtUtcMillis), end: Double(cook.endedAtUtcMillis ?? Int64(now.timeIntervalSince1970 * 1_000))) }
    }
    func phaseSummary(_ id: String) -> String {
        let stages = Set(phases(id).map(\.phase))
        return stages.count == 1 ? CookPhaseEngine.label(stages.first!) : "Mixed phases"
    }
    static func actionLabel(_ action: String?) -> String {
        ["food_on": "Food on", "spritz": "Spritz", "wrap": "Wrap", "check_in": "Check", "remove": "Remove", "rest_start": "Start rest", "hold_start": "Start hold", "dish_done": "Ready to serve", "preheat_start": "Preheat", "season": "Season", "trim": "Trim", "marinate": "Marinate", "add_fuel": "Add fuel"][action ?? ""] ?? "Cook note"
    }
    func adoptTiming(_ id: String, foodOn: Date?) throws {
        guard let cook = library.cooks.first(where: { $0.id == id }) else { throw CookNoteError.scope }
        let events = library.actions(for: id)
        var draft: CookNoteDraft?
        if !events.contains(where: { $0.action == "food_on" }) {
            guard let foodOn, foodOn.timeIntervalSince1970 > 0, foodOn <= (cook.endedAtUtcMillis.map { Date(timeIntervalSince1970: Double($0) / 1_000) } ?? .now) else { throw CookNoteError.order }
            draft = CookNoteDraft(cookId: id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: foodOn.timeIntervalSince1970 * 1_000)
        }
        let proposed = events + (draft.map { [CookAction(id: $0.id, action: "food_on", dishIds: [], at: $0.occurredAt)] } ?? [])
        try CookPhaseEngine.validate(proposed, dishIds: dishRecords(id).map(\.id))
        let prior = library
        if let draft { try saveNote(draft) }
        library.lifecycles.removeAll { $0.objectValue?["cookId"]?.stringValue == id }
        library.lifecycles.append(.object(["cookId": .string(id), "timingMode": .string("explicit_phases")]))
        save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
}
