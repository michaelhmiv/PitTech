import Foundation
import Security

struct NativeRelayOwnedShare: Codable, Sendable, Identifiable {
    let id: String
    let document: String
    let revision: Double
    let owner: String
    let inputSha: String
    let title: String
    let createdAt: Date
    var receipt: NativeRelayReceipt?
    var revoked = false
}

struct NativeRelayVault {
    private let service: String
    private let root: URL?
    init(service: String = "com.pittech.relay.owner.v1", directory: URL? = nil) { self.service = service; self.root = directory }
    func all() throws -> [NativeRelayOwnedShare] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitAll]
        var result: CFTypeRef?; let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let values = result as? [Data] else { throw NativeRelayError.message("This device's saved sharing capabilities are unavailable.") }
        return try values.map { try JSONDecoder().decode(NativeRelayOwnedShare.self, from: $0) }.sorted { $0.createdAt > $1.createdAt }
    }
    func latest(_ document: String) throws -> NativeRelayOwnedShare? { try all().first { $0.document == document } }
    func save(_ value: NativeRelayOwnedShare) throws {
        guard UUID(uuidString: value.id) != nil, value.owner.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else { throw NativeRelayError.message("Invalid saved share.") }
        let data = try JSONEncoder().encode(value)
        let key: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: value.id, kSecAttrSynchronizable as String: false]
        let attrs: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(key as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound { guard SecItemAdd(key.merging(attrs) { _, b in b } as CFDictionary, nil) == errSecSuccess else { throw NativeRelayError.message("The sharing capability could not be secured on this device.") } }
        else if status != errSecSuccess { throw NativeRelayError.message("The sharing capability could not be secured on this device.") }
    }
    func directory() throws -> URL {
        var url = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("RelayPending", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues(); values.isExcludedFromBackup = true; try url.setResourceValues(values)
        return url
    }
    func pack(_ value: NativeRelayOwnedShare) throws -> URL {
        guard UUID(uuidString: value.id) != nil else { throw NativeRelayError.message("Saved share unavailable.") }
        return try directory().appendingPathComponent(value.id + ".pittech")
    }
    func prepare(_ document: String, revision: Double, title: String, bytes: Data) throws -> NativeRelayOwnedShare {
        if let previous = try latest(document), previous.receipt == nil && !previous.revoked { throw NativeRelayError.message("Retry the interrupted upload before starting a different share.") }
        guard (1...NativeRelayProtocol.maximumBytes).contains(Int64(bytes.count)) else { throw NativeRelayError.message("Choose fewer dishes or photos.") }
        let files = try FileManager.default.contentsOfDirectory(at: directory(), includingPropertiesForKeys: [.fileSizeKey]).filter { $0.pathExtension == "pittech" }
        let stored = try files.reduce(Int64(0)) { $0 + Int64(try $1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        guard stored + Int64(bytes.count) <= 2 * NativeRelayProtocol.maximumBytes else { throw NativeRelayError.message("Resolve an interrupted share before preparing another pack.") }
        var random = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, random.count, &random) == errSecSuccess else { throw NativeRelayError.message("A secure share could not be created.") }
        let owner = Data(random).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        random.withUnsafeMutableBytes { $0.initializeMemory(as: UInt8.self, repeating: 0) }
        let value = NativeRelayOwnedShare(id: UUID().uuidString.lowercased(), document: document, revision: revision, owner: owner, inputSha: NativeRelayProtocol.hash(bytes), title: String(title.prefix(160)), createdAt: .now)
        let url = try pack(value); try bytes.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        do { try save(value) } catch { try? FileManager.default.removeItem(at: url); throw error }
        return value
    }
}
