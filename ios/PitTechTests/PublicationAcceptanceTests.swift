import XCTest
@testable import PitTech

@MainActor final class PublicationAcceptanceTests: XCTestCase {
    func testSelectedVariationWholeCollectionAndANewActualCookKeepTheJournalIndependent() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); defer { try? FileManager.default.removeItem(at: root) }
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "public-native-v1", withExtension: "json"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as? [String: Any])
        let bytes = try XCTUnwrap(Data(base64Encoded: object["packBase64"] as! String)), file = root.appendingPathComponent("source.pittech")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); try bytes.write(to: file)
        let store = CookStore(directory: root); await store.receiveArchive(at: file, cookPackOnly: true)
        let intake = try XCTUnwrap(store.archiveIntake); try await store.importSelectedRecipes(intake, selected: Set(intake.recipes.map(\.id))); store.discardArchiveIntake()
        let meal = try store.startRecipeMeal(title: "My new pork and potatoes cook", revisionIds: intake.recipes.map(\.id))
        let dishes = store.dishRecords(meal), pork = try XCTUnwrap(dishes.first { $0.name.lowercased().contains("pork") })
        try store.saveNote(CookNoteDraft(cookId: meal, dishId: pork.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "My food on"))
        try store.saveNote(CookNoteDraft(cookId: meal, dishId: pork.id, title: "My measured temperature", temperatures: [NoteTemperatureDraft(valueText: "167", unit: "°F", dishId: pork.id)]))
        try store.saveNote(CookNoteDraft(cookId: meal, dishId: pork.id, metadata: CookNoteMetadata(category: "action", action: "remove"), title: "My food off"))
        store.finishCook(meal); try store.saveDishVerdict(cookId: meal, dishId: pork.id, rating: 4.5, again: true, notes: "My recorded verdict")
        let journal = store.library, draft = try store.createSharingDraft(cookId: meal, dishIds: Set(dishes.map(\.id)))
        var document = try store.sharingDocument(draft)
        for index in document.entries.indices {
            document.entries[index].included = document.entries[index].sourceDishId == pork.id
            document.entries[index].includeStory = document.entries[index].sourceDishId == pork.id
        }
        try store.saveSharingDraft(draft, document: document, expectedUpdated: store.sharingRecord(draft)?["updated"]?.numberValue ?? 0)
        let entry = try XCTUnwrap(document.entries.first { $0.sourceDishId == pork.id }); var snapshot = try entry.snapshot
        snapshot.description = "My pepper variation"; snapshot.rating = 1; snapshot.resultNotes = "Editable recipe note"
        var setup = try XCTUnwrap(snapshot.draft.objectValue), setupDishes = try XCTUnwrap(setup["dishes"]?.arrayValue), dish = try XCTUnwrap(setupDishes[0].objectValue)
        dish["ingredients"] = .array((dish["ingredients"]?.arrayValue ?? []) + [.object(["name": .string("Extra pepper"), "stage": .string("main_ingredient"), "brand": .string(""), "amount": .string("2"), "unit": .string("tsp")])]); setupDishes[0] = .object(dish); setup["dishes"] = .array(setupDishes); snapshot.draft = .object(setup)
        try store.editSharingEntry(draft, entryId: entry.id, snapshot: snapshot, addedPhotos: [], captions: [], expectedUpdated: store.sharingRecord(draft)?["updated"]?.numberValue ?? 0)
        let variation = try await store.preparePublication(draft, kind: "recipe"), actual = try await store.preparePublication(draft, kind: "attempt")
        let recipe = try XCTUnwrap(actual.payload.objectValue?["recipes"]?.arrayValue?.first?.objectValue), story = try XCTUnwrap(recipe["story"]?.objectValue)
        XCTAssertEqual(story["verdict"]?.objectValue?["rating"]?.numberValue, 4.5); XCTAssertEqual(story["verdict"]?.objectValue?["notes"]?.stringValue, "My recorded verdict")
        XCTAssertEqual(story["measurements"]?.arrayValue?.count, 1); XCTAssertEqual(try XCTUnwrap(story["measurements"]?.arrayValue?.first?.objectValue?["valueCelsius"]?.numberValue), 75, accuracy: 0.0001)
        let detail = object["detail"] as! [String: Any], source = (detail["payload"] as! [String: Any])["recipes"] as! [[String: Any]]
        XCTAssertNotEqual(story["attemptId"]?.stringValue, (source[0]["story"] as! [String: Any])["attemptId"] as? String)
        XCTAssertEqual(recipe["source"]?.objectValue?["itemId"]?.stringValue, detail["id"] as? String)
        XCTAssertEqual(variation.payload.objectValue?["recipes"]?.arrayValue?.count, 1)
        XCTAssertTrue(recipe["ingredients"]?.arrayValue?.contains { $0.objectValue?["name"]?.stringValue == "Extra pepper" } == true)
        document = try store.sharingDocument(draft); for index in document.entries.indices { document.entries[index].included = true }
        document.identityMode = "named"; document.title = "My whole meal"
        try store.saveSharingDraft(draft, document: document, expectedUpdated: store.sharingRecord(draft)?["updated"]?.numberValue ?? 0)
        let collection = try await store.preparePublication(draft, kind: "collection"); XCTAssertEqual(collection.payload.objectValue?["recipes"]?.arrayValue?.count, 2); XCTAssertEqual(collection.payload.objectValue?["identityMode"]?.stringValue, "named")
        do { _ = try await store.preparePublication(draft, kind: "recipe"); XCTFail("A multi-dish selection became a single recipe") } catch { }
        XCTAssertEqual(store.library.events, journal.events); XCTAssertEqual(store.library.results, journal.results); XCTAssertEqual(store.library.dishes, journal.dishes); XCTAssertEqual(store.library.readings, journal.readings)
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        for (name, pack) in [("variant", variation), ("collection", collection), ("attempt", actual)] { try pack.bytes.write(to: folder.appendingPathComponent("pittech-native-publication-" + name + ".pittech"), options: .atomic) }
    }
}
