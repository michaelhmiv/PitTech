import XCTest
import UIKit
@testable import PitTech

@MainActor final class CookExperienceTests: XCTestCase {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
    private func jpeg() -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).jpegData(withCompressionQuality: 1) { context in UIColor.orange.setFill(); context.fill(CGRect(x: 0, y: 0, width: 4, height: 4)) }
    }
    func testAtomicNoteEditKeepsIdentityTimeReadingsAndPhotosTogether() async throws {
        let store = CookStore(directory: try folder())
        store.startCook(title: "Ribs", firstDish: "Rack")
        let cook = try XCTUnwrap(store.activeCooks.first), dish = try XCTUnwrap(store.dishRecords(cook.id).first)
        var draft = CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "temperature"), description: "Bark set", temperatures: [NoteTemperatureDraft(valueText: "165", dishId: dish.id)], photos: [try store.stagePhoto(jpeg()), try store.stagePhoto(jpeg())])
        try store.saveNote(draft)
        let created = store.library.events.first?.objectValue?["createdAtUtcMillis"]
        draft.description = "Bark set; rotate the rack"; draft.temperatures[0].valueText = "166"; draft.photos[0].caption = "Before rotating"
        try store.saveNote(draft)
        XCTAssertEqual(store.events(for: cook.id).count, 1)
        XCTAssertEqual(store.events(for: cook.id)[0].eventType, "note")
        XCTAssertEqual(store.events(for: cook.id)[0].id, draft.id)
        XCTAssertEqual(store.events(for: cook.id)[0].occurredAt.timeIntervalSince1970 * 1_000, draft.occurredAt, accuracy: 0.1)
        XCTAssertEqual(store.library.events.first?.objectValue?["createdAtUtcMillis"], created)
        XCTAssertEqual(store.readings(for: cook.id).count, 1)
        XCTAssertEqual(store.readings(for: cook.id)[0].value, 166)
        XCTAssertEqual(store.library.photos.count, 2)
        XCTAssertEqual(store.library.logReadingLinks.count, 1)
        XCTAssertTrue(store.noteDrafts.isEmpty)
        try store.deleteNote(draft.id)
        XCTAssertTrue(store.readings(for: cook.id).isEmpty)
        XCTAssertEqual(store.library.photos.count, 2)
        try store.undoDeleteNote()
        XCTAssertEqual(store.events(for: cook.id).first?.id, draft.id)
        XCTAssertEqual(store.library.logReadingLinks.count, 1)
        try store.saveNote(CookNoteDraft(cookId: cook.id, temperatures: [NoteTemperatureDraft(valueText: "167", dishId: dish.id)]))
        XCTAssertEqual(Set(store.readings(for: cook.id).map(\.probeId)).count, 1)
    }
    func testDraftSurvivesRelaunchAndMissingPhotoRetryDoesNotDuplicateEntry() async throws {
        let directory = try folder(), store = CookStore(directory: directory)
        store.startCook(title: "Preparation", firstDish: "Brisket")
        let id = try XCTUnwrap(store.activeCooks.first?.id), photo = try store.stagePhoto(jpeg())
        let draft = CookNoteDraft(cookId: id, description: "Trimmed", photos: [photo])
        try store.persistDraft(draft)
        let restored = CookStore(directory: directory)
        XCTAssertEqual(restored.noteDrafts.first, draft)
        XCTAssertEqual(restored.activeCooks.first?.id, id)
        XCTAssertEqual(restored.phases(id).first?.phase, "prep")
        let bytes = try Data(contentsOf: restored.photoURL(photo.path))
        try FileManager.default.removeItem(at: restored.photoURL(photo.path))
        XCTAssertThrowsError(try restored.saveNote(draft))
        XCTAssertTrue(restored.events(for: id).isEmpty)
        XCTAssertEqual(restored.noteDrafts.first?.id, draft.id)
        try bytes.write(to: restored.photoURL(photo.path), options: .atomic)
        try restored.saveNote(draft); try restored.saveNote(draft)
        XCTAssertEqual(restored.events(for: id).count, 1)
        XCTAssertEqual(restored.library.photos.count, 1)
    }
    func testPhaseActionsValidateOrderAndNeverPausePhysicalClock() async throws {
        let store = CookStore(directory: try folder())
        let foodOn = Date.now.addingTimeInterval(-3_600)
        store.startCook(title: "Already cooking", firstDish: "Pork", alreadyCooking: true, foodOn: foodOn)
        let id = try XCTUnwrap(store.activeCooks.first?.id)
        XCTAssertEqual(store.phases(id, now: foodOn.addingTimeInterval(600)).first?.cooking ?? 0, 600_000, accuracy: 1)
        let rest = CookNoteDraft(cookId: id, metadata: CookNoteMetadata(category: "action", action: "rest_start"), title: "Rest", occurredAt: foodOn.addingTimeInterval(1_200).timeIntervalSince1970 * 1_000)
        try store.saveNote(rest)
        try store.saveNote(CookNoteDraft(cookId: id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Reheat", occurredAt: foodOn.addingTimeInterval(1_800).timeIntervalSince1970 * 1_000))
        let phase = try XCTUnwrap(store.phases(id, now: foodOn.addingTimeInterval(2_400)).first)
        XCTAssertEqual(phase.cooking, 1_800_000, accuracy: 1); XCTAssertEqual(phase.rest, 600_000, accuracy: 1)
        var bad = rest; bad.metadata.action = nil
        XCTAssertThrowsError(try store.saveNote(bad))
        let first = try XCTUnwrap(store.library.actions(for: id).filter { $0.action == "food_on" }.min(by: { $0.at < $1.at }))
        XCTAssertThrowsError(try store.deleteNote(first.id))
        let reloaded = CookStore(directory: store.storageURL.deletingLastPathComponent())
        XCTAssertEqual(reloaded.activeCooks.first?.startedAtUtcMillis, store.activeCooks.first?.startedAtUtcMillis)
        XCTAssertEqual(reloaded.timingMode(id), "explicit_phases")
    }
    func testDifferentDishesHaveIndependentPhasesAndLegacyTimingRequiresAdoption() async throws {
        let store = CookStore(directory: try folder())
        store.startCook(title: "Two dishes", firstDish: "Ribs", alreadyCooking: true, foodOn: Date.now.addingTimeInterval(-60)); let id = try XCTUnwrap(store.activeCooks.first?.id)
        store.addDish(cookId: id, name: "Chicken")
        XCTAssertEqual(Set(store.phases(id).map(\.phase)), ["prep", "cooking"])
        let original = try XCTUnwrap(store.activeCooks.first?.startedAtUtcMillis)
        store.library.lifecycles = []; store.save()
        XCTAssertEqual(store.timingMode(id), "legacy_preserved")
        try store.adoptTiming(id, foodOn: nil)
        XCTAssertEqual(store.timingMode(id), "explicit_phases")
        XCTAssertEqual(store.activeCooks.first?.startedAtUtcMillis, original)
    }
    func testArchiveRoundTripPreservesPhotosLinksPhaseAndChecklistWithCanonicalUnits() async throws {
        let store = CookStore(directory: try folder()); store.startCook(title: "Archive", firstDish: "Ribs")
        let id = try XCTUnwrap(store.activeCooks.first?.id)
        let note = CookNoteDraft(cookId: id, metadata: CookNoteMetadata(category: "photo", action: "food_on"), title: "On the grill", temperatures: [NoteTemperatureDraft(valueText: "40")], photos: [try store.stagePhoto(jpeg())])
        try store.saveNote(note); store.createPrepChecklist(id)
        var checklist = try XCTUnwrap(store.prepChecklist(id)); checklist.items[0].done = true; try store.savePrepChecklist(id, checklist)
        store.startCook(title: "Reference", firstDish: "Prior ribs")
        let sourceId = try XCTUnwrap(store.library.cooks.last?.id), sourceDish = try XCTUnwrap(store.dishRecords(sourceId).first?.id), currentDish = try XCTUnwrap(store.dishRecords(id).first?.id)
        try store.saveReference(id, reference: NativeCookReference(dishId: currentDish, sourceCookId: sourceId, sourceDishId: sourceDish))
        store.library.readings = store.library.readings.map { row in guard var o = row.objectValue else { return row }; o["unit"] = .string("F"); return .object(o) }
        let archive = try store.exportArchive()
        XCTAssertEqual(try CookStore.previewArchive(archive).version, 6)
        let destination = CookStore(directory: try folder()); XCTAssertEqual(try destination.importArchive(archive).imported, 2)
        XCTAssertEqual(destination.library.events, store.library.events)
        XCTAssertEqual(destination.library.logReadingLinks, store.library.logReadingLinks)
        XCTAssertEqual(destination.library.readings.first?.objectValue?["unit"]?.stringValue, "°F")
        XCTAssertEqual(destination.timingMode(id), "explicit_phases")
        XCTAssertEqual(destination.prepChecklist(id)?.items[0].done, true)
        XCTAssertEqual(destination.savedReference(id, dishId: currentDish)?.sourceDishId, sourceDish)
        XCTAssertEqual(try Data(contentsOf: destination.photoURL(note.photos[0].path)), try Data(contentsOf: store.photoURL(note.photos[0].path)))
        XCTAssertEqual(try destination.importArchive(archive).imported, 0)
    }
    func testSaveRejectsForeignReadingIdentityWithoutChangingEitherCook() async throws {
        let store = CookStore(directory: try folder()); store.startCook(title: "First", firstDish: "Ribs")
        let first = try XCTUnwrap(store.activeCooks.first?.id)
        let draft = CookNoteDraft(cookId: first, temperatures: [NoteTemperatureDraft(valueText: "165")]); try store.saveNote(draft)
        store.startCook(title: "Second", firstDish: "Chicken"); let second = try XCTUnwrap(store.library.cooks.last?.id)
        XCTAssertNotEqual(first, second)
        XCTAssertThrowsError(try store.saveNote(CookNoteDraft(cookId: second, temperatures: draft.temperatures)))
        XCTAssertEqual(store.readings(for: first).count, 1); XCTAssertTrue(store.readings(for: second).isEmpty)
    }
    func testChartKeepsIdentityUnitsGapsAndExtremaWithoutInventingTrends() async throws {
        func sample(_ id: String, _ value: Double, _ at: Double, unit: String = "°F", probe: String = "p", source: String = "controller_cloud") -> TemperatureSample {
            TemperatureSample(json: .object(["id": .string(id), "cookId": .string("cook"), "probeId": .string(probe), "probeName": .string("Same name"), "measurementType": .string("food"), "value": .number(value), "unit": .string(unit), "measuredAtUtcMillis": .number(at), "source": .string(source), "samplingIntervalMillis": .number(300_000)]))!
        }
        let a = sample("a", 100, 0), b = sample("b", 40, 300_000, unit: "°C"), c = sample("c", 108, 600_000)
        XCTAssertEqual(CookChartMath.slope([a, b, c], unit: "F") ?? 0, 0.8, accuracy: 0.0001)
        XCTAssertEqual(CookChartMath.segments([a, b, c, sample("d", 110, 1_800_000)]).count, 2)
        XCTAssertNotEqual(a.seriesId, sample("other", 100, 0, probe: "other").seriesId)
        XCTAssertNil(CookChartMath.slope([sample("m1", 100, 0, source: "manual"), sample("m2", 110, 1_800_000, source: "manual"), sample("m3", 120, 3_600_000, source: "manual")], unit: "F"))
        let rows: [TemperatureSample] = (0...600).map { index in
            let value: Double = index == 302 ? 150.0 : 200.0
            let unit: String = index == 302 ? "°C" : "°F"
            let timestamp: Double = Double(index) * 1_000.0
            return sample(String(index), value, timestamp, unit: unit)
        }
        XCTAssertTrue(CookChartMath.reduced(rows).contains { $0.id == "302" })
    }
}
