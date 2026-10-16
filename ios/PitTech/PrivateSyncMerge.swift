import Foundation

enum NativeSyncError: Error { case invalid }
struct NativeSyncStamp: Codable, Equatable { var counter: Int64; var clientId: String }
struct NativeSyncRecord: Codable, Equatable {
    var recordType: String
    var recordId: String
    var version: NativeSyncStamp
    var deleted: Bool
    var payload: [String: JSONValue]?
    var assetIds: [String]
    private enum CodingKeys: String, CodingKey { case recordType, recordId, version, deleted, payload, assetIds }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(recordType, forKey: .recordType); try c.encode(recordId, forKey: .recordId); try c.encode(version, forKey: .version); try c.encode(deleted, forKey: .deleted); try c.encode(assetIds, forKey: .assetIds)
        if let payload { try c.encode(payload, forKey: .payload) } else { try c.encodeNil(forKey: .payload) }
    }
    var key: String { recordType + "|" + recordId }
    func sameContent(_ other: Self) -> Bool { deleted == other.deleted && payload == other.payload && assetIds == other.assetIds }
}
struct NativeSyncConflict: Codable, Equatable { var id: String; var winner: NativeSyncRecord; var loser: NativeSyncRecord; var reason: String }
struct NativeSyncMergeResult: Codable, Equatable { var records: [NativeSyncRecord]; var conflicts: [NativeSyncConflict]; var blocked: Bool; var nextCounter: Int64 }
/// Pure merge foundation only. Transport, projection, relationships and durable application remain separate gates.
enum NativePrivateSyncMerge {
    static let types: Set<String> = ["cooks", "dishes", "ingredients", "events", "readings", "targets", "results", "devices", "probes", "photos", "recipes", "recipeRevisions", "recipeAssets", "companionRecords", "companionPhotos", "mealPresets", "dishRecipeSources", "lifecycles", "targetHistory", "logReadingLinks", "reminderRules"]
    private static let immutable: Set<String> = ["readings", "recipeRevisions"]
    private static let maximum: Int64 = 9_007_199_254_740_991
    private static func bytes<T: Encodable>(_ value: T) throws -> Int { let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return try encoder.encode(value).count }
    private static func bounded(_ value: JSONValue, depth: Int = 0) throws {
        guard depth <= 24 else { throw NativeSyncError.invalid }
        switch value {
        case .number(let n): guard n.isFinite else { throw NativeSyncError.invalid }
        case .string(let s): guard s.utf16.count <= 16 * 1024 * 1024 else { throw NativeSyncError.invalid }
        case .array(let a): guard a.count <= 100000 else { throw NativeSyncError.invalid }; for v in a { try bounded(v, depth: depth + 1) }
        case .object(let o): for (key, v) in o { guard !["__proto__", "constructor", "prototype"].contains(key) else { throw NativeSyncError.invalid }; try bounded(v, depth: depth + 1) }
        default: break
        }
    }
    static func validate(_ rows: [NativeSyncRecord]) throws {
        guard rows.count <= 100000, Set(rows.map(\.key)).count == rows.count, try bytes(rows) <= 32 * 1024 * 1024 else { throw NativeSyncError.invalid }
        for r in rows {
            guard types.contains(r.recordType), (1...200).contains(r.recordId.utf16.count), r.recordId.rangeOfCharacter(from: CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789:_-").inverted) == nil,
                  r.version.counter >= 1, r.version.counter < maximum, UUID(uuidString: r.version.clientId) != nil, r.version.clientId == r.version.clientId.lowercased(),
                  r.assetIds.count <= 100, Set(r.assetIds).count == r.assetIds.count, r.assetIds.allSatisfy({ UUID(uuidString: $0) != nil && $0 == $0.lowercased() }),
                  r.deleted ? r.payload == nil && r.assetIds.isEmpty : r.payload != nil else { throw NativeSyncError.invalid }
            if let payload = r.payload { try bounded(.object(payload)); guard try bytes(payload) <= 16 * 1024 * 1024 else { throw NativeSyncError.invalid } }
        }
    }
    private static func less(_ a: NativeSyncRecord, _ b: NativeSyncRecord) -> Bool { a.version.counter == b.version.counter ? a.version.clientId < b.version.clientId : a.version.counter < b.version.counter }
    static func merge(base: [NativeSyncRecord], local: [NativeSyncRecord], remote: [NativeSyncRecord]) throws -> NativeSyncMergeResult {
        try validate(base); try validate(local); try validate(remote)
        let old = Dictionary(uniqueKeysWithValues: base.map { ($0.key, $0) }), ours = Dictionary(uniqueKeysWithValues: local.map { ($0.key, $0) }), theirs = Dictionary(uniqueKeysWithValues: remote.map { ($0.key, $0) })
        var records: [NativeSyncRecord] = [], conflicts: [NativeSyncConflict] = []
        func conflict(_ winner: NativeSyncRecord, _ loser: NativeSyncRecord, _ reason: String) {
            let id = [winner.recordType, winner.recordId, reason, String(winner.version.counter), winner.version.clientId, String(loser.version.counter), loser.version.clientId].joined(separator: "|")
            conflicts.append(NativeSyncConflict(id: NativeRelayProtocol.hash(Data(id.utf8)), winner: winner, loser: loser, reason: reason))
        }
        for key in Set(old.keys).union(ours.keys).union(theirs.keys).sorted() {
            let prior = old[key], a = ours[key], b = theirs[key]
            if let prior {
                guard let a, let b else { throw NativeSyncError.invalid }
                for r in [a, b] where r != prior { guard r.version.counter > prior.version.counter, !prior.deleted || r.deleted else { throw NativeSyncError.invalid } }
                guard a.version != b.version || a == b else { throw NativeSyncError.invalid }
                if !prior.deleted && immutable.contains(prior.recordType) {
                    var bad = [a, b].filter { !$0.deleted && !$0.sameContent(prior) }; if bad.count == 2 && bad[0] == bad[1] { bad.removeLast() }
                    if !bad.isEmpty { records.append(prior); for r in bad.sorted(by: less) { conflict(prior, r, "immutable_identity") }; continue }
                }
            }
            guard let a, let b else { records.append((a ?? b)!); continue }
            if a == b { records.append(a); continue }
            guard a.version != b.version else { throw NativeSyncError.invalid }
            let changedA = prior == nil || a != prior, changedB = prior == nil || b != prior
            if !changedA || !changedB { records.append(changedA ? a : b); continue }
            let deletion = a.deleted != b.deleted, winner = deletion ? (a.deleted ? a : b) : (less(a, b) ? b : a)
            let loser = winner == a ? b : a; records.append(winner)
            if !a.sameContent(b) { conflict(winner, loser, deletion ? "delete_edit" : immutable.contains(winner.recordType) ? "immutable_identity" : "edit_edit") }
        }
        try validate(records)
        let observed = (base + local + remote).reduce(Int64(0)) { max($0, $1.version.counter) }
        guard observed < maximum - 1 else { throw NativeSyncError.invalid }
        let result = NativeSyncMergeResult(records: records, conflicts: conflicts, blocked: conflicts.contains { $0.reason == "immutable_identity" }, nextCounter: observed + 1)
        guard try bytes(result) <= 64 * 1024 * 1024 else { throw NativeSyncError.invalid }; return result
    }
}

private struct NativeSyncCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int? = nil
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}
extension NativeSyncRecord {
    init(from decoder: Decoder) throws {
        let raw = try decoder.container(keyedBy: NativeSyncCodingKey.self)
        guard Set(raw.allKeys.map(\.stringValue)) == Set(["recordType", "recordId", "version", "deleted", "payload", "assetIds"]) else { throw NativeSyncError.invalid }
        let c = try decoder.container(keyedBy: CodingKeys.self)
        recordType = try c.decode(String.self, forKey: .recordType); recordId = try c.decode(String.self, forKey: .recordId)
        version = try c.decode(NativeSyncStamp.self, forKey: .version); deleted = try c.decode(Bool.self, forKey: .deleted)
        payload = try c.decodeIfPresent([String: JSONValue].self, forKey: .payload); assetIds = try c.decode([String].self, forKey: .assetIds)
    }
}
extension NativeSyncStamp {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: NativeSyncCodingKey.self)
        guard Set(c.allKeys.map(\.stringValue)) == Set(["counter", "clientId"]), let n = NativeSyncCodingKey(stringValue: "counter"), let id = NativeSyncCodingKey(stringValue: "clientId") else { throw NativeSyncError.invalid }
        counter = try c.decode(Int64.self, forKey: n); clientId = try c.decode(String.self, forKey: id)
    }
}
