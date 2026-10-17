import Foundation
import Observation

struct NativePrivateSyncPresentation {
    var status = "Sync is off"
    var detail: String?
    var lastSyncedAt: Int64 = 0
    var conflicts: [NativePrivateSyncConflictView] = []
    var busy = false
    var signedIn = false
}

private struct NativeSyncAuthLost: Error {}

/** Device-authoritative private sync. Local writes always complete first; network work is
 * queued here. Sync runs only on explicit request, only for the current session account,
 * and stays disabled until the accounts/social/sync/deletion phase passes. */
@MainActor @Observable final class NativePrivateSyncCoordinator {
    private let store: any NativePrivateSyncStorage
    private let session: any NativePublicationSession
    private let api: any NativePrivateSyncAPI
    private let library: any NativeSyncLibrary
    private let projection: NativePrivateSyncProjection?
    let enabled: Bool
    private let now: () -> Int64
    private var boundUser: String?
    private var downloadCache: [String: Data] = [:]
    private(set) var presentation = NativePrivateSyncPresentation()
    nonisolated init(store: any NativePrivateSyncStorage, session: any NativePublicationSession, api: any NativePrivateSyncAPI = NativePrivateSyncClient(), library: any NativeSyncLibrary, projection: NativePrivateSyncProjection?, enabled: Bool = NativeAccountProtocol.enabled, now: @escaping () -> Int64 = { Int64(Date.now.timeIntervalSince1970 * 1000) }) {
        self.store = store; self.session = session; self.api = api; self.library = library; self.projection = projection; self.enabled = enabled; self.now = now
    }
    static func forApp(store: CookStore, account: NativeAccountCoordinator) -> NativePrivateSyncCoordinator {
        #if DEBUG
        if NativeSyncUITestSupport.active { return NativeSyncUITestSupport.sync(store: store, account: account) }
        #endif
        return NativePrivateSyncCoordinator(store: (try? NativePrivateSyncStore()) ?? NativePrivateSyncMemoryStore(), session: account,
            library: NativeCookStoreSyncLibrary(store), projection: try? loadContract())
    }
    static func loadContract(bundle: Bundle = .main) throws -> NativePrivateSyncProjection {
        guard let url = bundle.url(forResource: "private-sync-projection-v1", withExtension: "json") else { throw NativeSyncError.invalid }
        return try NativePrivateSyncProjection(data: Data(contentsOf: url))
    }

    func refresh() { publish(bindUser(), busy: false) }

    /** Bind the current session account. A switch or sign-out cancels that account's
     * pending upload; the acknowledged base stays so the same account can resume, and
     * nothing is ever attached to another account. */
    private func bind() {
        let user = session.publicationUserId
        guard user != boundUser else { return }
        if let old = boundUser, let state = try? store.load(old), let mutation = state.mutationId {
            try? FileManager.default.removeItem(at: store.bodyURL(old, mutation))
            var cancelled = state; cancelled.mutationId = nil; cancelled.mutationSha = nil
            try? store.save(old, cancelled)
        }
        boundUser = user
    }

    func syncNow() async {
        guard !presentation.busy else { return }
        publish(bindUser(), busy: true)
        do { try await attempt() } catch is CancellationError { publish(boundUser, busy: false) } catch {
            if let user = boundUser, var state = try? store.load(user) {
                state.outcome = "retry"; state.message = "Private sync needs a retry. Your local journal is unchanged."
                try? store.save(user, state)
            }
            publish(boundUser, busy: false)
        }
    }

    func resolve(_ id: String, choice: String) async {
        guard !presentation.busy else { return }
        publish(bindUser(), busy: true)
        defer { publish(boundUser, busy: false) }
        do {
            guard enabled, let projection, let user = bindUser() else { return }
            let device = try store.deviceId()
            var state = try store.load(user)
            guard let conflict = state.conflicts.first(where: { $0.id == id }) else { return }
            let resolution = try NativePrivateSyncProtocol.resolve(conflict, choice: choice, deviceId: device)
            if let type = resolution.restoreType, let restoreId = resolution.restoreId, let payload = resolution.restorePayload {
                let archive = try library.archive()
                var assets = Dictionary(uniqueKeysWithValues: state.photos.map { ($0.key, $0.value.assetId) })
                var attachments: [String: Data] = [:]
                if let assetId = resolution.restoreAssetId {
                    guard let bytes = try await restoreBytes(resolution, state: state, user: user) else {
                        var waiting = state; waiting.outcome = "waiting"; waiting.message = "Waiting for connection. The conflict is still pending review."
                        try? store.save(user, waiting); return
                    }
                    attachments[restoreId] = bytes
                    assets[type + "|" + restoreId] = assetId
                }
                let upsert = NativeSyncUpsert(type: type, id: restoreId, payload: payload)
                do { _ = try projection.project(validatedArchive(archive, upserts: [upsert], deletes: []), assets: assets) } catch {
                    var failed = state; failed.outcome = "retry"; failed.message = "This resolution could not be applied safely. Your journal is unchanged."
                    try? store.save(user, failed); return
                }
                try library.apply(NativeSyncApplyPlan(upserts: [upsert], deletes: [], attachments: attachments))
                if let assetId = resolution.restoreAssetId, let bytes = attachments[restoreId] {
                    let count = (bytes.count + NativePrivateSyncProtocol.chunkBytes - 1) / NativePrivateSyncProtocol.chunkBytes
                    state.photos[type + "|" + restoreId] = NativePrivateSyncPhotoEntry(assetId: assetId, sha256: NativeRelayProtocol.hash(bytes), bytes: bytes.count, mime: mimeOf(payload), received: Array(0..<count), complete: true)
                }
            }
            state.conflicts.removeAll { $0.id == id }
            if !state.resolved.contains(id) { state.resolved.append(id) }
            state.pending = true
            try store.save(user, state)
        } catch { }
    }

    private func restoreBytes(_ resolution: NativeConflictResolution, state: NativePrivateSyncState, user: String) async throws -> Data? {
        guard let type = resolution.restoreType, let payload = resolution.restorePayload, let assetId = resolution.restoreAssetId else { return nil }
        let originalId = payload[NativePrivateSyncProtocol.keyField(type)]?.stringValue ?? ""
        // Keep both of a locally-lost photo reuses the bytes already on this device.
        if let local = try library.photoData(type: type, id: originalId), state.photos[type + "|" + originalId]?.assetId == assetId { return local.0 }
        let staging = try store.stagingDirectory()
        let file = staging.appendingPathComponent(assetId + ".download")
        do {
            try await authorized(user) { try await api.downloadPhoto(assetId: assetId, to: file, access: $0) }
            let bytes = try Data(contentsOf: file)
            guard (1...NativePrivateSyncProtocol.maxPhotoBytes).contains(bytes.count) else { throw NativeSyncError.invalid }
            return bytes
        } catch is NativePrivateSyncHttpError { return nil } catch is URLError { return nil } catch is CocoaError { return nil }
        finally { try? FileManager.default.removeItem(at: file) }
    }

    private func mimeOf(_ payload: [String: JSONValue]) -> String {
        let mime = payload["mimeType"]?.stringValue ?? "image/jpeg"
        return ["image/jpeg", "image/png", "image/webp"].contains(mime) ? mime : "image/jpeg"
    }

    private func bindUser() -> String? { bind(); return boundUser }

    private func attempt() async throws {
        guard enabled, let projection, let user = bindUser() else { return }
        let device = try store.deviceId()
        var state = try store.load(user)
        let archive: JSONValue
        do { archive = try library.archive() } catch { fail(user, state, outcome: "retry", message: "Private sync needs a retry. Your local journal is unchanged."); return }
        do { state = try await ensureRegistry(user: user, state: state, archive: archive) }
        catch is URLError { fail(user, state, outcome: "waiting", message: "Waiting for connection. Photo uploads resume where they stopped."); return }
        catch { fail(user, state, outcome: "retry", message: "Private sync needs a retry."); return }
        var assets = Dictionary(uniqueKeysWithValues: state.photos.map { ($0.key, $0.value.assetId) })
        var projected: [NativePrivateProjectedRecord]
        do { projected = try projection.project(archive, assets: assets) } catch {
            // A library that fails projection fails the attempt. Nothing uploads; nothing changes locally.
            fail(user, state, outcome: "retry", message: "Some saved information cannot be synced safely yet. Your journal is unchanged."); return
        }
        var local = try NativePrivateSyncProtocol.stampLocal(projected, base: state.base, deviceId: device, counter: state.maxCounter)
        // Every mutation advances the logical counter beyond observed counters, including on
        // paths that never merge (a fresh upload), so no version is ever stamped twice.
        state = advancedAfterStamp(user: user, state: state, stamped: local)
        guard var fetched = try await fetch(user: user, state: state) else { return }
        var attempts = 0
        while true {
            if fetched.envelope == nil {
                if state.serverVersion == 0, state.base.isEmpty, fetched.version == 0 {
                    switch try await upload(user: user, state: state, baseVersion: 0, records: local, retained: state.conflicts, fresh: [], resolved: state.resolved, serverIds: [], projectedKeys: Set(projected.map { $0.recordType + "|" + $0.recordId })) {
                    case .acked, .failed: return
                    case .conflict:
                        attempts += 1
                        guard attempts < 3 else { fail(user: user, (try? store.load(user)) ?? state, outcome: "retry", message: "Another device keeps changing the backup. Retry shortly."); return }
                        guard let refetched = try await fetch(user: user, state: (try? store.load(user)) ?? state) else { return }
                        fetched = refetched; state = (try? store.load(user)) ?? state
                        continue
                    }
                }
                // The server holds no snapshot while this device remembers one: rebase by
                // restamping every row as this device's own mutation. Nothing is dropped silently.
                guard fetched.version == 0 else { fail(user: user, state: state, outcome: "retry", message: "Private sync needs a retry. Your local journal is unchanged."); return }
                let rebasedRows = try restampAll(projected, device: device, counter: ((try? store.load(user)) ?? state).maxCounter)
                var rebased = ((try? store.load(user)) ?? state); rebased.conflicts = []; rebased.resolved = []
                rebased = advancedAfterStamp(user: user, state: rebased, stamped: rebasedRows)
                switch try await upload(user: user, state: rebased, baseVersion: 0, records: rebasedRows, retained: [], fresh: [], resolved: [], serverIds: [], projectedKeys: Set(projected.map { $0.recordType + "|" + $0.recordId })) {
                case .acked, .failed: return
                case .conflict:
                    attempts += 1
                    guard attempts < 3 else { fail(user: user, (try? store.load(user)) ?? state, outcome: "retry", message: "Another device keeps changing the backup. Retry shortly."); return }
                    guard let refetched = try await fetch(user: user, state: (try? store.load(user)) ?? state) else { return }
                    fetched = refetched; state = (try? store.load(user)) ?? state
                    continue
                }
            }
            let version = fetched.version, remote = fetched.envelope!
            guard version >= state.serverVersion else { fail(user: user, state: state, outcome: "retry", message: "Private sync needs a retry. Your local journal is unchanged."); return }
            do { try projection.validateRows(liveProjected(remote.records)) } catch { fail(user: user, state: state, outcome: "retry", message: "The server backup could not be applied safely. Your journal is unchanged."); return }
            let merged: NativeSyncMergeResult
            do { merged = try NativePrivateSyncMerge.merge(base: state.base, local: local, remote: remote.records) } catch { fail(user: user, state: state, outcome: "retry", message: "The server backup could not be merged safely. Your journal is unchanged."); return }
            state.maxCounter = max(state.maxCounter, merged.nextCounter)
            let previousConflicts = state.conflicts.count
            let retained = unionConflicts(state.conflicts, merged.conflicts, resolved: state.resolved)
            state.conflicts = retained
            try? store.save(user, state)
            do { try projection.validateRows(liveProjected(merged.records)) } catch { fail(user: user, state: state, outcome: "retry", message: "The merged backup could not be applied safely. Your journal is unchanged."); return }
            if merged.blocked {
                // Immutable rewrites never upload. The old rows stay; both versions await review.
                guard try await applyMerged(user: user, state: state, merged: merged.records, build: fetched.build ?? archive, assets: assets) else { return }
                if var pending = try? store.load(user) { pending.pending = true; try? store.save(user, pending) }
                publish(user, busy: false); return
            }
            if version == state.serverVersion, sameRecords(merged.records, state.base), retained.count == previousConflicts, state.resolved.isEmpty {
                state.lastSyncedAt = now(); state.outcome = "ok"; state.message = nil
                try? store.save(user, state)
                publish(user, busy: false); return
            }
            guard try await applyMerged(user: user, state: state, merged: merged.records, build: fetched.build ?? archive, assets: assets) else { return }
            state = (try? store.load(user)) ?? state
            let serverIds = Set(remote.conflicts.map(\.id))
            switch try await upload(user: user, state: state, baseVersion: version, records: merged.records, retained: state.conflicts, fresh: merged.conflicts, resolved: state.resolved, serverIds: serverIds, projectedKeys: Set(projected.map { $0.recordType + "|" + $0.recordId })) {
            case .acked, .failed: return
            case .conflict: break
            }
            attempts += 1
            guard attempts < 3 else { fail(user: user, (try? store.load(user)) ?? state, outcome: "retry", message: "Another device keeps changing the backup. Retry shortly."); return }
            // 409 with a moved server version: fetch, merge and retry with a fresh identity.
            guard let refetched = try await fetch(user: user, state: (try? store.load(user)) ?? state), refetched.version >= version else {
                fail(user: user, state, outcome: "retry", message: "Private sync needs a retry. Your local journal is unchanged."); return
            }
            fetched = refetched
            state = (try? store.load(user)) ?? state
            // Rebuild from the current journal: the merged apply above changed it, so the
            // retry diffs and validates against the fresh build, not the stale one.
            let rebuilt = try library.archive()
            assets = Dictionary(uniqueKeysWithValues: state.photos.map { ($0.key, $0.value.assetId) })
            do { projected = try projection.project(rebuilt, assets: assets) } catch { fail(user: user, state: state, outcome: "retry", message: "Some saved information cannot be synced safely yet. Your journal is unchanged."); return }
            fetched.build = rebuilt
            local = try NativePrivateSyncProtocol.stampLocal(projected, base: state.base, deviceId: device, counter: state.maxCounter)
        }
    }

    private struct Fetched { var version: Int64; var envelope: NativeSyncEnvelope?; var build: JSONValue? }
    private func fetch(user: String, state: NativePrivateSyncState) async throws -> Fetched? {
        let reply: Data
        do { reply = try await authorized(user) { try await api.snapshot(access: $0) } }
        catch is URLError { fail(user: user, state: state, outcome: "waiting", message: "Waiting for connection. Your changes stay on this device."); return nil }
        catch let http as NativePrivateSyncHttpError { fail(user: user, state: state, outcome: "retry", message: http.status == 401 ? "Sign in again to reconnect private sync. Your local journal is unchanged." : "Private sync needs a retry."); return nil }
        catch is NativeSyncAuthLost { bind(); publish(boundUser, busy: false); return nil }
        do {
            let (version, envelope) = try NativePrivateSyncProtocol.parseSnapshotGet(reply)
            return Fetched(version: version, envelope: envelope, build: nil)
        } catch { fail(user: user, state: state, outcome: "retry", message: "The server backup could not be read safely. Your journal is unchanged."); return nil }
    }

    private func authorized<T>(_ user: String, _ block: (String) async throws -> T) async throws -> T {
        guard session.publicationUserId == user else { throw NativeSyncAuthLost() }
        do { return try await block(try await session.access(forceRefresh: false)) } catch let http as NativePrivateSyncHttpError {
            guard http.unauthorized else { throw http }
            let token = try await session.access(forceRefresh: true)
            guard session.publicationUserId == user else { throw NativeSyncAuthLost() }
            return try await block(token)
        } catch { if session.publicationUserId != user { throw NativeSyncAuthLost() }; throw error }
    }

    private func applyMerged(user: String, state: NativePrivateSyncState, merged: [NativeSyncRecord], build: JSONValue, assets: [String: String]) async throws -> Bool {
        let old = Dictionary(uniqueKeysWithValues: state.base.map { ($0.key, $0) })
        var upserts: [NativeSyncUpsert] = [], deletes: [NativeSyncDelete] = []
        for row in merged {
            if let prior = old[row.key], prior == row { continue }
            if row.deleted { deletes.append(NativeSyncDelete(type: row.recordType, id: row.recordId)) }
            else if let payload = row.payload { upserts.append(NativeSyncUpsert(type: row.recordType, id: row.recordId, payload: payload)) }
            else { fail(user: user, state: state, outcome: "retry", message: "The merged backup could not be applied safely. Your journal is unchanged."); return false }
        }
        if upserts.isEmpty, deletes.isEmpty { return true }
        // A local write that landed mid-sync on an affected row aborts this attempt so the
        // next pass merges it properly; unrelated concurrent writes never block sync.
        let fresh = try library.archive()
        for key in (upserts.map { $0.type + "|" + $0.id } + deletes.map { $0.type + "|" + $0.id }) {
            let type = String(key.split(separator: "|", maxSplits: 1).first ?? ""), id = String(key.split(separator: "|", maxSplits: 1).last ?? "")
            if archiveRow(build, type: type, id: id) != archiveRow(fresh, type: type, id: id) {
                fail(user: user, state: state, outcome: "retry", message: "Your journal changed during sync. Retry to merge the latest edits."); return false
            }
        }
        var mutableAssets = assets
        var live = state
        for row in upserts where ["photos", "recipeAssets", "companionPhotos"].contains(row.type) {
            guard let assetId = row.payload["assetId"]?.stringValue else { fail(user: user, state: state, outcome: "retry", message: "The merged backup could not be applied safely. Your journal is unchanged."); return false }
            let key = row.type + "|" + row.id
            if live.photos[key]?.assetId == assetId, live.photos[key]?.complete == true { continue }
            guard let bytes = try await downloadAsset(user: user, assetId: assetId) else {
                fail(user: user, state: live, outcome: "waiting", message: "Waiting for connection. A photo could not be downloaded yet."); return false
            }
            mutableAssets[key] = assetId
            let count = (bytes.count + NativePrivateSyncProtocol.chunkBytes - 1) / NativePrivateSyncProtocol.chunkBytes
            live.photos[key] = NativePrivateSyncPhotoEntry(assetId: assetId, sha256: NativeRelayProtocol.hash(bytes), bytes: bytes.count, mime: mimeOf(row.payload), received: Array(0..<count), complete: true)
        }
        try? store.save(user, live)
        var attachments: [String: Data] = [:]
        for row in upserts where ["photos", "recipeAssets", "companionPhotos"].contains(row.type) {
            let key = row.type + "|" + row.id
            if state.photos[key]?.assetId == row.payload["assetId"]?.stringValue, state.photos[key]?.complete == true { continue }
            guard let bytes = downloadCache.removeValue(forKey: row.payload["assetId"]?.stringValue ?? "") else {
                fail(user: user, state: live, outcome: "retry", message: "A photo could not be staged for this sync."); return false
            }
            attachments[row.id] = bytes
        }
        do { _ = try projection.project(validatedArchive(build, upserts: upserts, deletes: deletes), assets: mutableAssets) } catch {
            fail(user: user, state: live, outcome: "retry", message: "The merged backup could not be applied safely. Your journal is unchanged."); return false
        }
        do { try library.apply(NativeSyncApplyPlan(upserts: upserts, deletes: deletes, attachments: attachments)) } catch is CancellationError { throw CancellationError() } catch {
            fail(user: user, state: live, outcome: "retry", message: "The merged backup could not be saved. Your journal is unchanged."); return false
        }
        return true
    }

    private func downloadAsset(user: String, assetId: String) async throws -> Data? {
        if let cached = downloadCache[assetId] { return cached }
        let staging = try store.stagingDirectory()
        let file = staging.appendingPathComponent(assetId + ".download")
        do {
            try await authorized(user) { try await api.downloadPhoto(assetId: assetId, to: file, access: $0) }
            let bytes = try Data(contentsOf: file)
            guard (1...NativePrivateSyncProtocol.maxPhotoBytes).contains(bytes.count) else { throw NativeSyncError.invalid }
            downloadCache[assetId] = bytes
            return bytes
        } catch is NativePrivateSyncHttpError { return nil } catch is URLError { return nil } catch is CocoaError { return nil }
        finally { try? FileManager.default.removeItem(at: file) }
    }

    /** Mint stable asset identities for local photos and upload every incomplete one with
     * durable per-part state, so an interruption resumes instead of restarting. Commits
     * only ever advertise complete objects. */
    private func ensureRegistry(user: String, state: NativePrivateSyncState, archive: JSONValue) async throws -> NativePrivateSyncState {
        var live = state
        guard let root = archive.objectValue else { throw NativeSyncError.invalid }
        for type in ["photos", "recipeAssets", "companionPhotos"] {
            for row in root[type]?.arrayValue ?? [] {
                guard let o = row.objectValue, let id = o["id"]?.stringValue else { throw NativeSyncError.invalid }
                let key = type + "|" + id
                guard let source = try library.photoData(type: type, id: id) else { throw NativeSyncError.invalid }
                guard (1...NativePrivateSyncProtocol.maxPhotoBytes).contains(source.0.count) else { throw NativeSyncError.invalid }
                guard ["image/jpeg", "image/png", "image/webp"].contains(source.1) else { throw NativeSyncError.invalid }
                let sha = NativeRelayProtocol.hash(source.0)
                if let prior = live.photos[key], prior.sha256 == sha, prior.bytes == source.0.count, prior.mime == source.1 { continue }
                if let prior = live.photos[key] { try? await authorized(user) { try await api.abandonPhoto(assetId: prior.assetId, access: $0) } }
                live.photos[key] = NativePrivateSyncPhotoEntry(assetId: UUID().uuidString.lowercased(), sha256: sha, bytes: source.0.count, mime: source.1, received: [], complete: false)
            }
        }
        try store.save(user, live)
        for key in live.photos.keys.sorted() {
            guard let entry = live.photos[key], !entry.complete else { continue }
            let type = String(key.split(separator: "|", maxSplits: 1).first ?? ""), id = String(key.split(separator: "|", maxSplits: 1).last ?? "")
            guard let local = try library.photoData(type: type, id: id), NativeRelayProtocol.hash(local.0) == entry.sha256 else { continue }
            live = try await uploadPhoto(user: user, state: live, key: key, entry: entry, bytes: local.0)
        }
        return live
    }

    private func uploadPhoto(user: String, state: NativePrivateSyncState, key: String, entry: NativePrivateSyncPhotoEntry, bytes: Data) async throws -> NativePrivateSyncState {
        var live = state
        var received = Set(entry.received)
        let chunk = NativePrivateSyncProtocol.chunkBytes
        let count = (entry.bytes + chunk - 1) / chunk
        let status: JSONValue
        do {
            status = try JSONDecoder().decode(JSONValue.self, from: try await authorized(user) { try await api.photoStatus(assetId: entry.assetId, access: $0) })
        } catch let http as NativePrivateSyncHttpError {
            guard http.status == 404 else { throw http }
            do {
                status = try JSONDecoder().decode(JSONValue.self, from: try await authorized(user) { try await api.beginPhoto(assetId: entry.assetId, bytes: entry.bytes, mime: entry.mime, sha: entry.sha256, access: $0) })
            } catch let retry as NativePrivateSyncHttpError {
                guard retry.conflict else { throw retry }
                // The server holds different bytes under this identity: mint a fresh one.
                var fresh = entry; fresh.assetId = UUID().uuidString.lowercased(); fresh.received = []
                live.photos[key] = fresh
                try store.save(user, live)
                return try await uploadPhoto(user: user, state: live, key: key, entry: fresh, bytes: bytes)
            }
        }
        guard let o = status.objectValue else { throw NativeSyncError.invalid }
        if o["complete"] == .bool(true) {
            live.photos[key] = NativePrivateSyncPhotoEntry(assetId: entry.assetId, sha256: entry.sha256, bytes: entry.bytes, mime: entry.mime, received: Array(0..<count), complete: true)
            try store.save(user, live)
            return live
        }
        for part in o["receivedParts"]?.arrayValue ?? [] { if let n = part.numberValue { received.insert(Int(n)) } }
        for index in 0..<count {
            if received.contains(index) { continue }
            let slice = Data(bytes[(index * chunk)..<min(bytes.count, (index + 1) * chunk)])
            do {
                _ = try await authorized(user) { try await api.putPart(assetId: entry.assetId, index: index, chunk: slice, access: $0) }
            } catch let http as NativePrivateSyncHttpError {
                guard http.conflict else { throw http }
                // A replaced or expired part restarts that part with the server's current state.
                let current = try JSONDecoder().decode(JSONValue.self, from: try await authorized(user) { try await api.photoStatus(assetId: entry.assetId, access: $0) })
                for part in current.objectValue?["receivedParts"]?.arrayValue ?? [] { if let n = part.numberValue { received.insert(Int(n)) } }
                if received.contains(index) { continue }
                throw http
            }
            received.insert(index)
            live.photos[key] = NativePrivateSyncPhotoEntry(assetId: entry.assetId, sha256: entry.sha256, bytes: entry.bytes, mime: entry.mime, received: received.sorted(), complete: false)
            try store.save(user, live)
        }
        _ = try await authorized(user) { try await api.completePhoto(assetId: entry.assetId, access: $0) }
        live.photos[key] = NativePrivateSyncPhotoEntry(assetId: entry.assetId, sha256: entry.sha256, bytes: entry.bytes, mime: entry.mime, received: Array(0..<count), complete: true)
        try store.save(user, live)
        return live
    }

    private enum UploadResult { case acked, failed, conflict }
    private func upload(user: String, state: NativePrivateSyncState, baseVersion: Int64, records: [NativeSyncRecord], retained: [NativeSyncConflict], fresh: [NativeSyncConflict], resolved: [String], serverIds: Set<String>, projectedKeys: Set<String>) async throws -> UploadResult {
        var live = (try? store.load(user)) ?? state
        let candidateId = live.mutationId ?? UUID().uuidString.lowercased()
        let candidate: NativePrivateSyncProtocol.PutBody
        do { candidate = try NativePrivateSyncProtocol.putBody(baseVersion: baseVersion, mutationId: candidateId, deviceId: store.deviceId(), records: records, retained: retained, fresh: fresh, resolved: resolved, serverConflictIds: serverIds) } catch {
            fail(user: user, state: live, outcome: "retry", message: "This library could not be uploaded safely. Your journal is unchanged."); return .failed
        }
        let stored = live.mutationId.flatMap { try? store.readBody(user, $0) }
        if let stored, live.mutationSha == NativeRelayProtocol.hash(candidate.data), NativeRelayProtocol.hash(stored) == NativeRelayProtocol.hash(candidate.data), let mutation = live.mutationId {
            // Exact retry of the persisted upload: same identity, same bytes.
            live.pending = true; try? store.save(user, live)
            return try await commit(user: user, state: live, bytes: stored, mutation: mutation, baseVersion: baseVersion, records: records, retained: retained, fresh: fresh, resolved: resolved, serverIds: serverIds, projectedKeys: projectedKeys)
        }
        // Changed content always rotates to a fresh idempotency identity; the server
        // rejects a reused identity with different bytes.
        let mutation = candidateId == live.mutationId ? UUID().uuidString.lowercased() : candidateId
        if let old = live.mutationId, old != mutation { try? FileManager.default.removeItem(at: store.bodyURL(user, old)) }
        let rebuilt = try NativePrivateSyncProtocol.putBody(baseVersion: baseVersion, mutationId: mutation, deviceId: store.deviceId(), records: records, retained: retained, fresh: fresh, resolved: resolved, serverConflictIds: serverIds)
        try store.writeBody(user, mutation, rebuilt.data)
        live.mutationId = mutation; live.mutationSha = NativeRelayProtocol.hash(rebuilt.data); live.pending = true
        try? store.save(user, live)
        return try await commit(user: user, state: live, bytes: rebuilt.data, mutation: mutation, baseVersion: baseVersion, records: records, retained: retained, fresh: fresh, resolved: resolved, serverIds: serverIds, projectedKeys: projectedKeys)
    }

    private func commit(user: String, state: NativePrivateSyncState, bytes: Data, mutation: String, baseVersion: Int64, records: [NativeSyncRecord], retained: [NativeSyncConflict], fresh: [NativeSyncConflict], resolved: [String], serverIds: Set<String>, projectedKeys: Set<String>) async throws -> UploadResult {
        let receipt: Data
        do { receipt = try await authorized(user) { try await api.commit(body: bytes, mutationId: mutation, access: $0) } }
        catch is URLError { fail(user: user, state: state, outcome: "waiting", message: "Waiting for connection. The exact upload will retry unchanged."); return .failed }
        catch let http as NativePrivateSyncHttpError {
            if http.conflict { return .conflict } // Caller refetches, merges and retries with a fresh identity.
            if http.status == 422, http.code == "private_assets_unavailable" {
                // A referenced photo is missing server-side: re-verify every advertised asset.
                var reset = state; for key in reset.photos.keys { reset.photos[key]?.complete = false; reset.photos[key]?.received = [] }
                try? store.save(user, reset)
            }
            fail(user: user, state: state, outcome: "retry", message: "Private sync needs a retry."); return .failed
        }
        catch is NativeSyncAuthLost { bind(); publish(boundUser, busy: false); return .failed }
        guard let receiptObject = try? JSONDecoder().decode(JSONValue.self, from: receipt).objectValue,
              receiptObject["mutationId"]?.stringValue == mutation, receiptObject["sha256"]?.stringValue == NativeRelayProtocol.hash(bytes),
              receiptObject["version"]?.numberValue == Double(baseVersion + 1) else {
            fail(user: user, state: state, outcome: "retry", message: "The server receipt did not match this upload. Nothing was acknowledged."); return .failed
        }
        var merged: [String: NativeSyncConflict] = [:]
        for conflict in retained + fresh { merged[conflict.id] = conflict }
        let sentResolved = Set(resolved.filter { serverIds.contains($0) })
        for id in resolved { merged.removeValue(forKey: id) }
        let baseKeys = Set(records.map(\.key))
        let conflictKeys = Set(merged.values.flatMap { [$0.winner.key, $0.loser.key] })
        var next = state
        for key in Array(next.photos.keys) where !projectedKeys.contains(key) && !baseKeys.contains(key) && !conflictKeys.contains(key) {
            if next.photos[key]?.complete == false, let assetId = next.photos[key]?.assetId { try? await authorized(user) { try await api.abandonPhoto(assetId: assetId, access: $0) } }
            next.photos.removeValue(forKey: key)
        }
        next.serverVersion = baseVersion + 1
        next.base = records
        next.conflicts = merged.values.sorted { $0.id < $1.id }
        next.resolved = resolved.filter { !sentResolved.contains($0) }
        next.pending = false; next.mutationId = nil; next.mutationSha = nil
        next.lastSyncedAt = now(); next.outcome = "ok"; next.message = nil
        try? store.save(user, next)
        try? FileManager.default.removeItem(at: store.bodyURL(user, mutation))
        publish(user, busy: false)
        return .acked
    }

    private func fail(user: String, state: NativePrivateSyncState, outcome: String, message: String) {
        var failed = state; failed.outcome = outcome; failed.message = String(message.prefix(500)); failed.pending = true
        try? store.save(user, failed)
        publish(user, busy: false)
    }

    private func publish(_ user: String?, busy: Bool) {
        guard enabled, projection != nil, let user else { presentation = NativePrivateSyncPresentation(busy: busy); return }
        let state = (try? store.load(user)) ?? NativePrivateSyncState(account: user)
        let device = (try? store.deviceId()) ?? ""
        let conflicts = state.conflicts.map { NativePrivateSyncProtocol.conflictView($0, deviceId: device) }
        let status: String
        if !conflicts.isEmpty { status = "Conflicts to review" }
        else if state.outcome == "waiting" { status = "Waiting for connection" }
        else if state.outcome == "retry" { status = "Retry needed" }
        else if state.lastSyncedAt <= 0 { status = "Last synced never" }
        else {
            let formatter = DateFormatter(); formatter.dateStyle = .medium; formatter.timeStyle = .short
            status = "Last synced " + formatter.string(from: Date(timeIntervalSince1970: Double(state.lastSyncedAt) / 1000))
        }
        presentation = NativePrivateSyncPresentation(status: status, detail: state.message, lastSyncedAt: state.lastSyncedAt, conflicts: conflicts, busy: busy, signedIn: true)
    }

    private func liveProjected(_ records: [NativeSyncRecord]) -> [NativePrivateProjectedRecord] {
        records.filter { !$0.deleted }.compactMap { row in row.payload.map { NativePrivateProjectedRecord(recordType: row.recordType, recordId: row.recordId, payload: $0, assetIds: row.assetIds) } }
    }

    private func sameRecords(_ a: [NativeSyncRecord], _ b: [NativeSyncRecord]) -> Bool {
        guard a.count == b.count else { return false }
        let encode = { (rows: [NativeSyncRecord]) -> [Data] in
            rows.compactMap { row in try? { let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return try encoder.encode(row) }() }.sorted { $0.lexicographicallyPrecedes($1) }
        }
        return encode(a) == encode(b)
    }

    private func unionConflicts(_ retained: [NativeSyncConflict], _ fresh: [NativeSyncConflict], resolved: [String]) -> [NativeSyncConflict] {
        var merged: [String: NativeSyncConflict] = [:]
        for conflict in retained + fresh { merged[conflict.id] = conflict }
        for id in resolved { merged.removeValue(forKey: id) }
        return merged.values.sorted { $0.id < $1.id }
    }

    private func restampAll(_ projected: [NativePrivateProjectedRecord], device: String, counter: Int64) throws -> [NativeSyncRecord] {
        let rows = projected.map { NativeSyncRecord(recordType: $0.recordType, recordId: $0.recordId, version: NativeSyncStamp(counter: counter, clientId: device), deleted: false, payload: $0.payload, assetIds: $0.assetIds) }.sorted { $0.key < $1.key }
        try NativePrivateSyncMerge.validateRecords(rows)
        return rows
    }

    private func advancedAfterStamp(user: String, state: NativePrivateSyncState, stamped: [NativeSyncRecord]) -> NativePrivateSyncState {
        let observed = stamped.reduce(Int64(0)) { max($0, $1.version.counter) }
        guard observed >= state.maxCounter else { return state }
        var advanced = state; advanced.maxCounter = observed + 1
        try? store.save(user, advanced)
        return advanced
    }

    private func archiveRow(_ archive: JSONValue, type: String, id: String) -> JSONValue? {
        let key = NativePrivateSyncProtocol.keyField(type)
        return archive.objectValue?[type]?.arrayValue?.first { $0.objectValue?[key]?.stringValue == id }
    }

    private func validatedArchive(_ archive: JSONValue, upserts: [NativeSyncUpsert], deletes: [NativeSyncDelete]) -> JSONValue {
        var root = archive.objectValue ?? [:]
        for delete in deletes {
            let key = NativePrivateSyncProtocol.keyField(delete.type)
            root[delete.type] = .array((root[delete.type]?.arrayValue ?? []).filter { $0.objectValue?[key]?.stringValue != delete.id })
        }
        for upsert in upserts {
            let key = NativePrivateSyncProtocol.keyField(upsert.type)
            var rows = root[upsert.type]?.arrayValue ?? []
            if let index = rows.firstIndex(where: { $0.objectValue?[key]?.stringValue == upsert.id }) {
                var merged = rows[index].objectValue ?? [:]
                for (field, content) in upsert.payload { merged[field] = content }
                rows[index] = .object(merged)
            } else { rows.append(.object(upsert.payload)) }
            root[upsert.type] = .array(rows)
        }
        return .object(root)
    }
}
