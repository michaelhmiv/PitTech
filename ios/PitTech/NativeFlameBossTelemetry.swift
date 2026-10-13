import Foundation
import Network
import Security

struct NativeFlameBossBroker {
    let host: String; let port: UInt16
    init(_ raw: String) throws {
        guard let u = URLComponents(string: raw), u.scheme == "mqtts", let host = u.host?.lowercased(),
              host == "myflameboss.com" || host.hasSuffix(".myflameboss.com"), let port = u.port, (1...65535).contains(port),
              u.user == nil, u.password == nil, u.query == nil, u.fragment == nil, u.path.isEmpty else { throw NativeTelemetryError.configuration }
        self.host = host; self.port = UInt16(port)
    }
}
enum NativeFlameBossCodec {
    private static func string(_ s: String) throws -> Data {
        let bytes = Data(s.utf8); guard (1...65535).contains(bytes.count), !s.contains("\0") else { throw NativeTelemetryError.configuration }
        return Data([UInt8(bytes.count >> 8), UInt8(bytes.count & 255)]) + bytes
    }
    private static func packet(_ header: UInt8, _ payload: Data) -> Data {
        var data = Data([header]), length = payload.count
        repeat { let digit = length % 128; length /= 128; data.append(UInt8(digit | (length > 0 ? 128 : 0))) } while length > 0
        return data + payload
    }
    static func connect(user: String, token: String) throws -> Data {
        try packet(0x10, string("MQTT") + Data([4, 0xC2, 0, 60]) + string("pittech-" + UUID().uuidString) + string(user) + string(token))
    }
    static func subscribe(_ device: String) throws -> Data {
        guard device.range(of: "^[0-9]{1,16}$", options: .regularExpression) != nil else { throw NativeTelemetryError.configuration }
        return try packet(0x82, Data([0, 1]) + string("flameboss/" + device + "/send/open") + Data([1]))
    }
    static func ack(_ id: UInt16) -> Data { packet(0x40, Data([UInt8(id >> 8), UInt8(id & 255)])) }
    static func observations(_ json: [String: Any], receipt: Double) -> [NativeTelemetryObservation] {
        guard json["name"] as? String == "temps", let seconds = NativeTelemetryEndpoint.number(json["sec"]), seconds > 0, seconds < Double(Int64.max) / 1000,
              let temps = json["temps"] as? [Any] else { return [] }
        let at = seconds * 1000, available = receipt - at <= 60_000 && at <= receipt + 300_000
        let values = temps.prefix(8).enumerated().map { index, raw in
            let counts = NativeTelemetryEndpoint.number(raw).flatMap { $0.rounded() == $0 ? $0 : nil } ?? -32767
            return NativeTelemetryObservation(channel: "probe\(index)", value: counts == -32767 || !(-200...9000).contains(counts) ? nil : counts / 10, unit: "°C", observedAt: at, receivedAt: receipt, available: available, eventId: String(Int64(at)))
        }
        if let setpoint = NativeTelemetryEndpoint.number(json["set_temp"]), setpoint.rounded() == setpoint, (-200...9000).contains(setpoint) { return values + [.init(channel: "setpoint", value: setpoint / 10, unit: "°C", observedAt: at, receivedAt: receipt, available: available, eventId: String(Int64(at)))] }
        return values
    }
}

@MainActor final class NativeFlameBossConnector: NativeReadOnlyTelemetry {
    private let broker: NativeFlameBossBroker
    private let device: String
    private var token: String
    private let user: String
    private var connection: NWConnection?
    private var reader: Task<Void, Never>?
    private var keepAlive: Task<Void, Never>?
    private var buffer = Data()
    private var latest: [NativeTelemetryObservation] = []
    var minimumInterval: Double { 15_000 }
    init(profile: NativeTelemetryProfile, credential: String) throws {
        broker = try NativeFlameBossBroker(profile.endpoint)
        guard profile.deviceHint.range(of: "^[0-9]{1,16}$", options: .regularExpression) != nil,
              let data = Data(base64Encoded: credential), let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = object["user_id"] as? Int, let token = object["auth_token"] as? String, !token.isEmpty else { throw NativeTelemetryError.authorization }
        device = profile.deviceHint; self.user = "T-\(id)"; self.token = token
    }
    func discover() async throws -> [NativeTelemetrySource] {
        [.init(provider: .flame_boss, id: device, label: "Flame Boss " + device, channels: [.init(id: "probe0", name: "Pit chamber", kind: .chamber)] + (1...3).map { .init(id: "probe\($0)", name: "Food probe \($0)", kind: .food) } + [.init(id: "setpoint", name: "Observed setpoint", kind: .setpoint)])]
    }
    private func open() async throws {
        latest = []; buffer = Data()
        let tls = NWProtocolTLS.Options(); sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions, broker.host)
        sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, true)
        let client = NWConnection(host: NWEndpoint.Host(broker.host), port: NWEndpoint.Port(rawValue: broker.port)!, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
        connection = client
        let timeout = Task { try? await Task.sleep(for: .seconds(20)); if !Task.isCancelled { client.cancel() } }
        defer { timeout.cancel() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var done = false
            client.stateUpdateHandler = { state in
                switch state {
                case .ready: if !done { done = true; continuation.resume() }
                case .failed, .cancelled: if !done { done = true; continuation.resume(throwing: NativeTelemetryError.network) }
                default: break
                }
            }
            client.start(queue: .main)
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { if !done { client.cancel() } }
        }
        try await send(NativeFlameBossCodec.connect(user: user, token: token))
        let ack = try await packet()
        guard ack.0 == 0x20, ack.1.count == 2, ack.1[1] == 0 else { throw NativeTelemetryError.authorization }
        try await send(NativeFlameBossCodec.subscribe(device))
        keepAlive?.cancel()
        keepAlive = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(25)); try await self?.send(Data([0xC0, 0])) }
                catch { return }
            }
        }
        reader = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                do { let p = try await self.packet(); try await self.consume(p) }
                catch { self.keepAlive?.cancel(); self.connection?.cancel(); self.connection = nil; return }
            }
        }
    }
    private func send(_ data: Data) async throws {
        guard let connection else { throw NativeTelemetryError.network }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in connection.send(content: data, completion: .contentProcessed { error in if let error { c.resume(throwing: error) } else { c.resume() } }) }
    }
    private func bytes(_ count: Int) async throws -> Data {
        while buffer.count < count {
            guard let connection else { throw NativeTelemetryError.network }
            let part: Data = try await withCheckedThrowingContinuation { c in connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, complete, error in
                if let data, !data.isEmpty { c.resume(returning: data) } else { c.resume(throwing: error ?? NativeTelemetryError.network) }
            } }
            buffer.append(part); guard buffer.count <= 131072 else { throw NativeTelemetryError.configuration }
        }
        let data = Data(buffer.prefix(count)); buffer.removeFirst(count); return data
    }
    private func packet() async throws -> (UInt8, Data) {
        let header = try await bytes(1)[0]; var length = 0, scale = 1, digit = 0
        repeat { let b = try await bytes(1)[0]; digit += 1; guard digit <= 4 else { throw NativeTelemetryError.configuration }; length += Int(b & 127) * scale; guard length <= 65536 else { throw NativeTelemetryError.configuration }; scale *= 128; if b & 128 == 0 { break } } while true
        return (header, try await bytes(length))
    }
    private func consume(_ packet: (UInt8, Data)) async throws {
        if packet.0 == 0x90 { guard packet.1.count == 3, packet.1[0] == 0, packet.1[1] == 1, packet.1[2] <= 1 else { throw NativeTelemetryError.authorization }; return }
        guard packet.0 >> 4 == 3 else { return }
        let qos = (packet.0 >> 1) & 3; guard qos <= 1, packet.1.count >= 2 else { throw NativeTelemetryError.configuration }
        let data = packet.1, size = Int(data[0]) * 256 + Int(data[1]); var at = size + 2
        guard size > 0, size <= 512, at + (qos == 1 ? 2 : 0) < data.count, let topic = String(data: data[2..<at], encoding: .utf8) else { throw NativeTelemetryError.configuration }
        if qos == 1 { let id = UInt16(data[at]) * 256 + UInt16(data[at + 1]); at += 2; guard id > 0 else { throw NativeTelemetryError.configuration }; try await send(NativeFlameBossCodec.ack(id)) }
        guard topic == "flameboss/" + device + "/send/open" else { return }
        if let json = try JSONSerialization.jsonObject(with: Data(data[at...])) as? [String: Any] { latest = NativeFlameBossCodec.observations(json, receipt: Date.now.timeIntervalSince1970 * 1000) }
    }
    func snapshot(_ id: String) async throws -> [NativeTelemetryObservation] {
        guard id == device, !token.isEmpty else { return [] }
        if connection == nil { try await open() }
        if latest.isEmpty { for _ in 0..<100 { try await Task.sleep(for: .milliseconds(100)); if !latest.isEmpty { break } } }
        // MQTT keepalive is observational protocol traffic, never an application PUBLISH.
        try await send(Data([0xC0, 0])); let now = Date.now.timeIntervalSince1970 * 1000
        return latest.map { item in var value = item; value.receivedAt = now; value.available = item.available && item.observedAt.map { now - $0 <= 60_000 } == true; return value }
    }
    func disconnect() { keepAlive?.cancel(); keepAlive = nil; reader?.cancel(); reader = nil; connection?.cancel(); connection = nil; latest = []; token = ""; buffer = Data() }
}
