import Foundation
import CryptoKit

struct NativeRelayResponse { let data: Data; let file: URL? }

/// One bounded request per instance; no cookies, cache, cross-host redirects or URL logging.
final class NativeRelayTransport: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private var session: URLSession?
    private var task: URLSessionTask?
    private let lock = NSLock()
    private var canceled = false
    private var continuation: CheckedContinuation<NativeRelayResponse, Error>?
    private var data = Data()
    private var target: URL?
    private var file: FileHandle?
    private var received: Int64 = 0
    private var total: Int64 = 0
    private var expectedHash = ""
    private var hasher = SHA256()
    private var failure: Error?
    private var progress: (@Sendable (Int64, Int64) -> Void)?
    func run(_ request: URLRequest, upload: URL? = nil, download: URL? = nil, progress: @escaping @Sendable (Int64, Int64) -> Void = { _, _ in }) async throws -> NativeRelayResponse {
        guard request.url?.scheme == "https", request.url?.host == "api.pittech.app" else { throw NativeRelayError.message("The relay address is invalid.") }
        self.target = download; self.progress = progress
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                let configuration = URLSessionConfiguration.ephemeral
                configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false; configuration.urlCache = nil
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.timeoutIntervalForRequest = 30; configuration.timeoutIntervalForResource = 30
                let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue); self.session = session
                let task: URLSessionTask
                if let upload { task = session.uploadTask(with: request, fromFile: upload) }
                else { task = session.dataTask(with: request) }
                lock.lock(); self.task = task; let canceled = self.canceled; lock.unlock()
                if canceled { task.cancel() }; task.resume()
            }
        }, onCancel: { self.lock.lock(); self.canceled = true; self.task?.cancel(); self.lock.unlock() })
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) { progress?(totalBytesSent, totalBytesExpectedToSend) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            failure = NativeRelayError.message(Self.message((response as? HTTPURLResponse)?.statusCode ?? 0)); completionHandler(.cancel); return
        }
        if let target {
            total = response.expectedContentLength; expectedHash = http.value(forHTTPHeaderField: "X-Content-SHA256") ?? ""
            guard (1...NativeRelayProtocol.maximumBytes).contains(total), expectedHash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                failure = NativeRelayError.message("The pack size or checksum is invalid."); completionHandler(.cancel); return
            }
            do {
                guard FileManager.default.createFile(atPath: target.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw NativeRelayError.message("The pack could not be staged.") }
                file = try FileHandle(forWritingTo: target)
            } catch { failure = NativeRelayError.message("The pack could not be staged."); completionHandler(.cancel); return }
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive bytes: Data) {
        received += Int64(bytes.count)
        let limit = target == nil ? Int64(65536) : min(total, NativeRelayProtocol.maximumBytes)
        guard received <= limit else { failure = NativeRelayError.message("The relay response exceeded its supported size."); dataTask.cancel(); return }
        do {
            if let file { try file.write(contentsOf: bytes); hasher.update(data: bytes); progress?(received, total) }
            else { data.append(bytes) }
        } catch { failure = NativeRelayError.message("The pack could not be saved for preview."); dataTask.cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? file?.close(); file = nil
        lock.lock(); let canceled = self.canceled; self.task = nil; lock.unlock()
        var failure = self.failure
        if failure == nil && canceled { failure = CancellationError() }
        else if failure == nil && error != nil { failure = NativeRelayError.message("The relay could not be reached. Your saved draft and retry are unchanged.") }
        if failure == nil && target != nil && (received != total || hasher.finalize().map { String(format: "%02x", $0) }.joined() != expectedHash) { failure = NativeRelayError.message("The download was incomplete. Retry before importing.") }
        if let failure { if let target { try? FileManager.default.removeItem(at: target) }; continuation?.resume(throwing: failure) }
        else { continuation?.resume(returning: NativeRelayResponse(data: data, file: target)) }
        continuation = nil; session.finishTasksAndInvalidate(); self.session = nil
    }
    static func message(_ status: Int) -> String {
        switch status {
        case 404: return "This code is unavailable, expired or revoked. Check it or ask the sender for a new share."
        case 413: return "Choose fewer dishes or photos, then try again."
        case 429: return "Too many attempts. Wait a minute before retrying."
        case 409: return "This retry contains different content. Keep the saved retry or start a new share."
        case 503: return "The relay is busy or new uploads are paused. Retry later or share a Cook Pack file."
        default: return "The relay could not complete this request. Your local draft is unchanged."
        }
    }
}
