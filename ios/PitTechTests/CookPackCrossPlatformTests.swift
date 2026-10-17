import XCTest
import UIKit
@testable import PitTech

@MainActor final class CookPackCrossPlatformTests: XCTestCase {
    private func fixture(_ kind: String) throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: "android-cook-pack-\(kind)-v1", withExtension: "pittech"), "Use the actual Android emulator writer output.")
    }

    func testActualAndroidRecipeAndCollectionPreserveCelsiusAndIndependentMedia() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        for (kind, count) in [("recipe", 1), ("collection", 3)] {
            let bytes = try Data(contentsOf: fixture(kind))
            let preview = try CookStore.previewArchive(bytes)
            XCTAssertEqual(preview.recipes, count); XCTAssertEqual(preview.cooks, 0)
            XCTAssertEqual(preview.cookPack?.objectValue?["kind"]?.stringValue, kind)
            let directory = folder.appendingPathComponent(kind), store = CookStore(directory: directory)
            _ = try store.importArchive(bytes); _ = try store.importArchive(bytes)
            XCTAssertEqual(store.library.recipes.count, count)
            XCTAssertEqual(store.library.recipeAssets.count, 1); XCTAssertEqual(store.library.companionPhotos.count, 1)
            XCTAssertTrue(store.library.cooks.isEmpty); XCTAssertTrue(store.library.dishes.isEmpty)
            XCTAssertTrue(store.library.devices.isEmpty); XCTAssertTrue(store.library.probes.isEmpty)
            XCTAssertTrue(store.library.recordings.isEmpty); XCTAssertTrue(store.library.reminderRules.isEmpty)
            let record = try XCTUnwrap(store.library.companionRecords.first?.objectValue)
            let document = try NativeSharingDocument.decode(try XCTUnwrap(record["payload"]))
            XCTAssertNil(document.sourceCookId)
            let mac = try XCTUnwrap(document.entries.first { (try? $0.snapshot.name) == "Mac" })
            XCTAssertNil(mac.sourceDishId); XCTAssertTrue(mac.includeStory)
            let readings = try XCTUnwrap(mac.story.objectValue?["readings"]?.arrayValue)
            XCTAssertEqual(readings.count, 1)
            let reading = try XCTUnwrap(readings.first?.objectValue)
            XCTAssertEqual(reading["value"]?.numberValue, 85); XCTAssertEqual(reading["unit"]?.stringValue, "°C")
            XCTAssertEqual(reading["probeName"]?.stringValue, "Android food")
            XCTAssertNil(reading["sourceDeviceId"])
            let snapshot = try mac.snapshot
            XCTAssertTrue(snapshot.observations.contains { $0.objectValue?["title"]?.stringValue == "Android pasta" })
            let recipePhoto = try XCTUnwrap(store.library.recipeAssets.first?.objectValue)
            let referencePhoto = try XCTUnwrap(store.library.companionPhotos.first?.objectValue)
            XCTAssertNotEqual(recipePhoto["id"], referencePhoto["id"])
            let original = try Data(contentsOf: store.photoURL(try XCTUnwrap(recipePhoto["archivePath"]?.stringValue)))
            XCTAssertEqual(original, try Data(contentsOf: store.photoURL(try XCTUnwrap(referencePhoto["archivePath"]?.stringValue))))
            try NativeCookPackPhoto.validatePublic(original)
            let image = try XCTUnwrap(UIImage(data: original)?.cgImage)
            XCTAssertEqual(image.width, 8); XCTAssertEqual(image.height, 8)
            let reopened = CookStore(directory: directory)
            XCTAssertEqual(reopened.library.recipes.count, count); XCTAssertEqual(reopened.library.companionPhotos.count, 1)
            if kind == "recipe" {
                let encoded = try recipeJSON(reopened.library)
                XCTAssertFalse(encoded.contains("Chicken")); XCTAssertFalse(encoded.contains("Pork"))
            }
        }
    }

    func testActualAndroidCollectionPartialThenWholeImportIsIdempotent() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        await store.receiveArchive(at: try fixture("collection"))
        let intake = try XCTUnwrap(store.archiveIntake)
        XCTAssertNil(intake.library.preferences)
        let mac = try XCTUnwrap(intake.library.recipes.first { $0.objectValue?["name"]?.stringValue == "Mac" }?.objectValue?["currentRevisionId"]?.stringValue)
        let all = Set(intake.library.recipes.compactMap { $0.objectValue?["currentRevisionId"]?.stringValue })
        try await store.importSelectedRecipes(intake, selected: [mac])
        try await store.importSelectedRecipes(intake, selected: [mac])
        XCTAssertEqual(store.library.recipes.count, 1); XCTAssertEqual(store.library.recipeAssets.count, 1)
        XCTAssertEqual(store.library.companionPhotos.count, 1)
        try await store.importSelectedRecipes(intake, selected: all)
        try await store.importSelectedRecipes(intake, selected: all)
        XCTAssertEqual(store.library.recipes.count, 3); XCTAssertEqual(store.library.recipeAssets.count, 1)
        XCTAssertEqual(store.library.companionRecords.count, 2); XCTAssertEqual(store.library.companionPhotos.count, 2)
        XCTAssertTrue(store.library.cooks.isEmpty); XCTAssertTrue(store.library.reminderRules.isEmpty)
        let staged = intake.url; store.discardArchiveIntake()
        XCTAssertFalse(FileManager.default.fileExists(atPath: staged.path))
    }
    func testActualAndroidRecordedCookPreservesDatesCelsiusAndHistoryChoice() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        store.startCook(title: "Keep this meal active", firstDish: "")
        await store.receiveArchive(at: try fixture("cook"))
        let intake = try XCTUnwrap(store.archiveIntake)
        XCTAssertEqual(intake.preview.cooks, 1); XCTAssertEqual(intake.preview.dishes, 1)
        try await store.importRecordedCook(intake); try await store.importRecordedCook(intake)
        XCTAssertEqual(store.activeCooks.count, 1); XCTAssertEqual(store.completedCooks.count, 1)
        XCTAssertTrue(store.library.recipes.isEmpty); XCTAssertTrue(store.library.reminderRules.isEmpty)
        XCTAssertTrue(store.library.devices.isEmpty); XCTAssertTrue(store.library.recordings.isEmpty)
        let cook = try XCTUnwrap(store.completedCooks.first)
        XCTAssertEqual(cook.startedAtUtcMillis, 1791838086183); XCTAssertEqual(cook.endedAtUtcMillis, 1792013580811)
        XCTAssertEqual(store.dishRecords(cook.id).first?.name, "Mac")
        let reading = try XCTUnwrap(store.library.readings.first?.objectValue)
        XCTAssertEqual(reading["value"]?.numberValue, 85); XCTAssertEqual(reading["unit"]?.stringValue, "°C")
        XCTAssertEqual(reading["measuredAtUtcMillis"]?.numberValue, 1792012980723)
        XCTAssertEqual(store.library.probes.count, 1); XCTAssertEqual(store.library.photos.count, 1)
        let photo = try XCTUnwrap(store.library.photos.first?.objectValue?["archivePath"]?.stringValue)
        let image = try XCTUnwrap(UIImage(data: Data(contentsOf: store.photoURL(photo)))?.cgImage)
        XCTAssertEqual(image.width, 8); XCTAssertEqual(image.height, 8)
        try NativeCookPackPhoto.validatePublic(Data(contentsOf: store.photoURL(photo)))
        store.discardArchiveIntake()
    }
}
