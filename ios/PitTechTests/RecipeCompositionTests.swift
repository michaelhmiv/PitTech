import XCTest
import UIKit
import ZIPFoundation
@testable import PitTech

final class RecipeCompositionTests: XCTestCase {
    @MainActor func testRecipesComposeIndependentAttemptsAndPreserveArchiveRelationships() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = CookStore(directory: directory)
        var revisions: [String] = [], originalEvents: [JSONValue] = []
        for name in ["Chicken", "Mac", "Pork"] {
            store.startCook(title: name, firstDish: name)
            let cook = store.library.cooks.last!.id, dish = store.dishRecords(cook)[0].id
            let temperatures = name == "Chicken" ? [NoteTemperatureDraft(probeName: "Same name", valueText: "74.125", unit: "°C", dishId: dish)] : []
            var photos: [NotePhotoDraft] = []
            if name == "Chicken" {
                let format = UIGraphicsImageRendererFormat(); format.scale = 1
                let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 4), format: format).image { UIColor.orange.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 8, height: 4)) }
                photos = [try store.stagePhoto(image.jpegData(compressionQuality: 0.8)!)]
            }
            try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, description: "\(name) private note", temperatures: temperatures, photos: photos))
            store.finishCook(cook)
            let recipeId = try store.saveRecipe(store.extractRecipe(cookId: cook, dishId: dish), sourceCookId: cook, sourceDishId: dish)
            revisions.append(store.recipe(recipeId)!["currentRevisionId"]!.stringValue!)
        }
        originalEvents = store.library.events
        let meal = try store.startRecipeMeal(title: "Today", revisionIds: revisions)
        XCTAssertEqual(store.dishRecords(meal).count, 3)
        XCTAssertEqual(store.library.events, originalEvents)
        let dish = store.dishRecords(meal)[1].id
        var working = try XCTUnwrap(store.recipeAttempt(dish))
        working.description = "Try more Gouda"
        try store.updateRecipeAttempt(dish, snapshot: working)
        XCTAssertEqual(try store.recipeSnapshot(revisions[1]).description, "")
        XCTAssertEqual(try store.recipeAttempt(dish)?.description, "Try more Gouda")
        try store.saveMealPreset(meal, name: "Favorites")
        XCTAssertEqual(store.library.recipes.count, 3)
        try store.saveReminderRule(cookId: meal, dishId: dish, title: "Last time wrapped · check the bark", config: ["version": .number(1), "type": .string("historical"), "anchorAction": .string("food_on"), "offsetMillis": .number(12000000)], id: "portable-history", revisionId: revisions[1], milestoneId: "original-wrap")
        let sharingId = try store.createSharingDraft(cookId: meal, dishIds: Set(store.dishRecords(meal).map(\.id)))
        var sharing = try store.sharingDocument(sharingId)
        for index in sharing.entries.indices { sharing.entries[index].included = index == 2 }
        try store.saveSharingDraft(sharingId, document: sharing, expectedUpdated: store.sharingRecord(sharingId)!["updated"]!.numberValue!)
        let bytes = try store.exportArchive()
        let destination = CookStore(directory: directory.appendingPathComponent("restored"))
        _ = try destination.importArchive(bytes)
        _ = try destination.importArchive(bytes)
        XCTAssertEqual(destination.library.recipes.count, 3)
        XCTAssertEqual(destination.library.dishRecipeSources.count, 3)
        XCTAssertEqual(destination.library.events, originalEvents)
        XCTAssertEqual(try destination.sharingDocument(sharingId), sharing)
        try destination.library.validateCookExperience()
        // CI retrieves this synthetic fixture to exercise the native writer with the Android reader.
        let fixtureURL = FileManager.default.temporaryDirectory.appendingPathComponent("pittech-native-recipe-fixture.zip")
        try bytes.write(to: fixtureURL, options: .atomic)
        // Preserve the legacy/full fixture and carry actual writer outputs inside the same CI artifact.
        let recordedCook = store.library.cooks.first!.id, recordedDish = store.dishRecords(recordedCook)[0].id
        let packDraft = try store.createSharingDraft(cookId: recordedCook, dishIds: [recordedDish])
        var chosen = try store.sharingDocument(packDraft); chosen.entries[0].includeStory = true
        chosen.entries[0].photoIds = store.sharingPhotos(packDraft).compactMap { $0["id"]?.stringValue }; chosen.heroPhotoId = chosen.entries[0].photoIds.first
        try store.saveSharingDraft(packDraft, document: chosen, expectedUpdated: store.sharingRecord(packDraft)!["updated"]!.numberValue!)
        let recipePack = try store.exportCookPack(packDraft)
        try store.addRecipeToSharingDraft(packDraft, revisionId: revisions[1], expectedUpdated: store.sharingRecord(packDraft)!["updated"]!.numberValue!)
        let collectionPack = try store.exportCookPack(packDraft)
        store.library.cooks[0].notes = String(repeating: "🌶", count: 17000)
        let workbookURL = directory.appendingPathComponent("native.xlsx"), csvURL = directory.appendingPathComponent("native.csv")
        try NativeTabularExport.writeWorkbook(store.library, to: workbookURL); try NativeTabularExport.writeCSV(store.library, to: csvURL)
        let fixture = try Archive(url: fixtureURL, accessMode: .update)
        for (path, data) in [("fixtures/recipe.pittech", recipePack), ("fixtures/collection.pittech", collectionPack), ("fixtures/portable.xlsx", try Data(contentsOf: workbookURL)), ("fixtures/readings.csv", try Data(contentsOf: csvURL))] {
            try fixture.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count), compressionMethod: .deflate) { offset, count in data.subdata(in: Int(offset)..<(Int(offset) + count)) }
        }
        let attachment = XCTAttachment(data: try Data(contentsOf: fixtureURL), uniformTypeIdentifier: "public.zip-archive")
        attachment.name = "PitTech actual native portable fixtures"; attachment.lifetime = .keepAlways; add(attachment)
    }
    @MainActor func testRecipePhotoCopiesAndVersionsRemainAvailableWithoutTheirSourceMeal() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = CookStore(directory: directory)
        store.startCook(title: "Mac", firstDish: "Mac")
        let cook = store.library.cooks[0].id, dish = store.dishRecords(cook)[0].id
        let bytes = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { $0.cgContext.setFillColor(UIColor.orange.cgColor); $0.fill(CGRect(x: 0, y: 0, width: 8, height: 8)) }.jpegData(compressionQuality: 0.8)!
        let photo = try store.stagePhoto(bytes)
        try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, title: "Original source photo", photos: [photo]))
        let originalPhoto = try XCTUnwrap(store.library.photos.first?.objectValue)
        let originalPath = try XCTUnwrap(originalPhoto["archivePath"]?.stringValue)
        let snapshot = try store.extractRecipe(cookId: cook, dishId: dish)
        let id = try store.saveRecipe(snapshot, sourceCookId: cook, sourceDishId: dish, photoIds: [try XCTUnwrap(originalPhoto["id"]?.stringValue)])
        let first = store.recipe(id)!["currentRevisionId"]!.stringValue!
        var changed = snapshot; changed.description = "Better next time"
        try store.saveRecipe(changed, recipeId: id, expectedRevisionId: first)
        XCTAssertEqual(store.recipeAssets(first).count, 1)
        let current = store.recipe(id)!["currentRevisionId"]!.stringValue!
        XCTAssertEqual(store.recipeAssets(current).count, 1)
        XCTAssertThrowsError(try store.saveRecipe(snapshot, recipeId: id, expectedRevisionId: first))
        try store.saveRecipe(snapshot, recipeId: id, expectedRevisionId: current, assetRevisionId: first)
        let branch = try XCTUnwrap(store.recipe(id)?["currentRevisionId"]?.stringValue)
        XCTAssertEqual(store.recipeRevision(branch)?["parentRevisionId"]?.stringValue, first)
        XCTAssertEqual(store.recipeRevision(branch)?["revisionNumber"]?.numberValue, 3)
        store.library.cooks = []; store.library.dishes = []; store.library.lifecycles = []; store.library.events = []; store.library.photos = []; store.library.logReadingLinks = []; store.library.readings = []
        try FileManager.default.removeItem(at: store.photoURL(originalPath))
        try store.library.validateRecipes()
        let asset = store.recipeAssets(branch)[0].objectValue!
        XCTAssertNotEqual(asset["archivePath"]?.stringValue, originalPath)
        XCTAssertEqual(try Data(contentsOf: store.photoURL(asset["archivePath"]!.stringValue!)), bytes)
        try store.duplicateRecipe(id, name: "Spicy Mac")
        XCTAssertEqual(store.library.recipes.count, 2)
        XCTAssertEqual(try store.recipeSnapshot(first).description, "")
    }
}
