import XCTest
@testable import PitTech

@MainActor final class PublicDiscoveryTests: XCTestCase {
    private func fixture() throws -> (Data, [String: Any]) {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "public-native-v1", withExtension: "json")),data = try Data(contentsOf: url)
        return (data, try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any]))
    }
    func testActualServerPackCreatesSelectedOwnedRecipesWithPinnedCreditAndAnIndependentCook() async throws {
        let (data, fixture) = try fixture(),root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = CookStore(directory: root);store.startCook(title: "Keep my existing meal", firstDish: "Existing")
        let oldCook = try XCTUnwrap(store.activeCooks.first?.id),api = NativePublicFixtureAPI(data: data)
        let community = NativePublicCoordinator(enabled: true, api: api, cache: NativePublicCache(directory: root.appendingPathComponent("cache")))
        let id = (fixture["detail"] as! [String: Any])["id"] as! String
        let (detail, _) = try await community.detail(id)
        await community.preview(detail, cook: false, store: store)
        let intake = try XCTUnwrap(store.archiveIntake);XCTAssertEqual(intake.recipes.count, 2)
        let selected = try XCTUnwrap(intake.recipes.first { $0.title.contains("pork") }?.id)
        try await store.importSelectedRecipes(intake, selected: [selected])
        XCTAssertEqual(store.library.recipes.count, 1);XCTAssertEqual(store.library.cooks.count, 1);XCTAssertEqual(store.activeCooks.first?.id, oldCook)
        let snapshot = try store.recipeSnapshot(selected);XCTAssertEqual(snapshot.publicSource?.itemId, id);XCTAssertEqual(snapshot.publicSource?.relativeTiming, true)
        XCTAssertEqual(snapshot.instructions.first?.objectValue?["action"]?.stringValue, "wrap")
        XCTAssertEqual(store.recipeAssets(selected).count, 1)
        store.discardArchiveIntake()
        await community.preview(detail, cook: true, store: store)
        let second = try XCTUnwrap(store.archiveIntake);XCTAssertTrue(second.publicCookOnSave)
        try await store.importSelectedRecipes(second, selected: Set(second.recipes.map(\.id)))
        // PL-19: "Cook this" no longer teleports into a live cook from the
        // save. The save parks a reviewed meal start and creates nothing;
        // confirming the review (what the sheet's Start button calls) is
        // what creates the independent cook.
        XCTAssertNil(store.publicCookDestination)
        XCTAssertEqual(store.library.cooks.count, 1);XCTAssertEqual(store.activeCooks.first?.id, oldCook)
        XCTAssertEqual(store.lastSavedPublicItemId, id)
        let start = try XCTUnwrap(store.pendingPublicRecipeStart)
        XCTAssertEqual(Set(start.revisionIds), Set(second.recipes.map(\.id)))
        let meal = try store.startRecipeMeal(title: start.title, revisionIds: start.revisionIds)
        XCTAssertNotEqual(meal, oldCook);XCTAssertEqual(store.dishRecords(meal).count, 2);XCTAssertTrue(store.library.cooks.contains { $0.id == oldCook })
    }
    func testNativePublicationWriterRetainsPinnedFrozenStoryAndOnlySelectedOwnedContent() async throws {
        let (_, fixture) = try fixture(), root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let bytes = try XCTUnwrap(Data(base64Encoded: fixture["packBase64"] as! String)), store = CookStore(directory: root)
        let file = root.appendingPathComponent("input.pittech"); try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); try bytes.write(to: file)
        await store.receiveArchive(at: file, cookPackOnly: true)
        let intake = try XCTUnwrap(store.archiveIntake), selected = try XCTUnwrap(intake.recipes.first { $0.title.contains("pork") }?.id)
        try await store.importSelectedRecipes(intake, selected: [selected]); store.discardArchiveIntake()
        let id = try store.createRecipeSharingDraft(selected), updated = try XCTUnwrap(store.sharingRecord(id)?["updated"]?.numberValue)
        var document = try store.sharingDocument(id)
        document.identityMode = "anonymous"
        document.entries[0].includeStory = true
        document.entries[0].photoIds = store.sharingPhotos(id).compactMap { $0["id"]?.stringValue }
        try store.saveSharingDraft(id, document: document, expectedUpdated: updated)
        let publication = try await store.preparePublication(id, kind: "recipe")
        let detail = fixture["detail"] as! [String: Any]
        XCTAssertEqual(publication.payload.objectValue?["source"]?.objectValue?["itemId"]?.stringValue, detail["id"] as? String)
        let expected = try JSONDecoder().decode(JSONValue.self, from: JSONSerialization.data(withJSONObject: (detail["payload"] as! [String: Any])["recipes"] as! [[String: Any]]))
        let recipe = try XCTUnwrap(publication.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(recipe["story"], expected.arrayValue?.first?.objectValue?["story"])
        XCTAssertEqual(publication.photos.count, 1)
        var actual = document
        actual.sourceCookId = "local-new-meal"
        actual.heroPhotoId = nil; actual.entries[0].photoIds = []
        var story = try XCTUnwrap(actual.entries[0].story.objectValue)
        story["publicStory"] = nil; story["attemptId"] = .string("local-new-dish")
        story["results"] = .array([.object(["type": .string("overall_rating"), "numericValue": .number(4.5)]), .object(["type": .string("result_notes"), "textValue": .string("Frozen verdict")])])
        actual.entries[0].story = .object(story)
        var edited = try actual.entries[0].snapshot; edited.rating = 1; edited.resultNotes = "Editable recipe note"
        actual.entries[0].snapshotJson = try recipeJSON(edited)
        let measured = try NativePublicationProjection.build(actual, updated: 1, photos: [], kind: "attempt")
        actual.identityMode = "named"
        let revision = try NativePublicationProjection.build(actual, updated: 2, photos: [], kind: "attempt")
        XCTAssertEqual(measured.payload.objectValue?["measuredAttemptId"], revision.payload.objectValue?["measuredAttemptId"])
        let recorded = measured.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue?["story"]?.objectValue?["verdict"]?.objectValue
        XCTAssertEqual(recorded?["rating"]?.numberValue, 4.5); XCTAssertEqual(recorded?["notes"]?.stringValue, "Frozen verdict")
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let exported = folder.appendingPathComponent("pittech-native-publication.pittech")
        try publication.bytes.write(to: exported, options: .atomic)
        let recovered = try NativePreparedPublication.pending(file: exported)
        XCTAssertEqual(recovered.payload, publication.payload); XCTAssertEqual(recovered.photos, publication.photos); XCTAssertEqual(recovered.bytes, publication.bytes)
    }
    func testCacheRestartsOfflineAndKnownUnavailableRecipeDoesNotReuseStaleRemotePreview() async throws {
        let (_, fixture) = try fixture(),root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let api = PublicTestAPI(fixture: fixture),cache = NativePublicCache(directory: root)
        let first = NativePublicCoordinator(enabled: true, api: api, cache: cache)
        await first.refresh(sort: "liked", food: "", query: "");XCTAssertEqual(first.items.count, 1)
        let id = (fixture["detail"] as! [String: Any])["id"] as! String
        _ = try await first.detail(id)
        await api.setStatus(0)
        let restarted = NativePublicCoordinator(enabled: true, api: api, cache: NativePublicCache(directory: root))
        await restarted.refresh(sort: "liked", food: "", query: "")
        XCTAssertEqual(restarted.items.count, 1);XCTAssertTrue(restarted.message?.contains("saved") == true)
        await api.setStatus(404)
        do { _ = try await restarted.detail(id);XCTFail("Unavailable recipe reused its stale preview") } catch { }
        let cached = try await cache.read((try NativeCommunityVault.cacheAudience()) + "|/v1/recipes/" + id);XCTAssertNil(cached)
        let values = try root.resourceValues(forKeys: [.isExcludedFromBackupKey]);XCTAssertEqual(values.isExcludedFromBackup, true)
    }
    func testForeignMediaAndDisabledDiscoveryNeverBecomeNetworkOrImportActions() async throws {
        let (_, fixture) = try fixture(),api = PublicTestAPI(fixture: fixture)
        let disabled = NativePublicCoordinator(api: api)
        await disabled.refresh(sort: "liked", food: "", query: "")
        let count = await api.count();XCTAssertEqual(count, 0)
        var detail = fixture["detail"] as! [String: Any],media = detail["media"] as! [[String: Any]]
        media[0]["url"] = "https://foreign.invalid/private";detail["media"] = media
        let parsed = try JSONDecoder().decode(NativePublicDetail.self, from: JSONSerialization.data(withJSONObject: detail));XCTAssertThrowsError(try parsed.validate())
    }
    func testChangingBlocksCannotReuseThePreviousAudienceOfflineCache() async throws {
        let (_, fixture) = try fixture(), root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        var audience = "before"
        let api = PublicTestAPI(fixture: fixture), cache = NativePublicCache(directory: root)
        let community = NativePublicCoordinator(enabled: true, api: api, cache: cache, audience: { audience })
        await community.refresh(sort: "liked", food: "", query: ""); XCTAssertEqual(community.items.count, 1)
        await api.setStatus(0); audience = "after"; community.controlsChanged()
        await community.refresh(sort: "liked", food: "", query: ""); XCTAssertTrue(community.items.isEmpty)
        // Reverting a block also uses a fresh generation, rather than reviving an
        // earlier audience's cached result while the source cannot be checked.
        audience = "after-unblock"; community.controlsChanged()
        await community.refresh(sort: "liked", food: "", query: ""); XCTAssertTrue(community.items.isEmpty)
    }

}
private actor PublicTestAPI: NativePublicAPI {
    let fixture: [String: Any]
    var status = 200
    var calls = 0
    init(fixture: [String: Any]) { self.fixture = fixture }
    func setStatus(_ status: Int) { self.status = status }
    func count() -> Int { calls }
    func json(_ path: String) async throws -> Data {
        calls += 1;if status != 200 { throw NativePublicError.status(status) }
        return try JSONSerialization.data(withJSONObject: fixture[path.hasPrefix("/v1/discover?") ? "feed" : "detail"]!)
    }
    func pack(_ id: String, revision: String, to: URL) async throws -> URL { XCTFail("Browsing attempted an import");throw NativePublicError.invalid }
    func image(_ id: String, asset: NativePublicDetail.Media) async throws -> Data { Data(base64Encoded: fixture["imageBase64"] as! String)! }
}
