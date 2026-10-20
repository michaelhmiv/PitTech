import Foundation

struct NativePrivateSyncPhotoEntry: Codable, Equatable {
    var assetId: String
    var sha256: String
    var bytes: Int
    var mime: String
    var received: [Int]
    var complete: Bool
    func validated() throws -> Self {
        guard UUID(uuidString: assetId) != nil, assetId == assetId.lowercased(), sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              (1...16 * 1024 * 1024).contains(bytes), ["image/jpeg", "image/png", "image/webp"].contains(mime),
              Set(received).count == received.count, received.allSatisfy({ (0..<16).contains($0) }) else { throw NativeSyncError.invalid }
        guard !complete || received.count == (bytes + 1024 * 1024 - 1) / (1024 * 1024) else { throw NativeSyncError.invalid }
        return self
    }
}

/** Durable per-account private-sync journal. Device-only: never exported, never synced,
 * never attached to another account. Holds the last acknowledged base snapshot plus the
 * server version, per-record mutation metadata, retained conflict copies with their
 * resolution IDs, resumable photo part state and the last honest sync status.
 * Sensitivity matches the on-device CookStore journal, which this mirrors. */
struct NativePrivateSyncState: Codable, Equatable {
    var version = 1
    var account = ""
    var serverVersion: Int64 = 0
    var maxCounter: Int64 = 1
    var base: [NativeSyncRecord] = []
    var conflicts: [NativeSyncConflict] = []
    var resolved: [String] = []
    var pending = false
    var mutationId: String?
    var mutationSha: String?
    var photos: [String: NativePrivateSyncPhotoEntry] = [:]
    var lastSyncedAt: Int64 = 0
    var outcome = "ok"
    var message: String?
    // Optional preserves decoding of existing v1 account-bound ledgers.
    var automatic: Bool?
    func validated(for user: String) throws -> Self {
        var copy = self
        guard version == 1, account == user, (0...9_007_199_254_740_990).contains(serverVersion), (1...9_007_199_254_740_990).contains(maxCounter),
              base.count <= 100000, conflicts.count <= 10000, Set(resolved).count == resolved.count,
              resolved.allSatisfy({ $0.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }),
              ["ok", "waiting", "retry"].contains(outcome), photos.count <= 100000 else { throw NativeSyncError.invalid }
        guard (mutationId == nil) == (mutationSha == nil) else { throw NativeSyncError.invalid }
        if let id = mutationId { guard UUID(uuidString: id) != nil, id == id.lowercased(), mutationSha!.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw NativeSyncError.invalid } }
        for (key, entry) in photos {
            guard key.range(of: "^(photos|recipeAssets|companionPhotos)\\|[A-Za-z0-9:_-]{1,200}$", options: .regularExpression) != nil else { throw NativeSyncError.invalid }
            copy.photos[key] = try entry.validated()
        }
        try NativePrivateSyncMerge.validateRecords(base); try NativePrivateSyncMerge.validateConflicts(conflicts)
        if let message { copy.message = String(message.prefix(500)) }
        return copy
    }
}

protocol NativePrivateSyncStorage: AnyObject {
    func deviceId() throws -> String
    func libraryAccount() throws -> String?
    func attachLibrary(_ user: String) throws
    func load(_ user: String) throws -> NativePrivateSyncState
    func save(_ user: String, _ state: NativePrivateSyncState) throws
    func purgeAccount(_ user: String) throws
    func bodyURL(_ user: String, _ mutationId: String) throws -> URL
    func writeBody(_ user: String, _ mutationId: String, _ bytes: Data) throws
    func readBody(_ user: String, _ mutationId: String) throws -> Data
    func stagingDirectory() throws -> URL
}

/** In-memory storage for unit tests. Same contract, no filesystem. */
final class NativePrivateSyncMemoryStore: NativePrivateSyncStorage {
    private var states: [String: NativePrivateSyncState] = [:]
    private var bodies: [String: Data] = [:]
    private var device: String?
    private var libraryOwner: String?
    func libraryAccount() throws -> String? { lock.lock(); defer { lock.unlock() }; return libraryOwner }
    func attachLibrary(_ user: String) throws { guard UUID(uuidString: user) != nil else { throw NativeSyncError.invalid }; lock.lock(); defer { lock.unlock() }; libraryOwner = user }
    private let lock = NSLock()
    func deviceId() throws -> String { lock.lock(); defer { lock.unlock() }; if device == nil { device = UUID().uuidString.lowercased() }; return device! }
    func load(_ user: String) throws -> NativePrivateSyncState { lock.lock(); defer { lock.unlock() }; return states[user] ?? NativePrivateSyncState(account: user) }
    func save(_ user: String, _ state: NativePrivateSyncState) throws {
        guard state.account == user else { throw NativeSyncError.invalid }
        let round = try JSONDecoder().decode(NativePrivateSyncState.self, from: try JSONEncoder().encode(state))
        lock.lock(); defer { lock.unlock() }; states[user] = try round.validated(for: user)
    }
    func purgeAccount(_ user: String) throws { lock.lock(); defer { lock.unlock() }; if libraryOwner == user { libraryOwner = nil }; states.removeValue(forKey: user); bodies = bodies.filter { !$0.key.hasPrefix(user + "|") } }
    func bodyURL(_ user: String, _ mutationId: String) throws -> URL {
        guard UUID(uuidString: user) != nil, UUID(uuidString: mutationId) != nil else { throw NativeSyncError.invalid }
        return FileManager.default.temporaryDirectory.appendingPathComponent(user + "." + mutationId + ".body")
    }
    func writeBody(_ user: String, _ mutationId: String, _ bytes: Data) throws {
        guard UUID(uuidString: user) != nil, UUID(uuidString: mutationId) != nil, !bytes.isEmpty else { throw NativeSyncError.invalid }
        lock.lock(); defer { lock.unlock() }; bodies[user + "|" + mutationId] = bytes
    }
    func readBody(_ user: String, _ mutationId: String) throws -> Data {
        lock.lock(); defer { lock.unlock() }; guard let bytes = bodies[user + "|" + mutationId] else { throw NativeSyncError.invalid }; return bytes
    }
    func stagingDirectory() throws -> URL { FileManager.default.temporaryDirectory }
}

final class NativePrivateSyncStore: NativePrivateSyncStorage {
    private let directory: URL
    private let lock = NSLock()
    private static let stateLimit = 72 * 1024 * 1024
    private static let bodyLimit = 32 * 1024 * 1024 + 1024
    init(directory: URL) { self.directory = directory }
    func libraryAccount() throws -> String? {
        lock.lock(); defer { lock.unlock() }
        let url = try prepared().appendingPathComponent("library-account.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let bytes = try Data(contentsOf: url); guard bytes.count <= 1024 else { throw NativeSyncError.invalid }
        let user = try JSONDecoder().decode(String.self, from: bytes)
        guard UUID(uuidString: user) != nil else { throw NativeSyncError.invalid }; return user
    }
    func attachLibrary(_ user: String) throws {
        guard UUID(uuidString: user) != nil else { throw NativeSyncError.invalid }
        lock.lock(); defer { lock.unlock() }
        let url = try prepared().appendingPathComponent("library-account.json")
        try JSONEncoder().encode(user).write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    }
    convenience init() throws {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        self.init(directory: root.appendingPathComponent("PrivateSync-v1", isDirectory: true))
    }
    private func prepared() throws -> URL {
        var directory = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        try? directory.setResourceValues(values)
        return directory
    }
    private func stateURL(_ user: String) throws -> URL {
        guard UUID(uuidString: user) != nil, user == user.lowercased() else { throw NativeSyncError.invalid }
        return try prepared().appendingPathComponent(NativeRelayProtocol.hash(Data(user.utf8)) + ".json")
    }
    func deviceId() throws -> String {
        lock.lock(); defer { lock.unlock() }
        let url = try prepared().appendingPathComponent("device.json")
        if let data = try? Data(contentsOf: url), let raw = try? JSONDecoder().decode([String: String].self, from: data), let id = raw["deviceId"],
           UUID(uuidString: id) != nil, id == id.lowercased() { return id }
        guard (try? Data(contentsOf: url)) == nil else { throw NativeSyncError.invalid }
        let id = UUID().uuidString.lowercased()
        try JSONEncoder().encode(["version": "1", "deviceId": id]).write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        return id
    }
    func load(_ user: String) throws -> NativePrivateSyncState {
        lock.lock(); defer { lock.unlock() }
        let url = try stateURL(user)
        guard let data = try? Data(contentsOf: url) else { return NativePrivateSyncState(account: user) }
        guard data.count <= Self.stateLimit else { throw NativeSyncError.invalid }
        return try JSONDecoder().decode(NativePrivateSyncState.self, from: data).validated(for: user)
    }
    func save(_ user: String, _ state: NativePrivateSyncState) throws {
        lock.lock(); defer { lock.unlock() }
        let valid = try state.validated(for: user)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(valid)
        guard data.count <= Self.stateLimit else { throw NativeSyncError.invalid }
        let url = try stateURL(user)
        try data.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        guard (try? Data(contentsOf: url)) == data else { throw NativeSyncError.invalid }
    }
    /** Remove the body's exact retry bytes first; the durable reference survives an interruption. */
    func purgeAccount(_ user: String) throws {
        lock.lock(); defer { lock.unlock() }
        let association = try prepared().appendingPathComponent("library-account.json")
        if let bytes = try? Data(contentsOf: association), (try? JSONDecoder().decode(String.self, from: bytes)) == user { try FileManager.default.removeItem(at: association) }
        let url = try stateURL(user)
        if let id = (try? JSONDecoder().decode(NativePrivateSyncState.self, from: Data(contentsOf: url)))?.mutationId { try? FileManager.default.removeItem(at: bodyFileURL(user, id)) }
        let prefix = NativeRelayProtocol.hash(Data(user.utf8)) + "."
        for file in (try? FileManager.default.contentsOfDirectory(at: prepared(), includingPropertiesForKeys: nil)) ?? [] where file.lastPathComponent.hasPrefix(prefix) && file.pathExtension == "body" { try? FileManager.default.removeItem(at: file) }
        try? FileManager.default.removeItem(at: url)
        guard (try? Data(contentsOf: url)) == nil else { throw NativeSyncError.invalid }
    }
    private func bodyFileURL(_ user: String, _ mutationId: String) throws -> URL {
        guard UUID(uuidString: user) != nil, UUID(uuidString: mutationId) != nil else { throw NativeSyncError.invalid }
        return try prepared().appendingPathComponent(NativeRelayProtocol.hash(Data(user.utf8)) + "." + mutationId + ".body")
    }
    func bodyURL(_ user: String, _ mutationId: String) throws -> URL { lock.lock(); defer { lock.unlock() }; return try bodyFileURL(user, mutationId) }
    func writeBody(_ user: String, _ mutationId: String, _ bytes: Data) throws {
        lock.lock(); defer { lock.unlock() }
        guard (1...Self.bodyLimit).contains(bytes.count) else { throw NativeSyncError.invalid }
        let url = try bodyFileURL(user, mutationId)
        try bytes.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        guard (try? Data(contentsOf: url)) == bytes else { throw NativeSyncError.invalid }
    }
    func readBody(_ user: String, _ mutationId: String) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        let data = try Data(contentsOf: bodyFileURL(user, mutationId))
        guard data.count <= Self.bodyLimit else { throw NativeSyncError.invalid }
        return data
    }
    func stagingDirectory() throws -> URL {
        lock.lock(); defer { lock.unlock() }
        let url = try prepared().appendingPathComponent("staging", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
