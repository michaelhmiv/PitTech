import Foundation

/// PL-13 (#172): the Start Cook setup on iOS — the same fields, in the
/// same order, as Android's Start Cook review (which is the long-standing
/// canonical form): Source · Current stage · Cook name · Dishes · Smoker ·
/// Starting setpoint · Cook notes · More cook details (fuel · wood blend ·
/// outdoor temperature · weather · wind). One setup value drives the blank
/// start, every sourced start (PL-10: Recipes, meal combinations, shared
/// Cook Packs), and the kept setup draft, so the platforms cannot drift
/// apart field by field again.
///
/// Equality is field-by-field and ignores `id`: the sheet compares the
/// working setup against the setup it opened with to know whether leaving
/// needs the kept-draft conversation at all.
struct CookSetupDish: Codable, Equatable, Identifiable {
    var id: String = UUID().uuidString.lowercased()
    var name: String
    var foodType: String = "Other"
    var cut: String = ""
    var weightText: String = ""
    var weightUnit: String = "lb"
    /// Flowing §4.2: the rub named at setup, when the cook names one.
    /// Optional so kept setup drafts saved before this field existed
    /// still decode; nil falls back to the recipe's rub ingredient.
    var rub: String? = nil
    /// The saved-recipe revision this dish attaches from, when it has one.
    /// Sourced dishes are reviewed (and removable), not re-typed.
    var revisionId: String? = nil
    /// A meal combination's working copy, applied over the revision when
    /// the cook starts — the same fidelity the direct preset start had.
    var snapshotJSON: String? = nil

    var detailLine: String {
        [foodType, cut, weightText.isEmpty ? "" : "\(weightText) \(weightUnit)"]
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }
}

struct CookSetupDraft: Codable, Identifiable {
    var id: String = UUID().uuidString.lowercased()
    /// DD-11: where this setup came from, shown on the review's source
    /// card. Nil for a blank start.
    var sourceLabel: String? = nil
    var title: String = ""
    var alreadyCooking: Bool = false
    var foodOn: Date = .now
    var dishes: [CookSetupDish] = []
    var smokerName: String = ""
    var setpointText: String = ""
    var setpointUnit: String = "°F"
    var notes: String = ""
    var fuelType: String = ""
    var woodBlend: String = ""
    var outdoorTemperatureText: String = ""
    var outdoorTemperatureUnit: String = "°F"
    var weather: String = ""
    var wind: String = ""

    static func blank(temperatureUnit: String, weightUnit: String) -> CookSetupDraft {
        // weightUnit is accepted for parity with the Android blank draft;
        // a blank setup has no dishes yet, so nothing consumes it today.
        _ = weightUnit
        return CookSetupDraft(setpointUnit: temperatureUnit, outdoorTemperatureUnit: temperatureUnit)
    }

    /// The canonical field order both platforms present, asserted by tests
    /// so a future field lands in the same place on Android and iOS.
    static let fieldOrder = [
        "source", "stage", "title", "dishes", "smoker", "setpoint", "notes",
        "fuel", "wood", "outdoorTemperature", "weather", "wind",
    ]
}

extension CookSetupDraft: Equatable {
    static func == (lhs: CookSetupDraft, rhs: CookSetupDraft) -> Bool {
        lhs.sourceLabel == rhs.sourceLabel && lhs.title == rhs.title &&
        lhs.alreadyCooking == rhs.alreadyCooking && lhs.foodOn == rhs.foodOn &&
        lhs.dishes == rhs.dishes && lhs.smokerName == rhs.smokerName &&
        lhs.setpointText == rhs.setpointText && lhs.setpointUnit == rhs.setpointUnit &&
        lhs.notes == rhs.notes && lhs.fuelType == rhs.fuelType &&
        lhs.woodBlend == rhs.woodBlend &&
        lhs.outdoorTemperatureText == rhs.outdoorTemperatureText &&
        lhs.outdoorTemperatureUnit == rhs.outdoorTemperatureUnit &&
        lhs.weather == rhs.weather && lhs.wind == rhs.wind
    }
}

/// The food types the dish editor offers — the same list as Android's.
let cookSetupFoodTypes = ["Meat", "Beef", "Pork", "Poultry", "Seafood", "Wild game", "Vegetables", "Pasta", "Sides", "Dairy", "Baked dishes", "Desserts", "Other"]

enum CookSetupError: LocalizedError {
    case invalidFoodOnTime
    var errorDescription: String? {
        switch self {
        case .invalidFoodOnTime: return "Choose the actual Food on time, up to now."
        }
    }
}
