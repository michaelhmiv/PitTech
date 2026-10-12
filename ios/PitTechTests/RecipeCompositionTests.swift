import XCTest
import UIKit
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
            try store.saveNote(CookNoteDraft(cookId: cook, dishId: dish, description: "\(name) private note"))
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
        let bytes = try store.exportArchive()
        let destination = CookStore(directory: directory.appendingPathComponent("restored"))
        _ = try destination.importArchive(bytes)
        _ = try destination.importArchive(bytes)
        XCTAssertEqual(destination.library.recipes.count, 3)
        XCTAssertEqual(destination.library.dishRecipeSources.count, 3)
        XCTAssertEqual(destination.library.events, originalEvents)
        try destination.library.validateCookExperience()
        // CI retrieves this synthetic fixture to exercise the native writer with the Android reader.
        try bytes.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("pittech-native-recipe-fixture.zip"), options: .atomic)
    }
    @MainActor func testRecipePhotoCopiesAndVersionsRemainAvailableWithoutTheirSourceMeal() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = CookStore(directory: directory)
        store.startCook(title: "Mac", firstDish: "Mac")
        let cook = store.library.cooks[0].id, dish = store.dishRecords(cook)[0].id
        let bytes = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { $0.cgContext.setFillColor(UIColor.orange.cgColor); $0.fill(CGRect(x: 0, y: 0, width: 8, height: 8)) }.jpegData(compressionQuality: 0.8)!
        let snapshot = try store.extractRecipe(cookId: cook, dishId: dish)
        let id = try store.saveRecipe(snapshot, sourceCookId: cook, sourceDishId: dish, addedPhotos: [bytes])
        let first = store.recipe(id)!["currentRevisionId"]!.stringValue!
        var changed = snapshot; changed.description = "Better next time"
        try store.saveRecipe(changed, recipeId: id, expectedRevisionId: first)
        XCTAssertEqual(store.recipeAssets(first).count, 1)
        let current = store.recipe(id)!["currentRevisionId"]!.stringValue!
        XCTAssertEqual(store.recipeAssets(current).count, 1)
        XCTAssertThrowsError(try store.saveRecipe(snapshot, recipeId: id, expectedRevisionId: first))
        store.library.cooks = []; store.library.dishes = []; store.library.lifecycles = []
        try store.library.validateRecipes()
        let asset = store.recipeAssets(current)[0].objectValue!
        XCTAssertEqual(try Data(contentsOf: store.photoURL(asset["archivePath"]!.stringValue!)), bytes)
        try store.duplicateRecipe(id, name: "Spicy Mac")
        XCTAssertEqual(store.library.recipes.count, 2)
        XCTAssertEqual(try store.recipeSnapshot(first).description, "")
    }
}
