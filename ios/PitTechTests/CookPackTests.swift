import XCTest
import UIKit
import ImageIO
import ZIPFoundation
import UniformTypeIdentifiers
@testable import PitTech

@MainActor final class CookPackTests: XCTestCase {
    private func locatedPhoto() throws -> Data {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 4), format: format).image { UIColor.orange.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 8, height: 4)) }
        let data = NSMutableData()
        let target = try XCTUnwrap(CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(target, try XCTUnwrap(image.cgImage), [kCGImagePropertyOrientation: 6, kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 32.0, kCGImagePropertyGPSLatitudeRef: "N", kCGImagePropertyGPSLongitude: 80.0, kCGImagePropertyGPSLongitudeRef: "W"]] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(target)); return data as Data
    }
    func testSelectedRecipePackOwnsMediaAndMeasuredGuideWithoutUnrelatedHistory() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = CookStore(directory: folder)
        source.startCook(title: "Pork and potatoes", firstDish: "Pork"); let cook = source.activeCooks[0].id
        source.addDish(cookId: cook, name: "Potatoes"); let pork = source.dishRecords(cook).first { $0.name == "Pork" }!.id
        let anchor = Double(Int64(Date.now.addingTimeInterval(-7200).timeIntervalSince1970 * 1000))
        try source.saveNote(CookNoteDraft(cookId: cook, dishId: pork, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: anchor))
        let original = try locatedPhoto(), photo = try source.stagePhoto(original)
        try source.saveNote(CookNoteDraft(cookId: cook, dishId: pork, metadata: CookNoteMetadata(category: "action", action: "wrap"), title: "Wrapped", occurredAt: anchor + 3600000, temperatures: [NoteTemperatureDraft(probeName: "Pork probe", valueText: "74", unit: "°C", measuredAt: anchor + 3600000, dishId: pork)], photos: [photo]))
        source.finishCook(cook)
        let id = try source.createSharingDraft(cookId: cook, dishIds: Set(source.dishRecords(cook).map(\.id)))
        var draft = try source.sharingDocument(id)
        let asset = try XCTUnwrap(source.sharingPhotos(id).first)
        for index in draft.entries.indices {
            draft.entries[index].included = draft.entries[index].sourceDishId == pork
            if draft.entries[index].included { draft.entries[index].includeStory = true; draft.entries[index].photoIds = [asset["id"]!.stringValue!] }
        }
        draft.title = "Pepper pork"
        try source.saveSharingDraft(id, document: draft, expectedUpdated: source.sharingRecord(id)!["updated"]!.numberValue!)
        let history = source.library.events, pack = try source.exportCookPack(id)
        let preview = try CookStore.previewArchive(pack)
        XCTAssertEqual(preview.recipes, 1); XCTAssertEqual(preview.cooks, 0); XCTAssertEqual(preview.cookPack?.objectValue?["kind"]?.stringValue, "recipe")
        let recipient = CookStore(directory: folder.appendingPathComponent("recipient")); _ = try recipient.importArchive(pack)
        let restored = try recipient.recipeSnapshot(recipient.library.recipes[0].objectValue!["currentRevisionId"]!.stringValue!)
        XCTAssertEqual(restored.observations.count, 2); XCTAssertEqual(restored.observations.last?.objectValue?["offsetMillis"]?.numberValue, 3600000)
        let reference = try NativeSharingDocument.decode(recipient.library.companionRecords[0].objectValue!["payload"]!)
        XCTAssertEqual(reference.entries[0].story.objectValue?["readings"]?.arrayValue?.first?.objectValue?["unit"]?.stringValue, "°C")
        XCTAssertEqual(reference.entries[0].story.objectValue?["readings"]?.arrayValue?.first?.objectValue?["value"]?.numberValue, 74)
        XCTAssertTrue(recipient.library.cooks.isEmpty); XCTAssertTrue(recipient.library.devices.isEmpty)
        XCTAssertEqual(source.library.events, history)
        let json = try recipeJSON(recipient.library)
        XCTAssertFalse(json.contains("Potatoes")); XCTAssertFalse(json.contains(cook)); XCTAssertFalse(json.contains(pork))
        for row in recipient.library.recipeAssets + recipient.library.companionPhotos {
            let bytes = try Data(contentsOf: recipient.photoURL(row.objectValue!["archivePath"]!.stringValue!))
            try NativeCookPackPhoto.validatePublic(bytes)
            let image = try XCTUnwrap(CGImageSourceCreateWithData(bytes as CFData, nil))
            let info = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [String: Any])
            XCTAssertNil(info[kCGImagePropertyGPSDictionary as String]); XCTAssertEqual(info[kCGImagePropertyPixelWidth as String] as? Int, 4); XCTAssertEqual(info[kCGImagePropertyPixelHeight as String] as? Int, 8)
        }
        XCTAssertEqual(try Data(contentsOf: source.photoURL(asset["archivePath"]!.stringValue!)), original)
        _ = try recipient.importArchive(pack)
        XCTAssertEqual(recipient.library.recipes.count, 1); XCTAssertEqual(recipient.library.recipeAssets.count, 1)
        let retained = CookStore(directory: folder.appendingPathComponent("recipient"))
        XCTAssertEqual(retained.library.companionPhotos.count, 1)
        var withoutStory = draft
        for index in withoutStory.entries.indices { withoutStory.entries[index].includeStory = false }
        try source.saveSharingDraft(id, document: withoutStory, expectedUpdated: source.sharingRecord(id)!["updated"]!.numberValue!)
        let instructionsOnly = CookStore(directory: folder.appendingPathComponent("instructions")); _ = try instructionsOnly.importArchive(source.exportCookPack(id))
        let instructionsRevision = instructionsOnly.library.recipeRevisions[0].objectValue!["id"]!.stringValue!
        XCTAssertTrue(try instructionsOnly.recipeSnapshot(instructionsRevision).observations.isEmpty)
        XCTAssertEqual(try instructionsOnly.recipeSnapshot(instructionsRevision).instructions.count, 2)
    }
    func testPublicImageRejectsLocationMetadataAndNonImageBytes() throws {
        XCTAssertThrowsError(try NativeCookPackPhoto.validatePublic(locatedPhoto()))
        XCTAssertThrowsError(try NativeCookPackPhoto.derivative(Data("not a photograph".utf8)))
    }

    func testPartialThenWholeCollectionOwnsPhotosAndRepeatedImportsStayIdempotent() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = CookStore(directory: folder)
        source.startCook(title: "Pork and potatoes", firstDish: "Pork")
        let cook = source.activeCooks[0].id
        source.addDish(cookId: cook, name: "Potatoes")
        for dish in source.dishRecords(cook) {
            let photo = try source.stagePhoto(locatedPhoto())
            try source.saveNote(CookNoteDraft(cookId: cook, dishId: dish.id, title: "\(dish.name) result", photos: [photo]))
        }
        source.finishCook(cook)
        let id = try source.createSharingDraft(cookId: cook, dishIds: Set(source.dishRecords(cook).map(\.id)))
        var document = try source.sharingDocument(id)
        for index in document.entries.indices {
            document.entries[index].includeStory = true
            document.entries[index].photoIds = source.sharingPhotos(id).filter { $0["action"]?.stringValue == document.entries[index].id }.compactMap { $0["id"]?.stringValue }
        }
        try source.saveSharingDraft(id, document: document, expectedUpdated: source.sharingRecord(id)!["updated"]!.numberValue!)
        let pack = try await source.exportCookPackAsync(id), url = folder.appendingPathComponent("collection.pittech")
        try pack.write(to: url)
        let recipient = CookStore(directory: folder.appendingPathComponent("recipient"))
        await recipient.receiveArchive(at: url)
        let intake = try XCTUnwrap(recipient.archiveIntake)
        try FileManager.default.removeItem(at: url) // The provider's original can disappear after preview.
        let pork = try XCTUnwrap(intake.library.recipes.first { $0.objectValue?["name"]?.stringValue == "Pork" }?.objectValue?["currentRevisionId"]?.stringValue)
        let all = Set(intake.library.recipes.compactMap { $0.objectValue?["currentRevisionId"]?.stringValue })
        try await recipient.importSelectedRecipes(intake, selected: [pork])
        try await recipient.importSelectedRecipes(intake, selected: [pork])
        XCTAssertEqual(recipient.library.recipes.count, 1)
        XCTAssertEqual(recipient.library.recipeAssets.count, 1)
        XCTAssertEqual(recipient.library.companionPhotos.count, 1)
        try await recipient.importSelectedRecipes(intake, selected: all)
        try await recipient.importSelectedRecipes(intake, selected: all)
        XCTAssertEqual(recipient.library.recipes.count, 2)
        XCTAssertEqual(recipient.library.recipeAssets.count, 2)
        XCTAssertEqual(recipient.library.companionRecords.count, 2)
        XCTAssertEqual(recipient.library.companionPhotos.count, 3)
        XCTAssertTrue(recipient.library.cooks.isEmpty)
        XCTAssertNil(intake.library.preferences)
        XCTAssertThrowsError(try NativeCookPackSelection.library(intake.library, selected: []))
        XCTAssertThrowsError(try NativeCookPackSelection.library(intake.library, selected: [UUID().uuidString]))
        for photo in recipient.library.recipeAssets + recipient.library.companionPhotos {
            let path = try XCTUnwrap(photo.objectValue?["archivePath"]?.stringValue)
            try NativeCookPackPhoto.validatePublic(Data(contentsOf: recipient.photoURL(path)))
        }
        recipient.discardArchiveIntake()
        XCTAssertFalse(FileManager.default.fileExists(atPath: intake.url.path))
    }
}
