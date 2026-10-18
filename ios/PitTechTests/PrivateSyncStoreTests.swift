import XCTest
@testable import PitTech

final class PrivateSyncStoreTests: XCTestCase {
    private let userA = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private let userB = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
    private func record(_ type: String, _ id: String, _ counter: Int64, _ client: String) -> NativeSyncRecord {
        NativeSyncRecord(recordType: type, recordId: id, version: NativeSyncStamp(counter: counter, clientId: client), deleted: false, payload: ["id": .string(id)], assetIds: [])
    }
    private func state() -> NativePrivateSyncState {
        NativePrivateSyncState(account: userA, serverVersion: 3, maxCounter: 9, base: [record("events", "note-1", 1, userA)],
            resolved: [String(repeating: "1", count: 64)], pending: true,
            mutationId: "22222222-2222-4222-8222-222222222222", mutationSha: String(repeating: "2", count: 64),
            photos: ["photos|photo-1": NativePrivateSyncPhotoEntry(assetId: "33333333-3333-4333-8333-333333333333", sha256: String(repeating: "3", count: 64), bytes: 10, mime: "image/jpeg", received: [0], complete: true)],
            lastSyncedAt: 1000, outcome: "waiting", message: "Waiting for connection.")
    }

    func testMemoryJournalRoundTripsPerAccountState() throws {
        let store = NativePrivateSyncMemoryStore()
        let device = try store.deviceId()
        XCTAssertEqual(device, try store.deviceId())
        try store.save(userA, state())
        try store.writeBody(userA, state().mutationId!, Data("exact-bytes".utf8))
        XCTAssertEqual(state(), try store.load(userA))
        XCTAssertEqual(Data("exact-bytes".utf8), try store.readBody(userA, state().mutationId!))
        XCTAssertEqual(NativePrivateSyncState(account: userB), try store.load(userB))
        try store.save(userB, NativePrivateSyncState(account: userB, serverVersion: 40))
        XCTAssertEqual(3, try store.load(userA).serverVersion)
        try store.purgeAccount(userA)
        XCTAssertEqual(NativePrivateSyncState(account: userA), try store.load(userA))
        XCTAssertEqual(40, try store.load(userB).serverVersion)
        XCTAssertThrowsError(try store.readBody(userA, state().mutationId!))
    }

    func testFileJournalSurvivesRestartStaysAccountBoundAndPurgesCleanly() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = NativePrivateSyncStore(directory: directory)
        let device = try store.deviceId()
        XCTAssertEqual(device, try NativePrivateSyncStore(directory: directory).deviceId())
        try store.save(userA, state())
        try store.writeBody(userA, state().mutationId!, Data("exact-bytes".utf8))
        // A restart is a new instance over the same directory: everything round-trips.
        let restarted = NativePrivateSyncStore(directory: directory)
        XCTAssertEqual(device, try restarted.deviceId())
        XCTAssertEqual(state(), try restarted.load(userA))
        XCTAssertEqual(Data("exact-bytes".utf8), try restarted.readBody(userA, state().mutationId!))
        // Another account never sees this journal.
        XCTAssertEqual(NativePrivateSyncState(account: userB), try restarted.load(userB))
        try restarted.save(userB, NativePrivateSyncState(account: userB, serverVersion: 40))
        XCTAssertEqual(3, try restarted.load(userA).serverVersion)
        try restarted.purgeAccount(userA)
        XCTAssertEqual(NativePrivateSyncState(account: userA), try restarted.load(userA))
        XCTAssertEqual(40, try restarted.load(userB).serverVersion)
        try restarted.purgeAccount(userB)
        // Only the per-device identity remains; every account journal and body is gone.
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil).map(\.lastPathComponent).sorted(), ["device.json"])
    }

    func testCorruptJournalFailsClosedWithoutTouchingTheOtherAccount() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = NativePrivateSyncStore(directory: directory)
        try store.save(userA, state())
        try store.save(userB, NativePrivateSyncState(account: userB, serverVersion: 7))
        try Data("{\"version\":1,\"account\":\"tampered\"}".utf8).write(to: directory.appendingPathComponent(NativeRelayProtocol.hash(Data(userA.utf8)) + ".json"), options: .atomic)
        XCTAssertThrowsError(try NativePrivateSyncStore(directory: directory).load(userA))
        // The surviving account still loads; repair is an explicit purge, never silent.
        XCTAssertEqual(7, try NativePrivateSyncStore(directory: directory).load(userB).serverVersion)
    }
}
