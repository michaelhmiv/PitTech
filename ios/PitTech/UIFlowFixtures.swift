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
        if CommandLine.arguments.contains("--screenshot-tour") { prepareScreenshotTourFixture(); return }
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

    /// Rich demo data for the App Store screenshot tour, seeded when the app is
    /// launched with --screenshot-tour (see AppStoreScreenshotTests). Builds an
    /// active brisket cook with a full temperature curve, two finished cooks,
    /// and saved recipes so every tab has something worth photographing.
    func prepareScreenshotTourFixture() {
        library = PitTechLibrary(); noteDrafts = []
        let now = Date.now
        func millis(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1_000) }
        func hoursAgo(_ hours: Double) -> Date { now.addingTimeInterval(-hours * 3_600) }
        func setFoodType(dishId: String, foodType: String) {
            if let index = library.dishes.firstIndex(where: { $0.objectValue?["id"]?.stringValue == dishId }), var value = library.dishes[index].objectValue {
                value["foodType"] = .string(foodType); library.dishes[index] = .object(value)
            }
        }
        func backdate(cookId: String, started: Date, ended: Date? = nil) {
            guard let index = library.cooks.firstIndex(where: { $0.id == cookId }) else { return }
            library.cooks[index].startedAtUtcMillis = millis(started)
            library.cooks[index].createdAtUtcMillis = millis(started)
            if let ended {
                library.cooks[index].endedAtUtcMillis = millis(ended)
                library.cooks[index].updatedAtUtcMillis = millis(ended)
            }
        }
        func note(cookId: String, dishId: String, metadata: CookNoteMetadata, title: String, at date: Date) {
            do { try saveNote(CookNoteDraft(cookId: cookId, dishId: dishId, metadata: metadata, title: title, occurredAt: Double(millis(date)))) }
            catch { assertionFailure("Tour fixture note failed: " + error.localizedDescription) }
        }
        func historicalCook(title: String, dish: String, foodType: String, startedHoursAgo: Double, durationHours: Double, midAction: String, midTitle: String) {
            let start = hoursAgo(startedHoursAgo)
            startCook(title: title, firstDish: dish, alreadyCooking: true, foodOn: start)
            guard let cook = activeCooks.first(where: { $0.title == title }), let dishRecord = dishRecords(cook.id).first else { return }
            setFoodType(dishId: dishRecord.id, foodType: foodType)
            note(cookId: cook.id, dishId: dishRecord.id, metadata: CookNoteMetadata(category: "action", action: midAction), title: midTitle, at: start.addingTimeInterval(durationHours * 0.6 * 3_600))
            note(cookId: cook.id, dishId: dishRecord.id, metadata: CookNoteMetadata(category: "general"), title: "Bark set, color looks right", at: start.addingTimeInterval(durationHours * 0.45 * 3_600))
            finishCook(cook.id)
            backdate(cookId: cook.id, started: start, ended: start.addingTimeInterval(durationHours * 3_600))
            do {
                let snapshot = try extractRecipe(cookId: cook.id, dishId: dishRecord.id)
                _ = try saveRecipe(snapshot, sourceCookId: cook.id, sourceDishId: dishRecord.id)
            } catch { assertionFailure("Tour fixture recipe failed: " + error.localizedDescription) }
        }
        historicalCook(title: "Sunday Pulled Pork", dish: "Pork shoulder", foodType: "pork", startedHoursAgo: 30, durationHours: 8, midAction: "wrap", midTitle: "Wrapped")
        historicalCook(title: "Game Day Chicken", dish: "Chicken thighs", foodType: "poultry", startedHoursAgo: 74, durationHours: 3.5, midAction: "spritz", midTitle: "Spritzed")

        // Active hero cook: a brisket 5h50m in, with a climbing probe curve.
        let start = now.addingTimeInterval(-350 * 60)
        startCook(title: "Weekend Brisket", firstDish: "Beef brisket", alreadyCooking: true, foodOn: start)
        guard let brisket = activeCooks.first(where: { $0.title == "Weekend Brisket" }), let brisketDish = dishRecords(brisket.id).first else { save(); return }
        backdate(cookId: brisket.id, started: start)
        if let index = library.cooks.firstIndex(where: { $0.id == brisket.id }) {
            library.cooks[index].smokerName = "Backyard smoker"
            library.cooks[index].initialSetpointValue = 250
            library.cooks[index].initialSetpointUnit = "F"
            library.cooks[index].notes = "Oak and hickory blend"
            library.cooks[index].fuelType = "pellets"
            library.cooks[index].woodOrPelletBlend = "Oak and hickory"
            library.cooks[index].outdoorTemperatureValue = 68
            library.cooks[index].outdoorTemperatureUnit = "F"
            library.cooks[index].weatherNotes = "Clear"
            library.cooks[index].windNotes = "Light breeze"
        }
        setFoodType(dishId: brisketDish.id, foodType: "beef")
        note(cookId: brisket.id, dishId: brisketDish.id, metadata: CookNoteMetadata(category: "action", action: "spritz"), title: "Spritzed with apple juice", at: start.addingTimeInterval(150 * 60))
        note(cookId: brisket.id, dishId: brisketDish.id, metadata: CookNoteMetadata(category: "action", action: "wrap"), title: "Wrapped in butcher paper", at: start.addingTimeInterval(200 * 60))
        note(cookId: brisket.id, dishId: brisketDish.id, metadata: CookNoteMetadata(category: "general"), title: "Bark is deep mahogany, probe slides in smooth", at: start.addingTimeInterval(290 * 60))
        func reading(probe: String, type: String, value: Double, minutesIn: Double) {
            let at = start.addingTimeInterval(minutesIn * 60)
            library.readings.append(.object([
                "id": .string(UUID().uuidString.lowercased()),
                "cookId": .string(brisket.id),
                "probeName": .string(probe),
                "measurementType": .string(type),
                "value": .number(value),
                "unit": .string("F"),
                "measuredAtUtcMillis": .number(Double(millis(at))),
                "timeZoneId": .string(TimeZone.current.identifier),
                "source": .string("manual"),
                "qualityStatus": .string("valid"),
                "recordedAtUtcMillis": .number(Double(millis(at))),
                "timestampBasis": .string("measurement")
            ]))
        }
        let foodCurve: [(Double, Double)] = [(0, 40), (25, 57), (50, 78), (75, 99), (100, 119), (125, 138), (150, 151), (175, 160), (200, 166), (225, 172), (250, 179), (275, 185), (300, 190), (325, 194), (350, 197)]
        for (minutes, value) in foodCurve { reading(probe: "Brisket", type: "food", value: value, minutesIn: minutes) }
        let pitCurve: [(Double, Double)] = [(0, 251), (25, 247), (50, 253), (75, 249), (100, 255), (125, 248), (150, 252), (175, 250), (200, 254), (225, 249), (250, 251), (275, 253), (300, 248), (325, 250), (350, 252)]
        for (minutes, value) in pitCurve { reading(probe: "Smoker", type: "pit", value: value, minutesIn: minutes) }
        save()
    }
}
#else
import SwiftUI
struct ScreenshotTextSize: ViewModifier { func body(content: Content) -> some View { content } }
#endif
