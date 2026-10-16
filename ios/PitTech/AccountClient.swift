import Foundation

protocol NativeAccountAPI { func call(_ path: String, body: [String: String]?, access: String?) async throws -> Data }
struct NativeAccountClient: NativeAccountAPI {
    var configuration: URLSessionConfiguration?
    func call(_ path: String, body: [String: String]? = nil, access: String? = nil) async throws -> Data {
        guard ["/v1/auth/challenges", "/v1/auth/verify", "/v1/auth/refresh", "/v1/auth/logout", "/v1/me", "/v1/me/deletion", "/v1/deletion/recover", "/v1/deletion/status"].contains(path) else { throw NativeAccountError.invalid }
        var request = URLRequest(url: URL(string: "https://api.pittech.app" + path)!)
        request.httpMethod = body == nil ? "GET" : "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        guard (request.httpBody?.count ?? 0) <= 8192 else { throw NativeAccountError.invalid }
        if let access { guard NativeAccountProtocol.credential(access) else { throw NativeAccountError.invalid }; request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization") }
        do {
            let result = try await NativeRelayTransport(configuration: configuration, responseError: { NativeAccountError.status($0) }, maximumResponseBytes: 8192).run(request)
            guard result.data.count <= 8192 else { throw NativeAccountError.invalid }; return result.data
        } catch let error as NativeAccountError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch { throw NativeAccountError.network }
    }
}
