import Foundation

/// The one shared food taxonomy for PitTech publishing and Discover (owner
/// decision, 2026-10-27). Categories are a controlled dropdown, never free
/// text: a publication carries exactly one category id, the publication
/// contract validates it as an enum, and both apps render labels and picker
/// examples from the same list. The canonical list lives in
/// contracts/fixtures/food-categories-v1.json; a contract test pins this
/// native copy to that file, in order. Display order is alphabetical with
/// Other pinned last as the escape hatch.
struct FoodCategory: Equatable, Sendable {
    let id: String
    let label: String
    let examples: [String]

    static let all: [FoodCategory] = [
        FoodCategory(id: "apps-snacks", label: "Apps & Snacks", examples: ["Smoked cheese", "Queso", "Smoked nuts", "Deviled eggs", "Pig shots"]),
        FoodCategory(id: "beef", label: "Beef", examples: ["Brisket", "Beef ribs", "Steaks"]),
        FoodCategory(id: "chicken", label: "Chicken", examples: ["Whole chicken", "Wings", "Thighs"]),
        FoodCategory(id: "desserts", label: "Desserts", examples: ["Cobbler", "Pies"]),
        FoodCategory(id: "lamb-game", label: "Lamb & Game", examples: ["Lamb", "Venison", "Duck"]),
        FoodCategory(id: "pork", label: "Pork", examples: ["Pork ribs", "Boston butt", "Pork belly", "Ham"]),
        FoodCategory(id: "sausage-links", label: "Sausage & Links", examples: ["Brats", "Kielbasa", "Hot links"]),
        FoodCategory(id: "seafood", label: "Seafood", examples: ["Salmon", "Shrimp", "Fish"]),
        FoodCategory(id: "sides", label: "Sides", examples: ["Beans", "Mac & cheese", "Slaw", "Potatoes", "Cornbread"]),
        FoodCategory(id: "turkey", label: "Turkey", examples: ["Whole turkey", "Turkey breast"]),
        FoodCategory(id: "veggies", label: "Veggies", examples: ["Corn", "Peppers", "Cabbage"]),
        FoodCategory(id: "other", label: "Other", examples: ["Anything that does not fit the rest"]),
    ]

    /// Publications written before the taxonomy existed carry no category.
    /// They are treated as Other everywhere without rewriting stored data.
    static let defaultId = "other"

    static func isValidId(_ id: String?) -> Bool {
        guard let id else { return false }
        return all.contains { $0.id == id }
    }

    /// The id a value counts as: itself when it is in the taxonomy,
    /// otherwise the pre-taxonomy default (Other). Read paths only —
    /// writes are validated strictly, never by this fallback.
    static func normalizedId(_ id: String?) -> String { isValidId(id) ? id! : defaultId }

    static func category(forId id: String) -> FoodCategory {
        all.first { $0.id == id } ?? all.first { $0.id == defaultId }!
    }

    static func label(forId id: String) -> String { category(forId: id).label }

    /// Pre-selection heuristic: maps a dish's free-text food type to the
    /// category the publish picker starts on. It only pre-selects — the
    /// user can always change the pick before publishing. Matching is
    /// case-insensitive substring, first rule wins, in this order:
    /// beef, pork, chicken, turkey, poultry (chicken), seafood words,
    /// game words, sausage, vegetable words, dessert, side, snack words;
    /// anything else is Other.
    static func suggestedId(forFoodType foodType: String) -> String {
        let text = foodType.lowercased()
        if text.contains("beef") { return "beef" }
        if text.contains("pork") { return "pork" }
        if text.contains("chicken") { return "chicken" }
        if text.contains("turkey") { return "turkey" }
        if text.contains("poultry") { return "chicken" }
        if text.contains("seafood") || text.contains("fish") || text.contains("salmon") || text.contains("shrimp") { return "seafood" }
        if text.contains("wild game") || text.contains("game") || text.contains("lamb") || text.contains("venison") || text.contains("duck") { return "lamb-game" }
        if text.contains("sausage") { return "sausage-links" }
        if text.contains("vegetable") || text.contains("veggie") { return "veggies" }
        if text.contains("dessert") { return "desserts" }
        if text.contains("side") { return "sides" }
        if text.contains("snack") || text.contains("appetizer") { return "apps-snacks" }
        return defaultId
    }
}
