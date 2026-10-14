import XCTest
import UIKit
@testable import PitTech

final class CrossPlatformArchiveTests: XCTestCase {
    @MainActor func testActualAndroidWriterPreservesNotesCelsiusMediaSourcesAndInactiveReminders() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "android-guide-meal-v10", withExtension: "zip"), "The fixture must be collected from the successful Android emulator writer.")
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = CookStore(directory: folder)
        _ = try store.importArchive(Data(contentsOf: url))
        XCTAssertEqual(store.library.recipes.count, 4)
        let note = try XCTUnwrap(store.library.events.compactMap(\.objectValue).first { $0["title"]?.stringValue == "Android pasta" })
        let metadata = try JSONDecoder().decode(CookNoteMetadata.self, from: Data(try XCTUnwrap(note["noteMetadataJson"]?.stringValue).utf8))
        XCTAssertEqual(metadata.method, "stovetop"); XCTAssertEqual(metadata.durationMillis, 480000)
        XCTAssertEqual(metadata.ingredients?.first?.amount, "4.00"); XCTAssertEqual(metadata.ingredients?.first?.unit, "oz")
        let reading = try XCTUnwrap(store.library.readings.compactMap(\.objectValue).first { $0["probeName"]?.stringValue == "Android food" })
        XCTAssertEqual(reading["unit"]?.stringValue, "°C"); XCTAssertEqual(reading["value"]?.numberValue, 85)
        let header = try XCTUnwrap(store.library.recipes.compactMap(\.objectValue).first { $0["name"]?.stringValue == "Android mac variation" })
        let revisionId = try XCTUnwrap(header["currentRevisionId"]?.stringValue)
        let snapshot = try store.recipeSnapshot(revisionId)
        XCTAssertTrue(snapshot.observations.contains { $0.objectValue?["sourceEventId"] == note["id"] })
        XCTAssertEqual(snapshot.targets.first?.objectValue?["value"]?.numberValue, 0)
        XCTAssertEqual(snapshot.targets.first?.objectValue?["unit"]?.stringValue, "°C")
        let asset = try XCTUnwrap(store.recipeAssets(revisionId).first?.objectValue)
        let original = try XCTUnwrap(store.library.photos.compactMap(\.objectValue).first { $0["id"] == asset["sourcePhotoId"] })
        let assetPath = try XCTUnwrap(asset["archivePath"]?.stringValue), sourcePath = try XCTUnwrap(original["archivePath"]?.stringValue)
        XCTAssertNotEqual(assetPath, sourcePath)
        XCTAssertEqual(try Data(contentsOf: store.photoURL(assetPath)), try Data(contentsOf: store.photoURL(sourcePath)))
        XCTAssertNotNil(store.photoPreview(assetPath))
        let current = try XCTUnwrap(store.library.cooks.first { $0.title == "Portable new meal" })
        XCTAssertFalse(store.library.actions(for: current.id).contains { CookPhaseEngine.actions.contains($0.action) })
        let linked = store.library.dishRecipeSources.compactMap(\.objectValue).filter { $0["recipeRevisionId"]?.stringValue == revisionId }
        XCTAssertEqual(linked.count, 1)
        XCTAssertEqual(store.library.reminderRules.count, 2)
        XCTAssertTrue(store.library.reminderRules.allSatisfy { $0.objectValue?["enabled"]?.boolValue == false && $0.objectValue?["status"]?.stringValue == "inactive" })
        XCTAssertTrue(store.library.reminders.allSatisfy { $0.objectValue?["status"]?.stringValue != "pending" })
        let state = store.guideState(current.id)
        XCTAssertTrue(state.visible);XCTAssertEqual(state.dishIds?.count,2)
        XCTAssertEqual(store.guides(current.id).count,3)
        let pinned = try XCTUnwrap(store.guides(current.id).first { $0.guide.story.objectValue?["readings"]?.arrayValue?.isEmpty == false })
        let sample = try XCTUnwrap(pinned.guide.story.objectValue?["readings"]?.arrayValue?.first?.objectValue)
        XCTAssertEqual(sample["unit"]?.stringValue,"°C");XCTAssertEqual(sample["value"]?.numberValue,85)
        try store.library.validateCookExperience()
        let restored = CookStore(directory: folder.appendingPathComponent("roundtrip"))
        let bytes = try store.exportArchive(); _ = try restored.importArchive(bytes); _ = try restored.importArchive(bytes)
        XCTAssertEqual(restored.guideState(current.id),state)
        XCTAssertEqual(restored.library.recipes.count, 4)
        XCTAssertEqual(restored.library.reminderRules.count, 2)
        XCTAssertEqual(restored.library.photos.count, store.library.photos.count)
        XCTAssertEqual(try restored.recipeSnapshot(revisionId).observations, snapshot.observations)
    }
}
