import XCTest
@testable import PitTech

/// PL-10 / PL-13 (#172): the unified Start Cook setup — one start for
/// every iOS start path, the same fields in the same order as Android,
/// and a kept setup draft that behaves like Android's (kept on leave,
/// resumed from Meals, cleared by starting or discarding).
final class StartCookSetupTests: XCTestCase {
    private func makeStore() -> (store: CookStore, directory: URL, defaults: UserDefaults) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let defaults = UserDefaults(suiteName: "pittech-setup-tests-\(UUID().uuidString)")!
        return (CookStore(directory: directory, defaults: defaults), directory, defaults)
    }

    /// The canonical field order both platforms present, pinned so a
    /// future field lands in the same place on Android and iOS.
    @MainActor func testFieldOrderMatchesTheSharedContract() {
        XCTAssertEqual(CookSetupDraft.fieldOrder, [
            "source", "stage", "title", "dishes", "smoker", "setpoint", "notes",
            "fuel", "wood", "outdoorTemperature", "weather", "wind",
        ])
    }

    /// Confirming the review creates the cook with EVERY field the form
    /// carries — the old two-field iOS start silently dropped the rest.
    @MainActor func testStartCookFromSetupPersistsEveryField() throws {
        let (store, directory, _) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        var setup = CookSetupDraft.blank(temperatureUnit: "°F", weightUnit: "lb")
        setup.title = "Full setup"
        setup.smokerName = "Offset"
        setup.setpointText = "250"
        setup.notes = "Wind from the north"
        setup.fuelType = "Oak splits"
        setup.woodBlend = "Post oak"
        setup.outdoorTemperatureText = "61"
        setup.weather = "Clear"
        setup.wind = "Breezy"
        setup.dishes = [CookSetupDish(name: "Brisket", foodType: "Beef", cut: "Packer", weightText: "12", weightUnit: "lb")]
        store.keepSetupDraft(setup)
        let cookId = try store.startCook(setup: setup)
        let cook = try XCTUnwrap(store.library.cooks.first { $0.id == cookId })
        XCTAssertEqual(cook.title, "Full setup")
        XCTAssertEqual(cook.smokerName, "Offset")
        XCTAssertEqual(cook.initialSetpointValue, 250)
        XCTAssertEqual(cook.initialSetpointUnit, "°F")
        XCTAssertEqual(cook.notes, "Wind from the north")
        XCTAssertEqual(cook.fuelType, "Oak splits")
        XCTAssertEqual(cook.woodOrPelletBlend, "Post oak")
        XCTAssertEqual(cook.outdoorTemperatureValue, 61)
        XCTAssertEqual(cook.outdoorTemperatureUnit, "°F")
        XCTAssertEqual(cook.weatherNotes, "Clear")
        XCTAssertEqual(cook.windNotes, "Breezy")
        XCTAssertEqual(store.dishRecords(cookId).map(\.name), ["Brisket"])
        // Starting consumed the setup: no draft is kept afterwards.
        XCTAssertNil(store.setupDraft)
    }

    /// A Recipes start builds the review setup and starts nothing; the
    /// cook exists only after the review confirms, recipe attached.
    @MainActor func testRecipeStartSetupCarriesSourcesWithoutStarting() throws {
        let (store, directory, _) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        store.startCook(title: "Chicken", firstDish: "Chicken")
        let source = store.library.cooks.last!.id, dish = store.dishRecords(source)[0].id
        store.finishCook(source)
        let recipeId = try store.saveRecipe(store.extractRecipe(cookId: source, dishId: dish), sourceCookId: source, sourceDishId: dish)
        let revision = store.recipe(recipeId)!["currentRevisionId"]!.stringValue!
        let cooksBefore = store.library.cooks.count
        let setup = try store.recipeStartSetup(title: "Again", revisionIds: [revision], sourceLabel: "From your Recipes · saved recipes stay attached to their dishes")
        XCTAssertEqual(store.library.cooks.count, cooksBefore)
        XCTAssertEqual(setup.sourceLabel, "From your Recipes · saved recipes stay attached to their dishes")
        XCTAssertEqual(setup.dishes.map(\.revisionId), [revision])
        let cookId = try store.startCook(setup: setup)
        let newDish = try XCTUnwrap(store.dishRecords(cookId).first)
        XCTAssertEqual(store.recipeSource(newDish.id)?["recipeRevisionId"]?.stringValue, revision)
    }

    /// A meal combination's review setup keeps the working copy, so the
    /// confirmed start matches what the old direct preset start produced.
    @MainActor func testPresetStartSetupCarriesTheWorkingCopy() throws {
        let (store, directory, _) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        store.startCook(title: "Chicken", firstDish: "Chicken")
        let source = store.library.cooks.last!.id, dish = store.dishRecords(source)[0].id
        store.finishCook(source)
        let recipeId = try store.saveRecipe(store.extractRecipe(cookId: source, dishId: dish), sourceCookId: source, sourceDishId: dish)
        let revision = store.recipe(recipeId)!["currentRevisionId"]!.stringValue!
        let meal = try store.startRecipeMeal(title: "Working meal", revisionIds: [revision])
        let mealDish = store.dishRecords(meal)[0].id
        var working = try XCTUnwrap(store.recipeAttempt(mealDish))
        working.description = "Try more Gouda"
        try store.updateRecipeAttempt(mealDish, snapshot: working)
        try store.saveMealPreset(meal, name: "Favorites")
        let presetId = store.library.mealPresets.first!.objectValue!["id"]!.stringValue!
        let cooksBefore = store.library.cooks.count
        let setup = try store.presetStartSetup(presetId)
        XCTAssertEqual(store.library.cooks.count, cooksBefore)
        XCTAssertEqual(setup.title, "Favorites")
        XCTAssertEqual(setup.sourceLabel, "From a saved meal combination · Favorites")
        XCTAssertEqual(setup.dishes.map(\.revisionId), [revision])
        XCTAssertNotNil(setup.dishes.first?.snapshotJSON)
        let cookId = try store.startCook(setup: setup)
        let newDish = try XCTUnwrap(store.dishRecords(cookId).first)
        XCTAssertEqual(try store.recipeAttempt(newDish.id)?.description, "Try more Gouda")
    }

    /// The kept draft survives a relaunch (a new store over the same
    /// defaults), compares by fields — not identity — and clears fully.
    @MainActor func testSetupDraftPersistsAcrossStoresAndClears() throws {
        let (store, directory, defaults) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        var setup = CookSetupDraft.blank(temperatureUnit: "°F", weightUnit: "lb")
        setup.title = "Kept setup"
        setup.dishes = [CookSetupDish(name: "Ribs", foodType: "Pork")]
        XCTAssertNil(store.setupDraft)
        store.keepSetupDraft(setup)
        let reopened = CookStore(directory: directory, defaults: defaults)
        XCTAssertEqual(reopened.setupDraft, setup)
        XCTAssertEqual(reopened.setupDraft?.title, "Kept setup")
        reopened.clearSetupDraft()
        XCTAssertNil(CookStore(directory: directory, defaults: defaults).setupDraft)
    }

    /// An untouched setup is not "changed": the sheet's dirty check rests
    /// on this, so merely opening the review never prompts or drafts.
    @MainActor func testSetupEqualityIgnoresIdentityOnly() {
        let setup = CookSetupDraft.blank(temperatureUnit: "°F", weightUnit: "lb")
        var copy = setup
        copy.id = UUID().uuidString.lowercased()
        XCTAssertEqual(copy, setup)
        copy.title = "Changed"
        XCTAssertNotEqual(copy, setup)
    }
}
