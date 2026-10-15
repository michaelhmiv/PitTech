import Foundation
import CoreFoundation
import Observation

@MainActor protocol NativePublicationSession: AnyObject {
    var publicationUserId: String? { get }
    func access(forceRefresh: Bool) async throws -> String
}
extension NativeAccountCoordinator: NativePublicationSession {
    var publicationUserId: String? { stateUserId }
}
struct NativePublicationReceipt: Codable, Equatable {
    let id: String
    let revisionId: String
    let revision: Int
    let url: String
    var kind: String? = nil
    func validate() throws {
        guard UUID(uuidString: id) != nil, UUID(uuidString: revisionId) != nil, revision > 0,
              url == "https://pittech.app/c/" + id, kind.map({ ["recipe", "collection", "attempt"].contains($0) }) ?? true else { throw NativePublicationError.invalid }
    }
}
struct NativePublicationOperation: Codable {
    let id: String
    let userId: String
    let method: String
    let path: String
    let contentType: String
    let sha256: String
    let bytes: Int
    let expectedRevision: String?
    let draftId: String?
    var rejected = false
    var publicationKind: String? = nil
    func validate() throws {
        guard UUID(uuidString: id) != nil, UUID(uuidString: userId) != nil,
              sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              (0...64 * 1024 * 1024).contains(bytes), expectedRevision.map({ UUID(uuidString: $0) != nil }) ?? true,
              draftId.map({ UUID(uuidString: $0) != nil }) ?? true else { throw NativePublicationError.invalid }
        let segments = path.split(separator: "/", omittingEmptySubsequences: false)
        let item = segments.count >= 4 && segments[0] == "" && segments[1] == "v1" && segments[2] == "recipes" && UUID(uuidString: String(segments[3])) != nil
        let recipe = item && segments.count == 4
        let like = item && segments.count == 5 && segments[4] == "like"
        let archive = method == "POST" && path == "/v1/recipes" || method == "PUT" && recipe
        guard publicationKind.map({ archive && ["recipe", "collection", "attempt"].contains($0) }) ?? true else { throw NativePublicationError.invalid }
        guard archive && contentType == "application/zip" && bytes > 0 && (method != "PUT" || expectedRevision != nil)
                || !archive && draftId == nil && contentType == "application/json" && bytes <= 8192 &&
                    (path == "/v1/me/profile" && method == "PUT" || like && ["POST", "DELETE"].contains(method) || recipe && method == "DELETE" && expectedRevision != nil) else { throw NativePublicationError.invalid }
    }
}
private struct NativePublicationState: Codable {
    var version = 1
    var pending: NativePublicationOperation?
    var bindings: [String: NativePublicationReceipt] = [:]
}
@MainActor final class NativePublicationOperationsStore {
    let directory: URL
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("PublicationOperations-v1", isDirectory: true)
    }
    private func prepareDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        var root = directory, values = URLResourceValues(); values.isExcludedFromBackup = true; try root.setResourceValues(values)
    }
    private func stateFile(_ user: String) throws -> URL {
        guard UUID(uuidString: user) != nil else { throw NativePublicationError.accountChanged }
        return directory.appendingPathComponent(NativeRelayProtocol.hash(Data(user.utf8)) + ".json")
    }
    func body(_ operation: NativePublicationOperation) throws -> URL {
        try operation.validate(); return directory.appendingPathComponent(operation.id + ".body")
    }
    private func load(_ user: String) throws -> NativePublicationState {
        let file = try stateFile(user); guard FileManager.default.fileExists(atPath: file.path) else { return NativePublicationState() }
        guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 8 * 1024 * 1024 else { throw NativePublicationError.invalid }
        let state = try JSONDecoder().decode(NativePublicationState.self, from: Data(contentsOf: file))
        guard state.version == 1, state.pending?.userId == nil || state.pending?.userId == user else { throw NativePublicationError.accountChanged }
        try state.pending?.validate(); for receipt in state.bindings.values { try receipt.validate() }; return state
    }
    private func write(_ state: NativePublicationState, user: String) throws {
        try prepareDirectory(); let file = try stateFile(user), bytes = try JSONEncoder().encode(state)
        guard bytes.count <= 8 * 1024 * 1024 else { throw NativePublicationError.limits }
        try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func pending(_ user: String) throws -> NativePublicationOperation? { try load(user).pending }
    func binding(_ user: String, draft: String) throws -> NativePublicationReceipt? { try load(user).bindings[draft] }
    func prepare(user: String, method: String, path: String, bytes: Data, archive: Bool, expected: String? = nil, draft: String? = nil, kind: String? = nil) throws -> NativePublicationOperation {
        var state = try load(user); guard state.pending == nil else { throw NativePublicationError.pending }
        try prepareDirectory()
        // The app is the only writer; this main-actor transaction cannot race another writer.
        // Remove only bodies unreferenced by any account's durable pending operation.
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
        var referenced = Set<String>()
        for file in files where file.pathExtension == "json" {
            guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 8 * 1024 * 1024 else { throw NativePublicationError.invalid }
            let saved = try JSONDecoder().decode(NativePublicationState.self, from: Data(contentsOf: file)); if let op = saved.pending { try op.validate(); referenced.insert(op.id + ".body") }
        }
        var used = 0
        for file in files where file.pathExtension == "body" {
            if referenced.contains(file.lastPathComponent) { used += try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0 }
            else { try FileManager.default.removeItem(at: file) }
        }
        guard used + bytes.count <= 128 * 1024 * 1024 else { throw NativePublicationError.limits }
        let operation = NativePublicationOperation(id: UUID().uuidString.lowercased(), userId: user, method: method, path: path, contentType: archive ? "application/zip" : "application/json", sha256: NativeRelayProtocol.hash(bytes), bytes: bytes.count, expectedRevision: expected, draftId: draft, publicationKind: kind)
        try operation.validate(); let target = try body(operation)
        try bytes.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        // An uncertain metadata failure must not delete a possibly referenced body.
        state.pending = operation; try write(state, user: user)
        return operation
    }
    func verify(_ operation: NativePublicationOperation) throws -> URL {
        guard try load(operation.userId).pending?.id == operation.id else { throw NativePublicationError.pending }
        let file = try body(operation)
        guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize) == operation.bytes,
              NativeRelayProtocol.hash(try Data(contentsOf: file, options: .mappedIfSafe)) == operation.sha256 else { throw NativePublicationError.invalid }; return file
    }
    func reject(_ operation: NativePublicationOperation) throws {
        var state = try load(operation.userId); guard state.pending?.id == operation.id else { throw NativePublicationError.pending }
        state.pending?.rejected = true; try write(state, user: operation.userId)
    }
    func discardRejected(_ user: String) throws {
        var state = try load(user); guard let operation = state.pending, operation.rejected else { throw NativePublicationError.pending }
        state.pending = nil; try write(state, user: user); try? FileManager.default.removeItem(at: body(operation))
    }
    func finish(_ operation: NativePublicationOperation, response: Data) throws {
        var state = try load(operation.userId); guard state.pending?.id == operation.id else { throw NativePublicationError.pending }
        if operation.contentType == "application/zip" {
            var receipt = try JSONDecoder().decode(NativePublicationReceipt.self, from: response); try receipt.validate(); receipt.kind = operation.publicationKind
            if operation.method == "PUT" { guard operation.path == "/v1/recipes/" + receipt.id else { throw NativePublicationError.invalid } }
            if let draft = operation.draftId { state.bindings[draft] = receipt }
        } else {
            guard let value = try JSONSerialization.jsonObject(with: response) as? [String: Any] else { throw NativePublicationError.invalid }
            func boolean(_ key: String) -> Bool? {
                guard let number = value[key] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }; return number.boolValue
            }
            if operation.path == "/v1/me/profile" {
                guard let name = value["displayName"] as? String, name.utf16.count <= 160, boolean("isPublic") != nil,
                      value["handle"] is NSNull || (value["handle"] as? String)?.range(of: "^[a-z0-9_]{3,24}$", options: .regularExpression) != nil else { throw NativePublicationError.invalid }
            } else {
                guard let id = operation.path.split(separator: "/").dropFirst(2).first, value["id"] as? String == String(id) else { throw NativePublicationError.invalid }
                if operation.path.hasSuffix("/like") { guard boolean("liked") == (operation.method == "POST") else { throw NativePublicationError.invalid } }
                else { guard boolean("unpublished") == true else { throw NativePublicationError.invalid } }
            }
        }
        state.pending = nil; try write(state, user: operation.userId); try? FileManager.default.removeItem(at: body(operation))
    }
}
protocol NativePublicationAPI {
    func read(_ path: String, access: String) async throws -> Data
    func write(_ operation: NativePublicationOperation, file: URL, access: String) async throws -> Data
}
struct NativePublicationClient: NativePublicationAPI {
    var configuration: URLSessionConfiguration?
    private func request(_ path: String, access: String) throws -> URLRequest {
        guard NativeAccountProtocol.credential(access) else { throw NativePublicationError.invalid }
        var request = URLRequest(url: URL(string: "https://api.pittech.app" + path)!); request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control"); request.setValue("application/json", forHTTPHeaderField: "Accept"); return request
    }
    func read(_ path: String, access: String) async throws -> Data {
        let ownerItem = path.split(separator: "/")
        let exactOwner = ownerItem.count == 4 && ownerItem[0] == "v1" && ownerItem[1] == "me" && ownerItem[2] == "recipes" && UUID(uuidString: String(ownerItem[3])) != nil
        guard exactOwner || path == "/v1/me/profile" || path.range(of: "^/v1/me/recipes(\\?cursor=[a-f0-9-]{36})?$", options: .regularExpression) != nil else { throw NativePublicationError.invalid }
        return try await NativeRelayTransport(configuration: configuration, responseError: { NativePublicError.status($0) }, maximumResponseBytes: 65536).run(request(path, access: access)).data
    }
    func write(_ operation: NativePublicationOperation, file: URL, access: String) async throws -> Data {
        try operation.validate(); var request = try request(operation.path, access: access); request.httpMethod = operation.method
        request.setValue(operation.id, forHTTPHeaderField: "Idempotency-Key"); request.setValue(operation.contentType, forHTTPHeaderField: "Content-Type")
        if let revision = operation.expectedRevision { request.setValue(revision, forHTTPHeaderField: "If-Match") }
        return try await NativeRelayTransport(configuration: configuration, responseError: { NativePublicError.status($0) }, maximumResponseBytes: 8192).run(request, upload: file).data
    }
}
@MainActor @Observable final class NativePublicationWriter {
    private let session: any NativePublicationSession
    let store: NativePublicationOperationsStore
    private let api: any NativePublicationAPI
    private(set) var busy = false
    init(session: any NativePublicationSession, store: NativePublicationOperationsStore? = nil, api: any NativePublicationAPI = NativePublicationClient()) { self.session = session; self.store = store ?? NativePublicationOperationsStore(); self.api = api }
    var userId: String? { session.publicationUserId }
    func pending() throws -> NativePublicationOperation? { guard let userId else { return nil }; return try store.pending(userId) }
    func binding(_ draft: String) throws -> NativePublicationReceipt? { guard let userId else { return nil }; return try store.binding(userId, draft: draft) }
    private func perform(_ operation: NativePublicationOperation) async throws -> Data {
        guard !operation.rejected else { throw NativePublicationError.pending }
        guard operation.userId == userId else { throw NativePublicationError.accountChanged }
        let file = try store.verify(operation); let token = try await session.access(forceRefresh: false)
        guard operation.userId == userId else { throw NativePublicationError.accountChanged }; try Task.checkCancellation()
        let response: Data
        do { response = try await api.write(operation, file: file, access: token) }
        catch NativePublicError.status(401) {
            let refreshed = try await session.access(forceRefresh: true)
            guard operation.userId == userId else { throw NativePublicationError.accountChanged }; try Task.checkCancellation()
            response = try await api.write(operation, file: file, access: refreshed)
        }
        guard response.count <= 8192 else { throw NativePublicationError.invalid }
        _ = try JSONSerialization.jsonObject(with: response); try store.finish(operation, response: response)
        guard operation.userId == userId else { throw NativePublicationError.accountChanged }; return response
    }
    private func execute(_ operation: NativePublicationOperation) async throws -> Data {
        do { return try await perform(operation) }
        catch NativePublicError.status(let status) {
            if [400,403,404,409,413,415,422].contains(status) { try store.reject(operation) }; throw NativePublicError.status(status)
        }
    }
    func recover() async throws -> Data? {
        guard !busy else { throw NativePublicationError.pending }; guard let operation = try pending() else { return nil }
        busy = true; defer { busy = false }; return try await execute(operation)
    }
    func submit(draft: String, prepared: NativePreparedPublication, update: NativePublicationReceipt? = nil) async throws -> Data {
        guard !busy else { throw NativePublicationError.pending }; guard let userId else { throw NativePublicationError.accountChanged }
        busy = true; defer { busy = false }; try update?.validate()
        let operation = try store.prepare(user: userId, method: update == nil ? "POST" : "PUT", path: update.map { "/v1/recipes/" + $0.id } ?? "/v1/recipes", bytes: prepared.bytes, archive: true, expected: update?.revisionId, draft: draft, kind: prepared.payload.objectValue?["kind"]?.stringValue)
        return try await execute(operation)
    }
    func json(method: String, path: String, body: Data = Data(), expected: String? = nil) async throws -> Data {
        guard !busy else { throw NativePublicationError.pending }; guard let userId else { throw NativePublicationError.accountChanged }
        busy = true; defer { busy = false }
        let operation = try store.prepare(user: userId, method: method, path: path, bytes: body, archive: false, expected: expected)
        return try await execute(operation)
    }
    func read(_ path: String) async throws -> Data {
        guard let userId else { throw NativePublicationError.accountChanged }; let token = try await session.access(forceRefresh: false)
        guard userId == self.userId else { throw NativePublicationError.accountChanged }
        let bytes: Data
        do { bytes = try await api.read(path, access: token) }
        catch NativePublicError.status(401) {
            let refreshed = try await session.access(forceRefresh: true)
            guard userId == self.userId else { throw NativePublicationError.accountChanged }; try Task.checkCancellation()
            bytes = try await api.read(path, access: refreshed)
        }
        guard bytes.count <= 65536 else { throw NativePublicationError.invalid }; guard userId == self.userId else { throw NativePublicationError.accountChanged }; return bytes
    }
}
