import Foundation

enum NativePrivateProjectionError: Equatable, LocalizedError {
    case invalid, unsupported
    var errorDescription: String? { self == .unsupported ? "Some saved information cannot be synced safely yet. Your journal is unchanged." : "This sync data could not be applied safely. Your journal is unchanged." }
}
struct NativePrivateProjectedRecord: Codable, Equatable {
    let recordType: String
    let recordId: String
    let payload: [String: JSONValue]
    let assetIds: [String]
}
/// Pure projection only. This does not attach an account, upload, import, arm rules,
/// start recording, or replace the local journal. Unsupported documents fail closed.
struct NativePrivateSyncProjection {
    private let contract: [String: JSONValue]
    init(data: Data) throws {
        guard let object = try JSONDecoder().decode(JSONValue.self, from: data).objectValue,
              object["contractVersion"]?.numberValue == 1, object["recordTypes"]?.objectValue != nil else { throw NativePrivateProjectionError.invalid }
        contract = object
    }
    private func strings(_ v: JSONValue?) -> [String] { v?.arrayValue?.compactMap(\.stringValue) ?? [] }
    private func yes(_ v: JSONValue?) -> Bool { if case .bool(true)? = v { return true }; return false }
    private func validate(_ value: JSONValue, descriptor: [String: JSONValue], depth: Int = 0) throws {
        guard depth <= 40 else { throw NativePrivateProjectionError.invalid }
        let n: [String: JSONValue]
        if let ref = descriptor["ref"]?.stringValue, let found = contract["definitions"]?.objectValue?[ref]?.objectValue { n = found } else { n = descriptor }
        if yes(n["unsupported"]) { throw NativePrivateProjectionError.unsupported }
        if value == .null && yes(n["nullable"]) { return }
        if let choices = n["any"]?.arrayValue {
            for choice in choices { if let node = choice.objectValue, (try? validate(value, descriptor: node, depth: depth + 1)) != nil { return } }
            throw NativePrivateProjectionError.invalid
        }
        if let selector = n["select"]?.stringValue {
            guard let key = value.objectValue?[selector]?.stringValue, let selected = n["cases"]?.objectValue?[key]?.objectValue else { throw NativePrivateProjectionError.invalid }
            try validate(value, descriptor: selected, depth: depth + 1); return
        }
        if let options = n["enum"]?.arrayValue, !options.contains(value) { throw NativePrivateProjectionError.invalid }
        switch n["type"]?.stringValue {
        case "object":
            guard let o = value.objectValue, let fields = n["fields"]?.objectValue,
                  Set(o.keys).isSubset(of: Set(fields.keys)), strings(n["required"]).allSatisfy({ o[$0] != nil }) else { throw NativePrivateProjectionError.invalid }
            for (key, v) in o { guard let schema = fields[key]?.objectValue else { throw NativePrivateProjectionError.invalid }; try validate(v, descriptor: schema, depth: depth + 1) }
        case "array":
            guard let a = value.arrayValue, Double(a.count) <= (n["max"]?.numberValue ?? 100000), let schema = n["items"]?.objectValue else { throw NativePrivateProjectionError.invalid }
            for v in a { try validate(v, descriptor: schema, depth: depth + 1) }
        case "string", "id", "uuid", "json":
            guard let s = value.stringValue, Double(s.utf16.count) <= (n["max"]?.numberValue ?? 32768) else { throw NativePrivateProjectionError.invalid }
            let type = n["type"]?.stringValue
            if type == "id" && (s.isEmpty || s.rangeOfCharacter(from: CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789:_-").inverted) != nil) { throw NativePrivateProjectionError.invalid }
            if type == "uuid" && (s.utf16.count != 36 || UUID(uuidString: s) == nil || s != s.lowercased()) { throw NativePrivateProjectionError.invalid }
            if type == "json" {
                guard let data = s.data(using: .utf8), let ref = n["refJson"]?.stringValue, let schema = contract["definitions"]?.objectValue?[ref]?.objectValue else { throw NativePrivateProjectionError.invalid }
                let parsed = try JSONDecoder().decode(JSONValue.self, from: data); try validate(parsed, descriptor: schema, depth: depth + 1)
            }
        case "integer", "number":
            guard let number = value.numberValue, number.isFinite, number >= (n["min"]?.numberValue ?? -9007199254740991), number <= (n["max"]?.numberValue ?? 9007199254740991), n["type"]?.stringValue != "integer" || number.rounded(.towardZero) == number && abs(number) <= 9007199254740991 else { throw NativePrivateProjectionError.invalid }
        case "boolean": if case .bool = value {} else { throw NativePrivateProjectionError.invalid }
        default: throw NativePrivateProjectionError.invalid
        }
    }
    func project(_ library: PitTechLibrary, assets: [String: String] = [:]) throws -> [NativePrivateProjectedRecord] {
        try project(JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(library)), assets: assets)
    }
    func project(_ archive: JSONValue, assets: [String: String] = [:]) throws -> [NativePrivateProjectedRecord] {
        guard let root = archive.objectValue, let specs = contract["recordTypes"]?.objectValue,
              Set(root.keys).isSubset(of: Set(specs.keys).union(strings(contract["excludedRootFields"]))) else { throw NativePrivateProjectionError.invalid }
        var rows: [NativePrivateProjectedRecord] = []
        for type in specs.keys.sorted() {
            guard let spec = specs[type]?.objectValue, let schema = spec["payload"]?.objectValue, let fields = schema["fields"]?.objectValue, let identity = spec["key"]?.stringValue else { throw NativePrivateProjectionError.invalid }
            guard root[type] == nil || root[type]?.arrayValue != nil else { throw NativePrivateProjectionError.invalid }
            for raw in root[type]?.arrayValue ?? [] {
                guard let o = raw.objectValue, let id = o[identity]?.stringValue,
                      Set(o.keys).isSubset(of: Set(fields.keys).union(strings(spec["omit"]))) else { throw NativePrivateProjectionError.invalid }
                var p = o.filter { fields[$0.key] != nil }
                if fields["assetId"] != nil { guard let asset = assets[type + "|" + id] else { throw NativePrivateProjectionError.invalid }; p["assetId"] = .string(asset) }
                try validate(.object(p), descriptor: schema)
                rows.append(NativePrivateProjectedRecord(recordType: type, recordId: id, payload: p, assetIds: p["assetId"]?.stringValue.map { [$0] } ?? []))
            }
        }
        try validateRows(rows)
        return rows.sorted { $0.recordType + "|" + $0.recordId < $1.recordType + "|" + $1.recordId }
    }
    func validateRows(_ rows: [NativePrivateProjectedRecord]) throws {
        let pairs = rows.map { ($0.recordType + "|" + $0.recordId, $0.payload) }
        guard rows.count <= 100000, Set(pairs.map(\.0)).count == rows.count else { throw NativePrivateProjectionError.invalid }
        let live = Dictionary(uniqueKeysWithValues: pairs); var media = Set<String>()
        for row in rows {
            guard let spec = contract["recordTypes"]?.objectValue?[row.recordType]?.objectValue, let schema = spec["payload"]?.objectValue, let key = spec["key"]?.stringValue, row.payload[key]?.stringValue == row.recordId else { throw NativePrivateProjectionError.invalid }
            try validate(.object(row.payload), descriptor: schema)
            if schema["fields"]?.objectValue?["assetId"] != nil { guard row.assetIds == row.payload["assetId"]?.stringValue.map({ [$0] }), media.insert(row.recordId).inserted else { throw NativePrivateProjectionError.invalid } }
            else if !row.assetIds.isEmpty { throw NativePrivateProjectionError.invalid }
            for raw in spec["references"]?.arrayValue ?? [] {
                guard let ref = raw.objectValue, let field = ref["field"]?.stringValue, let target = ref["target"]?.stringValue else { throw NativePrivateProjectionError.invalid }
                guard let id = row.payload[field]?.stringValue else { continue }
                guard let parent = live[target + "|" + id] else { throw NativePrivateProjectionError.invalid }
                for (selfKey, targetKey) in ref["same"]?.objectValue ?? [:] { guard let targetKey = targetKey.stringValue, row.payload[selfKey] == parent[targetKey] else { throw NativePrivateProjectionError.invalid } }
                if yes(ref["parentOrdinal"]) { guard (parent["revisionNumber"]?.numberValue ?? .infinity) < (row.payload["revisionNumber"]?.numberValue ?? -.infinity) else { throw NativePrivateProjectionError.invalid } }
            }
            if row.recordType == "logReadingLinks" {
                guard let reading = live["readings|" + (row.payload["readingId"]?.stringValue ?? "")], let event = live["events|" + (row.payload["eventId"]?.stringValue ?? "")], reading["cookId"] == event["cookId"], reading["source"]?.stringValue == "manual" else { throw NativePrivateProjectionError.invalid }
            }
            if row.recordType == "reminderRules" {
                guard let raw = row.payload["triggerConfig"]?.stringValue, let data = raw.data(using: .utf8), try JSONDecoder().decode(JSONValue.self, from: data).objectValue?["type"] == row.payload["kind"], row.payload["schemaVersion"]?.numberValue == 1 else { throw NativePrivateProjectionError.invalid }
            }
        }
    }
}
