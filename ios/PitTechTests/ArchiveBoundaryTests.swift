import XCTest
import ZIPFoundation
@testable import PitTech

@MainActor final class ArchiveBoundaryTests: XCTestCase {
    private func fixture(at url: URL, photoCount: Int, photoBytes: Int, compression: CompressionMethod = .none) throws -> (PitTechLibrary, [String]) {
        var library = PitTechLibrary(); let cook = CookRecord.start(title: "Portable backup")
        library.cooks = [cook]
        let ids = (0..<photoCount).map { _ in UUID().uuidString.lowercased() }
        library.photos = ids.map { .object(["id": .string($0), "cookId": .string(cook.id), "archivePath": .string("attachments/\($0).jpg"), "mimeType": .string("image/jpeg")]) }
        let archive = try Archive(url: url, accessMode: .create)
        func add(_ path: String, data: Data) throws {
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count)) { position, size in data.subdata(in: Int(position)..<(Int(position) + size)) }
        }
        try add("manifest.json", data: JSONSerialization.data(withJSONObject: ["format": "PitTech complete cook archive", "archiveVersion": PitTechLibrary.archiveVersion, "includesPreferences": false]))
        try add("data/pittech.json", data: JSONEncoder().encode(library))
        for id in ids {
            try archive.addEntry(with: "attachments/\(id).jpg", type: .file, uncompressedSize: Int64(photoBytes), compressionMethod: compression) { _, size in Data(repeating: 65, count: size) }
        }
        return (library, ids)
    }

    func testFullBackupExceedsOldLimitsWhileCookPackQuotaRemainsSmaller() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("large.zip"), bytes = 18 * 1024 * 1024
        let (library, ids) = try fixture(at: url, photoCount: 4, photoBytes: bytes, compression: .deflate)
        XCTAssertEqual(try CookStore.previewArchive(at: url).cooks, 1)
        XCTAssertThrowsError(try CookStore.previewArchive(at: url, limits: .cookPack))
        let staged = try CookStore.stageArchive(at: url)
        defer { try? FileManager.default.removeItem(at: staged) }
        let store = CookStore(directory: folder.appendingPathComponent("imported"))
        _ = try store.importArchive(at: staged)
        XCTAssertEqual(store.library.photos, library.photos)
        for id in ids { XCTAssertEqual(try store.photoURL("attachments/\(id).jpg").resourceValues(forKeys: [.fileSizeKey]).fileSize, bytes) }
        _ = try store.importArchive(at: staged)
        XCTAssertEqual(store.library.photos.count, 4)
    }

    func testCorruptedMediaFailsBeforeAnyLibraryOrPhotoMutation() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("bad.zip")
        _ = try fixture(at: url, photoCount: 1, photoBytes: 32)
        var bytes = try Data(contentsOf: url)
        let media = try XCTUnwrap(bytes.range(of: Data(repeating: 65, count: 32)))
        bytes[media.lowerBound] = 66
        try bytes.write(to: url)
        let store = CookStore(directory: folder.appendingPathComponent("local"))
        store.startCook(title: "Keep me", firstDish: "Pork")
        let before = try JSONEncoder().encode(store.library)
        XCTAssertThrowsError(try store.importArchive(at: url))
        XCTAssertEqual(store.library.cooks.first?.title, "Keep me")
        XCTAssertEqual(try JSONDecoder().decode(PitTechLibrary.self, from: before).photos, store.library.photos)
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent("local/attachments").path))
    }

    func testFailedPhotoCommitKeepsExistingFilesAndRollsBackPriorMoves() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("rollback.zip")
        let (_, ids) = try fixture(at: url, photoCount: 2, photoBytes: 32)
        let store = CookStore(directory: folder.appendingPathComponent("local"))
        let collision = store.photoURL("attachments/\(ids[1]).jpg")
        try FileManager.default.createDirectory(at: collision.deletingLastPathComponent(), withIntermediateDirectories: true)
        let original = Data("existing independent file".utf8); try original.write(to: collision)
        XCTAssertThrowsError(try store.importArchive(at: url))
        XCTAssertTrue(store.library.cooks.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.photoURL("attachments/\(ids[0]).jpg").path))
        XCTAssertEqual(try Data(contentsOf: collision), original)
    }

    func testBoundedStagingAndSharedQuotaConstants() throws {
        XCTAssertEqual(NativeArchiveLimits.backup.compressed, 350 * 1024 * 1024)
        XCTAssertEqual(NativeArchiveLimits.backup.expanded, 300 * 1024 * 1024)
        XCTAssertEqual(NativeArchiveLimits.backup.entry, 45 * 1024 * 1024)
        XCTAssertEqual(NativeArchiveLimits.backup.entries, 10_000)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        try Data(repeating: 1, count: 9).write(to: url)
        XCTAssertThrowsError(try CookStore.stageArchive(at: url, limits: .init(compressed: 8, expanded: 8, entry: 8, entries: 1)))
    }
}
