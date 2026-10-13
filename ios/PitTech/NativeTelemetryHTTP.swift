import Foundation

final class NativeTelemetryRedirectGuard: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
enum NativeTelemetryEndpoint {
    static func homeAssistant(_ raw: String) throws -> URL {
        guard let parts = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)), parts.scheme == "https", parts.host?.isEmpty == false,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil, parts.path.isEmpty || parts.path == "/", parts.port == nil || (1...65535).contains(parts.port!), let url = parts.url else { throw NativeTelemetryError.configuration }
        return URL(string: url.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")))!
    }
    static func millis(_ value: String?) -> Double? {
        guard let value else { return nil }
        let fractional = ISO8601DateFormatter(); fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return (fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)).map { $0.timeIntervalSince1970 * 1000 }
    }
}
@MainActor final class NativeTelemetryHTTP {
    private let guardDelegate = NativeTelemetryRedirectGuard()
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 20; config.httpCookieStorage = nil; config.urlCache = nil
        return URLSession(configuration: config, delegate: guardDelegate, delegateQueue: nil)
    }()
    func read(_ url: URL, token: String, prefix: String) async throws -> Data {
        guard url.scheme == "https", !token.isEmpty, token.rangeOfCharacter(from: .whitespacesAndNewlines) == nil else { throw NativeTelemetryError.configuration }
        var request = URLRequest(url: url); request.setValue(prefix + " " + token, forHTTPHeaderField: "Authorization"); request.setValue("PitTech/0.1 (read-only temperature journal)", forHTTPHeaderField: "User-Agent")
        return try await send(request)
    }
    func authenticate(_ provider: NativeTelemetryProvider, user: String, password: String) async throws -> String {
        let url: URL, payload: [String: String]
        switch provider {
        case .meater: url = URL(string: "https://public-api.cloud.meater.com/v1/login")!; payload = ["email": user, "password": password]
        case .fireboard: url = URL(string: "https://fireboard.io/api/rest-auth/login/")!; payload = ["username": user, "password": password]
        default: throw NativeTelemetryError.configuration
        }
        var request = URLRequest(url: url); request.httpMethod = "POST"; request.httpBody = try JSONEncoder().encode(payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.setValue("PitTech/0.1 (read-only)", forHTTPHeaderField: "User-Agent")
        let data = try await send(request)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let token = provider == .meater ? (json?["data"] as? [String: Any])?["token"] as? String : json?["key"] as? String
        guard let token, !token.isEmpty else { throw NativeTelemetryError.authorization }; return token
    }
    private func send(_ request: URLRequest) async throws -> Data {
        let (data, raw) = try await session.data(for: request)
        guard let response = raw as? HTTPURLResponse, data.count <= 8_388_608 else { throw NativeTelemetryError.configuration }
        switch response.statusCode {
        case 200...299: return data
        case 401, 403: throw NativeTelemetryError.authorization
        case 429:
            let now = Date.now.timeIntervalSince1970 * 1000
            let pause = Double(response.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 300
            throw NativeTelemetryError.rateLimit(now + min(86_400, max(1, pause)) * 1000)
        case 300...499: throw NativeTelemetryError.configuration
        default: throw NativeTelemetryError.network
        }
    }
    func disconnect() { session.invalidateAndCancel() }
}
@MainActor final class NativeTelemetryCloudCache {
    static let shared = NativeTelemetryCloudCache()
    private var cache: [String: (Double, Data)] = [:]
    private var next: [String: Double] = [:]
    private var running: [String: Task<Data, Error>] = [:]
    func read(key: String, interval: Double, now: Double = Date.now.timeIntervalSince1970 * 1000, load: @escaping @MainActor () async throws -> Data) async throws -> Data {
        if let result = cache[key], now - result.0 >= 0 && now - result.0 < interval { return result.1 }
        if let task = running[key] { return try await task.value }
        if let due = next[key], now < due { throw NativeTelemetryError.rateLimit(due) }
        next[key] = now + interval // Charge unsuccessful requests as well.
        let task = Task { try await load() }; running[key] = task
        defer { running[key] = nil }
        do { let value = try await task.value; cache[key] = (now, value); return value }
        catch { if case NativeTelemetryError.rateLimit(let due) = error { next[key] = max(next[key] ?? 0, due) }; throw error }
    }
    func clearSamples(_ key: String) { cache[key] = nil }
}

@MainActor final class NativeTelemetryCloudConnector: NativeReadOnlyTelemetry {
    let profile: NativeTelemetryProfile
    private var token: String
    private let http = NativeTelemetryHTTP()
    private let account: String
    init(profile: NativeTelemetryProfile, token: String) { self.profile = profile; self.token = token; self.account = NativeTelemetryIdentity.key(profile.provider.rawValue, token) }
    var minimumInterval: Double { profile.provider == .home_assistant ? 15_000 : 30_000 }
    private func data() async throws -> [[String: Any]] {
        let provider = profile.provider
        let url: URL, prefix: String
        switch provider {
        case .home_assistant: url = try NativeTelemetryEndpoint.homeAssistant(profile.endpoint).appendingPathComponent("api/states"); prefix = "Bearer"
        case .meater: url = URL(string: "https://public-api.cloud.meater.com/v1/devices")!; prefix = "Bearer"
        case .fireboard: url = URL(string: "https://fireboard.io/api/v1/devices.json")!; prefix = "Token"
        default: throw NativeTelemetryError.unavailable
        }
        let response = try await NativeTelemetryCloudCache.shared.read(key: account, interval: minimumInterval) { [self] in try await http.read(url, token: token, prefix: prefix) }
        let decoded = try JSONSerialization.jsonObject(with: response)
        if provider == .meater { return ((decoded as? [String: Any])?["data"] as? [String: Any])?["devices"] as? [[String: Any]] ?? [] }
        return decoded as? [[String: Any]] ?? []
    }
    func discover() async throws -> [NativeTelemetrySource] {
        let devices = try await data()
        return Self.sources(provider: profile.provider, devices: devices, scope: profile.endpoint)
    }
    static func sources(provider: NativeTelemetryProvider, devices: [[String: Any]], scope: String = "") -> [NativeTelemetrySource] {
        devices.compactMap { item in
            switch provider {
            case .meater:
                guard let id = item["id"] as? String, !id.isEmpty else { return nil }
                return .init(provider: provider, id: id, label: "MEATER " + id.suffix(4), channels: [.init(id: "internal", name: "Internal", kind: .food), .init(id: "ambient", name: "Ambient", kind: .ambient), .init(id: "target", name: "Observed target", kind: .setpoint)])
            case .fireboard:
                guard let id = item["uuid"] as? String else { return nil }
                let channels = (item["channels"] as? [[String: Any]] ?? []).compactMap { ch -> NativeTelemetryChannel? in guard let port = ch["channel"] as? Int, port > 0 else { return nil }; return .init(id: "channel\(port)", name: ch["channel_label"] as? String ?? "Channel \(port)", kind: .food) }
                guard !channels.isEmpty else { return nil }
                return .init(provider: provider, id: id, label: item["title"] as? String ?? "FireBoard", channels: channels)
            case .home_assistant:
                guard let id = item["entity_id"] as? String, id.range(of: "^sensor\\.[a-z0-9_]+$", options: .regularExpression) != nil, let attrs = item["attributes"] as? [String: Any], ["°F", "°C", "F", "C"].contains(attrs["unit_of_measurement"] as? String ?? ""), attrs["device_class"] == nil || attrs["device_class"] as? String == "temperature" else { return nil }
                return .init(provider: provider, id: id, label: attrs["friendly_name"] as? String ?? id, scope: scope, channels: [.init(id: "temperature", name: "Temperature", kind: .food)])
            default: return nil
            }
        }
    }
    func snapshot(_ device: String) async throws -> [NativeTelemetryObservation] {
        let rows = try await data()
        let item = rows.first { ($0[profile.provider == .home_assistant ? "entity_id" : profile.provider == .fireboard ? "uuid" : "id"] as? String) == device }
        return item.map { Self.observations(provider: profile.provider, item: $0, receipt: Date.now.timeIntervalSince1970 * 1000) } ?? []
    }
    static func observations(provider: NativeTelemetryProvider, item: [String: Any], receipt: Double, historical: Bool = false) -> [NativeTelemetryObservation] {
        switch provider {
        case .meater:
            let at = (item["updated_at"] as? Double).map { $0 * 1000 }; let temps = item["temperature"] as? [String: Any] ?? [:]
            let target = ((item["cook"] as? [String: Any])?["temperature"] as? [String: Any])?["target"] as? Double
            return [("internal", temps["internal"] as? Double), ("ambient", temps["ambient"] as? Double), ("target", target)].map { channel, value in .init(channel: channel, value: value, unit: "°C", observedAt: at, receivedAt: receipt, available: at != nil && receipt - at! <= 180_000, eventId: at.map { String(Int64($0)) }) }
        case .fireboard:
            return (item["latest_temps"] as? [[String: Any]] ?? []).compactMap { temp in
                guard let port = temp["channel"] as? Int, port > 0, let type = temp["degreetype"] as? Int, [1, 2].contains(type) else { return nil }
                let at = NativeTelemetryEndpoint.millis(temp["created"] as? String)
                return .init(channel: "channel\(port)", value: temp["temp"] as? Double, unit: type == 1 ? "°C" : "°F", observedAt: at, receivedAt: receipt, available: at != nil && receipt - at! <= 60_000, eventId: temp["created"] as? String)
            }
        case .home_assistant:
            guard let attrs = item["attributes"] as? [String: Any], let unit = attrs["unit_of_measurement"] as? String else { return [] }
            let value = (item["state"] as? String).flatMap(Double.init), at = NativeTelemetryEndpoint.millis(item["last_updated"] as? String) ?? NativeTelemetryEndpoint.millis(item["last_changed"] as? String)
            return [.init(channel: "temperature", value: value, unit: unit, observedAt: at, receivedAt: receipt, available: value?.isFinite == true, eventId: item["last_updated"] as? String, history: historical)]
        default: return []
        }
    }
    func history(_ device: String, from: Double, until: Double) async throws -> [NativeTelemetryObservation] {
        guard profile.provider == .home_assistant, from > 0, until >= from, until - from <= 21_600_000, device.range(of: "^sensor\\.[a-z0-9_]+$", options: .regularExpression) != nil else { return [] }
        let formatter = ISO8601DateFormatter()
        let base = try NativeTelemetryEndpoint.homeAssistant(profile.endpoint)
        var parts = URLComponents(url: base.appendingPathComponent("api/history/period/" + formatter.string(from: Date(timeIntervalSince1970: from / 1000))), resolvingAgainstBaseURL: false)!
        parts.queryItems = [.init(name: "filter_entity_id", value: device), .init(name: "end_time", value: formatter.string(from: Date(timeIntervalSince1970: until / 1000)))]
        let response = try await http.read(parts.url!, token: token, prefix: "Bearer")
        let batches = try JSONSerialization.jsonObject(with: response) as? [[[String: Any]]] ?? []
        return batches.flatMap { $0 }.filter { $0["entity_id"] as? String == device }.flatMap { Self.observations(provider: .home_assistant, item: $0, receipt: Date.now.timeIntervalSince1970 * 1000, historical: true) }.filter { $0.observedAt.map { from <= $0 && $0 <= until } == true }.prefix(20_000).map { $0 }
    }
    func disconnect() { token = ""; NativeTelemetryCloudCache.shared.clearSamples(account); http.disconnect() }
}
