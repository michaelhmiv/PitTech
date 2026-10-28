import Foundation

/// Structured cook-input capture (Flowing spec §4.2): the experiment's
/// inputs — wrap material, rub, spritz — recorded as data so compare
/// (Spread 05) has something to diff. Stored as rows in the existing
/// results archive table; absence of a row means NOT RECORDED, a
/// first-class state that is never backfilled by inference from notes.
/// Mirrors Android's CookInputs rule for rule.
enum WrapMaterial: String, Equatable {
    case none = "none"
    case foil = "foil"
    case butcherPaper = "butcher_paper"

    var label: String {
        switch self {
        case .none: return "No wrap"
        case .foil: return "Foil"
        case .butcherPaper: return "Butcher paper"
        }
    }

    static func fromArchive(_ value: String?) -> WrapMaterial? {
        value.flatMap { WrapMaterial(rawValue: $0) }
    }
}

enum SpritzState: Equatable {
    case notRecorded
    case no
    case yes
}

struct CookInputs: Equatable {
    var wrapMaterial: WrapMaterial?
    var rub: String?
    var spritz: SpritzState = .notRecorded
    var spritzMedium: String?

    static let notRecorded = CookInputs()
}

/// One results-table row worth of capture data (the store assigns ids).
struct CookInputRow: Equatable {
    let resultType: String
    let textValue: String?
}

enum CookInputVocabulary {
    static let typeWrapMaterial = "wrap_material"
    static let typeRub = "rub"
    static let typeSpritz = "spritz"
    static let typeSpritzMedium = "spritz_medium"
    static let notRecordedLabel = "Not recorded"

    /// The rows that persist `inputs` for one scope; a blank/absent field deletes its row.
    static func rows(_ inputs: CookInputs) -> [CookInputRow] {
        func trimmed(_ s: String?) -> String? {
            guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
            return t
        }
        return [
            CookInputRow(resultType: typeWrapMaterial, textValue: inputs.wrapMaterial?.rawValue),
            CookInputRow(resultType: typeRub, textValue: trimmed(inputs.rub)),
            CookInputRow(resultType: typeSpritz, textValue: inputs.spritz == .no ? "no" : inputs.spritz == .yes ? "yes" : nil),
            CookInputRow(resultType: typeSpritzMedium, textValue: trimmed(inputs.spritzMedium)),
        ]
    }

    /// Read capture back from result rows. Dish-scoped rows win over
    /// cook-scoped rows field by field. `hasSpritzEvent` is the timeline's
    /// word: a logged spritz action means YES even when nobody answered
    /// the medium prompt — the event is data, not inference.
    static func fromResults(_ rows: [InsightResultInput], cookId: String, dishId: String?, hasSpritzEvent: Bool = false) -> CookInputs {
        let scoped = rows.filter { $0.cookId == cookId }
        func pick(_ type: String) -> InsightResultInput? {
            let matches = scoped.filter { $0.resultType == type }
            return matches.first { $0.dishId == dishId } ?? matches.first { $0.dishId == nil }
        }
        let spritzRow = pick(typeSpritz)?.textValue
        let spritz: SpritzState = spritzRow == "no" ? .no : (spritzRow == "yes" || hasSpritzEvent) ? .yes : .notRecorded
        return CookInputs(
            wrapMaterial: WrapMaterial.fromArchive(pick(typeWrapMaterial)?.textValue),
            rub: pick(typeRub)?.textValue.flatMap { $0.isEmpty ? nil : $0 },
            spritz: spritz,
            spritzMedium: pick(typeSpritzMedium)?.textValue.flatMap { $0.isEmpty ? nil : $0 })
    }

    /// The prompt-time pre-fill for a wrap: a material named by the recipe's
    /// or plan's wrap instruction, as whole words. This only preselects a
    /// chip the cook confirms with the save — it never writes data itself.
    static func wrapMaterialHint(texts: [String]) -> WrapMaterial? {
        let joined = texts.joined(separator: " ").lowercased()
        func matches(_ pattern: String) -> Bool {
            joined.range(of: pattern, options: .regularExpression) != nil
        }
        if matches(#"\bno wrap|\bunwrapped\b|\bnever wrap"#) { return .none }
        if matches(#"\bbutcher paper\b|\bpeach paper\b|\bpink paper\b|\bpaper\b"#) { return .butcherPaper }
        if matches(#"\bfoil\b"#) { return .foil }
        return nil
    }

    /// The rub a recipe names for a dish, from its staged ingredients.
    static func rubFromIngredients(_ ingredients: [NoteIngredient]) -> String? {
        ingredients.first { $0.stage.lowercased() == "rub" }
            .map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines) }
            .flatMap { $0.isEmpty ? nil : $0 }
    }
}
