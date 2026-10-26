import XCTest
@testable import PitTech

@MainActor final class PrivateSyncCoordinatorTests: XCTestCase {
    func testAccountScreenRefreshKeepsInFlightSyncAndJournalRemovalGuarded() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); seedLibrary(store)
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        let commit = api.commitHandler
        defer { api.commitHandler = commit }
        api.commitHandler = { bytes, mutation in
            sync.refresh()
            XCTAssertTrue(sync.presentation.busy)
            XCTAssertThrowsError(try sync.detachLibrary()) { error in
                guard let removal = error as? LocalJournalRemovalError, case .syncing = removal else { return XCTFail("Journal removal must wait for sync") }
            }
            XCTAssertEqual(try journal.libraryAccount(), self.userA)
            return try commit(bytes, mutation)
        }
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        XCTAssertFalse(sync.presentation.busy)
        XCTAssertEqual(try journal.load(userA).serverVersion, 1)
    }

    func testAutomaticSyncRequiresExplicitAttachmentOptInAndCurrentLibraryAccount() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); seedLibrary(store)
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, session) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow(automatic: true); sync.setAutomatic(true)
        XCTAssertEqual(api.puts.count, 0); XCTAssertNotEqual(try journal.load(userA).automatic, true)
        await sync.syncNow(); XCTAssertEqual(api.puts.count, 1)
        await sync.syncNow(automatic: true); XCTAssertEqual(api.puts.count, 1)
        sync.setAutomatic(true); XCTAssertEqual(try journal.load(userA).automatic, true)
        await sync.syncNow(automatic: true); XCTAssertGreaterThan(api.puts.count, 1)
        let requests = api.puts.count
        var other = NativePrivateSyncState(account: userB); other.lastSyncedAt = 1000; other.automatic = true; try journal.save(userB, other)
        session.user = userB; await sync.syncNow(automatic: true)
        XCTAssertEqual(api.puts.count, requests); XCTAssertEqual(try journal.libraryAccount(), userA)
        try sync.detachLibrary(); XCTAssertNil(try journal.libraryAccount()); XCTAssertEqual(api.puts.count, requests)
    }

    func testRemoteFinishAndDeletionReconcileLocalTimers() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); seedLibrary(store)
        try store.startActivityTimer(cookId: "cook-1", dishId: "dish-1", title: "Rest", method: nil, target: nil)
        let timer = try XCTUnwrap(store.library.activityTimers.first?.objectValue?["id"]?.stringValue)
        var cook = try XCTUnwrap(try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library.cooks[0])).objectValue)
        cook["status"] = .string("completed"); cook["endedAtUtcMillis"] = .number(2000)
        let adapter = NativeCookStoreSyncLibrary(store)
        try adapter.apply(NativeSyncApplyPlan(upserts: [NativeSyncUpsert(type: "cooks", id: "cook-1", payload: cook)], deletes: [], attachments: [:]))
        XCTAssertEqual(store.library.activityTimers.first?.objectValue?["status"]?.stringValue, "cancelled")
        XCTAssertEqual(store.library.activityTimers.first?.objectValue?["id"]?.stringValue, timer)
        try adapter.apply(NativeSyncApplyPlan(upserts: [], deletes: [NativeSyncDelete(type: "dishes", id: "dish-1"), NativeSyncDelete(type: "cooks", id: "cook-1")], attachments: [:]))
        XCTAssertTrue(store.library.cooks.isEmpty); XCTAssertTrue(store.library.activityTimers.isEmpty)
    }
    private let userA = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private let userB = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private let deviceB = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    @MainActor private final class FakeSession: NativePublicationSession {
        var user: String?
        var publicationUserId: String? { user }
        func access(forceRefresh: Bool) async throws -> String {
            guard let user else { throw NativeAccountError.status(401) }
            return "token-" + user
        }
    }

    private final class FakeAPI: NativePrivateSyncAPI {
        var puts: [(Data, String)] = []
        var snapshot = JSONValue.object(["version": .number(0), "envelope": .null])
        var commitHandler: (Data, String) throws -> JSONValue = { bytes, mutation in
            let base = (try? JSONDecoder().decode(JSONValue.self, from: bytes))?.objectValue?["baseVersion"]?.numberValue ?? 0
            return .object(["version": .number(base + 1), "mutationId": .string(mutation), "sha256": .string(NativeRelayProtocol.hash(bytes))])
        }
        var failParts = Set<Int>()
        var photoState: [String: JSONValue] = [:]
        var downloads: [String: Data] = [:]
        var queued: [JSONValue] = []
        var calls = 0
        func snapshot(access: String) async throws -> Data {
            calls += 1
            let current = snapshot
            if !queued.isEmpty { snapshot = queued.removeFirst() }
            return try JSONEncoder().encode(current)
        }
        func commit(body: Data, mutationId: String, access: String) async throws -> Data {
            calls += 1; puts.append((body, mutationId)); return try JSONEncoder().encode(try commitHandler(body, mutationId))
        }
        func beginPhoto(assetId: String, bytes: Int, mime: String, sha: String, access: String) async throws -> Data {
            calls += 1
            let status = JSONValue.object(["assetId": .string(assetId), "bytes": .number(Double(bytes)), "receivedParts": .array([]), "complete": .bool(false)])
            photoState[assetId] = status; return try JSONEncoder().encode(status)
        }
        func photoStatus(assetId: String, access: String) async throws -> Data {
            calls += 1; guard let status = photoState[assetId] else { throw NativePrivateSyncHttpError(status: 404, code: "photo_not_found") }
            return try JSONEncoder().encode(status)
        }
        func putPart(assetId: String, index: Int, chunk: Data, access: String) async throws -> Data {
            calls += 1
            if failParts.contains(index) { throw URLError(.networkConnectionLost) }
            guard var o = photoState[assetId]?.objectValue else { throw NativePrivateSyncHttpError(status: 404, code: "photo_not_found") }
            o["receivedParts"] = .array((o["receivedParts"]?.arrayValue ?? []) + [.number(Double(index))])
            photoState[assetId] = .object(o); return try JSONEncoder().encode(photoState[assetId]!)
        }
        func completePhoto(assetId: String, access: String) async throws -> Data {
            calls += 1
            guard var o = photoState[assetId]?.objectValue else { throw NativePrivateSyncHttpError(status: 404, code: "photo_not_found") }
            o["complete"] = .bool(true); photoState[assetId] = .object(o); return try JSONEncoder().encode(photoState[assetId]!)
        }
        func abandonPhoto(assetId: String, access: String) async throws -> Data { calls += 1; photoState.removeValue(forKey: assetId); return try JSONEncoder().encode(JSONValue.object(["abandoned": .bool(true)])) }
        func downloadPhoto(assetId: String, to: URL, access: String) async throws {
            calls += 1; guard let bytes = downloads[assetId] else { throw NativePrivateSyncHttpError(status: 404, code: "photo_not_found") }
            try bytes.write(to: to, options: .atomic)
        }
    }

    private func note(_ title: String, _ id: String = "note-1") -> [String: JSONValue] {
        ["id": .string(id), "cookId": .string("cook-1"), "eventType": .string("note"), "title": .string(title),
         "occurredAtUtcMillis": .number(1000), "recordedAtUtcMillis": .number(1000), "timeZoneId": .string("UTC"), "source": .string("manual"),
         "createdAtUtcMillis": .number(1000), "updatedAtUtcMillis": .number(1000)]
    }
    private func versioned(_ type: String, _ id: String, _ counter: Int64, _ client: String, _ payload: [String: JSONValue]?, _ assets: [String] = [], deleted: Bool = false) -> NativeSyncRecord {
        NativeSyncRecord(recordType: type, recordId: id, version: NativeSyncStamp(counter: counter, clientId: client), deleted: deleted, payload: payload, assetIds: assets)
    }
    private func seedLibrary(_ store: CookStore, events: [[String: JSONValue]] = []) {
        var library = PitTechLibrary()
        library.cooks = [CookRecord(id: "cook-1", title: "Weeknight cook", status: "active", startedAtUtcMillis: 1000, startedTimeZoneId: "UTC", endedAtUtcMillis: nil, smokerName: nil, initialSetpointValue: nil, initialSetpointUnit: nil, notes: nil, createdAtUtcMillis: 1000, updatedAtUtcMillis: 1000, fuelType: nil, woodOrPelletBlend: nil, outdoorTemperatureValue: nil, outdoorTemperatureUnit: nil, weatherNotes: nil, windNotes: nil)]
        library.dishes = [.object(["id": .string("dish-1"), "cookId": .string("cook-1"), "name": .string("Brisket"), "foodType": .string("beef"), "createdAtUtcMillis": .number(1000), "updatedAtUtcMillis": .number(1000)])]
        library.events = events.map(JSONValue.object)
        store.library = library; store.save()
    }
    private func projection() throws -> NativePrivateSyncProjection {
        let bundle = Bundle(for: Self.self)
        return try NativePrivateSyncProjection(data: Data(contentsOf: XCTUnwrap(bundle.url(forResource: "private-sync-projection-v1", withExtension: "json"))))
    }
    private func coordinator(store: NativePrivateSyncMemoryStore = NativePrivateSyncMemoryStore(), session: FakeSession? = nil, api: FakeAPI = FakeAPI(), library: NativeSyncLibrary) throws -> (NativePrivateSyncCoordinator, FakeSession) {
        let session = session ?? { let s = FakeSession(); s.user = userA; return s }()
        let sync = NativePrivateSyncCoordinator(store: store, session: session, api: api, library: library, projection: try projection(), enabled: true, now: { 1_700_000_000_000 })
        return (sync, session)
    }
    private func eventRow(_ store: CookStore, _ id: String) -> [String: JSONValue]? {
        store.library.events.first { $0.objectValue?["id"]?.stringValue == id }?.objectValue
    }

    func testFreshSyncUploadsProjectionOnlyAndAdvancesBase() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        let body = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: api.puts[0].0).objectValue)
        XCTAssertEqual(body["baseVersion"], .number(0))
        // Snapshot build is validator output only: every uploaded row projects from the journal.
        let expected = try projection().project(store.library, assets: [:])
        let records = try XCTUnwrap(body["records"]?.arrayValue)
        XCTAssertEqual(records.count, expected.count)
        for row in expected {
            let uploaded = try XCTUnwrap(records.first { $0.objectValue?["recordType"]?.stringValue == row.recordType && $0.objectValue?["recordId"]?.stringValue == row.recordId }?.objectValue)
            XCTAssertEqual(uploaded["payload"], .object(row.payload))
            XCTAssertEqual(uploaded["version"]?.objectValue?["counter"], .number(1))
        }
        let state = try journal.load(userA)
        XCTAssertEqual(state.serverVersion, 1); XCTAssertFalse(state.pending); XCTAssertNil(state.mutationId)
        XCTAssertEqual(state.lastSyncedAt, 1_700_000_000_000); XCTAssertEqual(state.outcome, "ok")
        XCTAssertTrue(sync.presentation.status.hasPrefix("Last synced "))
        XCTAssertTrue(sync.presentation.signedIn)
    }

    func testRejectedProjectionBlocksUploadWithoutTouchingLocalData() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        var library = store.library
        library.companionRecords = [.object(["id": .string("doc-1"), "kind": .string("future_kind"), "title": .string("From tomorrow"), "payload": .object([:]), "created": .number(1000), "updated": .number(1000)])]
        store.library = library; store.save()
        let before = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library))
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.calls, 0)
        XCTAssertEqual(before, try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library)))
        XCTAssertEqual(sync.presentation.status, "Retry needed")
        XCTAssertEqual(try journal.load(userA).serverVersion, 0)
    }

    func testConflict409MergeRetryPreservesBothDivergentEdits() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        let base = try journal.load(userA).base
        // Local edit plus a remote edit at a higher counter: the remote wins, the local edit is retained.
        var library = store.library
        library.events = [.object(note("Mine"))]; store.library = library; store.save()
        var remote = base
        remote[remote.firstIndex(where: { $0.recordId == "note-1" })!] = versioned("events", "note-1", 3, deviceB, note("Theirs"))
        func envelope(_ version: Double, _ rows: [NativeSyncRecord]) throws -> JSONValue {
            .object(["version": .number(version), "envelope": .object(["contractVersion": .number(1),
                "records": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(rows))), "conflicts": .array([])])])
        }
        api.snapshot = try envelope(2, remote)
        // The 409 refetch sees a racing device's unrelated note; the retry adopts it too.
        var racer = remote
        racer.append(versioned("events", "note-2", 4, deviceB, note("Racer", "note-2")))
        api.queued = [try envelope(3, racer)]
        var commits = 0
        api.commitHandler = { bytes, mutation in
            commits += 1
            if commits == 1 { throw NativePrivateSyncHttpError(status: 409, code: "sync_version_changed") }
            let baseVersion = (try? JSONDecoder().decode(JSONValue.self, from: bytes))?.objectValue?["baseVersion"]?.numberValue ?? 0
            return .object(["version": .number(baseVersion + 1), "mutationId": .string(mutation), "sha256": .string(NativeRelayProtocol.hash(bytes))])
        }
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 3)
        XCTAssertNotEqual(api.puts[1].1, api.puts[2].1)
        let retry = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: api.puts[2].0).objectValue)
        XCTAssertEqual(retry["baseVersion"], .number(3))
        let records = try XCTUnwrap(retry["records"]?.arrayValue)
        // Both divergent edits survive: the winner in records, the loser as a retained copy.
        XCTAssertEqual(records.first { $0.objectValue?["recordId"]?.stringValue == "note-1" }?.objectValue?["payload"]?.objectValue?["title"], .string("Theirs"))
        XCTAssertEqual(records.first { $0.objectValue?["recordId"]?.stringValue == "note-1" }?.objectValue?["version"]?.objectValue?["clientId"], .string(deviceB))
        XCTAssertTrue(records.contains { $0.objectValue?["recordId"]?.stringValue == "note-2" })
        XCTAssertEqual(retry["conflicts"]?.arrayValue?.count, 1)
        let conflict = try XCTUnwrap(retry["conflicts"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(conflict["reason"], .string("edit_edit"))
        XCTAssertEqual(conflict["loser"]?.objectValue?["payload"]?.objectValue?["title"], .string("Mine"))
        XCTAssertEqual(eventRow(store, "note-1")?["title"], .string("Theirs"))
        XCTAssertNotNil(eventRow(store, "note-2"))
        XCTAssertEqual(sync.presentation.status, "Conflicts to review")
        XCTAssertEqual(sync.presentation.conflicts.count, 1)
        // Keep mine replays the losing edit under the same identity; the next upload resolves the copy.
        let conflictId = try XCTUnwrap(conflict["id"]?.stringValue)
        await sync.resolve(conflictId, choice: "mine")
        XCTAssertEqual(eventRow(store, "note-1")?["title"], .string("Mine"))
        api.queued = []
        api.snapshot = .object(["version": .number(4), "envelope": .object(["contractVersion": .number(1),
            "records": try XCTUnwrap(retry["records"]), "conflicts": try XCTUnwrap(retry["conflicts"])])])
        await sync.syncNow()
        let resolved = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: (try XCTUnwrap(api.puts.last)).0).objectValue)
        XCTAssertEqual(resolved["conflicts"]?.arrayValue?.count, 0)
        XCTAssertEqual(resolved["resolvedConflictIds"]?.arrayValue, [.string(conflictId)])
        XCTAssertTrue(sync.presentation.status.hasPrefix("Last synced "))
    }

    func testInterruptedPhotoUploadResumesFromDurablePartState() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        var library = store.library
        library.photos = [.object(["id": .string("photo-1"), "cookId": .string("cook-1"), "originalFileName": .string("photo-1.jpg"), "mimeType": .string("image/jpeg"), "archivePath": .string("attachments/photo-1.jpg"), "addedAtUtcMillis": .number(1000)])]
        store.library = library; store.save()
        let bytes = Data((0..<(2 * 1024 * 1024 + 512)).map { UInt8($0 & 0xff) })
        try FileManager.default.createDirectory(at: folder.appendingPathComponent("attachments", isDirectory: true), withIntermediateDirectories: true)
        try bytes.write(to: folder.appendingPathComponent("attachments/photo-1.jpg"), options: .atomic)
        let api = FakeAPI(); api.failParts = [1]
        let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 0)
        let partial = try XCTUnwrap(journal.load(userA).photos["photos|photo-1"])
        XCTAssertFalse(partial.complete); XCTAssertEqual(partial.received, [0])
        // A restart (new coordinator, same durable store) resumes with only the missing parts.
        api.failParts = []
        let (resumed, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await resumed.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        let entry = try XCTUnwrap(journal.load(userA).photos["photos|photo-1"])
        XCTAssertTrue(entry.complete); XCTAssertEqual(entry.received, [0, 1, 2])
        XCTAssertEqual(api.photoState.count, 1)
        XCTAssertEqual(api.photoState[entry.assetId]?.objectValue?["complete"], .bool(true))
    }

    func testAccountSwitchCancelsOutboxAndNeverAttachesToAnotherAccount() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let session = FakeSession(); session.user = userA
        let (sync, _) = try coordinator(store: journal, session: session, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        // An ambiguous outcome retains the exact retry bytes for the same account.
        api.commitHandler = { _, _ in throw URLError(.networkConnectionLost) }
        var library = store.library
        library.events = [.object(note("Edited offline"))]; store.library = library; store.save()
        await sync.syncNow()
        let pending = try journal.load(userA)
        XCTAssertNotNil(pending.mutationId); XCTAssertTrue(pending.pending); XCTAssertEqual(sync.presentation.status, "Waiting for connection")
        session.user = userB
        let callsBefore = api.calls
        sync.refresh()
        // Nothing syncs silently on switch; the new account starts clean.
        XCTAssertEqual(api.calls, callsBefore)
        let cancelled = try journal.load(userA)
        XCTAssertNil(cancelled.mutationId); XCTAssertNil(cancelled.mutationSha)
        XCTAssertEqual(cancelled.base.count, 3)
        api.commitHandler = { bytes, mutation in .object(["version": .number(1), "mutationId": .string(mutation), "sha256": .string(NativeRelayProtocol.hash(bytes))]) }
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 3)
        let other = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: api.puts[2].0).objectValue)
        // The new account starts its own backup from scratch on explicit request: fresh
        // base version, no resolutions, none of the prior account's journal attached.
        XCTAssertEqual(other["baseVersion"], .number(0))
        XCTAssertNil(other["resolvedConflictIds"])
        XCTAssertEqual(other["conflicts"]?.arrayValue?.count, 0)
        XCTAssertEqual(try journal.load(userB).serverVersion, 1)
        XCTAssertEqual(try journal.load(userA).serverVersion, 1)
        XCTAssertEqual(try journal.load(userA).base.count, 3)
    }

    func testTombstoneResolutionRestoresLosingEditAsNewIdentity() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        let base = try journal.load(userA).base
        var library = store.library
        library.events = [.object(note("Edited offline"))]; store.library = library; store.save()
        var remote = base
        remote[remote.firstIndex { $0.recordId == "note-1" }!] = versioned("events", "note-1", 2, deviceB, nil, deleted: true)
        api.snapshot = .object(["version": .number(2), "envelope": .object(["contractVersion": .number(1),
            "records": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(remote))), "conflicts": .array([])])])
        await sync.syncNow()
        XCTAssertNil(eventRow(store, "note-1"))
        XCTAssertEqual(sync.presentation.status, "Conflicts to review")
        let conflict = try XCTUnwrap(journal.load(userA).conflicts.first)
        XCTAssertEqual(conflict.reason, "delete_edit")
        await sync.resolve(conflict.id, choice: "both")
        XCTAssertEqual(store.library.events.count, 1)
        let restored = try XCTUnwrap(store.library.events.first?.objectValue)
        XCTAssertNotEqual(restored["id"]?.stringValue, "note-1")
        XCTAssertEqual(restored["title"], .string("Edited offline"))
        let committed = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: (try XCTUnwrap(api.puts.last)).0).objectValue)
        api.snapshot = .object(["version": .number(3), "envelope": .object(["contractVersion": .number(1),
            "records": try XCTUnwrap(committed["records"]), "conflicts": try XCTUnwrap(committed["conflicts"])])])
        await sync.syncNow()
        let body = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: (try XCTUnwrap(api.puts.last)).0).objectValue)
        XCTAssertEqual(body["resolvedConflictIds"]?.arrayValue, [.string(conflict.id)])
        let records = try XCTUnwrap(body["records"]?.arrayValue)
        XCTAssertEqual(records.first { $0.objectValue?["recordId"]?.stringValue == "note-1" }?.objectValue?["deleted"], .bool(true))
        XCTAssertEqual(records.filter { $0.objectValue?["recordType"]?.stringValue == "events" && $0.objectValue?["deleted"] != .bool(true) }.count, 1)
    }

    func testImmutableRewriteBlocksCommitWhilePreservingBothVersions() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store)
        var library = store.library
        library.readings = [.object(["id": .string("reading-1"), "cookId": .string("cook-1"), "probeName": .string("Pit"), "measurementType": .string("temperature"), "value": .number(74), "unit": .string("°C"), "measuredAtUtcMillis": .number(1000), "timeZoneId": .string("UTC"), "source": .string("controller_cloud"), "qualityStatus": .string("valid"), "recordedAtUtcMillis": .number(1000), "timestampBasis": .string("measurement"), "samplingIntervalMillis": .number(15000)])]
        store.library = library; store.save()
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        var edited = store.library
        edited.readings = [.object((edited.readings.first?.objectValue ?? [:]).merging(["value": .number(75)]) { _, new in new })]
        store.library = edited; store.save()
        api.snapshot = .object(["version": .number(1), "envelope": .object(["contractVersion": .number(1),
            "records": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(journal.load(userA).base))), "conflicts": .array([])])])
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        XCTAssertEqual(sync.presentation.status, "Conflicts to review")
        // The local rewrite stays in the journal; the acknowledged base still holds the original.
        XCTAssertEqual(store.library.readings.first?.objectValue?["value"], .number(75))
        XCTAssertEqual(try journal.load(userA).base.first { $0.recordType == "readings" }?.payload?["value"], .number(74))
    }

    func testAmbiguousUploadRetriesExactBytesAndNeverClaimsUnacknowledgedSuccess() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        api.commitHandler = { _, _ in throw URLError(.networkConnectionLost) }
        await sync.syncNow()
        XCTAssertEqual(sync.presentation.status, "Waiting for connection")
        XCTAssertEqual(try journal.load(userA).lastSyncedAt, 0)
        XCTAssertEqual(try journal.load(userA).serverVersion, 0)
        let first = try XCTUnwrap(api.puts.first)
        api.commitHandler = { bytes, mutation in .object(["version": .number(1), "mutationId": .string(mutation), "sha256": .string(NativeRelayProtocol.hash(bytes))]) }
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 2)
        XCTAssertEqual(first.1, api.puts[1].1)
        XCTAssertEqual(first.0, api.puts[1].0)
        XCTAssertTrue(sync.presentation.status.hasPrefix("Last synced "))
    }

    func testMismatchedReceiptNeverAdvancesBase() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        api.commitHandler = { bytes, _ in .object(["version": .number(1), "mutationId": .string("ffffffff-ffff-4fff-8fff-ffffffffffff"), "sha256": .string(NativeRelayProtocol.hash(bytes))]) }
        await sync.syncNow()
        XCTAssertEqual(sync.presentation.status, "Retry needed")
        XCTAssertEqual(try journal.load(userA).serverVersion, 0)
        XCTAssertTrue(try journal.load(userA).base.isEmpty)
        XCTAssertNotNil(try journal.load(userA).mutationId)
    }

    func testConcurrentWriteOnAnAffectedRowAbortsSoTheNextPassMergesIt() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        let base = try journal.load(userA).base
        var remote = base
        remote[remote.firstIndex { $0.recordId == "note-1" }!] = versioned("events", "note-1", 2, deviceB, note("Remote"))
        api.snapshot = .object(["version": .number(2), "envelope": .object(["contractVersion": .number(1),
            "records": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(remote))), "conflicts": .array([])])])
        @MainActor final class Racing: NativeSyncLibrary {
            let inner: NativeCookStoreSyncLibrary
            let store: CookStore
            var reads = 0
            init(_ inner: NativeCookStoreSyncLibrary, _ store: CookStore) { self.inner = inner; self.store = store }
            func archive() throws -> JSONValue {
                reads += 1
                if reads == 2 {
                    var library = store.library
                    library.events = [.object((library.events.first?.objectValue ?? [:]).merging(["title": .string("Raced ahead")]) { _, new in new })]
                    store.library = library
                }
                return try inner.archive()
            }
            func photoData(type: String, id: String) throws -> (Data, String)? { try inner.photoData(type: type, id: id) }
            func apply(_ plan: NativeSyncApplyPlan) throws { try inner.apply(plan) }
        }
        let racing = Racing(NativeCookStoreSyncLibrary(store), store)
        let (racer, _) = try coordinator(store: journal, api: api, library: racing)
        await racer.syncNow()
        // The raced row is left for the next pass; nothing uploads and nothing applies.
        XCTAssertEqual(api.puts.count, 1)
        XCTAssertEqual(racer.presentation.status, "Retry needed")
        XCTAssertEqual(eventRow(store, "note-1")?["title"], .string("Raced ahead"))
        await racer.syncNow()
        XCTAssertEqual(api.puts.count, 2)
        let retry = try XCTUnwrap(JSONDecoder().decode(JSONValue.self, from: api.puts[1].0).objectValue)
        XCTAssertEqual(retry["conflicts"]?.arrayValue?.count, 1)
        XCTAssertEqual(retry["conflicts"]?.arrayValue?.first?.objectValue?["winner"]?.objectValue?["payload"]?.objectValue?["title"], .string("Raced ahead"))
        XCTAssertEqual(retry["conflicts"]?.arrayValue?.first?.objectValue?["loser"]?.objectValue?["payload"]?.objectValue?["title"], .string("Remote"))
    }

    func testFailedApplyLeavesLibraryByteIdentical() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: folder.path); try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Original")])
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        let base = try journal.load(userA).base
        var remote = base
        remote[remote.firstIndex { $0.recordId == "note-1" }!] = versioned("events", "note-1", 2, deviceB, note("Remote"))
        api.snapshot = .object(["version": .number(2), "envelope": .object(["contractVersion": .number(1),
            "records": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(remote))), "conflicts": .array([])])])
        let before = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library))
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: folder.path)
        await sync.syncNow()
        XCTAssertEqual(sync.presentation.status, "Retry needed")
        XCTAssertEqual(before, try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library)))
        XCTAssertEqual(eventRow(store, "note-1")?["title"], .string("Original"))
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: folder.path)
        let oldPhoto = store.photoURL("attachments/rollback-existing.jpg")
        try FileManager.default.createDirectory(at: oldPhoto.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("original-photo".utf8).write(to: oldPhoto)
        store.library.photos.append(.object(["id": .string("rollback-existing"), "cookId": .string("cook-1"), "archivePath": .string("attachments/rollback-existing.jpg"), "mimeType": .string("image/jpeg"), "addedAtUtcMillis": .number(1000)]))
        store.save()
        let beforePhotos = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library))
        let adapter = NativeCookStoreSyncLibrary(store)
        XCTAssertThrowsError(try adapter.apply(NativeSyncApplyPlan(upserts: [
            NativeSyncUpsert(type: "photos", id: "rollback-existing", payload: ["id": .string("rollback-existing"), "cookId": .string("cook-1"), "mimeType": .string("image/jpeg"), "addedAtUtcMillis": .number(2000)]),
            NativeSyncUpsert(type: "unsupported", id: "invalid", payload: ["id": .string("invalid")])
        ], deletes: [], attachments: ["rollback-existing": Data("replacement-photo".utf8)])))
        XCTAssertEqual(try Data(contentsOf: oldPhoto), Data("original-photo".utf8))
        XCTAssertEqual(beforePhotos, try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(store.library)))
    }

    func testImportedRulesArriveInactiveAndMonitoringIsPreserved() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        seedLibrary(store, events: [note("Taste check")])
        let config: [String: JSONValue] = ["version": .number(1), "type": .string("historical"), "anchorAction": .string("food_on"), "offsetMillis": .number(12000000)]
        try store.saveReminderRule(cookId: "cook-1", dishId: "dish-1", title: "Stir", config: config, id: "rule-local")
        var library = store.library
        library.reminderOccurrences.append(.object(["id": .string("receipt-1"), "ruleId": .string("rule-local"), "sequence": .number(1), "status": .string("delivered"), "active": .bool(false)]))
        library.recordings = [.object(["cookId": .string("cook-1"), "deviceId": .string("device-1"), "status": .string("recording")])]
        library.probeAssignments = [.object(["id": .string("assignment-1"), "cookId": .string("cook-1"), "probeId": .string("probe-1")])]
        library.activityTimers = [.object(["id": .string("timer-1"), "noteId": .string("timer-note-1"), "cookId": .string("cook-1"), "title": .string("Rest"), "startedAtUtcMillis": .number(1000), "status": .string("running")])]
        store.library = library; store.save()
        let trigger = try XCTUnwrap(store.reminderRule("rule-local")?["triggerConfig"]?.stringValue)
        // The local rule's status is whatever the scheduler left it ("waiting" when no
        // due date is computable — the long-standing, cross-platform behavior the
        // reminders UI displays). Sync must preserve it exactly, not rewrite it.
        let preSyncStatus = store.reminderRule("rule-local")?["status"]
        let api = FakeAPI(); let journal = NativePrivateSyncMemoryStore()
        let (sync, _) = try coordinator(store: journal, api: api, library: NativeCookStoreSyncLibrary(store))
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 1)
        let base = try journal.load(userA).base
        let rulePayload: [String: JSONValue] = ["id": .string("rule-remote"), "cookId": .string("cook-1"), "title": .string("Remote rule"), "kind": .string("historical"), "triggerConfig": .string(trigger), "createdAtUtcMillis": .number(2000), "updatedAtUtcMillis": .number(2000), "schemaVersion": .number(1)]
        var remote = base
        remote.append(versioned("reminderRules", "rule-remote", 2, deviceB, rulePayload))
        api.snapshot = .object(["version": .number(2), "envelope": .object(["contractVersion": .number(1),
            "records": .array(try JSONDecoder().decode([JSONValue].self, from: JSONEncoder().encode(remote))), "conflicts": .array([])])])
        await sync.syncNow()
        XCTAssertEqual(api.puts.count, 2)
        // Imported definitions arrive inactive with no delivery state. Nothing arms, records or schedules.
        let imported = try XCTUnwrap(store.reminderRule("rule-remote"))
        XCTAssertEqual(imported["enabled"], .bool(false)); XCTAssertEqual(imported["status"], .string("inactive"))
        XCTAssertTrue(store.library.reminderOccurrences.filter { $0.objectValue?["ruleId"]?.stringValue == "rule-remote" }.isEmpty)
        // Existing local monitoring and recording state is preserved exactly.
        let local = try XCTUnwrap(store.reminderRule("rule-local"))
        XCTAssertEqual(local["enabled"], .bool(true)); XCTAssertEqual(local["status"], preSyncStatus)
        XCTAssertEqual(store.library.reminderOccurrences.filter { $0.objectValue?["ruleId"]?.stringValue == "rule-local" }.count, 2)
        XCTAssertEqual(store.library.recordings.count, 1)
        XCTAssertEqual(store.library.probeAssignments.count, 1)
        XCTAssertEqual(store.library.activityTimers.count, 1)
    }

    // Slice 7 (PL-10 / PL-13, DD-8): the sync status row and the canonical
    // empty states. These literals are the cross-platform contract — the
    // Android StateCopyTest pins the same strings.
    func testSyncStatusRowCopyPerState() {
        let never = SyncStatusCopy.row(status: "Last synced never", detail: nil, lastSyncedAt: 0, conflictCount: 0, signedIn: true, enabled: true)
        XCTAssertEqual(never.headline, "Last synced never")
        XCTAssertEqual(never.body, "Waiting for first connection — nothing has been backed up yet. Your journal is safe on this device. Tap Sync now to make the first backup.")

        let signedOut = SyncStatusCopy.row(status: "Sync is off", detail: nil, lastSyncedAt: 0, conflictCount: 0, signedIn: false, enabled: true)
        XCTAssertEqual(signedOut.headline, "Sign in to start private sync")
        XCTAssertTrue(signedOut.body.hasPrefix("Nothing is uploaded while you are signed out"))

        let disabled = SyncStatusCopy.row(status: "Sync is off", detail: nil, lastSyncedAt: 0, conflictCount: 0, signedIn: false, enabled: false)
        XCTAssertEqual(disabled.body, "Private sync is not available on this build. Your journal stays on this device and nothing is uploaded.")

        let conflict = SyncStatusCopy.row(status: "Conflicts to review", detail: nil, lastSyncedAt: 123, conflictCount: 3, signedIn: true, enabled: true)
        XCTAssertTrue(conflict.body.contains("Two devices changed the same 3 items."))
        XCTAssertEqual(SyncStatusCopy.conflictConsequence(1), "Choosing “Use theirs” for it replaces this device's unsynced changes to 1 item. Each choice is recorded on this device now and uploads on the next sync.")
        XCTAssertEqual(SyncStatusCopy.conflictConsequence(4), "Choosing “Use theirs” for all 4 replaces this device's unsynced changes to 4 items. Each choice is recorded on this device now and uploads on the next sync.")
        XCTAssertEqual(SyncStatusCopy.conflictItemNote, "Keep mine keeps this device's version of this item. Use theirs replaces it with the other device's version.")

        // PL-16: each choice prints its own consequence before it is tapped —
        // it applies on this device right away, and the resolution uploads
        // on the next sync; where Keep both cannot exist, the sheet says so
        // for that item instead of the button silently not being there.
        XCTAssertEqual(SyncStatusCopy.conflictChoiceNote("mine"), "Keep mine applies on this device right away — your version stays. The choice uploads on the next sync.")
        XCTAssertEqual(SyncStatusCopy.conflictChoiceNote("theirs"), "Use theirs applies on this device right away — the other device's version replaces yours. The choice uploads on the next sync.")
        XCTAssertEqual(SyncStatusCopy.conflictChoiceNote("both"), "Keep both applies on this device right away — both versions stay, the other as a new entry. The choice uploads on the next sync.")
        XCTAssertEqual(SyncStatusCopy.conflictKeepBothUnavailable("lifecycles"), "Keep both isn't available for meal timing — a meal has one timing record, so choose one version.")
        XCTAssertEqual(SyncStatusCopy.conflictKeepBothUnavailable("logReadingLinks"), "Keep both isn't available for a note temperature link — a reading links to one note, so choose one version.")
        XCTAssertEqual(SyncStatusCopy.conflictKeepBothUnavailable("events"), "Keep both isn't available for this entry, so choose one version.")

        let waiting = SyncStatusCopy.row(status: "Waiting for connection", detail: nil, lastSyncedAt: 0, conflictCount: 0, signedIn: true, enabled: true)
        XCTAssertTrue(waiting.body.contains("Sync tries again when a connection returns"))
        let retry = SyncStatusCopy.row(status: "Retry needed", detail: nil, lastSyncedAt: 99, conflictCount: 0, signedIn: true, enabled: true)
        XCTAssertEqual(retry.body, "Your journal is unchanged on this device. Tap Sync now to retry.")
        let synced = SyncStatusCopy.row(status: "Last synced Oct 24, 2026", detail: nil, lastSyncedAt: 99, conflictCount: 0, signedIn: true, enabled: true)
        XCTAssertEqual(synced.body, "Changes you make now stay on this device until the next sync uploads them.")
    }

    func testEmptyStateCopyParityLiterals() {
        XCTAssertEqual(EmptyStateCopy.timelineTitle, "No cook history yet")
        XCTAssertEqual(EmptyStateCopy.timelineBody, "Notes, temperature checks and photos you add during the cook will show up here.")
        XCTAssertEqual(EmptyStateCopy.photosEmptyBody, "Photos you add to this cook will show up here. Add one with the Photo button on the cook.")
        XCTAssertEqual(EmptyStateCopy.connectionsEmpty, "No connections yet. Add one below, then attach it to a cook on the next step.")
        XCTAssertEqual(EmptyStateCopy.sharedCodesEmpty, "No shared codes yet. Open a saved recipe or meal, choose Share, then Share by code.")
        XCTAssertEqual(EmptyStateCopy.blocksBody, "Publishers you block from a publication or profile will be listed here, and their publications stay hidden.")
        XCTAssertEqual(EmptyStateCopy.reportsBody, "Receipts for reports you send about a publication or profile will show up here.")
        XCTAssertEqual(EmptyStateCopy.profilePublicationsBody, "Named publications from this cook will show up here.")
    }

    /// Slice 10 (PL-25 / PL-26 / PL-23): the unified state table — same
    /// literals as Android's StateCopyTest pins.
    func testSlice10UnifiedCopyIsPinned() {
        XCTAssertEqual(EmptyStateCopy.insightsTitle, "No cooks yet")
        XCTAssertEqual(EmptyStateCopy.insightsBody, "Finished cooks will show up here with their results, readings and statistics. Start a cook to begin your history.")
        XCTAssertEqual(EmptyStateCopy.recipesTitle, "Your cookbook starts here")
        XCTAssertEqual(EmptyStateCopy.recipesBody, "Save selected dishes from a finished meal here. The original cook stays in your meal history.")
        XCTAssertEqual(EmptyStateCopy.recipesFilteredTitle, "No matching recipes")
        XCTAssertEqual(EmptyStateCopy.recipesFilteredBody, "No recipes match these filters. Try another search or change the filters.")
        XCTAssertEqual(ErrorCopy.saveChangeFailed, "Couldn't save that change. Your cook log is unchanged on this device — try again.")
        XCTAssertEqual(OfflineCopy.banner, "You're offline. Changes wait on this device until a connection returns.")
        XCTAssertEqual(OfflineCopy.changesWait, "Offline — changes wait on this device until a connection returns.")
        XCTAssertEqual(SettingsCopy.sectionOrder, ["Appearance", "Units", "Cook notifications", "Your data", "Feedback", "Ads and privacy", "About"])
        XCTAssertEqual(SettingsCopy.fullBackupScope, "A full backup (ZIP) includes your cooks, log entries, readings, results, reminders, photos, playbooks, plans, checklists, equipment, and app preferences.")
        XCTAssertEqual(LoadingCopy.checkingPro, "Checking PitTech Pro status…")
        XCTAssertEqual(FeedbackCopy.submitFailed, "Couldn't submit your feedback. What you wrote stays in the form — check your connection and try again.")
    }

    /// PL-30 (#172): the shared "not available yet" pattern strings —
    /// identical literals to Android's StateCopyTest pins.
    func testNotAvailablePatternCopyIsPinned() {
        XCTAssertEqual(NotAvailableCopy.devicesTitle, "Devices")
        XCTAssertEqual(NotAvailableCopy.devicesMessage, "Grill connections aren't available yet.")
        XCTAssertEqual(NotAvailableCopy.devicesNote, "You can add temperature readings by hand during a cook.")
        XCTAssertEqual(NotAvailableCopy.devicesOnward, "Open Meals")
        XCTAssertEqual(NotAvailableCopy.discoverTitle, "Find your next cook")
        XCTAssertEqual(NotAvailableCopy.discoverMessage, "Community recipes are being prepared for the next PitTech release. You'll be able to browse, save and cook them without an account.")
        XCTAssertEqual(NotAvailableCopy.discoverOnward, "Open your recipes")
    }
}
