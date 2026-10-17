import XCTest
import Foundation
import ZIPFoundation
@testable import PitTech

private final class ExportCellReader: NSObject, XMLParserDelegate {
    var cells: [String: String] = [:], types: [String: String] = [:]
    var active: String?, reading = false
    func parser(_ parser: XMLParser, didStartElement element: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        if element == "c", let reference = attributes["r"] { active = reference; cells[reference] = ""; types[reference] = attributes["t"] ?? "n" }
        if element == "t" || element == "v" { reading = true }
    }
    func parser(_ parser: XMLParser, foundCharacters text: String) { if reading, let active { cells[active, default: ""] += text } }
    func parser(_ parser: XMLParser, didEndElement element: String, namespaceURI: String?, qualifiedName: String?) {
        if element == "c" { active = nil }; if element == "t" || element == "v" { reading = false }
    }
}
private final class ExportSheetReader: NSObject, XMLParserDelegate {
    var ids: [String: String] = [:]
    func parser(_ parser: XMLParser, didStartElement element: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        if element == "sheet", let name = attributes["name"], let id = attributes["sheetId"] { ids[name] = id }
    }
}
@MainActor final class TabularExportTests: XCTestCase {
    private func bytes(_ archive: Archive, _ path: String) throws -> Data {
        let entry = try XCTUnwrap(archive[path]); var data = Data()
        let crc = try archive.extract(entry) { data.append($0) }; XCTAssertEqual(crc, entry.checksum); return data
    }
    private func cells(_ archive: Archive, _ id: String) throws -> ExportCellReader {
        let reader = ExportCellReader(), parser = XMLParser(data: try bytes(archive, "xl/worksheets/sheet\(id).xml"))
        parser.delegate = reader; XCTAssertTrue(parser.parse(), parser.parserError?.localizedDescription ?? "Invalid worksheet"); return reader
    }
    func testWorkbookNumericPrecisionLiteralFormulasUnicodeChunksAndScopedCSV() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        store.startCook(title: "Pork", firstDish: "Pork"); let cook = store.activeCooks[0].id, dish = store.dishRecords(cook)[0].id
        let name = "=Probe, \"same\"\n"
        try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, title: "Measured", temperatures: [
            NoteTemperatureDraft(probeName: name, measurementType: "food", valueText: "74.125", unit: "°C", measuredAt: 1700000000123, dishId: dish),
            NoteTemperatureDraft(probeName: name, measurementType: "pit", valueText: "84.375", unit: "°C", measuredAt: 1700000000223, dishId: dish)]))
        let longText = String(repeating: "🌶", count: 17000) + "Line, \"quoted\"\nNext"
        store.library.cooks[0].notes = longText
        store.startCook(title: "Unrelated meal", firstDish: "Chicken")
        let scope = store.library.tabularScope(cook), csv = folder.appendingPathComponent("readings.csv"), workbook = folder.appendingPathComponent("workbook.xlsx")
        try NativeTabularExport.writeCSV(scope, to: csv); try NativeTabularExport.writeWorkbook(scope, to: workbook)
        let text = try String(contentsOf: csv, encoding: .utf8)
        XCTAssertTrue(text.contains("74.125")); XCTAssertTrue(text.contains("°C")); XCTAssertTrue(text.contains("\"'=Probe, \"\"same\"\"\n\"")); XCTAssertTrue(text.contains(".123Z"))
        let archive = try Archive(url: workbook, accessMode: .read), names = ExportSheetReader(), parser = XMLParser(data: try bytes(archive, "xl/workbook.xml"))
        parser.delegate = names; XCTAssertTrue(parser.parse()); XCTAssertEqual(names.ids.count, 19)
        let readings = try cells(archive, XCTUnwrap(names.ids["Readings"]))
        XCTAssertEqual(readings.cells["F2"], "74.125"); XCTAssertEqual(readings.types["F2"], "n")
        XCTAssertEqual(readings.cells["D2"], "=Probe, \"same\"\n"); XCTAssertEqual(readings.types["D2"], "inlineStr"); XCTAssertEqual(readings.cells["G2"], "°C")
        let summary = try cells(archive, XCTUnwrap(names.ids["Cook Summary"]))
        XCTAssertEqual(summary.cells["B2"], "Pork"); XCTAssertNil(summary.cells["B3"]); XCTAssertTrue(summary.cells["S2"]?.contains("Long Values") == true)
        let chunks = try cells(archive, XCTUnwrap(names.ids["Long Values"]))
        let restored = (2...3).compactMap { chunks.cells["D\($0)"] }.joined()
        XCTAssertEqual(restored, longText)
        for chunk in chunks.cells.filter({ $0.key.hasPrefix("D") }).values { XCTAssertLessThanOrEqual(chunk.utf16.count, 32767) }
        let metrics = try cells(archive, XCTUnwrap(names.ids["Derived Metrics"]))
        XCTAssertEqual(metrics.cells.filter { $0.key.hasPrefix("C") && $0.value == "reading_count" }.count, 2)
    }
    func testFullBackupContainsSharedHumanReadableTablesWithoutChangingStoredReadings() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); store.startCook(title: "Pork", firstDish: "Pork")
        let cook = store.activeCooks[0].id, dish = store.dishRecords(cook)[0].id
        try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, title: "Measured", temperatures: [NoteTemperatureDraft(probeName: "Food", valueText: "0", unit: "°C", dishId: dish)]))
        let original = store.library.readings, bytes = try store.exportArchive(), url = folder.appendingPathComponent("full.zip")
        try bytes.write(to: url); let archive = try Archive(url: url, accessMode: .read)
        XCTAssertNotNil(archive["csv/readings.csv"]); XCTAssertNotNil(archive["csv/reference_photos.csv"])
        XCTAssertEqual(store.library.readings, original)
        let recipient = CookStore(directory: folder.appendingPathComponent("recipient")); _ = try recipient.importArchive(bytes)
        XCTAssertEqual(recipient.library.readings[0].objectValue?["value"]?.numberValue, 0)
        XCTAssertEqual(recipient.library.readings[0].objectValue?["unit"]?.stringValue, "°C")
    }
}
