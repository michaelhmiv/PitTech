#if DEBUG
import Foundation

private struct NativeSyncOfflineAPI: NativePrivateSyncAPI {
    func snapshot(access: String) async throws -> Data { throw URLError(.notConnectedToInternet) }
    func commit(body: Data, mutationId: String, access: String) async throws -> Data { throw URLError(.notConnectedToInternet) }
    func beginPhoto(assetId: String, bytes: Int, mime: String, sha: String, access: String) async throws -> Data { throw URLError(.notConnectedToInternet) }
    func photoStatus(assetId: String, access: String) async throws -> Data { throw URLError(.notConnectedToInternet) }
    func putPart(assetId: String, index: Int, chunk: Data, access: String) async throws -> Data { throw URLError(.notConnectedToInternet) }
    func completePhoto(assetId: String, access: String) async throws -> Data { throw URLError(.notConnectedToInternet) }
    func abandonPhoto(assetId: String, access: String) async throws -> Data { throw URLError(.notConnectedToInternet) }
    func downloadPhoto(assetId: String, to: URL, access: String) async throws { throw URLError(.notConnectedToInternet) }
}

/// UI automation only: a memory journal with one retained conflict over a fixture note.
/// Never contacts a server; sync actions run offline against the fixture library.
@MainActor enum NativeSyncUITestSupport {
    static let user = "12345678-1234-4234-8234-123456789abc"
    static var active: Bool { CommandLine.arguments.contains("--sync-ui-test") }
    static func sync(store: CookStore, account: NativeAccountCoordinator) -> NativePrivateSyncCoordinator {
        let journal = NativePrivateSyncMemoryStore()
        let device = (try? journal.deviceId()) ?? UUID().uuidString.lowercased()
        func note(_ title: String) -> [String: JSONValue] {
            ["id": .string("note-1"), "cookId": .string("cook-1"), "eventType": .string("note"), "title": .string(title),
             "occurredAtUtcMillis": .number(1000), "recordedAtUtcMillis": .number(1000), "timeZoneId": .string("UTC"), "source": .string("manual"),
             "createdAtUtcMillis": .number(1000), "updatedAtUtcMillis": .number(1000)]
        }
        func versioned(_ counter: Int64, _ client: String, _ payload: [String: JSONValue]) -> NativeSyncRecord {
            NativeSyncRecord(recordType: "events", recordId: "note-1", version: NativeSyncStamp(counter: counter, clientId: client), deleted: false, payload: payload, assetIds: [])
        }
        let base = versioned(1, device, note("Base title"))
        let conflict = (try? NativePrivateSyncMerge.merge(base: [base], local: [versioned(2, device, note("Local title"))],
            remote: [versioned(3, "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb", note("Remote title"))]).conflicts.first) ?? NativeSyncConflict(id: String(repeating: "0", count: 64), winner: base, loser: base, reason: "edit_edit")
        try? journal.save(user, NativePrivateSyncState(account: user, serverVersion: 1, maxCounter: 4, base: [base], conflicts: [conflict]))
        // The fixture library holds the merged winner, exactly as a real apply would leave it.
        var library = store.library
        if !library.cooks.contains(where: { $0.id == "cook-1" }) {
            library.cooks.append(CookRecord(id: "cook-1", title: "Weeknight cook", status: "active", startedAtUtcMillis: 1000, startedTimeZoneId: "UTC",
                endedAtUtcMillis: nil, smokerName: nil, initialSetpointValue: nil, initialSetpointUnit: nil, notes: nil,
                createdAtUtcMillis: 1000, updatedAtUtcMillis: 1000, fuelType: nil, woodOrPelletBlend: nil,
                outdoorTemperatureValue: nil, outdoorTemperatureUnit: nil, weatherNotes: nil, windNotes: nil))
        }
        if !(library.dishes.contains { $0.objectValue?["id"]?.stringValue == "dish-1" }) {
            library.dishes.append(.object(["id": .string("dish-1"), "cookId": .string("cook-1"), "name": .string("Brisket"), "foodType": .string("beef"), "createdAtUtcMillis": .number(1000), "updatedAtUtcMillis": .number(1000)]))
        }
        library.events.removeAll { $0.objectValue?["id"]?.stringValue == "note-1" }
        library.events.append(.object(note("Remote title")))
        store.library = library; store.save()
        let projection = try? NativePrivateSyncCoordinator.loadContract()
        return NativePrivateSyncCoordinator(store: journal, session: account, api: NativeSyncOfflineAPI(), library: NativeCookStoreSyncLibrary(store), projection: projection, enabled: true)
    }
}
#endif
