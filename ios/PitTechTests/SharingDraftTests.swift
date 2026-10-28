import XCTest
import UIKit
@testable import PitTech

@MainActor final class SharingDraftTests: XCTestCase {
    func testPorkOnlyDraftPersistsAndKeepsOwnedPhotosAfterSourceRemoval() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        store.startCook(title: "Pork and potatoes", firstDish: "Pork")
        let cook = try XCTUnwrap(store.activeCooks.first?.id); store.addDish(cookId: cook, name: "Potatoes")
        let pork = try XCTUnwrap(store.dishRecords(cook).first { $0.name == "Pork" })
        let bytes = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).jpegData(withCompressionQuality: 1) { UIColor.brown.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 8, height: 8)) }
        let photo = try store.stagePhoto(bytes)
        try store.saveNote(CookNoteDraft(cookId: cook, dishId: pork.id, title: "Bark", photos: [photo]))
        try store.saveDishVerdict(cookId: cook, dishId: pork.id, rating: nil, again: nil, notes: "")
        XCTAssertNil(store.dishVerdict(cookId: cook, dishId: pork.id).again)
        try store.saveDishVerdict(cookId: cook, dishId: pork.id, rating: 4.5, again: false, notes: "More pepper")
        XCTAssertEqual(store.dishVerdict(cookId: cook, dishId: pork.id).again, false)
        let events = store.library.events, dishes = store.library.dishes
        let id = try store.createSharingDraft(cookId: cook, dishIds: Set(store.dishRecords(cook).map(\.id)))
        var draft = try store.sharingDocument(id); let originalStory = draft.entries.first { $0.sourceDishId == pork.id }!.story
        let asset = try XCTUnwrap(store.sharingPhotos(id).first), assetId = try XCTUnwrap(asset["id"]?.stringValue)
        for index in draft.entries.indices {
            draft.entries[index].included = draft.entries[index].sourceDishId == pork.id
            if draft.entries[index].included { var snapshot = try draft.entries[index].snapshot; snapshot.description = "Extra pepper"; draft.entries[index].snapshotJson = try recipeJSON(snapshot); draft.entries[index].photoIds = [assetId]; draft.entries[index].includeStory = true }
        }
        try store.saveSharingDraft(id, document: draft, expectedUpdated: store.sharingRecord(id)!["updated"]!.numberValue!)
        XCTAssertEqual(store.library.events, events); XCTAssertEqual(store.library.dishes, dishes)
        let relaunched = CookStore(directory: folder)
        XCTAssertEqual(try relaunched.sharingDocument(id), draft)
        let collection = try relaunched.copySharingDocument(id, asCollection: true)
        XCTAssertEqual(try relaunched.sharingDocument(collection).entries.count, 1)
        XCTAssertEqual(try relaunched.sharingDocument(collection).entries[0].story, originalStory)
        var forged = draft; forged.entries[0].story = .object(["version": .number(1), "attemptId": .string("fabricated"), "readings": .array([])])
        XCTAssertThrowsError(try relaunched.saveSharingDraft(id, document: forged, expectedUpdated: relaunched.sharingRecord(id)!["updated"]!.numberValue!))
        let originalPath = try XCTUnwrap(store.library.photos.first?.objectValue?["archivePath"]?.stringValue)
        relaunched.library.cooks = []; relaunched.library.dishes = []; relaunched.library.lifecycles = []; relaunched.library.events = []; relaunched.library.photos = []; relaunched.library.results = []; relaunched.library.readings = []; relaunched.library.logReadingLinks = []
        try FileManager.default.removeItem(at: relaunched.photoURL(originalPath))
        try relaunched.library.validateSharingDocuments()
        let ownedPath = try XCTUnwrap(asset["archivePath"]?.stringValue)
        XCTAssertEqual(try Data(contentsOf: relaunched.photoURL(ownedPath)), bytes)
        let archive = try relaunched.exportArchive(); XCTAssertEqual(try CookStore.previewArchive(archive).version, 10)
        let restored = CookStore(directory: folder.appendingPathComponent("restored")); _ = try restored.importArchive(archive)
        XCTAssertEqual(try restored.sharingDocument(id), draft)
        XCTAssertEqual(restored.sharingPhotos(id).count, 1)
        let importedLibrary = restored.library
        _ = try restored.importArchive(archive)
        XCTAssertEqual(restored.library.companionRecords, importedLibrary.companionRecords)
        XCTAssertEqual(restored.library.companionPhotos, importedLibrary.companionPhotos)
        XCTAssertEqual(try Data(contentsOf: restored.photoURL(ownedPath)), bytes)
        var stale = draft; stale.title = "Stale edit"
        XCTAssertThrowsError(try relaunched.saveSharingDraft(id, document: stale, expectedUpdated: 0))
        XCTAssertEqual(try relaunched.sharingDocument(id), draft)
    }
    func testFoodCategoryTaxonomyMatchesSharedContractFixture() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "food-categories-v1", withExtension: "json"))
        let fixture = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: url))
        XCTAssertEqual(fixture.objectValue?["version"]?.numberValue, 1)
        let categories = try XCTUnwrap(fixture.objectValue?["categories"]?.arrayValue)
        XCTAssertEqual(categories.count, 12)
        XCTAssertEqual(categories.count, FoodCategory.all.count)
        for (index, category) in categories.enumerated() {
            XCTAssertEqual(category.objectValue?["id"]?.stringValue, FoodCategory.all[index].id)
            XCTAssertEqual(category.objectValue?["label"]?.stringValue, FoodCategory.all[index].label)
            XCTAssertEqual(category.objectValue?["examples"]?.arrayValue?.compactMap(\.stringValue), FoodCategory.all[index].examples)
        }
        XCTAssertEqual(FoodCategory.defaultId, "other")
        XCTAssertEqual(FoodCategory.all.last?.id, FoodCategory.defaultId)
        XCTAssertEqual(FoodCategory.label(forId: "beef"), "Beef")
        XCTAssertEqual(FoodCategory.label(forId: "not-a-category"), "Other")
        XCTAssertEqual(FoodCategory.normalizedId("not-a-category"), "other")
    }
    func testFoodCategorySuggestionMapsFreeTextFoodTypes() {
        let cases: [(String, String)] = [("Beef", "beef"), ("pOrK", "pork"), ("Chicken", "chicken"), ("Turkey", "turkey"),
            ("Poultry", "chicken"), ("Seafood", "seafood"), ("Fish", "seafood"), ("Salmon", "seafood"), ("Shrimp", "seafood"),
            ("Wild game", "lamb-game"), ("Lamb", "lamb-game"), ("Venison", "lamb-game"), ("Duck", "lamb-game"),
            ("Sausage", "sausage-links"), ("Vegetables", "veggies"), ("Veggie tray", "veggies"), ("Dessert", "desserts"),
            ("Side dish", "sides"), ("Snack", "apps-snacks"), ("Appetizer", "apps-snacks"), ("Other", "other"), ("", "other")]
        for (foodType, expected) in cases { XCTAssertEqual(FoodCategory.suggestedId(forFoodType: foodType), expected, foodType) }
    }
    func testSharingDraftCategoryRoundTripsDerivesAndValidates() throws {
        func document(foodType: String?) throws -> NativeSharingDocument {
            var dish: [String: JSONValue] = ["name": .string("Pork shoulder")]
            if let foodType { dish["foodType"] = .string(foodType) }
            let snapshot = RecipeSnapshot(version: 1, name: "Pork shoulder", description: "", tags: [], draft: .object(["dishes": .array([.object(dish)])]), instructions: [], observations: [], targets: [], resultNotes: "", rating: nil, smokingAnchorUtcMillis: nil, servings: "")
            let story: JSONValue = .object(["version": .number(1), "attemptId": .string("draft-attempt"), "observations": .array([]), "readings": .array([]), "results": .array([])])
            return NativeSharingDocument(title: "Pork shoulder", entries: [NativeSharingEntry(snapshotJson: try recipeJSON(snapshot), story: story)])
        }
        var explicit = try document(foodType: "Pork"); explicit.category = "beef"; try explicit.validate()
        let encoded = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(explicit))
        XCTAssertEqual(encoded.objectValue?["category"]?.stringValue, "beef")
        XCTAssertEqual(try NativeSharingDocument.decode(encoded), explicit)
        // A stored draft with no category derives one from the first
        // entry's dish food type; an out-of-taxonomy stored value derives
        // the same way instead of failing the draft.
        var stored = try XCTUnwrap(encoded.objectValue); stored["category"] = nil
        XCTAssertEqual(try NativeSharingDocument.decode(.object(stored)).category, "pork")
        stored["category"] = .string("not-a-category")
        XCTAssertEqual(try NativeSharingDocument.decode(.object(stored)).category, "pork")
        let untyped = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(try document(foodType: nil)))
        var untypedStored = try XCTUnwrap(untyped.objectValue); untypedStored["category"] = nil
        XCTAssertEqual(try NativeSharingDocument.decode(.object(untypedStored)).category, "other")
        // In-memory validation still rejects an out-of-taxonomy id.
        explicit.category = "not-a-category"
        XCTAssertThrowsError(try explicit.validate())
    }
    func testStoryKeepsCelsiusAndFahrenheitValuesWithoutChangingNativeSourceUnits() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder); store.startCook(title: "Pork", firstDish: "Pork")
        let cook = store.activeCooks[0].id, dish = store.dishRecords(cook)[0].id
        try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, title: "Manual samples", temperatures: [
            NoteTemperatureDraft(probeName: "Celsius", valueText: "0", unit: "°C", dishId: dish),
            NoteTemperatureDraft(probeName: "Fahrenheit", valueText: "32.125", unit: "°F", dishId: dish)]))
        let original = store.library.readings
        let id = try store.createSharingDraft(cookId: cook, dishIds: [dish])
        let readings = try XCTUnwrap(store.sharingDocument(id).entries[0].story.objectValue?["readings"]?.arrayValue)
        XCTAssertEqual(readings.compactMap { $0.objectValue?["unit"]?.stringValue }, ["°C", "°F"])
        XCTAssertEqual(readings.compactMap { $0.objectValue?["value"]?.numberValue }, [0, 32.125])
        XCTAssertEqual(store.library.readings, original)
    }
    func testUnknownVerdictAndOldArchivesRemainUnset() throws {
        let old = try JSONDecoder().decode(PitTechLibrary.self, from: Data("{\"schemaVersion\":9}".utf8))
        XCTAssertTrue(old.results.isEmpty); XCTAssertTrue(old.companionRecords.isEmpty)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = CookStore(directory: root); store.startCook(title: "Pork", firstDish: "Pork")
        let cook = store.activeCooks[0].id, dish = store.dishRecords(cook)[0].id
        XCTAssertNil(store.dishVerdict(cookId: cook, dishId: dish).rating)
        try store.saveDishVerdict(cookId: cook, dishId: dish, rating: 5, again: true, notes: "")
        let recipe = try store.extractRecipe(cookId: cook, dishId: dish)
        XCTAssertEqual(recipe.rating, 5); XCTAssertEqual(recipe.cookAgain, true)
        try store.saveDishVerdict(cookId: cook, dishId: dish, rating: nil, again: nil, notes: "")
        XCTAssertNil(store.dishVerdict(cookId: cook, dishId: dish).rating); XCTAssertNil(store.dishVerdict(cookId: cook, dishId: dish).again)
    }
}
