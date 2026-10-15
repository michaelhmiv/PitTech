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
        let meal = try XCTUnwrap(store.publicCookDestination)
        XCTAssertNotEqual(meal, oldCook);XCTAssertEqual(store.dishRecords(meal).count, 2);XCTAssertTrue(store.library.cooks.contains { $0.id == oldCook })
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
        let cached = try await cache.read("/v1/recipes/" + id);XCTAssertNil(cached)
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
