import XCTest
@testable import PitTech

@MainActor final class OwnedPublicationTests: XCTestCase {
    private let user = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
    private let owner = "feedfeed-feed-4eed-8eed-feedfeedfeed"
    private func receipt() -> NativePublicationReceipt { NativePublicationReceipt(id: owner, revisionId: "eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee", revision: 3, url: "https://pittech.app/c/" + owner) }
    private func fixture(_ root: URL) throws -> NativePreparedPublication {
        let file = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "native-publication-android-v1", withExtension: "json"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]), bytes = try XCTUnwrap(Data(base64Encoded: object["archiveBase64"] as! String))
        XCTAssertEqual(NativeRelayProtocol.hash(bytes), object["archiveSha256"] as? String)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); let archive = root.appendingPathComponent("owner.zip"); try bytes.write(to: archive)
        let prepared = try NativePreparedPublication.pending(file: archive)
        var payload = try XCTUnwrap(prepared.payload.objectValue), rows = try XCTUnwrap(prepared.payload.objectValue?["recipes"]?.arrayValue)
        var recipe = try XCTUnwrap(rows[0].objectValue); recipe["method"] = .string("Smoke"); rows[0] = .object(recipe); payload["recipes"] = .array(rows)
        return try NativePublicationProjection.package(.object(payload), photos: prepared.photos)
    }
    func testOwnerEditingWithoutAnOriginalDraftPreservesPublicIdentitiesSourcesAndFrozenFacts() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); defer { try? FileManager.default.removeItem(at: root) }
        let original = try fixture(root), draft = try NativeOwnedPublication.draft(original, receipt: receipt()), store = CookStore(directory: root)
        let operations = NativePublicationOperationsStore(directory: root.appendingPathComponent("operations"))
        try operations.attachEditContext(user, draft: draft.id, context: draft.context); try store.saveOwnedPublicationDraft(draft)
        let restored = NativePublicationOperationsStore(directory: operations.directory)
        let context = try XCTUnwrap(restored.editContext(user, draft: draft.id)); XCTAssertEqual(try restored.binding(user, draft: draft.id)?.id, owner)
        XCTAssertNil(try restored.editContext("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb", draft: draft.id))
        var snapshot = try draft.document.entries[0].snapshot; snapshot.description = "My revised instructions"; snapshot.rating = 1
        var step = try XCTUnwrap(snapshot.instructions[0].objectValue); step["details"] = .string("Change the method, not the recorded result."); snapshot.instructions[0] = .object(step)
        try store.editSharingEntry(draft.id, entryId: draft.document.entries[0].id, snapshot: snapshot, addedPhotos: [], captions: [], expectedUpdated: store.sharingRecord(draft.id)?["updated"]?.numberValue ?? 0)
        let update = try await store.preparePublication(draft.id, kind: "recipe", editContext: context)
        let a = try XCTUnwrap(original.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue), b = try XCTUnwrap(update.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(a["method"], b["method"]); XCTAssertEqual(a["ingredients"], b["ingredients"]); XCTAssertEqual(a["id"], b["id"]); XCTAssertEqual(a["source"], b["source"]); XCTAssertEqual(original.payload.objectValue?["source"], update.payload.objectValue?["source"])
        XCTAssertEqual(a["story"], b["story"]); XCTAssertEqual(b["description"]?.stringValue, "My revised instructions")
        XCTAssertEqual(a["instructions"]?.arrayValue?.first?.objectValue?["id"], b["instructions"]?.arrayValue?.first?.objectValue?["id"])
        XCTAssertEqual(a["assets"]?.arrayValue?.first?.objectValue?["id"], b["assets"]?.arrayValue?.first?.objectValue?["id"])
        let independent = try await store.preparePublication(draft.id, kind: "recipe")
        XCTAssertEqual(independent.payload.objectValue?["source"]?.objectValue?["itemId"]?.stringValue, owner)
        XCTAssertNotEqual(independent.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue?["id"], a["id"])
        let operation = try restored.prepare(user: user, method: "PUT", path: "/v1/recipes/" + owner, bytes: update.bytes, archive: true, expected: context.receipt.revisionId, draft: draft.id, kind: "recipe")
        let next = NativePublicationReceipt(id: owner, revisionId: "ffffffff-ffff-4fff-8fff-ffffffffffff", revision: 4, url: "https://pittech.app/c/" + owner)
        try restored.finish(operation, response: JSONEncoder().encode(next)); XCTAssertEqual(try restored.editContext(user, draft: draft.id)?.receipt.revisionId, next.revisionId)
        XCTAssertTrue(store.library.cooks.isEmpty); XCTAssertTrue(store.library.recipes.isEmpty)
        let publicBytes = try JSONEncoder().encode(update.payload); XCTAssertFalse(String(decoding: publicBytes, as: UTF8.self).contains(user))
        let exported = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("pittech-native-owned-update.pittech")
        try update.bytes.write(to: exported, options: .atomic); XCTAssertEqual(try NativePreparedPublication.pending(file: exported).payload, update.payload)
    }
    func testOwnerActualAttemptUpdatesRetainTheAttemptAndCannotClaimAReferenceAsANewCook() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); defer { try? FileManager.default.removeItem(at: root) }
        let original = try fixture(root); var payload = try XCTUnwrap(original.payload.objectValue)
        let attempt = try XCTUnwrap(payload["recipes"]?.arrayValue?.first?.objectValue?["story"]?.objectValue?["attemptId"])
        payload["kind"] = .string("attempt"); payload["measuredAttemptId"] = attempt
        let published = try NativePublicationProjection.package(.object(payload), photos: original.photos)
        let draft = try NativeOwnedPublication.draft(published, receipt: receipt()), store = CookStore(directory: root); try store.saveOwnedPublicationDraft(draft)
        let update = try await store.preparePublication(draft.id, kind: "attempt", editContext: draft.context)
        XCTAssertEqual(update.payload.objectValue?["kind"]?.stringValue, "attempt"); XCTAssertEqual(update.payload.objectValue?["measuredAttemptId"], attempt)
        XCTAssertEqual(update.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue?["story"], original.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue?["story"])
        do { _ = try await store.preparePublication(draft.id, kind: "attempt"); XCTFail("A reference copy claimed a new actual cook") } catch { }
        XCTAssertTrue(store.library.cooks.isEmpty)
    }
}
