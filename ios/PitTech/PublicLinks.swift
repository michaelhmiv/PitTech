import Foundation

/// A public link is navigation only. It never authenticates, imports or publishes.
enum NativePublicLink {
    static func destination(_ input: String) throws -> String {
        guard let c = URLComponents(string: input), c.query == nil, c.fragment == nil,
              c.user == nil, c.password == nil, c.port == nil, c.percentEncodedPath == c.path else { throw NativePublicError.invalid }
        let parts = c.path.split(separator: "/", omittingEmptySubsequences: false)
        let kind: String, value: String
        if c.scheme == "https", c.host == "pittech.app", parts.count == 3, parts[0].isEmpty {
            kind = String(parts[1]); value = String(parts[2])
        } else if c.scheme == "pittech", ["recipe", "profile"].contains(c.host ?? ""), parts.count == 2, parts[0].isEmpty {
            kind = c.host == "recipe" ? "c" : "p"; value = String(parts[1])
        } else { throw NativePublicError.invalid }
        if kind == "c", value.range(of: "^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$", options: .regularExpression) != nil { return value.lowercased() }
        if kind == "p", value.range(of: "^[a-z0-9_]{3,24}$", options: .regularExpression) != nil { return "profile:" + value }
        throw NativePublicError.invalid
    }
}
struct NativePublicProfile: Decodable {
    struct Item: Decodable, Identifiable { let id: String; let url: String }
    let handle: String
    let displayName: String
    let items: [Item]
    let nextCursor: String?
    func validate(_ expected: String) throws {
        guard handle == expected, handle.range(of: "^[a-z0-9_]{3,24}$", options: .regularExpression) != nil,
              displayName.utf16.count <= 160, items.count <= 20, Set(items.map(\.id)).count == items.count,
              nextCursor.map({ UUID(uuidString: $0) != nil }) ?? true else { throw NativePublicError.invalid }
        for item in items { guard UUID(uuidString: item.id) != nil, item.url == "https://pittech.app/c/" + item.id else { throw NativePublicError.invalid } }
    }
}
extension NativePublicCoordinator {
    func receivePublic(_ url: URL) -> Bool {
        guard enabled, let destination = try? NativePublicLink.destination(url.absoluteString) else { return false }
        path = [destination]; linkSequence += 1; return true
    }
}
