import SwiftUI
import UniformTypeIdentifiers

private struct NativeTableDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText, .spreadsheet] }
    var data = Data()
    init(data: Data = Data()) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadUnknown) }; self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct NativeDataExportButtons: View {
    @Environment(CookStore.self) private var store
    var cookId: String? = nil
    @State private var preparing = false
    @State private var exporting = false
    @State private var workbook = false
    @State private var document = NativeTableDocument()
    @State private var staged: URL?
    @State private var error: String?
    @State private var task: Task<Void, Never>?
    private var contentType: UTType { workbook ? (UTType(filenameExtension: "xlsx") ?? .spreadsheet) : .commaSeparatedText }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("Export readings CSV") { prepare(workbook: false) }.disabled(preparing).frame(minHeight: 44).accessibilityIdentifier("export-readings-csv")
            Button("Export workbook") { prepare(workbook: true) }.disabled(preparing).frame(minHeight: 44).accessibilityIdentifier("export-workbook")
            if preparing { ProgressView("Preparing export…") }
            if let error { Text(error).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
        }.buttonStyle(.borderless).fileExporter(isPresented: $exporting, document: document, contentType: contentType, defaultFilename: "PitTech-data") { result in
            if case .failure(let failure) = result { error = failure.localizedDescription }
            cleanup()
        }.onDisappear { task?.cancel() }
    }
    private func prepare(workbook: Bool) {
        guard !preparing else { return }
        self.workbook = workbook; preparing = true; error = nil
        let snapshot = store.library.tabularScope(cookId)
        task = Task { @MainActor in
            let renderer = Task.detached(priority: .userInitiated) { () throws -> URL in
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent("PitTech-export-" + UUID().uuidString)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let url = folder.appendingPathComponent(workbook ? "PitTech-data.xlsx" : "PitTech-data.csv")
                do {
                    if workbook { try NativeTabularExport.writeWorkbook(snapshot, to: url) } else { try NativeTabularExport.writeCSV(snapshot, to: url) }
                    try Task.checkCancellation(); return url
                } catch { try? FileManager.default.removeItem(at: folder); throw error }
            }
            do {
                let url = try await withTaskCancellationHandler(operation: { try await renderer.value }, onCancel: { renderer.cancel() })
                staged = url
                try Task.checkCancellation()
                document = NativeTableDocument(data: try Data(contentsOf: url, options: .mappedIfSafe)); exporting = true
            } catch is CancellationError { cleanup() }
            catch { cleanup(); self.error = error.localizedDescription }
            preparing = false
        }
    }
    private func cleanup() {
        document = NativeTableDocument()
        if let staged { try? FileManager.default.removeItem(at: staged.deletingLastPathComponent()) }; staged = nil
    }
}

extension PitTechLibrary {
    func tabularScope(_ cookId: String?) -> Self {
        var result = self
        result.companionRecords = companionRecords.filter { $0.objectValue?["kind"]?.stringValue != "monitor_status" }
        guard let cookId else { return result }
        func own(_ rows: [JSONValue]) -> [JSONValue] { rows.filter { $0.objectValue?["cookId"]?.stringValue == cookId } }
        result.cooks = cooks.filter { $0.id == cookId }; result.preferences = nil
        result.dishes = own(dishes); result.ingredients = own(ingredients); result.events = own(events); result.readings = own(readings); result.targets = own(targets); result.results = own(results)
        result.devices = own(devices); result.probes = own(probes); result.photos = own(photos); result.recordings = own(recordings); result.probeAssignments = own(probeAssignments)
        result.reminders = own(reminders); result.companionRecords = own(result.companionRecords); result.lifecycles = own(lifecycles); result.targetHistory = own(targetHistory)
        let records = Set(result.companionRecords.compactMap { $0.objectValue?["id"]?.stringValue }), events = Set(result.events.compactMap { $0.objectValue?["id"]?.stringValue })
        result.companionPhotos = companionPhotos.filter { $0.objectValue?["recordId"]?.stringValue.map(records.contains) == true }
        result.logReadingLinks = logReadingLinks.filter { $0.objectValue?["eventId"]?.stringValue.map(events.contains) == true }
        return result
    }
}
