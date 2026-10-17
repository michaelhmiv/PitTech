import Foundation
import CryptoKit

struct NativePrivateSyncHttpError: Error, Equatable {
    var status: Int
    var code: String
    var conflict: Bool { status == 409 }
    var unauthorized: Bool { status == 401 }
}

protocol NativePrivateSyncAPI {
    func snapshot(access: String) async throws -> Data
    func commit(body: Data, mutationId: String, access: String) async throws -> Data
    func beginPhoto(assetId: String, bytes: Int, mime: String, sha: String, access: String) async throws -> Data
    func photoStatus(assetId: String, access: String) async throws -> Data
    func putPart(assetId: String, index: Int, chunk: Data, access: String) async throws -> Data
    func completePhoto(assetId: String, access: String) async throws -> Data
    func abandonPhoto(assetId: String, access: String) async throws -> Data
    func downloadPhoto(assetId: String, to: URL, access: String) async throws
}

/// One bounded request per instance; no cookies, cache, cross-host redirects or URL logging.
final class NativeSyncTransport: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let maximumResponseBytes: Int64
    private let configuration: URLSessionConfiguration?
    init(configuration: URLSessionConfiguration? = nil, maximumResponseBytes: Int64 = 65536) { self.maximumResponseBytes = maximumResponseBytes; self.configuration = configuration; super.init() }
    private var task: URLSessionTask?
    private let lock = NSLock()
    private var canceled = false
    private var continuation: CheckedContinuation<Data, Error>?
    private var data = Data()
    private var target: URL?
    private var file: FileHandle?
    private var received: Int64 = 0
    private var total: Int64 = 0
    private var expectedHash = ""
    private var hasher = SHA256()
    private var failure: Error?
    private var httpStatus = 0
    private var errorBody = Data()
    func run(_ request: URLRequest, upload: Data? = nil, download: URL? = nil) async throws {
        guard request.url?.scheme == "https", request.url?.host == "api.pittech.app" else { throw NativeSyncError.invalid }
        self.target = download
        try await withTaskCancellationHandler(operation: {
            _ = try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                let configuration = self.configuration ?? URLSessionConfiguration.ephemeral
                configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false; configuration.urlCache = nil
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.timeoutIntervalForRequest = 120; configuration.timeoutIntervalForResource = 120
                let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
                let task: URLSessionTask
                if let upload { task = session.uploadTask(with: request, from: upload) }
                else { task = session.dataTask(with: request) }
                lock.lock(); self.task = task; let canceled = self.canceled; lock.unlock()
                if canceled { task.cancel() }; task.resume()
            }
        }, onCancel: { self.lock.lock(); self.canceled = true; self.task?.cancel(); self.lock.unlock() })
    }
    var result: Data { data }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else { failure = URLError(.badServerResponse); completionHandler(.cancel); return }
        httpStatus = http.statusCode
        guard (200...299).contains(http.statusCode) else { completionHandler(.allow); return }
        if let target {
            total = response.expectedContentLength; expectedHash = http.value(forHTTPHeaderField: "X-Content-SHA256") ?? ""
            guard (1...Int64(NativePrivateSyncProtocol.maxPhotoBytes)).contains(total), expectedHash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                failure = NativeSyncError.invalid; completionHandler(.cancel); return
            }
            do {
                guard FileManager.default.createFile(atPath: target.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw NativeSyncError.invalid }
                file = try FileHandle(forWritingTo: target)
            } catch { failure = NativeSyncError.invalid; completionHandler(.cancel); return }
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive bytes: Data) {
        if !(200...299).contains(httpStatus) {
            if errorBody.count < 2048 { errorBody.append(bytes.prefix(2048 - errorBody.count)) }
            return
        }
        received += Int64(bytes.count)
        let limit = target == nil ? maximumResponseBytes : min(total, Int64(NativePrivateSyncProtocol.maxPhotoBytes))
        guard received <= limit else { failure = NativeSyncError.invalid; dataTask.cancel(); return }
        do {
            if let file { try file.write(contentsOf: bytes); hasher.update(data: bytes) }
            else { data.append(bytes) }
        } catch { failure = NativeSyncError.invalid; dataTask.cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? file?.close(); file = nil
        lock.lock(); let canceled = self.canceled; self.task = nil; lock.unlock()
        var failure = self.failure
        if failure == nil && canceled { failure = CancellationError() }
        else if failure == nil, let error { failure = error }
        if failure == nil, !(200...299).contains(httpStatus) {
            let code = (try? JSONDecoder().decode(JSONValue.self, from: errorBody))?.objectValue?["error"]?.objectValue?["code"]?.stringValue ?? ""
            failure = NativePrivateSyncHttpError(status: httpStatus, code: code)
        }
        if failure == nil, target != nil, received != total || hasher.finalize().map({ String(format: "%02x", $0) }).joined() != expectedHash { failure = NativeSyncError.invalid }
        if let failure { continuation?.resume(throwing: failure) } else { continuation?.resume(returning: data) }
        continuation = nil
    }
}

struct NativePrivateSyncClient: NativePrivateSyncAPI {
    var configuration: URLSessionConfiguration?
    private func request(_ path: String, access: String) throws -> URLRequest {
        guard NativeAccountProtocol.credential(access) else { throw NativeSyncError.invalid }
        var request = URLRequest(url: URL(string: "https://api.pittech.app" + path)!)
        request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        return request
    }
    private func photoPath(_ assetId: String, _ suffix: String = "") throws -> String {
        guard UUID(uuidString: assetId) != nil, assetId == assetId.lowercased() else { throw NativeSyncError.invalid }
        return "/v1/sync/photos/" + assetId + suffix
    }
    private func json(_ request: URLRequest, limit: Int64 = 8192) async throws -> Data {
        let transport = NativeSyncTransport(configuration: configuration, maximumResponseBytes: limit)
        try await transport.run(request)
        return transport.result
    }
    func snapshot(access: String) async throws -> Data {
        var request = try request("/v1/sync", access: access); request.httpMethod = "GET"
        return try await json(request, limit: Int64(NativePrivateSyncProtocol.maxSnapshotBytes) + 1024)
    }
    func commit(body: Data, mutationId: String, access: String) async throws -> Data {
        guard UUID(uuidString: mutationId) != nil, mutationId == mutationId.lowercased(), (1...NativePrivateSyncProtocol.maxSnapshotBytes).contains(body.count) else { throw NativeSyncError.invalid }
        var request = try request("/v1/sync", access: access); request.httpMethod = "PUT"
        request.setValue(mutationId, forHTTPHeaderField: "Idempotency-Key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let transport = NativeSyncTransport(configuration: configuration)
        try await transport.run(request, upload: body)
        return transport.result
    }
    func beginPhoto(assetId: String, bytes: Int, mime: String, sha: String, access: String) async throws -> Data {
        guard (1...NativePrivateSyncProtocol.maxPhotoBytes).contains(bytes), ["image/jpeg", "image/png", "image/webp"].contains(mime),
              sha.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw NativeSyncError.invalid }
        var request = try request("/v1/sync/photos", access: access); request.httpMethod = "POST"
        request.setValue(assetId, forHTTPHeaderField: "Idempotency-Key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body = try JSONEncoder().encode(JSONValue.object(["assetId": .string(assetId), "bytes": .number(Double(bytes)), "mimeType": .string(mime), "sha256": .string(sha)]))
        let transport = NativeSyncTransport(configuration: configuration)
        try await transport.run(request, upload: body)
        return transport.result
    }
    func photoStatus(assetId: String, access: String) async throws -> Data {
        var request = try request(photoPath(assetId, "/status"), access: access); request.httpMethod = "GET"
        return try await json(request)
    }
    func putPart(assetId: String, index: Int, chunk: Data, access: String) async throws -> Data {
        guard (0..<16).contains(index), (1...NativePrivateSyncProtocol.chunkBytes).contains(chunk.count) else { throw NativeSyncError.invalid }
        var request = try request(photoPath(assetId, "/parts/\(index)"), access: access); request.httpMethod = "PUT"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let transport = NativeSyncTransport(configuration: configuration)
        try await transport.run(request, upload: chunk)
        return transport.result
    }
    func completePhoto(assetId: String, access: String) async throws -> Data {
        var request = try request(photoPath(assetId, "/complete"), access: access); request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let transport = NativeSyncTransport(configuration: configuration)
        try await transport.run(request, upload: Data("{}".utf8))
        return transport.result
    }
    func abandonPhoto(assetId: String, access: String) async throws -> Data {
        var request = try request(photoPath(assetId), access: access); request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let transport = NativeSyncTransport(configuration: configuration)
        try await transport.run(request, upload: Data("{\"confirm\":\"abandon-private-photo\"}".utf8))
        return transport.result
    }
    func downloadPhoto(assetId: String, to: URL, access: String) async throws {
        var request = try request(photoPath(assetId), access: access); request.httpMethod = "GET"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        do {
            try await NativeSyncTransport(configuration: configuration).run(request, download: to)
        } catch { try? FileManager.default.removeItem(at: to); throw error }
    }
}
