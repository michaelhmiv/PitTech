import Foundation
import CryptoKit
import Security

// Protocol intentionally exposes observations and disconnection only.
enum NativeTelemetryProvider: String, Codable, CaseIterable, Identifiable {
    case combustion, home_assistant, fireboard, flame_boss, meater
    var id: String { rawValue }
    var label: String { switch self { case .combustion: "Combustion"; case .home_assistant: "Home Assistant"; case .fireboard: "FireBoard"; case .flame_boss: "Flame Boss"; case .meater: "MEATER" } }
}
enum NativeTelemetryKind: String, Codable, CaseIterable {
    case food = "FOOD", chamber = "CHAMBER", ambient = "AMBIENT", setpoint = "SETPOINT", other = "OTHER"
    var storageType: String { switch self { case .food: "food_probe"; case .chamber, .ambient: "pit_ambient"; case .setpoint: "setpoint"; case .other: "sensor_temperature" } }
    var label: String { switch self { case .food: "Food probe"; case .chamber: "Grill chamber"; case .ambient: "Ambient"; case .setpoint: "Observed target"; case .other: "Other temperature" } }
}
struct NativeTelemetryChannel: Codable, Identifiable, Equatable { var id: String; var name: String; var kind: NativeTelemetryKind }
struct NativeTelemetrySource: Codable, Identifiable, Equatable {
    var provider: NativeTelemetryProvider; var id: String; var label: String; var scope = ""; var channels: [NativeTelemetryChannel]
}
struct NativeTelemetryObservation: Equatable {
    var channel: String; var value: Double?; var unit: String; var observedAt: Double?; var receivedAt: Double
    var available = true; var eventId: String?; var history = false
    var normalized: (value: Double, at: Double, basis: String, key: String)? {
        guard available, let value, value.isFinite, receivedAt.isFinite, receivedAt > 0, !history || observedAt != nil else { return nil }
        let rawAt = observedAt ?? receivedAt
        guard rawAt.isFinite, rawAt > 0, rawAt < Double(Int64.max) - 1024 else { return nil }
        let at = Double(Int64(rawAt))
        guard at.isFinite, at > 0, at < Double(Int64.max) - 1024, at <= receivedAt + 300_000 else { return nil }
        let f: Double
        switch unit.lowercased() { case "°f", "f", "fahrenheit": f = value; case "°c", "c", "celsius": f = value * 9 / 5 + 32; default: return nil }
        guard f.isFinite, (-100...1000).contains(f) else { return nil }
        let key = eventId?.isEmpty == false ? eventId! : "\(Int64(at)):\(Int64(bitPattern: f.bitPattern))"
        return (f, at, observedAt == nil ? "receipt" : "source_observed", key)
    }
}
@MainActor protocol NativeReadOnlyTelemetry: AnyObject {
    var minimumInterval: Double { get }
    var supportsHistory: Bool { get }
    func revoke() async throws
    func discover() async throws -> [NativeTelemetrySource]
    func snapshot(_ device: String) async throws -> [NativeTelemetryObservation]
    func history(_ device: String, from: Double, until: Double) async throws -> [NativeTelemetryObservation]
    func disconnect()
}
extension NativeReadOnlyTelemetry {
    var minimumInterval: Double { 30_000 }
    var supportsHistory: Bool { false }
    func revoke() async throws {}
    func history(_ device: String, from: Double, until: Double) async throws -> [NativeTelemetryObservation] { [] }
}
struct NativeTelemetryProfile: Codable, Identifiable, Equatable {
    var id = UUID().uuidString.lowercased(); var provider: NativeTelemetryProvider; var endpoint = ""; var deviceHint = ""
    var sources: [NativeTelemetrySource] = []; var selected: [String] = []; var cookId: String?
    var monitoring = false; var onLogOnly = false; var interval = 60_000.0
}
enum NativeTelemetryError: LocalizedError {
    case authorization, configuration, network, rateLimit(Double), unavailable
    var errorDescription: String? { switch self { case .authorization: "Sign in again to reconnect this account."; case .configuration: "Check the connection settings."; case .network: "The provider is unavailable. PitTech will retry."; case .rateLimit: "The provider has asked PitTech to wait before reading again."; case .unavailable: "This connection is not yet validated on iOS." } }
}
enum NativeTelemetryIdentity {
    // Same length-framed SHA-256 identities as Android. No raw account/device identity in archives.
    static func key(_ parts: String...) -> String { key(parts) }
    static func key(_ parts: [String]) -> String {
        var data = Data()
        for part in parts { let bytes = Data(part.utf8); var size = UInt32(bytes.count).bigEndian; withUnsafeBytes(of: &size) { data.append(contentsOf: $0) }; data.append(bytes) }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    static func device(_ cook: String, _ source: NativeTelemetrySource) -> String {
        "telemetry:" + key([cook, source.provider.rawValue, source.id] + (source.scope.isEmpty ? [] : [source.scope]))
    }
    static func probe(_ cook: String, _ source: NativeTelemetrySource, _ channel: String) -> String { device(cook, source) + ":" + key(channel) }
}
struct NativeTelemetryTokenVault {
    private let service = "com.pittech.telemetry.v1"
    func load(_ profile: String) throws -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: profile, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?; let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, let token = String(data: data, encoding: .utf8) else { throw NativeTelemetryError.authorization }
        return token
    }
    func save(_ profile: String, _ token: String) throws {
        guard !token.isEmpty, token.utf8.count <= 16_384 else { throw NativeTelemetryError.configuration }
        let key: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: profile]
        let attrs: [String: Any] = [kSecValueData as String: Data(token.utf8), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(key as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound { guard SecItemAdd(key.merging(attrs) { _, b in b } as CFDictionary, nil) == errSecSuccess else { throw NativeTelemetryError.authorization } }
        else if status != errSecSuccess { throw NativeTelemetryError.authorization }
    }
    func clear(_ profile: String) { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: profile] as CFDictionary) }
}

@MainActor extension CookStore {
    func attachTelemetry(_ source: NativeTelemetrySource, cookId: String, assignments: [String: String?], at: Double = Double(Int64(Date.now.timeIntervalSince1970 * 1000))) throws {
        guard let cook = library.cooks.first(where: { $0.id == cookId }), cook.status == "active" else { throw NativeTelemetryError.configuration }
        let dishes = Set(dishRecords(cookId).map(\.id))
        guard assignments.values.compactMap({ $0 }).allSatisfy(dishes.contains) else { throw NativeTelemetryError.configuration }
        let prior = library; let device = NativeTelemetryIdentity.device(cookId, source)
        if let index = library.devices.firstIndex(where: { $0.objectValue?["id"]?.stringValue == device }), var o = library.devices[index].objectValue { o["role"] = .string("telemetry"); library.devices[index] = .object(o) }
        else { library.devices.append(.object(["id": .string(device), "cookId": .string(cookId), "brand": .string(source.provider.label), "deviceName": .string(source.label), "role": .string("telemetry"), "createdAtUtcMillis": .number(at)])) }
        telemetryLifecycle(source, cookId: cookId, state: "active", at: at)
        for channel in source.channels {
            let probe = NativeTelemetryIdentity.probe(cookId, source, channel.id)
            let index = library.probes.firstIndex { $0.objectValue?["id"]?.stringValue == probe }
            var object = index.flatMap { library.probes[$0].objectValue } ?? ["id": .string(probe), "cookId": .string(cookId), "deviceId": .string(device), "createdAtUtcMillis": .number(at)]
            object["name"] = .string(source.channels.count == 1 ? source.label : source.label + " · " + channel.name); object["source"] = .string("telemetry:" + source.provider.rawValue); object["measurementType"] = .string(channel.kind.storageType)
            let food = channel.kind == .food ? assignments[channel.id] ?? nil : nil
            object["assignedDishId"] = food.map(JSONValue.string) ?? .null
            library.probeAssignments = library.probeAssignments.map { row in
                guard var o = row.objectValue, o["probeId"]?.stringValue == probe, o["endedAtUtcMillis"]?.numberValue == nil else { return row }; o["endedAtUtcMillis"] = .number(at); return .object(o)
            }
            if channel.kind == .food { library.probeAssignments.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId), "probeId": .string(probe), "dishId": food.map(JSONValue.string) ?? .null, "startedAtUtcMillis": .number(at)])) }
            if let index { library.probes[index] = .object(object) } else { library.probes.append(.object(object)) }
            library.events.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId), "eventType": .string("telemetry_mapping"), "title": .string(channel.name + " assignment"), "details": .string(channel.kind.rawValue), "occurredAtUtcMillis": .number(at), "recordedAtUtcMillis": .number(at), "createdAtUtcMillis": .number(at), "updatedAtUtcMillis": .number(at), "timeZoneId": .string(TimeZone.current.identifier), "source": .string("telemetry:" + source.provider.rawValue), "sourceDeviceId": .string(probe)]))
        }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
    @discardableResult func ingestTelemetry(_ source: NativeTelemetrySource, cookId: String, observations: [NativeTelemetryObservation], samplingInterval: Double = 60_000) throws -> Int {
        guard let cook = library.cooks.first(where: { $0.id == cookId }), cook.status == "active" else { return 0 }
        let device = NativeTelemetryIdentity.device(cookId, source)
        guard library.devices.contains(where: { $0.objectValue?["id"]?.stringValue == device && $0.objectValue?["role"]?.stringValue == "telemetry" }) else { return 0 }
        let prior = library; var inserted = 0; var existing = Set(library.readings.compactMap { $0.objectValue?["id"]?.stringValue })
        for raw in observations {
            guard let channel = source.channels.first(where: { $0.id == raw.channel }), let n = raw.normalized, n.at >= Double(cook.startedAtUtcMillis) else { continue }
            let status = library.events.compactMap(\.objectValue).filter { $0["cookId"]?.stringValue == cookId && ($0["occurredAtUtcMillis"]?.numberValue ?? .infinity) <= n.at }
            let local = status.filter { $0["eventType"]?.stringValue == "telemetry_lifecycle" && $0["sourceDeviceId"]?.stringValue == device }.max { ($0["occurredAtUtcMillis"]?.numberValue ?? 0) < ($1["occurredAtUtcMillis"]?.numberValue ?? 0) }
            let meal = status.filter { ["cook_paused", "cook_resumed"].contains($0["eventType"]?.stringValue ?? "") }.max { ($0["occurredAtUtcMillis"]?.numberValue ?? 0) < ($1["occurredAtUtcMillis"]?.numberValue ?? 0) }
            guard local == nil || local?["details"]?.stringValue == "active", meal?["eventType"]?.stringValue != "cook_paused" else { continue }
            let probe = NativeTelemetryIdentity.probe(cookId, source, channel.id)
            let roles = library.events.compactMap(\.objectValue).filter { $0["eventType"]?.stringValue == "telemetry_mapping" && $0["sourceDeviceId"]?.stringValue == probe && ($0["occurredAtUtcMillis"]?.numberValue ?? .infinity) <= n.at }
            let kind = roles.max { ($0["occurredAtUtcMillis"]?.numberValue ?? 0) < ($1["occurredAtUtcMillis"]?.numberValue ?? 0) }?["details"]?.stringValue.flatMap(NativeTelemetryKind.init(rawValue:)) ?? channel.kind
            let assignment = library.probeAssignments.compactMap(\.objectValue).filter { $0["probeId"]?.stringValue == probe && ($0["startedAtUtcMillis"]?.numberValue ?? .infinity) <= n.at && ($0["endedAtUtcMillis"]?.numberValue ?? .infinity) > n.at }.max { ($0["startedAtUtcMillis"]?.numberValue ?? 0) < ($1["startedAtUtcMillis"]?.numberValue ?? 0) }
            let parts = [cookId, source.provider.rawValue, source.id] + (source.scope.isEmpty ? [] : [source.scope]) + [channel.id, n.key]
            let id = "telemetry:" + NativeTelemetryIdentity.key(parts)
            guard existing.insert(id).inserted else { continue }
            library.readings.append(.object(["id": .string(id), "cookId": .string(cookId), "dishId": kind == .food ? assignment?["dishId"] ?? .null : .null, "probeId": .string(probe), "probeName": .string(source.channels.count == 1 ? source.label : source.label + " · " + channel.name), "measurementType": .string(kind.storageType), "value": .number(n.value), "unit": .string("°F"), "measuredAtUtcMillis": .number(n.at), "timeZoneId": .string(TimeZone.current.identifier), "source": .string("telemetry:" + source.provider.rawValue), "sourceDeviceId": .string(device), "qualityStatus": .string("valid"), "recordedAtUtcMillis": .number(raw.receivedAt), "timestampBasis": .string(n.basis), "samplingIntervalMillis": .number(raw.history ? 0 : samplingInterval)])); inserted += 1
        }
        if inserted > 0 { save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) } }
        return inserted
    }
    func detachTelemetry(_ source: NativeTelemetrySource, cookId: String) throws {
        let prior = library, device = NativeTelemetryIdentity.device(cookId, source), at = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        library.devices = library.devices.map { row in guard var o = row.objectValue, o["id"]?.stringValue == device else { return row }; o["role"] = .string("telemetry_detached"); return .object(o) }
        telemetryLifecycle(source, cookId: cookId, state: "detached", at: at)
        let probes = Set(library.probes.compactMap(\.objectValue).filter { $0["deviceId"]?.stringValue == device }.compactMap { $0["id"]?.stringValue })
        library.probeAssignments = library.probeAssignments.map { row in guard var o = row.objectValue, let probe = o["probeId"]?.stringValue, probes.contains(probe), o["endedAtUtcMillis"]?.numberValue == nil else { return row }; o["endedAtUtcMillis"] = .number(at); return .object(o) }
        library.probes = library.probes.map { row in guard var o = row.objectValue, let probe = o["id"]?.stringValue, probes.contains(probe) else { return row }; o["assignedDishId"] = .null; return .object(o) }
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
    func setTelemetryMonitoring(_ source: NativeTelemetrySource, cookId: String, enabled: Bool, at: Double = Double(Int64(Date.now.timeIntervalSince1970 * 1000))) throws {
        let device = NativeTelemetryIdentity.device(cookId, source), prior = library
        library.devices = library.devices.map { row in guard var o = row.objectValue, o["id"]?.stringValue == device, o["role"]?.stringValue != "telemetry_detached" else { return row }; o["role"] = .string(enabled ? "telemetry" : "telemetry_paused"); return .object(o) }
        telemetryLifecycle(source, cookId: cookId, state: enabled ? "active" : "paused", at: at)
        save(); if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }
    private func telemetryLifecycle(_ source: NativeTelemetrySource, cookId: String, state: String, at: Double) {
        library.events.append(.object(["id": .string(UUID().uuidString.lowercased()), "cookId": .string(cookId), "eventType": .string("telemetry_lifecycle"), "title": .string("Temperature monitoring"), "details": .string(state), "occurredAtUtcMillis": .number(at), "recordedAtUtcMillis": .number(at), "timeZoneId": .string(TimeZone.current.identifier), "source": .string("telemetry:" + source.provider.rawValue), "sourceDeviceId": .string(NativeTelemetryIdentity.device(cookId, source)), "createdAtUtcMillis": .number(at), "updatedAtUtcMillis": .number(at)]))
    }
    func pendingTelemetryRecovery(_ source: NativeTelemetrySource, cook: String) -> ClosedRange<Double>? {
        let device = NativeTelemetryIdentity.device(cook, source)
        guard let row = library.events.compactMap(\.objectValue).first(where: { $0["cookId"]?.stringValue == cook && $0["eventType"]?.stringValue == "telemetry_recovery" && $0["sourceDeviceId"]?.stringValue == device }),
              let details = row["details"]?.stringValue, let bytes = details.data(using: .utf8), let json = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any], json["complete"] as? Bool != true,
              let from = NativeTelemetryEndpoint.number(json["from"]), let until = NativeTelemetryEndpoint.number(json["until"]), from > 0, until >= from else { return nil }
        return from...until
    }
    func saveTelemetryRecovery(_ source: NativeTelemetrySource, cook: String, from: Double, until: Double, complete: Bool = false) throws {
        let id = "telemetry-recovery:" + NativeTelemetryIdentity.key(cook, NativeTelemetryIdentity.device(cook, source)), now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        let bytes = try JSONSerialization.data(withJSONObject: ["from": from, "until": until, "complete": complete]), prior = library
        var row = library.events.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue ?? ["id": .string(id), "cookId": .string(cook), "eventType": .string("telemetry_recovery"), "title": .string("Temperature history synchronization"), "occurredAtUtcMillis": .number(now), "recordedAtUtcMillis": .number(now), "createdAtUtcMillis": .number(now), "timeZoneId": .string(TimeZone.current.identifier), "source": .string("telemetry:" + source.provider.rawValue), "sourceDeviceId": .string(NativeTelemetryIdentity.device(cook, source))]
        row["details"] = .string(String(decoding: bytes, as: UTF8.self)); row["updatedAtUtcMillis"] = .number(now)
        library.events.removeAll { $0.objectValue?["id"]?.stringValue == id }; library.events.append(.object(row)); save()
        if saveError != nil { library = prior; throw CocoaError(.fileWriteUnknown) }
    }

}
