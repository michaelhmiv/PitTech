import Foundation
import CryptoKit

enum NativeRelayError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let value) = self { return value }; return nil }
}
enum NativeRelayProtocol {
    static let maximumBytes: Int64 = 64 * 1024 * 1024
    static let api = URL(string: "https://api.pittech.app")!
    static func code(_ input: String) throws -> String {
        guard input.utf8.count <= 256 else { throw NativeRelayError.message("Enter the complete Cook Pack code.") }
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let raw: String
        if trimmed.contains("://") {
            guard let url = URLComponents(string: trimmed), url.user == nil, url.password == nil, url.port == nil,
                  url.query == nil, url.fragment == nil, !url.percentEncodedPath.contains("%") else { throw NativeRelayError.message("This share link is not supported.") }
            let prefix: String
            if url.scheme?.lowercased() == "https" && url.host?.lowercased() == "pittech.app" { prefix = "/s/" }
            else if url.scheme?.lowercased() == "pittech" && url.host?.lowercased() == "share" { prefix = "/" }
            else { throw NativeRelayError.message("Only PitTech share links are supported.") }
            guard url.path.hasPrefix(prefix) else { throw NativeRelayError.message("Enter a complete share link.") }
            raw = String(url.path.dropFirst(prefix.count))
            guard !raw.isEmpty, !raw.contains("/") else { throw NativeRelayError.message("Enter a complete share link.") }
        } else { raw = trimmed }
        let compact = raw.filter { !$0.isWhitespace && $0 != "-" }.uppercased()
        guard compact.range(of: "^[0-9A-F]{16}$", options: .regularExpression) != nil else { throw NativeRelayError.message("Enter all 16 letters and numbers from the Cook Pack code.") }
        let characters = Array(compact)
        return stride(from: 0, to: 16, by: 4).map { String(characters[$0..<($0 + 4)]) }.joined(separator: "-")
    }
    static func link(_ code: String) throws -> URL { URL(string: "https://pittech.app/s/" + (try self.code(code)))! }
    static func hash(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    static func date(_ value: String) -> Date? {
        let parser = ISO8601DateFormatter(); parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return parser.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
}
struct NativeRelayReceipt: Codable, Sendable, Equatable {
    let code: String
    let expiresAt: String
    let sha256: String
    let bytes: Int64
    let kind: String
    let dishes: Int
    var expiry: Date { NativeRelayProtocol.date(expiresAt) ?? .distantPast }
    var expired: Bool { expiry <= .now }
    func validate() throws {
        guard code == (try NativeRelayProtocol.code(code)), sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              (1...NativeRelayProtocol.maximumBytes).contains(bytes), ["recipe", "collection", "cook"].contains(kind), (1...20).contains(dishes),
              NativeRelayProtocol.date(expiresAt) != nil else { throw NativeRelayError.message("The relay returned an invalid receipt.") }
    }
}
