import Foundation
import SwiftUI
import WebKit

struct NativeHASession: Codable {
    var accessToken: String; var refreshToken: String; var expiresAt: Double
    static let client = "https://pittech-privacy-production.up.railway.app"
    static let callback = client + "/ha/auth_callback"
    var encoded: String { (try? JSONEncoder().encode(self).base64EncodedString()) ?? "" }
    static func decode(_ raw: String) -> Self? { Data(base64Encoded: raw).flatMap { try? JSONDecoder().decode(Self.self, from: $0) } }
    static func exchange(server: String, code: String) async throws -> Self {
        let url = try NativeTelemetryEndpoint.homeAssistant(server).appendingPathComponent("auth/token")
        let bytes = try await NativeTelemetryHTTP().form(url, fields: ["grant_type": "authorization_code", "code": code, "client_id": client])
        guard let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], let access = object["access_token"] as? String,
              let refresh = object["refresh_token"] as? String, !access.isEmpty, !refresh.isEmpty else { throw NativeTelemetryError.authorization }
        return .init(accessToken: access, refreshToken: refresh, expiresAt: Double(Int64(Date.now.timeIntervalSince1970 * 1000)) + (NativeTelemetryEndpoint.number(object["expires_in"]) ?? 1800) * 1000)
    }
}
struct NativeHAAuthRequest {
    let origin: URL; let state: String; let url: URL
    init(server: String, state: String = UUID().uuidString + UUID().uuidString) throws {
        origin = try NativeTelemetryEndpoint.homeAssistant(server); self.state = state
        var parts = URLComponents(url: origin.appendingPathComponent("auth/authorize"), resolvingAgainstBaseURL: false)!
        parts.queryItems = [.init(name: "client_id", value: NativeHASession.client), .init(name: "redirect_uri", value: NativeHASession.callback), .init(name: "state", value: state)]
        url = parts.url!
    }
    func code(_ url: URL) throws -> String? {
        guard url.scheme == "https", url.host == URL(string: NativeHASession.callback)!.host, url.port == nil || url.port == 443,
              url.path == "/ha/auth_callback", url.user == nil, url.password == nil, url.fragment == nil else { return nil }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        guard items.filter({ $0.name == "state" }).count == 1, items.first(where: { $0.name == "state" })?.value == state,
              items.filter({ $0.name == "code" }).count == 1, let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else { throw NativeTelemetryError.authorization }
        return code
    }
    func allows(_ url: URL) -> Bool { url.scheme == "https" && url.host?.lowercased() == origin.host?.lowercased() && (url.port ?? 443) == (origin.port ?? 443) && url.user == nil && url.password == nil }
}
struct NativeHAOAuthView: UIViewRepresentable {
    let request: NativeHAAuthRequest
    let completion: (Result<String, Error>) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(request: request, completion: completion) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration(); configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration); view.navigationDelegate = context.coordinator
        view.load(URLRequest(url: request.url)); return view
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
    final class Coordinator: NSObject, WKNavigationDelegate {
        let request: NativeHAAuthRequest; let completion: (Result<String, Error>) -> Void; private var done = false
        init(request: NativeHAAuthRequest, completion: @escaping (Result<String, Error>) -> Void) { self.request = request; self.completion = completion }
        private func finish(_ result: Result<String, Error>) { guard !done else { return }; done = true; completion(result) }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
            do {
                if let code = try request.code(url) { decisionHandler(.cancel); finish(.success(code)); return }
                guard request.allows(url) else { decisionHandler(.cancel); finish(.failure(NativeTelemetryError.configuration)); return }
                decisionHandler(.allow)
            } catch { decisionHandler(.cancel); finish(.failure(error)) }
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { finish(.failure(error)) }
    }
}

@MainActor final class NativeHomeAssistantConnector: NativeReadOnlyTelemetry {
    private let profile: NativeTelemetryProfile
    private let base: URL
    private let http = NativeTelemetryHTTP()
    private let vault = NativeTelemetryTokenVault()
    private var credential: String
    private var session: NativeHASession?
    private var socket: URLSessionWebSocketTask?
    private var receiving: Task<Void, Never>?
    private var live: [String: (Double, [String: Any])] = [:]
    private let redirectGuard = NativeTelemetryRedirectGuard()
    private lazy var wsSession = URLSession(configuration: .ephemeral, delegate: redirectGuard, delegateQueue: nil)
    var minimumInterval: Double { 15_000 }
    var supportsHistory: Bool { true }
    init(profile: NativeTelemetryProfile, credential: String) throws {
        self.profile = profile; base = try NativeTelemetryEndpoint.homeAssistant(profile.endpoint)
        self.credential = credential; session = NativeHASession.decode(credential)
        guard !credential.isEmpty else { throw NativeTelemetryError.authorization }
    }
    private func token(force: Bool = false) async throws -> String {
        guard var current = session else { return credential }
        if force || current.expiresAt <= Double(Int64(Date.now.timeIntervalSince1970 * 1000)) + 60_000 {
            let bytes = try await http.form(base.appendingPathComponent("auth/token"), fields: ["grant_type": "refresh_token", "refresh_token": current.refreshToken, "client_id": NativeHASession.client])
            guard let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], let access = object["access_token"] as? String, !access.isEmpty else { throw NativeTelemetryError.authorization }
            current.accessToken = access; current.expiresAt = Double(Int64(Date.now.timeIntervalSince1970 * 1000)) + (NativeTelemetryEndpoint.number(object["expires_in"]) ?? 1800) * 1000
            session = current; credential = current.encoded; try vault.save(profile.id, credential)
            receiving?.cancel(); socket?.cancel(with: .goingAway, reason: nil); socket = nil; live = [:]
        }
        return current.accessToken
    }
    private func read(_ url: URL) async throws -> Data {
        do { return try await http.read(url, token: token(), prefix: "Bearer") }
        catch NativeTelemetryError.authorization where session != nil { return try await http.read(url, token: token(force: true), prefix: "Bearer") }
    }
    func discover() async throws -> [NativeTelemetrySource] {
        let bytes = try await read(base.appendingPathComponent("api/states"))
        let items = try JSONSerialization.jsonObject(with: bytes) as? [[String: Any]] ?? []
        return NativeTelemetryCloudConnector.sources(provider: .home_assistant, devices: items, scope: base.absoluteString)
    }
    private func startSocket() async throws {
        guard socket == nil else { return }
        let access = try await token()
        var components = URLComponents(url: base.appendingPathComponent("api/websocket"), resolvingAgainstBaseURL: false)!; components.scheme = "wss"
        let ws = wsSession.webSocketTask(with: components.url!); socket = ws; ws.resume()
        receiving = Task { [weak self] in
            guard let self else { return }
            do {
                while !Task.isCancelled {
                    let message = try await ws.receive(); let bytes: Data
                    switch message { case .data(let data): bytes = data; case .string(let text): bytes = Data(text.utf8); @unknown default: continue }
                    guard bytes.count <= 1_048_576, let json = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { throw NativeTelemetryError.configuration }
                    switch json["type"] as? String {
                    case "auth_required": try await self.send(ws, ["type": "auth", "access_token": access])
                    case "auth_ok": try await self.send(ws, ["id": 1, "type": "subscribe_events", "event_type": "state_changed"])
                    case "auth_invalid": self.session?.expiresAt = 0; throw NativeTelemetryError.authorization
                    case "event":
                        if let event = json["event"] as? [String: Any], let data = event["data"] as? [String: Any], let entity = data["entity_id"] as? String, let state = data["new_state"] as? [String: Any], state["entity_id"] as? String == entity { self.live[entity] = (Double(Int64(Date.now.timeIntervalSince1970 * 1000)), state) }
                    case "result": if json["success"] as? Bool == false { throw NativeTelemetryError.configuration }
                    default: break
                    }
                }
            } catch { ws.cancel(with: .goingAway, reason: nil); if self.socket === ws { self.socket = nil; self.live = [:] } }
        }
    }
    private func send(_ ws: URLSessionWebSocketTask, _ object: [String: Any]) async throws { let bytes = try JSONSerialization.data(withJSONObject: object); try await ws.send(.string(String(decoding: bytes, as: UTF8.self))) }
    func snapshot(_ device: String) async throws -> [NativeTelemetryObservation] {
        guard device.range(of: "^sensor\\.[a-z0-9_]+$", options: .regularExpression) != nil else { throw NativeTelemetryError.configuration }
        try await startSocket()
        let now = Double(Int64(Date.now.timeIntervalSince1970 * 1000))
        if let state = live[device], now - state.0 < minimumInterval { return NativeTelemetryCloudConnector.observations(provider: .home_assistant, item: state.1, receipt: now) }
        let bytes = try await read(base.appendingPathComponent("api/states/" + device))
        guard let item = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], item["entity_id"] as? String == device else { return [] }
        return NativeTelemetryCloudConnector.observations(provider: .home_assistant, item: item, receipt: now)
    }
    func history(_ device: String, from: Double, until: Double) async throws -> [NativeTelemetryObservation] {
        guard from > 0, until >= from, until - from <= 21_600_000, device.range(of: "^sensor\\.[a-z0-9_]+$", options: .regularExpression) != nil else { return [] }
        let formatter = ISO8601DateFormatter()
        var parts = URLComponents(url: base.appendingPathComponent("api/history/period/" + formatter.string(from: Date(timeIntervalSince1970: from / 1000))), resolvingAgainstBaseURL: false)!
        parts.queryItems = [.init(name: "filter_entity_id", value: device), .init(name: "end_time", value: formatter.string(from: Date(timeIntervalSince1970: until / 1000)))]
        let bytes = try await read(parts.url!)
        let batches = try JSONSerialization.jsonObject(with: bytes) as? [[[String: Any]]] ?? []
        return batches.flatMap { $0 }.filter { $0["entity_id"] as? String == device }.flatMap { NativeTelemetryCloudConnector.observations(provider: .home_assistant, item: $0, receipt: Double(Int64(Date.now.timeIntervalSince1970 * 1000)), historical: true) }.filter { $0.observedAt.map { from <= $0 && $0 <= until } == true }.prefix(20_000).map { $0 }
    }
    func revoke() async throws { if let session { _ = try await http.form(base.appendingPathComponent("auth/revoke"), fields: ["token": session.refreshToken]) } }
    func disconnect() { receiving?.cancel(); receiving = nil; socket?.cancel(with: .goingAway, reason: nil); socket = nil; live = [:]; credential = ""; session = nil; http.disconnect(); wsSession.invalidateAndCancel() }
}
