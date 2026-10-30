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
    func testPublicationArchiveWritesTheDocumentsCategory() throws {
        let snapshot = RecipeSnapshot(version: 1, name: "Pork shoulder", description: "", tags: [], draft: .object(["dishes": .array([.object(["name": .string("Pork shoulder"), "foodType": .string("Pork")])])]), instructions: [], observations: [], targets: [], resultNotes: "", rating: nil, smokingAnchorUtcMillis: nil, servings: "")
        let story: JSONValue = .object(["version": .number(1), "attemptId": .string("archive-attempt"), "observations": .array([]), "readings": .array([]), "results": .array([])])
        let document = NativeSharingDocument(title: "Pork shoulder", category: "sides", entries: [NativeSharingEntry(snapshotJson: try recipeJSON(snapshot), story: story)])
        let prepared = try NativePublicationProjection.build(document, updated: 1, photos: [], kind: "recipe")
        XCTAssertEqual(prepared.payload.objectValue?["category"]?.stringValue, "sides")
    }
    func testDiscoverItemCategoryDecodesTolerantly() throws {
        func item(_ category: String?) throws -> NativePublicItem {
            var json = "{\"id\":\"12345678-1234-4234-8234-123456789abc\",\"revisionId\":\"12345678-1234-4234-8234-123456789abd\",\"title\":\"Weekend smoke\",\"kind\":\"recipe\",\"credit\":{\"displayName\":\"a PitTech cook\"},\"likes\":3,\"forks\":1,\"heroAssetId\":null"
            if let category { json += ",\"category\":\"\(category)\"" }
            json += "}"
            let decoded = try JSONDecoder().decode(NativePublicItem.self, from: Data(json.utf8)); try decoded.validate(); return decoded
        }
        XCTAssertEqual(try item("beef").category, "beef"); XCTAssertEqual(try item("beef").categoryLabel, "Beef")
        XCTAssertEqual(try item(nil).category, "other"); XCTAssertEqual(try item(nil).categoryLabel, "Other")
        XCTAssertEqual(try item("mystery-meat").category, "other"); XCTAssertEqual(try item("mystery-meat").categoryLabel, "Other")
    }
    func testDiscoverRequestPathIncludesCategoryOnlyWhenSelected() async throws {
        let (_, fixture) = try fixture(), root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let api = PublicTestAPI(fixture: fixture)
        let community = NativePublicCoordinator(enabled: true, api: api, cache: NativePublicCache(directory: root))
        await community.refresh(sort: "liked", food: "", query: "")
        XCTAssertNil(community.appliedCategory)
        XCTAssertEqual(community.items.first?.category, "other")
        XCTAssertEqual(community.items.first?.categoryLabel, "Other")
        await community.refresh(sort: "liked", food: "", query: "", category: "beef")
        XCTAssertEqual(community.appliedCategory, "beef")
        let paths = await api.requestedPaths()
        XCTAssertEqual(paths.count, 2)
        XCTAssertFalse(paths[0].contains("category="), paths[0])
        XCTAssertTrue(paths[1].contains("category=beef"), paths[1])
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
    // MARK: - Slice 5: fork + tweak-lite, plan-this, card facts

    func testForkConverterAppliesTheThreeTweaksAndRenamesTheSnapshotOnly() async throws {
        let (data, fixture) = try fixture(), root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let community = NativePublicCoordinator(enabled: true, api: NativePublicFixtureAPI(data: data), cache: NativePublicCache(directory: root))
        let id = (fixture["detail"] as! [String: Any])["id"] as! String
        let (detail, _) = try await community.detail(id)
        // The sheet's prefill: renamed, the story's last food temperature
        // (72.2 °C) becomes the target in the reader's unit.
        var tweaks = detail.recipes.indices.map { PublicationFork.prefill(detail: detail, recipeIndex: $0) }
        XCTAssertEqual(tweaks.count, 2)
        XCTAssertEqual(tweaks[0].name, "Oak-smoked pork shoulder — my version")
        XCTAssertEqual(tweaks[0].targetF, 162)
        XCTAssertTrue(tweaks[0].targetFromStory)
        XCTAssertEqual(PublicationFork.untweaked(detail: detail)[0].name, "Oak-smoked pork shoulder")
        tweaks[0].rub = "House rub"; tweaks[0].wrap = .foil; tweaks[0].targetF = 165
        let prepared = try PublicationFork.prepare(detail: detail, tweaks: tweaks)
        XCTAssertEqual(prepared.count, 2)
        let pork = prepared[0]
        XCTAssertEqual(pork.snapshot.name, "Oak-smoked pork shoulder — my version")
        // Android parity on the flagged divergence: the fork renames the
        // snapshot only — the dish keeps its food name.
        let dish = try XCTUnwrap(pork.snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(dish["name"]?.stringValue, "Oak-smoked pork shoulder")
        XCTAssertEqual(pork.snapshot.publicSource?.itemId, detail.id)
        XCTAssertEqual(pork.snapshot.publicSource?.revisionId, detail.revisionId)
        XCTAssertEqual(pork.snapshot.publicSource?.displayName, "a PitTech cook")
        // The frozen story: the wrap milestone plus the synthesized
        // food-on / food-off pair is three observations, readings pinned.
        XCTAssertEqual(pork.snapshot.observations.count, 3)
        XCTAssertEqual(Set(pork.snapshot.observations.compactMap { $0.objectValue?["action"]?.stringValue }), ["food_on", "wrap", "remove"])
        XCTAssertEqual(pork.frozenStory.objectValue?["readings"]?.arrayValue?.count, 2)
        XCTAssertNotNil(pork.frozenStory.objectValue?["publicStory"])
        XCTAssertEqual(pork.photoMediaIds, detail.media.map(\.id))
        // The three tweaks, committed.
        let ingredients = try XCTUnwrap(dish["ingredients"]?.arrayValue)
        XCTAssertEqual(ingredients.first { $0.objectValue?["stage"]?.stringValue == "rub" }?.objectValue?["name"]?.stringValue, "House rub")
        let wrap = try XCTUnwrap(pork.snapshot.instructions.first { $0.objectValue?["action"]?.stringValue == "wrap" })
        XCTAssertEqual(wrap.objectValue?["method"]?.stringValue, "Foil")
        let target = try XCTUnwrap(pork.snapshot.targets.first?.objectValue)
        XCTAssertEqual(target["type"]?.stringValue, "personal_finish")
        XCTAssertEqual(target["value"]?.numberValue, 165)
        XCTAssertEqual(target["unit"]?.stringValue, "°F")
        XCTAssertEqual(pork.changeNotes, "Fork of Weekend smoke — Rub: House rub · Wrap: Foil · Target: 165°F")
        // The story-less second recipe forks plainly and notes it plainly.
        let potatoes = prepared[1]
        XCTAssertNil(potatoes.frozenStory.objectValue?["publicStory"])
        XCTAssertTrue(potatoes.photoMediaIds.isEmpty)
        let plain = try PublicationFork.prepare(detail: detail, tweaks: PublicationFork.untweaked(detail: detail))
        XCTAssertEqual(plain[0].changeNotes, "Saved public recipe")
        XCTAssertEqual(plain[0].snapshot.name, "Oak-smoked pork shoulder")
    }

    func testForkSavesThroughSaveRecipeAndNeverDownloadsAPack() async throws {
        let (_, fixture) = try fixture(), root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = CookStore(directory: root)
        // PublicTestAPI.pack XCTFails: a fork that packs fails this test.
        let community = NativePublicCoordinator(enabled: true, api: PublicTestAPI(fixture: fixture), cache: NativePublicCache(directory: root.appendingPathComponent("cache")))
        let id = (fixture["detail"] as! [String: Any])["id"] as! String
        let (detail, _) = try await community.detail(id)
        var tweaks = detail.recipes.indices.map { PublicationFork.prefill(detail: detail, recipeIndex: $0) }
        tweaks[0].rub = "House rub"; tweaks[0].wrap = .foil; tweaks[0].targetF = 165
        let saved = try await community.fork(detail, tweaks: tweaks, store: store)
        XCTAssertEqual(saved.count, 2)
        XCTAssertEqual(store.library.recipes.count, 2)
        let porkId = try XCTUnwrap(saved.first)
        let revisionId = try XCTUnwrap(store.recipe(porkId)?["currentRevisionId"]?.stringValue)
        let snapshot = try store.recipeSnapshot(revisionId)
        XCTAssertEqual(snapshot.name, "Oak-smoked pork shoulder — my version")
        XCTAssertEqual(store.recipeAssets(revisionId).count, 1)
        XCTAssertEqual(store.recipeRevision(revisionId)?["changeNotes"]?.stringValue, "Fork of Weekend smoke — Rub: House rub · Wrap: Foil · Target: 165°F")
        // The frozen story is pinned as the recipe's guide, public story
        // and readings included.
        let guide = try XCTUnwrap(store.library.companionRecords.first { $0.objectValue?["id"]?.stringValue == "recipe-guide:" + revisionId }?.objectValue)
        let entry = try XCTUnwrap(guide["payload"]?.objectValue?["entries"]?.arrayValue?.first?.objectValue)
        XCTAssertNotNil(entry["story"]?.objectValue?["publicStory"])
        XCTAssertEqual(entry["story"]?.objectValue?["readings"]?.arrayValue?.count, 2)
        // Plan-this reuses the fork (no second save) and the recorded
        // story seeds the plan that opens.
        let opened = await community.planThis(detail, store: store)
        XCTAssertTrue(opened)
        XCTAssertEqual(store.library.recipes.count, 2)
        let editorPlan = try XCTUnwrap(store.servePlanEditorPlan)
        XCTAssertEqual(editorPlan.schedules.count, 1)
        XCTAssertEqual(editorPlan.schedules[0].cookMaxMinutes, 460)
        XCTAssertTrue(editorPlan.schedules[0].evidenceNote.contains("a PitTech cook's recorded cook"), editorPlan.schedules[0].evidenceNote)
    }

    func testCardFactsComeFromTheCachedDetail() async throws {
        let (data, fixture) = try fixture(), root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let community = NativePublicCoordinator(enabled: true, api: NativePublicFixtureAPI(data: data), cache: NativePublicCache(directory: root))
        let id = (fixture["detail"] as! [String: Any])["id"] as! String
        let (detail, _) = try await community.detail(id)
        // 27,600,000 ms on the pit reads 7H 40M; the story's channels are
        // food probes only, so the temp slot honestly stays empty.
        let facts = publicCardFacts(detail: detail, temperatureUnit: "°F")
        XCTAssertEqual(facts.timeLabel, "7H 40M")
        XCTAssertNil(facts.tempLabel)
        await community.refresh(sort: "recent", food: "", query: "")
        let item = try XCTUnwrap(community.items.first)
        let cardFacts = await community.cardFacts(for: item, temperatureUnit: "°F")
        XCTAssertEqual(cardFacts?.timeLabel, "7H 40M")
    }

}
private actor PublicTestAPI: NativePublicAPI {
    let fixture: [String: Any]
    var status = 200
    var calls = 0
    var paths: [String] = []
    init(fixture: [String: Any]) { self.fixture = fixture }
    func setStatus(_ status: Int) { self.status = status }
    func count() -> Int { calls }
    func requestedPaths() -> [String] { paths }
    func json(_ path: String) async throws -> Data {
        calls += 1;paths.append(path);if status != 200 { throw NativePublicError.status(status) }
        return try JSONSerialization.data(withJSONObject: fixture[path.hasPrefix("/v1/discover?") ? "feed" : "detail"]!)
    }
    func pack(_ id: String, revision: String, to: URL) async throws -> URL { XCTFail("Browsing attempted an import");throw NativePublicError.invalid }
    func image(_ id: String, asset: NativePublicDetail.Media) async throws -> Data { Data(base64Encoded: fixture["imageBase64"] as! String)! }
}
