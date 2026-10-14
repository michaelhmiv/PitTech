import Foundation
import ZIPFoundation

struct NativeArchiveRecipePreview: Identifiable, Sendable {
    let id: String
    let title: String
    let detail: String
    let description: String
    let hasGuide: Bool
    let thumbnail: Data?
}
struct NativeArchiveIntake: Identifiable, Sendable {
    let id = UUID().uuidString.lowercased()
    let url: URL
    let preview: PitTechImportPreview
    let library: PitTechLibrary
    let recipes: [NativeArchiveRecipePreview]
}

enum NativeArchiveIntakeReader {
    static func prepare(_ source: URL) throws -> NativeArchiveIntake {
        let staged = try CookStore.stageArchive(at: source)
        do {
            let preview = try CookStore.previewArchive(at: staged), archive = try Archive(url: staged, accessMode: .read)
            guard let entry = archive["data/pittech.json"] else { throw PitTechArchiveError.invalidArchive }
            var data = Data()
            let crc = try archive.extract(entry) { chunk in
                guard Int64(data.count + chunk.count) <= NativeArchiveLimits.backup.entry else { throw PitTechArchiveError.tooLarge }
                data.append(chunk)
            }
            guard crc == entry.checksum else { throw PitTechArchiveError.invalidArchive }
            try Task.checkCancellation()
            let library = try JSONDecoder().decode(PitTechLibrary.self, from: data)
            let recipes = try preview.cookPack == nil ? [] : recipePreviews(library, archive: archive)
            return NativeArchiveIntake(url: staged, preview: preview, library: library, recipes: recipes)
        } catch { try? FileManager.default.removeItem(at: staged); throw error }
    }
    private static func recipePreviews(_ library: PitTechLibrary, archive: Archive) throws -> [NativeArchiveRecipePreview] {
        try library.recipes.map { value in
            try Task.checkCancellation()
            guard let row = value.objectValue, let id = row["currentRevisionId"]?.stringValue,
                  let raw = library.recipeRevisions.first(where: { $0.objectValue?["id"]?.stringValue == id })?.objectValue?["snapshotJson"]?.stringValue else { throw PitTechArchiveError.invalidArchive }
            let snapshot = try RecipeSnapshot.decode(raw), dish = snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue ?? [:]
            let detail = [dish["foodType"]?.stringValue, dish["cut"]?.stringValue].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
            var thumbnail: Data?
            if let path = library.recipeAssets.first(where: { $0.objectValue?["recipeRevisionId"]?.stringValue == id })?.objectValue?["archivePath"]?.stringValue,
               let entry = archive[path] {
                var data = Data()
                let crc = try archive.extract(entry) { chunk in
                    guard Int64(data.count + chunk.count) <= NativeArchiveLimits.cookPack.entry else { throw PitTechArchiveError.tooLarge }; data.append(chunk)
                }
                guard crc == entry.checksum else { throw PitTechArchiveError.invalidArchive }
                thumbnail = try NativeCookPackPhoto.derivative(data, maximumPixels: 384)
            }
            return NativeArchiveRecipePreview(id: id, title: snapshot.name, detail: detail, description: snapshot.description, hasGuide: !snapshot.observations.isEmpty, thumbnail: thumbnail)
        }
    }
}

@MainActor extension CookStore {
    func receiveArchive(at source: URL) async {
        guard source.isFileURL else { return }
        let request = UUID().uuidString
        archiveIntakeRequest = request
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let preparation = Task.detached(priority: .userInitiated) { try NativeArchiveIntakeReader.prepare(source) }
        do {
            let intake = try await withTaskCancellationHandler(operation: { try await preparation.value }, onCancel: { preparation.cancel() })
            guard !Task.isCancelled, archiveIntakeRequest == request else { try? FileManager.default.removeItem(at: intake.url); return }
            discardArchiveIntake(); archiveIntake = intake; archiveMessage = nil
        } catch is CancellationError { }
        catch { if archiveIntakeRequest == request { archiveMessage = error.localizedDescription } }
    }
    func discardArchiveIntake() {
        if let intake = archiveIntake { try? FileManager.default.removeItem(at: intake.url) }
        archiveIntake = nil
    }
    func importSelectedRecipes(_ source: NativeArchiveIntake, selected: Set<String>) async throws {
        guard let metadata = source.preview.cookPack else { throw PitTechArchiveError.invalidArchive }
        let preparation = Task.detached(priority: .userInitiated) {
            try NativeCookPackSelection.archive(at: source.url, source: source.library, metadata: metadata, selected: selected)
        }
        let data = try await withTaskCancellationHandler(operation: { try await preparation.value }, onCancel: { preparation.cancel() })
        try Task.checkCancellation()
        _ = try importArchive(data)
        archiveMessage = "Saved \(selected.count) \(selected.count == 1 ? "recipe" : "recipes"). Find them in Recipes."
    }
}
