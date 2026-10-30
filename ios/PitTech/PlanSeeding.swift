import Foundation

/// One plan lane seeded from recorded history: the dish as written plus
/// the duration schedule its recorded cook supports.
struct SeededPlanLane: Equatable {
    var dish: PlaybookDishDraft
    var schedule: DishSchedule
}

/// A saved recipe's row in the editor's "Add from a recipe" picker: the
/// seeded lane when the recipe's history supports one, else the honest
/// no-seed note.
struct PlanSeedOption: Equatable, Identifiable {
    var recipeId: String
    var revisionId: String
    var title: String
    var lane: SeededPlanLane?
    var note: String

    var id: String { revisionId }
    static let noSeedNote = "No recorded cook to seed durations from — add the dish by hand instead."
}

/// Duration seeding (Flowing §4.3/§4.4): a plan lane earns real numbers
/// only from a recorded cook. Two honest sources, in order — the recipe's
/// own provenance (the cook the user made it from), or the frozen story a
/// saved public recipe carries. A recipe with neither seeds nothing: the
/// lane waits for numbers the cook types. Mirrors Android's PlanSeeding.
enum PlanSeeding {
    /// The snapshot's one dish in playbook form (the snapshot draft is the
    /// same portable dish vocabulary the playbook codec reads).
    static func playbookDish(_ snapshot: RecipeSnapshot) -> PlaybookDishDraft? {
        guard let dish = snapshot.draft.objectValue?["dishes"]?.arrayValue?.first?.objectValue,
              let decoded = try? PlaybookCodec.dish(dish) else { return nil }
        return decoded
    }

    /// §4.3: the source cook's total time on the pit becomes the lane's
    /// range (min = max — one recorded cook supports one number), with
    /// the recorded rest when the cook recorded one.
    static func fromRecipe(snapshot: RecipeSnapshot, provenance: RecipeProvenance?) -> SeededPlanLane? {
        guard let provenance, let totalValue = provenance.totalMinutes else { return nil }
        let total = Int64(totalValue.rounded())
        guard total > 0, let dish = playbookDish(snapshot) else { return nil }
        let rest = Int64(max(0, (provenance.restMinutes ?? 0).rounded()))
        var note = "Seeded from your \(provenance.cookedDateText) cook — \(formatDuration(total)) on the pit"
        if provenance.restMinutes != nil { note += ", \(formatDuration(rest)) rest" }
        note += " (one recorded cook; adjust if this one runs different)"
        return SeededPlanLane(
            dish: dish,
            schedule: DishSchedule(
                dishIndex: 0,
                cookMinMinutes: total,
                cookMaxMinutes: total,
                restMinutes: rest,
                pitTemperatureF: snapshot.preferredPitTemperatureF ?? provenance.setpointF,
                evidenceCount: 1,
                evidenceNote: note
            )
        )
    }

    /// A saved public recipe carries its frozen story: food-on to the last
    /// finish (dish_done, else remove) is the recorded duration, and the
    /// rest counts only when both its start and the finish were recorded.
    static func fromSnapshotStory(_ snapshot: RecipeSnapshot) -> SeededPlanLane? {
        guard let anchor = snapshot.smokingAnchorUtcMillis, let dish = playbookDish(snapshot) else { return nil }
        func offset(_ row: [String: JSONValue]) -> Int64? {
            if let offset = row["offsetMillis"]?.numberValue { return Int64(offset) }
            guard let at = row["occurredAtUtcMillis"]?.numberValue else { return nil }
            return Int64(at) - anchor
        }
        let observations = snapshot.observations.compactMap { $0.objectValue }
        func lastOffset(_ action: String) -> Int64? {
            observations.last { $0["action"]?.stringValue == action }.flatMap(offset)
        }
        let done = lastOffset("dish_done")
        guard let end = done ?? lastOffset("remove"), end > 0 else { return nil }
        let total = Int64((Double(end) / 60_000).rounded())
        guard total > 0 else { return nil }
        let restStart = lastOffset("rest_start")
        let rest: Int64 = if let restStart, let done {
            Int64((Double(max(0, done - restStart)) / 60_000).rounded())
        } else { 0 }
        var note = if let credit = snapshot.publicSource?.displayName {
            "Durations from \(credit)'s recorded cook — \(formatDuration(total)) on the pit"
        } else {
            "Seeded from this recipe's recorded cook — \(formatDuration(total)) on the pit"
        }
        if restStart != nil, done != nil { note += ", \(formatDuration(rest)) rest" }
        note += " (adjust anytime in the plan)"
        return SeededPlanLane(
            dish: dish,
            schedule: DishSchedule(
                dishIndex: 0,
                cookMinMinutes: total,
                cookMaxMinutes: total,
                restMinutes: rest,
                pitTemperatureF: snapshot.preferredPitTemperatureF,
                evidenceCount: 1,
                evidenceNote: note
            )
        )
    }

    /// "9 h 38 min" / "2 h" / "45 min" — the seeding notes' duration form.
    static func formatDuration(_ minutes: Int64) -> String {
        let hours = minutes / 60
        let remainder = minutes % 60
        if hours > 0, remainder > 0 { return "\(hours) h \(remainder) min" }
        if hours > 0 { return "\(hours) h" }
        return "\(remainder) min"
    }
}
