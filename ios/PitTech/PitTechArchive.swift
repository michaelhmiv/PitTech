import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct PitTechImportPreview: Sendable {
    let cooks: Int
    let dishes: Int
    let readings: Int
    let events: Int
    let version: Int
    var recipes = 0
    var collections = 0
    var cookPack: JSONValue?
}

struct PitTechImportResult {
    let imported: Int
    let skipped: Int
}

enum PitTechArchiveError: LocalizedError {
    case invalidArchive
    case unsupportedVersion
    case tooLarge
    case tooManyEntries

    var errorDescription: String? {
        switch self {
        case .invalidArchive: return "This file isn't a valid PitTech backup. Your existing library was left unchanged."
        case .unsupportedVersion: return "This PitTech backup version isn't supported. Your existing library was left unchanged."
        case .tooLarge: return "This backup is too large to safely open on this device."
        case .tooManyEntries: return "This backup contains too many files to safely open."
        }
    }
}

struct PitTechArchiveDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.zip, .pittechCookPack] }
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else { throw PitTechArchiveError.invalidArchive }
        data = contents
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

extension UTType {
    static let pittechCookPack = UTType(exportedAs: "com.pittech.cook-pack", conformingTo: .zip)
}
