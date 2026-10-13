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
}
#else
import SwiftUI
struct ScreenshotTextSize: ViewModifier { func body(content: Content) -> some View { content } }
#endif
