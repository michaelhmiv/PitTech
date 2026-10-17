import Foundation

struct NativeRelayClient {
    private func request(_ path: String, method: String = "GET", owner: String? = nil) -> URLRequest {
        var request = URLRequest(url: URL(string: NativeRelayProtocol.api.absoluteString + path)!)
        request.httpMethod = method; request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let owner { request.setValue("Bearer " + owner, forHTTPHeaderField: "Authorization") }
        return request
    }
    private func receipt(_ response: NativeRelayResponse) throws -> NativeRelayReceipt {
        let value = try JSONDecoder().decode(NativeRelayReceipt.self, from: response.data); try value.validate(); return value
    }
    func upload(_ value: NativeRelayOwnedShare, file: URL, progress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> NativeRelayReceipt {
        let valid = try await Task.detached(priority: .userInitiated) {
            let bytes = try Data(contentsOf: file, options: .mappedIfSafe)
            return (1...NativeRelayProtocol.maximumBytes).contains(Int64(bytes.count)) && NativeRelayProtocol.hash(bytes) == value.inputSha
        }.value
        guard valid else { throw NativeRelayError.message("This upload's saved bytes changed. Your local recipe is unchanged.") }
        try Task.checkCancellation()
        var request = request("/packs", method: "POST", owner: value.owner)
        request.setValue(value.id, forHTTPHeaderField: "Idempotency-Key")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let response = try await NativeRelayTransport().run(request, upload: file, progress: progress)
        return try receipt(response)
    }
    func download(_ code: String, to target: URL, progress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> URL {
        var request = request("/packs/" + (try NativeRelayProtocol.code(code)))
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        let result = try await NativeRelayTransport().run(request, download: target, progress: progress)
        guard let file = result.file else { throw NativeRelayError.message("The downloaded pack is unavailable.") }
        return file
    }
    func renew(_ value: NativeRelayOwnedShare) async throws -> NativeRelayReceipt {
        guard let code = value.receipt?.code else { throw NativeRelayError.message("Retry this upload to recover its code first.") }
        let response = try await NativeRelayTransport().run(request("/packs/" + (try NativeRelayProtocol.code(code)) + "/renew", method: "POST", owner: value.owner))
        return try receipt(response)
    }
    func revoke(_ value: NativeRelayOwnedShare) async throws {
        guard let code = value.receipt?.code else { throw NativeRelayError.message("Retry this upload to recover its code first.") }
        _ = try await NativeRelayTransport().run(request("/packs/" + (try NativeRelayProtocol.code(code)), method: "DELETE", owner: value.owner))
    }
}
