#if DEBUG
import SwiftUI
import Foundation

struct ScreenshotTextSize: ViewModifier {
    func body(content: Content) -> some View {
        if CommandLine.arguments.contains("--screenshot-large-text") { content.dynamicTypeSize(.accessibility3) } else { content }
    }
}
@MainActor extension CookStore {
    func prepareUIFlowFixture() {
        guard CommandLine.arguments.contains("--ui-test-reset") else { return }
        library = PitTechLibrary(); noteDrafts = []
        if CommandLine.arguments.contains("--ui-test-pack") { prepareIncomingPackFixture(); return }
        if !CommandLine.arguments.contains("--ui-test-empty") {
            for (name, category) in [("Chicken", "Poultry"), ("Mac and cheese", "Pasta"), ("Pork", "Pork")] {
                startCook(title: "Previous " + name, firstDish: name)
                guard let cook = activeCooks.first, let dish = dishRecords(cook.id).first else { continue }
                if let index = library.dishes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == dish.id }), var value = library.dishes[index].objectValue { value["foodType"] = .string(category); library.dishes[index] = .object(value) }
                let anchor = Double(Int64(Date.now.addingTimeInterval(-5 * 3600).timeIntervalSince1970 * 1000))
                do {
                    if category == "Pasta" { try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(method: "stovetop", durationMillis: 480000), title: "Boil macaroni", occurredAt: anchor - 600000)) }
                    try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "food_on"), title: "Food on", occurredAt: anchor))
                    try saveNote(CookNoteDraft(cookId: cook.id, dishId: dish.id, metadata: CookNoteMetadata(category: "action", action: "wrap"), title: "Wrapped", occurredAt: anchor + 12000000))
                    finishCook(cook.id)
                    let snapshot = try extractRecipe(cookId: cook.id, dishId: dish.id)
                    _ = try saveRecipe(snapshot, sourceCookId: cook.id, sourceDishId: dish.id)
                } catch { assertionFailure("UI fixture failed: " + error.localizedDescription) }
            }
        }
        save()
    }
    private func prepareIncomingPackFixture() {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        Task { @MainActor in
            defer { try? FileManager.default.removeItem(at: folder) }
            do {
                let source = CookStore(directory: folder)
                source.startCook(title: "Pork and potatoes", firstDish: "Pork")
                let cook = source.activeCooks[0].id; source.addDish(cookId: cook, name: "Potatoes")
                for dish in source.dishRecords(cook) {
                    let image = UIGraphicsImageRenderer(size: CGSize(width: 80, height: 60)).image { context in
                        (dish.name == "Pork" ? UIColor.brown : UIColor.systemYellow).setFill(); context.fill(CGRect(x: 0, y: 0, width: 80, height: 60))
                    }
                    let photo = try source.stagePhoto(image.jpegData(compressionQuality: 0.8)!)
                    try source.saveNote(CookNoteDraft(cookId: cook, dishId: dish.id, title: "Recorded result", photos: [photo]))
                }
                source.finishCook(cook)
                let id = try source.createSharingDraft(cookId: cook, dishIds: Set(source.dishRecords(cook).map(\.id)))
                var document = try source.sharingDocument(id)
                for index in document.entries.indices {
                    document.entries[index].includeStory = true
                    document.entries[index].photoIds = source.sharingPhotos(id).filter { $0["action"]?.stringValue == document.entries[index].id }.compactMap { $0["id"]?.stringValue }
                }
                try source.saveSharingDraft(id, document: document, expectedUpdated: source.sharingRecord(id)!["updated"]!.numberValue!)
                let file = folder.appendingPathComponent("preview.pittech")
                let data = try await source.exportCookPackAsync(id)
                try data.write(to: file)
                await receiveArchive(at: file)
            } catch { assertionFailure("Cook Pack UI fixture failed: " + error.localizedDescription) }
        }
    }
}
#else
import SwiftUI
struct ScreenshotTextSize: ViewModifier { func body(content: Content) -> some View { content } }
#endif
