import Foundation
import Observation
import ZIPFoundation

@MainActor
@Observable
final class CookStore {
    var library = PitTechLibrary()
    private(set) var saveError: String?

    var noteDrafts: [CookNoteDraft] = []
    var deletedNote: DeletedNativeNote?
    private let directory: URL
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
        load()
        noteDrafts = (try? JSONDecoder().decode([CookNoteDraft].self, from: Data(contentsOf: draftsURL))) ?? []
    }

    var activeCooks: [CookRecord] { library.cooks.filter(\.isActive).sorted { $0.startedAtUtcMillis > $1.startedAtUtcMillis } }
    var completedCooks: [CookRecord] { library.cooks.filter { !$0.isActive }.sorted { $0.startedAtUtcMillis > $1.startedAtUtcMillis } }
    var temperatureUnit: String { (library.preferences?["temperatureUnit"]?.stringValue ?? "F").replacingOccurrences(of: "°", with: "") }
    var weightUnit: String { library.preferences?["weightUnit"]?.stringValue ?? "lb" }

    func startCook(title: String, firstDish: String, alreadyCooking: Bool = false, foodOn: Date = .now) {
        guard !alreadyCooking || foodOn <= .now && foodOn.timeIntervalSince1970 > 0 else { saveError = "Choose the actual Food on time, up to now."; return }
        let prior = library
        let cook = CookRecord.start(title: title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "New cook" : title)
        library.cooks.append(cook)
        library.lifecycles.append(.object(["cookId": .string(cook.id), "timingMode": .string("explicit_phases")]))
        if !firstDish.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let now = Int64(Date.now.timeIntervalSince1970 * 1_000)
            library.dishes.append(.object([
                "id": .string(UUID().uuidString.lowercased()),
                "cookId": .string(cook.id),
                "name": .string(firstDish.trimmingCharacters(in: .whitespacesAndNewlines)),
                "foodType": .string("other"),
                "createdAtUtcMillis": .number(Double(now)),
                "updatedAtUtcMillis": .number(Double(now))
            ]))
        }
        if alreadyCooking {
            do { try saveNote(CookNoteDraft(cookId: cook.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: foodOn.timeIntervalSince1970 * 1_000)) }
            catch { library = prior; saveError = error.localizedDescription; return }
        }
        save()
        if saveError != nil { library = prior }
    }

    func addDish(cookId: String, name: String) {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, library.cooks.contains(where: { $0.id == cookId }) else { return }
        let now = Int64(Date.now.timeIntervalSince1970 * 1_000)
        let prior = library, scopes = dishRecords(cookId).map(\.id)
        if !scopes.isEmpty && timingMode(cookId) == "explicit_phases" {
            let actions = library.actions(for: cookId)
            library.events = library.events.map { row in
                guard var o = row.objectValue, o["cookId"]?.stringValue == cookId, let id = o["id"]?.stringValue, let action = actions.first(where: { $0.id == id }), action.dishIds.isEmpty, CookPhaseEngine.actions.contains(action.action) else { return row }
                var metadata = o["noteMetadataJson"]?.stringValue.flatMap { try? JSONDecoder().decode(CookNoteMetadata.self, from: Data($0.utf8)) } ?? CookNoteMetadata(category: "action", action: action.action)
                metadata.dishIds = scopes; o["noteMetadataJson"] = .string(metadata.json); return .object(o)
            }
        }
        library.dishes.append(.object([
            "id": .string(UUID().uuidString.lowercased()),
            "cookId": .string(cookId),
            "name": .string(value),
            "foodType": .string("other"),
            "createdAtUtcMillis": .number(Double(now)),
            "updatedAtUtcMillis": .number(Double(now))
        ]))
        save()
        if saveError != nil { library = prior }
    }

    func updateCook(_ cookId: String, title: String, notes: String) {
        guard let index = library.cooks.firstIndex(where: { $0.id == cookId }) else { return }
        let now = Int64(Date.now.timeIntervalSince1970 * 1_000)
        library.cooks[index].title = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Cook" : title.trimmingCharacters(in: .whitespacesAndNewlines)
        library.cooks[index].notes = notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : notes
        library.cooks[index].updatedAtUtcMillis = now
        save()
    }

    func finishCook(_ cookId: String) {
        guard let index = library.cooks.firstIndex(where: { $0.id == cookId }) else { return }
        let now = Int64(Date.now.timeIntervalSince1970 * 1_000)
        library.cooks[index].status = "completed"
        library.cooks[index].endedAtUtcMillis = now
        library.cooks[index].updatedAtUtcMillis = now
        save()
    }

    func addReading(cookId: String, probeName: String, value: Double, unit: String, measurementType: String) {
        let now = Int64(Date.now.timeIntervalSince1970 * 1_000)
        library.readings.append(.object([
            "id": .string(UUID().uuidString.lowercased()),
            "cookId": .string(cookId),
            "probeName": .string(probeName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Probe" : probeName),
            "measurementType": .string(measurementType),
            "value": .number(value),
            "unit": .string(unit),
            "measuredAtUtcMillis": .number(Double(now)),
            "timeZoneId": .string(TimeZone.current.identifier),
            "source": .string("manual"),
            "qualityStatus": .string("valid"),
            "recordedAtUtcMillis": .number(Double(now)),
            "timestampBasis": .string("manual_entry")
        ]))
        save()
    }

    func addEvent(cookId: String, title: String, details: String) {
        let now = Int64(Date.now.timeIntervalSince1970 * 1_000)
        library.events.append(.object([
            "id": .string(UUID().uuidString.lowercased()),
            "cookId": .string(cookId),
            "eventType": .string("note"),
            "title": .string(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Cook note" : title),
            "details": .string(details),
            "occurredAtUtcMillis": .number(Double(now)),
            "recordedAtUtcMillis": .number(Double(now)),
            "timeZoneId": .string(TimeZone.current.identifier),
            "source": .string("manual"),
            "createdAtUtcMillis": .number(Double(now)),
            "updatedAtUtcMillis": .number(Double(now))
        ]))
        save()
    }

    func readings(for cookId: String) -> [TemperatureSample] {
        library.readings.compactMap(TemperatureSample.init(json:)).filter { $0.cookId == cookId }.sorted { $0.measuredAt < $1.measuredAt }
    }

    func events(for cookId: String) -> [TimelineEntry] {
        library.events
            .filter { $0.objectValue?["cookId"]?.stringValue == cookId }
            .compactMap(TimelineEntry.init(json:))
            .sorted { $0.occurredAt > $1.occurredAt }
    }

    func dishes(for cookId: String) -> [String] {
        library.dishes.compactMap { item in
            guard let object = item.objectValue,
                  object["cookId"]?.stringValue == cookId,
                  let name = object["name"]?.stringValue else { return nil }
            return name
        }
    }

    func setPreference(_ key: String, value: String) {
        var preferences = library.preferences ?? [:]
        preferences[key] = .string(value)
        library.preferences = preferences
        save()
    }

    func exportArchive() throws -> Data {
        var portable = library
        portable.schemaVersion = 6
        portable.readings = portable.readings.map { row in
            guard var o = row.objectValue, let unit = o["unit"]?.stringValue, ["F", "C"].contains(unit) else { return row }
            o["unit"] = .string("°" + unit); return .object(o)
        }
        let jsonData = try JSONEncoder().encode(portable)
        let manifest: [String: Any] = [
            "format": "PitTech complete cook archive",
            "archiveVersion": 6,
            "exportedAtUtc": ISO8601DateFormatter().string(from: .now),
            "appVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0",
            "includesPreferences": true,
            "counts": ["cooks": library.cooks.count, "dishes": library.dishes.count,
                       "timelineEvents": library.events.count, "readings": library.readings.count]
        ]
        let archive = try Archive(accessMode: .create)
        let manifestData = try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys])
        try archive.addEntry(with: "manifest.json", type: .file, uncompressedSize: Int64(manifestData.count), provider: { position, size in
            let bytes = manifestData
            return bytes.subdata(in: Int(position)..<(Int(position) + size))
        })
        try archive.addEntry(with: "README.txt", type: .file, uncompressedSize: Int64(Self.archiveReadme.utf8.count), provider: { position, size in
            let bytes = Data(Self.archiveReadme.utf8)
            return bytes.subdata(in: Int(position)..<(Int(position) + size))
        })
        try archive.addEntry(with: "data/pittech.json", type: .file, uncompressedSize: Int64(jsonData.count), provider: { position, size in
            jsonData.subdata(in: Int(position)..<(Int(position) + size))
        })
        for row in library.photos + library.companionPhotos {
            guard let object = row.objectValue,
                  let path = object["archivePath"]?.stringValue,
                  let id = object["id"]?.stringValue else { continue }
            guard Self.isExpectedAttachment(path: path, id: id),
                  let bytes = try? Data(contentsOf: attachmentURL(id: id, archivePath: path)),
                  !bytes.isEmpty else { throw CocoaError(.fileNoSuchFile) }
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(bytes.count), provider: { position, size in
                bytes.subdata(in: Int(position)..<(Int(position) + size))
            })
        }
        guard let result = archive.data else { throw PitTechArchiveError.invalidArchive }
        return result
    }

    static func previewArchive(_ data: Data) throws -> PitTechImportPreview {
        guard data.count <= 64 * 1024 * 1024 else { throw PitTechArchiveError.tooLarge }
        let archive = try Archive(data: data, accessMode: .read)
        guard archive.reduce(0, { count, _ in count + 1 }) <= 1_000 else { throw PitTechArchiveError.tooManyEntries }
        var totalBytes: Int64 = 0
        var manifestData: Data?
        var libraryData: Data?
        var paths = Set<String>()
        for entry in archive {
            guard Self.isSafeArchivePath(entry.path), paths.insert(entry.path).inserted, entry.type == .file,
                  entry.uncompressedSize <= 16 * 1024 * 1024 else { throw PitTechArchiveError.invalidArchive }
            totalBytes += Int64(entry.uncompressedSize)
            guard totalBytes <= 64 * 1024 * 1024 else { throw PitTechArchiveError.tooLarge }
            if entry.path == "manifest.json" || entry.path == "data/pittech.json" {
                var contents = Data()
                _ = try archive.extract(entry) { chunk in contents.append(chunk) }
                if entry.path == "manifest.json" { manifestData = contents } else { libraryData = contents }
            }
        }
        guard let manifestData, let libraryData,
              let manifest = try? JSONSerialization.jsonObject(with: manifestData) as? [String: Any],
              manifest["format"] as? String == "PitTech complete cook archive" else {
            throw PitTechArchiveError.invalidArchive
        }
        let library = try JSONDecoder().decode(PitTechLibrary.self, from: libraryData)
        guard manifest["archiveVersion"] as? Int == library.schemaVersion else { throw PitTechArchiveError.invalidArchive }
        guard (1...6).contains(library.schemaVersion) else { throw PitTechArchiveError.unsupportedVersion }
        let cookIds = Set(library.cooks.map(\.id))
        guard cookIds.count == library.cooks.count,
              (library.dishes + library.ingredients + library.events + library.readings + library.targets + library.results + library.devices + library.probes + library.photos + library.reminders + library.recordings + library.probeAssignments).allSatisfy({
                  guard let object = $0.objectValue,
                        let cookId = object["cookId"]?.stringValue,
                        cookIds.contains(cookId) else { return false }
                  return true
              }) else { throw PitTechArchiveError.invalidArchive }
        try library.validateCookExperience()
        return PitTechImportPreview(cooks: library.cooks.count, dishes: library.dishes.count,
                                    readings: library.readings.count, events: library.events.count,
                                    version: library.schemaVersion)
    }

    func importArchive(_ data: Data) throws -> PitTechImportResult {
        _ = try Self.previewArchive(data)
        let archive = try Archive(data: data, accessMode: .read)
        guard let entry = archive["data/pittech.json"] else { throw PitTechArchiveError.invalidArchive }
        var jsonData = Data()
        _ = try archive.extract(entry) { chunk in jsonData.append(chunk) }
        let imported = try JSONDecoder().decode(PitTechLibrary.self, from: jsonData)
        let oldIds = Set(library.cooks.map(\.id))
        let newCooks = imported.cooks.filter { !oldIds.contains($0.id) }
        let newIds = Set(newCooks.map(\.id))
        guard !newCooks.isEmpty else { return PitTechImportResult(imported: 0, skipped: imported.cooks.count) }

        for pair in [(library.dishes, imported.dishes), (library.events, imported.events), (library.readings, imported.readings), (library.probes, imported.probes), (library.devices, imported.devices), (library.ingredients, imported.ingredients), (library.targets, imported.targets), (library.results, imported.results), (library.reminders, imported.reminders), (library.probeAssignments, imported.probeAssignments), (library.targetHistory, imported.targetHistory)] {
            let existing = Set(pair.0.compactMap { $0.objectValue?["id"]?.stringValue })
            guard pair.1.filter({ belongsToNewCook($0, ids: newIds) }).allSatisfy({ row in
                guard let id = row.objectValue?["id"]?.stringValue else { return false }
                return !existing.contains(id)
            }) else { throw PitTechArchiveError.invalidArchive }
        }
        let existingPhotoIds = Set((library.photos + library.companionPhotos).compactMap { $0.objectValue?["id"]?.stringValue })
        let importedRecords = imported.companionRecords.filter { row in
            guard let object = row.objectValue else { return false }
            return object["cookId"]?.stringValue.map(newIds.contains) ?? true
        }
        let existingRecordIds = Set(library.companionRecords.compactMap { $0.objectValue?["id"]?.stringValue })
        guard importedRecords.filter({ belongsToNewCook($0, ids: newIds) }).allSatisfy({ !existingRecordIds.contains($0.objectValue?["id"]?.stringValue ?? "") }) else { throw PitTechArchiveError.invalidArchive }
        let newRecordIds = Set(importedRecords.compactMap { $0.objectValue?["id"]?.stringValue })
        let importedPhotos = imported.photos.filter { belongsToNewCook($0, ids: newIds) }
        let importedReferencePhotos = imported.companionPhotos.filter { row in
            row.objectValue?["recordId"]?.stringValue.map(newRecordIds.contains) ?? false
        }
        let photoRows = importedPhotos + importedReferencePhotos
        let photoIds = photoRows.compactMap { $0.objectValue?["id"]?.stringValue }
        guard Set(photoIds).count == photoIds.count, photoIds.allSatisfy({ !existingPhotoIds.contains($0) }) else {
            throw PitTechArchiveError.invalidArchive
        }
        var attachmentWrites: [(URL, Data)] = []
        if !photoRows.isEmpty {
            for row in photoRows {
                guard let object = row.objectValue,
                      let id = object["id"]?.stringValue,
                      let path = object["archivePath"]?.stringValue,
                      Self.isExpectedAttachment(path: path, id: id),
                      let entry = archive[path] else { throw PitTechArchiveError.invalidArchive }
                var bytes = Data()
                _ = try archive.extract(entry) { chunk in
                    guard bytes.count + chunk.count <= 16 * 1024 * 1024 else { throw PitTechArchiveError.tooLarge }
                    bytes.append(chunk)
                }
                guard !bytes.isEmpty else { throw PitTechArchiveError.invalidArchive }
                attachmentWrites.append((attachmentURL(id: id, archivePath: path), bytes))
            }
        }

        var writtenURLs: [URL] = []
        do {
            for (url, bytes) in attachmentWrites {
                try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try bytes.write(to: url, options: .atomic)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                var fileURL = url
                try? fileURL.setResourceValues(values)
                writtenURLs.append(url)
            }
        } catch {
            writtenURLs.forEach { try? FileManager.default.removeItem(at: $0) }
            throw error
        }

        let priorLibrary = library
        library.cooks.append(contentsOf: newCooks)
        library.dishes = mergeUnique(library.dishes, imported.dishes.filter { belongsToNewCook($0, ids: newIds) })
        library.ingredients = mergeUnique(library.ingredients, imported.ingredients.filter { belongsToNewCook($0, ids: newIds) })
        library.events = mergeUnique(library.events, imported.events.filter { belongsToNewCook($0, ids: newIds) })
        library.readings = mergeUnique(library.readings, imported.readings.filter { belongsToNewCook($0, ids: newIds) })
        library.targets = mergeUnique(library.targets, imported.targets.filter { belongsToNewCook($0, ids: newIds) })
        library.results = mergeUnique(library.results, imported.results.filter { belongsToNewCook($0, ids: newIds) })
        library.devices = mergeUnique(library.devices, imported.devices.filter { belongsToNewCook($0, ids: newIds) })
        library.probes = mergeUnique(library.probes, imported.probes.filter { belongsToNewCook($0, ids: newIds) })
        library.reminders = mergeUnique(library.reminders, imported.reminders.filter { belongsToNewCook($0, ids: newIds) })
        library.recordings = mergeUnique(library.recordings, imported.recordings.filter { belongsToNewCook($0, ids: newIds) }.map { item in
            guard case .object(var object) = item else { return item }
            object["status"] = .string("stopped")
            return .object(object)
        }, key: "cookId")
        library.probeAssignments = mergeUnique(library.probeAssignments, imported.probeAssignments.filter { belongsToNewCook($0, ids: newIds) })
        library.photos = mergeUnique(library.photos, importedPhotos)
        library.companionRecords = mergeUnique(library.companionRecords, importedRecords)
        library.companionPhotos = mergeUnique(library.companionPhotos, importedReferencePhotos)
        if let preferences = imported.preferences { library.preferences = preferences }
        library.logReadingLinks = mergeUnique(library.logReadingLinks, imported.logReadingLinks.filter { row in
            guard let eventId = row.objectValue?["eventId"]?.stringValue else { return false }
            return imported.events.contains { $0.objectValue?["id"]?.stringValue == eventId && belongsToNewCook($0, ids: newIds) }
        }, key: "readingId")
        library.lifecycles = mergeUnique(library.lifecycles, imported.lifecycles.filter { belongsToNewCook($0, ids: newIds) }, key: "cookId")
        library.targetHistory = mergeUnique(library.targetHistory, imported.targetHistory.filter { belongsToNewCook($0, ids: newIds) })
        library.schemaVersion = 6
        save()
        if saveError != nil {
            library = priorLibrary
            writtenURLs.forEach { try? FileManager.default.removeItem(at: $0) }
            throw CocoaError(.fileWriteUnknown)
        }
        return PitTechImportResult(imported: newCooks.count, skipped: imported.cooks.count - newCooks.count)
    }

    func clearSaveError() {
        saveError = nil
    }

    var storageURL: URL { directory.appendingPathComponent("pittech.json") }

    private static let archiveReadme = "PitTech cook archive, schema 6. Cook records are stored in data/pittech.json. This archive can be imported by PitTech on iOS or Android.\n"

    private static func isSafeArchivePath(_ path: String) -> Bool {
        !path.isEmpty && !path.hasPrefix("/") && !path.contains("\\") &&
        !path.split(separator: "/").contains(where: { $0 == ".." || $0 == "." })
    }

    private static func isExpectedAttachment(path: String, id: String) -> Bool {
        path.range(of: "^attachments/\(NSRegularExpression.escapedPattern(for: id))\\.[A-Za-z0-9]{1,8}$", options: .regularExpression) != nil
    }

    private func belongsToNewCook(_ value: JSONValue, ids: Set<String>) -> Bool {
        guard let cookId = value.objectValue?["cookId"]?.stringValue else { return false }
        return ids.contains(cookId)
    }

    private func mergeUnique(_ current: [JSONValue], _ incoming: [JSONValue], key: String = "id") -> [JSONValue] {
        var known = Set(current.compactMap { $0.objectValue?[key]?.stringValue })
        var added: [JSONValue] = []
        for value in incoming {
            guard let id = value.objectValue?[key]?.stringValue, known.insert(id).inserted else { continue }
            added.append(value)
        }
        return current + added
    }

    private func attachmentURL(id: String, archivePath: String) -> URL {
        let name = URL(fileURLWithPath: archivePath).lastPathComponent
        return directory.appendingPathComponent("attachments", isDirectory: true)
            .appendingPathComponent(name)
    }

    private func load() {
        guard FileManager.default.fileExists(atPath: storageURL.path) else { return }
        do {
            library = try JSONDecoder().decode(PitTechLibrary.self, from: Data(contentsOf: storageURL))
            guard (1...6).contains(library.schemaVersion) else {
                saveError = "This PitTech library version is not supported. Keep the original backup safe."
                library = PitTechLibrary()
                return
            }
            library.schemaVersion = 6
        } catch {
            saveError = "PitTech couldn't read its local cook library. Your file has been left untouched."
        }
    }

    func save() {
        do {
            let data = try JSONEncoder().encode(library)
            try data.write(to: storageURL, options: .atomic)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var fileURL = storageURL
            try? fileURL.setResourceValues(values)
            saveError = nil
        } catch {
            saveError = "PitTech couldn't save this change. Check available storage and try again."
        }
    }
}
