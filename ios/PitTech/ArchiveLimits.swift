import Foundation

/// Full backups preserve the established Android range. Network packs have a separate quota.
struct NativeArchiveLimits: Equatable {
    let compressed: Int64
    let expanded: Int64
    let entry: Int64
    let entries: Int
    static let backup = Self(compressed: 350 * 1024 * 1024, expanded: 300 * 1024 * 1024, entry: 45 * 1024 * 1024, entries: 10_000)
    static let cookPack = Self(compressed: 64 * 1024 * 1024, expanded: 64 * 1024 * 1024, entry: 16 * 1024 * 1024, entries: 1_000)
}

extension CookStore {
    /// Own the provider file before showing a preview, with bounded disk copying rather than a RAM mirror.
    nonisolated static func stageArchive(at source: URL, limits: NativeArchiveLimits = .backup) throws -> URL {
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip")
        guard let input = InputStream(url: source), let output = OutputStream(url: destination, append: false) else { throw PitTechArchiveError.invalidArchive }
        input.open(); output.open()
        defer { input.close(); output.close() }
        do {
            var total: Int64 = 0
            var buffer = [UInt8](repeating: 0, count: 32 * 1024)
            while true {
                try Task.checkCancellation()
                let count = input.read(&buffer, maxLength: buffer.count)
                guard count >= 0 else { throw input.streamError ?? PitTechArchiveError.invalidArchive }
                if count == 0 { break }
                total += Int64(count)
                guard total <= limits.compressed else { throw PitTechArchiveError.tooLarge }
                var offset = 0
                try buffer.withUnsafeBufferPointer { pointer in
                    while offset < count {
                        let written = output.write(pointer.baseAddress! + offset, maxLength: count - offset)
                        guard written > 0 else { throw output.streamError ?? CocoaError(.fileWriteUnknown) }
                        offset += written
                    }
                }
            }
            guard total > 0 else { throw PitTechArchiveError.invalidArchive }
            return destination
        } catch { try? FileManager.default.removeItem(at: destination); throw error }
    }
}
