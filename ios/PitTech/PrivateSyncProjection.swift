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
    private func validate(_ value: JSONValue, descriptor: [String: JSONValue], depth: Int = 0, parent: [String: JSONValue]? = nil) throws {
        guard depth <= 40 else { throw NativePrivateProjectionError.invalid }
        let n: [String: JSONValue]
        if let ref = descriptor["ref"]?.stringValue, let found = contract["definitions"]?.objectValue?[ref]?.objectValue { n = found } else { n = descriptor }
        if yes(n["unsupported"]) { throw NativePrivateProjectionError.unsupported }
        if let selector = n["selectSibling"]?.stringValue {
            guard let key = parent?[selector]?.stringValue, let selected = n["cases"]?.objectValue?[key]?.objectValue else { throw NativePrivateProjectionError.unsupported }
            try validate(value, descriptor: selected, depth: depth + 1); return
        }
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
            for (key, v) in o { guard let schema = fields[key]?.objectValue else { throw NativePrivateProjectionError.invalid }; try validate(v, descriptor: schema, depth: depth + 1, parent: o) }
        case "array":
            guard let a = value.arrayValue, Double(a.count) >= (n["min"]?.numberValue ?? 0), Double(a.count) <= (n["max"]?.numberValue ?? 100000), let schema = n["items"]?.objectValue else { throw NativePrivateProjectionError.invalid }
            for v in a { try validate(v, descriptor: schema, depth: depth + 1) }
            if let key = n["uniqueBy"]?.stringValue { guard Set(a.compactMap { $0.objectValue?[key]?.stringValue }).count == a.count else { throw NativePrivateProjectionError.invalid } }
        case "map":
            guard let o = value.objectValue, let schema = n["values"]?.objectValue,
                  Double(o.count) <= (n["maxKeys"]?.numberValue ?? 100000) else { throw NativePrivateProjectionError.invalid }
            for (key, v) in o {
                guard Double(key.utf16.count) <= (n["keyMax"]?.numberValue ?? 32768) else { throw NativePrivateProjectionError.invalid }
                try validate(v, descriptor: schema, depth: depth + 1)
            }
        case "string", "id", "uuid", "json":
            guard let s = value.stringValue, Double(s.utf16.count) <= (n["max"]?.numberValue ?? 32768) else { throw NativePrivateProjectionError.invalid }
            if yes(n["nonBlank"]) && s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw NativePrivateProjectionError.invalid }
            let type = n["type"]?.stringValue
            if type == "id" && (s.isEmpty || s.rangeOfCharacter(from: CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789:_-").inverted) != nil) { throw NativePrivateProjectionError.invalid }
            if type == "uuid" && (s.utf16.count != 36 || UUID(uuidString: s) == nil || s != s.lowercased()) { throw NativePrivateProjectionError.invalid }
            if type == "json" {
                guard let data = s.data(using: .utf8), let ref = n["refJson"]?.stringValue, let schema = contract["definitions"]?.objectValue?[ref]?.objectValue else { throw NativePrivateProjectionError.invalid }
                let parsed = try JSONDecoder().decode(JSONValue.self, from: data); try validate(parsed, descriptor: schema, depth: depth + 1)
            }
        case "integer", "number":
            guard let number = value.numberValue, number.isFinite, number >= (n["min"]?.numberValue ?? -9007199254740991), number <= (n["max"]?.numberValue ?? 9007199254740991), n["type"]?.stringValue != "integer" || number.rounded(.towardZero) == number && abs(number) <= 9007199254740991 else { throw NativePrivateProjectionError.invalid }
            if let minimum = n["exclusiveMin"]?.numberValue, number <= minimum { throw NativePrivateProjectionError.invalid }
        case "boolean": if case .bool = value {} else { throw NativePrivateProjectionError.invalid }
        default: throw NativePrivateProjectionError.invalid
        }
    }
    private func stripOmit(_ value: JSONValue, parts: ArraySlice<String>) -> JSONValue {
        guard let first = parts.first, var o = value.objectValue else { return value }
        if parts.count == 1 {
            if first == "*" { o.removeAll() } else { o.removeValue(forKey: first) }
            return .object(o)
        }
        if first == "*" { for key in o.keys { o[key] = stripOmit(o[key]!, parts: parts.dropFirst()) } }
        else if o[first] != nil { o[first] = stripOmit(o[first]!, parts: parts.dropFirst()) }
        return .object(o)
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
                guard var o = raw.objectValue else { throw NativePrivateProjectionError.invalid }
                if type == "companionRecords", let kind = o["kind"]?.stringValue, strings(contract["excludedDocumentKinds"]).contains(kind) { continue }
                for (local, portable) in spec["aliases"]?.objectValue ?? [:] {
                    guard let key = portable.stringValue else { throw NativePrivateProjectionError.invalid }
                    if let value = o[local] {
                        guard o[key] == nil || o[key] == value else { throw NativePrivateProjectionError.invalid }
                        o[key] = value; o.removeValue(forKey: local)
                    }
                }
                guard let id = o[identity]?.stringValue, Set(o.keys).isSubset(of: Set(fields.keys).union(strings(spec["omit"]))) else { throw NativePrivateProjectionError.invalid }
                for key in strings(spec["parseJsonFields"]) {
                    if let text = o[key]?.stringValue { o[key] = try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)) }
                }
                var p = o.filter { fields[$0.key] != nil }
                if fields["assetId"] != nil { guard let asset = assets[type + "|" + id] else { throw NativePrivateProjectionError.invalid }; p["assetId"] = .string(asset) }
                if type == "companionRecords", let kind = p["kind"]?.stringValue {
                    for path in strings(contract["documentRules"]?.objectValue?[kind]?.objectValue?["omit"]) {
                        if p["payload"] != nil { p["payload"] = stripOmit(p["payload"]!, parts: path.split(separator: ".").map(String.init)[...]) }
                    }
                }
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
            if row.recordType == "events", let text = row.payload["noteMetadataJson"]?.stringValue {
                let note = try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
                for id in strings(note.objectValue?["dishIds"]) { guard live["dishes|" + id]?["cookId"] == row.payload["cookId"] else { throw NativePrivateProjectionError.invalid } }
            }
            if row.recordType == "companionRecords" {
                guard let kind = row.payload["kind"]?.stringValue, let rule = contract["documentRules"]?.objectValue?[kind]?.objectValue,
                      let doc = row.payload["payload"]?.objectValue else { throw NativePrivateProjectionError.invalid }
                let cook = row.payload["cookId"]?.stringValue
                guard !(rule["scope"]?.stringValue == "global" && cook != nil), !(rule["scope"]?.stringValue == "cook" && cook == nil) else { throw NativePrivateProjectionError.invalid }
                for (docKey, rowKey) in rule["sameFields"]?.objectValue ?? [:] {
                    guard let rowKey = rowKey.stringValue, (doc[docKey] ?? .null) == (row.payload[rowKey] ?? .null) else { throw NativePrivateProjectionError.invalid }
                }
                for raw in rule["references"]?.arrayValue ?? [] {
                    guard let ref = raw.objectValue, let field = ref["field"]?.stringValue, let target = ref["target"]?.stringValue, let same = ref["sameCook"]?.stringValue, let id = doc[field]?.stringValue else { throw NativePrivateProjectionError.invalid }
                    let parent = live[target + "|" + id]
                    if parent == nil && yes(ref["allowMissing"]) { continue }
                    guard let parent, parent["cookId"] == (same == "$record" ? row.payload["cookId"] : doc[same]) else { throw NativePrivateProjectionError.invalid }
                }
                if kind == "plan" {
                    let dishes = doc["draft"]?.objectValue?["dishes"]?.arrayValue ?? []
                    let dishIds = strings(doc["dishIds"])
                    let stepIds = Set(doc["steps"]?.arrayValue?.compactMap { $0.objectValue?["id"]?.stringValue } ?? [])
                    guard doc["dishIds"]?.arrayValue != nil, dishIds.count == dishes.count, Set(dishIds).count == dishIds.count,
                          dishIds.allSatisfy({ live["dishes|" + $0]?["cookId"] == row.payload["cookId"] }),
                          Set((doc["progress"]?.objectValue ?? [:]).keys).isSubset(of: stepIds) else { throw NativePrivateProjectionError.invalid }
                }
                if kind == "alert" {
                    if let dish = doc["dishId"]?.stringValue { guard live["dishes|" + dish]?["cookId"] == row.payload["cookId"] else { throw NativePrivateProjectionError.invalid } }
                    if doc["type"]?.stringValue == "target" { guard let t = doc["targetF"]?.numberValue, (32...600).contains(t) else { throw NativePrivateProjectionError.invalid } }
                    if doc["type"]?.stringValue == "pit_range" { guard let lo = doc["lowF"]?.numberValue, let hi = doc["highF"]?.numberValue, lo < hi else { throw NativePrivateProjectionError.invalid } }
                }
                if kind == "upcoming" || kind == "serve_goal" {
                    let dishes = doc["book"]?.objectValue?["draft"]?.objectValue?["dishes"]?.arrayValue ?? []
                    let schedules = doc["schedules"]?.arrayValue ?? []
                    let order = schedules.compactMap { $0.objectValue?["dish"]?.numberValue }.sorted()
                    guard schedules.count == dishes.count, order.enumerated().allSatisfy({ $0.element == Double($0.offset) }),
                          schedules.allSatisfy({ ($0.objectValue?["max"]?.numberValue ?? -.infinity) >= ($0.objectValue?["min"]?.numberValue ?? .infinity) }) else { throw NativePrivateProjectionError.invalid }
                }
                if kind == "sharing_draft" || kind == "collection_revision" || kind == "recipe_reference" {
                    let entries = doc["entries"]?.arrayValue?.compactMap(\.objectValue) ?? []
                    for e in entries {
                        let photoIds = strings(e["photoIds"])
                        guard Set(photoIds).count == photoIds.count else { throw NativePrivateProjectionError.invalid }
                        for id in photoIds { guard let photo = live["companionPhotos|" + id], photo["recordId"] == row.payload["id"], photo["action"] == e["id"] else { throw NativePrivateProjectionError.invalid } }
                    }
                    if let hero = doc["heroPhotoId"]?.stringValue {
                        guard entries.contains(where: { $0["included"] == .bool(true) && strings($0["photoIds"]).contains(hero) }) else { throw NativePrivateProjectionError.invalid }
                    }
                }
                if kind == "guide_presentation", let dishIds = doc["dishIds"]?.arrayValue?.compactMap(\.stringValue) {
                    guard Set(dishIds).count == dishIds.count, dishIds.allSatisfy({ live["dishes|" + $0]?["cookId"] == row.payload["cookId"] }) else { throw NativePrivateProjectionError.invalid }
                }
            }
        }
    }
}
