import Foundation
import ZIPFoundation

private enum NativeTableCell {
    case text(String), number(Double)
    var string: String { switch self { case .text(let value): value; case .number(let value): String(value) } }
}
private struct NativeTableSheet {
    let name: String
    let headers: [String]
    let rows: AnySequence<[NativeTableCell]>
}
enum NativeTabularExportError: LocalizedError {
    case tooManyRows, invalidText
    var errorDescription: String? {
        switch self {
        case .tooManyRows: "This table exceeds Excel's row limit. Export a smaller selection or a cook archive."
        case .invalidText: "One value contains text Excel cannot represent. Export a cook archive to preserve it."
        }
    }
}
private final class NativeTableSink {
    let stream: OutputStream
    private(set) var count: Int64 = 0
    init(_ url: URL) throws {
        guard let stream = OutputStream(url: url, append: false) else { throw CocoaError(.fileWriteUnknown) }
        self.stream = stream; stream.open()
    }
    deinit { stream.close() }
    func append(_ text: String) throws {
        try Task.checkCancellation()
        let data = Data(text.utf8); count += Int64(data.count)
        guard count <= NativeArchiveLimits.backup.entry else { throw PitTechArchiveError.tooLarge }
        try data.withUnsafeBytes { bytes in
            guard let pointer = bytes.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            var offset = 0
            while offset < data.count {
                let written = stream.write(pointer + offset, maxLength: data.count - offset)
                guard written > 0 else { throw stream.streamError ?? CocoaError(.fileWriteUnknown) }; offset += written
            }
        }
    }
    func close() { stream.close() }
}
private final class NativeTableValues {
    private let utcFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return formatter
    }()
    private var localFormatters: [String: DateFormatter] = [:]
    func value(_ key: String, _ row: [String: JSONValue]) -> JSONValue {
        for candidate in key.split(separator: "|").map(String.init) { if let value = row[candidate], value != .null { return value } }
        return .null
    }
    func text(_ value: JSONValue) -> String {
        switch value {
        case .null: ""
        case .string(let text): text
        case .bool(let flag): flag ? "true" : "false"
        case .number(let number): String(number)
        default: (try? recipeJSON(value)) ?? ""
        }
    }
    func utc(_ value: JSONValue) -> String { value.numberValue.map { utcFormatter.string(from: Date(timeIntervalSince1970: $0 / 1000)) } ?? "" }
    func cell(_ field: NativeExportField, _ row: [String: JSONValue]) -> NativeTableCell {
        switch field {
        case .constant(let value): return .text(value)
        case .number(let key): return value(key, row).numberValue.map(NativeTableCell.number) ?? .text("")
        case .utc(let key): return .text(utc(value(key, row)))
        case .local(let key, let zoneKey):
            guard let at = value(key, row).numberValue else { return .text("") }
            let zone = text(value(zoneKey, row))
            if localFormatters[zone] == nil {
                let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.calendar = Calendar(identifier: .gregorian)
                formatter.timeZone = TimeZone(identifier: zone) ?? TimeZone(secondsFromGMT: 0)
                formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"; localFormatters[zone] = formatter
            }
            return .text(localFormatters[zone]!.string(from: Date(timeIntervalSince1970: at / 1000)))
        case .text(let key):
            var result = text(value(key, row))
            if key.hasSuffix("Unit") || key == "unit", ["C", "F"].contains(result) { result = "°" + result }
            if key == "timestampBasis", result == "manual_entry" { result = "measurement" }
            return .text(result)
        }
    }
}

/// Row-at-a-time files keep worksheet/CSV construction off the UI thread and bound temporary storage.
enum NativeTabularExport {
    private static let namespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private static func source(_ name: String, _ library: PitTechLibrary) throws -> [JSONValue] {
        switch name {
        case "cooks": return try library.cooks.map { try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode($0)) }
        case "dishes": return library.dishes
        case "ingredients": return library.ingredients
        case "events": return library.events
        case "readings": return library.readings
        case "targets": return library.targets
        case "results": return library.results
        case "devices": return library.devices
        case "probes": return library.probes
        case "photos": return library.photos
        case "recordings": return library.recordings
        case "probeAssignments": return library.probeAssignments
        case "companionRecords": return library.companionRecords
        case "companionPhotos": return library.companionPhotos
        case "reminders": return library.reminders
        case "logReadingLinks": return library.logReadingLinks
        case "lifecycles": return library.lifecycles
        case "targetHistory": return library.targetHistory
        default: throw PitTechArchiveError.invalidArchive
        }
    }
    private static func table(_ definition: NativeExportTable, _ library: PitTechLibrary, _ values: NativeTableValues) throws -> NativeTableSheet {
        var rows = try source(definition.source, library)
        if definition.name == "Readings" { rows.sort { ($0.objectValue?["measuredAtUtcMillis"]?.numberValue ?? 0) < ($1.objectValue?["measuredAtUtcMillis"]?.numberValue ?? 0) } }
        return NativeTableSheet(name: definition.name, headers: definition.headers, rows: AnySequence(rows.lazy.map { value in definition.fields.map { values.cell($0, value.objectValue ?? [:]) } }))
    }
    private static func csvCell(_ value: NativeTableCell) -> String {
        var text = value.string
        if case .text = value, let first = text.first, "=+-@\t\r".contains(first) { text = "'" + text }
        return "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
    static func writeCSV(_ library: PitTechLibrary, to url: URL, tableName: String = "readings.csv") throws {
        guard let definition = NativeExportTables.csv.first(where: { $0.name == tableName }) else { throw PitTechArchiveError.invalidArchive }
        let sheet = try table(definition, library, NativeTableValues()), sink = try NativeTableSink(url)
        do {
            try sink.append(sheet.headers.joined(separator: ",") + "\r\n")
            for row in sheet.rows { try sink.append(row.map(csvCell).joined(separator: ",") + "\r\n") }
            sink.close()
        } catch { sink.close(); try? FileManager.default.removeItem(at: url); throw error }
    }
    static func writeCSVTables(_ library: PitTechLibrary, directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var total: Int64 = 0
        do {
            for definition in NativeExportTables.csv {
                let file = directory.appendingPathComponent(definition.name)
                try writeCSV(library, to: file, tableName: definition.name)
                total += Int64((try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0)
                guard total <= NativeArchiveLimits.backup.expanded else { throw PitTechArchiveError.tooLarge }
            }
        } catch { try? FileManager.default.removeItem(at: directory); throw error }
    }
    static func writeWorkbook(_ library: PitTechLibrary, to url: URL) throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("PitTech-tables-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        do {
            let values = NativeTableValues()
            var sheets = try NativeExportTables.workbook.map { try table($0, library, values) }
            sheets.insert(summary(library, values), at: 3)
            sheets.insert(targetsAndResults(library, values), at: 8)
            sheets.insert(derived(library, values), at: 12)
            var files: [(String, URL)] = [], expanded: Int64 = 0
            let longValues = try LongValueWriter(folder.appendingPathComponent("long-values.xml"))
            for (index, sheet) in sheets.enumerated() {
                let file = folder.appendingPathComponent("sheet\(index + 1).xml")
                try writeSheet(sheet, to: file, longValues: longValues)
                expanded += Int64((try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0)
                guard expanded <= NativeArchiveLimits.backup.expanded else { throw PitTechArchiveError.tooLarge }
                files.append(("xl/worksheets/sheet\(index + 1).xml", file))
            }
            try longValues.finish()
            if longValues.hasValues {
                expanded += longValues.sink.count
                guard expanded <= NativeArchiveLimits.backup.expanded else { throw PitTechArchiveError.tooLarge }
                sheets.append(NativeTableSheet(name: "Long Values", headers: ["Sheet", "Cell", "Part", "Text"], rows: AnySequence([])))
                files.append(("xl/worksheets/sheet\(sheets.count).xml", longValues.url))
            }
            let archive = try Archive(url: url, accessMode: .create)
            func add(_ name: String, text: String) throws {
                let data = Data(text.utf8)
                try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count), compressionMethod: .deflate) { offset, count in data.subdata(in: Int(offset)..<(Int(offset) + count)) }
            }
            let overrides = sheets.indices.map { "<Override PartName=\"/xl/worksheets/sheet\($0 + 1).xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }.joined()
            try add("[Content_Types].xml", text: "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\(overrides)</Types>")
            try add("_rels/.rels", text: "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            let entries = sheets.enumerated().map { "<sheet name=\"\(xml($0.element.name))\" sheetId=\"\($0.offset + 1)\" r:id=\"rId\($0.offset + 1)\"/>" }.joined()
            try add("xl/workbook.xml", text: "<workbook xmlns=\"\(namespace)\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>\(entries)</sheets></workbook>")
            let relationships = sheets.indices.map { "<Relationship Id=\"rId\($0 + 1)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet\($0 + 1).xml\"/>" }.joined()
            try add("xl/_rels/workbook.xml.rels", text: "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\(relationships)</Relationships>")
            for (name, file) in files { try Task.checkCancellation(); try archive.addEntry(with: name, fileURL: file, compressionMethod: .deflate) }
            guard Int64((try url.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0) <= NativeArchiveLimits.backup.compressed else { throw PitTechArchiveError.tooLarge }
        } catch { try? FileManager.default.removeItem(at: url); throw error }
    }
    private static func xml(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "'", with: "&apos;")
    }
    private static func column(_ value: Int) -> String {
        var number = value, name = ""
        while number > 0 { name = String(UnicodeScalar(65 + (number - 1) % 26)!) + name; number = (number - 1) / 26 }
        return name
    }
    private static func writeSheet(_ sheet: NativeTableSheet, to file: URL, longValues: LongValueWriter) throws {
        let sink = try NativeTableSink(file)
        defer { sink.close() }
        try sink.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"\(namespace)\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>")
        var rowIndex = 1
        func append(_ cells: [NativeTableCell]) throws {
            guard rowIndex <= 1_048_576 else { throw NativeTabularExportError.tooManyRows }
            try sink.append("<row r=\"\(rowIndex)\">")
            for (index, cell) in cells.enumerated() {
                let reference = column(index + 1) + String(rowIndex)
                switch cell {
                case .number(let value):
                    guard value.isFinite else { throw PitTechArchiveError.invalidArchive }
                    try sink.append("<c r=\"\(reference)\"><v>\(value)</v></c>")
                case .text(let value):
                    guard value.unicodeScalars.allSatisfy({ $0.value == 9 || $0.value == 10 || $0.value == 13 || $0.value >= 32 && $0.value != 0xfffe && $0.value != 0xffff }) else { throw NativeTabularExportError.invalidText }
                    let text: String
                    if value.utf16.count > 32767 {
                        try longValues.store(value, sheet: sheet.name, cell: reference)
                        text = "Full value: Long Values · \(sheet.name)!\(reference)"
                    } else { text = value }
                    try sink.append("<c r=\"\(reference)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(xml(text))</t></is></c>")
                }
            }
            try sink.append("</row>"); rowIndex += 1
        }
        try append(sheet.headers.map(NativeTableCell.text))
        for row in sheet.rows { try append(row) }
        try sink.append("</sheetData><autoFilter ref=\"A1:\(column(sheet.headers.count))\(rowIndex - 1)\"/></worksheet>")
    }
    private final class LongValueWriter {
        let url: URL
        let sink: NativeTableSink
        private var rows = 0
        var hasValues: Bool { rows > 0 }
        init(_ url: URL) throws { self.url = url; sink = try NativeTableSink(url) }
        private func row(_ cells: [NativeTableCell]) throws {
            rows += 1
            guard rows <= 1_048_576 else { throw NativeTabularExportError.tooManyRows }
            try sink.append("<row r=\"\(rows)\">")
            for (index, cell) in cells.enumerated() {
                let reference = NativeTabularExport.column(index + 1) + String(rows)
                switch cell {
                case .text(let text): try sink.append("<c r=\"\(reference)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(NativeTabularExport.xml(text))</t></is></c>")
                case .number(let number): try sink.append("<c r=\"\(reference)\"><v>\(number)</v></c>")
                }
            }
            try sink.append("</row>")
        }
        func store(_ value: String, sheet: String, cell: String) throws {
            if rows == 0 {
                try sink.append("<worksheet xmlns=\"\(NativeTabularExport.namespace)\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>")
                try row(["Sheet", "Cell", "Part", "Text"].map(NativeTableCell.text))
            }
            var part = 1, units = 0, chunk = ""
            for scalar in value.unicodeScalars {
                let size = scalar.value > 0xffff ? 2 : 1
                if units + size > 32767 {
                    try row([.text(sheet), .text(cell), .number(Double(part)), .text(chunk)])
                    chunk = ""; units = 0; part += 1
                }
                chunk.unicodeScalars.append(scalar); units += size
            }
            if !chunk.isEmpty { try row([.text(sheet), .text(cell), .number(Double(part)), .text(chunk)]) }
        }
        func finish() throws {
            if hasValues { try sink.append("</sheetData><autoFilter ref=\"A1:D\(rows)\"/></worksheet>") }
            sink.close()
        }
    }
    private static func summary(_ library: PitTechLibrary, _ values: NativeTableValues) -> NativeTableSheet {
        let header = ["Cook ID", "Cook", "Status", "Started UTC", "Started local time", "Time zone", "Finished UTC", "Finished local time", "Duration minutes", "Smoker", "Fuel", "Wood or pellet blend", "Outdoor temperature", "Outdoor unit", "Weather", "Wind", "Dishes", "Overall rating", "Notes"]
        let rows = library.cooks.lazy.map { cook -> [NativeTableCell] in
            let raw = (try? JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(cook)).objectValue) ?? [:]
            var result = [NativeExportField.text("id"), .text("title"), .text("status"), .utc("startedAtUtcMillis"), .local("startedAtUtcMillis", "startedTimeZoneId"), .text("startedTimeZoneId"), .utc("endedAtUtcMillis"), .local("endedAtUtcMillis", "startedTimeZoneId")].map { values.cell($0, raw) }
            result.append(cook.endedAtUtcMillis.map { .number(Double(max(0, $0 - cook.startedAtUtcMillis)) / 60000) } ?? .text(""))
            result += [NativeExportField.text("smokerName"), .text("fuelType"), .text("woodOrPelletBlend"), .number("outdoorTemperatureValue"), .text("outdoorTemperatureUnit"), .text("weatherNotes"), .text("windNotes")].map { values.cell($0, raw) }
            result.append(.text(library.dishes.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cook.id }.compactMap { $0["name"]?.stringValue }.joined(separator: ", ")))
            let rating = library.results.compactMap(\.objectValue).first { $0["cookId"]?.stringValue == cook.id && $0["resultType"]?.stringValue == "overall_rating" }?["numericValue"]?.numberValue
            result.append(rating.map(NativeTableCell.number) ?? .text("")); result.append(.text(cook.notes ?? "")); return result
        }
        return NativeTableSheet(name: "Cook Summary", headers: header, rows: AnySequence(rows))
    }
    private static func targetsAndResults(_ library: PitTechLibrary, _ values: NativeTableValues) -> NativeTableSheet {
        let header = ["Record ID", "Cook ID", "Dish ID", "Type", "Value", "Unit", "Text", "Notes", "Recorded UTC"]
        let rows = (library.targets + library.results).lazy.map { value -> [NativeTableCell] in
            let row = value.objectValue ?? [:], target = row["targetType"] != nil
            return [NativeExportField.text("id"), .text("cookId"), .text("dishId"), .text(target ? "targetType" : "resultType"), .number(target ? "value" : "numericValue"), .text("unit"), .text(target ? "scope" : "textValue"), .text(target ? "explanation" : "notes"), .utc(target ? "createdAtUtcMillis" : "recordedAtUtcMillis")].map { values.cell($0, row) }
        }
        return NativeTableSheet(name: "Targets Results", headers: header, rows: AnySequence(rows))
    }
    private struct Statistics {
        var sample: [String: JSONValue]; var count: Double; var minimum: Double; var maximum: Double; var last: Double; var firstAt: Double; var lastAt: Double
    }
    private static func derived(_ library: PitTechLibrary, _ values: NativeTableValues) -> NativeTableSheet {
        var groups: [String: Statistics] = [:]
        for item in library.readings {
            guard let row = item.objectValue, row["qualityStatus"]?.stringValue == "valid", let value = row["value"]?.numberValue, let at = row["measuredAtUtcMillis"]?.numberValue else { continue }
            let key = ["cookId", "dishId", "probeId", "probeName", "measurementType", "unit"].map { values.text(row[$0] ?? .null) }.joined(separator: "|")
            if var current = groups[key] {
                current.count += 1; current.minimum = min(value, current.minimum); current.maximum = max(value, current.maximum); current.firstAt = min(at, current.firstAt)
                if at >= current.lastAt { current.lastAt = at; current.last = value }; groups[key] = current
            } else { groups[key] = Statistics(sample: row, count: 1, minimum: value, maximum: value, last: value, firstAt: at, lastAt: at) }
        }
        let rows = groups.keys.sorted().flatMap { key -> [[NativeTableCell]] in
            let group = groups[key]!
            return [("reading_count", group.count), ("minimum", group.minimum), ("maximum", group.maximum), ("last", group.last)].map { metric, value in
                [values.cell(.text("cookId"), group.sample), values.cell(.text("probeName"), group.sample), .text(metric), .number(value), values.cell(.text("unit"), group.sample), .text("Valid recorded values; no interpolation"), .text(values.utc(.number(group.firstAt))), .text(values.utc(.number(group.lastAt))), .text("PitTech summary v2"), values.cell(.text("probeId"), group.sample), values.cell(.text("dishId"), group.sample), values.cell(.text("measurementType"), group.sample)]
            }
        }
        return NativeTableSheet(name: "Derived Metrics", headers: ["Cook ID", "Probe", "Metric", "Value", "Unit", "Method", "Source start UTC", "Source end UTC", "Method version", "Probe ID", "Dish ID", "Measurement"], rows: AnySequence(rows))
    }
}
