import XCTest
import Security
import UIKit
@testable import PitTech

@MainActor final class LocalJournalRemovalTests: XCTestCase {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
    private func jpeg() -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).jpegData(withCompressionQuality: 1) { context in UIColor.orange.setFill(); context.fill(CGRect(x: 0, y: 0, width: 4, height: 4)) }
    }
    private final class Memory: NativeAccountStorage {
        var value = NativeAccountState()
        func load() throws -> NativeAccountState { value }
        func save(_ value: NativeAccountState) throws { self.value = value }
    }
    func testRemovalEmptiesJournalDraftsAndAttachmentFiles() throws {
        let directory = try folder(), store = CookStore(directory: directory)
        store.startCook(title: "Brisket", firstDish: "Point")
        let cook = try XCTUnwrap(store.activeCooks.first), dish = try XCTUnwrap(store.dishRecords(cook.id).first)
        let photo = try store.stagePhoto(jpeg())
        try store.saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, title: "Wrapped", photos: [photo]))
        try store.persistDraft(CookNoteDraft(cookId: cook.id, description: "Unfinished thought"))
        store.finishCook(cook.id)
        XCTAssertFalse(store.library.cooks.isEmpty)
        XCTAssertFalse(store.library.events.isEmpty)
        XCTAssertFalse(store.library.photos.isEmpty)
        XCTAssertFalse(store.noteDrafts.isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.photoURL(photo.path).path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.storageURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.draftsURL.path))
        try store.removeLocalJournal()
        XCTAssertTrue(store.library.cooks.isEmpty)
        XCTAssertTrue(store.library.dishes.isEmpty)
        XCTAssertTrue(store.library.events.isEmpty)
        XCTAssertTrue(store.library.photos.isEmpty)
        XCTAssertTrue(store.noteDrafts.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.photoURL(photo.path).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("attachments", isDirectory: true).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.storageURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.draftsURL.path))
    }
    func testRemovalRefusesActiveCookAndInProgressRecording() throws {
        let directory = try folder(), store = CookStore(directory: directory)
        store.startCook(title: "Ribs", firstDish: "Rack")
        let cook = try XCTUnwrap(store.activeCooks.first)
        XCTAssertThrowsError(try store.removeLocalJournal()) { XCTAssertTrue($0.localizedDescription.contains("active cook")) }
        store.setMealPaused(cook.id, paused: true)
        XCTAssertThrowsError(try store.removeLocalJournal()) { XCTAssertTrue($0.localizedDescription.contains("active cook")) }
        store.setMealPaused(cook.id, paused: false)
        store.finishCook(cook.id)
        store.telemetryLiveDevices = ["device"]
        XCTAssertThrowsError(try store.removeLocalJournal()) { XCTAssertTrue($0.localizedDescription.contains("recording")) }
        store.telemetryLiveDevices = []
        store.library.recordings.append(.object(["cookId": .string(cook.id), "deviceId": .string("device"), "status": .string("recording")]))
        store.save()
        XCTAssertThrowsError(try store.removeLocalJournal()) { XCTAssertTrue($0.localizedDescription.contains("recording")) }
        XCTAssertEqual(store.library.cooks.count, 1)
        store.library.recordings = store.library.recordings.map { row in guard var o = row.objectValue else { return row }; o["status"] = .string("stopped"); return .object(o) }
        store.save()
        try store.removeLocalJournal()
        XCTAssertTrue(store.library.cooks.isEmpty)
        XCTAssertTrue(store.library.recordings.isEmpty)
    }
    func testRemovalLeavesAccountCommunityAndPublicationStateUntouched() throws {
        let directory = try folder(), store = CookStore(directory: directory)
        store.startCook(title: "Meal", firstDish: "Mac")
        store.finishCook(try XCTUnwrap(store.activeCooks.first).id)
        let service = "com.pittech.account.test." + UUID().uuidString
        defer { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary) }
        let vault = NativeAccountVault(service: service)
        let token = String(repeating: "A", count: 43)
        let session = NativeAccountSession(accessToken: token, refreshToken: token, accessExpiresAt: "2100-01-01T00:00:00.000Z", sessionExpiresAt: "2100-02-01T00:00:00.000Z", userId: "12345678-1234-4234-8234-123456789abc")
        let deletion = NativeDeletionRecord(userId: session.userId, operationId: UUID().uuidString.lowercased(), verifier: token)
        try vault.save(NativeAccountState(session: session, deletion: deletion))
        let communityCodec: JSONEncoder = { let codec = JSONEncoder(); codec.outputFormatting = .sortedKeys; return codec }()
        let communityBefore = try communityCodec.encode(NativeCommunityVault().load())
        let operationsRoot = try folder()
        let operations = NativePublicationOperationsStore(directory: operationsRoot)
        let item = UUID().uuidString.lowercased()
        let pending = try operations.prepare(user: session.userId, method: "POST", path: "/v1/recipes/" + item + "/like", bytes: Data("{}".utf8), archive: false)
        try store.removeLocalJournal()
        XCTAssertTrue(store.library.cooks.isEmpty)
        XCTAssertEqual(try vault.load().session?.refreshToken, token)
        XCTAssertEqual(try vault.load().deletion?.operationId, deletion.operationId)
        XCTAssertEqual(try communityCodec.encode(NativeCommunityVault().load()), communityBefore)
        XCTAssertEqual(try NativePublicationOperationsStore(directory: operationsRoot).pending(session.userId)?.id, pending.id)
        XCTAssertTrue(FileManager.default.fileExists(atPath: (try operations.body(pending)).path))
    }
    func testEmptiedJournalStaysEmptyAfterRelaunch() throws {
        let directory = try folder(), store = CookStore(directory: directory)
        store.startCook(title: "Meal", firstDish: "Mac")
        let cook = try XCTUnwrap(store.activeCooks.first)
        let photo = try store.stagePhoto(jpeg())
        try store.saveNote(CookNoteDraft(cookId: cook.id, title: "Seasoned", photos: [photo]))
        store.finishCook(cook.id)
        try store.removeLocalJournal()
        let relaunched = CookStore(directory: directory)
        XCTAssertTrue(relaunched.library.cooks.isEmpty)
        XCTAssertTrue(relaunched.library.events.isEmpty)
        XCTAssertTrue(relaunched.library.photos.isEmpty)
        XCTAssertTrue(relaunched.noteDrafts.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: relaunched.photoURL(photo.path).path))
    }
    func testCoordinatorRemovalIgnoresAccountAvailabilityAndSurfacesRefusal() throws {
        let store = CookStore(directory: try folder())
        store.startCook(title: "Ribs", firstDish: "Rack")
        let cook = try XCTUnwrap(store.activeCooks.first)
        let coordinator = NativeAccountCoordinator(vault: Memory(), removeJournal: { try store.removeLocalJournal() })
        XCTAssertFalse(coordinator.enabled)
        coordinator.removeLocalJournal()
        XCTAssertTrue(coordinator.message?.contains("active cook") == true)
        XCTAssertEqual(store.activeCooks.count, 1)
        store.finishCook(cook.id)
        coordinator.removeLocalJournal()
        XCTAssertEqual(coordinator.message, "Local journal removed from this device. Your server account is unchanged.")
        XCTAssertTrue(store.library.cooks.isEmpty)
        XCTAssertNil(coordinator.deletionRecord)
    }
}
