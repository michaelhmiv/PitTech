import Foundation
import CryptoKit
import Security

/// Accounts remain unavailable until the complete accounts/social/sync/deletion phase is approved.
enum NativeAccountProtocol {
    static var enabled: Bool { NativeOnlineConfiguration.current.enabled }
    static func verifier() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw NativeAccountError.invalid }
        return Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func proof(_ verifier: String) -> String { SHA256.hash(data: Data(verifier.utf8)).map { String(format: "%02x", $0) }.joined() }
    static func credential(_ value: String) -> Bool {
        guard value.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else { return false }
        let base = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/") + "="
        guard let bytes = Data(base64Encoded: base), bytes.count == 32 else { return false }
        return bytes.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") == value
    }
    static func email(_ input: String) throws -> String {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard input.utf8.count <= 254, input.unicodeScalars.allSatisfy({ $0.value < 128 }), value.split(separator: "@").last?.split(separator: ".").allSatisfy({ $0.count <= 63 }) == true, value.range(of: "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$", options: .regularExpression) != nil,
              !value.hasPrefix("."), !value.contains(".."), !value.contains(".@"), value.split(separator: "@")[0].count <= 64 else { throw NativeAccountError.invalid }
        return value
    }
    /// Only the exact production origin, path and canonical fragment are accepted. Never exchange on receipt.
    static func link(_ input: URL, configuration: NativeOnlineConfiguration = .current) throws -> (String, String) {
        guard input.absoluteString.count <= 256, let parts = URLComponents(url: input, resolvingAgainstBaseURL: false),
              parts.scheme == "https", parts.host == configuration.publicHost, parts.port == nil, parts.user == nil, parts.password == nil,
              parts.percentEncodedPath == "/auth", parts.query == nil, let fragment = parts.percentEncodedFragment else { throw NativeAccountError.invalid }
        let fields = fragment.components(separatedBy: "&")
        guard fields.count == 2, fields[0].hasPrefix("challenge="), fields[1].hasPrefix("token=") else { throw NativeAccountError.invalid }
        let id = String(fields[0].dropFirst(10)), token = String(fields[1].dropFirst(6))
        guard UUID(uuidString: id) != nil, credential(token) else { throw NativeAccountError.invalid }
        return (id.lowercased(), token)
    }
    static func date(_ value: String) throws -> Date {
        let parser = ISO8601DateFormatter(); parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let result = parser.date(from: value) { return result }
        parser.formatOptions = [.withInternetDateTime]
        guard let result = parser.date(from: value) else { throw NativeAccountError.invalid }; return result
    }
}

enum NativeAccountError: LocalizedError {
    case invalid, unavailable, network, status(Int)
    case providerUnavailable, identityLinkRequired, providerSignInUnavailable, providerAttachFailed
    var errorDescription: String? {
        switch self {
        case .invalid: return "Check the email, code or sign-in link and try again."
        case .unavailable: return "Accounts are not available in this build. Your local cookbook works without signing in."
        case .network: return "Couldn't reach PitTech. Retry the same request when you're online."
        case .status(401): return "This sign-in has expired or is unavailable. Request a new code."
        case .status(429): return "Too many attempts. Wait a minute before trying again."
        case .providerUnavailable: return "Sign in with Apple is unavailable. Try email sign-in instead."
        case .identityLinkRequired: return "Sign in with your existing method first, then add Apple sign-in in account settings."
        case .providerSignInUnavailable: return "This Apple sign-in expired or is unavailable. Try again, or sign in by email first and then add Apple sign-in in account settings."
        case .providerAttachFailed: return "Couldn't add Apple sign-in. Try again."
        default: return "PitTech couldn't complete sign-in. Retry later."
        }
    }
}
struct NativeAccountSession: Codable {
    let accessToken: String
    let refreshToken: String
    let accessExpiresAt: String
    let sessionExpiresAt: String
    let userId: String
    func validate() throws {
        guard NativeAccountProtocol.credential(accessToken), NativeAccountProtocol.credential(refreshToken), UUID(uuidString: userId) != nil,
              try NativeAccountProtocol.date(accessExpiresAt) <= NativeAccountProtocol.date(sessionExpiresAt) else { throw NativeAccountError.invalid }
    }
}
struct NativeAccountChallenge: Codable {
    let id: String
    let email: String
    let verifier: String
    let created: Date
    var accepted = false
    var operation: String?
    var secret: String?
    var isLink = false
}
/// Proof-bound provider challenge. The id, verifier and operation persist before
/// the provider UI is presented so a lost response retries the exact exchange.
/// `bound` marks challenges created with an access bearer for attach.
struct NativeProviderChallenge: Codable {
    let id: String
    let provider: String
    let verifier: String
    let created: Date
    var operation: String?
    let bound: Bool
}
struct NativeAccountState: Codable {
    var challenge: NativeAccountChallenge?
    var session: NativeAccountSession?
    var refreshOperation: String?
    var logoutToken: String?
    var deletion: NativeDeletionRecord?
    var providerChallenge: NativeProviderChallenge?
    var accountEmail: String?
    var linkedProviders: [String]?
}
protocol NativeAccountStorage { func load() throws -> NativeAccountState; func save(_ value: NativeAccountState) throws }
struct NativeAccountVault: NativeAccountStorage {
    var service = NativeOnlineConfiguration.current.scopedName("com.pittech.account.production.v1")
    private var key: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "state", kSecAttrSynchronizable as String: false] }
    func load() throws -> NativeAccountState {
        guard let bytes = try loadBytes() else { return NativeAccountState() }
        return try JSONDecoder().decode(NativeAccountState.self, from: bytes)
    }
    func loadBytes() throws -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching(key.merging([kSecReturnData as String: true]) { _, b in b } as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let bytes = result as? Data, bytes.count <= 16384 else { throw NativeAccountError.invalid }
        return bytes
    }
    func save(_ value: NativeAccountState) throws {
        try saveBytes(JSONEncoder().encode(value))
    }
    func saveBytes(_ bytes: Data) throws {
        guard bytes.count <= 16384 else { throw NativeAccountError.invalid }
        let attrs: [String: Any] = [kSecValueData as String: bytes, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(key as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(key.merging(attrs) { _, b in b } as CFDictionary, nil) == errSecSuccess else { throw NativeAccountError.invalid }
        } else if status != errSecSuccess { throw NativeAccountError.invalid }
    }
}
