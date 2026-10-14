import Foundation
import ZIPFoundation

enum NativeCookPackHistory {
    private static let pitRoles: Set<String> = ["pit", "pit_ambient", "setpoint", "grill_temperature", "pit_temperature", "pit_probe", "ambient_temperature"]
    private static let fields: [String: Set<String>] = [
        "cooks": Set("id title status startedAtUtcMillis startedTimeZoneId endedAtUtcMillis initialSetpointValue initialSetpointUnit createdAtUtcMillis updatedAtUtcMillis fuelType woodOrPelletBlend outdoorTemperatureValue outdoorTemperatureUnit".split(separator: " ").map(String.init)),
        "dishes": Set("id cookId name foodType cut weightValue weightUnit pieceCount startingCondition boneIn placement prepNotes createdAtUtcMillis updatedAtUtcMillis gradeOrSource thicknessNotes".split(separator: " ").map(String.init)),
        "ingredients": Set("id cookId dishId stage name amountText brand amountValue amountUnit appliedAtUtcMillis notes createdAtUtcMillis".split(separator: " ").map(String.init)),
        "events": Set("id cookId dishId eventType title details occurredAtUtcMillis recordedAtUtcMillis timeZoneId source createdAtUtcMillis updatedAtUtcMillis noteMetadataJson temperatureContextJson".split(separator: " ").map(String.init)),
        "readings": Set("id cookId dishId probeId probeName measurementType value unit measuredAtUtcMillis timeZoneId source qualityStatus recordedAtUtcMillis timestampBasis samplingIntervalMillis".split(separator: " ").map(String.init)),
        "targets": Set("id cookId dishId targetType value unit scope explanation createdAtUtcMillis".split(separator: " ").map(String.init)),
        "results": Set("id cookId dishId resultType numericValue unit textValue notes recordedAtUtcMillis".split(separator: " ").map(String.init)),
        "probes": Set("id cookId assignedDishId name measurementType source createdAtUtcMillis".split(separator: " ").map(String.init)),
        "photos": Set("id cookId dishId eventId mimeType caption capturedAtUtcMillis addedAtUtcMillis originalFileName archivePath temperatureContextJson".split(separator: " ").map(String.init)),
        "logReadingLinks": ["readingId", "eventId"], "lifecycles": ["cookId", "timingMode"],
        "targetHistory": ["id", "cookId", "dishId", "targetType", "value", "unit", "effectiveAt"],
    ]
    private static let identityKeys = ["id", "cookId", "dishId", "probeId", "eventId", "readingId", "assignedDishId"]
    static func rows(_ root: JSONValue, _ key: String) -> [[String: JSONValue]] { (root.objectValue?[key]?.arrayValue ?? []).compactMap(\.objectValue) }
    private static func metadata(_ row: [String: JSONValue]) throws -> [String: JSONValue]? {
        guard let raw = row["noteMetadataJson"]?.stringValue else { return nil }
        return try JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8)).objectValue
    }
    private static func dishScope(_ row: [String: JSONValue]) throws -> Set<String> { Set(try metadata(row)?["dishIds"]?.arrayValue?.compactMap(\.stringValue) ?? []) }

    static func scope(_ source: JSONValue, dishIds: Set<String>, photoIds: Set<String>? = nil, title: String? = nil, alias: ((String) -> String)? = nil) throws -> JSONValue {
        let cooks = rows(source, "cooks")
        guard cooks.count == 1, let cook = cooks.first, cook["status"]?.stringValue == "completed",
              let start = cook["startedAtUtcMillis"]?.numberValue, let end = cook["endedAtUtcMillis"]?.numberValue, start > 0, end >= start,
              !dishIds.isEmpty, dishIds.isSubset(of: Set(rows(source, "dishes").compactMap { $0["id"]?.stringValue })) else { throw PitTechArchiveError.invalidArchive }
        let readings = rows(source, "readings").filter { row in
            row["dishId"]?.stringValue.map(dishIds.contains) == true || row["dishId"]?.stringValue == nil && pitRoles.contains(row["measurementType"]?.stringValue ?? "")
        }
        let events = try rows(source, "events").filter { row in
            if let id = row["dishId"]?.stringValue { return dishIds.contains(id) }
            let ids = try dishScope(row); return ids.isEmpty || !ids.isDisjoint(with: dishIds)
        }
        let readingIds = Set(readings.compactMap { $0["id"]?.stringValue }), eventIds = Set(events.compactMap { $0["id"]?.stringValue })
        let probeIds = Set(readings.compactMap { $0["probeId"]?.stringValue })
        func selected(_ row: [String: JSONValue], common: Bool) -> Bool { row["dishId"]?.stringValue.map(dishIds.contains) ?? common }
        let scoped: [String: [[String: JSONValue]]] = [
            "cooks": [cook], "dishes": rows(source, "dishes").filter { $0["id"]?.stringValue.map(dishIds.contains) == true },
            "ingredients": rows(source, "ingredients").filter { selected($0, common: false) }, "events": events, "readings": readings,
            "targets": rows(source, "targets").filter { selected($0, common: true) }, "results": rows(source, "results").filter { selected($0, common: false) },
            "probes": rows(source, "probes").filter { $0["id"]?.stringValue.map(probeIds.contains) == true },
            "photos": rows(source, "photos").filter { row in
                let inPhotos = photoIds == nil || row["id"]?.stringValue.map { photoIds!.contains($0) } == true
                let inDish = selected(row, common: false) || row["dishId"]?.stringValue == nil
                return inPhotos && inDish
            },
            "logReadingLinks": rows(source, "logReadingLinks").filter { $0["readingId"]?.stringValue.map(readingIds.contains) == true && $0["eventId"]?.stringValue.map(eventIds.contains) == true },
            "lifecycles": rows(source, "lifecycles"), "targetHistory": rows(source, "targetHistory").filter { selected($0, common: true) },
        ]
        var root: [String: JSONValue] = ["schemaVersion": .number(Double(PitTechLibrary.archiveVersion)), "preferences": .null]
        for (key, values) in scoped {
            root[key] = .array(try values.map { original in
                var row = original.filter { fields[key]!.contains($0.key) }
                if key == "photos", row["eventId"]?.stringValue.map(eventIds.contains) != true { row["eventId"] = .null }
                if key == "cooks", let title { row["title"] = .string(title) }
                if key == "probes", row["assignedDishId"]?.stringValue.map(dishIds.contains) != true { row["assignedDishId"] = .null }
                if row["source"] != nil { row["source"] = .string(row["source"]?.stringValue == "manual" ? "manual" : "recorded") }
                for unit in ["unit", "initialSetpointUnit", "outdoorTemperatureUnit"] { if let value = row[unit]?.stringValue, ["C", "F"].contains(value) { row[unit] = .string("°" + value) } }
                if let raw = try metadata(row) {
                    var note = raw.filter { ["version", "category", "action", "method", "durationMillis", "startedAtUtcMillis", "endedAtUtcMillis"].contains($0.key) }
                    note["dishIds"] = .array(try dishScope(row).intersection(dishIds).sorted().map { .string(alias?($0) ?? $0) })
                    note["ingredients"] = .array((raw["ingredients"]?.arrayValue ?? []).map { .object(($0.objectValue ?? [:]).filter { ["name", "stage", "brand", "amount", "unit"].contains($0.key) }) })
                    row["noteMetadataJson"] = .string(try recipeJSON(JSONValue.object(note)))
                }
                if let context = row["temperatureContextJson"]?.stringValue {
                    let values = try JSONDecoder().decode([JSONValue].self, from: Data(context.utf8)).compactMap(\.objectValue)
                    let selectedValues = values.filter { value in
                        dishIds == Set(rows(source, "dishes").compactMap { $0["id"]?.stringValue }) || selected(row, common: false) || readings.contains { sample in
                            sample["probeName"] == value["name"] && sample["value"] == value["value"] &&
                            sample["unit"]?.stringValue?.replacingOccurrences(of: "°", with: "") == value["unit"]?.stringValue?.replacingOccurrences(of: "°", with: "") && sample["measuredAtUtcMillis"] == value["observedAtUtcMillis"]
                        }
                    }
                    let clean = selectedValues.map { value -> JSONValue in
                        var result = value.filter { ["name", "value", "unit", "observedAtUtcMillis", "source", "timestampBasis"].contains($0.key) }
                        if let unit = result["unit"]?.stringValue, ["C", "F"].contains(unit) { result["unit"] = .string("°" + unit) }
                        return .object(result)
                    }
                    row["temperatureContextJson"] = clean.isEmpty ? .null : .string(try recipeJSON(clean))
                }
                if let alias {
                    for field in identityKeys { if let value = row[field]?.stringValue { row[field] = .string(alias(value)) } }
                    if key == "photos", let id = row["id"]?.stringValue { row["mimeType"] = .string("image/jpeg"); row["originalFileName"] = .string(id + ".jpg"); row["archivePath"] = .string("attachments/\(id).jpg") }
                }
                return .object(row)
            })
        }
        return .object(root)
    }

    static func freeze(_ source: PitTechLibrary, cookId: String, dishIds: Set<String>, copiedPhotos: [JSONValue]) throws -> JSONValue? {
        guard let cook = source.cooks.first(where: { $0.id == cookId }), cook.status == "completed", cook.startedAtUtcMillis > 0, cook.endedAtUtcMillis.map({ $0 >= cook.startedAtUtcMillis }) == true else { return nil }
        var core = PitTechLibrary(); core.preferences = nil; core.cooks = [cook]
        func owned(_ values: [JSONValue]) -> [JSONValue] { values.filter { $0.objectValue?["cookId"]?.stringValue == cookId } }
        core.dishes = owned(source.dishes); core.ingredients = owned(source.ingredients); core.events = owned(source.events)
        core.readings = owned(source.readings); core.targets = owned(source.targets); core.results = owned(source.results)
        core.probes = owned(source.probes); core.photos = copiedPhotos; core.lifecycles = owned(source.lifecycles)
        core.logReadingLinks = source.logReadingLinks; core.targetHistory = owned(source.targetHistory)
        return try scope(JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(core)), dishIds: dishIds)
    }

    static func remapOwnedPhotos(_ source: JSONValue?, mappings: [String: String], dishIds: Set<String>) throws -> JSONValue? {
        guard let source else { return nil }
        let selected = Set(rows(source, "dishes").compactMap { $0["id"]?.stringValue }).intersection(dishIds)
        if selected.isEmpty { return nil }
        var root = try scope(source, dishIds: selected, photoIds: Set(mappings.keys)).objectValue!
        root["photos"] = .array(rows(.object(root), "photos").map { value in
            var photo = value; let id = mappings[photo["id"]!.stringValue!]!, ext = URL(fileURLWithPath: photo["archivePath"]!.stringValue!).pathExtension
            photo["id"] = .string(id); photo["archivePath"] = .string("attachments/\(id).\(ext)"); photo["originalFileName"] = .string("\(id).\(ext)")
            return .object(photo)
        })
        return .object(root)
    }

    static func historyOnlyArchive(at url: URL, library: PitTechLibrary, metadata: JSONValue) throws -> Data {
        guard metadata.objectValue?["kind"]?.stringValue == "cook", library.cooks.count == 1, library.cooks[0].status == "completed" else { throw PitTechArchiveError.invalidArchive }
        var history = library
        history.recipes = []; history.recipeRevisions = []; history.recipeAssets = []; history.companionRecords = []; history.companionPhotos = []
        history.dishRecipeSources = []; history.mealPresets = []; history.preferences = nil
        let archive = try Archive(url: url, accessMode: .read)
        var photos: [String: Data] = [:]
        for row in history.photos {
            try Task.checkCancellation()
            guard let id = row.objectValue?["id"]?.stringValue, let path = row.objectValue?["archivePath"]?.stringValue, let entry = archive[path] else { throw CookNoteError.missingPhoto }
            var data = Data()
            let checksum = try archive.extract(entry) { chunk in
                guard Int64(data.count + chunk.count) <= NativeArchiveLimits.cookPack.entry else { throw PitTechArchiveError.tooLarge }
                data.append(chunk)
            }
            guard checksum == entry.checksum else { throw PitTechArchiveError.invalidArchive }; photos[id] = data
        }
        var header = metadata.objectValue!; header["recipeRevisionIds"] = .array([])
        return try NativeCookPackArchive.write(history, metadata: .object(header), attachments: photos)
    }
}
