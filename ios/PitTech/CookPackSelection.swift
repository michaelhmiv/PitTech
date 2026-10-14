import Foundation
import CryptoKit
import ZIPFoundation

enum NativeCookPackSelection {
    static func identity(_ value: String) -> String {
        var bytes = Array(Insecure.MD5.hash(data: Data(value.utf8)))
        bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])).uuidString.lowercased()
    }
    static func library(_ source: PitTechLibrary, selected: Set<String>) throws -> (library: PitTechLibrary, photoOrigins: [String: String]) {
        try source.validateCookExperience()
        let available = Set(source.recipes.compactMap { $0.objectValue?["currentRevisionId"]?.stringValue })
        guard !selected.isEmpty, selected.isSubset(of: available) else { throw PitTechArchiveError.invalidArchive }
        var result = PitTechLibrary(), origins: [String: String] = [:]
        result.preferences = nil
        result.recipes = source.recipes.filter { $0.objectValue?["currentRevisionId"]?.stringValue.map(selected.contains) == true }
        let recipeIds = Set(result.recipes.compactMap { $0.objectValue?["id"]?.stringValue })
        result.recipeRevisions = source.recipeRevisions.filter { $0.objectValue?["recipeId"]?.stringValue.map(recipeIds.contains) == true }
        let revisionIds = Set(result.recipeRevisions.compactMap { $0.objectValue?["id"]?.stringValue })
        result.recipeAssets = source.recipeAssets.filter { $0.objectValue?["recipeRevisionId"]?.stringValue.map(revisionIds.contains) == true }
        for asset in result.recipeAssets { guard let row = asset.objectValue, let id = row["id"]?.stringValue, let path = row["archivePath"]?.stringValue else { throw PitTechArchiveError.invalidArchive }; origins[id] = path }
        for record in source.companionRecords {
            guard let row = record.objectValue, let sourceId = row["id"]?.stringValue, let payload = row["payload"] else { throw PitTechArchiveError.invalidArchive }
            var document = try NativeSharingDocument.decode(payload)
            document.entries = document.entries.filter { $0.sourceRevisionId.map(selected.contains) == true }
            if document.entries.isEmpty { continue }
            let recordId = identity("PitTech Cook Pack selection:\(sourceId):\(selected.sorted().joined(separator: ","))")
            let mappings = Dictionary(uniqueKeysWithValues: document.entries.flatMap(\.photoIds).map { ($0, identity("PitTech Cook Pack photo:\(recordId):\($0)")) })
            let sourcePhotos = source.companionPhotos.compactMap(\.objectValue).filter { $0["recordId"]?.stringValue == sourceId && $0["id"]?.stringValue.map { mappings[$0] != nil } == true }
            guard sourcePhotos.count == mappings.count else { throw CookNoteError.missingPhoto }
            for photo in sourcePhotos {
                guard let id = photo["id"]?.stringValue, let target = mappings[id], let path = photo["archivePath"]?.stringValue else { throw CookNoteError.missingPhoto }
                var copy = photo; copy["id"] = .string(target); copy["recordId"] = .string(recordId); copy["archivePath"] = .string("attachments/\(target).jpg")
                result.companionPhotos.append(.object(copy)); origins[target] = path
            }
            for index in document.entries.indices { document.entries[index].photoIds = try document.entries[index].photoIds.map { guard let id = mappings[$0] else { throw CookNoteError.missingPhoto }; return id } }
            document.heroPhotoId = document.heroPhotoId.flatMap { mappings[$0] }; document.collectionId = recordId; document.parentRevisionId = nil
            if document.entries.count == 1 { document.title = try document.entries[0].snapshot.name }
            var scoped = row; scoped["id"] = .string(recordId); scoped["kind"] = .string(document.entries.count == 1 ? "recipe_reference" : "collection_revision"); scoped["title"] = .string(document.title)
            scoped["payload"] = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(document)); result.companionRecords.append(.object(scoped))
        }
        try result.validateCookExperience(); return (result, origins)
    }
    static func archive(at url: URL, source: PitTechLibrary, metadata: JSONValue, selected: Set<String>) throws -> Data {
        let scoped = try library(source, selected: selected), archive = try Archive(url: url, accessMode: .read)
        var bytes: [String: Data] = [:]
        for (id, path) in scoped.photoOrigins {
            guard let entry = archive[path] else { throw CookNoteError.missingPhoto }
            var data = Data()
            let crc = try archive.extract(entry) { chunk in
                guard Int64(data.count + chunk.count) <= NativeArchiveLimits.cookPack.entry else { throw PitTechArchiveError.tooLarge }
                data.append(chunk)
            }
            guard crc == entry.checksum else { throw PitTechArchiveError.invalidArchive }; bytes[id] = data
        }
        var header = metadata.objectValue ?? [:]
        header["kind"] = .string(scoped.library.recipes.count == 1 ? "recipe" : "collection")
        header["recipeRevisionIds"] = .array(scoped.library.recipeRevisions.compactMap { $0.objectValue?["id"] }); header["dishIds"] = .array([])
        header["includedStory"] = .bool(scoped.library.companionRecords.contains { record in
            (try? NativeSharingDocument.decode(record.objectValue?["payload"] ?? .null).entries.contains { $0.includeStory }) == true
        })
        return try NativeCookPackArchive.write(scoped.library, metadata: .object(header), attachments: bytes)
    }
}
