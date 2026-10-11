import Foundation
import Observation
import Security
import CoreFoundation

struct GrillirGDevice: Identifiable, Hashable {
    let id: String
    let name: String
    let brand: String?
    let model: String?
    let firmware: String?
}

struct GrillirGSnapshot {
    let fetchedAt: Date
    let status: [String: Double]
    let readings: [String: Double]
    let recognizedFields: [String]
    let unknownFieldCount: Int

    var isStale: Bool { Date.now.timeIntervalSince(fetchedAt) > 150 }
    var temperatureUnit: String {
        let code = readings["tempUnit"] ?? status["tempUnit"]
        if code == 0 { return "°F" }
        if code == 1 { return "°C" }
        return "(unit unknown)"
    }
}

@MainActor
@Observable
final class GrillirGMonitor {
    private(set) var devices: [GrillirGDevice] = []
    private(set) var selectedDeviceId: String?
    private(set) var snapshot: GrillirGSnapshot?
    private(set) var isSignedIn = false
    private(set) var isBusy = false
    private(set) var notice: String?

    private let keychain = GrillirGTokenStore()
    private var token: String?

    var selectedDevice: GrillirGDevice? { devices.first { $0.id == selectedDeviceId } }

    func restore() async {
        guard let saved = keychain.read(), !saved.token.isEmpty else { return }
        guard !saved.isExpired else { keychain.delete(); return }
        token = saved.token
        isSignedIn = true
        selectedDeviceId = saved.deviceId
        await loadDevices()
    }

    func requestCode(email: String) async {
        guard Self.validEmail(email) else { notice = "Enter the email used in GrillirG."; return }
        await runBusy {
            _ = try await Self.post("/email/m2s/send/verify/code", body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "emailType": "Login"], allowsEmptyData: true)
            notice = "Check your email for the GrillirG sign-in code."
        }
    }

    func signIn(email: String, code: String) async {
        guard Self.validEmail(email), code.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else {
            notice = "Enter your email and the six-digit code."; return
        }
        await runBusy {
            let data = try await Self.post("/auth/m2s/email/login", body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "verifyCode": code, "emailType": "Login"])
            guard let newToken = data["token"] as? String,
                  !newToken.isEmpty, newToken.count <= 16_384,
                  !newToken.contains(where: \.isWhitespace) else { throw GrillirGError.invalidResponse }
            token = newToken
            isSignedIn = true
            selectedDeviceId = nil
            keychain.write(token: newToken, deviceId: nil)
            try await fetchDevices()
        }
    }

    func loadDevices() async {
        guard token != nil else { return }
        await runBusy { try await fetchDevices() }
    }

    func selectDevice(_ id: String) async {
        guard devices.contains(where: { $0.id == id }) else { return }
        selectedDeviceId = id
        keychain.write(token: token ?? "", deviceId: id)
        await refresh()
    }

    func refresh() async {
        guard let token, let selectedDeviceId else { return }
        await runBusy {
            let body = ["deviceId": selectedDeviceId]
            let status = try await Self.post("/dms/queryDeviceStatus", body: body, token: token)
            let readings = try await Self.post("/dms/queryDeviceRealTimeData", body: body, token: token)
            let statusValues = Self.numericFields(in: status)
            let readingValues = Self.numericFields(in: readings)
            let allFields = Array(Set(status.keys.compactMap { $0 as? String } + readings.keys.compactMap { $0 as? String })).sorted()
            let recognized = allFields.filter { Self.numericFieldNames.contains($0) || $0 == "alarmEvent" }
            snapshot = GrillirGSnapshot(fetchedAt: .now, status: statusValues, readings: readingValues,
                                        recognizedFields: recognized, unknownFieldCount: allFields.count - recognized.count)
            notice = nil
        }
    }

    func signOut() {
        token = nil
        devices = []
        selectedDeviceId = nil
        snapshot = nil
        isSignedIn = false
        keychain.delete()
        notice = "Signed out of GrillirG."
    }

    private func fetchDevices() async throws {
        guard let token else { return }
        let data = try await Self.post("/dms/queryDeviceListByUserId", body: [:], token: token)
        guard let raw = data["list"] as? [[String: Any]], raw.count <= 100 else { throw GrillirGError.invalidResponse }
        var seen = Set<String>()
        devices = raw.enumerated().compactMap { index, item in
            let id: String
            if let value = item["id"] as? String { id = value }
            else if let value = item["id"] as? NSNumber { id = value.stringValue }
            else { return nil }
            guard !id.isEmpty, id.count <= 160, seen.insert(id).inserted else { return nil }
            return GrillirGDevice(id: id,
                                  name: (item["deviceName"] as? String).flatMap { $0.isEmpty ? nil : String($0.prefix(100)) } ?? "Grill \(index + 1)",
                                  brand: Self.safeText(item["grillBrand"]),
                                  model: Self.safeText(item["grillModel"] ?? item["modelName"]),
                                  firmware: Self.safeText(item["firmwareVersion"]))
        }
        if let selectedDeviceId, !devices.contains(where: { $0.id == selectedDeviceId }) { self.selectedDeviceId = nil }
        if self.selectedDeviceId == nil, let first = devices.first { self.selectedDeviceId = first.id }
        keychain.write(token: token, deviceId: self.selectedDeviceId)
        if self.selectedDeviceId != nil { await refresh() }
        else { notice = devices.isEmpty ? "No grills were found on this GrillirG account." : nil }
    }

    private func runBusy(_ operation: () async throws -> Void) async {
        guard !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        do { try await operation() }
        catch let error as GrillirGError {
            switch error {
            case .authentication: signOut(); notice = "GrillirG needs you to sign in again. Request a new email code."
            case .rateLimited: notice = "GrillirG is limiting requests. Wait a moment and try again."
            case .offline: notice = "Could not reach GrillirG. Check the internet connection and try again."
            case .invalidResponse: notice = "GrillirG returned data PitTech could not read."
            case .rejected(let code): notice = "GrillirG rejected the request (code \(code))."
            }
        } catch { notice = "Could not reach GrillirG. Check the internet connection and try again." }
    }

    private static func post(_ path: String, body: [String: Any], token: String? = nil, allowsEmptyData: Bool = false) async throws -> [String: Any] {
        guard let url = URL(string: "https://api.prime-polaris.com/api" + path) else { throw GrillirGError.invalidResponse }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("PitTech-GrillirG-Monitor/1", forHTTPHeaderField: "User-Agent")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) }
        catch { throw GrillirGError.offline }
        guard data.count <= 512 * 1024, let http = response as? HTTPURLResponse else { throw GrillirGError.invalidResponse }
        if http.statusCode == 401 || http.statusCode == 403 { throw GrillirGError.authentication }
        if http.statusCode == 429 { throw GrillirGError.rateLimited }
        guard (200...299).contains(http.statusCode),
              let envelope = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let code = number(envelope["respCode"]), code.rounded() == code else { throw GrillirGError.invalidResponse }
        guard code == 10000 else {
            let authCodes: Set<Int> = [-10001, -10002, -10003, -10007, -10108]
            if authCodes.contains(Int(code)) { throw GrillirGError.authentication }
            throw GrillirGError.rejected(Int(code))
        }
        if allowsEmptyData, envelope["data"] == nil || envelope["data"] is NSNull { return [:] }
        guard let result = envelope["data"] as? [String: Any] else { throw GrillirGError.invalidResponse }
        return result
    }

    private static func validEmail(_ email: String) -> Bool {
        let value = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.count <= 254 && value.range(of: "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", options: .regularExpression) != nil
    }

    private static func safeText(_ value: Any?) -> String? {
        guard let text = value as? String, !text.isEmpty else { return nil }
        return String(text.prefix(100))
    }

    private static func number(_ value: Any?) -> Double? {
        if let number = value as? NSNumber {
            guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
            return number.doubleValue.isFinite ? number.doubleValue : nil
        }
        if let text = value as? String, let number = Double(text), number.isFinite { return number }
        return nil
    }

    private static let numericFieldNames: Set<String> = [
        "onlineStatus", "runningStatus", "deviceSwitch", "furnaceTempMeasured", "furnaceTempSetting",
        "probeP1Measured", "probeP1Setting", "probeP1Status", "probeP2Measured", "probeP2Setting", "probeP2Status",
        "tempUnit", "tempScale", "smokeMode", "smokeLevel", "winter", "refTemp", "alarmSwitch",
        "forwardTimingStatus", "forwardTimerValue", "countdownStatus", "countdownSetTimerValue", "countdownTimerValue"
    ]

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: RejectRedirects(), delegateQueue: nil)
    }()

    private static func numericFields(in object: [String: Any]) -> [String: Double] {
        var output: [String: Double] = [:]
        for key in numericFieldNames {
            guard let value = number(object[key]) else { continue }
            let temperature = key.localizedCaseInsensitiveContains("temp") || key.hasPrefix("probe") && !key.hasSuffix("Status")
            guard temperature ? (-100...1000).contains(value) : (value.rounded() == value && (-1_000_000...1_000_000).contains(value)) else { continue }
            output[key] = value
        }
        return output
    }
}

private final class RejectRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

private enum GrillirGError: Error {
    case authentication
    case rateLimited
    case offline
    case invalidResponse
    case rejected(Int)
}

private struct SavedGrillirGSession {
    let token: String
    let deviceId: String?
    let expiry: Date?
    var isExpired: Bool { expiry.map { Date.now >= $0.addingTimeInterval(-30) } ?? false }
}

private struct GrillirGTokenStore {
    private let service = "com.pittech.grillirg-session"
    private let account = "session"

    func read() -> SavedGrillirGSession? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service, kSecAttrAccount as String: account,
                                    kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = object["token"] as? String else { return nil }
        let deviceId = object["deviceId"] as? String
        let expiry = (object["expiry"] as? Double).map(Date.init(timeIntervalSince1970:))
        return SavedGrillirGSession(token: token, deviceId: deviceId, expiry: expiry)
    }

    func write(token: String, deviceId: String?) {
        guard !token.isEmpty else { return }
        let payload: [String: Any] = ["token": token, "deviceId": deviceId.map { $0 as Any } ?? NSNull(), "expiry": Self.tokenExpiry(token).map { $0.timeIntervalSince1970 as Any } ?? NSNull()]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service, kSecAttrAccount as String: account]
        let update = SecItemUpdate(base as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    func delete() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                       kSecAttrService as String: service, kSecAttrAccount as String: account] as CFDictionary)
    }

    private static func tokenExpiry(_ token: String) -> Date? {
        let pieces = token.split(separator: ".")
        guard pieces.count == 3 else { return nil }
        var base64 = String(pieces[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: base64),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let seconds = payload["exp"] as? Double, seconds > 0, seconds < Double.greatestFiniteMagnitude / 1000 else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }
}
